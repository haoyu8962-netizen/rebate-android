package com.couli.app.orders

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 清语义金额探针的正反控制；反例必须被正式裁切判据拒绝，不能只证明探针找到一个节点。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualAmountProbeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun centeredShortTextWithClearedSemanticsPasses() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(280.dp).testTag("probe")) {
                    Text("¥1", Modifier.width(128.dp).clearAndSetSemantics { }, textAlign = TextAlign.Center)
                }
            }
        }
        probe("¥1").assertNoOverflow()
    }

    @Test fun shortPriceLocatedOutsideParentIsRejectedEvenWhenItsIntrinsicWidthFits() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(64.dp).clipToBounds().testTag("probe")) {
                    Text("¥1", Modifier.requiredWidth(280.dp).clearAndSetSemantics { }, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
        val text = probe("¥1")
        assertTrue(text.result.multiParagraph.maxIntrinsicWidth < text.bounds.width)
        val error = assertThrows(AssertionError::class.java) { text.assertNoOverflow() }
        assertTrue(error.message.orEmpty().contains("横向裁切（位置）"))
    }

    @Test fun longAmountHardClipIsRejected() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(64.dp).testTag("probe")) {
                    Text("¥999999999.99", Modifier.clearAndSetSemantics { }, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
        val error = assertThrows(AssertionError::class.java) { probe("¥999999999.99").assertNoOverflow() }
        assertTrue(error.message.orEmpty().contains("横向裁切"))
    }

    @Test fun ellipsizedAmountIsRejected() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(64.dp).testTag("probe")) {
                    Text("¥999999999.99", Modifier.clearAndSetSemantics { }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val error = assertThrows(AssertionError::class.java) { probe("¥999999999.99").assertNoOverflow() }
        assertTrue(error.message.orEmpty().contains("省略"))
    }

    private fun probe(text: String): OrderDrawnText {
        val values =
            rule
                .drawnTexts(rule.onNodeWithTag("probe").fetchSemanticsNode().layoutInfo)
                .filter { it.result.layoutInput.text.text == text }
        assertEquals(1, values.size)
        return values.single()
    }
}
