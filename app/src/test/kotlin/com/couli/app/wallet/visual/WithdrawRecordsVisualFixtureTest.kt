package com.couli.app.wallet.visual

import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WithdrawalText
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class WithdrawRecordsVisualFixtureTest {
    @Test fun everyVisualPageStrictlyParsesWithoutDroppingRows() {
        WithdrawRecordsVisualFixtures.pages.forEach { (name, raw) ->
            val page = checkNotNull(WalletJson.withdrawalList(raw)) { name }
            assertEquals(name, 0, page.dropped)
            assertEquals(
                name,
                raw.jsonObject
                    .getValue("items")
                    .jsonArray.size,
                page.items.size,
            )
            assertEquals(
                name,
                page.items.size,
                page.items
                    .map { it.withdrawalId }
                    .distinct()
                    .size,
            )
        }
    }

    @Test fun detailsAreValidButNeverContainUncontractedProgressFields() {
        val forbidden = setOf("review_deadline", "needs_manual", "channel_state", "confirm_deadline", "hold_days", "paid_at")
        WithdrawRecordsVisualFixtures.details.forEach { (name, raw) ->
            assertNotNull(name, WalletJson.withdrawalDetail(raw))
            assertFalse(name, raw.jsonObject.keys.any { it in forbidden })
        }
    }

    @Test fun gw09ExistingBranchesHaveExplicitIndependentExpectations() {
        val expected =
            mapOf(
                "auto_review" to "预计几分钟内到账",
                "auto_waiting" to "处理中，结果会通知你",
                "paying" to "处理中，结果会通知你",
                "paid" to "已转入支付宝 138****5678，到账 ¥5",
                "manual" to "已转入招商银行 尾号 1234，到账 ¥10",
                "failed_other" to "¥10 已退回可提现余额",
            )
        expected.forEach { (name, line) ->
            val item = checkNotNull(WalletJson.withdrawalDetail(WithdrawRecordsVisualFixtures.detail(name)))
            assertEquals(name, line, WithdrawalText.render(WithdrawalText.of(item, null), WalletDraftTexts).first())
        }
    }
}
