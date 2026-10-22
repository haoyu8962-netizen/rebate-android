package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** O1/O4–O8：视觉高度、命中扩展、相邻区域和真实查询分别检查。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualHeaderTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun titleIs22SemiboldAndTopRightFindEntryUsesDictionary() {
        host.launch(OrderListVisualFixtures.one())
        val title = rule.onNodeWithText("订单", useUnmergedTree = true)
        val style = title.textLayout().layoutInput.style
        assertEquals(22f, style.fontSize.value, .01f)
        assertEquals(600, style.fontWeight?.weight)
        assertTrue(title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        val entry = rule.onNodeWithTag(OrderTags.FIND_ENTRY).fetchSemanticsNode()
        assertTrue(entry.boundsInRoot.left >= title.fetchSemanticsNode().boundsInRoot.right)
        entry.assertTouch48()
    }

    @Test fun titleVisualRowIs44WithoutBeingRaisedByFindTarget() {
        host.launch(OrderListVisualFixtures.one())
        val title = rule.onNodeWithText("订单", useUnmergedTree = true).fetchSemanticsNode()
        val entry = rule.onNodeWithTag(OrderTags.FIND_ENTRY).fetchSemanticsNode()
        val row = outerBounds(commonLayout(title.layoutInfo, entry.layoutInfo))
        assertEquals("默认标题视觉行是44dp；48dp命中不能撑高整行", 44f, row.height / title.layoutInfo.density.density, 1f)
        entry.assertTouch48()
    }

    @Test fun findTargetHas48HitWithoutOverlapAndBothVerticalEdgesNavigate() {
        // 留出真实顶部外壳空隙，不能让根节点裁掉向上扩展的命中区。
        host.launch(OrderListVisualFixtures.one(), shell = true)
        val entry = rule.onNodeWithTag(OrderTags.FIND_ENTRY).fetchSemanticsNode()
        entry.assertTouch48()
        val touch = entry.touchBoundsInRoot
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.id != entry.id }.forEach {
            assertFalse("标题入口扩展区不能覆盖其他控件", touch.overlaps(it.touchBoundsInRoot))
        }
        val d = entry.layoutInfo.density.density
        val origin =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.topLeft
        listOf(touch.top + d, touch.bottom - d).forEach { y ->
            rule.onRoot().performTouchInput { click(Offset(touch.center.x, y) - origin) }
        }
        rule.waitForIdle()
        assertEquals(List(2) { "FindOrder" to JsonObject(emptyMap()) }, host.opened)
        assertEquals("标题入口不能新增请求", 1, host.api.calls.size)
    }

    @Test fun scopeTabVisual44Has48HitAndSelectedRoleWithoutExtraFetch() {
        host.launch(OrderListVisualFixtures.one())
        val tab = rule.onNodeWithTag(OrderTags.TAB_SELF).assertIsSelected().fetchSemanticsNode()
        assertEquals(Role.Tab, tab.config[SemanticsProperties.Role])
        tab.assertTouch48()
        assertEquals("选中块视觉高，不含容器上下4dp", 44f, tab.boundsInRoot.height / tab.layoutInfo.density.density, 2f)
        rule.onNodeWithTag(OrderTags.TAB_SELF).performClick()
        assertEquals(1, host.api.calls.size)
        rule.onNodeWithTag(OrderTags.TAB_SHARE).performClick()
        assertEquals("share", host.model.controller.state.value.scope)
        assertEquals(2, host.api.calls.size)
    }

    @Test fun searchVisual44And17SpPlaceholderDoesNotSearchEachKeystroke() {
        host.launch(OrderListVisualFixtures.one())
        val placeholder = rule.onNodeWithText("搜订单号或商品名", useUnmergedTree = true)
        assertEquals(
            17f,
            placeholder
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        val node = placeholder.fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val box = generateSequence(node.layoutInfo) { it.parentInfo }.map(::outerBounds).first { it.width >= 358 * d - 1 }
        assertEquals("搜索框视觉高", 44f, box.height / d, 2f)
        rule.onNodeWithTag(OrderTags.SEARCH).performTextInput("纯牛奶")
        assertEquals(1, host.api.calls.size)
        rule.onNodeWithTag(OrderTags.SEARCH).performImeAction()
        assertEquals(2, host.api.calls.size)
        assertEquals("纯牛奶", host.model.controller.state.value.conditions.q)
    }

    @Test fun groupVisualRowIs32AndUnselectedHasBorder() {
        host.launch(OrderListVisualFixtures.one())
        val all = rule.onNodeWithTag(OrderTags.GROUP_PREFIX + "all").fetchSemanticsNode()
        val estimating = rule.onNodeWithTag(OrderTags.GROUP_PREFIX + "estimating").fetchSemanticsNode()
        val row = outerBounds(commonLayout(all.layoutInfo, estimating.layoutInfo))
        val d = all.layoutInfo.density.density
        assertEquals("48dp 命中区不能把胶囊视觉行撑高", 32f, row.height / d, 2f)
        rule.withOrderPixels(all) { bitmap ->
            assertTrue("未选胶囊必须有 borderSubtle 描边", bitmap.colorCount(estimating.boundsInRoot, host.border.toArgb()) > 30 * d)
            val selectedRows =
                (all.touchBoundsInRoot.top.toInt() until all.touchBoundsInRoot.bottom.toInt()).count { y ->
                    bitmap.getPixel(
                        all.boundsInRoot.center.x
                            .toInt(),
                        y,
                    ) == host.subtle.toArgb()
                }
            assertTrue("选中胶囊必须有浅强调底", selectedRows > 10 * d)
        }
    }

    @Test fun dropdownVisual32HasBorderAnd16DpArrow() {
        host.launch(OrderListVisualFixtures.one())
        val filter = rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).fetchSemanticsNode()
        val arrow = rule.onNodeWithTag(OrderTags.FILTER_PLATFORM + "_arrow", useUnmergedTree = true).fetchSemanticsNode()
        val d = filter.layoutInfo.density.density
        assertEquals(32f, filter.boundsInRoot.height / d, 2f)
        assertEquals(16f, arrow.boundsInRoot.width / d, .5f)
        assertEquals(16f, arrow.boundsInRoot.height / d, .5f)
        rule.withOrderPixels(filter) { bitmap ->
            assertTrue("平台必须是描边胶囊", bitmap.colorCount(filter.boundsInRoot, host.border.toArgb()) > 30 * d)
        }
        filter.assertTouch48()
    }

    @Test fun groupAndFilterTargetsNeverOverlapAndBothVerticalEdgesSelectSameGroup() {
        host.launch(OrderListVisualFixtures.one())
        val tags =
            listOf("all", "estimating", "credited", "no_rebate").map { OrderTags.GROUP_PREFIX + it } +
                listOf(OrderTags.FILTER_PLATFORM, OrderTags.FILTER_MONTH, OrderTags.MASK)
        val nodes = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode() }
        nodes.forEach { it.assertTouch48() }
        nodes.forEachIndexed { i, first ->
            nodes.drop(i + 1).forEach { second ->
                assertFalse("${tags[i]} 的命中区与其他控件重叠", first.touchBoundsInRoot.overlaps(second.touchBoundsInRoot))
            }
        }
        val group = rule.onNodeWithTag(OrderTags.GROUP_PREFIX + "estimating").fetchSemanticsNode()
        val bounds = group.touchBoundsInRoot
        val d = group.layoutInfo.density.density
        listOf(bounds.top + d, bounds.bottom - d).forEach { y ->
            rule.onNodeWithTag(OrderTags.GROUP_PREFIX + "all").performClick()
            val before = host.api.calls.size
            rule.touch(Offset(bounds.center.x, y))
            assertEquals("estimating", host.model.controller.state.value.conditions.group)
            assertEquals(before + 1, host.api.calls.size)
        }
    }
}
