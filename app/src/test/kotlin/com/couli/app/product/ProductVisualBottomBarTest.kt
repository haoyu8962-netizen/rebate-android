package com.couli.app.product

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.product.ProductTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D9 / D14：真实测量、文字排版和 View.draw，避免字号阈值假判断。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualBottomBarTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun aiEntryHas24DpIconAbove12SpLabelAnd48DpHitArea() {
        host.launch()
        val entry =
            rule
                .onNodeWithTag(ProductTags.ASK_AI)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .fetchSemanticsNode()
        val text = rule.onNodeWithText("问问 AI", useUnmergedTree = true)
        val label = text.fetchSemanticsNode()
        val density = entry.layoutInfo.density.density
        assertTrue(entry.boundsInRoot.width >= 48 * density && entry.boundsInRoot.height >= 48 * density)
        assertEquals(
            12.sp,
            text
                .textLayout()
                .layoutInput.style.fontSize,
        )
        assertTrue("图标必须在字上面留出 24dp", label.boundsInRoot.top - entry.boundsInRoot.top >= 24 * density)
        val pixels = rule.productPixels()
        try {
            val iconLeft = (entry.boundsInRoot.center.x - 12 * density).toInt()
            val iconTop = entry.boundsInRoot.top.toInt()
            val size = (24 * density).toInt()
            val ink =
                pixels.inkPixels(
                    Rect(
                        iconLeft.toFloat(),
                        iconTop.toFloat(),
                        (iconLeft + size).toFloat(),
                        (iconTop + size).toFloat(),
                    ),
                    host.surface.toArgb(),
                )
            assertTrue("24dp 区域需要真的画出图标", ink > size)
        } finally {
            pixels.recycle()
        }
        rule.onNodeWithTag(ProductTags.ASK_AI).performClick()
        assertEquals(1, host.asks)
        assertTrue(host.buys.isEmpty())
    }

    @Test fun aiFlagChangesImmediately() {
        host.launch()
        rule.runOnIdle { host.aiVisible.value = false }
        rule.onNodeWithTag(ProductTags.ASK_AI).assertDoesNotExist()
        rule.runOnIdle { host.aiVisible.value = true }
        rule.onNodeWithTag(ProductTags.ASK_AI).performClick()
        assertEquals(1, host.asks)
    }

    @Test fun nullAiCallbackHasNoClickablePlaceholder() {
        host.launch(askEnabled = false)
        rule.onNodeWithTag(ProductTags.ASK_AI).assertDoesNotExist()
    }

    @Test fun normalSizeKeepsAiAndPurchaseOnOneRowAnd16DpBottomPadding() {
        host.launch("jd_no_coupon_single")
        val ai = rule.onNodeWithTag(ProductTags.ASK_AI).fetchSemanticsNode().boundsInRoot
        val buy = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode()
        assertTrue(ai.right <= buy.boundsInRoot.left)
        assertTrue(ai.top < buy.boundsInRoot.bottom && buy.boundsInRoot.top < ai.bottom)
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        assertEquals("安全区为 0 时底内边距 16dp", 16f, (root.boundsInRoot.bottom - buy.boundsInRoot.bottom) / density, 1f)
    }

    @Test
    fun navigationInset32UsesMaxInsteadOfAddingAnother16() {
        host.launch()
        host.navigationInset(32)
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val buy = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode().boundsInRoot
        assertEquals("max(32,16)=32，不应重复加安全区", 32f, (root.boundsInRoot.bottom - buy.bottom) / density, 1f)
    }

    @Test fun font200At390UsesTwoRowsWithoutClippingButton() = assertTwoRows()

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun font200At320UsesTwoRowsWithoutClippingButton() = assertTwoRows()

    private fun assertTwoRows() {
        host.launch("jd_no_coupon_single", fontScale = 2f)
        val ai = rule.onNodeWithTag(ProductTags.ASK_AI).fetchSemanticsNode().boundsInRoot
        val buy = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        assertTrue("G-D13 放不下时入口上行、购买按钮下行", ai.bottom <= buy.top)
        assertEquals(16f, buy.left / density, 1f)
        assertEquals(16f, (root.boundsInRoot.right - buy.right) / density, 1f)
        assertTrue(buy.height >= 48 * density)
        // btn.buy 字典原文随锁 a89c1ec3a 由「去购买」改为「购买」（FU-X-CTA-KEY-DRIFT，#24）
        rule.onNodeWithText("购买 · 预估返 ¥2.34", useUnmergedTree = true).assertNoTextOverflow()
        rule.onNodeWithTag(ProductTags.BUY).performClick()
        assertEquals(1, host.buys.size)
        assertEquals(ProductVisualFixtures.card("jd_no_coupon_single"), host.buys.single().first)
    }

    @Test
    @Config(qualifiers = "w840dp-h1400dp-xhdpi")
    fun wideBarSurfaceReachesBothEdgesWhileControlsAndContentStayCenteredAt600() {
        host.launch()
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val ai = rule.onNodeWithTag(ProductTags.ASK_AI).fetchSemanticsNode()
        val buy = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode()
        val controls = outerBounds(commonLayout(ai.layoutInfo, buy.layoutInfo))
        assertEquals(600f, controls.width / density, 1f)
        assertEquals(root.boundsInRoot.center.x, controls.center.x, density)
        val left = (root.boundsInRoot.width / density - 600f) / 2f
        assertEquals(left + 16, ai.boundsInRoot.left / density, 1f)
        assertEquals(left + 584, buy.boundsInRoot.right / density, 1f)
        val hero = rule.onNode(SemanticsMatcher("头图") { it.readableText().startsWith("商品图片，") }).fetchSemanticsNode()
        assertEquals(left + 16, hero.boundsInRoot.left / density, 1f)
        assertEquals(left + 584, hero.boundsInRoot.right / density, 1f)
        val pixels = rule.productPixels()
        try {
            val y =
                buy.boundsInRoot.center.y
                    .toInt()
            assertEquals("左侧白底铺满", host.surface.toArgb(), pixels.getPixel((4 * density).toInt(), y))
            assertEquals("右侧白底铺满", host.surface.toArgb(), pixels.getPixel(pixels.width - (4 * density).toInt(), y))
        } finally {
            pixels.recycle()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi")
    fun longAmountsAt200PercentDoNotClipAnyDigitOrRebateEndpoint() {
        host.launch("long_amount", fontScale = 2f)
        val amounts = rule.onAllNodes(hasText("¥", substring = true), useUnmergedTree = true)
        assertTrue("压力 fixture 必须真的渲染金额", amounts.fetchSemanticsNodes().size >= 3)
        repeat(amounts.fetchSemanticsNodes().size) { amounts[it].performScrollTo().assertNoTextOverflow() }
    }
}
