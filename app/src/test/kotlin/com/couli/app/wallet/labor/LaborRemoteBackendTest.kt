package com.couli.app.wallet.labor

import com.couli.app.wallet.labor.LaborContract.text
import com.couli.core.generated.ApiOperations
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffectKind
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.LaborAgreementController
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.LaborSnapshot
import com.couli.feature.wallet.WalletDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-LABOR-AGREEMENT-API：真实劳务协议后端 RemoteLaborBackend 经 [com.couli.feature.wallet.WalletApi] 调契约里已有的两个 operation，接到现有的
 * [LaborAgreementController]（页面口径不改）。网络换成可控的假 WalletApi（记下每个 ApiCall，同步回答），协程在 Unconfined 上同步推进；签名、
 * 请求头与线上请求形状另见 LaborRemoteHttpTest（真实 ApiClient + MockWebServer）。
 *
 * 契约与控制器的差异（以契约为准，由 RemoteLaborBackend 适配）：契约 data.version 是整数、全文给地址 text_url（没有 text），签署请求体是
 * {version: 整数, text_sha256, agreed: true}；控制器按字符串 version / text / text_sha256 解析，sign(version: String, textSha256)。
 */
@Suppress("TooManyFunctions")
class LaborRemoteBackendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000_000L
    private val env = LaborRemoteEnv()
    private val api = LaborRemoteApi()
    private val gates = WalletGates(scope) { now }

    /**
     * fetchText 收到的地址与期望摘要；[fetchReply] 决定全文（null = 取不到，含生产实现里摘要不符）。摘要核对本身由生产取文函数做
     * （LaborTextFetcherTest），这里的替身只核对后端把服务端给的 text_sha256 显式交给了取文函数。
     */
    private val fetched = mutableListOf<String>()
    private val expectedShas = mutableListOf<String>()
    private var fetchReply: (String) -> String? = { LaborContract.TEXT }
    private val fetch: suspend (String, String) -> String? = { url, expectedSha256 ->
        fetched += url
        expectedShas += expectedSha256
        fetchReply(url)
    }

    /** newKey 发出的键（按次序）。 */
    private val issued = mutableListOf<String>()
    private val newKey: () -> String = { "labor-key-%04d".format(issued.size + 1).also { issued += it } }

    /** 协议读取的回答（缺省契约样例 needSign）；签署的回答按第 n 次（从 0 起）给。 */
    private var getReply: () -> ApiResult = { LaborResults.ok(LaborContract.needSign) }
    private var signReply: (Int) -> ApiResult = { LaborResults.ok("{}") }
    private var backs = 0

    @After
    fun tearDown() {
        pageScope.cancel()
        scope.cancel()
    }

    private fun backend(): LaborBackend {
        api.answer = { call: ApiCall ->
            when (call.operationId) {
                LaborContract.GET_OPERATION -> getReply()
                LaborContract.SIGN_OPERATION -> signReply(api.of(LaborContract.SIGN_OPERATION).size - 1)
                else -> ApiResult.LocalReject("unexpected_${call.operationId}")
            }
        }
        return LaborRemoteReflect.backend(api, fetch, { env.user }, newKey)
    }

    private fun deps(labor: LaborBackend?) =
        WalletDeps(
            api = api,
            env = env,
            gates = gates,
            tokens = StepUpTokenCache(),
            cache = null,
            mask = null,
            realname = null,
            labor = labor,
            clock = { now },
            service = scope,
        )

    private fun controller(labor: LaborBackend): LaborAgreementController =
        LaborAgreementController(deps(labor), pageScope) { backs += 1 }.also { it.load() }

    private fun signs(): List<ApiCall> = api.of(LaborContract.SIGN_OPERATION)

    private fun ApiCall.json(): JsonObject = Json.parseToJsonElement(checkNotNull(body) { "签署要有请求体" }).jsonObject

    // ---------------------------------------------------------------- 读取：getLaborAgreement

    @Test fun currentCallsGetLaborAgreementOnceAndFetchesTextUrl() {
        val backend = backend()
        val result = runBlocking { backend.current() }
        val call = api.calls.single()
        assertEquals("调契约 operation getLaborAgreement", LaborContract.GET_OPERATION, call.operationId)
        assertNull("读取没有请求体", call.body)
        assertNull("读取没有查询串", call.query)
        assertNull("读取不带幂等键", call.idempotencyKey)
        val operation = checkNotNull(ApiOperations.all[LaborContract.GET_OPERATION])
        assertEquals("GET", operation.method)
        assertEquals(LaborContract.GET_PATH, operation.pathTemplate)
        assertEquals("x-auth realname", "realname", operation.auth)
        assertEquals("全文按契约 text_url 取，只取一次", listOf(LaborContract.textUrl), fetched)
        assertEquals("取文时显式带上服务端给的 text_sha256 供核对", listOf(LaborContract.sha), expectedShas)
        assertTrue("读取成功交给控制器的是 Success", result is ApiResult.Success)
    }

    @Test fun contractResponseReachesContentWithTextVersionAndSha() {
        val controller = controller(backend())
        val state = controller.state.value
        assertEquals("契约形状的响应进入 CONTENT（不是 ERROR）", LaborSnapshot.CONTENT, state.phase)
        assertEquals("版本：契约整数 2 → 控制器的版本文字 \"2\"", LaborContract.version.toString(), state.version)
        assertEquals("正文：text_url 指向的全文", LaborContract.TEXT, state.text)
        assertEquals("sha256：服务端给的原值", LaborContract.sha, state.sha256)
        assertFalse("默认不勾选", state.checked)
        controller.toggle()
        assertTrue("勾选后【同意并签署】可用", controller.signEnabled)
    }

    @Test fun needSignFalseFollowsControllerCurrentRule() {
        getReply = { LaborResults.ok(LaborContract.signed) }
        val controller = controller(backend())
        val state = controller.state.value
        // 控制器现口径：不看 need_sign，照常展示全文、可勾选可签（是否进本页由提现守卫决定）
        assertEquals(LaborSnapshot.CONTENT, state.phase)
        assertEquals(LaborContract.signed.text("version"), state.version)
        assertEquals(LaborContract.TEXT, state.text)
        assertEquals(LaborContract.signed.text("text_sha256"), state.sha256)
        assertEquals("取文核对的是这次响应给的摘要", listOf(LaborContract.signed.text("text_sha256")), expectedShas)
        controller.toggle()
        assertTrue(controller.signEnabled)
        assertTrue("need_sign=false 不自动签署", signs().isEmpty())
    }

    @Test fun textUnavailableIsNotContentAndRetryLoads() {
        // 取不到或摘要不符（生产取文函数都返回 null）
        fetchReply = { null }
        val controller = controller(backend())
        assertEquals("全文取不到：不进 CONTENT（不展示空协议给人签）", LaborSnapshot.ERROR, controller.state.value.phase)
        assertNotNull("有可重试的错误提示", controller.state.value.error)
        controller.toggle()
        assertFalse("取不到全文不可签", controller.signEnabled)
        controller.sign()
        assertTrue("没有发签署", signs().isEmpty())
        fetchReply = { LaborContract.TEXT }
        controller.load()
        assertEquals("【重试】后进入 CONTENT", LaborSnapshot.CONTENT, controller.state.value.phase)
        assertEquals(LaborContract.TEXT, controller.state.value.text)
    }

    @Test fun readErrorCodesPassThroughToControllerErrorHandling() {
        listOf(30304, 10403).forEach { code ->
            val failure = LaborResults.fail(code)
            getReply = { failure }
            fetched.clear()
            val backend = backend()
            assertSame("$code 原样上抛（不吞、不改写）", failure, runBlocking { backend.current() })
            assertTrue("$code 不取全文", fetched.isEmpty())
            val controller = controller(backend)
            assertEquals("$code 进控制器的错误态", LaborSnapshot.ERROR, controller.state.value.phase)
            assertEquals(
                "$code 按既有错误处理取文案",
                WalletErrors.of(failure).textKey,
                controller.state.value.error
                    ?.key,
            )
        }
    }

    // ---------------------------------------------------------------- 签署：signLaborAgreement

    @Test fun signSendsContractBodyWithServerValuesAndIdempotencyKey() {
        val controller = controller(backend())
        controller.toggle()
        controller.sign()
        val call = signs().single()
        val operation = checkNotNull(ApiOperations.all[LaborContract.SIGN_OPERATION])
        assertEquals("POST", operation.method)
        assertEquals(LaborContract.SIGN_PATH, operation.pathTemplate)
        assertTrue("x-signed：由网络层签名", operation.signed)
        assertTrue("x-idempotent", operation.idempotent)
        val body = call.json()
        assertEquals("请求体键恰好是契约的三个", setOf("version", "text_sha256", "agreed"), body.keys)
        val version = body.getValue("version") as JsonPrimitive
        assertFalse("version 是整数（契约 int32），不是字符串", version.isString)
        assertEquals("version 原样回传服务端给的值", LaborContract.version.toString(), version.content)
        val sha = body.getValue("text_sha256") as JsonPrimitive
        assertTrue(sha.isString)
        assertEquals("text_sha256 原样回传服务端给的值", LaborContract.sha, sha.content)
        val agreed = body.getValue("agreed") as JsonPrimitive
        assertFalse("agreed 是布尔", agreed.isString)
        assertEquals("agreed=true", "true", agreed.content)
        assertEquals("带调用方给的幂等键（newKey 第一把）", issued.first(), call.idempotencyKey)
        assertTrue("幂等键符合契约格式", LaborContract.KEY_PATTERN.matches(checkNotNull(call.idempotencyKey)))
        assertNull("不带查询串", call.query)
        assertEquals("签署成功后出栈回 Withdraw（控制器现口径）", 1, backs)
        assertFalse(controller.state.value.signing)
    }

    @Test fun retryOfTheSameSigningAfterUnknownOutcomeReusesTheKey() {
        signReply = { n -> if (n == 0) LaborResults.disconnect(issued.lastOrNull()) else LaborResults.ok("{}") }
        val controller = controller(backend())
        controller.toggle()
        controller.sign()
        assertEquals("断连：停在本页、给错误提示", 0, backs)
        assertNotNull(controller.state.value.error)
        assertTrue("断连后可再点", controller.signEnabled)
        controller.sign()
        val (first, second) = signs()
        assertNotNull(first.idempotencyKey)
        assertEquals("同一次签署的重试用同一个幂等键", first.idempotencyKey, second.idempotencyKey)
        assertEquals("重试请求体不变", first.json(), second.json())
        assertEquals("重试成功后出栈", 1, backs)
    }

    @Test fun signingAfterSuccessUsesAFreshKey() {
        val backend = backend()
        runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        val (first, second) = signs()
        assertNotEquals("成功之后是新的一次签署：换新键", first.idempotencyKey, second.idempotencyKey)
    }

    @Test fun differentVersionOrDigestNeverReusesTheHeldKey() {
        signReply = { LaborResults.disconnect(null) }
        val backend = backend()
        runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        runBlocking { backend.sign((LaborContract.version + 1).toString(), LaborContract.sha) }
        runBlocking { backend.sign(LaborContract.version.toString(), OTHER_SHA) }
        val keys = signs().map { it.idempotencyKey }
        assertEquals("三次都发出", 3, keys.size)
        assertEquals("版本或摘要不同不沿用旧键（同键异体会得 20901）", 3, keys.toSet().size)
    }

    @Test fun anotherAccountNeverReusesTheHeldKey() {
        signReply = { LaborResults.disconnect(null) }
        val backend = backend()
        runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        env.user = "user-b"
        env.epoch += 1
        runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        val (first, second) = signs()
        assertNotEquals("换了账号：不沿用上一个账号结果未知的键", first.idempotencyKey, second.idempotencyKey)
    }

    @Test fun signErrorCodesPassThroughToControllerErrorHandling() {
        SIGN_ERRORS.forEach { (code, data) ->
            val kind = if (code == FORCE_UPDATE_CODE) ErrorEffectKind.FORCE_UPDATE else ErrorEffectKind.TOAST
            val failure = LaborResults.fail(code, data, kind)
            signReply = { failure }
            api.calls.clear()
            backs = 0
            val forcedBefore = env.forced
            val recording = Recording(backend())
            val controller = controller(recording)
            controller.toggle()
            controller.sign()
            assertEquals("$code 发出一次签署", 1, signs().size)
            assertSame("$code 原样上抛（不吞、不改写）", failure, recording.signed.single())
            val state = controller.state.value
            assertEquals("$code 停在本页", 0, backs)
            assertFalse("$code 结束签署中", state.signing)
            assertEquals("$code 按既有错误处理取文案", WalletErrors.of(failure).textKey, state.error?.key)
            val forced = env.forced - forcedBefore
            assertEquals("$code 强更只在 10405 FORCE_UPDATE 时", if (code == FORCE_UPDATE_CODE) 1 else 0, forced)
        }
    }

    /** 记下真实后端交给控制器的签署结果（核对原样上抛）。 */
    private class Recording(
        private val inner: LaborBackend,
    ) : LaborBackend {
        val signed = mutableListOf<ApiResult>()

        override suspend fun current(): ApiResult = inner.current()

        override suspend fun sign(
            version: String,
            textSha256: String,
        ): ApiResult = inner.sign(version, textSha256).also { signed += it }
    }

    private companion object {
        const val OTHER_SHA = "0000000000000000000000000000000000000000000000000000000000000000"
        const val FORCE_UPDATE_CODE = 10405
        val SIGN_ERRORS =
            listOf(
                30304 to "{}",
                10403 to "{}",
                10004 to """{"consent_type":"labor_agreement"}""",
                20001 to """{"fields":["text_sha256"]}""",
                FORCE_UPDATE_CODE to "{}",
            )
    }
}
