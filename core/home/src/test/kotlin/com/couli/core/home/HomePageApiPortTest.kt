package com.couli.core.home

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-HOME-PAGE-API-android 测试 v1（独立测试作者）：首页两个契约接口的生产端口 [ApiClientHomeContractPort]（brief §2.2；D10-604 ①、D10-607）。
 *
 * 端口只把 [HomeContractPort] 的两次调用翻成经 [PageApi]（生产为 ApiClientPageApi(ApiClient)）的 [ApiCall]：
 * - getPage → operationId `getPage`，路径参数只有 page_key；
 * - listSectionItems → operationId `listPageSectionItems`（rebate-platform 00deded0ef6f openapi.yaml:2479），路径参数 page_key + section_id，
 *   查询串只有 `cursor=<RFC 3986 百分号编码>`（契约：cursor 不透明、原样带回；每页 20 条是服务端常量，没有 limit 参数；不带 kind、pool_id、频道、排序）；
 * - [HomeCall.allowed] 是发送前闸门、[HomeCall.stillOwned] 是归属，都原样交给 [CallOwnership]；业务码与传输失败原样交回（30701 不在端口里改写）。
 * 端口不经 ApiClient 之外的任何地方发请求，也不在端口里写死数据（「仍只用包内默认」的错误实现在这里就红）。
 */
class HomePageApiPortTest {
    /** 只记录、不联网的 PageApi：遵守发送前闸门（与 ApiClient 写出前核对同一口径：为 false 本地拒绝、不算发出）。 */
    private class RecordingPageApi(
        var reply: (ApiCall) -> ApiResult = { ok() },
    ) : PageApi {
        val attempted = mutableListOf<ApiCall>()
        val sent = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            attempted += call
            val ownership = call.ownership
            if (ownership != null && (ownership.sendAllowed?.invoke() == false || !ownership.stillOwned())) {
                return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            }
            sent += call
            return reply(call)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(3, 5)
    }

    private fun call(
        anonymous: Boolean = false,
        account: String? = "user-a",
        allowed: () -> Boolean = { true },
        owned: () -> Boolean = { true },
    ) = HomeCall(anonymous = anonymous, expectedAccount = account, allowed = allowed, stillOwned = owned)

    @Test
    fun p01GetPageSendsExactlyOneGetPageCallWithOnlyThePageKey() {
        val api = RecordingPageApi()
        val result = runBlocking { ApiClientHomeContractPort(api).getPage("home", call()) }
        assertTrue("成功结果原样交回", result is ApiResult.Success)
        val sent = api.sent.single()
        assertEquals("operationId 照生成表（不是手写路径）", "getPage", sent.operationId)
        assertEquals("路径参数只有 page_key=home", mapOf("page_key" to "home"), sent.pathParams)
        assertTrue("getPage 不带任何查询参数：${sent.query}", sent.query.isNullOrEmpty())
        assertNull("GET 没有请求体", sent.body)
        assertNull("读接口不带 Idempotency-Key", sent.idempotencyKey)
        assertNull("不带二次验证票据", sent.stepUpToken)
        assertEquals("发起时固定的账号原样交给执行层", "user-a", sent.expectedAccount)
        assertNotNull("带调用归属（作废后不交结果、不写出）", sent.ownership)
    }

    @Test
    fun p02FirstSectionPageSendsTheSectionPathAndNoQueryAtAll() {
        val api = RecordingPageApi()
        runBlocking { ApiClientHomeContractPort(api).listSectionItems("home", "demo-grid", null, call()) }
        val sent = api.sent.single()
        assertEquals("listPageSectionItems", sent.operationId)
        assertEquals("路径参数恰为 page_key + section_id", mapOf("page_key" to "home", "section_id" to "demo-grid"), sent.pathParams)
        assertTrue("首屏不带任何查询参数（不带 kind / pool_id / limit / 频道 / 排序）：${sent.query}", sent.query.isNullOrEmpty())
        assertNull(sent.body)
        assertNull(sent.idempotencyKey)
    }

    @Test
    fun p03CursorIsTheOnlyQueryParameterAndIsPercentEncoded() {
        val api = RecordingPageApi()
        val port = ApiClientHomeContractPort(api)
        runBlocking { port.listSectionItems("home", "feed", "Y3Vy+c29y/LWV4=", call()) }
        runBlocking { port.listSectionItems("home", "feed", "c2&kind=product_pool", call()) }
        assertEquals("游标按 RFC 3986 编码、原样带回", "cursor=Y3Vy%2Bc29y%2FLWV4%3D", api.sent[0].query)
        assertEquals("游标里的 & / = 不得变成别的参数", "cursor=c2%26kind%3Dproduct_pool", api.sent[1].query)
        api.sent.forEach { assertEquals(mapOf("page_key" to "home", "section_id" to "feed"), it.pathParams) }
    }

    @Test
    fun p04SendGateAndOwnershipAreTheHomeCallsOwn() {
        var allowed = true
        var owned = true
        val api = RecordingPageApi()
        val port = ApiClientHomeContractPort(api)
        val home = call(allowed = { allowed }, owned = { owned })
        runBlocking { port.getPage("home", home) }
        val ownership = requireNotNull(api.attempted.single().ownership)
        assertTrue(ownership.sendAllowed?.invoke() ?: true)
        assertTrue(ownership.stillOwned())
        allowed = false
        assertEquals("发送前闸门就是 HomeCall.allowed（每次写出前现读，不是构造时取一次）", false, ownership.sendAllowed?.invoke())
        allowed = true
        owned = false
        assertFalse("归属就是 HomeCall.stillOwned", ownership.stillOwned())
        // 闸门关着时：一个请求也不算发出，结果不是成功
        allowed = false
        owned = true
        val gated = runBlocking { port.listSectionItems("home", "feed", null, home) }
        assertFalse("闸门关着不得交出成功", gated is ApiResult.Success)
        assertEquals("闸门关着不发", 1, api.sent.size)
    }

    @Test
    fun p05ServerAndTransportResultsPassThroughUnchanged() {
        val replies =
            listOf(
                failure(30701),
                failure(20001),
                ApiResult.TransportFailure("disconnect", null, null, false),
                ApiResult.TransportFailure("timeout", null, null, false),
                failure(42901),
                failure(10405),
            )
        replies.forEach { reply ->
            val api = RecordingPageApi { reply }
            val port = ApiClientHomeContractPort(api)
            assertSame("getPage 原样交回 $reply", reply, runBlocking { port.getPage("home", call()) })
            assertSame("listSectionItems 原样交回 $reply", reply, runBlocking { port.listSectionItems("home", "feed", null, call()) })
        }
    }

    @Test
    fun p06BasicModeCallsAreStillSentTheClientStripsCredentials() {
        // 基本模式（anonymous = true）：端口照样发；是否带 Authorization / X-Device-Id 由 ApiClient 按隐私结论决定（RequestHeaderPolicy），端口不另拒
        val api = RecordingPageApi()
        val port = ApiClientHomeContractPort(api)
        runBlocking { port.getPage("home", call(anonymous = true, account = null)) }
        runBlocking { port.listSectionItems("home", "demo-today", null, call(anonymous = true, account = null)) }
        assertEquals(listOf("getPage", "listPageSectionItems"), api.sent.map { it.operationId })
        assertNull(api.sent[0].expectedAccount)
    }

    @Test
    fun p07EachCallIsSentOnceThePortDoesNotRetryOrFanOut() {
        val api = RecordingPageApi { failure(50001) }
        val port = ApiClientHomeContractPort(api)
        runBlocking { port.getPage("home", call()) }
        runBlocking { port.listSectionItems("home", "feed", "c2", call()) }
        assertEquals("端口一次调用只交给执行层一次（重试归 ApiClient 的统一策略）", 2, api.attempted.size)
    }

    private companion object {
        fun ok(): ApiResult = ApiResult.Success(JsonObject(mapOf("items" to JsonPrimitive("x"))), "trace-port-000001", null)

        fun failure(code: Int): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                "trace-port-$code",
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )
    }
}
