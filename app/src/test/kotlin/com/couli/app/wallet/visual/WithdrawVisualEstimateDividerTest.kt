package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
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
class WithdrawVisualEstimateDividerTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w42ShownEstimateHasRealFullWidthHairline() = divider(true)

    @Test fun w44FailedEstimateRemovesRealHairlineToo() = divider(false)

    private fun divider(shown: Boolean) {
        if (!shown) host.api.estimate = WithdrawVisualFixtures.failure()
        host.launch()
        host.input()
        host.settleEstimate()
        val input = rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).fetchSemanticsNode()
        val quick = rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "1000").fetchSemanticsNode()
        val card = outerBounds(commonLayout(input.layoutInfo, quick.layoutInfo))
        val d = quick.layoutInfo.density.density
        val bottom =
            if (shown) {
                rule
                    .onNodeWithTag(WalletTags.WITHDRAW_ESTIMATE)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            } else {
                card.bottom -
                    8 * d
            }
        val left = (card.left + 16 * d).toInt()
        val right = (card.right - 16 * d).toInt()
        val bitmap = rule.productPixels()
        val color = host.colors.getValue("border.subtle").toArgb()
        val rows =
            (quick.boundsInRoot.bottom.toInt() + 1 until bottom.toInt()).count { y ->
                (left until right).count { x -> bitmap.getPixel(x, y) == color } >= (right - left) * .8f
            }
        assertEquals("分隔线必须随估算一起出现/消失，不能靠空tag假通过", shown, rows >= d)
        assertEquals(0, host.api.writes().size)
    }
}
