package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTypography
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M6：检查真实用途说明标题的最终 TextLayout，不能以文案存在或截图人工检查代替字号断言。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardConsentTypographyVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun titleUsesDesignTitleSizeAndWeight() = assertTitle(1f)

    @Test fun titleUsesDesignTitleSizeAtFont200() = assertTitle(2f)

    private fun assertTitle(fontScale: Float) {
        driver.launch(fontScale = fontScale, fresh = true)
        driver.consent()
        val node =
            rule
                .onNodeWithText(ClipboardDraftTexts.text("clipboard.consent.title"), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts) }
        assertEquals(1, layouts.size)
        val input = layouts.single().layoutInput
        assertEquals("M6：标题不能退回 body 的 17sp", CouliTypography.title.fontSize, input.style.fontSize)
        assertEquals("标题字重遵循 title 令牌", CouliTypography.title.fontWeight, input.style.fontWeight)
        assertEquals("独立半屏内的真实字号缩放", fontScale, input.density.fontScale, 0.001f)
    }
}
