package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⑥ 共享商品卡的独立版式测试，不声称已经覆盖未存在的 parseInput 素材链路。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardMaterialVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun materialLineIsBelowRealPriceAndAboveAiAction() {
        driver.host.overlay = { ClipboardMaterialVisualFixture.Sheet() }
        driver.launch()
        val inCard = hasAnyAncestor(hasTestTag(ClipboardMaterialVisualFixture.TAG))
        val price =
            rule
                .onNode(hasText("¥13.45") and inCard, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val line =
            rule
                .onNode(hasText(ClipboardMaterialVisualFixture.line) and inCard, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val action =
            rule
                .onNode(
                    hasText(ClipboardMaterialVisualFixture.ACTION) and inCard,
                ).assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("⑥ 真价格 → 素材参考条件 → AI 操作", price.bottom <= line.top && line.bottom <= action.top)
        assertTrue("AI 操作位于右侧", action.center.x > line.center.x)
        assertEquals("¥13.45", ClipboardMaterialVisualFixture.data().price?.mainAmount)
    }

    @Test fun aiButtonCallsItsOwnViewCallbackOnce() {
        var calls = 0
        driver.host.overlay = { ClipboardMaterialVisualFixture.Sheet(onAskAi = { calls += 1 }) }
        driver.launch()
        rule.onNode(hasText(ClipboardMaterialVisualFixture.ACTION)).performTouchInput { click() }
        driver.host.idle()
        assertEquals(1, calls)
    }
}
