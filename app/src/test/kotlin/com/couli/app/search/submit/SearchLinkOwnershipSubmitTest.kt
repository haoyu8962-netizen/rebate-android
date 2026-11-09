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
import com.couli.core.network.CallOwnership
import com.couli.core.router.PrivacyState
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
 * 修复 r2 / 评审 S1-1、S1-2（测试 v5）：识别票据绑定提交时的身份 / 环境世代与发起页。响应已到、等主线程消费的窗口里切号、切环境、撤回隐私，
 * 或搜索页被详情覆盖（仍在回退栈）、返回后，旧结果一律丢弃：不出卡、不写 ProductSeeds、不导航、不预填、不回落关键词搜。
 * 走生产提交入口（真实 AppSearch / SearchViewModel / AppClipboard），世代与回退栈按生产的通知顺序由夹具推进。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个世代 / 页面时序一条，便于先红与变异复核
class SearchLinkOwnershipSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- S1-1 身份 / 环境世代

    @Test
    fun accountSwitchWhileSingleCardReplyWaitsDropsDetailAndSeed() = stale(LINK, SubmitReplies.single(), SearchSubmitFixture::switchAccount)

    @Test
    fun environmentSwitchWhileFailureWaitsDoesNotFallBackToKeywordSearch() =
        stale(LINK, SubmitReplies.failure(SERVER_ERROR), SearchSubmitFixture::switchEnvironment)

    @Test
    fun environmentSwitchWhileTransportFailureWaitsDoesNotFallBackToKeywordSearch() =
        stale(LINK, SubmitReplies.transport(), SearchSubmitFixture::switchEnvironment)

    @Test
    fun privacyWithdrawWhileMultiCardReplyWaitsDropsCardList() =
        stale(
            "$LINK $SECOND_LINK",
            SubmitReplies.cards(SubmitReplies.card(), SubmitReplies.card(SECOND_KEY, "jd", SECOND_LINK)),
        ) { f ->
            f.withdrawPrivacy()
            assertEquals("前提：已撤回隐私同意", PrivacyState.BASIC_MODE, f.privacy.conclusion)
        }

    @Test
    fun accountSwitchWhileUnsupportedReplyWaitsNeitherNoticesNorPrefills() =
        stale(TITLED_LINK, SubmitReplies.failure(UNSUPPORTED), SearchSubmitFixture::switchAccount)

    @Test
    fun environmentSwitchWhileSingleCardReplyWaitsDropsDetailAndSeed() =
        stale(LINK, SubmitReplies.single(), SearchSubmitFixture::switchEnvironment)

    // ---------------------------------------------------------------- S1-2 发起页被覆盖（仍在回退栈）

    @Test
    fun coveredByDetailLateSingleCardDoesNotNavigateAgain() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                val owner = sendParse(f, LINK)
                f.coverWithDetail()
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertDropped(f, LINK, owner)
            }
        }

    @Test
    fun coveredByDetailLateMultiCardDoesNotPopOverCurrentPage() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                val input = "$LINK $SECOND_LINK"
                val owner = sendParse(f, input)
                f.coverWithDetail()
                f.api.parseReply.complete(
                    SubmitReplies.cards(SubmitReplies.card(), SubmitReplies.card(SECOND_KEY, "jd", SECOND_LINK)),
                )
                advanceUntilIdle()
                assertDropped(f, input, owner)
                // 回到搜索页：被覆盖时作废的结果不补出
                f.onSearchPage()
                advanceUntilIdle()
                assertNull("返回搜索页后旧结果也不补出", f.clipboard.result.value)
            }
        }

    @Test
    fun coveredThenBackToSearchOldTicketStaysDead() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                val owner = sendParse(f, LINK)
                f.coverWithDetail()
                // 识别仍未返回，用户已从详情返回搜索页：旧票据不恢复
                f.onSearchPage()
                runCurrent()
                assertFalse("返回搜索页后旧识别票据不恢复", owner.stillOwned())
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertDropped(f, LINK, owner)
            }
        }

    @Test
    fun coveredByDetailLateFailureDoesNotFallBackToKeywordSearch() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                val owner = sendParse(f, LINK)
                f.coverWithDetail()
                f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
                advanceUntilIdle()
                assertDropped(f, LINK, owner)
            }
        }

    // ---------------------------------------------------------------- 公共

    /**
     * 提交 [input] → 只发出识别 → 响应 [reply] 已到（未被主线程消费）→ [change] 推进世代 → 主线程消费：旧结果一律丢弃。
     * 先放行响应、再推进世代：网络层写出前 / 返回时的世代核对覆盖不到这个窗口（评审 S1-1）。
     */
    private fun stale(
        input: String,
        reply: ApiResult,
        change: (SearchSubmitFixture) -> Unit,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            f.onSearchPage()
            val owner = sendParse(f, input)
            f.api.parseReply.complete(reply)
            change(f)
            advanceUntilIdle()
            assertDropped(f, input, owner)
        }
    }

    private fun TestScope.sendParse(
        f: SearchSubmitFixture,
        input: String,
    ): CallOwnership {
        assertEquals(PrivacyState.CONSENTED, f.privacy.conclusion)
        f.submit(input)
        runCurrent()
        assertEquals("前提：输入是候选，先只发识别", listOf("parseInput"), SubmitReplies.operations(f))
        val owner = f.api.ownerOf(0)
        assertTrue("前提：识别请求发出时归属有效", owner.stillOwned())
        return owner
    }

    private fun assertDropped(
        f: SearchSubmitFixture,
        input: String,
        owner: CallOwnership,
    ) {
        assertEquals("作废的识别不得回落关键词搜、不得再识别", listOf("parseInput"), SubmitReplies.operations(f))
        assertTrue("作废的识别不得导航（实际 ${f.navigations}）", f.navigations.isEmpty())
        assertNull("作废的识别不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY))
        assertNull("作废的识别不得写 ProductSeeds（第二张）", SubmitReplies.claimSeed(f, SECOND_KEY))
        assertNull("作废的识别不得出卡片列表 / 提示（实际 ${f.clipboard.result.value}）", f.clipboard.result.value)
        assertEquals("作废的识别不得预填、不得改输入框", input, f.model.input.value)
        assertEquals("作废的识别不得写搜索历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
        assertFalse("旧识别票据已作废", owner.stillOwned())
    }
}
