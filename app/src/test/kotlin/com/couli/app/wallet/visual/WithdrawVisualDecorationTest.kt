package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualDecorationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w36ContentStartsEightBelowNavbarWithSixteenSideMargin() {
        host.launch()
        val balance = rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE).fetchSemanticsNode()
        val remaining = rule.onNodeWithTag(WalletTags.WITHDRAW_REMAINING).fetchSemanticsNode()
        val card = outerBounds(commonLayout(balance.layoutInfo, remaining.layoutInfo))
        val title = rule.onNodeWithText(WalletDraftTexts.text("withdraw.title"), true).fetchSemanticsNode()
        val d = balance.layoutInfo.density.density
        val nav = generateSequence(title.layoutInfo) { it.parentInfo }.first { it.width / d >= 389f && it.height / d < 80f }
        assertEquals(8f, (card.top - outerBounds(nav).bottom) / d, 1f)
        assertEquals(16f, card.left / d, 1f)
    }

    @Test fun w52IrrevocableTwelveSecondaryAndSlaFourteen() {
        host.launch()
        val text =
            rule
                .onNodeWithText(WalletDraftTexts.text("withdraw.irrevocable"), true)
                .textLayout()
                .layoutInput.style
        assertEquals(12f, text.fontSize.value, .01f)
        assertEquals(host.colors.getValue("text.secondary"), text.color)
        assertEquals(
            14f,
            rule
                .onNodeWithTag(WalletTags.WITHDRAW_SLA)
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
    }

    @Test fun w53RulesLinkFourteenRegular() {
        host.launch()
        val text =
            rule
                .onNodeWithText(WalletDraftTexts.text("withdraw.rules_link"), true)
                .textLayout()
                .layoutInput.style
        assertEquals(14f, text.fontSize.value, .01f)
        assertEquals(400, text.fontWeight?.weight ?: 400)
        assertEquals(host.colors.getValue("text.link"), text.color)
    }

    @Test fun w53FooterMatchesCanvasLight() = footer(false)

    @Test fun w55FooterMatchesCanvasDark() = footer(true)

    @Test fun w56PendingOuterCardIsSurfaceLight() = pending(false)

    @Test fun w56PendingOuterCardIsSurfaceDark() = pending(true)

    private fun footer(dark: Boolean) {
        host.launch(dark = dark)
        val button = rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).fetchSemanticsNode()
        val d = button.layoutInfo.density.density
        val bitmap = rule.productPixels()
        assertEquals(
            host.colors.getValue("background.canvas").toArgb(),
            bitmap.getPixel(
                (button.boundsInRoot.left - 6 * d).toInt(),
                button.boundsInRoot.center.y
                    .toInt(),
            ),
        )
    }

    private fun pending(dark: Boolean) {
        host.api.pending = WithdrawVisualFixtures.pending()
        host.launch(dark = dark)
        val card = rule.onNodeWithTag(WalletTags.PENDING_CARD).fetchSemanticsNode()
        val d = card.layoutInfo.density.density
        val outer = outerBounds(card.layoutInfo)
        assertEquals(
            host.colors.getValue("background.surface").toArgb(),
            rule.productPixels().getPixel(
                (outer.left + 6 * d).toInt(),
                outer.center.y
                    .toInt(),
            ),
        )
    }
}
