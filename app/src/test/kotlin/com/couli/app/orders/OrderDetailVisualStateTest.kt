package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.orders.OrderDraftTexts
import com.couli.feature.orders.OrderTags
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun genericErrorIsNotEmptyAndRetryMakesOneGetOrder() {
        host.api.fail()
        host.launch()
        rule.onNodeWithTag(OrderTags.DETAIL_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.DETAIL_EMPTY).assertDoesNotExist()
        rule.onNodeWithText("abc123", substring = true).assertExists()
        host.api.success(OrderDetailVisualFixtures.detail())
        rule.onNodeWithText(OrderDraftTexts.text("order_list.retry")).performTouchInput { click(center) }
        rule.waitForIdle()
        rule.onNodeWithTag(OrderTags.DETAIL_STATUS).assertIsDisplayed()
        assertEquals(2, host.api.calls.size)
        assertEquals(
            host.api.calls.first(),
            host.api.calls
                .last()
                .copy(
                    ownership =
                        host.api.calls
                            .first()
                            .ownership,
                ),
        )
    }

    @Test fun notFoundHasNoRetryOrFakeDetail() {
        host.api.fail(30701)
        host.launch()
        rule.onNodeWithTag(OrderTags.DETAIL_EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.DETAIL_STATUS).assertDoesNotExist()
        rule.onNodeWithText(OrderDraftTexts.text("order_list.retry")).assertDoesNotExist()
        rule.onNodeWithText(OrderDraftTexts.text("error.30701")).assertExists()
        assertEquals(1, host.api.calls.size)
    }

    @Test fun cachedDetailStaysReadableAndSuccessfulRetryClearsStale() {
        host.stale()
        host.launch()
        rule.onNodeWithTag(OrderTags.DETAIL_STALE).assertIsDisplayed()
        rule.detailAmount("¥4.5")
        host.api.success(OrderDetailVisualFixtures.detail())
        rule.clickDetail(OrderTags.DETAIL_STALE_RETRY)
        rule.onNodeWithTag(OrderTags.DETAIL_STALE).assertDoesNotExist()
        assertEquals(2, host.api.calls.size)
        assertEquals(
            host.api.calls.first(),
            host.api.calls
                .last()
                .copy(
                    ownership =
                        host.api.calls
                            .first()
                            .ownership,
                ),
        )
    }

    @Test fun loadingHasOnlySkeletonAndReadableLoadingWithoutFakeData() {
        host.api.hold = kotlinx.coroutines.CompletableDeferred()
        host.launch()
        rule.onNodeWithContentDescription(OrderDraftTexts.text("order_list.loading")).assertExists()
        rule.onNodeWithText(OrderDraftTexts.text("order_list.loading")).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.DETAIL_STATUS).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.DETAIL_TIMELINE).assertDoesNotExist()
        assertEquals(1, host.api.calls.size)
    }

    @Test fun skeletonContainsStatusAmountsProductAndFourTimelineRows() {
        host.api.hold = kotlinx.coroutines.CompletableDeferred()
        host.launch()
        val loading = rule.onNodeWithContentDescription(OrderDraftTexts.text("order_list.loading")).fetchSemanticsNode()
        val d = loading.layoutInfo.density.density
        rule.withOrderPixels(loading) { bitmap ->
            val blocks = orderSkeletonBlocks(bitmap, loading.boundsInRoot, host.muted.toArgb(), d.toInt())
            assertTrue("不能仍用三块实心矩形代替结构：$blocks", blocks.size >= 10)
            val top = blocks.minOf { it.top }
            assertTrue(
                "状态标题下两列数字块",
                blocks.filter { it.top > top + 20 * d }.any { left ->
                    blocks.any { right -> right.left > left.right && kotlin.math.abs(right.top - left.top) < 2 * d }
                },
            )
        }
    }

    @Test fun reviewingHasNoExpectedMonthOrInventedContactButton() {
        host.launch(OrderDetailVisualFixtures.detail("detail_reviewing"))
        rule.detailText(OrderDraftTexts.text("order_status.REVIEWING.hint")).assertExists()
        rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs").assertDoesNotExist()
        assertFalse(
            rule.detailDrawn().any {
                it.result.layoutInput.text.text
                    .contains("预计")
            },
        )
    }
}
