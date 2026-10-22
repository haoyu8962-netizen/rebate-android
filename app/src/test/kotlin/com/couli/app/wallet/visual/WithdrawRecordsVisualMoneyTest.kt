package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WithdrawalText
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualMoneyTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun fundsServerAmountsRemainIndependentEvenWhenArithmeticDoesNotBalance() {
        host.hidden = true
        host.launch("independent", focus = "INDEPENDENT")
        val amounts = listOf("申请金额" to 12345L, "手续费" to 123L, "代扣个税" to 456L, "实际到账" to 9876L)
        amounts.forEach { (label, value) ->
            listOf(false, true).forEach { unmerged ->
                val description = label + com.couli.core.designsystem.PriceTerms.SPOKEN_PAUSE + CouliFormat.moneySpoken(value)
                assertEquals(
                    "每项金额独立读法：$description",
                    1,
                    rule.onAllNodes(hasContentDescription(description), unmerged).fetchSemanticsNodes().size,
                )
            }
            assertTrue(rule.drawnTexts().contains(CouliFormat.money(value)))
        }
        assertFalse("记录页不跟钱包金额遮罩", rule.drawnTexts().contains("****"))
        assertFalse("不可用申请额减费税推算", rule.drawnTexts().contains(CouliFormat.money(12345 - 123 - 456)))
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun fundsNullMissingAndStringMoneyAreDroppedInsteadOfZeroFilled() {
        host.launch("empty")
        listOf("amount_fen", "fee_fen", "tax_fen", "net_fen").forEach { field ->
            val good = WithdrawRecordsVisualFixtures.detail("independent")
            listOf(
                JsonObject(good - field),
                JsonObject(good + (field to JsonNull)),
                JsonObject(good + (field to JsonPrimitive("123"))),
            ).forEach { bad ->
                host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.list(bad)))
                assertTrue(
                    "$field 无有效金额就不得展示记录",
                    host.controller.state.value.items
                        .isEmpty(),
                )
                rule.record("INDEPENDENT").assertDoesNotExist()
                assertTrue(rule.drawnTexts().none { it.contains("¥") })
            }
        }
        assertEquals(13, host.api.calls.size)
    }

    @Test fun fundsMalformedDetailRetainsSummaryAndDoesNotInventTradeNumber() {
        val bad = JsonObject(WithdrawRecordsVisualFixtures.detail("paid") + ("net_fen" to JsonNull))
        host.api.detailAnswers["PAID"] = WithdrawRecordsVisualFixtures.ok(bad)
        host.launch("paid")
        host.toggle("PAID")
        assertEquals(
            500L,
            host.controller.state.value.items
                .single()
                .netFen,
        )
        assertTrue(
            host.controller.state.value.details
                .isEmpty(),
        )
        rule.onNodeWithTag(WalletTags.RECORD_COPY).assertDoesNotExist()
        assertTrue(rule.drawnTexts().none { it == "¥0.00" })
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    @Test fun w32TaxHiddenWhenZeroAndPreservedInCompleteSpokenCollapsedNameWhenPositive() {
        host.launch("tax")
        val line = "已代扣个税 ¥0.8"
        assertFalse("收起只绘制首句", rule.drawnTexts().contains(line))
        assertEquals(1, rule.onAllNodes(hasContentDescription(line, substring = true)).fetchSemanticsNodes().size)
        host.toggle("TAX")
        rule.recordText(line, "TAX").assertIsDisplayed()
        host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("paid")))
        host.toggle("PAID")
        rule.onNodeWithText("代扣个税", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun w32AndW34ExistingDictionaryBranchesSurviveVisualChange() {
        host.launch("empty")
        val cases = listOf("auto_deciding", "auto_waiting", "approved", "paying", "failed_other", "manual", "paid", "reject_user_request")
        cases.forEach { name ->
            host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page(name)))
            val item =
                host.controller.state.value.items
                    .single()
            val expected = WithdrawalText.render(WithdrawalText.of(item, null), WalletDraftTexts)
            expected.forEach { line ->
                assertEquals(
                    "全文仍在行的读屏名：$name",
                    1,
                    rule.onAllNodes(hasContentDescription(line, substring = true)).fetchSemanticsNodes().size,
                )
            }
            rule.recordText(expected.first(), item.withdrawalId).assertIsDisplayed()
        }
    }

    @Test fun w33UnknownStatusAndWechatNeverExposeActionsOrInventProgress() {
        host.launch("empty")
        listOf("unknown", "wechat").forEach { name ->
            host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page(name)))
            host.toggle(WithdrawRecordsVisualFixtures.id(name))
            listOf(WalletTags.RECORD_COPY, WalletTags.RECORD_CHANGE_ACCOUNT, WalletTags.RECORD_CS).forEach {
                rule.onNodeWithTag(it).assertDoesNotExist()
            }
            val all = rule.drawnTexts().joinToString(" ")
            listOf("到账时间", "7 天", "审核时间较长", "到账有延迟", "去微信确认收款").forEach { assertFalse(all.contains(it)) }
        }
        assertTrue(host.env.opened.isEmpty())
        assertTrue(host.env.contacts.isEmpty())
        assertTrue(host.env.copies.isEmpty())
    }

    @Test fun w33PendingAndPaidDoNotInventDeadlineOrPaidAt() {
        host.launch("pending", focus = "PENDING")
        assertTrue(rule.drawnTexts().none { it.contains("前完成审核") })
        host.refresh(WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("paid")))
        host.toggle("PAID")
        rule.onNodeWithText("到账时间", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(rule.drawnTexts().none { it.contains("09-28 09:12") })
    }
}
