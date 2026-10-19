package com.couli.core.designsystem

import android.app.Dialog
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02NativeInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun navBarIconAndSystemBackUseSameCallbackExactlyOnce() {
        var backs = 0
        rule.setContent { CouliTheme { NavBar(title = "钱包", backLabel = "返回", onBack = { backs++ }) } }
        rule
            .onNodeWithContentDescription("返回")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle {
            assertEquals(1, backs)
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.runOnIdle { assertEquals(2, backs) }
    }

    @Test
    fun dialogUsesNativeWindowAndBackDismissesWithoutConfirming() {
        var open by mutableStateOf(true)
        var confirms = 0
        var dismisses = 0
        rule.setContent {
            CouliTheme {
                if (open) {
                    CouliDialog(
                        title = "协议确认",
                        confirmText = "同意",
                        onConfirm = { confirms++ },
                        onDismissRequest = {
                            dismisses++
                            open = false
                        },
                    )
                }
            }
        }
        rule.onNodeWithText("协议确认").assertIsDisplayed()
        pressNativeDialogBack()
        rule.onNodeWithText("协议确认").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(1, dismisses)
            assertEquals(0, confirms)
        }
    }

    @Test
    fun normalSheetSwipeDismisses() = sheetCase(protected = false, closeWithIcon = false)

    @Test
    fun protectedSheetRejectsSwipeButKeepsSystemBack() = sheetCase(protected = true, closeWithIcon = false)

    @Test
    fun protectedSheetKeepsExplicitClose() = sheetCase(protected = true, closeWithIcon = true)

    private fun sheetCase(
        protected: Boolean,
        closeWithIcon: Boolean,
    ) {
        var open by mutableStateOf(true)
        var dismisses = 0
        rule.setContent {
            CouliTheme {
                if (open) {
                    CouliSheet(
                        title = "二次验证",
                        closeLabel = "关闭",
                        onDismissRequest = {
                            dismisses++
                            open = false
                        },
                        dismissOnSwipe = !protected,
                        dismissOnClickOutside = !protected,
                    ) { Text("验证码", modifier = Modifier.testTag("sheet-body")) }
                }
            }
        }
        rule.onNodeWithTag("sheet-body").assertIsDisplayed()
        assertNotNull("必须使用系统模态窗口", ShadowDialog.getLatestDialog())
        if (closeWithIcon) {
            rule.onNodeWithContentDescription("关闭").performClick()
        } else {
            val travel = with(rule.density) { 600.dp.toPx() }
            rule.onNodeWithText("二次验证").performTouchInput { swipe(center, Offset(center.x, center.y + travel)) }
            rule.waitForIdle()
            if (protected) {
                rule.onNodeWithTag("sheet-body").assertIsDisplayed()
                rule.runOnIdle { assertEquals(0, dismisses) }
                pressNativeDialogBack()
            }
        }
        rule.onNodeWithTag("sheet-body").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, dismisses) }
    }

    @Test
    fun normalSheetScrimClosesButProtectedSheetScrimDoesNot() {
        var protected by mutableStateOf(false)
        var open by mutableStateOf(true)
        var dismisses = 0
        rule.setContent {
            CouliTheme {
                if (open) {
                    CouliSheet(
                        title = "授权",
                        onDismissRequest = {
                            dismisses++
                            open = false
                        },
                        dismissOnSwipe = !protected,
                        dismissOnClickOutside = !protected,
                    ) { Text("半屏正文") }
                }
            }
        }
        rule.onNodeWithText("半屏正文").assertIsDisplayed()
        tapNativeScrim()
        rule.onNodeWithText("半屏正文").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(1, dismisses)
            protected = true
            open = true
        }
        rule.onNodeWithText("半屏正文").assertIsDisplayed()
        tapNativeScrim()
        rule.onNodeWithText("半屏正文").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, dismisses) }
        pressNativeDialogBack()
        rule.onNodeWithText("半屏正文").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, dismisses) }
    }

    private fun tapNativeScrim() {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        rule.runOnIdle {
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now, action, 8f, 100f, 0)
                requireNotNull(dialog.window).decorView.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        rule.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun pressNativeDialogBack() {
        val dialog: Dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "不能用普通 Box 伪造系统 Dialog" }
        assertTrue(dialog.isShowing)
        rule.runOnIdle { dialog.onBackPressed() }
        rule.waitForIdle()
    }
}
