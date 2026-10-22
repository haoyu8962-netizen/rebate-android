package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只声明入口；测试作者不执行 record/verify，不生成基准。画板和 PNG 永远只读。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class OrderListVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w390dp-h1740dp-xhdpi")
    fun selfLight() = page("Orders-v2-light")

    @Test
    @Config(qualifiers = "w390dp-h2015dp-xhdpi")
    fun selfDark() = page("OrdersDark-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h1492dp-xhdpi")
    fun shareLight() = page("OrdersShare-v2-light", "list_share", scope = "share")

    @Test
    @Config(qualifiers = "w390dp-h1492dp-xhdpi")
    fun shareDark() = page("OrdersShare-v2-dark", "list_share", scope = "share", dark = true)

    @Test fun emptySelfLight() = page("OrdersEmpty-light", "list_self_empty")

    @Test fun emptySelfDark() = page("OrdersEmpty-dark", "list_self_empty", dark = true)

    @Test fun emptyShareLight() = page("OrdersShareEmpty-light", "list_share_empty", scope = "share")

    @Test fun emptyShareDark() = page("OrdersShareEmpty-dark", "list_share_empty", scope = "share", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h2075dp-xhdpi")
    fun pinnedLight() = pinned(false)

    @Test
    @Config(qualifiers = "w390dp-h2075dp-xhdpi")
    fun pinnedDark() = pinned(true)

    @Test
    @Config(qualifiers = "w390dp-h1951dp-xhdpi")
    fun hiddenLight() = hidden(false)

    @Test
    @Config(qualifiers = "w390dp-h1951dp-xhdpi")
    fun hiddenDark() = hidden(true)

    @Test
    @Config(qualifiers = "w390dp-h1794dp-xhdpi")
    fun staleLight() = stale(false)

    @Test
    @Config(qualifiers = "w390dp-h1794dp-xhdpi")
    fun staleDark() = stale(true)

    @Test fun searchFoundLight() = search(false, false)

    @Test fun searchFoundDark() = search(false, true)

    @Test fun searchEmptyLight() = search(true, false)

    @Test fun searchEmptyDark() = search(true, true)

    @Test fun platformLight() = sheet(false, false)

    @Test fun platformDark() = sheet(false, true)

    @Test fun monthLight() = sheet(true, false)

    @Test fun monthDark() = sheet(true, true)

    @Test fun platform200() = sheet(false, false, 2f)

    @Test fun month200() = sheet(true, false, 2f)

    @Test fun loadingLight() = loading(false)

    @Test fun loadingDark() = loading(true)

    @Test fun errorLight() = error(false)

    @Test fun errorDark() = error(true)

    @Test fun paginationLoading() = pagination(false)

    @Test fun paginationError() = pagination(true)

    @Test fun historyRangeAtEnd() {
        host.launch(OrderListVisualFixtures.page("history"), shell = true)
        rule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(6)
        rule.onNodeWithTag(OrderTags.HISTORY_HINT).assertIsDisplayed()
        capture("state-history-range-light")
    }

    @Test fun font200At390() = page("Orders-font200-w390-light", "stress", fontScale = 2f)

    @Test fun font200DarkAt390() = page("Orders-font200-w390-dark", "stress", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun font200At320() = page("Orders-font200-w320-light", "stress", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = page("Orders-w840-light")

    private fun page(
        name: String,
        fixture: String = "list_self",
        scope: String = "self",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(OrderListVisualFixtures.page(fixture), scope = scope, dark = dark, fontScale = fontScale, shell = true)
        assertEquals(
            OrderListVisualFixtures
                .page(fixture)
                .getValue("items")
                .jsonArray.size,
            host.model.controller.state.value.items.size,
        )
        capture(name)
    }

    private fun pinned(dark: Boolean) {
        host.launch(OrderListVisualFixtures.page("list_self_pinned"), dark = dark, shell = true)
        rule.onNodeWithTag(FIND_PINNED).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.FIND_ENTRY).assertDoesNotExist()
        capture("OrdersClaimPinned-${mode(dark)}")
    }

    private fun hidden(dark: Boolean) {
        host.launch(dark = dark, shell = true)
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        capture("OrdersHidden-${mode(dark)}")
    }

    private fun stale(dark: Boolean) {
        host.stale()
        host.launch(dark = dark, shell = true)
        rule.onNodeWithTag(OrderTags.STALE).assertIsDisplayed()
        capture("OrdersStale-v2-${mode(dark)}")
    }

    private fun search(
        empty: Boolean,
        dark: Boolean,
    ) {
        host.launch(
            OrderListVisualFixtures.page(if (empty) "search_share_empty" else "search_self"),
            scope = if (empty) "share" else "self",
            dark = dark,
            shell = true,
        )
        rule.onNodeWithTag(OrderTags.SEARCH).performTextInput(if (empty) "不存在" else "纯牛奶")
        rule.onNodeWithTag(OrderTags.SEARCH).performImeAction()
        rule.onNodeWithTag(if (empty) OrderTags.SEARCH_EMPTY else OrderTags.SEARCH_FOUND).assertIsDisplayed()
        capture("${if (empty) "OrdersSearchEmpty" else "OrdersSearch-v2"}-${mode(dark)}")
    }

    private fun sheet(
        month: Boolean,
        dark: Boolean,
        fontScale: Float = 1f,
    ) {
        host.launch(
            OrderListVisualFixtures.page(if (month) "month_options" else "list_self"),
            dark = dark,
            fontScale = fontScale,
            shell = true,
        )
        rule.onNodeWithTag(if (month) OrderTags.FILTER_MONTH else OrderTags.FILTER_PLATFORM).performClick()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "all").assertIsDisplayed()
        val title =
            rule.onNode(
                hasText(
                    if (month) "月份" else "平台",
                ) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading),
                useUnmergedTree = true,
            )
        assertEquals(
            "不能把未放大的 Dialog 标作200%",
            fontScale,
            title
                .textLayout()
                .layoutInput.density.fontScale,
            .001f,
        )
        val name = "OrdersFilter${if (month) "Month" else "Platform"}-v2-${mode(dark)}${if (fontScale == 2f) "-font200" else ""}"
        captureScreenRoboImage("src/test/snapshots/order-list-v/$name.png")
    }

    private fun loading(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        host.launch(dark = dark, shell = true)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        capture("state-loading-${mode(dark)}")
    }

    private fun error(dark: Boolean) {
        host.api.fail()
        host.launch(dark = dark, shell = true)
        rule.onNodeWithTag(OrderTags.ERROR).assertIsDisplayed()
        capture("state-error-${mode(dark)}")
    }

    private fun pagination(error: Boolean) {
        if (error) {
            host.api.nextHold = null
            host.api.nextAnswer = OrderListVisualApi.failure()
        }
        host.launch(shell = true)
        rule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(4)
        if (error) rule.onNodeWithTag(OrderTags.RETRY).performScrollTo().assertIsDisplayed()
        capture("state-pagination-${if (error) "error" else "loading"}-light")
    }

    private fun capture(name: String) {
        rule.onNodeWithTag(OrderTags.LIST_PAGE).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/order-list-v/$name.png")
    }

    private fun mode(dark: Boolean) = if (dark) "dark" else "light"
}
