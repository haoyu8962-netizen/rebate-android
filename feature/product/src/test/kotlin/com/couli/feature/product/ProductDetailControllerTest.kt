package com.couli.feature.product

import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.ProductCardMapper
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06a 详情执行器的集成测试（本地假服务 [FakeApi]）：详情各状态映射、30141 / 30143、价格不可用不补 0、缓存降级提示、离开页面后迟到
 * 响应不更新界面、重新加载丢弃旧响应、42901 禁用【重试】到期恢复、item_ref 原样透传。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailControllerTest {
    private val api = FakeApi()
    private var context = ctx(loggedIn = true, privacy = ProductDetailState.PRIVACY_CONSENTED)

    private fun TestScope.controller() = ProductDetailController(api, { context }, backgroundScope) { testScheduler.currentTime }

    @Test
    fun loadsThroughGetProductWithTheOpaqueItemRef() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:7Kq9LmN3pQ", "v1.a/b+c=")
            runCurrent()
            val call = api.calls.single().call
            assertEquals(ProductDetailController.GET_PRODUCT, call.operationId)
            assertEquals(mapOf("product_key" to "tb:7Kq9LmN3pQ"), call.pathParams)
            assertEquals("item_ref=v1.a%2Fb%2Bc%3D", call.query)
            api.complete(0, card())
            runCurrent()
            val s = c.state.value
            assertEquals(ProductPhase.CONTENT, s.phase)
            assertEquals("btn.buy.coupon", s.view?.buttonKeyRaw)
            assertTrue(s.view!!.buyEnabled)
            assertFalse(s.view!!.publicMode)
        }

    @Test
    fun guestAndBasicModeAreThePublicState() =
        runTest {
            context = ctx(loggedIn = false, privacy = ProductDetailState.PRIVACY_CONSENTED)
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.complete(0, card())
            runCurrent()
            assertTrue(
                c.state.value.view!!
                    .publicMode,
            )
            context = ctx(loggedIn = true, privacy = ProductDetailState.PRIVACY_BASIC)
            assertTrue(ProductDetailState.map(c.state.value.product!!, context).publicMode)
        }

    @Test
    fun offShelfAndRefExpiredAreErrorStatesWithTheirKeys() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.fail(0, 30141)
            runCurrent()
            assertEquals(ProductPhase.ERROR, c.state.value.phase)
            assertEquals(ProductDetailController.OFF_SHELF, c.state.value.errorCode)
            assertEquals("error.30141", c.state.value.errorTextKey)
            assertTrue(c.retry())
            runCurrent()
            api.fail(1, 30143)
            runCurrent()
            assertEquals("error.30143", c.state.value.errorTextKey)
        }

    @Test
    fun priceUnavailableHidesEveryAmountAndDisablesBuying() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.complete(0, card(price = null, availability = "price_unavailable"))
            runCurrent()
            val s = c.state.value
            assertTrue(s.view!!.priceUnavailable)
            assertFalse(s.view!!.buyEnabled)
            val mapped = ProductCardMapper.map(s.product!!, { "x" })
            assertNull("价格保持未知，不补 0", mapped.data.price)
            assertEquals(BuyButtonState.RETRY_LATER, mapped.buyState)
            assertNull(mapped.data.estNet)
        }

    @Test
    fun staleQuoteRaisesThePriceMayChangeNote() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.complete(0, card(stale = true))
            runCurrent()
            assertTrue(
                c.state.value.view!!
                    .stale,
            )
            assertEquals("x", ProductCardMapper.map(c.state.value.product!!, { "x" }).data.staleNote)
        }

    @Test
    fun lateResponseAfterLeavingIsDroppedAndTransportCancelled() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            c.leave()
            assertFalse(api.calls[0].owner.stillOwned())
            assertTrue(api.calls[0].owner in api.cancelled)
            api.complete(0, card())
            runCurrent()
            assertEquals(ProductPhase.LOADING, c.state.value.phase)
            assertNull(c.state.value.shownSeq)
        }

    @Test
    fun reloadDropsTheOldResponse() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.fail(0, 50401)
            runCurrent()
            c.retry()
            runCurrent()
            c.load("taobao", "tb:2", null)
            runCurrent()
            api.complete(1, card(key = "tb:old"))
            runCurrent()
            assertEquals(ProductPhase.LOADING, c.state.value.phase)
            api.complete(2, card(key = "tb:2"))
            runCurrent()
            assertEquals("tb:2", (c.state.value.product!!["product_key"] as JsonPrimitive).content)
        }

    @Test
    fun rateLimitedRetryIsBlockedUntilRetryAfter() =
        runTest {
            val c = controller()
            c.load("taobao", "tb:1", null)
            runCurrent()
            api.fail(0, 42901, disableMillis = 8_000)
            runCurrent()
            assertTrue(c.state.value.retryBlocked)
            assertFalse(c.retry())
            advanceTimeBy(8_001)
            runCurrent()
            assertFalse(c.state.value.retryBlocked)
            assertTrue(c.retry())
        }

    private class Pending(
        val call: ApiCall,
        val owner: CallOwnership,
        val result: CompletableDeferred<ApiResult>,
    )

    private class FakeApi : PageApi {
        val calls = mutableListOf<Pending>()
        val cancelled = mutableListOf<CallOwnership>()

        override suspend fun send(call: ApiCall): ApiResult {
            val pending = Pending(call, call.ownership!!, CompletableDeferred())
            calls += pending
            return pending.result.await()
        }

        override fun cancelOwned(owner: CallOwnership) {
            cancelled += owner
        }

        override fun epochs(): CallEpochs = CallEpochs(0, 0)

        fun complete(
            index: Int,
            data: String,
        ) {
            calls[index].result.complete(ApiResult.Success(Json.parseToJsonElement(data), TRACE, null))
        }

        fun fail(
            index: Int,
            code: Int,
            disableMillis: Long? = null,
        ) {
            val kind = if (disableMillis != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.PAGE_ACTION
            val effect = ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = disableMillis)
            calls[index].result.complete(ApiResult.Failure(code, "", JsonObject(emptyMap()), TRACE, effect, null))
        }
    }

    private companion object {
        const val TRACE = "trace-000456"

        fun ctx(
            loggedIn: Boolean,
            privacy: String,
        ): JsonObject = Json.parseToJsonElement("""{"loggedIn":$loggedIn,"privacy":"$privacy"}""").jsonObject

        fun card(
            key: String = "tb:1",
            price: Long? = 3990,
            availability: String = "ok",
            stale: Boolean = false,
        ): String {
            val p = price?.toString() ?: "null"
            val f = price?.let { (it - 1000).toString() } ?: "null"
            val c = if (price == null) "null" else "1000"
            return """{"product_key":"$key","platform":"taobao","title":"t","price_fen":$p,"coupon_fen":$c,"final_price_fen":$f,""" +
                """"est_net_price_fen":$f,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","is_presale":false,""" +
                """"cta":{"text_key":"btn.buy.coupon"},"stale":$stale,"age_sec":12,"quoted_at":"2026-10-02T09:30:00+08:00",""" +
                """"disclaimer_keys":["price_basis"],"availability":"$availability"}"""
        }
    }
}
