package com.couli.app.wallet.realname

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.couli.feature.wallet.WalletTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10-1002 前提（Release 形态不误提交资金动作）：生产接线下实名是真实实现，劳务后端仍 null、rules 回 block_code=10004（打款未就绪）。实名走通后进提现，【提交】置灰、引导进劳务页、
 * 【同意并签署】置灰（每一步固定断言，不跳过），点置灰按钮也不发；全程不得出现任何资金写请求（提现、收款账号、step-up 发码或验证、作废、劳务写）。对应 iOS RealnameFundsNoFundWriteTests。
 */
class RealnameFundsNoFundWriteTest : RealnameFundsHarness() {
    @Test fun realRealnameWithoutLaborBackendSendsNoFundWrite() {
        rulesReply = { envelope(RULES_BLOCKED_LABOR) }
        launch(labor = null)
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        idleUntil { realnameVerified() }
        assertFalse("实名本身通过（前提）", submits().isEmpty())

        open("Withdraw")
        idleUntil { has(WalletTags.WITHDRAW_GUIDE_BUTTON) }
        val submit = composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true)
        submit.assertIsNotEnabled()
        submit.performClick()
        settle()
        assertEquals("点置灰的【提交】仍停在提现页", "Withdraw", top()?.name)

        click(WalletTags.WITHDRAW_GUIDE_BUTTON)
        idleUntil { has(WalletTags.LABOR_UNAVAILABLE) }
        assertEquals("引导到达劳务协议页", "LaborAgreement", top()?.name)
        val sign = composeRule.onNodeWithTag(WalletTags.LABOR_SIGN, useUnmergedTree = true)
        sign.assertIsNotEnabled()
        sign.performClick()
        observe(OBSERVE_STEP_MS)

        assertEquals("不发提现", 0, recorded("POST", WITHDRAW_PATH).size)
        assertEquals("不写收款账号", 0, recorded("PUT", PAYOUT_PATH).size)
        assertEquals("不发 step-up 发码", 0, recorded("POST", SMS_PATH).size)
        assertEquals("不发 step-up 验证", 0, recorded("POST", STEP_UP_PATH).size)
        assertEquals("不发作废", 0, recorded("POST", ABANDON_PATH).size)
        assertTrue("劳务协议没有写接口调用", laborWrites().isEmpty())
    }

    private fun laborWrites(): List<Recorded> =
        synchronized(requests) { requests.filter { it.path.contains("labor") && it.method != "GET" } }
}
