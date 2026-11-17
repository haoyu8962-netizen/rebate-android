@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.wallet.stepup

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.network.SessionIdentity
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-STEPUP-SEND-STALE-HANG：只驱动真实控制器与 SmsDeadlines，网络和身份由替身控制。
 * 装配沿用 StepUpPhoneBoundGateTest，挂起回包沿用 WalletReviewR1Test 的 CompletableDeferred 写法。
 * 每条注释的「口径」对应本任务 brief.md 的 1–8；零发送指替身传输边界，不冒充真实 ApiClient 集成测试。
 *
 * 当前 StepUpUiState 没有说明位显隐字段。以下只断言现有 phase/error/resendLeftSec/smsLimited，
 * 不在测试里虚构 sendFailed/showSent，也不把这些断言冒充 StepUpSheet 的隐藏说明断言；缺口记入交回。
 */
@Suppress("TooManyFunctions") // 独立列出拒绝原因、换代方式与槽位交错，失败时可直接定位口径
class FuAndStepUpSendStaleHangTest {
    /** 口径 1、3、4、8：同账号同世代，仅 access 消失；成功体不能形成已发送状态或共享截止时间。 */
    @Test
    fun inFlightSuccessWithoutAccessSettlesAsRetryableFailure() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panel = rig.open()
            assertEquals(StepUpUiState.SENDING, panel.state.value?.phase)
            assertTrue(rig.sms.busy(slot()))
            rig.env.accessAvailable = false
            assertEquals(ACCOUNT, rig.env.storedAccount)
            assertEquals(EPOCH, rig.env.sessionEpoch())
            assertFalse(OWNER.current(rig.env))

            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            assertNoSharedResult(rig)
            assertFailed(panel.state.value, rig)
            advanceTimeBy(1_000)
            runCurrent()
            assertFailed(panel.state.value, rig)
            assertEquals("不得等待身份恢复后自动再发", 1, rig.api.attempts.size)
        }

    /** 口径 1、3、4：身份暂时读不出时连 42901 的限频字段也不能消费。 */
    @Test
    fun unreadableIdentityDiscardsLateRateLimitResult() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panel = rig.open()
            rig.env.readable = false
            rig.api.sent
                .single()
                .answer
                .complete(rateLimited())
            runCurrent()
            assertNoSharedResult(rig)
            assertFailed(panel.state.value, rig)
        }

    /** 口径 2、3、8：网络边界按 account_mismatch 本地拒绝，一条没发。 */
    @Test
    fun localAccountMismatchShowsFailureWithoutTransmission() = localReject(LocalRejectReason.ACCOUNT_MISMATCH)

    /** 口径 2、3、8：网络边界按 session_unreadable 本地拒绝，一条没发。 */
    @Test
    fun localSessionUnreadableShowsFailureWithoutTransmission() = localReject(LocalRejectReason.SESSION_UNREADABLE)

    /** 口径 1、3、5、8：open 已置 SENDING，但 send 的第一道身份守卫就拒绝，不能悬着。 */
    @Test
    fun openingWithoutAccessFailsBeforeCallingApi() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            rig.env.accessAvailable = false
            val panel = rig.open()
            runCurrent()
            assertTrue(rig.api.attempts.isEmpty())
            assertTrue(rig.api.sent.isEmpty())
            assertNoSharedResult(rig)
            assertFailed(panel.state.value, rig)
            rig.env.accessAvailable = true
            runCurrent()
            assertTrue("恢复身份不自动发码", rig.api.attempts.isEmpty())
            panel.resend(OWNER)
            assertEquals("恢复后可手动重新获取", 1, rig.api.sent.size)
        }

    /** 口径 1、3、4、5、8：A 等 B，同世代暂失 access，B stale 结算后 A 也必须落失败态。 */
    @Test
    fun awaitingPanelFailsWhenOtherSendSettlesWithoutAccess() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val senderB = rig.open()
            val waiterA = rig.open()
            assertEquals(StepUpUiState.SENDING, waiterA.state.value?.phase)
            assertEquals(1, rig.api.sent.size)
            rig.env.accessAvailable = false
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            assertEquals("等待者不自动补发", 1, rig.api.attempts.size)
            assertNoSharedResult(rig)
            assertFailed(waiterA.state.value, rig)
            assertFailed(senderB.state.value, rig)

            val settled = waiterA.state.value
            rig.env.accessAvailable = true
            val probe = requireNotNull(rig.sms.acquire(slot()))
            rig.sms.release(slot(), probe)
            runCurrent()
            assertEquals("等待已结束，后续槽位通知不改失败态", settled, waiterA.state.value)
            assertEquals(1, rig.api.attempts.size)
        }

    /** 口径 5、6、8：awaitOther 的世代也失效，不能在 valid=false 后永久保留 SENDING。 */
    @Test
    fun awaitingPanelDoesNotHangAfterSessionChanges() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            rig.open()
            val waiter = rig.open()
            rig.env.epoch += 1
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            waiter.resend(OWNER)
            assertEquals(1, rig.api.attempts.size)
            assertNoSharedResult(rig)
            assertSettledOrClosed(waiter, rig)
        }

    /** 口径 4、8：A stale 释放自己的槽位，不清掉不同 action 的 B 在途占用和随后倒计时。 */
    @Test
    fun staleReleaseKeepsAnotherActionsInFlightSendAndCountdown() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panelA = rig.open()
            val panelB = rig.open(action = StepUpController.PHONE_CHANGE)
            val slotB = slot(StepUpController.PHONE_CHANGE)
            assertEquals(2, rig.api.sent.size)
            rig.env.accessAvailable = false
            rig.api.sent[0]
                .answer
                .complete(success(30))
            runCurrent()
            val failedA = panelA.state.value
            assertFalse(rig.sms.busy(slot()))
            assertTrue("A 不能清全局槽位", rig.sms.busy(slotB))
            assertNull(rig.sms.acquire(slotB))
            assertEquals(StepUpUiState.SENDING, panelB.state.value?.phase)
            assertEquals(0L, rig.sms.remaining(slot(), rig.now()))

            rig.env.accessAvailable = true
            rig.api.sent[1]
                .answer
                .complete(success(48))
            runCurrent()
            assertSuccess(panelB, 48)
            assertEquals(48_000L, rig.sms.remaining(slotB, rig.now()))
            advanceTimeBy(1_000)
            runCurrent()
            assertSuccess(panelB, 47)
            assertEquals(47_000L, rig.sms.remaining(slotB, rig.now()))
            assertFalse(rig.sms.busy(slotB))
            assertEquals("A 失败后不跟随 B 起倒计时", failedA, panelA.state.value)
            assertEquals(2, rig.api.attempts.size)
            assertFailed(failedA, rig)
        }

    /**
     * 口径 4、6、8：同槽位票据被换代后的 B 接手，A 的迟到 finally 只能释放 A 的旧票据。
     * 正常同槽位互斥；用真实会话换代 clear → B acquire 构造票据替换，不推算或反射私有 ticket。
     */
    @Test
    fun staleOldTicketCannotReleaseReplacementSendInSameSlot() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panelA = rig.open()
            rig.env.epoch += 1
            rig.sms.clear()
            val panelB = rig.open(owner = FormOwner(ACCOUNT, rig.env.epoch))
            assertEquals(2, rig.api.sent.size)
            rig.api.sent[0]
                .answer
                .complete(success(30))
            runCurrent()
            assertTrue("旧 finally 不能释放 B 的票据", rig.sms.busy(slot()))
            assertNull(rig.sms.acquire(slot()))
            assertEquals(StepUpUiState.SENDING, panelB.state.value?.phase)
            assertEquals(0L, rig.sms.remaining(slot(), rig.now()))
            panelA.resend(OWNER)
            assertEquals(2, rig.api.attempts.size)

            rig.api.sent[1]
                .answer
                .complete(success(48))
            runCurrent()
            assertSuccess(panelB, 48)
            assertEquals(48_000L, rig.sms.remaining(slot(), rig.now()))
            assertFalse(rig.sms.busy(slot()))
            advanceTimeBy(1_000)
            runCurrent()
            assertSuccess(panelB, 47)
            assertEquals(2, rig.api.attempts.size)
            assertSettledOrClosed(panelA, rig)
        }

    /** 口径 6、8：同账号重新登录也是真换代；外层收起通知到来前不悬在 SENDING，不再发。 */
    @Test
    fun newLoginEpochSettlesBeforeDismissWithoutNewRequests() = sessionChange(logout = false)

    /** 口径 6、8：登出后迟到成功不消费、不续发；收起通知到来前必须离开 SENDING。 */
    @Test
    fun logoutSettlesBeforeDismissWithoutNewRequests() = sessionChange(logout = true)

    /** 口径 6、8 回归：外层流程调用 dismiss 后，迟到结果不能复活面板或新增请求。 */
    @Test
    fun dismissedSessionDoesNotReopenOnLateSuccess() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panel = rig.open()
            rig.env.epoch += 1
            rig.env.storedAccount = null
            // 对应 SensitiveFlow.resetFor / PhoneChangeController.leave 的既有收起入口。
            panel.dismiss()
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            panel.resend(OWNER)
            panel.verify(OWNER)
            assertNull(panel.state.value)
            assertFalse(panel.showing)
            assertEquals(1, rig.api.attempts.size)
            assertNoSharedResult(rig)
        }

    /** 口径 1、3、7、8：服务端确实成功，但回包时无 access；恢复身份后手动重发撞 42901 走旧提示。 */
    @Test
    fun successWithoutAccessThenManualRetryUsesExistingRateLimit() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panel = rig.open()
            rig.env.accessAvailable = false
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            val failed = panel.state.value
            assertNoSharedResult(rig)
            rig.env.accessAvailable = true
            runCurrent()
            assertEquals("身份恢复不自动发", 1, rig.api.attempts.size)
            panel.resend(OWNER)
            assertEquals(2, rig.api.sent.size)
            rig.api.sent[1]
                .answer
                .complete(rateLimited())
            runCurrent()
            val limited = requireNotNull(panel.state.value)
            assertEquals(StepUpUiState.READY, limited.phase)
            assertEquals("error.42901", limited.error?.key)
            assertTrue(limited.smsLimited)
            assertEquals(0L, limited.resendLeftSec)
            assertEquals(9_000L, rig.sms.limitedLeft(slot(), rig.now()))
            assertTrue(rig.smsGate.active)
            assertEquals(0L, rig.sms.remaining(slot(), rig.now()))
            panel.resend(OWNER)
            assertEquals("限频期内不多发", 2, rig.api.attempts.size)
            // 最后核第一拍，使当前旧实现也能先走完整个 42901 回归路径。
            assertFailedState(failed)
        }

    /** 口径 8 回归及 1、3 正对照：正常成功仍 READY、有倒计时、无失败提示，重开不多发。 */
    @Test
    fun normalSuccessIsReadyWithCountdownAndReopenDoesNotResend() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panel = rig.open()
            val call =
                rig.api.sent
                    .single()
                    .call
            assertEquals(StepUpController.SEND_OPERATION, call.operationId)
            assertEquals(ACCOUNT, call.expectedAccount)
            assertTrue(call.requiresSession)
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            assertSuccess(panel, 60)
            assertEquals(60_000L, rig.sms.remaining(slot(), rig.now()))
            assertFalse(rig.sms.busy(slot()))
            panel.resend(OWNER)
            panel.dismiss()
            val reopened = rig.open()
            runCurrent()
            assertSuccess(reopened, 60)
            advanceTimeBy(1_000)
            runCurrent()
            assertSuccess(reopened, 59)
            assertEquals(1, rig.api.attempts.size)
        }

    /** S2-01：本地拒绝落失败态后，手动重发成功清失败标记和错误，并恢复倒计时。 */
    @Test
    fun localRejectThenManualRetrySuccessClearsSendFailureAndStartsCountdown() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            rig.api.rejectReason = LocalRejectReason.ACCOUNT_MISMATCH
            val panel = rig.open()
            runCurrent()
            assertFailed(panel.state.value, rig)
            assertTrue(requireNotNull(panel.state.value).sendFailed)
            assertTrue(rig.api.sent.isEmpty())

            rig.api.rejectReason = null
            panel.resend(OWNER)
            assertEquals(2, rig.api.attempts.size)
            assertEquals(StepUpUiState.SENDING, panel.state.value?.phase)
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            assertFalse(requireNotNull(panel.state.value).sendFailed)
            assertSuccess(panel, 60)
            advanceTimeBy(1_000)
            runCurrent()
            assertSuccess(panel, 59)
        }

    private fun localReject(reason: String) =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            rig.api.rejectReason = reason
            val panel = rig.open()
            runCurrent()
            assertEquals("一次逻辑调用由网络边界拒绝", 1, rig.api.attempts.size)
            assertTrue("本地拒绝：零传输", rig.api.sent.isEmpty())
            assertNoSharedResult(rig)
            assertFailed(panel.state.value, rig)
            rig.api.rejectReason = null
            panel.resend(OWNER)
            assertEquals("用户手动重试才再次调用", 2, rig.api.attempts.size)
            assertEquals(1, rig.api.sent.size)
        }

    private fun sessionChange(logout: Boolean) =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(this)
            val panel = rig.open()
            rig.env.epoch += 1
            if (logout) rig.env.storedAccount = null
            panel.resend(OWNER)
            rig.api.sent
                .single()
                .answer
                .complete(success())
            runCurrent()
            panel.onCodeChange("123456", OWNER)
            panel.verify(OWNER)
            panel.resend(OWNER)
            assertEquals(1, rig.api.attempts.size)
            assertNoSharedResult(rig)
            assertSettledOrClosed(panel, rig)
            panel.dismiss()
            panel.resend(OWNER)
            assertNull(panel.state.value)
            assertEquals(1, rig.api.attempts.size)
        }

    private fun assertSettledOrClosed(
        panel: StepUpController,
        rig: Rig,
    ) {
        if (panel.state.value != null) assertFailed(panel.state.value, rig)
    }

    private fun assertFailed(
        state: StepUpUiState?,
        rig: Rig,
    ) {
        assertFailedState(state)
        assertFalse("钱包发码闸门不应挡重新获取", rig.smsGate.active)
    }

    private fun assertFailedState(state: StepUpUiState?) {
        val failed = requireNotNull(state) { "暂失身份不能冒充已收起" }
        assertEquals("结束 SENDING，回到可重新获取的状态", StepUpUiState.READY, failed.phase)
        assertEquals("D10-1792：发送失败暂用此键", "wallet.network_error", failed.error?.key)
        assertEquals(0L, failed.resendLeftSec)
        assertFalse(failed.smsLimited)
        assertNull("stale 不把结果的 trace 带进提示", failed.error?.traceId)
        assertFalse(failed.error?.showTrace6 ?: false)
        // 上面三项（READY、0 秒、非 smsLimited）加 smsGate=false，是现有 StepUpSheet 的 resendEnabled 条件。
        // 说明位的显隐目前没有状态字段，不能在这里写一个测试自造的布尔值当作产品断言。
    }

    private fun assertSuccess(
        panel: StepUpController,
        seconds: Long,
    ) {
        val state = requireNotNull(panel.state.value)
        assertEquals(StepUpUiState.READY, state.phase)
        assertEquals(seconds, state.resendLeftSec)
        assertNull(state.error)
        assertFalse(state.smsLimited)
    }

    private fun assertNoSharedResult(rig: Rig) {
        assertFalse("本次槽位必须释放", rig.sms.busy(slot()))
        assertEquals("不能写共享发码截止时间", 0L, rig.sms.remaining(slot(), rig.now()))
        assertEquals("不能写共享限频截止时间", 0L, rig.sms.limitedLeft(slot(), rig.now()))
        assertFalse(rig.smsGate.active)
    }

    private class Env : StepUpEnv {
        var storedAccount: String? = ACCOUNT
        var epoch = EPOCH
        var accessAvailable = true
        var readable = true

        override fun sessionEpoch(): Long = epoch

        override fun account(): String? = storedAccount.takeIf { readable && accessAvailable }

        override fun identitySnapshot(): SessionIdentity =
            if (!readable || !accessAvailable) {
                SessionIdentity.unknown(epoch)
            } else {
                SessionIdentity(known = true, account = storedAccount, loginEpoch = epoch, partition = null)
            }

        override fun stepUpPhone(account: String): String = PHONE

        override fun forceUpdate() = Unit
    }

    private class Sent(
        val call: ApiCall,
        val answer: CompletableDeferred<ApiResult> = CompletableDeferred(),
    )

    private class HoldingApi : PageApi {
        val attempts = mutableListOf<ApiCall>()
        val sent = mutableListOf<Sent>()
        var rejectReason: String? = null

        override suspend fun send(call: ApiCall): ApiResult {
            attempts += call
            rejectReason?.let { return ApiResult.LocalReject(it) }
            val pending = Sent(call)
            sent += pending
            return pending.answer.await()
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(1, 1)
    }

    private class Rig(
        val test: TestScope,
    ) {
        val env = Env()
        val api = HoldingApi()
        val sms = SmsDeadlines()
        val smsGate = WalletRateGate(test.backgroundScope, ::now)
        private val deps =
            StepUpDeps(
                api = api,
                env = env,
                smsGate = smsGate,
                verifyGate = WalletRateGate(test.backgroundScope, ::now),
                sms = sms,
                tokens = StepUpTokenCache(),
                clock = ::now,
                service = test.backgroundScope,
            )

        fun now(): Long = 1_000L + test.testScheduler.currentTime

        fun open(
            action: String = ACTION,
            owner: FormOwner = OWNER,
        ): StepUpController =
            StepUpController(deps, test.backgroundScope) { true }.also {
                it.open(action, owner, onToken = {}, onClosed = {})
            }
    }

    private companion object {
        const val ACCOUNT = "u-1"
        const val EPOCH = 3L
        const val PHONE = "13800005678"
        const val ACTION = "withdraw"
        val OWNER = FormOwner(ACCOUNT, EPOCH)

        fun slot(action: String = ACTION) = SmsDeadlines.Slot(ACCOUNT, PHONE, action)

        fun success(seconds: Long = 60): ApiResult =
            ApiResult.Success(
                Json.parseToJsonElement("""{"resend_after_sec":$seconds,"expires_in_sec":300}"""),
                "trace-stale-000001",
                null,
            )

        fun rateLimited(): ApiResult =
            ApiResult.Failure(
                42901,
                "",
                JsonObject(emptyMap()),
                "trace-stale-000002",
                ErrorEffect(42901, ErrorEffectKind.DISABLE_BUTTON, ReplayMode.NONE, false, disableMillis = 9_000),
                null,
            )
    }
}
