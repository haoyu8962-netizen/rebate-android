package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.router.SessionState
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.trade.ColdAuthResume
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeInbox
import com.couli.feature.trade.WebCodeOrphanGate
import com.couli.feature.trade.WebCodeOrphanStartup
import com.couli.feature.trade.webCodeAccountTag
import com.couli.feature.trade.webCodeStateSha256
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-TB-WEBCODE-B 测试：AppTrade 装配 orphan 处理器与冷启动恢复槽（设计稿 §3.1 WebCodeOrphanHandler 接线、§6.4、§6.5、§8）。
 * - 新增的构造参数都有缺省：照旧只传原有参数（UnavailableWebCodeAuthorizer）也能构造，回跳一律不提交；
 * - 接上尝试记录、收件箱、启动闸门后：无人认领的 Code → bindings（提交期间「正在确认」浮层）→ active：授权管理页再 load() 一次、有效待办登记恢复槽、
 *   发起页 [com.couli.feature.trade.OpenPurchaseController.claimColdAuthResume] 用待办里的 link_id 发 open；
 * - 退出登录（会话由已登录变为游客）或 [AppTrade.onSessionEnded]：清尝试记录、清空收件箱、作废恢复槽；
 * - 切换环境（D10-3690 口径 (7)，同 N-22 clearForEnvironmentSwitch）：登记进清理表后，清理时清空收件箱、作废恢复槽。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppTradeWebCodeOrphanWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Call(
        val call: ApiCall,
        val result: CompletableDeferred<ApiResult>,
    )

    private val calls = mutableListOf<Call>()

    private val api =
        object : PageApi {
            override suspend fun send(call: ApiCall): ApiResult {
                val pending = Call(call, CompletableDeferred())
                calls += pending
                return pending.result.await()
            }

            override fun cancelOwned(owner: CallOwnership) = Unit

            override fun epochs(): CallEpochs = CallEpochs(0, 0)
        }

    private val ports =
        object : JumpPorts {
            override fun installed(platform: String): String = "unknown"

            override fun launchApp(
                type: String,
                value: String,
            ): Boolean = false

            override fun openBrowser(url: String): Boolean = true

            override fun copy(text: String): Boolean = true
        }

    private val attempts = TaggedWebCodeAttempts()
    private val inbox = WebCodeInbox()

    @After fun close() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ops(): List<String> = calls.map { it.call.operationId }

    private fun count(op: String): Int = calls.count { it.call.operationId == op }

    private fun succeed(
        call: Call,
        data: String,
    ) = call.result.complete(ApiResult.Success(Json.parseToJsonElement(data), "trace-1", call.call.idempotencyKey))

    private fun routing(): AppRouting {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        return AppRouting(context, gate).also {
            it.session.value = SessionState.LOGGED_IN
            idle()
        }
    }

    /** 只传原有参数（与 AppTradeHeldResumeTest 等十几个旧测试同样的构造）。 */
    private fun legacyTrade(routing: AppRouting): AppTrade =
        AppTrade(
            api = api,
            store = null,
            routing = routing,
            tracker = Provider { error("not used") },
            baichuan = UnavailableBaichuan(),
            webCode = UnavailableWebCodeAuthorizer,
            ports = ports,
            browserNoticeMillis = 0L,
        )

    private fun trade(routing: AppRouting): AppTrade =
        AppTrade(
            api = api,
            store = null,
            routing = routing,
            tracker = Provider { error("not used") },
            baichuan = UnavailableBaichuan(),
            webCode = UnavailableWebCodeAuthorizer,
            ports = ports,
            browserNoticeMillis = 0L,
            sessionAccount = { USER },
            webCodeAttempts = attempts,
            webCodeInbox = inbox,
            webCodeGate = WebCodeOrphanGate { WebCodeOrphanStartup.READY },
            processStartedAtMillis = PROCESS_START,
        )

    private fun env(trade: AppTrade): PurchaseEnv =
        object : PurchaseEnv {
            override fun loggedIn(): Boolean = trade.loggedIn()

            override fun platformStatus(platform: String): String? = "on"

            override fun updateCard(
                target: JsonObject,
                transform: (JsonObject) -> JsonObject,
            ) = Unit

            override fun open(
                route: String,
                params: JsonObject,
            ) = Unit

            override fun refetchCard(
                target: JsonObject,
                merge: (JsonObject, JsonObject) -> JsonObject,
            ) = Unit

            override fun helpLink(key: String): Pair<String, JsonObject>? = null
        }

    /** 冷启动回跳的尝试记录：发起于本进程启动之前、当前账号。 */
    private fun coldAttempt() = attempts.record(STATE, PROCESS_START - 1_000L, webCodeAccountTag(USER))

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun t01_legacyConstructionStillWorksAndNeverSubmits() {
        val trade = legacyTrade(routing())
        assertNotNull("恢复槽已装配", trade.coldResume)
        assertNotNull("绑定变化信号已装配", trade.bindingChanges)
        trade.onUnclaimedWebCode(WebCodeCallback.Code(CODE, STATE))
        idle()
        assertTrue("缺省装配（没有尝试记录、闸门保守）：不发任何请求：${ops()}", calls.isEmpty())
        assertFalse(trade.webCodeOverlay.value.confirming)
    }

    @Test
    fun t02_anUnclaimedCodeSubmitsBindingsBehindTheConfirmingOverlay() {
        val trade = trade(routing())
        coldAttempt()
        trade.onUnclaimedWebCode(WebCodeCallback.Code(CODE, STATE))
        idle()
        assertEquals("提交 bindings 一次：${ops()}", 1, count(TradeOperations.BIND_UNION))
        val body = obj(calls.single().call.body!!)
        assertEquals("请求体恰好三项", setOf("state", "auth_method", "code"), body.keys)
        assertTrue("提交期间显示「正在确认」浮层", trade.webCodeOverlay.value.confirming)
        succeed(calls.single(), ACTIVE)
        idle()
        assertFalse("结束后浮层消失", trade.webCodeOverlay.value.confirming)
        assertEquals("没有购买待办：只提示再点一次", "auth.taobao.completed_tap_again", trade.webCodeOverlay.value.toast?.key)
        trade.consumeWebCodeToast()
        assertNull("提示可消费", trade.webCodeOverlay.value.toast)
        assertEquals("不重放 open", 0, count(TradeOperations.OPEN_LINK))
    }

    @Test
    fun t03_aSuccessfulOrphanBindingReloadsAnOpenAuthManagePage() {
        val trade = trade(routing())
        val page = trade.authManageController(scope)
        page.load()
        idle()
        succeed(calls.last(), """{"items":[{"platform":"taobao","status":"unbound"}]}""")
        idle()
        coldAttempt()
        trade.onUnclaimedWebCode(WebCodeCallback.Code(CODE, STATE))
        idle()
        assertEquals("无人认领的 Code 提交 bindings：${ops()}", 1, count(TradeOperations.BIND_UNION))
        succeed(calls.last { it.call.operationId == TradeOperations.BIND_UNION }, ACTIVE)
        idle()
        assertEquals("授权管理页收到绑定变化，再 load() 一次", 2, count(TradeOperations.LIST_UNION_BINDINGS))
        assertEquals("不重放 open", 0, count(TradeOperations.OPEN_LINK))
    }

    @Test
    fun t04_aValidPendingBecomesAResumeTicketThatAPurchasePageClaims() {
        val trade = trade(routing())
        assertTrue(
            "登记淘宝授权待办（Router 的同一份记录）",
            trade.pending.register(
                obj("""{"kind":"auth","link_id":"link-old","platform":"taobao","client_owner":"page-dead#1","product_key":"tb:1"}"""),
            ),
        )
        coldAttempt()
        trade.onUnclaimedWebCode(WebCodeCallback.Code(CODE, STATE))
        idle()
        assertEquals("无人认领的 Code 提交 bindings：${ops()}", 1, count(TradeOperations.BIND_UNION))
        succeed(calls.single(), ACTIVE)
        idle()
        val ticket = trade.coldResume.peek()
        assertNotNull("有效待办：登记恢复槽", ticket)
        assertEquals("link-old", ticket!!.linkId)
        val controller = trade.purchaseController(env(trade), scope)
        val card =
            obj("""{"card_id":"c1","product_key":"tb:1","platform":"taobao","title":"milk","link_id":"link-new","availability":"ok"}""")
        assertTrue("发起页认领（AppTrade 把恢复槽交给了执行器）", controller.claimColdAuthResume(card))
        idle()
        val open = calls.lastOrNull { it.call.operationId == TradeOperations.OPEN_LINK }
        assertNotNull("发一次 open", open)
        assertEquals("open 的是待办里的 link_id", mapOf("link_id" to "link-old"), open!!.call.pathParams)
        assertNull("认领时删掉购买待办", trade.pending.peek())
        trade.release(controller)
    }

    @Test
    fun t05_loggingOutClearsTheAttemptTheInboxAndTheResumeTicket() {
        val routing = routing()
        val trade = trade(routing)
        coldAttempt()
        inbox.deliver(WebCodeCallback.Code(CODE, STATE))
        trade.coldResume.offer(ColdAuthResume.Ticket("link-old", "tb:1", "taobao", Long.MAX_VALUE))
        routing.session.value = SessionState.GUEST
        idle()
        assertNull("退出登录：清尝试记录", attempts.current())
        val waiting = scope.async(start = CoroutineStart.UNDISPATCHED) { inbox.await(webCodeSha256(STATE)) }
        assertFalse("退出登录：收件箱已清空（等待拿不到旧回跳）", waiting.isCompleted)
        waiting.cancel()
        assertNull("退出登录：恢复槽作废", trade.coldResume.peek())
    }

    @Test
    fun t06_onSessionEndedClearsTheSameThingsForAnAccountSwitch() {
        val trade = trade(routing())
        coldAttempt()
        inbox.deliver(WebCodeCallback.Code(CODE, STATE))
        trade.onSessionEnded()
        assertNull("换账号：清尝试记录", attempts.current())
        val waiting = scope.async(start = CoroutineStart.UNDISPATCHED) { inbox.await(webCodeSha256(STATE)) }
        assertFalse("换账号：收件箱已清空", waiting.isCompleted)
        waiting.cancel()
    }

    @Test
    fun t07_anEnvironmentSwitchEmptiesTheInboxAndTheResumeSlot() {
        val trade = trade(routing())
        val cleaners = EnvironmentSwitchCleanerRegistry()
        trade.connectEnvironmentCleanup(cleaners)
        inbox.deliver(WebCodeCallback.Code(CODE, STATE))
        trade.coldResume.offer(ColdAuthResume.Ticket("link-old", "tb:1", "taobao", Long.MAX_VALUE))
        assertNotNull("前提：恢复槽里有票", trade.coldResume.peek())
        cleaners.cleanAll(AppEnvironment.TEST, AppEnvironment.STAGING)
        assertNull("切换环境：恢复槽作废", trade.coldResume.peek())
        assertNull("切换环境：收件箱已清空（下一轮取不到旧回跳）", inbox.takeUnclaimed(webCodeStateSha256(STATE)))
        val waiting = scope.async(start = CoroutineStart.UNDISPATCHED) { inbox.await(webCodeSha256(STATE)) }
        assertFalse("切换环境：等待者也拿不到旧回跳", waiting.isCompleted)
        waiting.cancel()
    }

    private companion object {
        const val USER = "user-1"
        const val STATE = "st_cold_1"
        const val CODE = "c0de_cold_1"
        const val PROCESS_START = 1_000_000L
        const val ACTIVE = """{"platform":"taobao","status":"active"}"""
    }
}
