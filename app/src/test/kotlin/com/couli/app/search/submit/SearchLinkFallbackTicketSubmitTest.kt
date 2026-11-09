@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.core.network.ApiResult
import com.couli.feature.search.SearchPhase
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 修复 r3 / 评审 r2 S1-1（测试 v6）：识别失败回落关键词搜时，回落请求继承原提交的票据（登录 / 环境世代与发起页）。
 * 时序：识别失败 → 主线程核票通过 → 回落关键词请求已交给网络层，但还在 IO、尚未写出（生产里 ApiClient 切到 IO 后才读取并绑定会话）。
 * - 这时切号或搜索页被详情覆盖：旧提交不得带着新账号会话写出（写出前守卫读到的归属须已失效），回落结果不得被消费；
 * - 回落已写出、响应未回时切号 / 覆盖：响应同样不得被消费（不出结果页、不记 shownSeq）。
 * 「写出」以夹具的写出前守卫为准（[SubmitApi.written]，与 ApiClient 同一口径：世代、stillOwned、sendAllowed、未被 cancelOwned）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SearchLinkFallbackTicketSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- 回落已启动、关键词尚未写出

    @Test
    fun accountSwitchBeforeFallbackWriteNeitherSendsNorConsumes() =
        heldBeforeWrite(SubmitReplies.failure(SERVER_ERROR), SearchSubmitFixture::switchAccount)

    @Test
    fun accountSwitchBeforeTransportFallbackWriteNeitherSendsNorConsumes() =
        heldBeforeWrite(SubmitReplies.transport(), SearchSubmitFixture::switchAccount)

    @Test
    fun coveredBeforeFallbackWriteNeitherSendsNorConsumes() =
        heldBeforeWrite(SubmitReplies.failure(SERVER_ERROR), SearchSubmitFixture::coverWithDetail)

    // ---------------------------------------------------------------- 回落已写出、响应未回

    @Test
    fun accountSwitchAfterFallbackWriteDoesNotConsumeReply() = heldReply(SearchSubmitFixture::switchAccount)

    @Test
    fun coveredAfterFallbackWriteDoesNotConsumeReply() = heldReply(SearchSubmitFixture::coverWithDetail)

    // ---------------------------------------------------------------- 公共

    /** 识别失败（[reply]）→ 回落关键词请求交到网络层、停在写出前 → [change] → 放行：不写出、不消费。 */
    private fun heldBeforeWrite(
        reply: ApiResult,
        change: (SearchSubmitFixture) -> Unit,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            val hold = f.api.holdWrite(SEARCH)
            startFallback(f, reply)
            assertEquals("前提：回落尚未写出，只写出了识别", listOf(PARSE), f.api.writtenOperations)
            change(f)
            hold.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "回落请求不得在切号 / 发起页被覆盖之后写出（不得带着新账号会话上传；实际写出 ${f.api.writtenOperations}）",
                listOf(PARSE),
                f.api.writtenOperations,
            )
            assertNotConsumed(f)
        }
    }

    /** 识别失败 → 回落关键词已写出、响应停住 → [change] → 放行响应：不消费。 */
    private fun heldReply(change: (SearchSubmitFixture) -> Unit) =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val hold: CompletableDeferred<Unit> = f.api.holdReply()
                startFallback(f, SubmitReplies.failure(SERVER_ERROR))
                assertEquals("前提：回落已写出", listOf(PARSE, SEARCH), f.api.writtenOperations)
                change(f)
                hold.complete(Unit)
                advanceUntilIdle()
                assertNotConsumed(f)
            }
        }

    /** 提交 [LINK] → 只写出识别 → 识别失败 [reply] → 主线程核票通过、回落关键词请求交到网络层。 */
    private fun TestScope.startFallback(
        f: SearchSubmitFixture,
        reply: ApiResult,
    ) {
        f.onSearchPage()
        f.submit(LINK)
        runCurrent()
        assertEquals("前提：输入是候选，先只发识别", listOf(PARSE), SubmitReplies.operations(f))
        f.api.parseReply.complete(reply)
        runCurrent()
        assertEquals("前提：识别失败后已回落，关键词请求交到了网络层", listOf(PARSE, SEARCH), SubmitReplies.operations(f))
        assertEquals("前提：回落按原提交搜", listOf<String?>(LINK), SubmitReplies.keywords(f))
        val fallback = f.api.calls.last()
        assertTrue("前提：回落请求带归属", fallback.ownership != null)
    }

    private fun assertNotConsumed(f: SearchSubmitFixture) {
        val page = f.page
        assertNull("作废的回落结果不得被消费（shownSeq 实际 ${page.shownSeq}，phase ${page.phase}）", page.shownSeq)
        assertFalse(
            "作废的回落结果不得展示为结果页 / 空结果（实际 ${page.phase}）",
            page.phase == SearchPhase.CONTENT || page.phase == SearchPhase.EMPTY,
        )
        assertTrue("作废的回落不得导航（实际 ${f.navigations}）", f.navigations.isEmpty())
    }

    private companion object {
        const val PARSE = "parseInput"
        const val SEARCH = "searchProducts"
    }
}
