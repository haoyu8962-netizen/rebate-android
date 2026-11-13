package com.couli.app.orders

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.numwrap.NumWrap
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.WholeNumberText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 同一正式断言（[assertCopyClearOfNumber]，#24 ⑨ 新口径：NumCopy 可视窗口 + M10 流式位置）的正反控制；故意错误的布局只存在于测试，不修改生产代码制造变异。
 * FU-AND-NUM-WRAP（判据 1 / D10-1265）：旧口径的正例「号码在 160dp 里从数字中间断成多行、复制换到下一行」不再是正例——改为必须被判据 1
 * （[NumWrap.checkRun]「从中间断行」）拒绝；新增真实共用整段数字件横滑的正例，以及「横滑号码旁边并排放复制」的反例。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualNumberFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shortNumberWithEightDpAfterActualTextPasses() {
        row("订单号 1", fill = false)
        assertFlow("1")
    }

    /** 旧正例改反例：号码在数字中间断行，判据 1 必须拒绝（复制位置再对也不行）。 */
    @Test fun multilineNumberBrokenInTheMiddleIsRejectedByCriterionOne() {
        content {
            Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberText("订单号 $LONG_NO")
                CopyText()
            }
        }
        assertMultiline()
        val failure = assertThrows(AssertionError::class.java) { assertWhole(LONG_NO) }
        assertTrue("必须因判据 1「从中间断行」拒绝，不能靠其他异常冒充", failure.message.orEmpty().contains("从中间断行"))
    }

    /** 新口径正例：真实共用整段数字件，独占一行仍放不下时框内横滑；【复制】整体换到下一行、回到列起点。 */
    @Test fun scrollingNumberThenWholeCopyOnNextLinePasses() {
        content {
            Column(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WholeNumberText("订单号 $LONG_NO", style = CouliTypography.caption, textModifier = Modifier.testTag(NUMBER))
                CopyText()
            }
        }
        assertEquals("正例必须实际单行（横滑）", 1, rule.onNodeWithTag(NUMBER, useUnmergedTree = true).textLayout().lineCount)
        rule.onNodeWithTag(NUMBER, useUnmergedTree = true).assertNumberScrollsFromStart("自测横滑号码")
        assertWhole(LONG_NO)
        assertFlow(LONG_NO)
    }

    /** 新口径反例：号码横滑（末端在视口外）却把【复制】并排放在横滑框旁边。 */
    @Test fun copyBesideAScrollingNumberIsRejected() {
        content {
            // 200dp 行减去复制与间距后约 168dp，19 位号码（200% caption）整段约 250dp，必然横滑
            Row(
                Modifier.width(200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WholeNumberText(
                    "订单号 $LONG_NO",
                    style = CouliTypography.caption,
                    modifier = Modifier.weight(1f),
                    textModifier = Modifier.testTag(NUMBER),
                )
                CopyText()
            }
        }
        rule.onNodeWithTag(NUMBER, useUnmergedTree = true).assertNumberScrollsFromStart("自测横滑号码")
        assertRejected(LONG_NO, "横滑号码同一行")
    }

    @Test fun filledShortNumberBoxPushingCopyToTrailingEdgeIsRejected() {
        row("订单号 1", fill = true)
        assertLegacyBoxGapStillLooksCorrect()
        assertRejected("1", "末行实际文字末端")
    }

    @Test fun filledMultilineBoxPushingCopyPastLastLineEndIsRejected() {
        // 最后一行很短；底部对齐排除“中部”因素，单独证明末行文字间距能拦住 weight(1f)。
        row("订单号 1234\n6", fill = true, alignment = Alignment.Bottom)
        assertMultiline()
        assertLegacyBoxGapStillLooksCorrect()
        assertRejected("1234", "末行实际文字末端")
    }

    @Test fun verticallyCenteredCopyBesideMultilineNumberIsRejected() {
        row("订单号\n374123\n6", fill = false)
        assertMultiline()
        assertRejected("374123", "多行单号中部")
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
        Text(value, modifier.testTag(NUMBER), style = CouliTypography.caption)
    }

    /** 与真实【复制】同写法：可点（Role.Button），命中区由框架补到 48dp，视觉仍是一行字。 */
    @Composable
    private fun CopyText() {
        Text(
            "复制",
            Modifier.clickable(role = Role.Button, onClick = {}).testTag(COPY),
            style = CouliTypography.caption,
            softWrap = false,
        )
    }

    private fun assertMultiline() {
        val result = rule.onNodeWithTag(NUMBER).textLayout()
        assertEquals(2f, result.layoutInput.density.fontScale, .001f)
        assertTrue("反例必须实际多行", result.lineCount > 1)
    }

    private fun assertLegacyBoxGapStillLooksCorrect() {
        val number = rule.onNodeWithTag(NUMBER).fetchSemanticsNode()
        val copy = rule.onNodeWithTag(COPY).fetchSemanticsNode()
        assertEquals("旧盒子间距判据会误放行此布局", 8f, (copy.boundsInRoot.left - number.boundsInRoot.right) / number.layoutInfo.density.density, .1f)
    }

    /** 判据 1：号码整段不断、不截断、不缩字、首尾可达。 */
    private fun assertWhole(run: String) = NumWrap.checkRun(rule, rule.onNodeWithTag(NUMBER, useUnmergedTree = true), run, "自测号码")

    private fun assertFlow(run: String) = rule.onNodeWithTag(NUMBER, useUnmergedTree = true).assertCopyClearOfNumber(rule, run, COPY, "自测")

    private fun assertRejected(
        run: String,
        reason: String,
    ) {
        val failure = assertThrows(AssertionError::class.java) { assertFlow(run) }
        assertTrue("必须因指定布局错误拒绝，不能靠其他异常冒充（实际：${failure.message}）", failure.message.orEmpty().contains(reason))
    }

    private companion object {
        const val NUMBER = "number"
        const val COPY = "copy"

        /** QA-11 截图里断开的那串（19 位）。 */
        const val LONG_NO = "3741234567890123456"
    }
}
