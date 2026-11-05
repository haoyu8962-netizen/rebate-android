package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.readableText
import com.couli.app.product.textLayout
import com.couli.feature.search.SearchTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun idleHasHeaderPasteHistoryButNoTabsSortOrFilters() {
        host.launch("history")
        rule.onNodeWithTag(SearchTags.INPUT).assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.SUBMIT).assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.PASTE_ENTRY).assertIsDisplayed()
        assertNoResultControls()
        rule.onNodeWithText("搜索发现").assertDoesNotExist()
        rule.onNodeWithText("试试这样搜").assertDoesNotExist()
        assertEquals(0, host.api.calls.size)
    }

    @Test fun platformTabsHaveDecorative20IconsChineseNamesAndTabRole() {
        host.launch()
        listOf("taobao" to "淘宝", "jd" to "京东", "pdd" to "拼多多").forEach { (code, name) ->
            val tab = rule.onNodeWithTag(SearchTags.TAB_PREFIX + code)
            tab.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            if (code == "taobao") tab.assertIsSelected()
            val node = tab.fetchSemanticsNode()
            assertEquals("图标不重复读、不读编码", name, node.readableText())
            node.assertSearchTouch48()
            rule.runOnUiThread {
                val images =
                    searchLayoutTree(node.layoutInfo).filter { layout ->
                        layout.getModifierInfo().any {
                            it.modifier.javaClass.simpleName
                                .contains("Painter")
                        }
                    }
                assertEquals("平台 Tab 必须包含一张官方图标", 1, images.size)
                val size = images.single().coordinates.size
                assertEquals(20f, size.width / node.layoutInfo.density.density, .5f)
                assertEquals(20f, size.height / node.layoutInfo.density.density, .5f)
            }
        }
    }

    @Test fun sortingIs14Over21RadioAndHas48Hit() {
        host.launch()
        val sort = rule.onNodeWithTag(SearchTags.SORT_PREFIX + "relevance")
        sort.assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        sort.fetchSemanticsNode().assertSearchTouch48()
        val text = rule.onNodeWithText("综合", useUnmergedTree = true)
        text.assertSearchFont(14f)
        assertEquals(
            21f,
            text
                .textLayout()
                .layoutInput.style.lineHeight.value,
            .1f,
        )
    }

    @Test fun filterCapsulesAre32With48HitAndCheckboxRole() {
        host.launch()
        listOf(SearchTags.COUPON, SearchTags.PRICE).forEach { tag ->
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals(Role.Checkbox, node.config[SemanticsProperties.Role])
            rule.assertSearchCapsule32(node)
        }
        rule.onNodeWithText("只看有券", useUnmergedTree = true).assertSearchFont(12f)
        val coupon = rule.onNodeWithTag(SearchTags.COUPON).fetchSemanticsNode().touchBoundsInRoot
        val price = rule.onNodeWithTag(SearchTags.PRICE).fetchSemanticsNode().touchBoundsInRoot
        assertFalse("相邻命中区不重叠", coupon.overlaps(price))
    }

    @Test fun historyUsesSeparate32Capsules14FontAndButtonRole() {
        host.launch("history")
        rule.onNodeWithText("搜索历史", useUnmergedTree = true).assertSearchFont(14f)
        SearchVisualFixtures.history.forEach { word ->
            val node = rule.onNodeWithTag(SearchTags.HISTORY_PREFIX + word).fetchSemanticsNode()
            assertEquals("历史是提交按钮，不是复选框", Role.Button, node.config[SemanticsProperties.Role])
            assertEquals(null, node.config.getOrNull(SemanticsProperties.Selected))
            rule.assertSearchCapsule32(node)
            rule.onNodeWithText(word, useUnmergedTree = true).assertSearchFont(14f)
        }
    }

    @Test fun unavailableOnlyHasBackExplanationAndPaste() {
        host.launch("disabled")
        rule.onNodeWithTag(SearchTags.UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText("该平台暂不提供搜索，可以粘贴商品链接查返利").assertIsDisplayed()
        listOf(SearchTags.INPUT, SearchTags.SUBMIT, SearchTags.CLEAR, SearchTags.RETRY, SearchTags.HISTORY_CLEAR).forEach {
            rule.onNodeWithTag(it).assertDoesNotExist()
        }
        assertNoResultControls()
        assertEquals("只留返回和粘贴两个可点入口", 2, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        rule.onNodeWithText("粘贴链接查返利").performClick()
        assertEquals(1, host.pastes)
        assertEquals(0, host.api.calls.size)
    }

    @Test fun disabledPlatformDisappearsButOthersRemain() {
        host.launch("one_off")
        rule.onNodeWithTag(SearchTags.TAB_PREFIX + "jd").assertDoesNotExist()
        rule.onNodeWithTag(SearchTags.TAB_PREFIX + "taobao").assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.TAB_PREFIX + "pdd").assertIsDisplayed()
        assertEquals(1, host.api.calls.size)
    }

    private fun assertNoResultControls() {
        listOf("taobao", "jd", "pdd").forEach { rule.onNodeWithTag(SearchTags.TAB_PREFIX + it).assertDoesNotExist() }
        listOf("relevance", "sales_desc", "final_price_asc", "rebate_desc").forEach {
            assertTrue(rule.onAllNodesWithTag(SearchTags.SORT_PREFIX + it).fetchSemanticsNodes().isEmpty())
        }
        rule.onNodeWithTag(SearchTags.COUPON).assertDoesNotExist()
        rule.onNodeWithTag(SearchTags.PRICE).assertDoesNotExist()
    }
}
