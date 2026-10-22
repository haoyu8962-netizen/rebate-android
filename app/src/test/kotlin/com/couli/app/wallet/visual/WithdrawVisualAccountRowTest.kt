package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualAccountRowTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w51PayoutGlyphsAlignRightBeforeChevronAndClickOnlyNavigates() {
        host.launch()
        val row = rule.onNodeWithTag(WalletTags.WITHDRAW_PAYOUT)
        val account = rule.onNodeWithText("支付宝 138****5678", useUnmergedTree = true)
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val text = account.textLayout()
        val glyphRight =
            account
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .positionInRoot()
                .x + text.getLineRight(0)
        val d = text.layoutInput.density.density
        assertTrue("账号字尾距卡片右缘应只剩20箭头、间距与内边距", (bounds.right - glyphRight) / d in 36f..52f)
        val before = host.api.events.toList()
        row.performTouchInput { click(center) }
        assertEquals(listOf("PayoutAccount"), host.env.opened.map { it.first })
        assertEquals(before, host.api.events)
    }

    @Test fun w54SubmittingDisablesInputAndQuickWithSubtleBorder() {
        host.api.writeHold = CompletableDeferred()
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        val input = rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).assertIsNotEnabled().fetchSemanticsNode()
        rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "500").assertIsNotEnabled()
        rule.onNodeWithTag(WalletTags.WITHDRAW_ALL).assertIsNotEnabled()
        val yuan = rule.onNodeWithText("¥", useUnmergedTree = true).fetchSemanticsNode()
        val box = outerBounds(commonLayout(input.layoutInfo, yuan.layoutInfo))
        val d = input.layoutInfo.density.density
        assertEquals(
            host.colors.getValue("border.subtle").toArgb(),
            rule.productPixels().getPixel((box.left + d / 2).toInt(), box.center.y.toInt()),
        )
        assertEquals(1, host.api.writes().size)
    }
}
