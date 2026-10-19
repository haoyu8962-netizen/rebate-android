package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardSaltRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardReviewPersistence0724Test {
    @Test
    fun failedProductCommitCannotUploadAndRestartStillTreatsCandidateAsFirstSeen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProductionImportPreferences0722Harness(context).use { disk ->
            seed(disk.preferences)
            val before =
                disk.flaky.durable.all
                    .toMap()
            val failedUploads: Int
            ClipboardReview0724Harness(disk.preferences).use { h ->
                disk.flaky.failFromNextCommit()
                h.foreground()
                assertTrue("确实执行到摘要 commit=false", disk.flaky.failures > 0)
                assertNotNull("失败也更新进程内存，不能用读回当成功", disk.preferences.hashes.read(ClipboardRecords.KEY_PRODUCTS))
                assertEquals("磁盘仍是预存的盐与同意", before, disk.flaky.durable.all)
                failedUploads = h.requests.size // 结算后读取端口入口计数，先留证再验证重启。
            }
            ClipboardReview0724Harness(disk.restartFromDisk()).use { restarted ->
                restarted.foreground()
                assertEquals("重启只读原磁盘，仍应首次处理同一候选", 1, restarted.system.reads)
                assertEquals("写盘恢复后的首次处理恰好上传一次", 1, restarted.requests.size)
                assertTrue(restarted.board.result.value is ClipboardResult.CardList)
            }
            assertEquals("未可靠保存摘要的旧进程不得自动上传", 0, failedUploads)
        }
    }

    @Test
    fun successfulProductCommitUploadsOnceAndRestartWithin24HoursDeduplicates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProductionImportPreferences0722Harness(context).use { disk ->
            seed(disk.preferences)
            ClipboardReview0724Harness(disk.preferences).use { h ->
                h.foreground()
                assertEquals(1, h.requests.size)
                assertNotNull(disk.inspectDisk().hashes.read(ClipboardRecords.KEY_PRODUCTS))
            }
            ClipboardReview0724Harness(disk.restartFromDisk()).use { restarted ->
                restarted.foreground()
                assertEquals(1, restarted.system.reads)
                assertEquals("已落盘才允许重启后去重", 0, restarted.requests.size)
                assertNull(restarted.board.result.value)
            }
        }
    }

    private fun seed(prefs: ClipboardPreferences) {
        assertTrue(prefs.salt.write(SAMPLE_SALT))
        assertTrue(prefs.salt.read() is ClipboardSaltRead.Present)
        assertTrue(prefs.importPreferences { ClipboardReview0724Harness.NOW }.enable("1"))
        // 建立有效盐命名空间，故障只能命中商品摘要提交，而不是首次盐/元数据初始化。
        val records = ClipboardRecords(prefs.hashes, prefs.salt, { ClipboardReview0724Harness.NOW }, { SAMPLE_SALT.copyOf() })
        assertTrue(records.recordSelfWritten("独立的自写样例，不是商品候选"))
        assertNull(prefs.hashes.read(ClipboardRecords.KEY_PRODUCTS))
    }
}
