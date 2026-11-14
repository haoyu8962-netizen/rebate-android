package com.couli.core.wallet.stepup

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-STEPUP-SUMMARY（D10-1635 / D10-1655，第三轮按 D10-1667 收窄）：step_up 发码不带 phone（B1-02zzz）；改收款的 stepUp 带业务摘要
 * （StepUpSummary，BR-ID-08 细则「绑定业务摘要」），令牌按摘要区分；提现取令牌不带摘要，令牌按同 action 复用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepUpSummaryBindingTest {
    private class RecordingApi : PageApi {
        val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            val data =
                when (call.operationId) {
                    StepUpController.SEND_OPERATION -> """{"resend_after_sec":60,"expires_in_sec":300}"""
                    else -> """{"step_up_token":"tok-1","expire_at":"2099-01-01T00:05:00+08:00"}"""
                }
            return ApiResult.Success(Json.parseToJsonElement(data), "trace-000001", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(1, 1)
    }

    private class Env : StepUpEnv {
        override fun sessionEpoch(): Long = EPOCH

        override fun account(): String = ACCOUNT

        override fun stepUpPhone(account: String): String = "bound"

        override fun forceUpdate() = Unit
    }

    private fun body(call: ApiCall): JsonObject = Json.parseToJsonElement(call.body!!).jsonObject

    // ---------------------------------------------------------------- 摘要的形状

    @Test
    fun payoutChangeSummaryCarriesTheAccountFieldOnlyWithoutPayeeName() {
        val alipay =
            StepUpSummary.payoutChange("""{"payout_method":"alipay","alipay_logon_id":"13800138000","payee_name":"张三"}""")!!
        assertEquals(setOf("payout_method", "alipay_logon_id"), alipay.keys)
        val bank = StepUpSummary.payoutChange("""{"payout_method":"bank_card","card_no":"6222020000000000","payee_name":"张三"}""")!!
        assertEquals(setOf("payout_method", "card_no"), bank.keys)
        val wechat = StepUpSummary.payoutChange("""{"payout_method":"wechat","attempt_id":"att-1"}""")!!
        assertEquals(setOf("payout_method", "attempt_id"), wechat.keys)
        assertNull(StepUpSummary.payoutChange("""{"payout_method":"unknown","x":"1"}"""))
        assertTrue(StepUpSummary.required("payout_account_change"))
        assertFalse(
            "提现（D10-1667）、换号、注销都不带摘要",
            StepUpSummary.required("withdraw") || StepUpSummary.required("phone_change") || StepUpSummary.required("account_deletion"),
        )
    }

    @Test
    fun verifyBodyCarriesSummaryOnlyWhenGiven() {
        val summary = StepUpSummary.payoutChange(PAYOUT_A)
        val withSummary = Json.parseToJsonElement(StepUpController.verifyBody("payout_account_change", "123456", summary)).jsonObject
        assertEquals(summary, withSummary["summary"])
        val withdraw = Json.parseToJsonElement(StepUpController.verifyBody("withdraw", "123456", null)).jsonObject
        assertEquals("action=withdraw 取令牌不带摘要（D10-1667）", setOf("action", "code"), withdraw.keys)
    }

    // ---------------------------------------------------------------- 令牌缓存：改收款按摘要区分，提现同 action 复用

    @Test
    fun tokenCacheReusesPayoutTokensOnlyForTheSameSummary() {
        val cache = StepUpTokenCache()
        val a = StepUpSummary.key(StepUpSummary.payoutChange(PAYOUT_A))
        val b = StepUpSummary.key(StepUpSummary.payoutChange(PAYOUT_B))
        cache.put("payout_account_change", ACCOUNT, "tok-a", FAR, EPOCH, SCOPE, a)
        assertEquals("tok-a", cache.take("payout_account_change", ACCOUNT, NOW, EPOCH, SCOPE, a))
        assertNull("换了收款账号不复用", cache.take("payout_account_change", ACCOUNT, NOW, EPOCH, SCOPE, b))
        assertNull("没摘要不复用带摘要的 token", cache.take("payout_account_change", ACCOUNT, NOW, EPOCH, SCOPE))
    }

    @Test
    fun withdrawTokenIsReusedForTheSameActionWithoutSummary() {
        val cache = StepUpTokenCache()
        cache.put("withdraw", ACCOUNT, "tok-w", FAR, EPOCH, SCOPE)
        assertEquals("同 action 复用（不按摘要区分）", "tok-w", cache.take("withdraw", ACCOUNT, NOW, EPOCH, SCOPE))
        cache.put("phone_change", ACCOUNT, "tok-p", FAR, EPOCH, SCOPE)
        assertEquals("tok-p", cache.take("phone_change", ACCOUNT, NOW, EPOCH, SCOPE))
    }

    // ---------------------------------------------------------------- 执行器：发码不带 phone，验证按 action 带 / 不带摘要

    private fun deps(
        api: RecordingApi,
        tokens: StepUpTokenCache,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = StepUpDeps(
        api = api,
        env = Env(),
        smsGate = WalletRateGate(scope) { NOW },
        verifyGate = WalletRateGate(scope) { NOW },
        sms = SmsDeadlines(),
        tokens = tokens,
        clock = { NOW },
        service = scope,
    )

    @Test
    fun controllerSendsWithoutPhoneAndVerifiesPayoutChangeWithSummary() =
        runTest(UnconfinedTestDispatcher()) {
            val api = RecordingApi()
            val tokens = StepUpTokenCache()
            val controller = StepUpController(deps(api, tokens, backgroundScope), backgroundScope) { true }
            val summary = StepUpSummary.payoutChange(PAYOUT_A)!!
            var delivered: String? = null
            controller.open(
                "payout_account_change",
                FormOwner(ACCOUNT, EPOCH),
                onToken = { delivered = it },
                onClosed = {},
                summary = summary,
            )

            val send = api.calls.single { it.operationId == StepUpController.SEND_OPERATION }
            assertEquals(mapOf("purpose" to JsonPrimitive("step_up"), "action" to JsonPrimitive("payout_account_change")), body(send))
            assertEquals("发码带点击时的账号（令牌由网络层带上）", ACCOUNT, send.expectedAccount)

            controller.onCodeChange("123456")
            controller.verify()
            val verify = api.calls.single { it.operationId == StepUpController.VERIFY_OPERATION }
            assertEquals(summary, body(verify)["summary"])
            assertEquals("tok-1", delivered)
            assertEquals("tok-1", tokens.take("payout_account_change", ACCOUNT, NOW, EPOCH, SCOPE, StepUpSummary.key(summary)))
            assertNull(
                "摘要不同不复用",
                tokens.take("payout_account_change", ACCOUNT, NOW, EPOCH, SCOPE, StepUpSummary.key(StepUpSummary.payoutChange(PAYOUT_B))),
            )
        }

    @Test
    fun controllerVerifiesWithdrawWithoutSummaryAndCachesForTheSameAction() =
        runTest(UnconfinedTestDispatcher()) {
            val api = RecordingApi()
            val tokens = StepUpTokenCache()
            val controller = StepUpController(deps(api, tokens, backgroundScope), backgroundScope) { true }
            controller.open("withdraw", FormOwner(ACCOUNT, EPOCH), onToken = {}, onClosed = {})
            assertFalse("step_up 发码不带 phone", "phone" in body(api.calls.single { it.operationId == StepUpController.SEND_OPERATION }))
            controller.onCodeChange("123456")
            controller.verify()
            val verify = api.calls.single { it.operationId == StepUpController.VERIFY_OPERATION }
            assertEquals("action=withdraw 取令牌不带摘要（D10-1667）", setOf("action", "code"), body(verify).keys)
            assertEquals("withdraw 令牌同 action 复用", "tok-1", tokens.take("withdraw", ACCOUNT, NOW, EPOCH, SCOPE))
        }

    private companion object {
        const val ACCOUNT = "u-1"
        const val EPOCH = 3L
        const val SCOPE = StepUpEnv.SCOPE_FULL
        const val NOW = 1_000L
        const val FAR = Long.MAX_VALUE
        const val PAYOUT_A = """{"payout_method":"alipay","alipay_logon_id":"13800138000","payee_name":"张三"}"""
        const val PAYOUT_B = """{"payout_method":"alipay","alipay_logon_id":"13900139000","payee_name":"张三"}"""
    }
}
