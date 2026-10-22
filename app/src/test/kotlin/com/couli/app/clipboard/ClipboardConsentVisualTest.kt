package com.couli.app.clipboard

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardNotice
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A16：实际 Core 组件；受控宿主只记录回调并移除，不在测试内重画半屏。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardConsentVisualTest {
    @get:Rule val rule = createComposeRule()
    private val probe by lazy { OverlayVisualProbe(rule) }
    private var enables = 0
    private var notNows = 0
    private var surface = Color.Unspecified
    private var border = Color.Unspecified

    @Test fun bottomSheetHasLeftAlignedTitleAndEqualSideBySideButtons() {
        show()
        val root = rule.onNodeWithTag(ROOT).assertIsDisplayed().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val window = (root.root as ViewRootForTest).view
        assertTrue("用途说明宽度按底部半屏，不再是居中窄 Dialog", root.boundsInRoot.width / density >= 358f)
        assertTrue("内容靠近窗口底部", window.height - root.boundsInRoot.bottom <= 48f * density)
        val left = rule.onNodeWithTag(NOT_NOW).fetchSemanticsNode()
        val right = rule.onNodeWithTag(ENABLE).fetchSemanticsNode()
        assertEquals("两钮等宽", left.boundsInRoot.width, right.boundsInRoot.width, density)
        assertEquals("两钮同一行", left.boundsInRoot.top, right.boundsInRoot.top, density)
        assertTrue("暂不开启在左、开启在右", left.boundsInRoot.right < right.boundsInRoot.left)
        probe.assertTarget48(left)
        probe.assertTarget48(right)
        probe.assertOutline(left, surface.toArgb(), border.toArgb())
        val title = rule.onNodeWithText(ClipboardDraftTexts.text("clipboard.consent.title")).fetchSemanticsNode()
        assertEquals("标题与左按钮左对齐", left.boundsInRoot.left, title.boundsInRoot.left, density)
    }

    @Test fun notNowButtonCallsOnlyNotNowOnce() {
        show()
        rule.onNodeWithTag(NOT_NOW).performClick()
        assertDismissed()
    }

    @Test fun enableButtonCallsOnlyEnableOnce() {
        show()
        rule.onNodeWithTag(ENABLE).performClick()
        rule.waitForIdle()
        assertEquals(1, enables)
        assertEquals(0, notNows)
        rule.onNodeWithTag(ROOT).assertDoesNotExist()
    }

    @Test fun systemBackEqualsNotNow() {
        show()
        probe.back()
        assertDismissed()
    }

    @Test fun tappingScrimEqualsNotNow() {
        show()
        probe.scrim()
        assertDismissed()
    }

    @Test fun draggingSheetDownEqualsNotNow() {
        show()
        probe.swipeSheetDown(rule.onNodeWithTag(ROOT))
        assertDismissed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun font200KeepsBothActionsVisibleAndTextUnclipped() {
        show(fontScale = 2f)
        listOf(ENABLE, NOT_NOW).forEach { probe.assertTarget48(rule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode()) }
        listOf("开启", "暂不开启").forEach { probe.assertTextFits(rule.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()) }
    }

    private fun show(fontScale: Float = 1f) {
        val visible = mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                CouliTheme(darkTheme = false) {
                    surface = CouliColors.backgroundSurface
                    border = CouliColors.borderControl
                    if (visible.value) {
                        ClipboardNotice(
                            text = ClipboardDraftTexts::text,
                            onEnable = {
                                enables += 1
                                visible.value = false
                            },
                            onNotNow = {
                                notNows += 1
                                visible.value = false
                            },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ROOT).assertIsDisplayed()
    }

    private fun assertDismissed() {
        rule.waitForIdle()
        assertEquals(0, enables)
        assertEquals("G27：同暂不开启且只回调一次", 1, notNows)
        rule.onNodeWithTag(ROOT).assertDoesNotExist()
    }

    private companion object {
        const val ROOT = "clipboard_consent_notice"
        const val ENABLE = "clipboard_consent_enable"
        const val NOT_NOW = "clipboard_consent_not_now"
    }
}
