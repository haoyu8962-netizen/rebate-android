package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 大屏快照（brief §2 第 14 条；03 §10.4）：窗口本身就是 840dp（类级 qualifiers），页面铺满窗口，不再在 390dp 窗口里套 840dp 的盒子（R3-01：那样会被父窗口约束成
 * 390dp，截图与 01-normal 逐字节相同）。断言视口宽 840dp、内容区限宽 600dp、左右留白相等（居中），再截 30-large（基准在 src/test/snapshots，另拷到
 * NATIVE/APP-10/shots/android/）。数据是契约 fixture `normal`。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w840dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentLargeScreenSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeScreenContentIs600dpCentered() {
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        composeRule.runOnIdle { kit.play(c, "normal") }
        composeRule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides AgentUiKit.images) {
                CouliTheme {
                    Box(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        AgentScreen(c, kit.ui())
                    }
                }
            }
        }
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }

        val density = composeRule.density.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val page = composeRule.onNodeWithTag(AgentTags.PAGE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val viewportDp = root.width / density
        val contentDp = page.width / density
        val leftDp = (page.left - root.left) / density
        val rightDp = (root.right - page.right) / density
        assertEquals("视口宽度应为 840dp", WINDOW_DP, viewportDp, EPS)
        assertEquals("内容区应限宽 600dp", CONTENT_DP, contentDp, EPS)
        assertEquals("左右留白应相等（居中）", leftDp, rightDp, EPS)
        assertEquals("左留白应为 (840-600)/2", (WINDOW_DP - CONTENT_DP) / 2, leftDp, EPS)

        composeRule.onRoot().captureRoboImage("src/test/snapshots/30-large.png")
    }

    private companion object {
        const val ROUNDS = 4
        const val WINDOW_DP = 840f
        const val CONTENT_DP = 600f
        const val EPS = 0.5f
    }
}
