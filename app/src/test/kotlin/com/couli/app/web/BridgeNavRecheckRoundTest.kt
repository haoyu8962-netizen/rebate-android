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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-04 共性第 63 条（Android 第 1 轮评审 R1-06）：桥 nav.open 拿到 supported 结论、正在等回复脚本执行时，App 回前台开始新一轮
 * 版本检查（Router 变为检查中）→ 回复执行完后的复核不能拿上一轮的 supported 顶替新一轮的检查中，这次导航保守取消。
 * 只有同一轮检查等待超时得到的 unknown，才按该轮的 unknown 规则判（只读清单里的页面只读打开）。
 * 路径与 AppWeb.Services.navOpen 相同：真实 BridgeHost → [BridgeRouting] → 真实 Router。
 */
class BridgeNavRecheckRoundTest {
    private val opened = mutableListOf<Pair<String, Boolean>>()
    private var live = guard(UpdateState.OK)
    private var awaited = BridgeUpdateState.SUPPORTED
    private var callerUpdate = BridgeUpdateState.SUPPORTED
    private var round = 1L
    private var settle: ((Boolean) -> Unit)? = null

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

            override fun requestConsent(request: RouteRequest) = Unit

            override fun blocked(
                request: RouteRequest,
                decision: GuardDecision,
            ) = Unit
        }

    private val router = Router({ live }, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)

    private val services =
        object : BridgeServices by NoServices {
            override suspend fun navOpen(
                route: String,
                params: JsonObject,
                update: String,
            ): BridgeOutcome = BridgeRouting.open(router, { live }, route, params, update, round = { round })
        }

    private val host =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
            caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-A", update = callerUpdate) },
            services = services,
            ui = NoUi,
            awaitUpdate = { awaited },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private fun navOpen(route: String) {
        host.handle(
            """{"v":1,"id":"id-$route","method":"nav.open","params":{"route":"$route"}}""",
            ORIGIN,
            true,
            false,
            deliver = { _, settled -> settle = settled },
        ) { }
    }

    @Test
    fun aNewCheckRoundWhileTheReplyRunsCancelsTheNavigationInsteadOfReusingTheOldSupported() {
        navOpen("OrderList")
        assertTrue("回复还没执行：不导航", opened.isEmpty())
        // App 回前台：新一轮版本检查开始，Router 变为检查中
        round += 1
        live = guard(UpdateState.CHECKING)
        checkNotNull(settle).invoke(true)
        assertTrue("不拿上一轮的 supported 顶替新一轮的检查中：保守取消", opened.isEmpty())
    }

    /**
     * APP-04 共性第 65 条：Router 判定桥导航为 deferred（宿主拿到 supported 时 Router 已进入新一轮检查中）——不交给 Router，不登记成
     * 待办、不进启动队列；检查结束后也不补执行。
     */
    @Test
    fun aDeferredBridgeNavigationIsNeitherQueuedNorReplayedLater() {
        live = guard(UpdateState.CHECKING)
        navOpen("OrderList")
        settle?.invoke(true)
        assertTrue("没有打开", opened.isEmpty())
        assertTrue("没有登记成待办", !router.hasPendingAction)

        live = guard(UpdateState.OK)
        router.resumePending(enterPrerequisite = false)
        assertTrue("检查结束后也不补执行", opened.isEmpty())
    }

    @Test
    fun theSameRoundThatTimedOutIsStillJudgedAsUnknown() {
        live = guard(UpdateState.CHECKING)
        callerUpdate = BridgeUpdateState.CHECKING
        awaited = BridgeUpdateState.UNKNOWN
        navOpen("OrderList")
        checkNotNull(settle).invoke(true)
        assertEquals("同一轮等待超时：按 unknown 只读打开", listOf("OrderList" to true), opened)
    }

    @Test
    fun aConcludedNewRoundUsesItsOwnConclusion() {
        navOpen("OrderList")
        round += 1
        live = guard(UpdateState.OK)
        checkNotNull(settle).invoke(true)
        assertEquals("新一轮已有结论：按当前结论导航", listOf("OrderList" to false), opened)
    }

    private fun guard(update: UpdateState) =
        GuardState(
            PrivacyState.CONSENTED,
            update,
            SessionState.PHONE_BOUND,
            environmentSettled = true,
            appVersion = "9.9.9",
            isDebugBuild = false,
        )

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
