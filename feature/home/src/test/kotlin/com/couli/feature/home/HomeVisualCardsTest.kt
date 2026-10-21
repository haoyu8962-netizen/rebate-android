package com.couli.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A8/A10–A12：窄屏必须整组回退；横滑卡精简可见文字但保留完整读屏名。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h6000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeVisualCardsTest {
    @get:Rule
    val rule = createComposeRule()
    private val driver by lazy { HomeVisualTestDriver(rule) }

    @After
    fun close() = driver.close()

    @Test
    fun gridAtNarrowFont200FallsBackAsOneColumn() {
        driver.show("grid", scale = 2f)
        assertColumn("grid", 2)
    }

    @Test
    fun feedAtNarrowFont200FallsBackAsOneColumn() {
        driver.show("feed", scale = 2f)
        assertColumn("feed", 4)
    }

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xhdpi")
    fun normalGridKeepsTwoColumns() {
        driver.show("grid")
        assertTwoColumns("grid")
    }

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xhdpi")
    fun normalFeedKeepsTwoColumns() {
        driver.show("feed")
        assertTwoColumns("feed")
    }

    @Test
    fun scrollHasNoVisibleTitleOrPlatformButItsSpokenNameKeepsBothAndAmounts() {
        driver.show("scroll")
        val card =
            driver.controller.state.value.cards
                .single() as HomeCardUi.ProductScroll
        val product = card.items.first().data
        val tag = HomeTags.tap("scroll", 1)
        val inTile = hasAnyAncestor(hasTestTag(tag))
        rule.onAllNodes(hasText(product.title) and inTile, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasText(product.platformName) and inTile, useUnmergedTree = true).assertCountEquals(0)
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val spoken = node.config[SemanticsProperties.ContentDescription].joinToString()
        listOf(product.platformName, product.title, checkNotNull(product.price).mainSpoken, product.rebate.spoken).forEach {
            assertTrue("读屏缺少 $it：$spoken", spoken.contains(it))
        }
        assertEquals("推广", card.items.first().adLabel)
        rule.onAllNodes(hasText("推广") and inTile, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun footerContainsTwoBasisLinesAndNoInfoAction() {
        driver.show("grid")
        val lines = driver.controller.state.value.disclaimers
        assertEquals(2, lines.size)
        val list = rule.onNodeWithTag(HomeTags.LIST)
        list.performScrollToNode(hasTestTag(HomeTags.DISCLAIMER))
        val inFooter = hasAnyAncestor(hasTestTag(HomeTags.DISCLAIMER))
        lines.forEach { rule.onAllNodes(hasText(it) and inFooter, useUnmergedTree = true).assertCountEquals(1) }
        rule.onAllNodes(hasClickAction() and inFooter).assertCountEquals(0)
        rule
            .onNodeWithTag(HomeTags.DISCLAIMER)
            .assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        // 即便 i 的 contentDescription 为 null，也不能漏检：文字左侧不得绘出图标。
        val footer = rule.onNodeWithTag(HomeTags.DISCLAIMER).fetchSemanticsNode()
        val footerBounds = footer.boundsInRoot
        val textBounds = rule.onNode(hasText(lines.first()) and inFooter, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val view = (footer.root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            rule.runOnUiThread { view.draw(Canvas(bitmap)) }
            val startX = footerBounds.left.toInt()
            val endX = textBounds.left.toInt().coerceAtMost(bitmap.width)
            val startY = textBounds.top.toInt().coerceAtLeast(0)
            val endY = textBounds.bottom.toInt().coerceAtMost(bitmap.height)
            assertTrue("页脚左侧取样区不能为空", endX > startX && endY > startY)
            for (y in startY until endY) {
                for (x in startX until endX) {
                    assertEquals("口径页脚文字左侧仍有图标 ($x,$y)", bitmap.getPixel(startX, y), bitmap.getPixel(x, y))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColumn(
        section: String,
        count: Int,
    ) {
        val bounds = (1..count).map { rule.onNodeWithTag(HomeTags.tap(section, it)).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (first, second) ->
            assertEquals("整组同列", first.left, second.left, 1f)
            assertEquals("整组等宽", first.width, second.width, 1f)
            assertTrue("后卡必须在前卡下方", second.top >= first.bottom)
            assertFalse("不能裁到零宽", second.width <= 0f)
        }
    }

    private fun assertTwoColumns(section: String) {
        val first = rule.onNodeWithTag(HomeTags.tap(section, 1)).fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag(HomeTags.tap(section, 2)).fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left >= first.right)
    }
}
