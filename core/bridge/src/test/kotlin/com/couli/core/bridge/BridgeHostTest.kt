package com.couli.core.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BridgeHost 的接线测试（判定层之外）：在途 id、本端未实现与 P1、强更守卫、超时、归属（invalidate、账号变化）、
 * 判定顺序 login → gesture → params、被拒调用的埋点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeHostTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val replies = mutableListOf<String>()
    private val rejected = mutableListOf<Pair<String, Int>>()
    private var state = BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-A", update = BridgeUpdateState.SUPPORTED)
    private var userGate = CompletableDeferred<BridgeOutcome>()
    private val navOpens = mutableListOf<String>()
    private val tokenScopes = mutableListOf<Boolean>()
    private val logins = mutableListOf<Unit>()
    private var updateGate: CompletableDeferred<String>? = null
    private var permissionValidity: BridgeCallValidity? = null
    private var userValidity: BridgeCallValidity? = null
    private val permissionGate = CompletableDeferred<BridgeOutcome>()

    private val services =
        object : BridgeServices {
            override fun env() = JsonObject(emptyMap())

            override fun config(keys: List<String>) = BridgeOutcome.Ok()

            override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
                userValidity = validity
                return userGate.await()
            }

            override suspend fun h5Token(
                readOnly: Boolean,
                validity: BridgeCallValidity,
            ): BridgeOutcome {
                tokenScopes += readOnly
                return BridgeOutcome.Ok()
            }

            override suspend fun login(update: String): BridgeOutcome {
                logins += Unit
                return BridgeOutcome.Ok()
            }

            override suspend fun signedRequest(
                method: String,
                path: String,
                body: JsonElement?,
                validity: BridgeCallValidity,
            ) = BridgeOutcome.Ok()

            override suspend fun navOpen(
                route: String,
                params: JsonObject,
                update: String,
            ): BridgeOutcome {
                navOpens += update
                return if (update != BridgeUpdateState.SUPPORTED) BridgeOutcome.Fail(BridgeCode.NATIVE_ERROR) else BridgeOutcome.Ok()
            }

            override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

            override suspend fun clipboardRead() = BridgeOutcome.Ok()

            override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

            override suspend fun pushStatus() = BridgeOutcome.Ok()

            override suspend fun requestPermission(
                type: String,
                validity: BridgeCallValidity,
            ): BridgeOutcome {
                permissionValidity = validity
                return permissionGate.await()
            }

            override fun track(
                name: String,
                props: JsonObject,
            ) = BridgeOutcome.Ok()

            override fun reportRejected(
                method: String,
                code: Int,
            ) {
                rejected += method to code
            }
        }

    private val ui =
        object : BridgeUi {
            override fun toast(
                text: String,
                long: Boolean,
            ) = Unit

            override fun showLoading(text: String?) = Unit

            override fun hideLoading() = Unit

            override fun setNavBar(params: JsonObject) = true

            override fun close() = Unit
        }

    private val host =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
            caller = { state },
            services = services,
            ui = ui,
            awaitUpdate = { updateGate?.await() ?: BridgeUpdateState.UNKNOWN },
            scope = scope,
        )

    @Suppress("LongParameterList")
    private fun call(
        method: String,
        params: String = "{}",
        id: String = "id-1",
        gesture: Boolean = false,
        mainFrame: Boolean = true,
        origin: String = ORIGIN,
    ) {
        host.handle("""{"v":1,"id":"$id","method":"$method","params":$params}""", origin, mainFrame, gesture) { replies += it }
        scope.runCurrent()
    }

    private fun lastCode(): Int =
        Json
            .parseToJsonElement(replies.last())
            .jsonObject
            .getValue("code")
            .jsonPrimitive.int

    @Test
    fun successCarriesTheContractEnvelope() {
        call("ui.toast", """{"text":"hello"}""")
        val reply = Json.parseToJsonElement(replies.single()).jsonObject
        assertEquals("id-1", reply.getValue("id").jsonPrimitive.content)
        assertEquals(0, reply.getValue("code").jsonPrimitive.int)
        assertEquals("", reply.getValue("msg").jsonPrimitive.content)
    }

    @Test
    fun subframeAndForeignOriginAreRejectedBeforeAnything() {
        call("app.getEnv", mainFrame = false)
        assertEquals(90403, lastCode())
        call("app.getEnv", id = "id-2", origin = "https://foreign.example.test")
        assertEquals(90403, lastCode())
    }

    @Test
    fun duplicateInFlightIdIsRejectedWithoutRunning() {
        call("auth.getUser")
        assertTrue("第一次还在途", replies.isEmpty())
        call("auth.getUser")
        assertEquals(90002, lastCode())
        userGate.complete(BridgeOutcome.Ok())
        scope.runCurrent()
        assertEquals(0, lastCode())
        assertEquals(2, replies.size)
    }

    @Test
    fun notImplementedMethodsAnswer90001AfterAdmissionAndWhitelistComesFirst() {
        call("trade.openProduct", """{"platform":"taobao","product_key":"vector-product"}""")
        assertEquals(90001, lastCode())
        // ext.openApp：trade_only 目标的白名单拒绝先于「本端未实现」
        call("ext.openApp", """{"target":"taobao","url":"https://example.com/"}""", id = "id-2")
        assertEquals(90403, lastCode())
        // P1（since 为 null）
        call("media.scan", id = "id-3")
        assertEquals(90001, lastCode())
    }

    @Test
    fun orderIsLoginThenGestureThenParams() {
        state = state.copy(accountKey = null)
        call("net.signedRequest")
        assertEquals(90401, lastCode())
        state = state.copy(accountKey = "account-A")
        call("net.signedRequest", id = "id-2")
        assertEquals(90404, lastCode())
        call("net.signedRequest", id = "id-3", gesture = true)
        assertEquals(90002, lastCode())
        assertEquals(listOf("net.signedRequest" to 90401, "net.signedRequest" to 90404, "net.signedRequest" to 90002), rejected)
    }

    @Test
    fun clipboardWriteNeedsAGestureEvenForGuests() {
        state = state.copy(accountKey = null)
        call("clipboard.write", """{"text":"x"}""")
        assertEquals(90404, lastCode())
        call("clipboard.write", """{"text":"x"}""", id = "id-2", gesture = true)
        assertEquals(0, lastCode())
    }

    @Test
    fun unknownUpdateStateRunsReadOnlyMethodsAndEndsTheRestWith90500() {
        state = state.copy(update = BridgeUpdateState.UNKNOWN)
        call("app.getEnv")
        assertEquals(0, lastCode())
        call("auth.login", id = "id-2")
        assertEquals(90500, lastCode())
        call("nav.open", """{"route":"Home"}""", id = "id-3")
        assertEquals("nav.open 带着未知状态交给 Router（只开只读页面）", listOf(BridgeUpdateState.UNKNOWN), navOpens)
        assertEquals(90500, lastCode())
    }

    @Test
    fun belowMinimumEndsEverythingWith90500() {
        state = state.copy(update = BridgeUpdateState.BELOW)
        call("app.getEnv")
        assertEquals(90500, lastCode())
        state = state.copy(fromForceUpdatePage = true)
        call("app.getEnv", id = "id-2")
        assertEquals("从强更页进入的页面里只读方法照常", 0, lastCode())
    }

    @Test
    fun checkingWaitsThenFallsBackToUnknown() {
        state = state.copy(update = BridgeUpdateState.CHECKING)
        call("auth.login")
        assertEquals(90500, lastCode())
    }

    /** APP-04 三端共性第 6 条（BR-ID-32）：只有强更结论为受支持（OK / BELOW_RECOMMENDED）时申请 standard，其余一律 read_only。 */
    @Test
    fun h5TokenIsReadOnlyUnlessTheUpdateStateIsSupported() {
        call("auth.getH5Token", id = "supported")
        state = state.copy(update = BridgeUpdateState.UNKNOWN)
        call("auth.getH5Token", id = "unknown")
        state = state.copy(update = BridgeUpdateState.CHECKING)
        call("auth.getH5Token", id = "checking")
        state = state.copy(update = BridgeUpdateState.BELOW, fromForceUpdatePage = true)
        call("auth.getH5Token", id = "below")
        assertEquals("standard 只在 supported", listOf(false, true, true, true), tokenScopes)
    }

    /** APP-04 三端共性第 4 条：强更检查的等待结束后、执行副作用之前再核对一次隐私与账号；变了就不执行（90500）。 */
    @Test
    fun privacyOrAccountChangeWhileWaitingForTheUpdateCheckSkipsTheSideEffect() {
        val gate = CompletableDeferred<String>()
        updateGate = gate
        state = state.copy(update = BridgeUpdateState.CHECKING)
        call("auth.login")
        assertTrue("还在等强更检查", replies.isEmpty())
        state = state.copy(privacy = BridgePrivacy.BASIC_MODE, update = BridgeUpdateState.SUPPORTED)
        gate.complete(BridgeUpdateState.SUPPORTED)
        scope.runCurrent()
        assertEquals(90500, lastCode())
        assertTrue("等待期间撤回了同意：登录没有执行", logins.isEmpty())

        val second = CompletableDeferred<String>()
        updateGate = second
        state = state.copy(privacy = BridgePrivacy.CONSENTED, update = BridgeUpdateState.CHECKING)
        call("auth.login", id = "id-2")
        state = state.copy(accountKey = "account-B")
        second.complete(BridgeUpdateState.SUPPORTED)
        scope.runCurrent()
        assertEquals(90500, lastCode())
        assertTrue("等待期间换了账号：登录没有执行", logins.isEmpty())

        val third = CompletableDeferred<String>()
        updateGate = third
        state = state.copy(update = BridgeUpdateState.CHECKING)
        call("auth.login", id = "id-3")
        host.invalidate()
        third.complete(BridgeUpdateState.SUPPORTED)
        scope.runCurrent()
        assertEquals(90500, lastCode())
        assertTrue("等待期间页面失效：登录没有执行", logins.isEmpty())
    }

    @Test
    fun asyncMethodsEndWithTimeoutAtTheContractTimeout() {
        call("auth.getUser")
        scope.advanceTimeBy(USER_TIMEOUT_MS + 1)
        scope.runCurrent()
        assertEquals(90003, lastCode())
    }

    @Test
    fun invalidateCancelsInFlightCallsAndTheirResultsAreNotDelivered() {
        call("auth.getUser")
        host.invalidate()
        scope.runCurrent()
        assertEquals(90500, lastCode())
        // 同一个 id 可以再用，不被旧调用的结算误删
        userGate = CompletableDeferred()
        call("auth.getUser")
        userGate.complete(BridgeOutcome.Ok())
        scope.runCurrent()
        assertEquals(0, lastCode())
    }

    @Test
    fun accountChangeDuringTheCallDropsTheResult() {
        call("auth.getUser")
        state = state.copy(accountKey = "account-B")
        userGate.complete(BridgeOutcome.Ok())
        scope.runCurrent()
        assertEquals(90500, lastCode())
    }

    /** APP-04 三端共性第 15 条：会话绑定看登录世代，不看账号——同一账号退出后重新登录，在途调用的结果同样不交回。 */
    @Test
    fun sameAccountLoggingOutAndBackInIsANewSessionAndDropsTheResult() {
        call("auth.getUser")
        // 同一账号 account-A：退出（世代 +1）再登录（世代 +1）
        state = state.copy(sessionEpoch = state.sessionEpoch + 2)
        userGate.complete(BridgeOutcome.Ok())
        scope.runCurrent()
        assertEquals(90500, lastCode())
    }

    /**
     * APP-04 三端共性第 14 条：调用有效性一路带进 PermissionRequester（BridgeServices.requestPermission 拿到 [BridgeCallValidity]）；
     * 撤回同意、页面失效、会话换代之后它立即为 false，内部链路在出队、说明结束、调系统接口前据此停止。
     */
    @Test
    fun permissionRequestCarriesTheCallValidityIntoTheRequester() {
        call("perm.request", """{"type":"push"}""", gesture = true)
        val validity = checkNotNull(permissionValidity)
        assertTrue("开始时有效", validity.stillValid())
        state = state.copy(privacy = BridgePrivacy.BASIC_MODE)
        assertTrue("撤回同意后立即失效", !validity.stillValid())
        state = state.copy(privacy = BridgePrivacy.CONSENTED)
        assertTrue(validity.stillValid())
        state = state.copy(sessionEpoch = state.sessionEpoch + 1)
        assertTrue("会话换代后失效", !validity.stillValid())
        state = state.copy(sessionEpoch = state.sessionEpoch - 1)
        host.invalidate()
        assertTrue("页面换文档 / 切换环境后失效", !validity.stillValid())
        permissionGate.complete(BridgeOutcome.Ok())
        scope.runCurrent()
        assertEquals(90500, lastCode())
    }

    /** 第 22 条：发 ApiRequest 的能力拿到桥调用的有效性（交给 ApiClient 作调用归属）；页面换了文档之后立即失效。 */
    @Test
    fun apiBackedMethodsReceiveTheCallValidityWhichDiesWithThePage() {
        call("auth.getUser")
        val validity = checkNotNull(userValidity)
        assertTrue(validity.stillValid())
        host.invalidate()
        assertTrue("页面换文档 / 关闭之后不再发出", !validity.stillValid())
    }

    @Test
    fun envelopeWithoutAValidIdGetsNoReplyAndBadStructureGets90002() {
        host.handle("""{"v":1,"method":"app.getEnv"}""", ORIGIN, true, false) { replies += it }
        assertTrue(replies.isEmpty())
        host.handle("""{"v":2,"id":"x","method":"app.getEnv"}""", ORIGIN, true, false) { replies += it }
        assertEquals(90002, lastCode())
    }

    @Test
    fun declaredMethodsAreTheContractMethodsWithAndroidSince() {
        assertTrue("app.getEnv" in host.declaredMethods)
        assertTrue("trade.openProduct" in host.declaredMethods)
        assertTrue("P1 不声明", "media.scan" !in host.declaredMethods)
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
        const val USER_TIMEOUT_MS = 10_000L
    }
}
