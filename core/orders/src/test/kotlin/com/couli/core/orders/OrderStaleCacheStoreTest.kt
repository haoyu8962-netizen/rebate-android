package com.couli.core.orders

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 断网缓存的落盘（G10）：按账号隔离、最近 30 条详情、坏文件按没有缓存、删除失败保留待清理且不读回旧数据。 */
class OrderStaleCacheStoreTest {
    private val root: File = Files.createTempDirectory("orders-cache").toFile()
    private val store = OrderStaleCache.Store(root)

    private fun item(id: String) =
        OrderSummary(id, "taobao", "样例商品", null, 4560, 1, "3741234567890123456", null, "PAID", null, 234, null, false)

    private fun detail(id: String) = OrderDetail(item(id), "tb:sampleA", emptyList(), emptyList(), null, false, false, false)

    @Test
    fun listIsIsolatedPerAccountAndOtherAccountsAreWipedOnWrite() {
        store.writeList("user-a", "self", OrderListData(listOf(item("o1")), "next", null), 1L)
        val cached = store.readList("user-a", "self")
        assertNotNull(cached)
        assertEquals(listOf("o1"), cached!!.data.items.map { it.orderId })
        assertNull("缓存不能翻页", cached.data.nextCursor)
        assertNull("别的账号读不到", store.readList("user-b", "self"))
        store.writeList("user-b", "self", OrderListData(listOf(item("o2")), null, null), 2L)
        assertNull("换账号写入前删掉旧账号的缓存", store.readList("user-a", "self"))
        assertFalse("目录名不落明文账号", root.walkTopDown().any { it.name.contains("user-b") })
    }

    @Test
    fun detailsKeepTheLatest30() {
        repeat(OrderStaleCache.DETAIL_LIMIT + 5) { store.writeDetail("user-a", detail("d$it"), it.toLong()) }
        assertNull(store.readDetail("user-a", "d0"))
        assertNotNull(store.readDetail("user-a", "d34"))
        assertNotNull(store.readDetail("user-a", "d5"))
    }

    @Test
    fun reconcileOwnerClearsOnLogoutOrSwitch() {
        store.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        store.reconcileOwner("user-a")
        assertNotNull(store.readList("user-a", "self"))
        store.reconcileOwner(null)
        assertNull(store.readList("user-a", "self"))
        assertTrue(root.walkTopDown().none { it.isFile && it.name.startsWith("list_") })
    }

    @Test
    fun corruptFileIsTreatedAsNoCacheAndDeleted() {
        store.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        val file = root.walkTopDown().first { it.name == "list_self.json" }
        file.writeText("{not json")
        assertNull(store.readList("user-a", "self"))
        assertFalse(file.exists())
    }

    @Test
    fun failedClearKeepsPendingMarkerAndNeverReadsOldData() {
        store.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        val dir = root.listFiles()!!.first { it.isDirectory }
        // 目录不可写：删不掉里面的文件
        dir.setWritable(false)
        try {
            val failed = runCatching { store.clearAll() }.isFailure
            if (failed) {
                assertTrue("保留待清理标记", File(root, "pending_clear").exists())
                assertNull("清理完成前不读回旧数据", store.readList("user-a", "self"))
            }
        } finally {
            dir.setWritable(true)
        }
        // 能删了：下一次读之前先完成清理
        assertNull(store.readList("user-a", "self"))
        assertFalse(File(root, "pending_clear").exists())
    }

    /**
     * R2-03（共性第 20 条）：已有缓存目录枚举失败（listFiles 返回 null：权限、I/O 故障）不能当成空目录——清理必须失败、保留待清理标记、期间
     * 不读回；目录恢复可读后重启（新的 Store 实例），读之前先完成清理，读不到清理前的订单。
     */
    @Test
    fun r2_03_unlistableDirectoryFailsTheClearAndKeepsPendingAcrossRestart() {
        var broken = false
        val flaky = OrderStaleCache.Store(root) { dir -> if (broken) null else dir.listFiles() }
        flaky.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        flaky.writeDetail("user-a", detail("d1"), 1L)
        broken = true
        assertTrue("目录枚举失败：清理不能报成功", runCatching { flaky.clearAll() }.isFailure)
        assertTrue("保留待清理标记", File(root, "pending_clear").exists())
        assertNull("待清理期间不读回", flaky.readList("user-a", "self"))
        assertNull("待清理期间不读回详情", flaky.readDetail("user-a", "d1"))
        // 目录恢复可读、App 重启：新实例先按标记完成清理再读
        broken = false
        val restarted = OrderStaleCache.Store(root)
        assertNull("重启后读不到清理前的列表", restarted.readList("user-a", "self"))
        assertNull("重启后读不到清理前的详情", restarted.readDetail("user-a", "d1"))
        assertFalse("确认全删后才清掉标记", File(root, "pending_clear").exists())
        assertTrue("缓存文件已全部删除", root.walkTopDown().none { it.isFile })
    }

    /**
     * R3-02（共性第 28 条）：退出时缓存目录局部故障——目录内的待清理标记与缓存删除同时失败 → 故障期间同账号重新登录（归属一致）→ 进程被杀、
     * 故障解除后重启（新 Store 实例，内存状态丢失）→ 断网读取：缓存目录之外的待清理状态仍在，读之前先完成清理，读不到退出前的订单。
     */
    @Test
    fun r3_02_markerAndDeleteBothFailThenSameAccountReloginAndRestartNeverReadsOldOrders() {
        val outside = MemoryFlag()
        val before = OrderStaleCache.Store(root, outside)
        before.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L)
        before.writeDetail("user-a", detail("d1"), 1L)
        val accountDir = root.listFiles()!!.first { it.isDirectory }
        // 故障：缓存根目录与账号目录都不可写（标记写不进、文件删不掉）
        root.setWritable(false)
        accountDir.setWritable(false)
        try {
            val failed = runCatching { before.clearAll() }.isFailure
            assumeTrue("本机能模拟目录不可写（非 root 运行）", failed)
            assertFalse("前提：目录内的标记也没写进去", File(root, "pending_clear").exists())
            assertTrue("缓存目录之外的待清理状态已记下", outside.value)
            // 故障期间同一账号重新登录（connectSession → reconcileOwner），再写入也写不进
            before.reconcileOwner("user-a")
            assertNull(before.readList("user-a", "self"))
        } finally {
            accountDir.setWritable(true)
            root.setWritable(true)
        }
        // 进程被杀、故障解除后重启：新实例只剩缓存目录之外的状态
        val restarted = OrderStaleCache.Store(root, outside)
        restarted.reconcileOwner("user-a")
        assertNull("重启后断网读不到退出前的列表", restarted.readList("user-a", "self"))
        assertNull("重启后断网读不到退出前的详情", restarted.readDetail("user-a", "d1"))
        assertTrue("旧文件已全部删除", root.walkTopDown().none { it.isFile })
        assertFalse("确认删完后才清掉待清理状态", outside.value)
    }

    /** 缓存目录之外的待清理状态（测试替身：跨 Store 实例保留 = 模拟 SharedPreferences 在重启后仍在）。 */
    private class MemoryFlag : OrderStaleCache.PendingClearFlag {
        var value = false

        override fun isSet(): Boolean = value

        override fun set(): Boolean {
            value = true
            return true
        }

        override fun clear(): Boolean {
            value = false
            return true
        }
    }

    /** 共性第 20 条：缓存根目录根本不存在（从没写过）不是枚举失败，清理照常成功。 */
    @Test
    fun clearingAMissingDirectorySucceeds() {
        val missing = OrderStaleCache.Store(File(root, "never-created"))
        missing.clearAll()
        assertNull(missing.readList("user-a", "self"))
    }
}
