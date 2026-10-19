package com.couli.app.catalog

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 必须打开真实 Dialog / Popup 根，静态 catalog-sample 的大字号不能替代真实窗口。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi", fontScale = 1f)
class Ds02CatalogRealContainerFontTest(
    private val component: String,
    private val openLabel: String,
    private val dark: Boolean,
    private val fontScale: Float,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun selectedFontScaleReachesEveryTextInsideTheRealWindow() {
        assertEquals("系统字号固定 100%，不能借系统设置让目录测试误过", 1f, rule.density.fontScale, 0.001f)
        rule.setContent { CouliTheme { ComponentCatalog(initial = CatalogPage.INDEX, onClose = {}) } }
        rule.onNodeWithTag("catalog-index").performScrollToNode(hasTestTag("catalog-entry/$component"))
        rule.onNodeWithTag("catalog-entry/$component").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(if (dark) "catalog-theme-dark" else "catalog-theme-light").performClick()
        rule.onNodeWithTag(if (fontScale == 2f) "catalog-font-200" else "catalog-font-100").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(openLabel).performClick()
        rule.waitForIdle()
        val window = isDialog() or isPopup()
        rule.onNode(window).assertIsDisplayed()
        val texts =
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(window),
                useUnmergedTree = true,
            )
        val count = texts.fetchSemanticsNodes().size
        assertTrue("$openLabel 的真实窗口里必须有文字，不能只验证静态样例", count > 0)
        repeat(count) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            texts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals(
                    "$openLabel / dark=$dark / ${layout.layoutInput.text} 未读取目录字号",
                    fontScale,
                    layout.layoutInput.density.fontScale,
                    0.001f,
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/{1}/dark={2}/font={3}")
        fun parameters(): List<Array<Any>> =
            listOf(
                "Dialog" to "打开真实对话框",
                "AlertDialog" to "打开系统确认框",
                "Sheet" to "打开普通半屏",
                "Sheet" to "打开保护型半屏",
                "ActionMenu" to "打开底部菜单",
                "ActionMenu" to "打开锚点菜单",
            ).flatMap { (component, label) ->
                listOf(false, true).flatMap { dark -> listOf(1f, 2f).map { arrayOf(component, label, dark, it) } }
            }
    }
}
