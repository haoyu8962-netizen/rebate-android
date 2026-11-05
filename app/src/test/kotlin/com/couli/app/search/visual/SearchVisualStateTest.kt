package com.couli.app.search.visual

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.ErrorStateFocus
import com.couli.feature.search.SearchTags
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun pageErrorKeepsTraceAnnouncesImmediatelyOnceThenFocusesSolidRetry() {
        rule.mainClock.autoAdvance = false
        host.launch("error")
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("淘宝搜索暂不可用，请稍后再试（000123）").assertIsDisplayed()
        assertEquals(listOf("淘宝搜索暂不可用，请稍后再试（000123）"), host.spoken)
        val retry = rule.onNodeWithTag(SearchTags.RETRY).fetchSemanticsNode()
        val pixels = rule.searchPixels(retry)
        val d = retry.layoutInfo.density.density
        try {
            assertEquals(
                "实心品牌色主按钮",
                0xFFC44820.toInt(),
                pixels.getPixel(
                    retry.boundsInRoot.center.x
                        .toInt(),
                    (
                        retry.boundsInRoot.top +
                            4 * d
                    ).toInt(),
                ),
            )
        } finally {
            pixels.recycle()
        }
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        rule.onNodeWithTag(SearchTags.RETRY).assertIsFocused()
        retry.assertSearchTouch48()
        assertTrue("主重试至少160宽", retry.boundsInRoot.width / retry.layoutInfo.density.density >= 159f)
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        assertEquals("同一错误不重复播报", 1, host.spoken.size)
    }

    @Test fun limitedDisablesEverySearchEntryAndCannotSend() {
        host.launch("limited")
        val tags =
            listOf(SearchTags.SUBMIT, SearchTags.COUPON, SearchTags.PRICE, SearchTags.RETRY) +
                listOf("taobao", "jd", "pdd").map { SearchTags.TAB_PREFIX + it } +
                listOf("relevance", "sales_desc", "final_price_asc", "rebate_desc").map { SearchTags.SORT_PREFIX + it }
        tags.forEach { rule.onNodeWithTag(it).assertIsNotEnabled().performClick() }
        assertEquals(1, host.api.calls.size)
        rule.runOnIdle {
            host.controller.retry()
            host.controller.selectTab("pdd")
            host.controller.selectSort("sales_desc")
            host.controller.loadMore()
        }
        assertEquals("执行层也拦截", 1, host.api.calls.size)
    }

    @Test fun limitedRestoresAfterRetryAfterWithoutAutomaticRequest() {
        host.launch("limited")
        rule.onNodeWithTag(SearchTags.RETRY).assertIsNotEnabled()
        rule.runOnUiThread {
            host.now += 5000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RETRY).assertIsEnabled()
        assertEquals("到期只恢复控件，不自动重试", 1, host.api.calls.size)
        rule.onNodeWithTag(SearchTags.RETRY).performClick()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun footerLoadingKeepsSpokenLoadingAndOnePendingPage() {
        host.launch("paging")
        rule.onNodeWithTag(SearchTags.LOAD_MORE).assertIsDisplayed()
        val loading = rule.onNodeWithTag(SearchTags.LOAD_MORE).fetchSemanticsNode()
        val d = loading.layoutInfo.density.density
        assertTrue("footer 内是真实卡片骨架，不能只有一行加载文字", loading.boundsInRoot.height / d >= 96)
        assertTrue(
            loading.config.toString().contains("加载中"),
        )
        assertEquals(2, host.api.calls.size)
        rule.runOnIdle { host.controller.loadMore() }
        assertEquals(2, host.api.calls.size)
    }

    @Test fun noticesRemainPoliteAndFallbackSearchOnlyHappensOnce() {
        host.launch("notice")
        rule
            .onNodeWithTag(SearchTags.NOTICE)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals(2, host.api.calls.size)
        assertTrue(
            host.api.calls[0]
                .query
                .orEmpty()
                .contains("platform=taobao"),
        )
        assertTrue(
            host.api.calls[1]
                .query
                .orEmpty()
                .contains("platform=jd"),
        )
        assertEquals(1, host.port.refreshes)
    }

    @Test fun cachedNoticeRemainsPoliteAnd14Sp() {
        host.launch("stale")
        rule
            .onNodeWithTag(SearchTags.STALE)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText("价格可能已变化，下单前以平台为准", useUnmergedTree = true).assertSearchFont(14f)
    }
}
