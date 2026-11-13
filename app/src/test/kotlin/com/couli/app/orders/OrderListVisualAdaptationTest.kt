package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.numwrap.NumField
import com.couli.app.numwrap.NumToken
import com.couli.app.numwrap.NumWrap
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliTextStyles
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
class OrderListVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun longAmountsAt200Percent390NeverShrinkClipOrEllipsize() = stress(false)

    @Test fun longAmountsAt200Percent390DarkNeverShrinkClipOrEllipsize() = stress(true)

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun longAmountsAt200Percent320NeverShrinkClipOrEllipsize() = stress(false)

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun multilineNumberAt200Percent320PlacesWholeCopyAfterItsLastLine() {
        host.launch(OrderListVisualFixtures.page("stress"), fontScale = 2f)
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + "STRESS").performScrollTo()
        val number = rule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "STRESS", useUnmergedTree = true)
        val copy = rule.cardText("复制", "STRESS")
        assertEquals(
            2f,
            number
                .textLayout()
                .layoutInput.density.fontScale,
            .001f,
        )
        // #24 ①（FU-AND-NUM-WRAP，D10-1265 / 判据 1）：19 位号码在 w320×200% 列表卡独占一行也放不下，现在整行框内横滑、一行。
        // 「必须实际形成多行」换成「必须实际处在横滑态」（同样防短号绕过）；「整段落在可见框内」换成整段不断、不截断、不缩字、真滚到首尾，纵向不裁切照旧；
        // 复制位置改 NumCopy 可视窗口口径，且横滑时【复制】必须整体换到号码下一行、回到列起点。
        val where = "订单列表卡订单号（w320×200%）"
        number.assertNumberScrollsFromStart(where)
        number.assertNumberNotClippedVertically(where)
        NumWrap.assertField(rule, NumField(STRESS_NO, where, NumWrap.within(OrderTags.ORDER_NO_PREFIX + "STRESS"), ORDER_NO_TOKEN))
        copy.assertNoTextOverflow()
        number.assertCopyClearOfNumber(rule, STRESS_NO, OrderTags.COPY_PREFIX + "STRESS", where)
        val target = rule.onNodeWithTag(OrderTags.COPY_PREFIX + "STRESS").fetchSemanticsNode()
        target.assertTouch48()
        rule.touch(target.touchBoundsInRoot.center)
        assertEquals(listOf(STRESS_NO), host.copies)
        assertTrue(host.opened.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun longSelectedFiltersWrapWithoutCoveringArrowOrEyeAt200Percent() {
        host.launch(OrderListVisualFixtures.page("history"), fontScale = 2f)
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).performClick()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "pdd").performScrollTo().performClick()
        rule.onNodeWithTag(OrderTags.FILTER_MONTH).performClick()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "2026-10").performScrollTo().performClick()
        val eye = rule.onNodeWithTag(OrderTags.MASK).fetchSemanticsNode()
        eye.assertTouch48()
        listOf(OrderTags.FILTER_PLATFORM, OrderTags.FILTER_MONTH).forEach { tag ->
            val button = rule.onNodeWithTag(tag).fetchSemanticsNode()
            button.assertTouch48()
            val arrow = rule.onNodeWithTag(tag + "_arrow", useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(arrow.boundsInRoot.width >= 16 * arrow.layoutInfo.density.density - 1)
            assertFalse(button.touchBoundsInRoot.overlaps(eye.touchBoundsInRoot))
        }
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        assertTrue(host.mask.hidden.value)
        assertEquals(3, host.api.calls.size)
    }

    @Test
    @Config(qualifiers = "w840dp-h1400dp-xhdpi")
    fun wideScreenCenters600ColumnIncludingPinnedBannerAndCards() {
        host.launch(OrderListVisualFixtures.page("list_self_pinned"))
        val page = rule.onNodeWithTag(OrderTags.LIST_PAGE).fetchSemanticsNode()
        val d = page.layoutInfo.density.density
        val expected = Rect(120 * d, 0f, 720 * d, page.boundsInRoot.bottom)
        listOf(FIND_PINNED, OrderTags.TAB_SELF, OrderTags.FILTERS, OrderTags.CARD_PREFIX + "A").forEach { tag ->
            val bounds =
                rule
                    .onNodeWithTag(tag)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("$tag 在居中600列内", bounds.left >= expected.left && bounds.right <= expected.right)
        }
        val pinned = rule.onNodeWithTag(FIND_PINNED).fetchSemanticsNode().boundsInRoot
        assertEquals(page.boundsInRoot.center.x, pinned.center.x, d)
        assertEquals(568f, pinned.width / d, 2f)
        host.api.offline()
        rule.runOnIdle { host.model.refresh() }
        rule.waitForIdle()
        val stale = rule.onNodeWithTag(OrderTags.STALE).fetchSemanticsNode().boundsInRoot
        assertEquals(page.boundsInRoot.center.x, stale.center.x, d)
        assertTrue(stale.left >= expected.left && stale.right <= expected.right)
    }

    private fun stress(dark: Boolean) {
        host.launch(OrderListVisualFixtures.page("stress"), dark = dark, fontScale = 2f)
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + "STRESS").performScrollTo()
        val rebate = rule.orderAmount("¥99999.99", "STRESS")
        val paid = rule.orderAmount("¥88888.88", "STRESS")
        val copy = rule.cardText("复制", "STRESS")
        val number = rule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "STRESS", useUnmergedTree = true)
        copy.assertNoTextOverflow() // 复用 main 的 v3 宽度+位置判据，不能恢复 didOverflowWidth 假阳性。
        // #24 ②（FU-AND-NUM-WRAP，D10-1265 / 判据 1）：订单号独占一行仍放不下时框内横滑，布局宽 > 可见框；「整段落在可见框内」换成
        // 整段不断、不截断、不缩字（captionAmount 令牌）、真滚到首尾（NumReach），纵向不裁切照旧。
        val where = "订单列表卡订单号（200%）"
        number.assertNumberNotClippedVertically(where)
        NumWrap.assertField(rule, NumField(STRESS_NO, where, NumWrap.within(OrderTags.ORDER_NO_PREFIX + "STRESS"), ORDER_NO_TOKEN))
        listOf(copy, number).forEach {
            assertEquals(
                2f,
                it
                    .textLayout()
                    .layoutInput.density.fontScale,
                .001f,
            )
        }
        assertEquals(
            17f,
            rebate.result
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            17f,
            paid.result
                .layoutInput.style.fontSize.value,
            .01f,
        )
        listOf(rebate, paid).forEach {
            it.assertNoOverflow()
            assertEquals(2f, it.result.layoutInput.density.fontScale, .001f)
        }
        assertTrue("200% 必须纵排返利在上", rebate.bounds.bottom <= paid.bounds.top)
        val target = rule.onNodeWithTag(OrderTags.COPY_PREFIX + "STRESS").fetchSemanticsNode()
        target.assertTouch48()
        rule.touch(target.touchBoundsInRoot.center)
        assertEquals(listOf(STRESS_NO), host.copies)
        assertTrue(host.opened.isEmpty())
    }

    private companion object {
        /** visualOrders.stress 的订单号（19 位，QA-11 断开的那串）。 */
        const val STRESS_NO = "3741234567890123456"

        /** 订单号令牌 captionAmount，200%（不缩字）。 */
        val ORDER_NO_TOKEN = NumToken(CouliTextStyles.captionAmount.fontSize, 2f)
    }
}
