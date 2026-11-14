package com.couli.app.wallet

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
import com.couli.core.wallet.WalletRoutes
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WithdrawController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * FU-AND-STEPUP-SUMMARY 第三轮（D10-1667，执行器级：真实的 WithdrawController / PayoutAccountController / SensitiveFlow / StepUpController，网络换成
 * 同步应答的假 [WalletApi]）：
 * - 提现取令牌不带摘要，withdraw 令牌按同 action 复用（缓存对 withdraw 不按摘要区分）；提现路径不按 phone_bound 拦；
 * - 改收款带摘要；phone_bound=false 不发码，走「先绑定手机」引导（D10-1659 S2-4）。
 */
class StepUpRound3FlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = 1_000_000L

    @After
    fun tearDown() = scope.cancel()

    private class Env : WalletEnv {
        val opened = mutableListOf<String>()
        var phoneBound: Boolean? = true
        var slot: String? = "13800005678"

        override fun loggedIn() = true

        override fun sessionEpoch() = 1L

        override fun account() = "user-a"

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

        override fun stepUpPhone(account: String): String? = slot

        override fun stepUpPhoneBound(account: String): Boolean? = phoneBound
    }

    /** 同步应答：写操作按 [writes] 依次给（用完一律 10003）。 */
    private inner class FakeApi : WalletApi {
        val calls = mutableListOf<ApiCall>()
        val writes = ArrayDeque<ApiResult>()

        fun of(operation: String) = calls.filter { it.operationId == operation }

        override fun pendingFor(operationId: String): PendingKeyRecord? = null

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = error("本测试不确认")

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = error("本测试不放弃")

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                "sendSmsCode" -> ok("""{"resend_after_sec":60,"expires_in_sec":300}""")
                "stepUp" -> ok("""{"step_up_token":"tok-${of("stepUp").size}","expire_at":"2099-01-01T00:00:00+08:00"}""")
                WithdrawController.OPERATION, PayoutAccountController.OPERATION -> writes.removeFirstOrNull() ?: fail(10003)
                else -> ok("{}")
            }
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private val env = Env()
    private val api = FakeApi()
    private val deps =
        WalletDeps(
            api = api,
            env = env,
            gates = WalletGates(scope) { now },
            tokens = StepUpTokenCache(),
            cache = null,
            mask = null,
            realname = null,
            labor = null,
            clock = { now },
            service = scope,
        )

    private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)

    private fun fail(code: Int) =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            "trace-000123",
            ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
            null,
        )

    private fun body(call: ApiCall) = Json.parseToJsonElement(call.body!!).jsonObject

    @Test
    fun withdrawStepUpCarriesNoSummary() {
        api.writes += listOf(fail(10003), ok(WITHDRAWAL))
        val c = WithdrawController(deps, scope).also { it.start() }
        c.flow.submit(AMOUNT_1000, AMOUNT_1000)
        c.flow.stepUp.onCodeChange("123456")
        c.flow.stepUp.verify()
        val verify = api.of("stepUp").single()
        assertFalse("action=withdraw 取令牌不带摘要（D10-1667）", "summary" in body(verify))
        assertEquals(setOf("action", "code"), body(verify).keys)
        assertEquals("拿到 token 原键重放", "tok-1", api.of(WithdrawController.OPERATION)[1].stepUpToken)
    }

    @Test
    fun withdrawTokenIsReusedForTheSameActionWhateverTheAmount() {
        // 缓存里一枚 withdraw 令牌（流程写入时摘要为 null）：任何金额的提现都直接带上，不按摘要区分
        deps.tokens.put("withdraw", "user-a", "tok-cached", Long.MAX_VALUE, 1L, "full", null)
        api.writes += listOf(ok(WITHDRAWAL))
        val c = WithdrawController(deps, scope).also { it.start() }
        c.flow.submit(AMOUNT_2000, AMOUNT_2000)
        assertEquals("同 action 复用", "tok-cached", api.of(WithdrawController.OPERATION).single().stepUpToken)
        assertTrue("不弹面板、不发码", api.of("sendSmsCode").isEmpty())
    }

    @Test
    fun withdrawPathIsNotBlockedByPhoneBound() {
        env.phoneBound = false
        api.writes += listOf(fail(10003))
        val c = WithdrawController(deps, scope).also { it.start() }
        c.flow.submit(AMOUNT_1000, AMOUNT_1000)
        assertNotNull("提现路径不按 phone_bound 拦：照改前打开面板", c.flow.stepUp.state.value)
        assertEquals("照改前按槽位键发码", 1, api.of("sendSmsCode").size)
        assertFalse("不走先绑定手机", WalletRoutes.BIND_PHONE in env.opened)
    }

    @Test
    fun payoutChangeCarriesTheSummaryAndUnboundPhoneGuidesToBindPhone() {
        api.writes += listOf(fail(10003))
        val c = PayoutAccountController(deps, scope)
        c.flow.submit(PAYOUT_BODY, "{}")
        c.flow.stepUp.onCodeChange("123456")
        c.flow.stepUp.verify()
        val summary = body(api.of("stepUp").single())["summary"]!!.jsonObject
        assertEquals(setOf("payout_method", "alipay_logon_id"), summary.keys)

        env.phoneBound = false
        api.writes += listOf(fail(10003))
        val other = PayoutAccountController(deps, scope)
        val sends = api.of("sendSmsCode").size
        other.flow.submit(PAYOUT_BODY_B, "{}")
        assertNull("改收款未绑手机：不开面板", other.flow.stepUp.state.value)
        assertEquals("不发码", sends, api.of("sendSmsCode").size)
        assertEquals(
            "error.10005",
            other.flow.state.value.error
                ?.key,
        )
        assertTrue("先绑定手机", WalletRoutes.BIND_PHONE in env.opened)
    }

    private companion object {
        const val AMOUNT_1000 = """{"amount_fen":1000}"""
        const val AMOUNT_2000 = """{"amount_fen":2000}"""
        const val PAYOUT_BODY = """{"payout_method":"alipay","alipay_logon_id":"13800138000","payee_name":"测试三"}"""
        const val PAYOUT_BODY_B = """{"payout_method":"alipay","alipay_logon_id":"13900139000","payee_name":"测试三"}"""
        const val PAYOUT_ALIPAY =
            """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三","change_remaining_this_month":2}"""
        const val WITHDRAWAL = """{}"""
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
    }
}
