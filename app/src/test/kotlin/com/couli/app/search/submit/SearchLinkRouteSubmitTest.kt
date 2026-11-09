@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardResult
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.TITLE
import com.couli.app.search.submit.SubmitReplies.TITLED_LINK
import com.couli.app.search.submit.SubmitReplies.UNSUPPORTED
import com.couli.app.search.submit.SubmitReplies.WORD
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 修复 r2 / 评审 S2-5（测试 v5）：以 `Search(q=带标题的链接)` 路由参数打开本页，行为与输入框提交一致（本线推荐，实现照此做）：先只识别；
 * 识别成功直达详情、不写历史；30131 把「」标题预填进输入框、不自动搜、不写历史；识别失败回落关键词搜一次，按普通关键词写历史。
 * 普通关键词的 q 照旧搜一次并写历史（守护）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SearchLinkRouteSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun routeQueryLinkSuccessOpensDetailWithoutHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope, query = TITLED_LINK).use { f ->
                assertParseOnly(f)
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertEquals("与输入框提交一致：只识别、不搜", listOf("parseInput"), SubmitReplies.operations(f))
                assertEquals("与输入框提交一致：直达详情一次", listOf("ProductDetail" to SubmitReplies.detailParams()), f.navigations)
                assertEquals("与输入框提交一致：识别成功不写历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
            }
        }

    @Test
    fun routeQueryUnsupportedPrefillsTitleWithoutSearchOrHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope, query = TITLED_LINK).use { f ->
                assertParseOnly(f)
                f.api.parseReply.complete(SubmitReplies.failure(UNSUPPORTED))
                advanceUntilIdle()
                assertEquals("30131 不回落关键词搜", listOf("parseInput"), SubmitReplies.operations(f))
                assertTrue("前提：照粘贴出 30131 提示", f.clipboard.result.value is ClipboardResult.Notice)
                assertEquals("与输入框提交一致：30131 把「」标题预填进输入框", TITLE, f.model.input.value)
                assertEquals("与输入框提交一致：按提示呈现的不写历史", emptyList<String>() to emptyList<String>(), SubmitReplies.history(f))
            }
        }

    @Test
    fun routeQueryFailureFallsBackOnceAndWritesKeywordHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope, query = TITLED_LINK).use { f ->
                assertParseOnly(f)
                f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
                advanceUntilIdle()
                assertEquals("识别失败回落关键词一次", listOf("parseInput", "searchProducts"), SubmitReplies.operations(f))
                assertEquals(listOf(TITLED_LINK), SubmitReplies.keywords(f))
                assertEquals("回落的关键词按普通关键词写历史", listOf(TITLED_LINK) to listOf(TITLED_LINK), SubmitReplies.history(f))
            }
        }

    @Test
    fun routeQueryWordStillSearchesOnceAndWritesHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope, query = WORD).use { f ->
                advanceUntilIdle()
                assertEquals("普通关键词 q 不识别，照旧搜一次", listOf("searchProducts"), SubmitReplies.operations(f))
                assertEquals(listOf(WORD), SubmitReplies.keywords(f))
                assertEquals(listOf(WORD) to listOf(WORD), SubmitReplies.history(f))
            }
        }

    private fun TestScope.assertParseOnly(f: SearchSubmitFixture) {
        runCurrent()
        assertEquals("与输入框提交一致：带链接的 q 先只识别", listOf("parseInput"), SubmitReplies.operations(f))
        assertEquals(
            "识别响应返回前不写历史（与输入框提交一致）",
            emptyList<String>() to emptyList<String>(),
            SubmitReplies.history(f),
        )
    }
}
