package com.couli.app.home

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.shell.BASIC_MODE_ENABLE_TAG
import com.couli.app.shell.BASIC_MODE_SETTINGS_TAG
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeLevel
import com.couli.feature.home.HomeTags
import com.couli.generated.DefaultTexts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S2-4：必须以 BASIC 身份走真实 BasicModePage，不能用游客首页代替。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w840dp-h2400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReviewBasicLayoutTest {
    @get:Rule
    val rule = createComposeRule()
    private val host by lazy { HomeVisualTestHost(rule) }

    @After
    fun close() = host.close()

    @Test
    fun basicSearchNoticeValueAndSduiShareTheCentered600DpColumn() {
        host.launch(level = HomeLevel.BASIC)
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val search = rule.onNode(hasText(HomeDraftTexts.text("search.placeholder"))).fetchSemanticsNode()
        val settings = rule.onNodeWithTag(BASIC_MODE_SETTINGS_TAG).fetchSemanticsNode()
        val notice = rule.onNode(hasText(DefaultTexts.text("privacy.basic_mode.notice").orEmpty())).fetchSemanticsNode()
        val enable = rule.onNodeWithTag(BASIC_MODE_ENABLE_TAG).fetchSemanticsNode()
        val searchRow = outerBounds(commonLayout(search.layoutInfo, settings.layoutInfo))
        val noticeCard = outerBounds(commonLayout(notice.layoutInfo, enable.layoutInfo))
        val value = rule.onNodeWithTag(HomeVisualFixtures.GUEST_CARD).fetchSemanticsNode().boundsInRoot
        val sdui = rule.onNodeWithTag(HomeTags.CARD_PREFIX + "icons").fetchSemanticsNode().boundsInRoot
        val columnLeft = (root.boundsInRoot.width - 600f * density) / 2f
        // 600 为内容列外宽，卡片/提示区共同左右留 16；搜索整行包括搜索按钮和设置按钮。
        assertEdges("搜索整行", columnLeft, columnLeft + 600f * density, searchRow, density)
        listOf("提示区" to noticeCard, "价值卡" to value, "SDUI" to sdui).forEach { (name, bounds) ->
            assertEdges(name, columnLeft + 16f * density, columnLeft + 584f * density, bounds, density)
            assertEquals("$name 与价值卡左边界", value.left, bounds.left, density)
            assertEquals("$name 与价值卡右边界", value.right, bounds.right, density)
        }
        assertTrue(host.port.pageCalls.isNotEmpty() && host.port.itemCalls.isNotEmpty())
        assertTrue(host.port.pageCalls.all { it.anonymous && it.expectedAccount == null })
        assertTrue(host.port.itemCalls.all { it.second.anonymous && it.second.expectedAccount == null })
    }

    private fun commonLayout(
        first: LayoutInfo,
        second: LayoutInfo,
    ): LayoutInfo {
        val ancestors = generateSequence(first) { it.parentInfo }.toList()
        return generateSequence(second) { it.parentInfo }.first { candidate -> ancestors.any { it === candidate } }
    }

    // LayoutInfo.coordinates 是扣掉 padding 的内层；取最外层 modifier 坐标比较实际容器边界。
    private fun outerBounds(layout: LayoutInfo): Rect =
        (layout.getModifierInfo().firstOrNull()?.coordinates ?: layout.coordinates).boundsInRoot()

    private fun assertEdges(
        name: String,
        left: Float,
        right: Float,
        bounds: Rect,
        tolerance: Float,
    ) {
        assertEquals("$name 左边界", left, bounds.left, tolerance)
        assertEquals("$name 右边界", right, bounds.right, tolerance)
    }
}
