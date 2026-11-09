@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SECOND_KEY
import com.couli.app.search.submit.SubmitReplies.SECOND_LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v2（Codex 对抗评审 r1 S0-3、S2-1）。
 *
 * S0-3 历史：回落已写出，网络线程上写出通知已投递（主线程还没执行），这时主线程先切号 / 撤回隐私 / 切环境——它们作废了这次提交，排队的记账
 * 不得把旧提交写进历史。同时保持任务书要求：已写出后**单纯离页**（含清理 ViewModel，见 SearchSubmitOwnerResidualTest）照样记。所以历史要在
 * 主线程执行时再核一次归属，并区分离页（照记）与身份类作废（不记）。
 *
 * S2-1（M24）：回落在途时再提交 B，旧回落迟到（被挡住）之后，B 的识别结果仍须照常呈现（单卡直达 B 的详情、写 B 的种子）；B 先到、旧回落后到
 * 也一样。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个作废类别一条
class SearchSubmitOwnerReviewR1Test {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- S0-3：投递后、主线程执行前的身份类作废

    @Test fun accountSwitchAfterWriteNoticePostedRecordsNoHistory() = noticePostedThen(record = false, SearchSubmitFixture::switchAccount)

    @Test
    fun privacyWithdrawAfterWriteNoticePostedRecordsNoHistory() = noticePostedThen(record = false, SearchSubmitFixture::withdrawPrivacy)

    @Test
    fun environmentSwitchAfterWriteNoticePostedRecordsNoHistory() = noticePostedThen(record = false, SearchSubmitFixture::switchEnvironment)

    /** 同一窗口里单纯离页（不清理 ViewModel）：已写出的回落照记（任务书 §2.4，不得因区分身份类作废而一起丢掉）。 */
    @Test fun leavingAfterWriteNoticePostedStillRecordsHistory() = noticePostedThen(record = true, SearchSubmitFixture::leaveSearch)

    /** 识别失败 → 回落写出，写出通知在网络线程上调完（主线程尚未执行投递的记账）→ [change] → 排空：[record] 为 true 才应记下 [LINK]。 */
    private fun noticePostedThen(
        record: Boolean,
        change: (SearchSubmitFixture) -> Unit,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            val notice = f.api.notifyWrittenOnIo(SEARCH)
            f.api.holdReply()
            f.onSearchPage()
            f.submit(LINK)
            runCurrent()
            f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
            runCurrent()
            assertTrue("前提：回落已写出（实际 ${f.api.writtenOperations}）", SEARCH in f.api.writtenOperations)
            notice.release()
            assertTrue("前提：网络线程已调完写出通知", notice.await())
            assertTrue("前提：回落请求挂了写出通知", notice.hadCallback.get())
            assertNotEquals("前提：写出通知不在测试主线程上", Thread.currentThread().name, notice.thread.get())
            assertEquals("前提：主线程还没执行记账", emptyList<String>(), f.pageConfig.history.items)
            change(f)
            settle()
            if (record) {
                assertEquals("已写出的回落在单纯离页后仍记入进程历史", listOf(LINK), f.pageConfig.history.items)
            } else {
                assertEquals(
                    "写出通知投递之后、主线程执行之前提交被切号 / 撤回隐私 / 切环境作废：不得记历史",
                    emptyList<String>(),
                    f.pageConfig.history.items,
                )
            }
        }
    }

    // ---------------------------------------------------------------- S2-1：旧回落迟到被挡之后，新提交仍能呈现

    @Test
    fun lateFallbackBeforeWriteDoesNotBlockNewerSubmit() = newerSubmitSurvivesLateFallback(fallbackWritten = false, newerFirst = false)

    @Test fun lateFallbackReplyDoesNotBlockNewerSubmit() = newerSubmitSurvivesLateFallback(fallbackWritten = true, newerFirst = false)

    @Test fun newerSubmitFirstThenLateFallbackStillShowsNewer() = newerSubmitSurvivesLateFallback(fallbackWritten = true, newerFirst = true)

    /**
     * 提交 A 识别失败、回落在途（写出前或响应前）→ 再提交 B（B 的识别在途）→ 旧回落迟到被挡（[newerFirst] 为 true 时 B 的识别先回）→ B 识别为
     * jd 单卡：直达 B 的详情一次、写 B 的种子；旧回落不得被消费。
     */
    private fun newerSubmitSurvivesLateFallback(
        fallbackWritten: Boolean,
        newerFirst: Boolean,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            val write: CompletableDeferred<Unit> = f.api.holdWrite(SEARCH)
            val reply: CompletableDeferred<Unit> = f.api.holdReply()
            f.onSearchPage()
            f.submit(LINK)
            runCurrent()
            f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
            runCurrent()
            if (fallbackWritten) {
                write.complete(Unit)
                runCurrent()
            }
            assertEquals("前提：回落按原文搜", listOf<String?>(LINK), SubmitReplies.keywords(f))
            f.submit(SECOND_LINK)
            runCurrent()
            assertEquals("前提：B 的识别已发出", 2, f.api.parseCalls.size)
            val newer = SubmitReplies.single(SECOND_KEY, "jd", SECOND_LINK)
            if (newerFirst) {
                f.api.reply(1).complete(newer)
                settle()
            }
            write.complete(Unit)
            reply.complete(Unit)
            settle()
            if (!newerFirst) {
                assertTrue("旧回落迟到被挡之后、B 返回之前不得导航（实际 ${f.navigations}）", f.navigations.isEmpty())
                f.api.reply(1).complete(newer)
                settle()
            }
            assertEquals(
                "旧回落迟到被挡之后，B 的识别结果仍须呈现：直达 B 的详情一次",
                listOf("ProductDetail" to SubmitReplies.detailParams(SECOND_KEY, "jd")),
                f.navigations,
            )
            assertEquals(
                "B 的商品种子已写下",
                SubmitReplies.cardObject(SECOND_KEY, "jd", SECOND_LINK),
                SubmitReplies.claimSeed(f, SECOND_KEY),
            )
            assertEquals("旧回落的结果不得被消费（shownSeq）", null, f.page.shownSeq)
        }
    }

    private fun TestScope.settle() {
        repeat(DRAIN_ROUNDS) {
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private companion object {
        const val SEARCH = "searchProducts"
        const val DRAIN_ROUNDS = 3
    }
}
