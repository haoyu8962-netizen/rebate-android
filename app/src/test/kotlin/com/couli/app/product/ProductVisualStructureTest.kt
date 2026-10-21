package com.couli.app.product

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.DETAIL_TITLE_TAG
import com.couli.feature.product.ProductTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** D1 / D5 / D6 / D8 / D10 / D11：位置、实际点击与读屏，而不只查文字存在。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun systemBackDeliversOneCallbackAndInvalidatesInFlightRequest() {
        val gate = CompletableDeferred<Unit>()
        host.api.hold = gate
        host.launch()
        assertEquals(1, host.api.calls.size)
        host.systemBack()
        assertEquals(1, host.backs)
        assertTrue(host.api.cancelled.isNotEmpty())
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitForIdle()
        rule.onNodeWithTag(ProductTags.BUY).assertDoesNotExist()
    }

    @Test fun toolbarBackDeliversOneCallbackAndTitleKeepsHeading() {
        host.launch()
        rule.onNodeWithText("商品详情").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, host.backs)
    }

    @Test fun compareReasonReplacesRebateInsidePriceCardAndPassesOriginalProduct() {
        host.launch("pdd_price_compare")
        val reason = rule.onNodeWithTag(ProductTags.COMPARE_REASON).fetchSemanticsNode()
        val title = rule.onNodeWithTag(DETAIL_TITLE_TAG).fetchSemanticsNode()
        val price = rule.onNodeWithText("¥12.34", useUnmergedTree = true).fetchSemanticsNode()
        val priceCard = outerBounds(commonLayout(title.layoutInfo, price.layoutInfo))
        assertContainsRect(priceCard, reason.boundsInRoot)
        assertTrue(
            "原因行在价格之下、标题之上",
            reason.boundsInRoot.top >= price.boundsInRoot.bottom &&
                reason.boundsInRoot.bottom <= title.boundsInRoot.top,
        )
        rule.onNodeWithText("暂无返利", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("看看相似商品").performClick()
        assertEquals(listOf(ProductVisualFixtures.card("pdd_price_compare")), host.similar)
        assertEquals(0, host.buys.size)
    }

    @Test fun offShelfAfterUpdateHidesAllAmountsBenefitsAndDisclaimerAndDisablesBuy() {
        host.launch()
        rule.onNodeWithText("有券", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { host.controller.updateProduct { JsonObject(it + ("availability" to JsonPrimitive("off_shelf"))) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled().performClick()
        assertTrue(host.buys.isEmpty())
        rule.onNodeWithTag(ProductTags.DISCLAIMER).assertDoesNotExist()
        listOf("¥", "预估返后", "有券", "以结算为准").forEach { text ->
            rule.onNodeWithText(text, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        rule.onNodeWithText("商品已下架", useUnmergedTree = true).assertExists()
        val title =
            ProductVisualFixtures
                .card()
                .getValue("title")
                .jsonPrimitive.content
        rule.onNodeWithContentDescription("商品已下架，淘宝 $title").assertExists()
        rule.onNodeWithText("搜相似商品").assertDoesNotExist()
    }

    @Test fun loadingUsesLiveRegionWithoutVisibleLoadingTextOrAmounts() {
        host.api.hold = CompletableDeferred()
        host.launch()
        rule
            .onNodeWithTag(ProductTags.LOADING)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("加载中")))
        rule.onNodeWithText("加载中", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("¥", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(ProductTags.BUY).assertDoesNotExist()
    }

    @Test fun entryOffShelfIsAnnouncedOnceWithNoActionOrInventedCard() {
        host.api.fail(30141)
        host.launch()
        assertEquals(listOf("商品已下架（000123）"), host.spoken)
        rule
            .onNodeWithText("商品已下架（000123）")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(ProductTags.ERROR))).assertCountEquals(0)
        rule.onNodeWithTag(DETAIL_TITLE_TAG).assertDoesNotExist()
        rule.onNodeWithTag(ProductTags.BUY).assertDoesNotExist()
    }

    @Test fun expiredKeepsResearchCallbackAndDoesNotRetryDetail() {
        host.api.fail(30143)
        host.launch()
        rule
            .onNodeWithTag(ProductTags.RESEARCH)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(1, host.researches)
        assertEquals(1, host.api.calls.size)
        assertEquals(1, host.spoken.size)
        rule.onNodeWithTag(ProductTags.RETRY).assertDoesNotExist()
    }

    @Test fun genericErrorUsesHeadingAnnouncesOnceAndRetriesOnce() {
        host.api.fail(50401)
        host.launch()
        assertEquals(1, host.spoken.size)
        assertTrue(host.spoken.single().endsWith("（000123）"))
        rule.onNodeWithText(host.spoken.single()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithTag(ProductTags.RETRY).performClick()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun noRebateStillHasFullPriceDisclaimerAndInfoIconRowIsNot48DpTall() {
        host.launch("tb_rebate_gone")
        val panel = rule.onNodeWithTag(ProductTags.DISCLAIMER)
        panel.performScrollTo()
        val line = rule.onNodeWithText("券后价按单件计算", substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val row = checkNotNull(line.layoutInfo.parentInfo)
        val density = line.layoutInfo.density.density
        val pixels = rule.productPixels()
        try {
            val bounds = outerBounds(row)
            val ink =
                pixels.inkPixels(
                    Rect(
                        bounds.left,
                        bounds.top,
                        bounds.left + 16 * density,
                        bounds.top + 16 * density,
                    ),
                    host.muted.toArgb(),
                )
            assertTrue("无返利仍有静态口径 i 图标", ink > 16 * density)
        } finally {
            pixels.recycle()
        }
        assertTrue("48 命中区不能撑高静态说明行", outerBounds(row).height < 48 * density)
        assertFalse(host.controller.state.value.product == null)
    }

    @Test fun outOfScopeFavoriteTrendAndShareStayAbsent() {
        host.launch()
        listOf("收藏", "分享赚", "近 30 天券后价", "近 30 天价格").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        assertEquals(1, host.api.calls.size)
    }
}
