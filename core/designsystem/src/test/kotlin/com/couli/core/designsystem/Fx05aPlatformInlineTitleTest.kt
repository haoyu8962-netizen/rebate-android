package com.couli.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** BadgedTitle 旧调用必须为新增图标预留宽度；标题可省略，平台中文名不可被行内占位截掉。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h914dp-xxhdpi", fontScale = 2f)
class Fx05aPlatformInlineTitleTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun inlineBadgeKeepsItsFullNameAtTwoHundredPercentFontScale() {
        rule.setContent {
            CouliTheme {
                BadgedTitle(platformName = "拼多多", title = "全脂纯牛奶 250ml × 24 盒 整箱装", modifier = Modifier)
            }
        }
        val nodes = rule.onAllNodes(hasText("拼多多", substring = false), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("必须检查徽标里的真实文字", 1, nodes.size)
        val node = nodes.single()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
        assertEquals(1, results.size)
        val result = results.single()
        assertEquals(1, result.lineCount)
        assertFalse(result.isLineEllipsized(0))
        assertFalse(result.didOverflowHeight)
        val textWidth = result.getLineRight(0) - result.getLineLeft(0)
        assertTrue("新增 14dp 图标后行内占位必须增加，不能压缩中文名", textWidth <= node.boundsInRoot.width + 1)
    }
}
