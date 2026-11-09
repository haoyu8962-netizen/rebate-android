package com.couli.app.wallet.realname

import com.couli.feature.wallet.FormOwner
import com.couli.feature.wallet.WalletTags
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成功放行、复查 GET /v1/me（复查失败保持已实名）、失败停留、换号丢弃、断连后改表单（D10-887 ③）。 */
class RealnameFundsFlowTest : RealnameFundsHarness() {
    @Test fun successPopsVerifiesRechecksAndDoesNotReplayWithdraw() {
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        val mesBefore = meGets()
        submitAndAgree(1)
        idleUntil { realnameVerified() }
        idleUntil { meGets() > mesBefore }
        assertEquals("出栈回钱包", "Wallet", top()?.name)
        assertEquals("不重放提现", 0, recorded("POST", WITHDRAW_PATH).size)
        open("Withdraw")
        idleUntil { has(WalletTags.WITHDRAW_SUBMIT) }
        assertEquals("再点【提现】直接进提现页", "Withdraw", top()?.name)
    }

    @Test fun successCountsEvenIfRecheckFails() {
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        meReply = { envelope("null", code = 50001) }
        submitAndAgree(1)
        idleUntil { realnameVerified() }
        observe(OBSERVE_STEP_MS)
        assertTrue("复查失败仍按已实名（三端一致）", realnameVerified())
        open("Withdraw")
        idleUntil { has(WalletTags.WITHDRAW_SUBMIT) }
        assertEquals("复查失败后提现照常放行", "Withdraw", top()?.name)
    }

    @Test fun failureKeepsWithdrawBlockedAndFormFilled() {
        submitReply = { envelope("null", code = 30406) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        idleUntil { errorBar() != null }
        assertEquals("停在实名页", "RealName", top()?.name)
        assertFalse("不按已实名", realnameVerified())
        assertEquals("保留已填内容（只在内存）", normalizedId, fieldText(WalletTags.REALNAME_ID))
    }

    @Test fun resultForOldAccountDoesNotVerifyNewAccount() {
        submitReply = { success().setBodyDelay(SUBMIT_DELAY_MS, TimeUnit.MILLISECONDS) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        meReply = { envelope("""{"user_id":"user-two","phone_bound":true,"realname_status":"none"}""") }
        switchSession("user-two")
        observe(AFTER_REPLY_MS)
        assertFalse("A 的成功结果不让 B 按已实名", realnameVerified())
        assertEquals("换号后不出栈（B 的表单）", "RealName", top()?.name)
        assertEquals("B 的表单已清空", "", fieldText(WalletTags.REALNAME_NAME))
        open("Withdraw")
        settle()
        assertEquals("B 进提现仍先进实名页", "RealName", top()?.name)
    }

    @Test fun consentForOldAccountDoesNotSubmitForNewAccount() {
        consentReply = { envelope("null").setBodyDelay(SUBMIT_DELAY_MS, TimeUnit.MILLISECONDS) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        toggleAndAgree()
        idleUntil { consents().size == 1 }
        meReply = { envelope("""{"user_id":"user-two","phone_bound":true,"realname_status":"none"}""") }
        switchSession("user-two")
        observe(AFTER_REPLY_MS)
        assertEquals("换号后 A 的同意结果不发实名提交", 0, submits().size)
    }

    @Test fun unknownThenEditedFormUsesNewKeyAndSucceeds() {
        submitReply = { index -> if (index == 0) disconnect() else success("**乙") }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        val first = submits()[0]
        idleUntil { submitEnabled() }
        assertFalse("断连不算成功", realnameVerified())
        fillForm(EDITED_NAME, EDITED_ID)
        assertTrue("断连后不锁表单（D10-887 ③）", submitEnabled())
        val mesBefore = meGets()
        resubmit()
        val second = submits()[1]
        assertKeyShape(second.header("Idempotency-Key"))
        assertFalse("改了表单换新键", first.header("Idempotency-Key") == second.header("Idempotency-Key"))
        assertFalse("新键配新请求体", first.body == second.body)
        idleUntil { realnameVerified() }
        idleUntil { meGets() > mesBefore }
        assertEquals("新键回已实名结果按成功，出栈", "Wallet", top()?.name)
    }

    /** S2-m07b：直接调入口（绕开网络层与控制器的核对），旧身份的结果不改实名状态、不触发复查。 */
    @Test fun realnameSubmittedWithStaleOwnerIsIgnored() {
        launch()
        val oldOwner = currentOwner()
        meReply = { envelope("""{"user_id":"user-two","phone_bound":true,"realname_status":"none"}""") }
        switchSession("user-two")
        val before = stableMeGets()
        composeRule.runOnUiThread { wallet.deps.env.realnameSubmitted(oldOwner) }
        val after = stableMeGets()
        assertFalse("旧身份的结果不按已实名", realnameVerified())
        assertEquals("旧身份的结果不触发复查 GET /v1/me", before, after)
    }

    /** m07b 的正向对照：当前身份立刻按已实名，并复查一次 GET /v1/me（证明上一例的「没动静」不是空过）。 */
    @Test fun realnameSubmittedWithCurrentOwnerVerifiesAndRechecks() {
        launch()
        val before = stableMeGets()
        val owner = currentOwner()
        composeRule.runOnUiThread { wallet.deps.env.realnameSubmitted(owner) }
        assertTrue("当前身份按已实名", realnameVerified())
        assertEquals("当前身份复查一次 GET /v1/me", before + 1, stableMeGets())
    }

    private fun currentOwner(): FormOwner {
        var owner: FormOwner? = null
        composeRule.runOnUiThread { owner = FormOwner.of(wallet.deps.env) }
        return checkNotNull(owner)
    }

    private companion object {
        const val SUBMIT_DELAY_MS = 400L
        const val AFTER_REPLY_MS = 1_000L
    }
}
