package com.couli.app.clipboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** APP-06v T1 同式自证：普通 Text(String)、200% 字号，不加 width(IntrinsicSize.Max)。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayTextBoundsVisualTest {
    @get:Rule val rule = createComposeRule()
    private val probe by lazy { OverlayVisualProbe(rule) }

    @Test fun centeredEnableFitsWithoutIntrinsicWidthWorkaround() = assertCenteredButtonFits("开启")

    @Test fun centeredNotNowFitsWithoutIntrinsicWidthWorkaround() = assertCenteredButtonFits("暂不开启")

    @Test fun centeredBuyFitsWithoutIntrinsicWidthWorkaround() = assertCenteredButtonFits("领券购买")

    @Test fun shortAmountInsideWiderConstraintsIsNotClipping() {
        show {
            Box(Modifier.width(256.dp)) {
                Text("预估返后 ¥87654.32", Modifier.testTag(TEXT), style = CouliTextStyles.captionAmount)
            }
        }
        val result = rule.onNodeWithTag(TEXT).overlayTextLayout()
        assertTrue("复现 T1 金额行宽差", result.layoutInput.constraints.maxWidth > result.size.width)
        probe.assertTextFits(rule.onNodeWithTag(TEXT).assertIsDisplayed())
    }

    @Test fun fullyVisibleWrappedTextIsAllowed() {
        show {
            Text("领券购买 · 预估返 ¥2.34", Modifier.width(200.dp).testTag(TEXT), style = CouliTextStyles.bodySemibold)
        }
        assertTrue("必须实际换行", rule.onNodeWithTag(TEXT).overlayTextLayout().lineCount > 1)
        probe.assertTextFits(rule.onNodeWithTag(TEXT).assertIsDisplayed())
    }

    @Test fun actualHorizontalClipStillFailsWithoutEllipsis() {
        show {
            Text(
                "¥99999.99",
                Modifier.width(64.dp).testTag(TEXT),
                style = CouliTextStyles.amountDisplay,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        assertRejected("横向裁切")
    }

    @Test fun ellipsizedTextIsRejected() {
        show {
            Text(
                "领券购买 · 预估返 ¥99999.99",
                Modifier.width(96.dp).testTag(TEXT),
                style = CouliTextStyles.bodySemibold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        assertRejected("省略")
    }

    @Test fun maxLinesClippingIsRejectedWithoutEllipsis() {
        show {
            Text(
                "领券购买 · 预估返 ¥99999.99",
                Modifier.width(96.dp).testTag(TEXT),
                style = CouliTextStyles.bodySemibold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        assertRejected("纵向裁切")
    }

    @Test fun parentClippingFailsEvenWhenTextFitsItsOwnLayout() {
        show {
            Box(Modifier.width(64.dp).clipToBounds()) {
                Text(
                    "预估返后 ¥87654.32",
                    Modifier.requiredWidth(280.dp).testTag(TEXT),
                    style = CouliTextStyles.captionAmount,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        val node = rule.onNodeWithTag(TEXT).assertIsDisplayed()
        val result = node.overlayTextLayout()
        assertTrue("文字自身完整，裁切来自父容器", result.multiParagraph.maxIntrinsicWidth <= result.size.width)
        assertTrue("父容器确实裁窄可见区域", node.fetchSemanticsNode().boundsInRoot.width < result.size.width)
        assertRejected("横向裁切")
    }

    @Test fun shortPriceOutsideParentVisibleAreaIsRejected() {
        show {
            Box(Modifier.width(64.dp).clipToBounds()) {
                Text(
                    "¥1",
                    Modifier.requiredWidth(280.dp).testTag(TEXT),
                    style = CouliTextStyles.captionAmount,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        val text = rule.onNodeWithTag(TEXT)
        val result = text.overlayTextLayout()
        val node = text.fetchSemanticsNode()
        val visible = node.boundsInRoot
        val origin = node.layoutInfo.coordinates.positionInRoot()
        assertTrue("短价格宽度小于可见区，旧宽度判据会漏报", result.multiParagraph.maxIntrinsicWidth < visible.width)
        assertTrue("反例文字确实完全落在可见区左侧", origin.x + result.getLineRight(0) < visible.left)
        println("r2 短价格探针：原点=$origin，行右边=${origin.x + result.getLineRight(0)}，可见区=$visible")
        assertRejected("横向裁切（位置）")
    }

    private fun assertCenteredButtonFits(label: String) {
        show {
            Box(Modifier.width(280.dp), contentAlignment = Alignment.Center) {
                Text(label, Modifier.testTag(TEXT), style = CouliTextStyles.bodySemibold, textAlign = TextAlign.Center)
            }
        }
        val node = rule.onNodeWithTag(TEXT).assertIsDisplayed()
        val result = node.overlayTextLayout()
        assertTrue("实际按 200% 字号排版", result.layoutInput.density.fontScale == 2f)
        assertTrue("复现 T1 父约束宽于文字节点", result.layoutInput.constraints.maxWidth > result.size.width)
        assertTrue("旧绝对右边界判据确实会误报", result.getLineRight(0) > result.size.width + 1f)
        probe.assertTextFits(node)
        println("T1 自证：居中 $label，父宽=${result.layoutInput.constraints.maxWidth}，节点宽=${result.size.width}，旧右界=${result.getLineRight(0)} → 通过")
    }

    private fun assertRejected(reason: String) {
        val node = rule.onNodeWithTag(TEXT).assertIsDisplayed()
        val failure = assertThrows(AssertionError::class.java) { probe.assertTextFits(node) }
        assertTrue("必须是预期裁切红灯：${failure.message}", failure.message.orEmpty().contains(reason))
        println("T1 自证：$reason → 已捕获 AssertionError：${failure.message}")
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                CouliTheme { Column(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private companion object {
        const val TEXT = "overlay_overflow_oracle_text"
    }
}
