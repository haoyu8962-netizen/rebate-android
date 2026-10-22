package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliSpacing
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class OrderDetailVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun font200At390KeepsFullAmountsAndTitle() = stress()

    @Test
    @Config(qualifiers = "w320dp-h1800dp-xhdpi")
    fun font200At320KeepsFullAmountsAndTitle() = stress()

    @Test fun font200DarkKeepsFullAmountsAndTitle() = stress(dark = true)

    @Test
    @Config(qualifiers = "w390dp-h640dp-xhdpi")
    fun shortPageWithoutWithdrawKeepsLastCardAboveNavigationInset48() = safeLastCard()

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun shortFont200PageWithoutWithdrawKeepsLastCardAboveNavigationInset48() = safeLastCard(fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun shortFont200WithoutAgentKeepsLastTimelineAboveNavigationInset48() = safeLastCard(fontScale = 2f, agent = false)

    private fun safeLastCard(
        fontScale: Float = 1f,
        agent: Boolean = true,
    ) {
        host.launch(fontScale = fontScale, agent = agent)
        host.navigationInset(48)
        rule.onNodeWithTag(DETAIL_BOTTOM).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "withdraw").assertDoesNotExist()
        val scroll = rule.onNode(hasScrollAction())
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        rule.waitForIdle()
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("短屏必须确实产生滚动", range.maxValue() > 0f)
        assertEquals("必须滚到内容末尾", range.maxValue(), range.value(), 1f)
        val page = rule.onNodeWithTag(OrderTags.DETAIL_PAGE).fetchSemanticsNode()
        val last = rule.onNodeWithTag(if (agent) OrderTags.ASK_AI else OrderTags.DETAIL_TIMELINE)
        val d = page.layoutInfo.density.density
        assertEquals("真实三键导航 inset", 48f, host.observedNavigationBottom / d, .5f)
        // T-r1-2：两者都取同一语义修饰符的根坐标（dp），不用 LayoutNode 内层坐标与语义边界混比。
        val unclipped = last.getUnclippedBoundsInRoot()
        val visible = last.getBoundsInRoot()
        // 时间线的语义 tag 位于 space4 内边距之后，整卡上下边缘还需计入该内边距；AI tag 已覆盖整卡。
        val cardPadding = if (agent) 0f else CouliSpacing.space4.value
        val bottom = unclipped.bottom.value + cardPadding
        val safeBottom = (page.boundsInRoot.bottom - host.observedNavigationBottom) / d
        val tolerance = 1f / d
        assertTrue("最后一张卡不能藏在导航栏：bottom=$bottom safeBottom=$safeBottom", bottom <= safeBottom + tolerance)
        assertTrue("最后一张卡顶部也应可见", unclipped.top.value - cardPadding >= scroll.getBoundsInRoot().top.value - tolerance)
        assertEquals("不能靠底部裁切伪装整卡避开导航栏", unclipped.bottom.value, visible.bottom.value, tolerance)
        assertEquals("不能靠顶部裁切伪装整卡可见", unclipped.top.value, visible.top.value, tolerance)
        assertEquals(1, host.api.calls.size)
    }

    private fun stress(dark: Boolean = false) {
        host.launch(OrderDetailVisualFixtures.detail("detail_stress"), dark = dark, fontScale = 2f)
        val rebate = rule.detailAmount("¥99999.99")
        val pay = rule.detailAmount("¥88888.88")
        assertEquals(2f, rebate.result.layoutInput.density.fontScale, .001f)
        assertEquals(28f, rebate.result.layoutInput.style.fontSize.value, .01f)
        assertEquals(22f, pay.result.layoutInput.style.fontSize.value, .01f)
        assertTrue("纵排返利在上", rebate.bounds.bottom <= pay.bounds.top)
        rebate.assertNoOverflow()
        pay.assertNoOverflow()
        val text =
            OrderDetailVisualFixtures
                .detail("detail_stress")
                .getValue("title")
                .jsonPrimitive.content
        val title = rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
        title.assertNoTextOverflow()
        assertEquals(
            14f,
            title
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        val number = rule.onNodeWithTag(OrderTags.DETAIL_ORDER_NO, useUnmergedTree = true).performScrollTo()
        number.assertNoTextOverflow()
        number.assertCopyFollowsLastLine(rule.onNodeWithText("复制", useUnmergedTree = true))
        rule.clickDetail(OrderTags.DETAIL_COPY)
        assertEquals(listOf("3741234567890123456"), host.copies)
    }

    @Test
    @Config(qualifiers = "w320dp-h1600dp-xhdpi")
    fun cardButtonsWrapAt200WithoutOverlappingTargetsOrDuplicateCallbacks() {
        host.launch(OrderDetailVisualFixtures.detail("detail_clawed_two"), fontScale = 2f)
        val a = OrderTags.DETAIL_ACTION_PREFIX + "contact_cs"
        val b = OrderTags.DETAIL_ACTION_PREFIX + "ledger"
        rule.clickDetail(a)
        rule.clickDetail(b)
        val first = rule.onNodeWithTag(a).fetchSemanticsNode()
        val second = rule.onNodeWithTag(b).fetchSemanticsNode()
        first.assertInside(rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode())
        second.assertInside(rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode())
        assertTrue("放不下应换行", second.boundsInRoot.top >= first.boundsInRoot.bottom)
        assertFalse(first.touchBoundsInRoot.overlaps(second.touchBoundsInRoot))
        rule.onNodeWithText("联系客服", useUnmergedTree = true).assertNoTextOverflow()
        rule.onNodeWithText("查看流水", useUnmergedTree = true).assertNoTextOverflow()
        assertEquals(listOf("Help", "Ledger"), host.opened.map { it.first })
        assertEquals(1, host.api.calls.size)
    }

    @Test
    @Config(qualifiers = "w840dp-h1400dp-xhdpi")
    fun wideContentIs600CenteredWhileBottomSurfaceSpans840() {
        host.launch(OrderDetailVisualFixtures.detail("detail_settled"))
        val root = rule.onNodeWithTag(OrderTags.DETAIL_PAGE).fetchSemanticsNode()
        val d = root.layoutInfo.density.density
        for (tag in listOf(OrderTags.DETAIL_STATUS, OrderTags.DETAIL_TIMELINE, OrderTags.ASK_AI)) {
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            // DETAIL_STATUS 的 tag 在16dp内边距之后；外卡最大568，内容最大536。
            assertTrue(node.boundsInRoot.width <= 568 * d + 1)
            assertEquals(root.boundsInRoot.center.x, node.boundsInRoot.center.x, 1f)
        }
        val bottom = rule.onNodeWithTag(DETAIL_BOTTOM).fetchSemanticsNode()
        assertEquals(840f, bottom.boundsInRoot.width / d, 1f)
        val button = rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "withdraw").fetchSemanticsNode()
        assertEquals(568f, button.boundsInRoot.width / d, 1f)
        assertEquals(root.boundsInRoot.center.x, button.boundsInRoot.center.x, 1f)
        assertTrue((root.boundsInRoot.bottom - button.boundsInRoot.bottom) / d >= 16 - .5f)
        rule.withOrderPixels(root) { pixels ->
            val y =
                bottom.boundsInRoot.center.y
                    .toInt()
            assertEquals(host.surface.toArgb(), pixels.getPixel((8 * d).toInt(), y))
            assertEquals(host.surface.toArgb(), pixels.getPixel((832 * d).toInt(), y))
        }
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "withdraw")
        assertEquals(listOf("Withdraw" to JsonObject(emptyMap())), host.opened)
    }
}
