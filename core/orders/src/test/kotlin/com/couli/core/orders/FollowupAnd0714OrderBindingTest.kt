package com.couli.core.orders

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * FU-AND-0714 第 3、5 项（F-AND-25 / F-AND-29）：订单缓存与订单页恢复记录绑定「账号 + 令牌存储里的持久化登录世代」。
 * - 目录外标记、目录内标记、删除三者同时失败（整体存储故障）→ 同账号重新登录（登录世代换新）→ 进程重建（新实例，内存标记丢失）→ 不读回；
 * - 失败写入其实已落盘（被同命名空间的 flush 顺带写下、或写后报错）→ 重新登录后不恢复；
 * - 同一登录世代、无故障（含中间刷新令牌：世代沿用）照常读回 / 恢复；登录世代读不出（抛错、null、空串）、目录外标记读不出都按未知，不读、不恢复。
 * 登录世代这里用一个可变值代替令牌存储（令牌存储里的原子写入见 core/network 的 FollowupAnd0714LoginEpochTest）。
 */
class FollowupAnd0714OrderBindingTest {
    private val base: File = Files.createTempDirectory("fuand0714-orders").toFile()
    private val cacheRoot = File(base, "cache")
    private val restoreRoot = File(base, "restore")

    @Volatile private var loginEpoch: String? = "login-1"

    private val epochSource: () -> String? = { loginEpoch }

    /** 目录外标记（模拟 SharedPreferences commit）：[commitOk] 为 false 时写不进；[readFails] 时读取抛错。 */
    private class Flag(
        var commitOk: Boolean = true,
        var readFails: Boolean = false,
    ) : OrderStaleCache.PendingClearFlag {
        var value = false

        override fun isSet(): Boolean {
            if (readFails) throw IOException("flag unreadable")
            return value
        }

        override fun set(): Boolean {
            if (commitOk) value = true
            return commitOk
        }

        override fun clear(): Boolean {
            if (commitOk) value = false
            return commitOk
        }
    }

    private fun item(id: String) =
        OrderSummary(id, "taobao", "样例商品", null, 4560, 1, "3741234567890123456", null, "PAID", null, 234, null, false)

    private fun detail(id: String) = OrderDetail(item(id), "tb:sampleA", emptyList(), emptyList(), null, false, false, false)

    // FU-AND-0717（F-X-17）：存储不再自己读「此刻的世代」，由调用方给出固定世代；这里每次按 [loginEpoch] 的当前值给（等同原来的来源）
    private fun cache(flag: OrderStaleCache.PendingClearFlag) = OrderStaleCache.Store(cacheRoot, flag).apply { partitionByLoginEpoch() }

    private val record = OrderRestoreRecord(owner = "owner-a", platform = "taobao", q = ORDER_NO, input = ORDER_NO, anchorOrderId = "o1")

    private fun restore(
        flag: OrderStaleCache.PendingClearFlag,
        deletable: () -> Boolean = { true },
        writer: (File, String) -> Unit = { f, t ->
            f.parentFile?.mkdirs()
            f.writeText(t)
        },
        bound: Boolean = true,
    ) = OrderRestoreStore(restoreRoot, flag, { f -> deletable() && f.deleteRecursively() && !f.exists() }, writer).apply {
        if (bound) bindLoginEpoch(epochSource)
    }

    // ---------------------------------------------------------------- 订单缓存

    @Test
    fun fand25_cache_flag_marker_and_delete_all_fail_relogin_same_account_rebuilt_store_never_reads_back() {
        val flag = Flag(commitOk = false)
        val before = cache(flag)
        before.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, loginEpoch)
        before.writeDetail("user-a", detail("d1"), 1L, loginEpoch)
        val accountDir = cacheRoot.listFiles()!!.first { it.isDirectory }
        cacheRoot.setWritable(false)
        accountDir.setWritable(false)
        try {
            assumeTrue("本机能模拟目录不可写（非 root 运行）", runCatching { before.clearAll() }.isFailure)
            assertFalse("前提：目录外标记没写进", flag.value)
            assertFalse("前提：目录内标记也没写进", File(cacheRoot, "pending_clear").exists())
            assertTrue("前提：旧订单仍在盘上", cacheRoot.walkTopDown().any { it.isFile && it.name == "list_self.json" })
            // 同一账号重新登录：令牌存储换新的持久化登录世代；进程重建：新实例，内存里的待清理丢失
            loginEpoch = "login-2"
            val rebuilt = cache(flag)
            assertNull("不读回旧列表", rebuilt.readList("user-a", "self", loginEpoch))
            assertNull("不读回旧详情", rebuilt.readDetail("user-a", "d1", loginEpoch))
        } finally {
            accountDir.setWritable(true)
            cacheRoot.setWritable(true)
        }
        // 故障解除：下一次归属核对把旧登录世代的缓存整体删掉
        val healed = cache(Flag())
        healed.reconcileOwner("user-a", loginEpoch)
        assertTrue("旧登录世代的缓存已删", cacheRoot.walkTopDown().none { it.isFile && it.name == "list_self.json" })
        assertNull(healed.readList("user-a", "self", loginEpoch))
    }

    @Test
    fun fand25_cache_same_login_epoch_reads_back_and_unknown_epoch_or_flag_reads_nothing() {
        val flag = Flag()
        val store = cache(flag)
        store.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, loginEpoch)
        assertNotNull("同一登录世代（重建实例也一样）照常读回", cache(flag).readList("user-a", "self", loginEpoch))
        loginEpoch = null
        assertNull("登录世代读不出：不读", store.readList("user-a", "self", loginEpoch))
        store.writeList("user-a", "self", OrderListData(listOf(item("o2")), null, null), 2L, loginEpoch)
        store.reconcileOwner("user-a", loginEpoch)
        loginEpoch = "login-1"
        assertEquals(
            "世代未知期间不写、不按归属删",
            listOf("o1"),
            store
                .readList("user-a", "self", loginEpoch)!!
                .data.items
                .map { it.orderId },
        )
        // 调用方给不出世代（快照未知 → null）、空串：不读
        assertNull("登录世代未知：不读", cache(flag).readList("user-a", "self", null))
        assertNull(cache(flag).readList("user-a", "self", ""))
        flag.readFails = true
        assertNull("目录外标记读不出按待清理：先清理，不读回", cache(flag).readList("user-a", "self", loginEpoch))
    }

    // ---------------------------------------------------------------- 订单页恢复记录（F-AND-29）

    @Test
    fun fand29_restore_flag_marker_and_delete_all_fail_relogin_same_account_rebuilt_store_not_restored() {
        val flag = Flag(commitOk = false)
        var deletable = true
        val first = restore(flag, { deletable })
        val ref = first.newRef()
        assertTrue(first.write(ref, first.epoch, record, first.loginEpochNow()))
        assertEquals("同一登录世代照常恢复", record, first.read(ref))
        deletable = false
        restoreRoot.setWritable(false)
        try {
            assumeTrue("本机能模拟目录不可写（非 root 运行）", runCatching { first.clearAll() }.isFailure)
            assertFalse(flag.value)
            assertFalse(File(restoreRoot, "pending_clear").exists())
            assertTrue("前提：记录仍在盘上", restoreRoot.walkTopDown().any { it.isFile && it.readText().contains(ORDER_NO) })
            loginEpoch = "login-2"
            val rebuilt = restore(flag, { deletable })
            assertNull("同账号重新登录 + 重建：不恢复旧记录", rebuilt.read(ref))
            // 对照（HEAD 的口径：没有归属世代）：同样的盘面会恢复出旧搜索词
            assertNotNull(restore(flag, { deletable }, bound = false).read(ref))
        } finally {
            restoreRoot.setWritable(true)
        }
    }

    @Test
    fun fand29_failed_write_that_actually_landed_is_not_restored_after_relogin() {
        val flag = Flag(commitOk = false)
        // 写入「失败」：内容其实已落盘（被同命名空间的 flush 顺带写下 / 写后报错）
        val landed =
            restore(flag, writer = { f, t ->
                f.parentFile?.mkdirs()
                f.writeText(t)
                throw IOException("reported as failed")
            })
        val ref = landed.newRef()
        assertFalse("调用方看到的是写失败", landed.write(ref, landed.epoch, record, landed.loginEpochNow()))
        assertTrue("前提：内容在盘上", File(restoreRoot, "$ref.json").exists())
        // 标记、目录内标记与删除同时失败的清理；然后同账号重新登录
        restoreRoot.setWritable(false)
        try {
            assumeTrue(runCatching { restore(flag, { false }).clearAll() }.isFailure)
        } finally {
            restoreRoot.setWritable(true)
        }
        loginEpoch = "login-2"
        assertNull("重启后不恢复", restore(flag, { false }).read(ref))
    }

    @Test
    fun fand29_unknown_login_epoch_or_epoch_changed_since_page_bound_neither_restores_nor_writes() {
        val flag = Flag()
        val store = restore(flag)
        val ref = store.newRef()
        val pageEpoch = store.loginEpochNow()
        assertEquals("login-1", pageEpoch)
        assertTrue(store.write(ref, store.epoch, record, pageEpoch))
        loginEpoch = "login-1"
        assertEquals("刷新令牌不换登录世代：照常恢复", record, restore(flag).read(ref))
        loginEpoch = null
        assertNull("登录世代读不出：不恢复", store.read(ref))
        assertFalse("登录世代读不出：不写", store.write(ref, store.epoch, record, pageEpoch))
        loginEpoch = "login-2"
        assertFalse("页面固定的世代已不是当前的（同账号重新登录、清理通知还没到）：不写", store.write(ref, store.epoch, record, pageEpoch))
        val throwing = restore(flag).apply { bindLoginEpoch { error("x") } }
        val rebuilt = OrderRestoreStore(restoreRoot, flag).apply { bindLoginEpoch { error("token store unreadable") } }
        assertNull(rebuilt.read(ref))
        assertNull(throwing.read(ref))
        flag.readFails = true
        assertNull("目录外标记读不出：按待清理", restore(flag).read(ref))
    }

    private companion object {
        const val ORDER_NO = "3741234567890123456"
    }
}
