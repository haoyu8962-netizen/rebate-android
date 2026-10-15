package com.couli.feature.product

import com.couli.core.designsystem.ProductDetailState
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0715 第 1 轮评审 R1-03（F-X-09）：商品详情的结果适配按网络层 Effect 判 10405。非强更（REFRESH_ONLY、刷新失败清会话）→ 统一错误文案
 * error.50001 +【重试】（错误码不是 30141 / 30143，页面给重试；重试再发一次 getProduct）；强更（FORCE_UPDATE）照旧 error.10405。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailFollowupAnd0715Test {
    private val api = FakeApi()
    private val context: JsonObject =
        Json.parseToJsonElement("""{"loggedIn":true,"privacy":"${ProductDetailState.PRIVACY_CONSENTED}"}""").jsonObject

    private fun TestScope.controller() = ProductDetailController(api, { context }, backgroundScope) { testScheduler.currentTime }

    @Test
    fun fx09_refreshOnly10405ShowsTheUnifiedErrorWithRetry() = runTest { assertUnifiedErrorWithRetry(ErrorEffectKind.REFRESH_ONLY) }

    @Test
    fun fx09_refreshFailed10405ShowsTheUnifiedErrorWithRetry() =
        runTest {
            assertUnifiedErrorWithRetry(ErrorEffectKind.CLEAR_SESSION_LOGIN)
            assertUnifiedErrorWithRetry(ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE)
        }

    @Test
    fun fx09_forced10405KeepsTheUpdateText() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.fail(api.calls.lastIndex, ErrorEffectKind.FORCE_UPDATE)
            runCurrent()
            assertEquals(ProductPhase.ERROR, c.state.value.phase)
            assertEquals("强更照旧：版本过低文案", "error.10405", c.state.value.errorTextKey)
        }

    private suspend fun TestScope.assertUnifiedErrorWithRetry(kind: ErrorEffectKind) {
        val c = controller()
        c.load("taobao", "tb:1", null)
        runCurrent()
        api.fail(api.calls.lastIndex, kind)
        runCurrent()
        val s = c.state.value
        assertEquals(ProductPhase.ERROR, s.phase)
        assertEquals("$kind：统一错误文案，不是版本过低", ProductDetailController.GENERIC_ERROR_KEY, s.errorTextKey)
        assertEquals(TRACE, s.traceId)
        assertNotEquals("给【重试】（不是下架）", ProductDetailController.OFF_SHELF, s.errorCode)
        assertNotEquals("给【重试】（不是重新搜索）", ProductDetailController.REF_EXPIRED, s.errorCode)
        assertFalse(s.retryBlocked)
        val before = api.calls.size
        assertTrue("$kind：可重试", c.retry())
        runCurrent()
        assertEquals("重试再发一次 getProduct", before + 1, api.calls.size)
        val retried = api.calls.last()
        assertEquals(ProductDetailController.GET_PRODUCT, retried.call.operationId)
        retried.result.complete(ApiResult.Success(Json.parseToJsonElement(CARD), TRACE, null))
        runCurrent()
        assertEquals(ProductPhase.CONTENT, c.state.value.phase)
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

        fun fail(
            index: Int,
            kind: ErrorEffectKind,
        ) {
            val effect = ErrorEffect(UPDATE_REQUIRED, kind, ReplayMode.NONE, registerPending = false)
            calls[index].result.complete(ApiResult.Failure(UPDATE_REQUIRED, "", JsonObject(emptyMap()), TRACE, effect, null))
        }
    }

    private companion object {
        const val UPDATE_REQUIRED = 10405
        const val TRACE = "trace-000456"
        const val CARD =
            """{"product_key":"tb:1","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                """"est_net_price_fen":2990,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","is_presale":false,""" +
                """"cta":{"text_key":"btn.buy.coupon"},"stale":false,"age_sec":12,"quoted_at":"2026-10-02T09:30:00+08:00",""" +
                """"disclaimer_keys":["price_basis"],"availability":"ok"}"""
    }
}
