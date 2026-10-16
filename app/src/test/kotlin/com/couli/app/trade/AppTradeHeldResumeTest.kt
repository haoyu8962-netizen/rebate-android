package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.router.SessionState
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0716 ①（iOS APP-05b R1-01、共性第 19 条）：第三方新号登录后的「待办暂缓」挡住购买页经会话订阅的自动恢复（resumeAfterLogin，含投递之后
 * 才开始的暂缓与延后到回前台的续执行）；暂缓明确释放后 App 通知原页重新核对、恰好用原键重放一次。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppTradeHeldResumeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

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

    private val launched = mutableListOf<String>()

    private val ports =
        object : JumpPorts {
            override fun installed(platform: String): String = "unknown"

            override fun launchApp(
                type: String,
                value: String,
            ): Boolean {
                launched += "$type:$value"
                return false
            }

            override fun openBrowser(url: String): Boolean {
                launched += "browser:$url"
                return true
            }

            override fun copy(text: String): Boolean = true
        }

    private val card =
        Json
            .parseToJsonElement(
                """{"card_id":"c1","product_key":"pdd:1","platform":"pdd","title":"milk","link_id":"link-1","availability":"ok"}""",
            ).jsonObject

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun opens() = calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    private fun fail(
        call: Call,
        code: Int,
        data: String,
    ) {
        val effect = ErrorEffect(code, ErrorEffectKind.PAGE_ACTION, ReplayMode.NONE, registerPending = false, disableMillis = null)
        call.result.complete(
            ApiResult.Failure(code, "", Json.parseToJsonElement(data).jsonObject, "trace-1", effect, call.call.idempotencyKey),
        )
    }

    private fun routing(): AppRouting {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        return AppRouting(context, gate)
    }

    private fun env(trade: AppTrade): PurchaseEnv =
        object : PurchaseEnv {
            override fun loggedIn(): Boolean = trade.loggedIn()

            override fun basicMode(): Boolean = false

            override fun requestConsent() = Unit

            override fun platformStatus(platform: String): String? = null

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
        )

    /** 游客点购买 → open 回 10001：登记 kind=login 的购买待办（原键）进登录。返回第一次 open。 */
    private fun guestBuyNeedsLogin(controller: com.couli.feature.trade.OpenPurchaseController): Call {
        controller.buy(card, "product_detail.buy_bar.1")
        idle()
        val first = opens().single()
        fail(first, LOGIN_CODE, "{}")
        idle()
        return first
    }

    @Test
    fun fuand0716_01_aHeldPendingBlocksTheSessionDrivenPurchaseResumeAndTheReleaseReplaysItOnceWithTheOriginalKey() {
        val routing = routing()
        routing.session.value = SessionState.GUEST
        idle()
        val trade = trade(routing)
        idle()
        val controller = trade.purchaseController(env(trade), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        val first = guestBuyNeedsLogin(controller)
        assertNotNull("10001 登记了购买待办", trade.pending.peek())

        // 第三方新号登录：先暂缓，再同步会话（同 LoginService 的顺序）
        routing.router.holdPending()
        routing.session.value = SessionState.LOGGED_IN
        idle()
        assertEquals("暂缓期间会话订阅不重放", 1, opens().size)
        // 已经投递出去、暂缓开始之后才执行的那次恢复：入口本身查暂缓，不读不清不重放
        controller.resumeAfterLogin()
        idle()
        assertEquals(1, opens().size)
        assertNotNull("暂缓期间待办保留", trade.pending.peek())

        // 明确释放（引导【暂不】/ 绑定完成 / 查清）：通知原页重新核对，恰好用原键重放一次
        routing.router.releaseHeldPending()
        idle()
        assertEquals("释放后恢复一次", 2, opens().size)
        assertEquals("原键", first.call.idempotencyKey, opens()[1].call.idempotencyKey)
        assertNull("执行时删除待办", trade.pending.peek())
        // 重复释放、之后的会话变化都不会再重放
        routing.router.releaseHeldPending()
        routing.session.value = SessionState.PHONE_BOUND
        idle()
        assertEquals(2, opens().size)
        trade.release(controller)
    }

    @Test
    fun fuand0716_01_aReleaseWhileThePageIsInTheBackgroundReplaysOnceWhenItReturns() {
        val routing = routing()
        routing.session.value = SessionState.GUEST
        idle()
        val trade = trade(routing)
        idle()
        val controller = trade.purchaseController(env(trade), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        guestBuyNeedsLogin(controller)
        // 登录页盖着：发起页在后台
        controller.onBackground()
        routing.router.holdPending()
        routing.session.value = SessionState.LOGGED_IN
        idle()
        // 暂缓期间回到前台：延后的续执行同样被暂缓挡住
        controller.onForeground()
        idle()
        assertEquals("暂缓期间回前台不执行", 1, opens().size)
        controller.onBackground()
        routing.router.releaseHeldPending()
        idle()
        assertEquals("后台不执行", 1, opens().size)
        controller.onForeground()
        idle()
        assertEquals("回到前台恰好一次", 2, opens().size)
        controller.onBackground()
        controller.onForeground()
        idle()
        assertEquals(2, opens().size)
        trade.release(controller)
    }

    private companion object {
        const val LOGIN_CODE = 10001
    }
}
