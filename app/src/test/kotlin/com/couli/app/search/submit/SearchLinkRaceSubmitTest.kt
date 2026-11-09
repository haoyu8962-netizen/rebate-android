@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.FIRST_KEY
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SECOND_KEY
import com.couli.app.search.submit.SubmitReplies.SECOND_LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.WORD
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * 修复 r2 / 评审 S2-7、S2-8（测试 v5）：按请求分别放行的时序。
 * - S2-7：识别在途时用户改了输入，失败回落仍按原提交搜，但输入框不回写成原提交（以输入版本为准）；历史按真正发出的关键词写。
 * - S2-8：再次提交后乱序返回只认最新；新关键词搜索、离页使在途识别作废（并取消传输）；单卡直达写下 ProductSeeds。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个时序一条，便于先红与变异复核
class SearchLinkRaceSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- S2-7 输入版本

    @Test fun editedInputSurvivesServerErrorFallback() = editedInputSurvives(SubmitReplies.failure(SERVER_ERROR))

    @Test fun editedInputSurvivesTransportFallback() = editedInputSurvives(SubmitReplies.transport())

    private fun editedInputSurvives(reply: ApiResult) =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.submit(LINK)
                runCurrent()
                assertEquals(listOf("parseInput"), SubmitReplies.operations(f))
                // 识别返回前把输入改成别的字，尚未再次提交
                f.model.onInput(EDITED)
                f.api.parseReply.complete(reply)
                advanceUntilIdle()
                assertEquals("回落仍按原提交 A 搜一次", listOf("parseInput", "searchProducts"), SubmitReplies.operations(f))
                assertEquals(listOf(LINK), SubmitReplies.keywords(f))
                assertEquals("输入框以用户此刻的输入为准，不回写成 A", EDITED, f.model.input.value)
                assertEquals("历史按真正发出的关键词写（与输入框回写分开）", listOf(LINK) to listOf(LINK), SubmitReplies.history(f))
            }
        }

    // ---------------------------------------------------------------- S2-8 再次提交后乱序返回只认最新

    @Test
    fun resubmitNewerReplyFirstThenOlderLateIsDropped() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val (first, second) = twoSubmits(f)
                f.api.reply(1).complete(SubmitReplies.single(SECOND_KEY, "jd", SECOND_LINK))
                runCurrent()
                assertEquals("最新一次直达详情", listOf("ProductDetail" to SubmitReplies.detailParams(SECOND_KEY, "jd")), f.navigations)
                f.api.reply(0).complete(SubmitReplies.single())
                advanceUntilIdle()
                assertLatestOnly(f, first, second)
            }
        }

    @Test
    fun resubmitOlderReplyFirstIsDroppedThenNewerWins() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val (first, second) = twoSubmits(f)
                f.api.reply(0).complete(SubmitReplies.single())
                runCurrent()
                assertTrue("旧请求先返回：不得导航", f.navigations.isEmpty())
                assertNull("旧请求先返回：不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY, "early-probe"))
                f.api.reply(1).complete(SubmitReplies.single(SECOND_KEY, "jd", SECOND_LINK))
                advanceUntilIdle()
                assertLatestOnly(f, first, second)
            }
        }

    @Test
    fun resubmitOlderReplyDelayedPastNewerIsDropped() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val (first, second) = twoSubmits(f)
                // 旧请求先放行，但带延迟：在新请求返回之后才落地
                f.api.delayParse(0, LATE_MS)
                f.api.reply(0).complete(SubmitReplies.single())
                f.api.reply(1).complete(SubmitReplies.single(SECOND_KEY, "jd", SECOND_LINK))
                runCurrent()
                advanceTimeBy(LATE_MS + 1)
                advanceUntilIdle()
                assertLatestOnly(f, first, second)
            }
        }

    @Test
    fun resubmitOlderFailureLateDoesNotFallBackOverNewerDetail() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val (first, second) = twoSubmits(f)
                f.api.reply(1).complete(SubmitReplies.single(SECOND_KEY, "jd", SECOND_LINK))
                runCurrent()
                f.api.reply(0).complete(SubmitReplies.failure(SERVER_ERROR))
                advanceUntilIdle()
                assertEquals("旧识别的失败不得回落关键词搜", listOf("parseInput", "parseInput"), SubmitReplies.operations(f))
                assertLatestOnly(f, first, second)
            }
        }

    // ---------------------------------------------------------------- S2-8 新关键词搜索使在途识别作废

    @Test
    fun newKeywordSearchDropsPendingRecognition() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.submit(LINK)
                runCurrent()
                val owner = f.api.ownerOf(0)
                f.submit(WORD)
                runCurrent()
                assertEquals("前提：新关键词照搜", listOf("parseInput", "searchProducts"), SubmitReplies.operations(f))
                assertFalse("新关键词搜索后旧识别票据作废", owner.stillOwned())
                assertTrue("新关键词搜索取消旧识别的传输", owner in f.api.cancelled)
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertEquals("迟到的识别不得再搜、再识别", listOf("parseInput", "searchProducts"), SubmitReplies.operations(f))
                assertTrue("迟到的识别不得导航", f.navigations.isEmpty())
                assertNull("迟到的识别不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY))
                assertEquals(listOf(WORD) to listOf(WORD), SubmitReplies.history(f))
            }
        }

    // ---------------------------------------------------------------- S2-8 离页作废

    @Test fun leavingPageDropsPendingSingleCard() = leavingDrops(SubmitReplies.single())

    @Test fun leavingPageDropsPendingFailureFallback() = leavingDrops(SubmitReplies.failure(SERVER_ERROR))

    private fun leavingDrops(reply: ApiResult) =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                f.submit(LINK)
                runCurrent()
                val owner = f.api.ownerOf(0)
                f.leaveSearch()
                assertFalse("离页后旧识别票据作废", owner.stillOwned())
                assertTrue("离页取消在途识别的传输", owner in f.api.cancelled)
                f.api.parseReply.complete(reply)
                advanceUntilIdle()
                assertEquals("离页后迟到的识别不得回落关键词搜", listOf("parseInput"), SubmitReplies.operations(f))
                assertTrue("离页后迟到的识别不得导航", f.navigations.isEmpty())
                assertNull("离页后迟到的识别不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY))
                assertNull("离页后迟到的识别不得出结果", f.clipboard.result.value)
            }
        }

    // ---------------------------------------------------------------- S2-8 单卡直达写 ProductSeeds

    @Test
    fun singleCardDetailWritesProductSeed() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.submit(LINK)
                runCurrent()
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertEquals("前提：单卡直达详情一次", listOf("ProductDetail" to SubmitReplies.detailParams()), f.navigations)
                assertEquals(
                    "单卡直达把识别卡（含 link_id）写进 ProductSeeds，详情沿用它、不重取商品",
                    SubmitReplies.cardObject(),
                    SubmitReplies.claimSeed(f, FIRST_KEY, DETAIL),
                )
            }
        }

    // ---------------------------------------------------------------- 公共

    /** 先提交 A（LINK），未返回时再提交 B（SECOND_LINK）：两个 parse 都已发出，旧的一个已作废并取消。 */
    private fun TestScope.twoSubmits(f: SearchSubmitFixture): Pair<CallOwnership, CallOwnership> {
        f.submit(LINK)
        runCurrent()
        f.submit(SECOND_LINK)
        runCurrent()
        assertEquals("前提：两次提交各发一次识别", listOf("parseInput", "parseInput"), SubmitReplies.operations(f))
        val first = f.api.ownerOf(0)
        val second = f.api.ownerOf(1)
        assertFalse("再次提交后旧识别票据作废", first.stillOwned())
        assertTrue("再次提交取消旧识别的传输", first in f.api.cancelled)
        assertTrue("新识别票据有效", second.stillOwned())
        return first to second
    }

    private fun assertLatestOnly(
        f: SearchSubmitFixture,
        first: CallOwnership,
        second: CallOwnership,
    ) {
        assertEquals(
            "只认最新一次：只进 B 的详情一次，旧结果不再导航",
            listOf("ProductDetail" to SubmitReplies.detailParams(SECOND_KEY, "jd")),
            f.navigations,
        )
        assertNull("旧结果不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY))
        assertEquals("最新一次的种子已写下", SubmitReplies.cardObject(SECOND_KEY, "jd", SECOND_LINK), SubmitReplies.claimSeed(f, SECOND_KEY))
        assertNull("旧结果不得出卡片列表 / 提示", f.clipboard.result.value)
        assertFalse("旧识别票据始终作废", first.stillOwned())
        assertTrue("迟到的旧结果不得把最新一次的票据作废", second.stillOwned())
        assertEquals("识别成功不写历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
    }

    private companion object {
        const val EDITED = "牛奶"
        const val LATE_MS = 3_000L
        const val DETAIL = "detail-claim"
    }
}
