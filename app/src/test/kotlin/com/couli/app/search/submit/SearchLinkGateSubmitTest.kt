@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardDraftTexts
import com.couli.app.clipboard.ClipboardResult
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.RATE_LIMITED
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.core.network.ApiResult
import com.couli.feature.search.SearchPhase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 修复 r2 / 评审 S1-3、S2-4（测试 v5）：
 * - 42901 共享禁用期不是「没有候选」：禁用期内再次提交同一链接停止本次提交（不发 searchProducts、不写历史、不再识别），到期后恢复识别；
 * - 没有可搜索平台（UNAVAILABLE）时识别失败的回落没有发出关键词请求，不写历史（历史只由真正发出的关键词请求触发）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SearchLinkGateSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- S1-3 42901 禁用期

    @Test
    fun rateLimitedResubmitOfSameLinkStopsWithoutKeywordSearchOrHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope, clock = { SearchSubmitFixture.NOW + testScheduler.currentTime }).use { f ->
                rateLimited(f)
                // 禁用期内（5 秒缺省时长内）再次提交同一链接
                advanceTimeBy(DURING_MS)
                f.submit(LINK)
                runCurrent()
                assertEquals("禁用期内：不发 searchProducts、不再识别，停止本次提交", listOf("parseInput"), SubmitReplies.operations(f))
                assertEquals("禁用期内的提交不写搜索历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
                assertTrue("禁用期内的提交不导航", f.navigations.isEmpty())
            }
        }

    @Test
    fun rateLimitedResubmitAfterExpiryRecognizesAgain() =
        runTest {
            SearchSubmitFixture(backgroundScope, clock = { SearchSubmitFixture.NOW + testScheduler.currentTime }).use { f ->
                rateLimited(f)
                advanceTimeBy(DURING_MS)
                f.submit(LINK)
                runCurrent()
                // 到期后
                advanceTimeBy(AFTER_MS)
                runCurrent()
                f.submit(LINK)
                runCurrent()
                assertEquals(
                    "到期后恢复识别：再发一次 parse，不改走关键词",
                    listOf("parseInput", "parseInput"),
                    SubmitReplies.operations(f),
                )
                f.api.reply(1).complete(SubmitReplies.single())
                advanceUntilIdle()
                assertEquals("恢复后识别成功照常直达详情", listOf("ProductDetail" to SubmitReplies.detailParams()), f.navigations)
                assertEquals("识别成功不写历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
            }
        }

    // ---------------------------------------------------------------- S2-4 UNAVAILABLE 的回落不写历史

    @Test
    fun serverErrorFallbackWithoutSearchablePlatformWritesNoHistory() = unavailableFallback(SubmitReplies.failure(SERVER_ERROR))

    @Test
    fun transportFallbackWithoutSearchablePlatformWritesNoHistory() = unavailableFallback(SubmitReplies.transport())

    private fun unavailableFallback(reply: ApiResult) =
        runTest {
            SearchSubmitFixture(backgroundScope, searchEnabled = false).use { f ->
                f.submit(LINK)
                runCurrent()
                assertEquals("前提：没有可搜索平台也先识别", listOf("parseInput"), SubmitReplies.operations(f))
                f.api.parseReply.complete(reply)
                advanceUntilIdle()
                assertEquals("前提：没有可搜索平台，回落发不出关键词请求", listOf("parseInput"), SubmitReplies.operations(f))
                assertEquals("前提：页面是「无可搜索平台」", SearchPhase.UNAVAILABLE, f.model.state.value.phase)
                assertEquals(
                    "关键词请求没有真正发出：不写搜索历史",
                    emptyList<String>() to emptyList<String>(),
                    SubmitReplies.history(f),
                )
            }
        }

    // ---------------------------------------------------------------- 公共

    /** 首次提交 → 识别回整体 42901：照粘贴启动共享禁用期并出 error.42901 提示，不回落。 */
    private fun TestScope.rateLimited(f: SearchSubmitFixture) {
        f.submit(LINK)
        runCurrent()
        f.api.parseReply.complete(SubmitReplies.failure(RATE_LIMITED))
        runCurrent()
        assertEquals("前提：42901 不回落关键词", listOf("parseInput"), SubmitReplies.operations(f))
        val result = f.clipboard.result.value
        assertTrue("前提：照粘贴出 42901 提示，实际 $result", result is ClipboardResult.Notice)
        assertEquals(ClipboardDraftTexts.text("error.$RATE_LIMITED"), (result as ClipboardResult.Notice).text)
        assertTrue("前提：共享禁用期已启动", f.clipboard.blocked.value)
    }

    private companion object {
        const val DURING_MS = 1_000L
        const val AFTER_MS = 5_000L
    }
}
