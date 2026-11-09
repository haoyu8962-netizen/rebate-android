package com.couli.app.wallet.realname

import com.couli.feature.wallet.WalletTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评审 r1 S1：调用停在网络层的恢复等待里（10002 单飞刷新，用闩卡住 POST /v1/auth/refresh）时离页，放行后不得再发实名提交或同意记录。回包后的 left 核对只能丢结果，挡不住 ApiClient 内部的重放。
 * 三条路（实名提交、同意记录、40901 自动重发进入的刷新）各四例：离页；不离页的对照（放行后确实重放，证明闩卡住的是重放，离页例不是空过）；换号；切环境。
 */
class RealnameFundsLeaveTest : RealnameFundsHarness() {
    private val hold = CountDownLatch(1)

    private fun refreshes(): Int = recorded("POST", REFRESH_PATH).size

    private fun leavePage() {
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        assertFalse("已离开实名页", has(WalletTags.REALNAME_NAME))
    }

    private fun switchToUserTwo() {
        meReply = { envelope("""{"user_id":"user-two","phone_bound":true,"realname_status":"none"}""") }
        switchSession("user-two")
    }

    private fun release(operationId: String) {
        hold.countDown()
        awaitCallsSettled(operationId)
        observe(AFTER_RELEASE_MS)
    }

    // ---------------------------------------------------------------- 实名提交：首发收到 10002

    private fun submitStuckInRefresh() {
        refreshHold = hold
        submitReply = { index -> if (index == 0) envelope("null", code = TOKEN_EXPIRED) else success() }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        idleUntil { refreshes() == 1 }
    }

    @Test fun submitLeaveDuringRefreshSendsNoReplay() {
        submitStuckInRefresh()
        leavePage()
        release(SUBMIT_OPERATION)
        assertEquals("离页后不得重放实名提交", 1, submits().size)
        assertFalse("离页后不按已实名", realnameVerified())
    }

    @Test fun submitStayingOnPageReplaysAfterRefresh() {
        submitStuckInRefresh()
        release(SUBMIT_OPERATION)
        assertEquals("对照：不离页时刷新后重放一次", 2, submits().size)
        assertEquals("重放沿用原键", submits()[0].header("Idempotency-Key"), submits()[1].header("Idempotency-Key"))
        idleUntil { realnameVerified() }
    }

    @Test fun submitAccountSwitchDuringRefreshSendsNoReplay() {
        submitStuckInRefresh()
        switchToUserTwo()
        release(SUBMIT_OPERATION)
        assertEquals("换号后不得重放实名提交", 1, submits().size)
        assertFalse("B 不按已实名", realnameVerified())
    }

    @Test fun submitEnvironmentSwitchDuringRefreshSendsNoReplay() {
        submitStuckInRefresh()
        composeRule.runOnUiThread { api.onEnvironmentSwitching() }
        release(SUBMIT_OPERATION)
        assertEquals("切环境后不得重放实名提交", 1, submits().size)
        assertFalse("切环境后不按已实名", realnameVerified())
    }

    // ---------------------------------------------------------------- 同意记录：首发收到 10002

    private fun consentStuckInRefresh() {
        refreshHold = hold
        val calls = AtomicInteger()
        consentReply = { if (calls.getAndIncrement() == 0) envelope("null", code = TOKEN_EXPIRED) else envelope("null") }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        toggleAndAgree()
        idleUntil { consents().size == 1 && refreshes() == 1 }
    }

    @Test fun consentLeaveDuringRefreshSendsNoReplayAndNoSubmit() {
        consentStuckInRefresh()
        leavePage()
        release(CONSENT_OPERATION)
        assertEquals("离页后不得重放同意记录", 1, consents().size)
        assertEquals("离页后不得发实名提交", 0, submits().size)
    }

    @Test fun consentStayingOnPageReplaysThenSubmits() {
        consentStuckInRefresh()
        release(CONSENT_OPERATION)
        assertEquals("对照：不离页时同意记录刷新后重放一次", 2, consents().size)
        idleUntil { submits().size == 1 }
    }

    @Test fun consentAccountSwitchDuringRefreshSendsNoReplayAndNoSubmit() {
        consentStuckInRefresh()
        switchToUserTwo()
        release(CONSENT_OPERATION)
        assertEquals("换号后不得重放同意记录", 1, consents().size)
        assertEquals("换号后不得发实名提交", 0, submits().size)
    }

    @Test fun consentEnvironmentSwitchDuringRefreshSendsNoReplayAndNoSubmit() {
        consentStuckInRefresh()
        composeRule.runOnUiThread { api.onEnvironmentSwitching() }
        release(CONSENT_OPERATION)
        assertEquals("切环境后不得重放同意记录", 1, consents().size)
        assertEquals("切环境后不得发实名提交", 0, submits().size)
    }

    // ---------------------------------------------------------------- 40901 自动重发：重发收到 10002

    private fun busyResendStuckInRefresh() {
        refreshHold = hold
        submitReply = { index ->
            when (index) {
                0 -> envelope("null", code = BUSY)
                1 -> envelope("null", code = TOKEN_EXPIRED)
                else -> success()
            }
        }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        idleUntil {
            observe(OBSERVE_STEP_MS)
            submits().size >= 2
        }
        idleUntil { refreshes() == 1 }
    }

    @Test fun busyResendLeaveDuringRefreshSendsNoReplay() {
        busyResendStuckInRefresh()
        leavePage()
        release(SUBMIT_OPERATION)
        assertEquals("离页后 40901 的重发不得再重放", 2, submits().size)
        assertFalse("离页后不按已实名", realnameVerified())
    }

    @Test fun busyResendStayingOnPageReplaysAfterRefresh() {
        busyResendStuckInRefresh()
        release(SUBMIT_OPERATION)
        assertEquals("对照：不离页时刷新后重放一次", 3, submits().size)
        assertEquals("重放沿用原键", submits()[0].header("Idempotency-Key"), submits()[2].header("Idempotency-Key"))
        idleUntil { realnameVerified() }
    }

    @Test fun busyResendAccountSwitchDuringRefreshSendsNoReplay() {
        busyResendStuckInRefresh()
        switchToUserTwo()
        release(SUBMIT_OPERATION)
        assertEquals("换号后 40901 的重发不得再重放", 2, submits().size)
        assertFalse("B 不按已实名", realnameVerified())
    }

    @Test fun busyResendEnvironmentSwitchDuringRefreshSendsNoReplay() {
        busyResendStuckInRefresh()
        composeRule.runOnUiThread { api.onEnvironmentSwitching() }
        release(SUBMIT_OPERATION)
        assertEquals("切环境后 40901 的重发不得再重放", 2, submits().size)
        assertTrue("切环境后没有成功结果", !realnameVerified())
    }

    private companion object {
        const val TOKEN_EXPIRED = 10002
        const val BUSY = 40901
        const val AFTER_RELEASE_MS = 500L
        const val SUBMIT_OPERATION = "submitRealname"
        const val CONSENT_OPERATION = "recordConsent"
    }
}
