package com.couli.app.me

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.core.designsystem.CouliTheme
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualOracleTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun wholeCenteredTextPassesWidthAndPositionOracle() {
        rule.setContent { CouliTheme { Text("完整文字", Modifier.width(160.dp).testTag("text"), textAlign = TextAlign.Center) } }
        rule.meTag("text").assertNoTextOverflow()
    }

    @Test fun shortTextOutsideParentIsRejectedByPosition() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(64.dp).clipToBounds()) {
                    Text("¥1", Modifier.requiredWidth(280.dp).testTag("text"), softWrap = false)
                }
            }
        }
        val error = assertThrows(AssertionError::class.java) { rule.meTag("text").assertNoTextOverflow() }
        assertTrue(error.message!!.contains("横向裁切（位置）"))
    }

    @Test fun filledTextBoxCannotHideCopyPushedToRowEnd() {
        rule.setContent {
            CouliTheme {
                Row(Modifier.fillMaxWidth()) {
                    Text("K7M3QX", Modifier.weight(1f).testTag("text"))
                    Box(Modifier.size(16.dp).semantics { contentDescription = "复制" }.testTag("copy"))
                }
            }
        }
        val error = assertThrows(AssertionError::class.java) { rule.meTag("text").assertMeCopyFollows(rule.meTag("copy")) }
        assertTrue(error.message!!.contains("实际末字"))
    }

    @Test fun compactTextWithAdjacentCopyPassesGapOracle() {
        rule.setContent {
            CouliTheme {
                Row {
                    Text("K7M3QX", Modifier.testTag("text"))
                    Box(Modifier.size(16.dp).semantics { contentDescription = "复制" }.testTag("copy"))
                }
            }
        }
        rule.meTag("text").assertMeCopyFollows(rule.meTag("copy"))
    }
}
