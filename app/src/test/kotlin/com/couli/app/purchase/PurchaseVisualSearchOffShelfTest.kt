package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.search.SearchTags
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.TradeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualSearchOffShelfTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualAppHost(rule) }

    @After fun close() = host.close()

    @Test fun search30141DisablesBuyAndAnnouncesOffShelfPolitely() = offShelf(false)

    @Test fun searchAfterJumpOffShelfDisablesBuyAndAnnouncesPolitely() = offShelf(true)

    private fun offShelf(success: Boolean) {
        host.launchSearch()
        rule.onNodeWithTag(SearchTags.RESULTS).assertIsDisplayed()
        rule.onNode(hasText("领券购买") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            if (success) host.api.succeed(PurchaseVisualFixtures.response("off_shelf")) else host.api.fail(30141)
        }
        rule.waitForIdle()
        rule.onNode(hasText("领券购买") and hasClickAction()).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, host.api.calls(TradeOperations.OPEN_LINK).size)
        assertEquals(0, host.ports.jumps.size)
        assertEquals(0, host.ports.webAuthorizations.size + host.ports.sdkAuthorizations)
        assertEquals(0, host.ports.copies.size + host.ports.sdkOpens)
        for (op in listOf(TradeOperations.BIND_UNION, TradeOperations.GET_UNION_AUTH_URL, TradeOperations.LIST_UNION_BINDINGS)) {
            assertEquals(op, 0, host.api.calls(op).size)
        }
        PurchaseVisualCallbacks.assertCalls(host.controller)
        assertNull(host.controller.state.value.dialog)
        assertNull(host.controller.state.value.toast)
        rule.onNodeWithTag(TradeTags.TOAST).assertDoesNotExist()
        rule.onNodeWithText("商品已下架", useUnmergedTree = true).assertIsDisplayed()
        val polite = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), true)
        assertTrue("搜索下架须有商品已下架 polite 状态", polite.fetchSemanticsNodes().any { it.config.toString().contains("商品已下架") })
    }
}
