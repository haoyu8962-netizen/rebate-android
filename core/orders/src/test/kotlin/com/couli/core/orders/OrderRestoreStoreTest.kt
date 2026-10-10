package com.couli.core.orders

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 订单页恢复记录的落盘（FU-AND-0710 R1-03 / R2-02，APP-08 共性第 40、45 条）：个人数据放在能确认写入与删除结果的文件里；清理失败（删不掉、
 * 目录外标记 commit 失败）保留可跨进程的待清理状态，进程重建后读不出、写不进，删除恢复后补清理完成。
 */
class OrderRestoreStoreTest {
    private val root: File = File(Files.createTempDirectory("orders-restore").toFile(), "restore")
    private val record =
        OrderRestoreRecord(
            owner = "owner-a",
            platform = "taobao",
            month = "2026-09",
            q = ORDER_NO,
            input = ORDER_NO,
            anchorOrderId = "ord-1",
        )

    /** 目录外标记（模拟 SharedPreferences commit）：[commitOk] 为 false 时写不进。 */
    private class Flag(
        var commitOk: Boolean = true,
    ) : OrderStaleCache.PendingClearFlag {
        var value = false

        override fun isSet(): Boolean = value

        override fun set(): Boolean {
            if (commitOk) value = true
            return commitOk
        }

        override fun clear(): Boolean {
            if (commitOk) value = false
            return commitOk
        }
    }

    private fun store(
        flag: OrderStaleCache.PendingClearFlag,
        deletable: Boolean = true,
    ) = OrderRestoreStore(root, flag, { f -> deletable && f.deleteRecursively() && !f.exists() }, { f, t ->
        f.parentFile?.mkdirs()
        f.writeText(t)
    })

    private fun filesWithOrderNo(): List<File> = root.walkTopDown().filter { it.isFile && it.readText().contains(ORDER_NO) }.toList()

    @Test
    fun recordRoundTripsAndOnlyTheCurrentEpochCanWrite() {
        val flag = Flag()
        val s = store(flag)
        val ref = s.newRef()
        assertTrue(s.write(ref, s.epoch, record))
        assertEquals(record, s.read(ref))
        val stale = s.epoch
        s.clearAll()
        assertNull("清理后读不出", s.read(ref))
        assertFalse("清理之前取得的代次写不进", s.write(ref, stale, record))
        assertTrue(filesWithOrderNo().isEmpty())
        assertTrue("新代次照常", s.write(ref, s.epoch, record))
        assertFalse("引用必须是随机 id", s.write("../x", s.epoch, record))
    }

    /** 删除失败 → 抛错上报、保留目录外标记 → 进程重建（新实例，内存状态丢失）→ 读不出、写不进 → 删除恢复后补清理完成。 */
    @Test
    fun deleteFailureIsReportedAndSurvivesProcessRebuild() {
        val flag = Flag()
        val first = store(flag, deletable = false)
        val ref = first.newRef()
        assertTrue(first.write(ref, first.epoch, record))
        try {
            first.clearAll()
            fail("删除失败必须上报")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
        assertTrue("前提：记录仍在盘上", filesWithOrderNo().isNotEmpty())
        assertTrue("目录外待清理已落盘", flag.value)
        // 进程重建：新实例，删除仍失败
        val rebuilt = store(flag, deletable = false)
        assertNull("待清理期间读不出", rebuilt.read(ref))
        assertFalse("待清理期间写不进", rebuilt.write(ref, rebuilt.epoch, record))
        // 删除恢复：下一次读取先补清理
        val healed = store(flag)
        assertNull(healed.read(ref))
        assertTrue("补清理删掉了记录", filesWithOrderNo().isEmpty())
        assertFalse("补清理完成后去掉标记", flag.value)
        assertTrue(healed.write(ref, healed.epoch, record))
    }

    /** 目录外标记 commit 失败 + 删除失败 → 进程重建后靠目录内标记仍判为待清理，读不出旧记录。 */
    @Test
    fun commitFailureAndDeleteFailureStillBlockAfterProcessRebuild() {
        val flag = Flag(commitOk = false)
        val first = store(flag, deletable = false)
        val ref = first.newRef()
        assertTrue(first.write(ref, first.epoch, record))
        assertTrue(runCatching { first.clearAll() }.isFailure)
        assertFalse("前提：目录外标记没写进", flag.value)
        val rebuilt = store(flag, deletable = false)
        assertNull("目录内标记仍在：读不出", rebuilt.read(ref))
        flag.commitOk = true
        val healed = store(flag)
        assertNull(healed.read(ref))
        assertTrue(filesWithOrderNo().isEmpty())
    }

    @Test
    fun retainOnlyDropsOrphansAndCorruptRecordsReadAsNothing() {
        val s = store(Flag())
        val keep = s.newRef()
        val orphan = s.newRef()
        s.write(keep, s.epoch, record.copy(q = null, input = ""))
        s.write(orphan, s.epoch, record)
        s.retainOnly(keep)
        assertNull(s.read(orphan))
        assertTrue(filesWithOrderNo().isEmpty())
        File(root, "$keep.json").writeText("{bad")
        assertNull(s.read(keep))
        assertFalse(File(root, "$keep.json").exists())
        // 根目录不存在时清理照常成功
        root.deleteRecursively()
        s.clearAll()
    }

    private companion object {
        const val ORDER_NO = "3741234567890123456"
    }
}
