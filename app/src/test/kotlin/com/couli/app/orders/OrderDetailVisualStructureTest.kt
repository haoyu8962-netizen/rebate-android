package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.core.orders.OrderDraftTexts
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun invalidReasonIsInsideStatusCardBelowAmountsWithHeadingAndDivider() {
        host.launch(OrderDetailVisualFixtures.detail("detail_blacklist"))
        val card = rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode()
        val why = rule.onNodeWithTag(OrderTags.DETAIL_REASON).fetchSemanticsNode()
        why.assertInside(card)
        val heading = rule.detailText("为什么没返利？")
        assertTrue(heading.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertEquals(
            14f,
            heading
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            600,
            heading
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        val amount = rule.onNodeWithTag(OrderTags.DETAIL_AMOUNTS).fetchSemanticsNode()
        assertTrue(why.boundsInRoot.top >= amount.boundsInRoot.bottom)
        rule.withOrderPixels(card) { pixels ->
            val rect =
                androidx.compose.ui.geometry.Rect(
                    card.boundsInRoot.left,
                    amount.boundsInRoot.bottom,
                    card.boundsInRoot.right,
                    why.boundsInRoot.top,
                )
            assertTrue("原因区上方必须有真实分隔线", pixels.colorCount(rect, host.border.toArgb()) > 200)
        }
    }

    @Test fun appealBlockAlsoBelongsToStatusCardAndDoesNotInventSubmissionTime() {
        host.launch(OrderDetailVisualFixtures.detail("detail_appeal_pending"))
        rule
            .onNodeWithTag(OrderTags.DETAIL_APPEAL)
            .fetchSemanticsNode()
            .assertInside(rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode())
        rule.detailText(OrderDraftTexts.text("order_detail.appeal_pending.desc")).assertExists()
        // G-O03 / r1 S2-1 / T2：只限定申诉块的合法字典文本，原因说明允许出现「提交申诉」。
        assertEquals(
            listOf("order_detail.appeal_pending.title", "order_detail.appeal_pending.desc", "order_detail.appeal_progress")
                .map(OrderDraftTexts::text),
            rule.detailDrawn(OrderTags.DETAIL_APPEAL).map { it.result.layoutInput.text.text },
        )
        rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "appeal").assertDoesNotExist()
    }

    @Test fun missingDifferenceDoesNotSubtractEstimateAndHelpKeepsOriginalRoute() {
        host.launch(OrderDetailVisualFixtures.detail("detail_settled"))
        rule.detailText(OrderDraftTexts.text("order_reason.PRICE_COMPARE.title")).assertExists()
        assertFalse(
            rule.detailDrawn().any {
                it.result.layoutInput.text.text
                    .contains("比预估") ||
                    it.result.layoutInput.text.text
                        .contains("¥1.3")
            },
        )
        rule.clickDetail(OrderTags.DETAIL_HELP)
        assertEquals(listOf("Help" to JsonObject(mapOf("article_id" to JsonPrimitive("price-compare-fixture")))), host.opened)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun helpAbsentWithoutConfigDoesNotRemoveReason() {
        host.help = null
        host.launch(OrderDetailVisualFixtures.detail("detail_settled"))
        rule.detailText(OrderDraftTexts.text("order_reason.PRICE_COMPARE.title")).assertExists()
        rule.onNodeWithTag(OrderTags.DETAIL_HELP).assertDoesNotExist()
    }

    @Test fun productTitleIs14MediumAndNumberSharesItsColumn() {
        host.launch()
        val title = rule.onNodeWithText("样例商品 C：洗衣液 3kg", useUnmergedTree = true)
        val number = rule.onNodeWithTag(OrderTags.DETAIL_ORDER_NO, useUnmergedTree = true)
        assertEquals(
            14f,
            title
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            500,
            title
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(title.fetchSemanticsNode().boundsInRoot.left, number.fetchSemanticsNode().boundsInRoot.left, 1f)
        number.assertCopyFollowsLastLine(rule.onNodeWithText("复制", useUnmergedTree = true))
        rule.clickDetail(OrderTags.DETAIL_VIEW_PRODUCT)
        assertEquals(
            listOf("ProductDetail" to JsonObject(mapOf("platform" to JsonPrimitive("jd"), "product_key" to JsonPrimitive("fixture:C")))),
            host.opened,
        )
        rule.clickDetail(OrderTags.DETAIL_COPY)
        assertEquals(listOf("305678901234"), host.copies)
        assertEquals(1, host.opened.size)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun productAndCopyRealClicksPreserveRoutesAndSingleInitialRequest() {
        host.launch()
        rule.clickDetail(OrderTags.DETAIL_VIEW_PRODUCT)
        rule.clickDetail(OrderTags.DETAIL_COPY)
        assertEquals(
            listOf(
                "ProductDetail" to
                    JsonObject(
                        mapOf(
                            "platform" to JsonPrimitive("jd"),
                            "product_key" to JsonPrimitive("fixture:C"),
                        ),
                    ),
            ),
            host.opened,
        )
        assertEquals(listOf("305678901234"), host.copies)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun shareOtherHasNoViewProductCopyOrVisibleImagePlaceholder() {
        host.launch(OrderDetailVisualFixtures.detail("detail_share"))
        rule.onNodeWithText(OrderDraftTexts.text("order_list.other_product")).assertExists()
        rule.onNodeWithTag(OrderTags.DETAIL_VIEW_PRODUCT).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.DETAIL_COPY).assertDoesNotExist()
        rule.onNodeWithText(OrderDraftTexts.text("order_list.image_placeholder")).assertDoesNotExist()
        val number = rule.onNodeWithTag(OrderTags.DETAIL_ORDER_NO, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(number.config.toString().contains("星号"))
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CopyText)).assertCountEquals(0)
    }

    @Test fun aiCardIsAfterTimelineAndFullWidthWithBothEdgesClickable() {
        host.launch()
        val ai = rule.onNodeWithTag(OrderTags.ASK_AI).performScrollTo().fetchSemanticsNode()
        val timeline = rule.onNodeWithTag(OrderTags.DETAIL_TIMELINE).fetchSemanticsNode()
        val d = ai.layoutInfo.density.density
        assertTrue(ai.boundsInRoot.top >= timeline.boundsInRoot.bottom)
        assertEquals(358f, ai.boundsInRoot.width / d, 1f)
        ai.assertTouch48()
        val text = rule.onNodeWithText("问问 AI", useUnmergedTree = true)
        assertEquals(
            17f,
            text
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(
            500,
            text
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight,
        )
        listOf(ai.boundsInRoot.left + d, ai.boundsInRoot.right - d).forEach { x -> rule.touchDetail(Offset(x, ai.boundsInRoot.center.y)) }
        assertEquals(2, host.askCount)
        assertEquals(1, host.api.calls.size)
        rule.runOnIdle { host.askVisible.value = false }
        rule.onNodeWithTag(OrderTags.ASK_AI).assertDoesNotExist()
    }

    @Test fun unavailableAgentDoesNotRenderEntry() {
        host.launch(agent = false)
        rule.onNodeWithTag(OrderTags.ASK_AI).assertDoesNotExist()
    }
}
