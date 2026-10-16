package com.couli.core.orders

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FU-AND-0717 F-X-17 / F-X-19 补充二：订单页恢复记录的写入与恢复决策只按页面固定身份那一份快照的登录世代（[OrderRestoreStore.bindingFor]）。
 * - 交错：页面固定于 E1，写入已通过存储里的核对、正在落盘时同账号重新登录（E2）——记录只按 E1 绑定，E2 的页面（同一实例、重建实例）都恢复不出它；
 * - 恢复决策用快照里的世代读（[OrderRestoreStore.read] 带绑定），不另读此刻的世代：快照未知不恢复，也不删。
 */
class FollowupAnd0717RestoreEpochTest {
    private val root: File = Files.createTempDirectory("fuand0717-restore").toFile()

    @Volatile private var current: String? = "login-1"

    private val record =
        OrderRestoreRecord(owner = "owner-a", q = "3741234567890123456", input = "3741234567890123456", anchorOrderId = "o1")

    @Test
    fun fuand0717_x17_aRecordWrittenAcrossASameAccountReloginIsBoundToTheOldLoginAndNotRestoredByTheNewOne() {
        val store =
            OrderRestoreStore(root, OrderStaleCache.SiblingFileFlag(root), { it.deleteRecursively() && !it.exists() }) { file, text ->
                // 交错点：存储里的核对已通过、落盘的这一刻同账号重新登录
                current = "login-2"
                file.parentFile?.mkdirs()
                file.writeText(text)
            }.apply { bindLoginEpoch { current } }
        val ref = store.newRef()
        val pageBinding = store.bindingFor("login-1")
        store.write(ref, store.epoch, record, pageBinding)
        assertEquals("前提：已是新世代", "login-2", current)
        assertEquals("前提：记录已落盘", true, File(root, "$ref.json").exists())
        val rebuilt = OrderRestoreStore(root).apply { bindLoginEpoch { current } }
        assertNull("重建实例（进程重建，此刻世代 E2）恢复不出", rebuilt.read(ref, rebuilt.bindingFor("login-2")))
        assertNull("新世代的页面（快照 E2）恢复不出", store.read(ref, store.bindingFor("login-2")))
        assertFalse("读到旧世代的记录即删除（不保留）", File(root, "$ref.json").exists())
    }

    @Test
    fun fuand0717_x19_aRestoreDecisionUsesTheSnapshotBindingAndAnUnknownSnapshotNeitherRestoresNorDeletes() {
        val store = OrderRestoreStore(root).apply { bindLoginEpoch { current } }
        val ref = store.newRef()
        store.write(ref, store.epoch, record, store.bindingFor("login-1"))
        // 此刻存储读得出 login-1，但页面快照是未知：不恢复、不删
        assertNull(store.read(ref, store.bindingFor(null)))
        assertEquals("快照未知不删记录", true, File(root, "$ref.json").exists())
        // 快照已知（与写入同一世代）：恢复
        assertEquals(record, store.read(ref, store.bindingFor("login-1")))
    }
}
