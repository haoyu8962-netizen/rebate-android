package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.TradeDraftTexts
import com.couli.feature.product.ProductTags
import com.couli.feature.trade.TradeOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1050dp-xhdpi")
class PurchaseVisualNotInstalledTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualAppHost(rule) }

    @After fun close() = host.close()

    private fun counts(
        open: Int,
        copy: Int,
    ) {
        assertEquals(open, host.api.calls(TradeOperations.OPEN_LINK).size)
        assertEquals(copy, host.ports.copies.size)
        assertEquals(0, host.ports.webAuthorizations.size + host.ports.sdkAuthorizations)
        assertEquals(0, host.api.calls(TradeOperations.BIND_UNION).size)
        assertEquals(0, host.api.calls(TradeOperations.GET_UNION_AUTH_URL).size)
        assertEquals(0, host.api.calls(TradeOperations.LIST_UNION_BINDINGS).size)
    }

    @Test fun taobaoCopyPathShowsPersistentExplanationAndCopyButtonFromRealAppSlots() {
        host.launch()
        host.buy()
        host.answer()
        counts(1, 1)
        assertEquals(0, host.ports.jumps.size)
        rule.onNodeWithText("复制淘口令").assertIsDisplayed()
        val line = rule.onNodeWithText("安装淘宝后下单才有返利").assertIsDisplayed().fetchSemanticsNode()
        val button = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode()
        assertTrue("常驻说明在购买按钮上方", line.boundsInWindow.bottom <= button.boundsInWindow.top)
        assertEquals(
            "trade.not_installed.tpwd_copied",
            host.controller.state.value.toast
                ?.key,
        )
        rule.onNodeWithText("已复制淘口令").assertIsDisplayed()
        rule.runOnIdle { host.controller.consumeToast() }
        rule.waitForIdle()
        rule.onNodeWithText("安装淘宝后下单才有返利").assertIsDisplayed()
        counts(1, 1)
    }

    @Test fun pressingCopyButtonStillUsesNewOpenBeforeServerCopyStep() {
        host.launch()
        host.buy()
        host.answer()
        val oldKey =
            host.api
                .last()
                .call.idempotencyKey
        counts(1, 1)
        host.buy()
        counts(2, 1)
        assertNotEquals(
            oldKey,
            host.api
                .last()
                .call.idempotencyKey,
        )
        host.answer()
        counts(2, 2)
        assertEquals(listOf("FIXTURE-TPWD", "FIXTURE-TPWD"), host.ports.copies)
    }

    @Test fun taobaoWithoutCopyStepDoesNotClaimCopyAvailable() = absent("tb_coupon_range", "scheme", true)

    @Test fun taobaoFailedCopyDoesNotClaimCopyAvailable() = absent("tb_coupon_range", "copy_tpwd", false)

    @Test fun jdCopyStepDoesNotUseTaobaoCopyWording() = absent("jd_no_coupon_single", "copy_tpwd", true)

    @Test fun pddCopyStepDoesNotUseTaobaoCopyWording() = absent("pdd_coupon", "copy_tpwd", true)

    private fun absent(
        card: String,
        step: String,
        copySucceeds: Boolean,
    ) {
        host.ports.copySucceeds = copySucceeds
        host.launch(card)
        host.buy()
        host.answer(step)
        counts(1, if (step == "copy_tpwd") 1 else 0)
        assertEquals(true, host.controller.state.value.notInstalled)
        if (host.platform() != "taobao") {
            val platformName = TradeDraftTexts.text("platform.${host.platform()}")
            rule.onNodeWithText("安装${platformName}后下单才有返利").assertIsDisplayed()
        }
        rule.onNodeWithText("复制淘口令").assertDoesNotExist()
        rule.onNodeWithText("已复制淘口令").assertDoesNotExist()
        assertNotEquals(
            "trade.not_installed.tpwd_copied",
            host.controller.state.value.toast
                ?.key,
        )
    }

    @Test fun newDraftKeysHavePageOwnedText() {
        assertEquals("复制淘口令", TradeDraftTexts.text("trade.not_installed.copy_tpwd"))
        assertEquals("已复制淘口令", TradeDraftTexts.text("trade.not_installed.tpwd_copied"))
    }
}
