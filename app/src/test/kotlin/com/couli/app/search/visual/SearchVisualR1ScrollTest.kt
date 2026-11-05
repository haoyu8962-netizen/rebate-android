package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.core.network.percentEncode
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
class SearchVisualR1ScrollTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualR1Host(rule) }

    @After fun close() = host.close()

    @Test fun longPriceFiltersStayOnOneLineAt320And200Percent() {
        launchLongPrice()
        val nodes = listOf(SearchTags.COUPON, SearchTags.PRICE).map { rule.onNodeWithTag(it).fetchSemanticsNode() }
        val ordered =
            nodes.sortedBy {
                it.layoutInfo.coordinates
                    .positionInRoot()
                    .x
            }
        assertEquals(nodes.map { it.id }, ordered.map { it.id })
        val coupon = nodes[0].layoutInfo.coordinates.searchR1Rect()
        val price = nodes[1].layoutInfo.coordinates.searchR1Rect()
        val d = nodes[0].layoutInfo.density.density
        assertEquals("两胶囊同一行，中心差不超过1dp", coupon.center.y, price.center.y, d)
        assertTrue("相邻布局不重叠", coupon.right <= price.left)
        listOf("只看有券", "券后 ¥12345.67–¥99999.99").forEach {
            val text = rule.onNode(textMatcher(it), useUnmergedTree = true)
            val layout = text.textLayout()
            val node = text.fetchSemanticsNode()
            // 直接断言排版行数；不用 lineHeight.toPx() 当高度上限（非线性字号缩放下单行实高约 72px > 60px，见 T6）。
            assertEquals("胶囊文字只排一行：$it", 1, layout.lineCount)
            assertTrue("完整文字未被压窄或省略", node.size.width + 1f >= layout.multiParagraph.maxIntrinsicWidth)
        }
        assertTrue(
            rule
                .searchR1ScrollRow(SearchTags.PRICE)
                .fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange]
                .maxValue() >
                0,
        )
        assertEquals(1, host.api.calls.size)
    }

    @Test fun longPriceHorizontalScrollKeeps48TargetsAndOriginalCallbacks() {
        launchLongPrice()
        val row = rule.searchR1ScrollRow(SearchTags.PRICE)
        // 先回到起点，再真实执行 ScrollBy；不能只检查存在滚动语义。
        row.performSemanticsAction(SemanticsActions.ScrollBy) { it(-10000f, 0f) }
        rule.waitForIdle()
        val before =
            rule
                .onNodeWithTag(SearchTags.PRICE)
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .positionInRoot()
        row.performSemanticsAction(SemanticsActions.ScrollBy) { it(160f, 0f) }
        rule.waitForIdle()
        val after =
            rule
                .onNodeWithTag(SearchTags.PRICE)
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .positionInRoot()
        assertTrue("横滑确实移动了价格胶囊", after.x < before.x)
        assertEquals("横滑不改行高", before.y, after.y, 1f)
        assertEquals("纯滚动不请求", 1, host.api.calls.size)
        rule
            .onNodeWithTag(SearchTags.COUPON)
            .performScrollTo()
            .fetchSemanticsNode()
            .assertSearchR1Touch48()
        rule.onNodeWithTag(SearchTags.COUPON).performClick()
        rule
            .onNodeWithTag(SearchTags.PRICE)
            .performScrollTo()
            .fetchSemanticsNode()
            .assertSearchR1Touch48()
        rule.onNodeWithTag(SearchTags.PRICE).performClick()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).performTextReplacement("23456.78")
        rule.onNodeWithTag(SearchTags.PRICE_MAX).performTextReplacement("88888.88")
        assertEquals("打开面板和改草稿不请求", 2, host.api.calls.size)
        rule.onNodeWithTag(SearchTags.PRICE_CONFIRM).performClick()
        rule.waitForIdle()
        val base = "q=${percentEncode(SearchVisualFixtures.query("normal"))}&platform=taobao&sort=relevance"
        assertEquals(
            listOf(
                "$base&price_min_fen=1234567&price_max_fen=9999999",
                "$base&has_coupon=true&price_min_fen=1234567&price_max_fen=9999999",
                "$base&has_coupon=true&price_min_fen=2345678&price_max_fen=8888888",
            ),
            host.api.calls.map { it.query },
        )
        assertEquals(List(3) { "searchProducts" }, host.api.calls.map { it.operationId })
    }

    @Test fun initialPddIsFullyVisibleWithoutAnExtraRequest() {
        host.launch(platform = "pdd")
        assertEquals("pdd", host.model.state.value.platform)
        assertSelectedVisibleWithoutRequest(SearchTags.TAB_PREFIX + "pdd")
        assertTrue(
            host.api.calls
                .single()
                .query
                .orEmpty()
                .contains("platform=pdd"),
        )
    }

    @Test fun restoredLastSortIsFullyVisibleWithoutAnExtraRequest() {
        host.restore("rebate_desc")
        host.launch()
        assertTrue("走真实 SavedStateHandle 恢复分支", host.model.restored)
        assertEquals("rebate_desc", host.model.state.value.sort)
        assertSelectedVisibleWithoutRequest(SearchTags.SORT_PREFIX + "rebate_desc")
        assertTrue(
            host.api.calls
                .single()
                .query
                .orEmpty()
                .contains("sort=rebate_desc"),
        )
    }

    @Test fun fontGrowthKeepsSelectedPlatformVisibleWithoutRequest() {
        host.launch(platform = "pdd", scale = 1f)
        assertSelectedVisibleWithoutRequest(SearchTags.TAB_PREFIX + "pdd")
        val model = host.model
        rule.runOnIdle { host.fontScale.floatValue = 2f }
        assertSelectedVisibleWithoutRequest(SearchTags.TAB_PREFIX + "pdd")
        assertTrue("字号变化保留原页面实例", model === host.model)
    }

    @Test fun fontGrowthKeepsRestoredLastSortVisibleWithoutRequest() {
        host.restore("rebate_desc")
        host.launch(scale = 1f)
        // 100% 的长排序名已超出320宽：只在改变字号之前模拟用户把末项滚入视口。
        rule.onNodeWithTag(SearchTags.SORT_PREFIX + "rebate_desc").performScrollTo()
        assertSelectedVisibleWithoutRequest(SearchTags.SORT_PREFIX + "rebate_desc")
        rule.runOnIdle { host.fontScale.floatValue = 2f }
        assertSelectedVisibleWithoutRequest(SearchTags.SORT_PREFIX + "rebate_desc")
        assertEquals("rebate_desc", host.model.state.value.sort)
    }

    private fun launchLongPrice() {
        host.restore()
        host.saved["search.price_min_fen"] = 1234567L
        host.saved["search.price_max_fen"] = 9999999L
        host.launch()
        assertEquals(1234567L, host.model.state.value.priceMinFen)
        assertEquals(9999999L, host.model.state.value.priceMaxFen)
        assertEquals(1, host.api.calls.size)
    }

    private fun assertSelectedVisibleWithoutRequest(tag: String) {
        // 允许视图定位动画完成，但不对被测项调用 performScrollTo，防止测试替产品补滚动。
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertEquals("定位 / 重排不能追加请求", listOf("searchProducts"), host.api.calls.map { it.operationId })
        val node =
            rule
                .onNodeWithTag(tag)
                .assertIsSelected()
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val item = node.layoutInfo.coordinates.searchR1Rect()
        val row =
            node
                .searchR1ScrollParent()
                .layoutInfo.coordinates
                .searchR1Rect()
        val page =
            rule
                .onNodeWithTag(SearchTags.PAGE)
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .searchR1Rect()
        val viewport = row.intersect(page)
        assertEquals("实际布局已采用目标字号", host.fontScale.floatValue, node.layoutInfo.density.fontScale, .01f)
        assertEquals("实际页面为320dp", 320f, page.width / node.layoutInfo.density.density, 1f)
        assertTrue("选中项完整落在横滑视口内：item=$item viewport=$viewport", item.left >= viewport.left - 1f && item.right <= viewport.right + 1f)
        assertTrue("选中项宽度未被压裁", item.width <= viewport.width + 1f)
        assertTrue("选中项纵向可见", item.top >= viewport.top - 1f && item.bottom <= viewport.bottom + 1f)
    }
}
