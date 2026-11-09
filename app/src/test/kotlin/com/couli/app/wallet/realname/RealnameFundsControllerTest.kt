package com.couli.app.wallet.realname

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.FormOwner
import com.couli.feature.wallet.RealNameBackend
import com.couli.feature.wallet.RealNameController
import com.couli.feature.wallet.RemoteRealNameBackend
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器级：幂等键去留的矩阵（结果未知 / 10001 / 10005 / 10004 id_verification 保留；改表单作废；D10-1018 断连后 10405 / 5xxxx / 42901 保留；其余结束）、同意先行、换号丢弃、文案分流。
 * 假后端，协程在 Unconfined 上同步推进（写法照 WalletReviewR3Test）。40901 的退避见 RealnameFundsBusyTest（走真实时钟）。
 */
@Suppress("LargeClass", "TooManyFunctions")
class RealnameFundsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000_000L
    private val env = Env()
    private val backend = Scripted()
    private var keys = 0
    private var backs = 0

    @After
    fun tearDown() {
        pageScope.cancel()
        scope.cancel()
    }

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"
        val opened = mutableListOf<String>()
        val submitted = mutableListOf<FormOwner>()

        override fun loggedIn() = user != null

        override fun sessionEpoch() = epoch

        override fun account(): String? = user

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        override fun open(
            route: String,
            params: JsonObject,
        ) {
            opened += route
        }

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) {
            opened += route
        }

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String) = "13800005678"

        override fun realnameSubmitted(owner: FormOwner) {
            submitted += owner
        }
    }

    private class FakeApi : WalletApi {
        val calls = mutableListOf<ApiCall>()

        override fun pendingFor(operationId: String): PendingKeyRecord? = null

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = ApiResult.LocalReject("call_cancelled")

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = ApiResult.LocalReject("call_cancelled")

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return ApiResult.Success(JsonObject(emptyMap()), "trace-000123", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private class Submit(
        val name: String,
        val idNo: String,
        val key: String,
    )

    private class Scripted : RealNameBackend {
        val events = mutableListOf<String>()
        val submits = mutableListOf<Submit>()
        var consent: suspend () -> ApiResult = { ApiResult.Success(JsonObject(emptyMap()), "trace-000123", null) }
        val replies = ArrayDeque<ApiResult>()
        var hold: CompletableDeferred<ApiResult>? = null

        override suspend fun recordConsent(): ApiResult {
            events += "consent"
            return consent()
        }

        override suspend fun submit(
            name: String,
            idNo: String,
            idempotencyKey: String,
        ): ApiResult {
            events += "submit"
            submits += Submit(name, idNo, idempotencyKey)
            hold?.let { return it.await() }
            return replies.removeFirstOrNull() ?: ApiResult.Success(JsonObject(emptyMap()), "trace-000123", null)
        }
    }

    private fun deps() =
        WalletDeps(
            api = FakeApi(),
            env = env,
            gates = WalletGates(scope) { now },
            tokens = StepUpTokenCache(),
            cache = null,
            mask = null,
            realname = backend,
            labor = null,
            clock = { now },
            newKey = { "key-" + (++keys) },
            service = scope,
        )

    private fun fill(
        name: String = NAME,
        idNo: String = ID,
    ) {
        controller.onNameChange(name)
        controller.onIdChange(idNo)
    }

    /** 【提交认证】→ 勾选 → 【同意并继续】。 */
    private fun attempt() {
        val owner = controller.state.value.owner
        controller.submit(owner)
        controller.toggleConsent(owner)
        controller.agreeAndSubmit(owner)
    }

    private fun lastKey() = backend.submits.last().key

    private fun firstKey() = backend.submits.first().key

    private fun assertSameKey(message: String) {
        assertEquals(message, 2, backend.submits.size)
        assertEquals(message, firstKey(), lastKey())
        assertEquals("同键同体：姓名", backend.submits.first().name == backend.submits.last().name, true)
    }

    private fun assertNewKey(message: String) {
        assertEquals(message, 2, backend.submits.size)
        assertTrue(message, firstKey() != lastKey())
    }

    // ---------------------------------------------------------------- 同意先行与失败不提交

    @Test fun consentFirstThenSubmit() {
        fill()
        attempt()
        assertEquals(listOf("consent", "submit"), backend.events)
    }

    @Test fun consentFailureDoesNotSubmit() {
        backend.consent = { failure(50001) }
        fill()
        attempt()
        assertEquals("同意失败不提交", listOf("consent"), backend.events)
        assertFalse("同意失败不算在途", controller.state.value.submitting)
        assertTrue("同意失败出错误条", controller.state.value.error != null)
    }

    @Test fun consentTransportFailureDoesNotSubmit() {
        backend.consent = { transport() }
        fill()
        attempt()
        assertEquals(listOf("consent"), backend.events)
        assertFalse(controller.state.value.submitting)
        assertTrue(controller.state.value.error != null)
    }

    @Test fun consentHeldThenSessionChangedDoesNotSubmit() {
        val hold = CompletableDeferred<ApiResult>()
        backend.consent = { hold.await() }
        fill()
        attempt()
        env.epoch += 1
        env.user = "user-b"
        hold.complete(ok())
        assertEquals("换号后不发实名提交", listOf("consent"), backend.events)
    }

    // ---------------------------------------------------------------- 键矩阵

    @Test fun transportFailureKeepsKeyForSameForm() {
        backend.replies += transport()
        fill()
        attempt()
        attempt()
        assertSameKey("断连后表单不变沿用原键")
    }

    @Test fun e10001KeepsKey() = assertKeptAfter(failure(10001))

    @Test fun e10005KeepsKey() = assertKeptAfter(failure(10005))

    private fun assertKeptAfter(result: ApiResult) {
        backend.replies += result
        fill()
        attempt()
        attempt()
        assertSameKey("补办后表单不变沿用原键")
    }

    @Test fun e10004IdVerificationReopensConsentAndKeepsKey() {
        backend.replies += failure(10004, """{"consent_type":"id_verification"}""")
        fill()
        attempt()
        assertTrue("重开单独同意", controller.state.value.consentOpen)
        assertFalse("默认不勾选", controller.state.value.consentChecked)
        val owner = controller.state.value.owner
        controller.toggleConsent(owner)
        controller.agreeAndSubmit(owner)
        assertSameKey("同意后原键重放")
        assertEquals("先记同意再重放", listOf("consent", "submit", "consent", "submit"), backend.events)
    }

    @Test fun businessErrorsEndKey() {
        listOf(20001, 30405, 30406, 30407, 10010).forEach { code ->
            setUpFresh()
            backend.replies += failure(code)
            fill()
            attempt()
            attempt()
            assertNewKey("业务错误 $code 后新键")
        }
    }

    @Test fun serverErrorsWithoutUnknownEndKey() {
        listOf(50001, 50301, 50401, 10405).forEach { code ->
            setUpFresh()
            backend.replies += failure(code)
            fill()
            attempt()
            attempt()
            assertNewKey("从未结果未知的 $code 后新键")
        }
    }

    @Test fun rateLimitWithoutUnknownEndsKey() {
        backend.replies += failure(42901, disableMillis = 500)
        fill()
        attempt()
        now += 1_000
        attempt()
        assertNewKey("从未结果未知的 42901 过禁用期后新键")
    }

    /** D10-1018：结果未知（断连）之后，10405 / 5xxxx / 42901 不结束键（核验按次计费）。 */
    @Test fun unknownThenNonEndingCodesKeepKey() {
        listOf(10405, 50001, 50401, 50301, 42901).forEach { code ->
            setUpFresh()
            backend.replies += transport()
            backend.replies += failure(code, disableMillis = 500.takeIf { code == 42901 }?.toLong())
            fill()
            attempt()
            attempt()
            assertEquals("第 2 次沿用原键（$code）", firstKey(), lastKey())
            now += 1_000
            attempt()
            assertEquals("第 3 次仍沿用原键（$code，D10-1018）", backend.submits[0].key, backend.submits[2].key)
        }
    }

    @Test fun unknownThenBusinessErrorEndsKey() {
        backend.replies += transport()
        backend.replies += failure(30406)
        fill()
        attempt()
        attempt()
        attempt()
        assertTrue("30406 写入幂等结果，键结束", backend.submits[0].key != backend.submits[2].key)
    }

    @Test fun editedFormVoidsRetainedKey() {
        listOf<ApiResult>(transport(), failure(10001), failure(10005)).forEach { first ->
            setUpFresh()
            backend.replies += first
            fill()
            attempt()
            fill(name = EDITED_NAME, idNo = EDITED_ID)
            attempt()
            assertNewKey("改了表单旧键作废（D10-887 ③）")
            assertFalse("新键不配旧请求体", backend.submits.first().name == backend.submits.last().name)
        }
    }

    @Test fun sameKeyNeverCarriesDifferentBody() {
        backend.replies += transport()
        backend.replies += transport()
        fill()
        attempt()
        fill(name = EDITED_NAME, idNo = EDITED_ID)
        attempt()
        attempt()
        val byKey = backend.submits.groupBy { it.key }
        byKey.forEach { (_, attempts) ->
            assertTrue("同一个键只配一个请求体", attempts.map { it.name to it.idNo }.toSet().size == 1)
        }
    }

    // ---------------------------------------------------------------- 文案分流

    @Test fun e50301FallsBackToGenericErrorWithTrace() {
        backend.replies += failure(50301)
        fill()
        attempt()
        val notice = controller.state.value.error
        assertEquals("D10-887 ②：实名场景回落 error.50001", "error.50001", notice?.key)
        assertTrue("带 trace6", notice?.showTrace6 == true)
    }

    @Test fun e10010ShowsGenericReverifyHintWithoutNavigation() {
        backend.replies += failure(10010, """{"method":"id_number","fail_limit":3}""")
        fill()
        attempt()
        val notice = controller.state.value.error
        assertEquals("error.10010", notice?.key)
        assertTrue("不导航（D10-887 ④）", env.opened.isEmpty())
        assertEquals("不出栈", 0, backs)
    }

    @Test fun e20001FieldsMapped() {
        mapOf("id_no" to RealNameController.FIELD_ID, "name" to RealNameController.FIELD_NAME).forEach { (raw, field) ->
            setUpFresh()
            backend.replies += failure(20001, """{"fields":["$raw"]}""")
            fill()
            attempt()
            assertEquals(listOf(field), controller.state.value.fieldErrors)
            assertEquals("字段错误不另出错误条", null, controller.state.value.error)
        }
    }

    @Test fun e30410LocksUntilLeave() {
        backend.replies += failure(30410)
        fill()
        attempt()
        assertTrue(controller.state.value.lockedToday)
        assertFalse("当日用完后不可再提交", controller.submitEnabled)
    }

    @Test fun repeatTapsSubmitOnce() {
        val hold = CompletableDeferred<ApiResult>()
        backend.hold = hold
        fill()
        val owner = controller.state.value.owner
        controller.submit(owner)
        controller.toggleConsent(owner)
        controller.agreeAndSubmit(owner)
        controller.agreeAndSubmit(owner)
        controller.submit(owner)
        assertEquals("在途再点不重发", 1, backend.submits.size)
        assertFalse("在途不重开半屏", controller.state.value.consentOpen)
        hold.complete(ok())
    }

    // ---------------------------------------------------------------- 归属与成功

    @Test fun successReportsFormOwnerAndClears() {
        fill()
        val owner = controller.state.value.owner
        attempt()
        assertEquals("用表单固定身份记已实名（换号后到达的结果丢弃）", listOf(owner), env.submitted)
        assertEquals("出栈一次", 1, backs)
        assertEquals("清空姓名", "", controller.state.value.name)
        assertEquals("清空身份证号", "", controller.state.value.idNo)
    }

    @Test fun resultArrivingAfterSessionChangeIsDiscarded() {
        val hold = CompletableDeferred<ApiResult>()
        backend.hold = hold
        fill()
        attempt()
        env.epoch += 1
        env.user = "user-b"
        hold.complete(ok())
        assertTrue("换号后到达的成功结果不记已实名", env.submitted.isEmpty())
        assertEquals("不出栈", 0, backs)
    }

    @Test fun resultArrivingAfterLeaveIsIgnored() {
        val hold = CompletableDeferred<ApiResult>()
        backend.hold = hold
        fill()
        attempt()
        controller.leave()
        hold.complete(ok())
        assertTrue(env.submitted.isEmpty())
        assertEquals(0, backs)
    }

    // ---------------------------------------------------------------- 真实后端的请求形状（不经 UI）

    @Test fun remoteBackendSubmitCall() =
        runBlocking {
            val api = FakeApi()
            val remote = RemoteRealNameBackend(api, { null }, { 3 })
            remote.submit(NAME, ID, "key-abc12345")
            assertEquals("发出一次实名提交", 1, api.calls.size)
            val call = api.calls.single()
            assertEquals("submitRealname", call.operationId)
            assertEquals("key-abc12345", call.idempotencyKey)
            val body = Json.parseToJsonElement(call.body!!).jsonObject
            assertEquals(setOf("name", "id_no"), body.keys)
            assertTrue(body.getValue("name").jsonPrimitive.content == NAME)
        }

    @Test fun remoteBackendRecordConsentCall() =
        runBlocking {
            val api = FakeApi()
            val config = JsonObject(mapOf("legal" to JsonObject(mapOf("privacy" to JsonObject(mapOf("version" to JsonPrimitive(9)))))))
            val remote = RemoteRealNameBackend(api, { config }, { 3 })
            val result = remote.recordConsent()
            assertTrue(result is ApiResult.Success)
            val call = api.calls.single()
            assertEquals("recordConsent", call.operationId)
            val body = Json.parseToJsonElement(call.body!!).jsonObject
            assertEquals("id_verification", body.getValue("type").jsonPrimitive.content)
            assertEquals("true", body.getValue("accepted").jsonPrimitive.content)
            assertEquals("realname_sheet", body.getValue("channel").jsonPrimitive.content)
            assertEquals("配置版本优先", "9", body.getValue("version").jsonPrimitive.content)
        }

    /** D10-1033 回落分支：配置取不到 legal.privacy.version（整个缺或只缺 privacy）时上报本机已同意版本。 */
    @Test fun remoteBackendFallsBackToConsentedVersion() =
        runBlocking {
            listOf<JsonObject?>(null, JsonObject(emptyMap()), JsonObject(mapOf("legal" to JsonObject(emptyMap())))).forEach { config ->
                val api = FakeApi()
                val remote = RemoteRealNameBackend(api, { config }, { 3 })
                assertTrue("回落后照常记同意", remote.recordConsent() is ApiResult.Success)
                val body = Json.parseToJsonElement(api.calls.single().body!!).jsonObject
                assertEquals("回落本机已同意版本", "3", body.getValue("version").jsonPrimitive.content)
            }
        }

    @Test fun remoteBackendNoVersionDoesNotSendConsent() =
        runBlocking {
            val api = FakeApi()
            val remote = RemoteRealNameBackend(api, { null }, { null })
            val result = remote.recordConsent()
            assertFalse("取不到版本不算成功（不提交）", result is ApiResult.Success)
            assertTrue("取不到版本不发请求", api.calls.isEmpty())
        }

    private fun setUpFresh() {
        backend.submits.clear()
        backend.events.clear()
        backend.replies.clear()
        keys = 0
        // 换一个全新的执行器（键状态不跨执行器）
        freshController = RealNameController(deps(), pageScope) { backs += 1 }
    }

    private var freshController: RealNameController? = null

    private val controller: RealNameController get() = freshController ?: original

    private val original by lazy { RealNameController(deps(), pageScope) { backs += 1 } }

    private fun ok(): ApiResult = ApiResult.Success(JsonObject(emptyMap()), "trace-000123", null)

    private fun failure(
        code: Int,
        data: String = "{}",
        disableMillis: Long? = null,
    ): ApiResult =
        ApiResult.Failure(
            code,
            "",
            Json.parseToJsonElement(data).jsonObject,
            "trace-000123",
            ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false, disableMillis = disableMillis),
            null,
        )

    private fun transport(): ApiResult = ApiResult.TransportFailure("disconnect", null, null, false)

    private companion object {
        const val NAME = "测试实名甲"
        const val ID = "00000000000000001X"
        const val EDITED_NAME = "测试实名乙"
        const val EDITED_ID = "00000000000000009X"
    }
}
