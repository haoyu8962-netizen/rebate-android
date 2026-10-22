package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.textLayout
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w37BalanceUsesSeparate34BoldTnumAmountAnd12Counts() {
        host.launch()
        val amount = rule.onNode(hasText("¥12.34") and hasAnyAncestor(hasTestTag(WalletTags.WITHDRAW_BALANCE)), true)
        val style = amount.textLayout().layoutInput.style
        assertEquals(34f, style.fontSize.value, .01f)
        assertEquals(700, style.fontWeight?.weight)
        assertTrue(style.fontFeatureSettings.orEmpty().contains("tnum"))
        assertEquals(
            12f,
            rule
                .onNodeWithTag(WalletTags.WITHDRAW_REMAINING)
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
    }

    @Test fun w38InputIs28SemiboldAndVisualContainer56() {
        host.launch()
        host.input()
        val input = rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT)
        val style = input.textLayout().layoutInput.style
        assertEquals(28f, style.fontSize.value, .01f)
        assertEquals(600, style.fontWeight?.weight)
        val n = input.fetchSemanticsNode()
        val yuan = rule.onNodeWithText("¥", useUnmergedTree = true).fetchSemanticsNode()
        val bounds = outerBounds(commonLayout(n.layoutInfo, yuan.layoutInfo))
        assertEquals(56f, bounds.height / n.layoutInfo.density.density, 1f)
    }

    @Test fun w39AllIs14MediumAndW40MaxIs12Tnum() {
        host.launch()
        val all =
            rule
                .onNodeWithText("全部提现", useUnmergedTree = true)
                .textLayout()
                .layoutInput.style
        assertEquals(14f, all.fontSize.value, .01f)
        assertEquals(500, all.fontWeight?.weight)
        val max =
            rule
                .onNodeWithTag(WalletTags.WITHDRAW_MAX)
                .textLayout()
                .layoutInput.style
        assertEquals(12f, max.fontSize.value, .01f)
        assertTrue(max.fontFeatureSettings.orEmpty().contains("tnum"))
    }

    @Test fun w41QuickHas32VisualBut48TouchAndNoSelectionSemantics() {
        host.launch()
        val chip = rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "500").fetchSemanticsNode()
        val d = chip.layoutInfo.density.density
        assertEquals(32f, chip.layoutInfo.height / d, 1f)
        assertTrue(chip.touchBoundsInRoot.height >= 48 * d)
        assertFalse(chip.config.contains(SemanticsProperties.Selected))
        val other = rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "1000").fetchSemanticsNode()
        assertRecordTargetsSeparate(chip.touchBoundsInRoot, other.touchBoundsInRoot)
    }

    @Test fun w42EstimateRemainsOnePoliteTextWithIndependentServerMoney() {
        host.api.estimate = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules("independent"))
        host.launch()
        host.input()
        host.settleEstimate()
        val estimate = rule.onNodeWithTag(WalletTags.WITHDRAW_ESTIMATE).performScrollTo().fetchSemanticsNode()
        val texts = estimate.config[SemanticsProperties.Text]
        assertEquals(1, texts.size)
        listOf("8.76", "0.34", "0.56").forEach { assertTrue(texts.single().text.contains(it)) }
        assertEquals(LiveRegionMode.Polite, estimate.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals(0, host.api.writes().size)
    }

    @Test fun w43CalculatingHas16SpinnerButNoLiveRegion() {
        host.api.estimateHold = CompletableDeferred()
        host.launch()
        host.input()
        val calculating = rule.onNodeWithTag(WalletTags.WITHDRAW_CALCULATING).fetchSemanticsNode()
        assertEquals(null, calculating.config.getOrNull(SemanticsProperties.LiveRegion))
        val progress =
            rule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher
                        .keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                    true,
                ).fetchSemanticsNodes()
        assertTrue("W43计算中应有16dp进度圈", progress.any { kotlin.math.abs(it.boundsInRoot.width / it.layoutInfo.density.density - 16f) < 1f })
    }

    @Test fun w44FailedEstimateHidesEstimateAndItsDivider() {
        host.api.estimate = WithdrawVisualFixtures.failure()
        host.launch()
        host.input()
        host.settleEstimate()
        rule.onNodeWithTag(WalletTags.WITHDRAW_ESTIMATE).assertDoesNotExist()
        assertEquals(0, host.api.writes().size)
    }

    @Test fun w46GuideAndInputShareAmountCardBeforePayout() {
        host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules("blocked_30304"))
        host.launch()
        val input = rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).fetchSemanticsNode()
        val guide = rule.onNodeWithTag(WalletTags.WITHDRAW_GUIDE_BUTTON).fetchSemanticsNode()
        val balance = rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE).fetchSemanticsNode()
        val card = outerBounds(commonLayout(input.layoutInfo, guide.layoutInfo))
        assertTrue("引导与金额须同卡，不能把余额卡也包含进来", card.top > balance.boundsInRoot.bottom)
        assertTrue(guide.boundsInRoot.top > input.boundsInRoot.bottom)
    }
}
