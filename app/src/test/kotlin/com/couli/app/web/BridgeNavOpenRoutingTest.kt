package com.couli.app.web

import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.router.GuardDecision
import com.couli.core.router.GuardReason
import com.couli.core.router.GuardState
import com.couli.core.router.MemoryPendingBackend
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.Router
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Android 第 1 轮评审 R1-01 / APP-04 三端共性第 33 条：桥发起的 nav.open 从真实 BridgeHost 一路走到真实 Router（与 AppWeb.Services
 * 同一条 [BridgeRouting] 路径），来源是 bridge，强更只读清单与基本模式守卫在 Router 产生任何副作用（推页面、补同意）之前生效：
 * - 强更未知：不在只读清单的 Login 不打开，桥回 90500；只读清单里的 OrderList 只读打开；
 * - 强更检查中、宿主等满 5 秒得到 unknown：Router 按 unknown 判（不再按检查中排队），同上；
 * - 基本模式：受限页面直接拒绝（不弹补同意），基本模式页面照常只读打开。
 */
class BridgeNavOpenRoutingTest {
    private val opened = mutableListOf<Pair<String, Boolean>>()
    private val consents = mutableListOf<String>()
    private val blocked = mutableListOf<GuardDecision>()
    private val replies = mutableListOf<String>()

    private var live = guard(PrivacyState.CONSENTED, UpdateState.OK)
    private var bridgeUpdate = BridgeUpdateState.SUPPORTED
    private var awaited = BridgeUpdateState.UNKNOWN

    private val navigator =
        object : RouteNavigator {
            override fun open(
                route: String,
                params: JsonObject,
                readonly: Boolean,
            ) {
                opened += route to readonly
            }

            override fun showUpgradePrompt(request: RouteRequest) = Unit

            override fun requestConsent(request: RouteRequest) {
                consents += request.route
            }

            override fun blocked(
                request: RouteRequest,
                decision: GuardDecision,
            ) {
                blocked += decision
            }
        }

    private val router = Router({ live }, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)

    private val services =
        object : BridgeServices by NoServices {
            // 与 AppWeb.Services.navOpen 相同：BridgeRouting.open(router, routing::guardState, …)
            override suspend fun navOpen(
                route: String,
                params: JsonObject,
                update: String,
            ): BridgeOutcome = BridgeRouting.open(router, { live }, route, params, update)
        }

    private val host =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
            caller = {
                val privacy = if (live.privacy == PrivacyState.BASIC_MODE.raw) BridgePrivacy.BASIC_MODE else BridgePrivacy.CONSENTED
                BridgeCallerState(privacy, accountKey = "account-A", update = bridgeUpdate)
            },
            services = services,
            ui = NoUi,
            awaitUpdate = { awaited },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private fun navOpen(route: String): Int {
        host.handle("""{"v":1,"id":"id-${replies.size}","method":"nav.open","params":{"route":"$route"}}""", ORIGIN, true, false) {
            replies += it
        }
        return Json
            .parseToJsonElement(replies.last())
            .jsonObject
            .getValue("code")
            .jsonPrimitive.int
    }

    private fun openedFlag(): Boolean =
        Json
            .parseToJsonElement(replies.last())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("opened")
            .jsonPrimitive.content
            .toBoolean()

    @Test
    fun unknownUpdateOpensOnlyReadOnlyPagesAndRejectsTheRestBeforeAnySideEffect() {
        live = guard(PrivacyState.CONSENTED, UpdateState.UNKNOWN)
        bridgeUpdate = BridgeUpdateState.UNKNOWN

        assertEquals("Login 不在只读清单：90500", 90500, navOpen("Login"))
        assertTrue("没有推任何页面", opened.isEmpty())
        assertEquals(GuardReason.UPDATE_UNKNOWN_BLOCKED, blocked.single().reason)

        assertEquals(0, navOpen("OrderList"))
        assertEquals("只读清单里的页面只读打开", listOf("OrderList" to true), opened)
    }

    @Test
    fun checkingThatTimedOutIsJudgedAsUnknownInsteadOfQueued() {
        // Router 此刻仍在检查中；宿主已等满 5 秒，按 unknown 交给 Router
        live = guard(PrivacyState.CONSENTED, UpdateState.CHECKING)
        bridgeUpdate = BridgeUpdateState.CHECKING
        awaited = BridgeUpdateState.UNKNOWN

        assertEquals(90500, navOpen("Login"))
        assertTrue(opened.isEmpty())
        assertEquals("按 unknown 拒绝，不是 update_checking 排队", GuardReason.UPDATE_UNKNOWN_BLOCKED, blocked.single().reason)

        assertEquals(0, navOpen("OrderList"))
        assertEquals(listOf("OrderList" to true), opened)
    }

    @Test
    fun basicModeRejectsRestrictedPagesWithoutAskingForConsent() {
        live = guard(PrivacyState.BASIC_MODE, UpdateState.OK)

        assertEquals(0, navOpen("OrderList"))
        assertEquals("没有打开", false, openedFlag())
        assertTrue("桥来源不弹补同意（那是原生主动操作的处理）", consents.isEmpty())
        assertEquals(GuardReason.BASIC_MODE_BLOCKED, blocked.single().reason)
        assertTrue(opened.isEmpty())

        assertEquals(0, navOpen("Search"))
        assertEquals("基本模式页面只读打开", listOf("Search" to true), opened)
        assertEquals(true, openedFlag())
    }

    @Test
    fun supportedVersionOpensNormally() {
        assertEquals(0, navOpen("Login"))
        assertEquals(listOf("Login" to false), opened)
        assertEquals(true, openedFlag())
    }

    private fun guard(
        privacy: PrivacyState,
        update: UpdateState,
    ) = GuardState(privacy, update, SessionState.PHONE_BOUND, environmentSettled = true, appVersion = "9.9.9", isDebugBuild = false)

    private object NoUi : BridgeUi {
        override fun toast(
            text: String,
            long: Boolean,
        ) = Unit

        override fun showLoading(text: String?) = Unit

        override fun hideLoading() = Unit

        override fun setNavBar(params: JsonObject) = true

        override fun close() = Unit
    }

    private object NoServices : BridgeServices {
        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity) = BridgeOutcome.Ok()

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun login(update: String) = BridgeOutcome.Ok()

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
        ) = BridgeOutcome.Ok()

        override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

        override suspend fun clipboardRead() = BridgeOutcome.Ok()

        override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

        override suspend fun pushStatus() = BridgeOutcome.Ok()

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override fun track(
            name: String,
            props: JsonObject,
        ) = BridgeOutcome.Ok()

        override fun reportRejected(
            method: String,
            code: Int,
        ) = Unit
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
    }
}
