package com.couli.feature.search

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0715 第 1 轮评审 R1-03（F-X-09）：搜索结果适配（[viewOf]）保留并消费网络层 Effect。非强更的 10405（REFRESH_ONLY、刷新失败清会话）首屏与翻页
 * 都是统一错误文案（error.50001 + trace 末 6 位）+【重试】，不显示「版本过低」；网络层判定强更（FORCE_UPDATE）的 10405 照旧 error.10405。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFollowupAnd0715Test {
    private val api = FakeApi()

    private fun TestScope.controller(): SearchController = SearchController(api, FakeConfig, backgroundScope) { testScheduler.currentTime }

    @Test
    fun fx09_refreshOnly10405OnTheFirstPageAndLoadMoreShowsTheUnifiedErrorWithRetry() =
        runTest { assertUnifiedErrorWithRetry(ErrorEffectKind.REFRESH_ONLY) }

    @Test
    fun fx09_refreshFailed10405ShowsTheUnifiedErrorWithRetry() =
        runTest {
            assertUnifiedErrorWithRetry(ErrorEffectKind.CLEAR_SESSION_LOGIN)
        }

    @Test
    fun fx09_refreshFailedStayingOnForceUpdate10405IsStillNotTheVersionText() =
        runTest {
            assertUnifiedErrorWithRetry(ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE)
        }

    @Test
    fun fx09_forced10405KeepsTheUpdateText() =
        runTest {
            val c = controller()
            c.open("milk", null)
            runCurrent()
            api.fail(0, ErrorEffectKind.FORCE_UPDATE)
            runCurrent()
            assertEquals(SearchPhase.ERROR, c.state.value.phase)
            val error = c.state.value.error
            assertEquals("强更照旧：版本过低文案", "error.10405", error?.textKey)
            assertEquals(DefaultTexts.text("error.10405") + "（000123）", errorText(c.state.value.error, "taobao", TestTexts))
        }

    private suspend fun TestScope.assertUnifiedErrorWithRetry(kind: ErrorEffectKind) {
        val c = controller()
        c.open("milk", null)
        runCurrent()
        api.fail(0, kind)
        runCurrent()
        val first = c.state.value
        assertEquals(SearchPhase.ERROR, first.phase)
        assertEquals("$kind：统一错误文案", "error.50001", first.error?.textKey)
        assertEquals("出了点问题，请稍后再试（000123）", errorText(first.error, "taobao", TestTexts))
        assertTrue("$kind：可重试", c.retry())
        runCurrent()
        api.complete(1, page(cursor = "c2"))
        runCurrent()
        assertTrue(c.loadMore())
        runCurrent()
        api.fail(2, kind)
        runCurrent()
        val more = c.state.value
        assertNotNull(more.loadMoreError)
        assertEquals("$kind：翻页同样是统一错误文案", "error.50001", more.loadMoreError?.textKey)
        assertEquals("出了点问题，请稍后再试（000123）", errorText(more.loadMoreError, more.platform, TestTexts))
        assertTrue("$kind：翻页失败可重试", c.retry())
        runCurrent()
        assertEquals("重试再发一次翻页", 4, api.calls.size)
    }

    private class Pending(
        val call: ApiCall,
        val result: CompletableDeferred<ApiResult>,
    )

    private class FakeApi : PageApi {
        val calls = mutableListOf<Pending>()

        override suspend fun send(call: ApiCall): ApiResult {
            val pending = Pending(call, CompletableDeferred())
            calls += pending
            return pending.result.await()
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)

        fun complete(
            index: Int,
            data: String,
        ) {
            calls[index].result.complete(ApiResult.Success(Json.parseToJsonElement(data), TRACE, null))
        }

        fun fail(
            index: Int,
            kind: ErrorEffectKind,
        ) {
            val effect = ErrorEffect(UPDATE_REQUIRED, kind, ReplayMode.NONE, registerPending = false)
            calls[index].result.complete(ApiResult.Failure(UPDATE_REQUIRED, "", JsonObject(emptyMap()), TRACE, effect, null))
        }
    }

    private object FakeConfig : SearchConfigPort {
        override fun statusLayers(): JsonObject = JsonObject(mapOf("current" to Json.parseToJsonElement("""{"taobao":"on"}""").jsonObject))

        override fun refresh() = Unit

        override fun issuedMark(): Long = 0

        override fun appliedMark(): Long = 0

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    private object TestTexts : SearchTexts {
        override fun text(key: String): String = DefaultTexts.text(key) ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return text
        }
    }

    private companion object {
        const val UPDATE_REQUIRED = 10405
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000123"

        fun page(cursor: String?): String {
            val item =
                """{"product_key":"tb:1","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                    """"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","stale":false,"age_sec":12,""" +
                    """"cta":{"text_key":"btn.buy.coupon"},"availability":"ok"}"""
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$item],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
