@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.WORD
import com.couli.feature.search.SearchPhase
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
 * FU-AND-SEARCH-LINK-OWNER 测试 v1：补旧单 r3 评审遗留的 S2（review-android-r3.md 第 2、3 项）。
 *
 * - 历史离页漏记（S2-2）：网络线程已通知写出、主线程还没处理时页面出栈并清理 ViewModel，进程级历史仍要记下这次已写出的搜索；写出前就被取消
 *   的不记。历史服务按 onWritten 在主线程记账，不挂在 viewModelScope 上。
 * - Z13 写出去重：同一请求的写出通知来了两次（GET 自动重试再次写出），只记一次——中间用户清空了历史，第二次通知不得把词写回来。
 * - Z14 主线程切换：写出通知在另一条真实线程上调（生产里 onCommitted 在 OkHttp 线程上），那条线程返回时历史还不能变；主线程处理后才记下。
 * - Z15 失效后回到输入态：回落的提交作废后，页面回到输入态（phase=IDLE、查询关键词清空），用户输入框里的文字保留；之后的配置变化不得按旧
 *   提交续搜。
 *
 * 「排空」= 协程 advanceUntilIdle + 主 Looper idle（实现可用 viewModelScope 之外的主线程作用域或 Handler.post）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个遗留项一条或两条
class SearchSubmitOwnerResidualTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- 历史离页漏记

    @Test
    fun wordWrittenOnIoThenPageClearedStillRecordsHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val notice = f.api.notifyWrittenOnIo(SEARCH)
                f.api.holdReply()
                f.onSearchPage()
                f.submit(WORD)
                runCurrent()
                assertWrittenOnIo(f, notice)
                f.leaveSearch()
                f.clearViewModel()
                settle()
                assertEquals("已写出的搜索在离页、清理 ViewModel 之后仍记入进程历史", listOf(WORD), f.pageConfig.history.items)
            }
        }

    @Test
    fun fallbackWrittenOnIoThenPageClearedStillRecordsHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val notice = f.api.notifyWrittenOnIo(SEARCH)
                f.api.holdReply()
                f.onSearchPage()
                f.submit(LINK)
                runCurrent()
                f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
                runCurrent()
                assertWrittenOnIo(f, notice)
                f.leaveSearch()
                f.clearViewModel()
                settle()
                assertEquals("已写出的回落在离页、清理 ViewModel 之后仍记入进程历史", listOf(LINK), f.pageConfig.history.items)
            }
        }

    @Test
    fun cancelledBeforeWriteThenPageClearedRecordsNothing() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold = f.api.holdWrite(SEARCH)
                f.onSearchPage()
                f.submit(WORD)
                runCurrent()
                f.leaveSearch()
                f.clearViewModel()
                hold.complete(Unit)
                settle()
                assertTrue("前提：写出前被取消，没有写出（实际 ${f.api.writtenOperations}）", f.api.written.isEmpty())
                assertEquals("写出前被取消的搜索不记历史", emptyList<String>(), f.pageConfig.history.items)
            }
        }

    // ---------------------------------------------------------------- Z13 写出去重

    @Test fun repeatedWriteNoticeForWordRecordsOnce() = repeatedNoticeRecordsOnce(WORD, fallback = false)

    @Test fun repeatedWriteNoticeForFallbackRecordsOnce() = repeatedNoticeRecordsOnce(LINK, fallback = true)

    private fun repeatedNoticeRecordsOnce(
        text: String,
        fallback: Boolean,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            f.api.holdReply()
            f.onSearchPage()
            f.submit(text)
            runCurrent()
            if (fallback) {
                f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
                runCurrent()
            }
            settle()
            val search = f.api.written.single { it.operationId == SEARCH }
            assertEquals("前提：写出后记一次", listOf(text) to listOf(text), SubmitReplies.history(f))
            f.model.onClearHistory()
            assertEquals("前提：用户清空了历史", NONE, SubmitReplies.history(f))
            // 同一请求被 GET 自动重试再次写出：网络层对同一归属再通知一次
            checkNotNull(checkNotNull(search.ownership).onWritten) { "前提：关键词请求挂了写出通知" }.invoke(true)
            settle()
            assertEquals("同一请求的第二次写出通知不得再记历史（Z13 去重）", NONE, SubmitReplies.history(f))
        }
    }

    // ---------------------------------------------------------------- Z14 主线程切换

    @Test
    fun writeNoticeOnIoThreadRecordsHistoryOnlyOnMainThread() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val notice = f.api.notifyWrittenOnIo(SEARCH)
                f.api.holdReply()
                f.onSearchPage()
                f.submit(WORD)
                runCurrent()
                assertWrittenOnIo(f, notice)
                assertEquals(
                    "写出通知所在的网络线程上不得直接写历史（要切回主线程，Z14）",
                    NONE,
                    SubmitReplies.history(f),
                )
                settle()
                assertEquals("主线程处理后记下历史", listOf(WORD) to listOf(WORD), SubmitReplies.history(f))
            }
        }

    // ---------------------------------------------------------------- Z15 失效后回到输入态

    @Test
    fun coveredThenReturnedBeforeFallbackReplyReturnsToInput() =
        abandonedFallback(writeHeld = false) { f ->
            f.coverWithDetail()
            f.onSearchPage()
        }

    @Test
    fun accountSwitchBeforeFallbackWriteReturnsToInput() = abandonedFallback(writeHeld = true, SearchSubmitFixture::switchAccount)

    /** 识别失败 → 回落停在写出前（[writeHeld]）或响应前 → [invalidate] → 放行：回到输入态，保留输入框，配置变化不复活。 */
    private fun abandonedFallback(
        writeHeld: Boolean,
        invalidate: (SearchSubmitFixture) -> Unit,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            val write = f.api.holdWrite(SEARCH)
            val reply = f.api.holdReply()
            f.onSearchPage()
            f.submit(LINK)
            runCurrent()
            f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
            runCurrent()
            if (!writeHeld) {
                write.complete(Unit)
                runCurrent()
            }
            assertEquals("前提：回落按原文搜", listOf<String?>(LINK), SubmitReplies.keywords(f))
            val input = f.model.input.value
            invalidate(f)
            write.complete(Unit)
            reply.complete(Unit)
            settle()
            assertReturnedToInput(f, input, "作废放行后")
            val written = f.api.written.size
            f.pushSearchStatus(taobao = "off", jd = "on")
            settle()
            f.pushSearchStatus(taobao = "on", jd = "off")
            settle()
            val revived =
                f.api.written
                    .drop(written)
                    .filter { it.operationId == SEARCH }
            assertTrue("作废后的配置变化不得按旧提交续搜（实际写出 ${revived.map { it.query }}）", revived.isEmpty())
            assertReturnedToInput(f, input, "配置变化后")
        }
    }

    private fun assertReturnedToInput(
        f: SearchSubmitFixture,
        input: String,
        stage: String,
    ) {
        val page = f.page
        assertEquals("$stage：作废的回落回到输入态（Z15，实际 ${page.phase}）", SearchPhase.IDLE, page.phase)
        assertEquals("$stage：查询关键词清空（防止配置变化按旧提交重搜）", "", page.keyword)
        assertEquals("$stage：用户输入框里的文字保留", input, f.model.input.value)
    }

    // ---------------------------------------------------------------- 公共

    private fun assertWrittenOnIo(
        f: SearchSubmitFixture,
        notice: IoWriteNotice,
    ) {
        assertTrue("前提：关键词请求已写出（实际 ${f.api.writtenOperations}）", SEARCH in f.api.writtenOperations)
        // 测试主线程的 runCurrent 已结束，才放网络线程去通知：它排进主线程的任务只能由下面的排空执行
        notice.release()
        assertTrue("前提：网络线程已调完写出通知", notice.await())
        assertTrue("前提：关键词请求挂了写出通知", notice.hadCallback.get())
        assertNotEquals("前提：写出通知不在测试主线程上", Thread.currentThread().name, notice.thread.get())
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
        val NONE = emptyList<String>() to emptyList<String>()
    }
}
