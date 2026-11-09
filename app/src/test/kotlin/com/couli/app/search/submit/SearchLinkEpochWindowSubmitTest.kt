@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.FIRST_KEY
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SECOND_KEY
import com.couli.app.search.submit.SubmitReplies.SECOND_LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.TITLED_LINK
import com.couli.app.search.submit.SubmitReplies.UNSUPPORTED
import com.couli.core.network.ApiResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 修复 r3 / 评审 r2 S2-4（测试 v6）：「世代已变、通知未到」的窗口。夹具把推进世代与发通知拆开：先只推进登录世代（或只推进环境世代），
 * 不调 onSessionChanged / onEnvironmentSwitched——剪贴板的界面世代此时不变，只有票据里的世代比对能挡住。分别检查：
 * - 发送：识别请求交到网络层、写出前推进 → 不写出；
 * - 呈现：单卡 / 多卡响应已到、未消费时推进 → 不导航、不写种子、不出卡片列表；
 * - 预填：30131 响应已到时推进 → 不提示、不预填；
 * - 回落：50001 响应已到时推进 → 不回落关键词搜。
 * 最后补发晚到的通知，旧结果仍不补出。登录一组用来杀掉「删掉登录世代核对、保留界面世代」（变异 X1d）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 两种世代 × 五个出口，各一条
class SearchLinkEpochWindowSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- 只推进登录世代

    @Test fun loginEpochOnlyBeforeParseWriteBlocksSend() = beforeWrite(LOGIN)

    @Test fun loginEpochOnlyWhileSingleCardWaitsBlocksDetail() = afterReply(LINK, SubmitReplies.single(), LOGIN)

    @Test fun loginEpochOnlyWhileMultiCardWaitsBlocksCardList() = afterReply(MULTI, multiCards(), LOGIN)

    @Test fun loginEpochOnlyWhileUnsupportedWaitsBlocksPrefill() = afterReply(TITLED_LINK, SubmitReplies.failure(UNSUPPORTED), LOGIN)

    @Test fun loginEpochOnlyWhileFailureWaitsBlocksFallback() = afterReply(LINK, SubmitReplies.failure(SERVER_ERROR), LOGIN)

    // ---------------------------------------------------------------- 只推进环境世代

    @Test fun environmentEpochOnlyBeforeParseWriteBlocksSend() = beforeWrite(ENVIRONMENT)

    @Test fun environmentEpochOnlyWhileSingleCardWaitsBlocksDetail() = afterReply(LINK, SubmitReplies.single(), ENVIRONMENT)

    @Test fun environmentEpochOnlyWhileMultiCardWaitsBlocksCardList() = afterReply(MULTI, multiCards(), ENVIRONMENT)

    @Test
    fun environmentEpochOnlyWhileUnsupportedWaitsBlocksPrefill() = afterReply(TITLED_LINK, SubmitReplies.failure(UNSUPPORTED), ENVIRONMENT)

    @Test
    fun environmentEpochOnlyWhileFailureWaitsBlocksFallback() = afterReply(LINK, SubmitReplies.failure(SERVER_ERROR), ENVIRONMENT)

    // ---------------------------------------------------------------- 公共

    /** 世代推进只做一半（[Window.bump]），通知由 [Window.notify] 晚到。 */
    private class Window(
        val name: String,
        val bump: (SearchSubmitFixture) -> Unit,
        val notify: (SearchSubmitFixture) -> Unit,
    )

    /** 发送：识别请求交到网络层、写出前停住 → 只推进世代 → 放行：不得写出。 */
    private fun beforeWrite(window: Window) =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                val hold = f.api.holdWrite(PARSE)
                f.submit(LINK)
                runCurrent()
                assertEquals("前提：识别已交到网络层", listOf(PARSE), SubmitReplies.operations(f))
                assertEquals("前提：识别尚未写出", emptyList<String>(), f.api.writtenOperations)
                window.bump(f)
                hold.complete(Unit)
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertEquals(
                    "只推进${window.name}、通知未到：识别上传不得写出（实际 ${f.api.writtenOperations}）",
                    emptyList<String>(),
                    f.api.writtenOperations,
                )
                assertDropped(f, LINK, window)
            }
        }

    /** 呈现 / 预填 / 回落：识别已写出、响应 [reply] 已到 → 只推进世代 → 主线程消费：一律丢弃。 */
    private fun afterReply(
        input: String,
        reply: ApiResult,
        window: Window,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            f.onSearchPage()
            sendParse(f, input)
            f.api.parseReply.complete(reply)
            window.bump(f)
            advanceUntilIdle()
            assertDropped(f, input, window)
        }
    }

    private fun TestScope.sendParse(
        f: SearchSubmitFixture,
        input: String,
    ) {
        f.submit(input)
        runCurrent()
        assertEquals("前提：输入是候选，先只发识别", listOf(PARSE), SubmitReplies.operations(f))
        assertEquals("前提：识别已写出", listOf(PARSE), f.api.writtenOperations)
    }

    private fun TestScope.assertDropped(
        f: SearchSubmitFixture,
        input: String,
        window: Window,
    ) {
        assertWindowDropped(f, input, "只推进${window.name}、通知未到")
        // 通知晚到：旧结果仍不补出
        window.notify(f)
        advanceUntilIdle()
        assertWindowDropped(f, input, "通知晚到之后")
    }

    private fun assertWindowDropped(
        f: SearchSubmitFixture,
        input: String,
        stage: String,
    ) {
        assertEquals("$stage：不得回落关键词搜、不得再识别", listOf(PARSE), SubmitReplies.operations(f))
        assertTrue("$stage：不得导航（实际 ${f.navigations}）", f.navigations.isEmpty())
        assertNull("$stage：不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY))
        assertNull("$stage：不得写 ProductSeeds（第二张）", SubmitReplies.claimSeed(f, SECOND_KEY))
        assertNull("$stage：不得出卡片列表 / 提示（实际 ${f.clipboard.result.value}）", f.clipboard.result.value)
        assertEquals("$stage：不得预填、不得改输入框", input, f.model.input.value)
        assertEquals("$stage：不得写搜索历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
    }

    private companion object {
        const val PARSE = "parseInput"
        const val MULTI = "$LINK $SECOND_LINK"
        val LOGIN = Window("登录世代", SearchSubmitFixture::bumpLoginEpoch, SearchSubmitFixture::notifySessionChanged)
        val ENVIRONMENT = Window("环境世代", SearchSubmitFixture::bumpEnvironmentEpoch, SearchSubmitFixture::notifyEnvironmentSwitched)

        fun multiCards(): ApiResult = SubmitReplies.cards(SubmitReplies.card(), SubmitReplies.card(SECOND_KEY, "jd", SECOND_LINK))
    }
}
