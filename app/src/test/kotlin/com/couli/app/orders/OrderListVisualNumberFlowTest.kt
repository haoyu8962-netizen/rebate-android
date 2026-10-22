package com.couli.app.orders

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 同一正式断言的正反控制；故意错误的布局只存在于测试，不修改生产代码制造变异。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualNumberFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shortNumberWithEightDpAfterActualTextPasses() {
        row("订单号 1", fill = false)
        assertFlow()
    }

    @Test fun multilineNumberThenWholeCopyOnNextLinePasses() {
        content {
            Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberText("订单号 3741234567890123456")
                CopyText()
            }
        }
        assertMultiline()
        assertFlow()
    }

    @Test fun filledShortNumberBoxPushingCopyToTrailingEdgeIsRejected() {
        row("订单号 1", fill = true)
        assertLegacyBoxGapStillLooksCorrect()
        assertRejected("末行实际文字末端")
    }

    @Test fun filledMultilineBoxPushingCopyPastLastLineEndIsRejected() {
        // 最后一行很短；底部对齐排除“中部”因素，单独证明末行文字间距能拦住 weight(1f)。
        row("订单号 1234\n6", fill = true, alignment = Alignment.Bottom)
        assertMultiline()
        assertLegacyBoxGapStillLooksCorrect()
        assertRejected("末行实际文字末端")
    }

    @Test fun verticallyCenteredCopyBesideMultilineNumberIsRejected() {
        row("订单号\n374123\n6", fill = false)
        assertMultiline()
        assertRejected("多行单号中部")
    }

    private fun row(
        value: String,
        fill: Boolean,
        alignment: Alignment.Vertical = Alignment.CenterVertically,
    ) {
        content {
            Row(Modifier.width(280.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = alignment) {
                NumberText(value, Modifier.weight(1f, fill = fill))
                CopyText()
            }
        }
    }

    private fun content(block: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                CouliTheme { block() }
            }
        }
    }

    @Composable
    private fun NumberText(
        value: String,
        modifier: Modifier = Modifier,
    ) {
        Text(value, modifier.testTag("number"), style = CouliTypography.caption)
    }

    @Composable
    private fun CopyText() {
        Text("复制", Modifier.testTag("copy"), style = CouliTypography.caption, softWrap = false)
    }

    private fun assertMultiline() {
        val result = rule.onNodeWithTag("number").textLayout()
        assertEquals(2f, result.layoutInput.density.fontScale, .001f)
        assertTrue("反例必须实际多行", result.lineCount > 1)
    }

    private fun assertLegacyBoxGapStillLooksCorrect() {
        val number = rule.onNodeWithTag("number").fetchSemanticsNode()
        val copy = rule.onNodeWithTag("copy").fetchSemanticsNode()
        assertEquals("旧盒子间距判据会误放行此布局", 8f, (copy.boundsInRoot.left - number.boundsInRoot.right) / number.layoutInfo.density.density, .1f)
    }

    private fun assertFlow() = rule.onNodeWithTag("number").assertCopyFollowsLastLine(rule.onNodeWithTag("copy"))

    private fun assertRejected(reason: String) {
        val failure = assertThrows(AssertionError::class.java) { assertFlow() }
        assertTrue("必须因指定布局错误拒绝，不能靠其他异常冒充", failure.message.orEmpty().contains(reason))
    }
}
