package com.couli.app.wallet

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** APP-06v v3 / APP-08v r1 正反控制：短字位移裁切、填充文字盒不可骗过判据。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualOracleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun centeredShortTextInsideWideParentIsAccepted() {
        rule.setContent { CouliTheme { Text("提现", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } }
        rule.walletText("提现").assertWalletTextWhole()
    }

    @Test fun shortAmountOutsideParentIsRejectedEvenThoughItsGlyphsAreNarrow() {
        rule.setContent {
            CouliTheme {
                Box(Modifier.width(64.dp).clipToBounds()) {
                    Text("¥1", Modifier.requiredWidth(280.dp), softWrap = false)
                }
            }
        }
        val failure = assertThrows(AssertionError::class.java) { rule.walletText("¥1").assertWalletTextWhole() }
        assertTrue(failure.message.orEmpty().contains("横向裁切（位置）"))
    }

    @Test fun clearedAmountIsDrawableButNotExposedToScreenReader() {
        rule.setContent { CouliTheme { Text("¥56.78", Modifier.clearAndSetSemantics { }) } }
        rule.onNodeWithText("¥56.78", useUnmergedTree = true).assertDoesNotExist()
        rule.walletText("¥56.78").assertWalletTextWhole()
    }

    @Test fun filledLabelBoxCannotDisguiseGapAfterActualLastGlyph() {
        rule.setContent {
            CouliTheme {
                Row(Modifier.width(300.dp)) {
                    Text("预估收益", Modifier.weight(1f))
                    Text("?")
                }
            }
        }
        val label = rule.walletText("预估收益")
        val question = rule.walletText("?").walletBounds()
        val boxGap = question.left - label.walletBounds().right
        val glyphGap = question.left - label.walletLastGlyph().right
        assertTrue("旧盒间距会误放行", boxGap <= 8 * label.walletDp())
        assertTrue("实际字尾间距必须识别出填充空白", glyphGap > 100 * label.walletDp())
    }
}
