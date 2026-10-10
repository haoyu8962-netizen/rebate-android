package com.couli.app.wallet

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.EstimateUi
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawSnapshot
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * APP-09 Android 第二次定向复核 RC-05（S1，共性第 64 条）：【全部提现】、快捷档位与延时估算携带渲染时的表单身份。场景是「B 的令牌已保存、会话清空通知
 * 还没执行」——rules 版本还没递增，版本核对拦不住，只能靠表单身份核对；估算请求不得为 A 页面的金额重新绑定 B 的会话。控制器跑在主线程调度器上，
 * 0.5 秒的输入停顿用 Robolectric 主线程时钟推进；网络换成立即回答、记录每次调用的假 [WalletApi]。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class WithdrawQuickFillOwnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val env = Env()
    private val api = FakeApi()
    private var keys = 0

    @After
    fun tearDown() = scope.cancel()

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"

        override fun loggedIn() = user != null

        override fun sessionEpoch() = epoch

        override fun account() = user

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) = Unit

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String) = "13800005678"
    }

    private class FakeApi : WalletApi {
        val calls = mutableListOf<ApiCall>()

        fun estimates(): List<ApiCall> = calls.filter { it.operationId == WithdrawController.RULES_OPERATION && it.query != null }

        override fun pendingFor(operationId: String): PendingKeyRecord? = null

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = error("not used")

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = error("not used")

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok("""{"payout_method":null}""")
                else -> ApiResult.LocalReject("not_used")
            }
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)

        private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)
    }

    private fun controller(): WithdrawController {
        val deps =
            WalletDeps(
                api = api,
                env = env,
                gates = WalletGates(scope),
                tokens = StepUpTokenCache(),
                cache = null,
                mask = null,
                realname = null,
                labor = null,
                newKey = {
                    keys += 1
                    "key-$keys"
                },
                service = scope,
            )
        return WithdrawController(deps, scope).also {
            it.start()
            idle()
            assertEquals(WithdrawSnapshot.CONTENT, it.state.value.phase)
        }
    }

    private fun idle(millis: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (millis > 0) looper.idleFor(Duration.ofMillis(millis)) else looper.idle()
    }

    /** B 的令牌已保存、清空通知还没执行：A 页面的【全部提现】与快捷档位回调到达，rules 版本仍是渲染时的——按表单身份拦下，金额不写入、不发估算。 */
    @Test
    fun rc05_fillAllAndQuickBeforeNoticeAreRejectedByFormOwner() {
        val controller = controller()
        val ownerA = controller.flow.owner
        val version = controller.state.value.version
        api.calls.clear()
        env.user = "user-b"
        env.epoch += 1
        controller.fillAll(version, ownerA)
        controller.fillQuick(version, QUICK_FEN, ownerA)
        idle(DEBOUNCE_PLUS)
        assertEquals("令牌已换、通知未执行：版本没变，但表单身份已不是当前会话，金额不写入", "", controller.state.value.amountText)
        assertEquals(EstimateUi.Hidden, controller.state.value.estimate)
        assertTrue("不得以 B 的身份为 A 页面的金额发估算", api.estimates().isEmpty())
        controller.leave()
    }

    /** 对照：同一会话里渲染身份与当前一致时，【全部提现】与快捷档位照常写入并按该身份发估算（网络层 expectedAccount 是表单的账号）。 */
    @Test
    fun rc05_fillAllAndQuickWithCurrentOwnerCarryFormIdentityToRequest() {
        val controller = controller()
        val ownerA = controller.flow.owner
        val version = controller.state.value.version
        api.calls.clear()
        controller.fillQuick(version, QUICK_FEN, ownerA)
        idle()
        assertEquals("5", controller.state.value.amountText)
        val sent = api.estimates().single()
        assertEquals("amount_fen=$QUICK_FEN", sent.query)
        assertEquals("估算请求绑定表单的账号（网络层读取凭据前核对）", "user-a", sent.expectedAccount)
        // 写出前的归属核对按表单身份：令牌换成 B 后，这次估算的归属即失效（网络层的续发、重放都不会以 B 的身份写出）
        env.user = "user-b"
        assertFalse(sent.ownership!!.stillOwned())
        controller.leave()
    }

    /** A 输入金额后 0.5 秒停顿内，B 的令牌已保存、清空通知还没执行：延时估算到点时按 A 的表单身份复核，不以 B 的身份发出 A 的金额，「计算中」收起。 */
    @Test
    fun rc05_debouncedEstimateAfterTokenSwapIsNotSentAsNewSession() {
        val controller = controller()
        val ownerA = controller.flow.owner
        api.calls.clear()
        assertTrue(controller.onAmountChange("10", ownerA))
        assertEquals(EstimateUi.Calculating, controller.state.value.estimate)
        env.user = "user-b"
        env.epoch += 1
        idle(DEBOUNCE_PLUS)
        assertTrue("延时估算不得为旧金额重新绑定当前会话（B）", api.estimates().isEmpty())
        assertEquals("不留下没有在途请求的「计算中」", EstimateUi.Hidden, controller.state.value.estimate)
        controller.leave()
    }

    /** 对照：同一会话里停顿 0.5 秒后照常带金额估算，请求绑定表单的账号。 */
    @Test
    fun rc05_debouncedEstimateInSameSessionIsSentWithFormAccount() {
        val controller = controller()
        val ownerA = controller.flow.owner
        api.calls.clear()
        assertTrue(controller.onAmountChange("10", ownerA))
        idle(DEBOUNCE_PLUS)
        val sent = api.estimates().single()
        assertEquals("amount_fen=1000", sent.query)
        assertEquals("user-a", sent.expectedAccount)
        assertTrue(controller.state.value.estimate is EstimateUi.Shown)
        controller.leave()
    }

    private companion object {
        const val QUICK_FEN = 500L
        const val DEBOUNCE_PLUS = WithdrawController.ESTIMATE_DEBOUNCE_MILLIS + 100
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
    }
}
