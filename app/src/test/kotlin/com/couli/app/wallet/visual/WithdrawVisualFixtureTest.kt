package com.couli.app.wallet.visual

import com.couli.core.wallet.PayoutAccountForm
import com.couli.core.wallet.WalletJson
import com.couli.feature.wallet.PayoutAccountController
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawVisualFixtureTest {
    @Test fun allVisualRulesUseTheLockedParserAndNoInventedWechatFields() {
        val rules = WithdrawVisualFixtures.group.getValue("rules").jsonObject
        assertEquals(25, rules.size)
        rules.forEach { (name, raw) ->
            assertNotNull(name, WalletJson.rules(raw.jsonObject))
            assertEquals(WithdrawVisualFixtures.rules().keys, raw.jsonObject.keys)
        }
        assertEquals(1791772200000L, WithdrawVisualFixtures.now)
    }

    @Test fun payoutPendingUsesActualRequestFieldsAndLocallyMaskedSummary() {
        val record = WithdrawVisualFixtures.pending(payout = true)
        val body = WithdrawVisualFixtures.obj(record.body)
        assertEquals(body, PayoutAccountForm.build(PayoutAccountForm.form(body)).body)
        assertEquals(Triple("alipay", "138****5678", "**三"), PayoutAccountController.pendingSummary(record.summary))
        assertTrue(record.summary.contains("138****5678"))
        assertTrue(!record.summary.contains("13800005678"))
    }
}
