package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 主次像素判据的正/反控制，防止 Window 截取错误让所有样式断言一起失败。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualOracleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun recognizesPrimaryAndSecondaryAndRejectsReversal() {
        var color = Color.Unspecified
        var confirms = 0
        var dismisses = 0
        rule.setContent {
            CouliTheme {
                color = CouliColors.buttonPrimaryDefaultBackground
                CouliDialog("样例", "主按钮", { confirms += 1 }, { dismisses += 1 }, dismissText = "次按钮")
            }
        }
        rule.waitForIdle()
        val main = rule.fillRatio(rule.button("主按钮").fetchSemanticsNode(), color)
        val other = rule.fillRatio(rule.button("次按钮").fetchSemanticsNode(), color)
        assertPrimaryAndSecondary(main, other)
        assertThrows(AssertionError::class.java) { assertPrimaryAndSecondary(other, main) }
        rule.button("主按钮").performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
        rule.button("次按钮").performClick()
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
    }
}
