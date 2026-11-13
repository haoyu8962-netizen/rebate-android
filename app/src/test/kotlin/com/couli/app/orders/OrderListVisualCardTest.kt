package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.readableText
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualCardTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun paymentTimeIsRightAlignedOppositePlatformBadge() {
        host.launch(OrderListVisualFixtures.one())
        val card = rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").fetchSemanticsNode()
        val time = rule.cardText("今天 09:05").fetchSemanticsNode()
        val badge = rule.cardText("淘宝").fetchSemanticsNode()
        assertEquals(
            "付款时间右对齐卡片内容边",
            card.boundsInRoot.right - 16 * card.layoutInfo.density.density,
            time.boundsInRoot.right,
            2 * card.layoutInfo.density.density,
        )
        assertEquals(badge.boundsInRoot.center.y, time.boundsInRoot.center.y, 2f)
        assertEquals(
            12f,
            rule
                .cardText("今天 09:05")
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
    }

    @Test fun titleIs14MediumSingleLineAndOnlyTitleMayEllipsize() {
        host.launch(OrderListVisualFixtures.page("stress"))
        val title =
            host.model.controller.state.value.items
                .single()
                .title!!
        val result = rule.cardText(title, "STRESS").textLayout()
        assertEquals(14f, result.layoutInput.style.fontSize.value, .01f)
        assertEquals(
            500,
            result.layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(1, result.layoutInput.maxLines)
        assertEquals(1, result.lineCount)
        assertTrue("长商品标题允许单行省略", result.isLineEllipsized(0))
    }

    @Test fun numberBelongsToProductRightColumnAndCopyUses12Sp() {
        host.launch(OrderListVisualFixtures.one())
        val title = rule.cardText("样例商品 A：纯牛奶 250ml×12 盒").fetchSemanticsNode()
        val number = rule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "A", useUnmergedTree = true)
        assertEquals("单号要与标题同列，不能掉到64dp图片下面", title.boundsInRoot.left, number.fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(
            12f,
            number
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        val copy = rule.cardText("复制")
        assertEquals(
            12f,
            copy
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        copy.assertNoTextOverflow()
    }

    @Test fun shortNumberCopyFollowsTextInsteadOfBeingPushedToTrailingEdge() {
        host.launch(OrderListVisualFixtures.page("short_number"))
        val number = rule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "SHORT", useUnmergedTree = true)
        val label = rule.cardText("复制", "SHORT")
        // #24 ⑨（FU-AND-NUM-WRAP）：复制位置改 NumCopy 可视窗口口径 + 原 M10 流式位置（号码不横滑时逐字同旧）
        number.assertCopyClearOfNumber(rule, "1234", OrderTags.COPY_PREFIX + "SHORT", "订单列表卡短单号")
        val copy = label.fetchSemanticsNode()
        val d = copy.layoutInfo.density.density
        assertEquals(
            "短单号仍应与复制同行",
            number
                .fetchSemanticsNode()
                .boundsInRoot.center.y,
            copy.boundsInRoot.center.y,
            2 * d,
        )
        val target = rule.onNodeWithTag(OrderTags.COPY_PREFIX + "SHORT").fetchSemanticsNode()
        target.assertTouch48()
        assertTrue("48dp命中不撑高单号视觉行", copy.boundsInRoot.height / d <= 20)
    }

    @Test fun subtleDividerSeparatesProductBlockAndAmountGrid() {
        host.launch(OrderListVisualFixtures.one())
        val card = rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").fetchSemanticsNode()
        val number = rule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "A", useUnmergedTree = true).fetchSemanticsNode()
        val paid = rule.cardText("实付金额").fetchSemanticsNode()
        val area = Rect(card.boundsInRoot.left, number.boundsInRoot.bottom, card.boundsInRoot.right, paid.boundsInRoot.top)
        rule.withOrderPixels(card) { bitmap ->
            val longest =
                (area.top.toInt() until area.bottom.toInt()).maxOfOrNull { y ->
                    (area.left.toInt() until area.right.toInt()).count { x -> bitmap.getPixel(x, y) == host.border.toArgb() }
                } ?: 0
            assertTrue("商品与金额间需要贯穿内容列的1dp分隔线", longest >= area.width * .9f)
        }
    }

    @Test fun copyIsSeparateTargetDoesNotOpenCardAndOldRenderedCopyCannotWrite() {
        host.launch(OrderListVisualFixtures.one())
        val copy = rule.onNodeWithTag(OrderTags.COPY_PREFIX + "A").assertIsDisplayed().fetchSemanticsNode()
        val oldClick = checkNotNull(copy.config[SemanticsActions.OnClick].action)
        rule.touch(copy.touchBoundsInRoot.center)
        assertEquals(listOf("3741234567890123456"), host.copies)
        assertTrue(host.opened.isEmpty())
        val before = host.api.calls.size
        rule.runOnIdle {
            host.epoch += 1
            oldClick()
        }
        assertEquals(1, host.copies.size)
        assertEquals("过期复制不能触发请求", before, host.api.calls.size)
    }

    @Test fun cardClickOpensExactDetailOnceWithMergedReadingAndIndependentCopy() {
        host.launch(OrderListVisualFixtures.one())
        val card = rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").fetchSemanticsNode()
        val spoken = card.readableText()
        listOf("淘宝", "纯牛奶", "件数 2", "3741234567890123456", "实付金额", "预估返", "已付款").forEach {
            assertTrue("整卡读屏缺 $it：$spoken", spoken.contains(it))
        }
        assertEquals(1, card.config[SemanticsProperties.ContentDescription].count { it == "淘宝" })
        assertTrue(
            rule
                .cardText("淘宝")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.HideFromAccessibility),
        )
        assertFalse(card.config[SemanticsProperties.Text].any { it.text == "复制" })
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").performClick()
        assertEquals(listOf("OrderDetail" to JsonObject(mapOf("order_id" to JsonPrimitive("A")))), host.opened)
        assertTrue(host.copies.isEmpty())
        assertEquals(1, host.api.calls.size)
    }
}
