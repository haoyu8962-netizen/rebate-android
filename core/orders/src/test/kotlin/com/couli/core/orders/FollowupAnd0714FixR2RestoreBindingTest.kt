package com.couli.core.orders

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0714 第 2 轮评审 R2-04：订单页恢复记录的迟到写入不得用当前登录世代给「未知」的旧页面补绑。
 * - 页面固定身份时登录世代读不出（[OrderRestoreStore.LoginBinding.Unknown]）→ 世代之后恢复可读、再换登录，这份页面的写入一律拒绝，盘上没有记录；
 * - 「没接来源」与「绑定时读不出」分开：存储接了来源后，没有绑定（字符串简写的 null）的写入同样拒绝；
 * - 对照：已知且相等的绑定照常写入、恢复。
 */
class FollowupAnd0714FixR2RestoreBindingTest {
    private val root: File = Files.createTempDirectory("fuand0714-r2-04").toFile()

    @Volatile private var loginEpoch: String? = null

    private val record = OrderRestoreRecord(owner = "owner-a", platform = "taobao", q = ORDER_NO, input = ORDER_NO, anchorOrderId = "o1")

    private fun store(bound: Boolean = true) =
        OrderRestoreStore(root, OrderStaleCache.SiblingFileFlag(root)).apply { if (bound) bindLoginEpoch { loginEpoch } }

    private fun records(): List<File> = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }

    @Test
    fun r2_04_page_bound_with_unknown_epoch_never_writes_after_the_epoch_recovers_or_after_relogin() {
        val store = store()
        val ref = store.newRef()
        // 页面固定身份时：令牌存储读不出（会话不可用 / 隔离 / 撤销）
        val binding = store.loginBindingNow()
        assertEquals("绑定时读取失败（不是没接来源）", OrderRestoreStore.LoginBinding.Unknown, binding)
        // 世代恢复可读：未知的旧页面不补绑当前世代
        loginEpoch = "login-1"
        assertFalse("未知绑定：世代恢复后也拒写", store.write(ref, store.epoch, record, binding))
        assertFalse("没有绑定（null 简写）：接了来源就拒写，不用当前世代补绑", store.write(ref, store.epoch, record, null as String?))
        assertTrue("盘上没有记录", records().isEmpty())
        assertNull(store.read(ref))
        // 换登录（同账号重新登录）之后的迟到写入：同样拒绝；新世代下也恢复不出任何东西
        loginEpoch = "login-2"
        assertFalse("换登录后的迟到写入：拒绝", store.write(ref, store.epoch, record, binding))
        assertFalse(store.write(ref, store.epoch, record, null as String?))
        assertTrue(records().isEmpty())
        assertNull(store().read(ref))

        // 对照：已知且相等的绑定照常写入、恢复；世代变了就拒绝
        val known = store.loginBindingNow()
        assertEquals(OrderRestoreStore.LoginBinding.Known("login-2"), known)
        assertTrue(store.write(ref, store.epoch, record, known))
        assertEquals(record, store().read(ref))
        loginEpoch = "login-3"
        assertFalse(store.write(ref, store.epoch, record.copy(q = "late"), known))
    }

    @Test
    fun r2_04_unbound_source_and_unknown_binding_are_distinct() {
        // 没接来源（旧用法）：照旧写、照旧读（不带 gen）
        val legacy = store(bound = false)
        val legacyRef = legacy.newRef()
        assertEquals(OrderRestoreStore.LoginBinding.Unbound, legacy.loginBindingNow())
        assertTrue(legacy.write(legacyRef, legacy.epoch, record, legacy.loginBindingNow()))
        assertEquals(record, legacy.read(legacyRef))
        // 取绑定时还没接来源、之后接上了：这份「没绑定」的页面不补绑
        val unboundBinding = OrderRestoreStore.LoginBinding.Unbound
        loginEpoch = "login-1"
        legacy.bindLoginEpoch { loginEpoch }
        val ref = legacy.newRef()
        assertFalse("取绑定时没接来源、写入时接上了：拒写", legacy.write(ref, legacy.epoch, record, unboundBinding))
        assertTrue(legacy.write(ref, legacy.epoch, record, legacy.loginBindingNow()))
        assertEquals(record, legacy.read(ref))
    }

    private companion object {
        const val ORDER_NO = "3741234567890123456"
    }
}
