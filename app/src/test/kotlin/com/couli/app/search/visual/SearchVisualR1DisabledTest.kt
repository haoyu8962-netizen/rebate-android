package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.feature.search.SearchTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 禁用取色沿用 CouliSelection：文字 textSecondary，胶囊 backgroundMuted，指示线 borderSubtle。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualR1DisabledTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualR1Host(rule) }

    // 禁用的 selectable 不可获焦，语义里没有 Focused 键，assertIsNotFocused 要求键存在且为 false 会误报。
    // 等价改写：Focused 只要不是 true 即视为未获焦；获焦（true）仍然失败，防获焦叠层污染取色的意图不变。
    private val notFocused =
        SemanticsMatcher("未获焦（Focused != true）") { it.config.getOrNull(SemanticsProperties.Focused) != true }

    @After fun close() = host.close()

    @Test fun limitedPlatformTextUsesDisabledColorForSelectedAndUnselectedTabs() {
        launchLimited()
        listOf("taobao" to "淘宝", "jd" to "京东", "pdd" to "拼多多").forEach { (code, label) ->
            assertDisabledText(SearchTags.TAB_PREFIX + code, label)
        }
        assertNoRequestAfterClicks(listOf("taobao", "jd", "pdd").map { SearchTags.TAB_PREFIX + it })
    }

    @Test fun limitedSortTextUsesDisabledColorForSelectedAndUnselectedItems() {
        launchLimited()
        val labels =
            listOf(
                "relevance" to "综合",
                "sales_desc" to "销量",
                "final_price_asc" to "券后价从低到高",
                "rebate_desc" to "返利从高到低",
            )
        labels.forEach { (code, label) ->
            assertDisabledText(SearchTags.SORT_PREFIX + code, label)
        }
        assertNoRequestAfterClicks(listOf("relevance", "sales_desc", "final_price_asc", "rebate_desc").map { SearchTags.SORT_PREFIX + it })
    }

    @Test fun limitedCapsuleTextUsesDisabledColorForSelectedAndUnselectedFilters() {
        launchLimited()
        assertDisabledText(SearchTags.COUPON, "只看有券")
        assertDisabledText(SearchTags.PRICE, "价格区间")
        assertNoRequestAfterClicks(listOf(SearchTags.COUPON, SearchTags.PRICE))
    }

    @Test fun limitedCapsuleBackgroundUsesDisabledColorForSelectedAndUnselectedFilters() {
        launchLimited()
        listOf(SearchTags.COUPON, SearchTags.PRICE).forEach { tag ->
            val node =
                rule
                    .onNodeWithTag(tag)
                    .assertIsNotEnabled()
                    .assert(notFocused)
                    .fetchSemanticsNode()
            val rect = node.layoutInfo.coordinates.searchR1Rect()
            val pixels = rule.searchPixels(node)
            try {
                // 高度中心、距左边4dp：避开文字、箭头、边框和圆角抗锯齿。
                val x = (rect.left + 4 * node.layoutInfo.density.density).toInt()
                assertEquals("$tag 禁用胶囊实底 = backgroundMuted", host.muted, pixels.getPixel(x, rect.center.y.toInt()))
            } finally {
                pixels.recycle()
            }
        }
        assertNoRequestAfterClicks(listOf(SearchTags.COUPON, SearchTags.PRICE))
    }

    @Test fun limitedSelectedTabUnderlineUsesDisabledBorderColor() {
        launchLimited()
        val tag = SearchTags.TAB_PREFIX + "taobao"
        val node =
            rule
                .onNodeWithTag(tag)
                .assertIsNotEnabled()
                .assert(notFocused)
                .fetchSemanticsNode()
        val rect = node.layoutInfo.coordinates.searchR1Rect()
        val pixels = rule.searchPixels(node)
        try {
            val y = (rect.bottom - node.layoutInfo.density.density).toInt()
            listOf(.25f, .5f, .75f).forEach { fraction ->
                assertEquals(
                    "禁用下划线 = borderSubtle，保留整条指示线",
                    host.subtleBorder,
                    pixels.getPixel((rect.left + rect.width * fraction).toInt(), y),
                )
            }
        } finally {
            pixels.recycle()
        }
        assertNoRequestAfterClicks(listOf(tag))
    }

    private fun launchLimited() {
        rule.mainClock.autoAdvance = false
        host.restore()
        host.saved["search.has_coupon"] = true
        host.launch(scale = 1f, limited = true)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue("真实42901阻断态", host.model.state.value.blocked)
        assertTrue("覆盖选中胶囊", host.model.state.value.hasCoupon)
        assertEquals("覆盖未选中胶囊", null, host.model.state.value.priceMinFen)
        assertEquals(1, host.api.calls.size)
    }

    private fun assertDisabledText(
        tag: String,
        label: String,
    ) {
        val control =
            rule
                .onNodeWithTag(tag)
                .assertIsNotEnabled()
                .assert(notFocused)
                .fetchSemanticsNode()
        val text = rule.onNode(textMatcher(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        assertEquals(
            "$tag 字色 = 组件库禁用文字色",
            host.secondary,
            text
                .textLayout()
                .layoutInput.style.color
                .toArgb(),
        )
        val rect =
            text
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .searchR1Rect()
        val pixels = rule.searchPixels(control)
        try {
            val ink =
                (rect.top.toInt() until rect.bottom.toInt()).sumOf { y ->
                    (rect.left.toInt() until rect.right.toInt()).count { x -> pixels.getPixel(x, y) == host.secondary }
                }
            assertTrue("$tag 实际字形也画出禁用色（避开平台图标）", ink > 0)
        } finally {
            pixels.recycle()
        }
    }

    private fun assertNoRequestAfterClicks(tags: List<String>) {
        // 先完成静态取色，再点击；不能让按压 / 获焦叠层污染颜色断言。
        tags.forEach { rule.onNodeWithTag(it).performClick() }
        rule.waitForIdle()
        assertEquals("禁用点击不发请求", 1, host.api.calls.size)
    }
}
