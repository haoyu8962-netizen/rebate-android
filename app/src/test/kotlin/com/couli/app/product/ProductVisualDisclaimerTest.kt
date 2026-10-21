package com.couli.app.product

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliIcons
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D7：实际入账行的像素要等于钟表，不能给测试语义写 clock 后仍画 i。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualDisclaimerTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun creditLineUsesActualClockPixels() {
        host.referenceIcon = CouliIcons.clock
        host.launch()
        assertIconMatchesReference("确认收货后随联盟月度结算入账")
    }

    @Test fun noRebateKeepsActualInfoPixels() {
        host.referenceIcon = CouliIcons.info
        host.launch("tb_rebate_gone")
        assertIconMatchesReference("券后价按单件计算")
    }

    private fun assertIconMatchesReference(text: String) {
        val line = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
        val reference = rule.onNodeWithText("参考图标", useUnmergedTree = true).fetchSemanticsNode()
        val actualRow = outerBounds(checkNotNull(line.layoutInfo.parentInfo))
        val refRow = outerBounds(checkNotNull(reference.layoutInfo.parentInfo))
        val size = (16 * line.layoutInfo.density.density).toInt()
        val pixels = rule.productPixels()
        try {
            val differences = pixels.iconMaskDifference(actualRow, refRow, size, host.muted.toArgb())
            assertTrue("图标轮廓不同：$differences / ${size * size} 像素；actual=$actualRow, reference=$refRow", differences <= size / 2)
        } finally {
            pixels.recycle()
        }
    }
}
