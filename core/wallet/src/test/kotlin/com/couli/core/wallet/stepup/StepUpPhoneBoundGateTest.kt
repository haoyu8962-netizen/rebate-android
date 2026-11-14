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
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-STEPUP-SUMMARY 第二轮（D10-1659 S2-4 / S2-5、D10-1658 ④）：二次验证面板能否发码按 phone_bound 判定——未绑不发码；已绑不需要本机号码；
 * 未知沿用槽位键的旧口径。面板的 step_up 发码要求就绪会话（requiresSession）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepUpPhoneBoundGateTest {
    private class RecordingApi : PageApi {
        val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return ApiResult.Success(Json.parseToJsonElement("""{"resend_after_sec":60,"expires_in_sec":300}"""), "trace-000001", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(1, 1)
    }

    private class Env(
        val bound: Boolean?,
        val slot: String?,
    ) : StepUpEnv {
        override fun sessionEpoch(): Long = 3L

        override fun account(): String = "u-1"

        override fun stepUpPhone(account: String): String? = slot

        override fun stepUpPhoneBound(account: String): Boolean? = bound

        override fun forceUpdate() = Unit
    }

    private fun open(env: Env): Pair<RecordingApi, StepUpUiState?> {
        val api = RecordingApi()
        var state: StepUpUiState? = null
        runTest(UnconfinedTestDispatcher()) {
            val deps =
                StepUpDeps(
                    api = api,
                    env = env,
                    smsGate = WalletRateGate(backgroundScope) { 1_000L },
                    verifyGate = WalletRateGate(backgroundScope) { 1_000L },
                    sms = SmsDeadlines(),
                    tokens = StepUpTokenCache(),
                    clock = { 1_000L },
                    service = backgroundScope,
                )
            val controller = StepUpController(deps, backgroundScope) { true }
            controller.open("payout_account_change", FormOwner("u-1", 3L), onToken = {}, onClosed = {})
            state = controller.state.value
        }
        return api to state
    }

    @Test
    fun unboundPhoneDoesNotSend() {
        val (api, state) = open(Env(bound = false, slot = "13800005678"))
        assertTrue("未绑手机：本机有槽位键也不发码", api.calls.isEmpty())
        assertEquals(StepUpUiState.NO_PHONE, state?.phase)
    }

    @Test
    fun boundPhoneSendsWithoutALocalNumberAndRequiresASession() {
        val (api, _) = open(Env(bound = true, slot = null))
        val send = api.calls.single()
        assertEquals(StepUpController.SEND_OPERATION, send.operationId)
        assertTrue("面板发码要求就绪会话", send.requiresSession)
        assertTrue("请求体无 phone", "phone" !in Json.parseToJsonElement(send.body!!).jsonObject)
    }

    @Test
    fun unknownKeepsTheOldSlotRule() {
        val (none, state) = open(Env(bound = null, slot = null))
        assertTrue(none.calls.isEmpty())
        assertEquals(StepUpUiState.NO_PHONE, state?.phase)
        val (some, _) = open(Env(bound = null, slot = "13800005678"))
        assertEquals(1, some.calls.size)
        assertNull(StepUpController.slotPhone(Env(bound = false, slot = "13800005678"), "u-1"))
        assertEquals(StepUpController.BOUND_SLOT, StepUpController.slotPhone(Env(bound = true, slot = null), "u-1"))
    }
}
