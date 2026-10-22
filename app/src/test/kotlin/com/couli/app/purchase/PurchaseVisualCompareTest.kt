package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.ProductVisualFixtures
import com.couli.feature.trade.TradeTags
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualCompareTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    private fun open() {
        host.launch("pdd_coupon")
        host.buy()
        host.answer("compare")
        host.counts(open = 1)
    }

    @Test fun mainSimilarUsesOriginalPlatformAndTitleWithoutBuying() {
        open()
        val primary = rule.fillRatio(rule.button("看看相似商品").fetchSemanticsNode(), host.primaryColor)
        val secondary = rule.fillRatio(rule.button("仍去购买（无返利）").fetchSemanticsNode(), host.primaryColor)
        rule.button("看看相似商品").performClick()
        rule.waitForIdle()
        host.counts(open = 1, callbacks = 0 to 1)
        PurchaseVisualCallbacks.assertCalls(host.controller, "similar")
        assertEquals("Search", host.routes.single().first)
        assertEquals(
            "pdd",
            host.routes
                .single()
                .second
                .getValue("platform")
                .jsonPrimitive.content,
        )
        assertEquals(ProductVisualFixtures.card("pdd_coupon")["title"], host.routes.single().second["q"])
        assertNull(host.controller.state.value.dialog)
        assertPrimaryAndSecondary(primary, secondary)
    }

    @Test fun secondaryConfirmsExistingAttemptWithoutAnotherOpenOrAuthorization() {
        open()
        val primary = rule.fillRatio(rule.button("看看相似商品").fetchSemanticsNode(), host.primaryColor)
        val secondary = rule.fillRatio(rule.button("仍去购买（无返利）").fetchSemanticsNode(), host.primaryColor)
        rule.button("仍去购买（无返利）").performClick()
        rule.waitForIdle()
        host.counts(open = 1, jumps = 1)
        PurchaseVisualCallbacks.assertCalls(host.controller, "confirm")
        assertEquals(1, host.reports.size)
        assertNull(host.controller.state.value.dialog)
        assertPrimaryAndSecondary(primary, secondary)
    }

    @Test fun closeKeepsUpdatedCardAndDoesNotJump() {
        open()
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        assertNull(host.controller.state.value.dialog)
        assertEquals(
            "price_compare",
            host.card
                .getValue("no_rebate_cause")
                .jsonPrimitive.content,
        )
        assertEquals(
            "trade.cancelled.rebate",
            host.controller.state.value.toast
                ?.key,
        )
    }

    @Test fun compareIsCenteredDialogAndExplanationAbsentWithoutConfiguration() {
        open()
        rule.onNodeWithTag(TradeTags.COMPARE).assertExists()
        val button = rule.button("仍去购买（无返利）").fetchSemanticsNode()
        val density = button.layoutInfo.density.density
        // 底部半屏的按钮贴近956底端；居中弹窗的两按钮均应落在页面中段。
        assertTrue("比价确认不能仍为底部半屏", button.boundsInWindow.bottom / density < 800f)
        rule.onNodeWithText("查看说明").assertDoesNotExist()
        host.counts(open = 1)
    }

    @Test fun explanationUsesConfiguredArticleAndDoesNotConfirmPurchase() {
        host.explanation = true
        open()
        rule.onNodeWithText("查看说明", substring = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        host.counts(open = 1, callbacks = 0 to 1)
        PurchaseVisualCallbacks.assertCalls(host.controller, "viewExplanation")
        assertEquals("HelpArticle", host.routes.single().first)
        assertEquals(
            "compare-fixture",
            host.routes
                .single()
                .second
                .getValue("article_id")
                .jsonPrimitive.content,
        )
        assertEquals(0, host.reports.size)
    }
}
