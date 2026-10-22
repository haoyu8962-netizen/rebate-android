package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonObject
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w16RiskWithdrawNavigatesOnceWithNoWalletRulesPrefetchOrWrites() {
        host.launch("risk")
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("Withdraw" to JsonObject(emptyMap())), host.env.opened)
        host.assertSummaryReads(1)
        rule.walletTag(WalletTags.TOAST).assertDoesNotExist()
    }

    @Test fun normalWithdrawKeepsSameEmptyParamsAndReadCount() {
        host.launch()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).performClick()
        assertEquals(listOf("Withdraw" to JsonObject(emptyMap())), host.env.opened)
        host.assertSummaryReads(1)
    }

    @Test fun w7RiskTextDoesNotInventReasonOrExpiryAndTapKeepsRiskNoticeRoute() {
        host.launch("risk")
        rule.walletText("提现已暂停").assertExists()
        assertEquals(0, rule.walletTextCount("fixture_unlisted_reason"))
        assertEquals(0, rule.walletTextCount("恢复"))
        rule.walletTag(WalletTags.WALLET_RISK).performClick()
        assertEquals(listOf("RiskNotice" to JsonObject(emptyMap())), host.env.opened)
        host.assertSummaryReads(1)
    }

    @Test fun w13EntriesAndRecordsKeepExactRoutesAndNoExtraRequests() {
        host.env.earnings = true
        host.env.since = "1.0.0"
        host.launch()
        listOf("订单", "余额流水", "收益看板", "提现记录").forEach { rule.walletText(it).performClick() }
        assertEquals(listOf("OrderList", "Ledger", "Earnings", "WithdrawRecords"), host.env.opened.map { it.first })
        assertTrue(host.env.opened.all { it.second.isEmpty() })
        host.assertSummaryReads(1)
    }

    @Test fun earningsFlagOnWithNullSinceStillHidesEntry() {
        host.env.earnings = true
        host.launch()
        assertEquals(0, rule.walletTextCount("收益看板"))
        host.assertSummaryReads(1)
    }

    @Test fun earningsFlagOffWithValidSinceStillHidesEntry() {
        host.env.since = "1.0.0"
        host.launch()
        assertEquals(0, rule.walletTextCount("收益看板"))
    }

    @Test fun staleTapCannotNavigateAndSuccessfulRefreshRestoresOriginalBalance() {
        host.seedCache()
        host.api.answer = WalletVisualFixtures.offline()
        host.launch()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsNotEnabled().performClick()
        assertTrue(host.env.opened.isEmpty())
        host.assertSummaryReads(1)
        host.refresh(WalletVisualFixtures.ok())
        rule.walletTag(WalletTags.WALLET_STALE).assertDoesNotExist()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsEnabled()
        rule.walletText("¥12.34").assertExists()
        host.assertSummaryReads(2)
    }

    @Test fun w17EyeHidesEveryAmountWithoutRequestAndRestoresServerValues() {
        host.launch()
        rule.onNodeWithContentDescription("隐藏金额").performClick()
        rule.waitForIdle()
        listOf(true, false).forEach { unmerged ->
            val page = rule.onNodeWithTag(WalletTags.WALLET_PAGE, useUnmergedTree = unmerged).fetchSemanticsNode().walletReadout()
            listOf("12.34", "56.78", "34.56", "4.56", "¥5", "¥0").forEach {
                assertFalse("读屏隐藏漏出 $it", page.contains(it))
                assertEquals("可见文字隐藏漏出 $it", 0, rule.walletTextCount(it))
            }
            // test-change r1 S2-2：整页任一“金额已隐藏”会误放行丢失的已收货项，改查每项独立读法。
            rule.assertWalletHiddenReadouts(
                listOf(
                    "可提现余额，金额已隐藏",
                    "冻结中，金额已隐藏",
                    "已提现，金额已隐藏",
                    "预估收益，金额已隐藏",
                    "其中 金额已隐藏 暂缓入账",
                    "其中已收货 金额已隐藏",
                ),
                unmerged,
            )
        }
        assertEquals(1, rule.walletTextCount("10 月结算"))
        assertTrue(host.mask.hidden.value)
        host.assertSummaryReads(1)
        rule.onNodeWithContentDescription("显示金额").performClick()
        rule.walletText("¥12.34").assertExists()
        rule.walletText("¥56.78").assertExists()
        host.assertSummaryReads(1)
    }

    @Test fun everyDisplayedAmountUsesItsOwnServerFieldWithoutSumming() {
        host.launch("no_sum")
        listOf("¥12.34", "¥4.56", "¥7.89", "¥56.78", "其中已收货 ¥0.02", "其中 ¥0.03 暂缓入账").forEach {
            rule.walletText(it).assertExists()
        }
        assertEquals(0, rule.walletTextCount("¥99"))
        assertEquals(0, rule.walletTextCount("¥0.01"))
        host.assertSummaryReads(1)
    }
}
