package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardUi
import com.couli.core.designsystem.CouliTypography
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-2：问句放不下时须让出整行，不允许缩字号、截字或拆开问句。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardInviteLargeFontVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun font200At390KeepsQuestionTogetherAndMovesActionsWhenNeeded() = assertQuestion()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200At320KeepsQuestionTogetherAndMovesActionsBelow() = assertQuestion()

    private fun assertQuestion() {
        driver.launch(guest = true, fontScale = 2f)
        driver.invite()
        val text = "检测到好友邀请码 ${ClipboardVisualFixtures.INVITE}，绑定为邀请人？"
        val node = rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread { checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertEquals("实际文字须使用 200%", 2f, layout.layoutInput.density.fontScale, 0.001f)
        assertEquals("不能缩小文字避开适配", CouliTypography.footnote.fontSize, layout.layoutInput.style.fontSize)
        val start = text.indexOf("绑定为邀请人？")
        assertEquals("整个问句必须在同一行", layout.getLineForOffset(start), layout.getLineForOffset(text.lastIndex))
        OverlayVisualProbe(rule).assertTextFits(rule.onNodeWithText(text, useUnmergedTree = true))
        val prompt = rule.onNodeWithTag(ClipboardUi.PROMPT_BAR).fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).assertIsDisplayed().fetchSemanticsNode()
        val close = rule.onNodeWithTag(ClipboardUi.PROMPT_CLOSE).assertIsDisplayed().fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val clauseWidth = (start..text.lastIndex).sumOf { layout.getBoundingBox(it).width.toDouble() }.toFloat()
        val inlineRoom = prompt.width - action.touchBoundsInRoot.width - close.touchBoundsInRoot.width - 16 * density
        if (clauseWidth > inlineRoom + 1) {
            assertTrue("空间不足时文字获得整行宽度", node.boundsInRoot.width >= prompt.width - 24 * density - 1)
            assertTrue(
                "空间不足时绑定与关闭按钮移到文字下方",
                action.boundsInRoot.top >= node.boundsInRoot.bottom && close.boundsInRoot.top >= node.boundsInRoot.bottom,
            )
        }
        assertTrue("绑定与关闭命中区不重叠", !action.touchBoundsInRoot.overlaps(close.touchBoundsInRoot))
        listOf(action, close).forEach { OverlayVisualProbe(rule).assertTarget48(it) }
    }
}
