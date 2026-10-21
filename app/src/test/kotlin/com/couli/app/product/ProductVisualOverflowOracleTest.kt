package com.couli.app.product

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

/** T1 的正反控制：全部普通 Text(String)，不加 width(IntrinsicSize.Max)，不依赖产品侧规避。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualOverflowOracleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun centeredShortButtonFitsWithoutIntrinsicWidthWorkaround() {
        show {
            Box(Modifier.width(318.dp), contentAlignment = Alignment.Center) {
                Text("去购买 · 预估返 ¥2.34", Modifier.testTag(TEXT), style = CouliTextStyles.bodySemibold, textAlign = TextAlign.Center)
            }
        }
        val result = rule.onNodeWithTag(TEXT).textLayout()
        assertTrue("复现T1父约束比实际字宽更宽", result.layoutInput.constraints.maxWidth > result.size.width)
        rule.onNodeWithTag(TEXT).assertNoTextOverflow()
    }

    @Test fun shortMetadataFitsWithoutIntrinsicWidthWorkaround() {
        show {
            Box(Modifier.width(256.dp)) {
                Text("预估返后 ¥87654.32", Modifier.testTag(TEXT), style = CouliTextStyles.captionAmount)
            }
        }
        val result = rule.onNodeWithTag(TEXT).textLayout()
        assertTrue("复现T1取价行宽差", result.layoutInput.constraints.maxWidth > result.size.width)
        rule.onNodeWithTag(TEXT).assertNoTextOverflow()
    }

    @Test fun fullyVisibleWrappedTextIsAllowed() {
        show {
            Text("去购买 · 预估返 ¥2.34", Modifier.width(200.dp).testTag(TEXT), style = CouliTextStyles.bodySemibold)
        }
        assertTrue(rule.onNodeWithTag(TEXT).textLayout().lineCount > 1)
        rule.onNodeWithTag(TEXT).assertNoTextOverflow()
    }

    @Test fun realHorizontalClipFailsEvenWithoutEllipsis() {
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
                "去购买 · 预估返 ¥99999.99",
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
                "去购买 · 预估返 ¥99999.99",
                Modifier.width(96.dp).testTag(TEXT),
                style = CouliTextStyles.bodySemibold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        assertRejected("纵向裁切")
    }

    @Test fun parentClippingStillFailsWhenTextItselfFitsItsOwnLayout() {
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
        val result = text.textLayout()
        val node = text.fetchSemanticsNode()
        val visible = node.boundsInRoot
        val origin = node.layoutInfo.coordinates.positionInRoot()
        assertTrue("短价格宽度小于可见区，旧宽度判据会漏报", result.multiParagraph.maxIntrinsicWidth < visible.width)
        assertTrue("反例文字确实完全落在可见区左侧", origin.x + result.getLineRight(0) < visible.left)
        println("r2 短价格探针：原点=$origin，行右边=${origin.x + result.getLineRight(0)}，可见区=$visible")
        assertRejected("横向裁切")
    }

    private fun assertRejected(reason: String) {
        val failure = assertThrows(AssertionError::class.java) { rule.onNodeWithTag(TEXT).assertNoTextOverflow() }
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
        const val TEXT = "overflow_oracle_text"
    }
}
