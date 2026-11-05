package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.search.SearchTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualR1PriceDashTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun priceDashAt200PercentIsCenteredOnBothInputFramesWithin4Dp() {
        host.launch("price", fontScale = 2f)
        val min = inputFrame(SearchTags.PRICE_MIN)
        val max = inputFrame(SearchTags.PRICE_MAX)
        val node = rule.onNodeWithTag(SearchTags.PRICE_MIN).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        assertEquals("两输入框本身对齐", min.center.y, max.center.y, d)
        val top =
            rule
                .onNodeWithText("最低价", useUnmergedTree = true)
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .searchR1Rect()
                .top
        val pixels = rule.searchPixels(node)
        try {
            // 在两输入框之间取真实横杠像素，不依赖它有 Text 语义，清除语义或改为绘制后仍可验证。
            val rows =
                (top.toInt() until min.bottom.toInt()).filter { y ->
                    ((min.right + 2 * d).toInt() until (max.left - 2 * d).toInt()).any { x -> pixels.getPixel(x, y) == 0xFF655E57.toInt() }
                }
            assertTrue("装饰横杠必须仍然可见", rows.isNotEmpty())
            val center = (rows.first() + rows.last() + 1) / 2f
            // 像素墨迹中心与行盒中心有字体度量差；容差4dp，不能容纳固定22dp偏移造成的错位。
            listOf(min, max).forEach { frame -> assertEquals("横杠与输入框垂直居中，容差4dp", frame.center.y, center, 4 * d) }
        } finally {
            pixels.recycle()
        }
        assertEquals("开面板 / 改草稿不请求", 1, host.api.calls.size)
    }

    @Test fun decorativePriceDashHasNoSpokenNodeInEitherSemanticsTree() {
        host.launch("price", fontScale = 2f)
        val dash = textMatcher("–") or hasContentDescription("–")
        rule.onAllNodes(dash).assertCountEquals(0)
        rule.onAllNodes(dash, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, host.api.calls.size)
    }

    private fun inputFrame(tag: String): Rect {
        val input = rule.onNodeWithTag(tag).fetchSemanticsNode()
        var frame: Rect? = null
        rule.runOnUiThread {
            val center =
                input.layoutInfo.coordinates
                    .searchR1Rect()
                    .center
            frame =
                searchLayoutTree(generateSequence(input.layoutInfo) { it.parentInfo }.last())
                    .flatMap { it.getModifierInfo() }
                    .filter {
                        it.modifier.javaClass.simpleName
                            .contains("Border")
                    }.map { checkNotNull(it.coordinates).searchR1Rect() }
                    .filter { it.contains(center) }
                    .minByOrNull { it.width * it.height }
        }
        return checkNotNull(frame) { "必须找到输入框的描边外框，不能拿文字内容盒代替" }
    }
}
