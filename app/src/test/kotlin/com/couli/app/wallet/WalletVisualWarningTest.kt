package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
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
class WalletVisualWarningTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w4NegativeWarningIsBelowHeroAboveEstimateAndHasExactTwoLines() {
        host.launch("negative")
        assertWarningLocation(WalletTags.WALLET_NEGATIVE)
        val first = rule.walletText("待抵扣 ¥5.67")
        val second = rule.walletText(WalletDraftTexts.text("error.30302"))
        assertTrue(second.walletBounds().top >= first.walletBounds().bottom)
        assertEquals(
            host.warning,
            first
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.warning,
            second
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            600,
            first
                .walletTextLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(0, rule.walletTextCount("后续返利将优先抵扣"))
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsNotEnabled()
    }

    @Test fun w5DisabledReasonIsSingleWarningBelowHero() {
        host.launch("disabled")
        assertWarningLocation(WalletTags.WALLET_DISABLED_REASON)
        val text = rule.walletText(WalletDraftTexts.text("error.30306"))
        assertEquals(
            host.warning,
            text
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            500,
            text
                .walletTextLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(1, rule.walletTextCount(WalletDraftTexts.text("error.30306")))
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsNotEnabled()
    }

    @Test fun w6RiskUsesTwoWarningColoredLinesAt14Sp() {
        host.launch("risk")
        val first =
            rule
                .walletText("提现已暂停")
                .walletTextLayout()
                .layoutInput.style
        val second =
            rule
                .walletText(WalletDraftTexts.text("wallet.risk_hint"))
                .walletTextLayout()
                .layoutInput.style
        assertEquals(host.warning, first.color)
        assertEquals(host.warning, second.color)
        assertEquals(600, first.fontWeight?.weight)
        assertEquals(14f, second.fontSize.value)
        assertEquals(400, second.fontWeight?.weight)
    }

    @Test fun w8OfflineBarPrecedesRiskAndRemainsPolite() {
        host.seedCache("risk")
        host.api.answer = WalletVisualFixtures.offline()
        host.launch()
        val stale = rule.walletTag(WalletTags.WALLET_STALE).fetchSemanticsNode()
        val risk = rule.walletTag(WalletTags.WALLET_RISK).walletBounds()
        assertTrue("断网条应最先显示", stale.boundsInRoot.bottom <= risk.top)
        assertEquals(LiveRegionMode.Polite, stale.config.getOrNull(SemanticsProperties.LiveRegion))
        assertTrue(stale.walletReadout().contains("09:30"))
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsNotEnabled()
        host.assertSummaryReads(1)
    }

    @Test fun w17NegativeMaskRemovesVisibleAndSpokenAmountInBothTrees() {
        host.launch("negative")
        rule.onNodeWithContentDescription("隐藏金额").performClick()
        rule.waitForIdle()
        val negative = rule.walletTag(WalletTags.WALLET_NEGATIVE).fetchSemanticsNode()
        assertFalse(negative.walletReadout().contains("5.67"))
        assertTrue(negative.walletReadout().contains("金额已隐藏"))
        assertEquals(0, rule.walletTextCount("5.67"))
        rule.walletText("待抵扣 ****").assertExists()
        rule.walletText(WalletDraftTexts.text("error.30302")).assertExists()
        host.assertSummaryReads(1)
    }

    @Test fun w17DarkWarningUsesDarkTokens() {
        host.launch("negative", dark = true)
        assertEquals(
            host.warning,
            rule
                .walletText("待抵扣 ¥5.67")
                .walletTextLayout()
                .layoutInput.style.color,
        )
        assertEquals(
            host.warning,
            rule
                .walletText(WalletDraftTexts.text("error.30302"))
                .walletTextLayout()
                .layoutInput.style.color,
        )
    }

    private fun assertWarningLocation(tag: String) {
        val warning = rule.walletTag(tag).walletBounds()
        val hero = rule.walletTag(WalletTags.WALLET_HERO).walletBounds()
        val estimate = rule.walletTag(WalletTags.WALLET_ESTIMATED).walletBounds()
        assertTrue("G-W06 警告条在主卡外下方", warning.top >= hero.bottom)
        assertTrue("警告条在预估区之前", warning.bottom <= estimate.top)
    }
}
