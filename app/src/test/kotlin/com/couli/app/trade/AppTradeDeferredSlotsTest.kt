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
import com.couli.feature.trade.PurchaseDialog
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0607 第 3 轮 F2-01（APP-06b 共性第 48 条）：延迟恢复按类型分槽。后台完成拼多多授权（授权恢复排入延迟），随后会话状态补全
 * （LOGGED_IN → PHONE_BOUND）经 AppTrade 的实际通知链路触发 resumeAfterLogin（也排入延迟）：登录恢复不得覆盖授权恢复；有效期内回到
 * 前台，恰好以新键发出一次 open。撤掉分槽（回到单槽覆盖）时此用例失败（NATIVE/FU-AND-0607/revert-checks.txt 第 3 轮）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppTradeDeferredSlotsTest {
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

    private fun queries() = calls.filter { it.call.operationId == TradeOperations.LIST_UNION_BINDINGS }

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

    @Test
    fun f2_01_aSessionCompletionWhileAnAuthResumeWaitsDoesNotOverwriteItAndOpensOnce() {
        val routing = routing()
        // 冷启动已有完整会话，phone_bound 尚未查清
        routing.session.value = SessionState.LOGGED_IN
        idle()
        val trade =
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
        idle()
        val controller = trade.purchaseController(env(trade), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        controller.buy(card, "product_detail.buy_bar.1")
        idle()
        val first = opens().single()
        fail(
            first,
            30111,
            """{"auth_jump":{"primary":{"type":"h5","value":"https://auth.example.test/pdd"},"fallbacks":[],""" +
                """"expire_at":"2099-01-01T00:00:00+08:00"}}""",
        )
        idle()
        assertTrue(controller.state.value.dialog is PurchaseDialog.AuthSheet)
        // 去授权：拉起浏览器，离开 App 后回来，向服务端确认
        controller.authorize()
        idle()
        assertEquals(listOf("browser:https://auth.example.test/pdd"), launched)
        controller.onBackground()
        controller.onForeground()
        idle()
        val query = queries().single()
        // 查询期间再次进入后台；查询返回已授权 → 授权恢复排入延迟
        controller.onBackground()
        query.result.complete(
            ApiResult.Success(Json.parseToJsonElement("""{"items":[{"platform":"pdd","status":"active"}]}"""), "trace-2", null),
        )
        idle()
        assertEquals("后台不执行 open", 1, opens().size)
        // getMe 补查完成：会话 LOGGED_IN → PHONE_BOUND，AppTrade 通知 resumeAfterLogin（页面在后台 → 排入登录槽）
        routing.session.value = SessionState.PHONE_BOUND
        idle()
        assertEquals("后台仍不执行 open", 1, opens().size)
        // 有效期内回到前台：授权恢复仍在，恰好以新键执行一次 open
        controller.onForeground()
        idle()
        assertEquals("恰好一次新 open", 2, opens().size)
        assertNotEquals("新键", first.call.idempotencyKey, opens()[1].call.idempotencyKey)
        assertEquals(mapOf("link_id" to "link-1"), opens()[1].call.pathParams)
        assertNull("执行时删除待办", trade.pending.peek())
        // 再次进出前台不会重复执行
        controller.onBackground()
        controller.onForeground()
        idle()
        assertEquals(2, opens().size)
        trade.release(controller)
    }
}
