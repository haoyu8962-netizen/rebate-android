package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun nullWithdrawableIsAnErrorNotZeroBalance() =
        invalid(
            JsonObject(WalletVisualFixtures.summary() + ("withdrawable_fen" to JsonNull)),
        )

    @Test fun nullAvailableDoesNotFallBackToWithdrawable() =
        invalid(
            JsonObject(WalletVisualFixtures.summary() + ("available_fen" to JsonNull)),
        )

    @Test fun missingFrozenIsAnErrorNotZero() = invalid(JsonObject(WalletVisualFixtures.summary() - "frozen_fen"))

    @Test fun stringNegativeDoesNotEnableWithdraw() =
        invalid(
            JsonObject(WalletVisualFixtures.summary() + ("negative_fen" to JsonPrimitive("0"))),
        )

    @Test fun malformedRefreshRetainsOldAmountsDisablesWithdrawThenRecovers() {
        host.launch()
        host.refresh(WalletVisualFixtures.ok(JsonObject(emptyMap())))
        rule.walletText("¥12.34").assertExists()
        rule.walletText("¥56.78").assertExists()
        rule.walletTag(WalletTags.WALLET_REFRESH_ERROR).assertExists()
        rule.walletTag(WalletTags.WALLET_STALE).assertExists()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsNotEnabled().performClick()
        assertTrue(host.env.opened.isEmpty())
        assertEquals(0, rule.walletTextCount("网络不可用，以下为"))
        host.assertSummaryReads(2)
        host.refresh(WalletVisualFixtures.ok(WalletVisualFixtures.summary("frozen")))
        rule.walletTag(WalletTags.WALLET_REFRESH_ERROR).assertDoesNotExist()
        rule.walletTag(WalletTags.WALLET_STALE).assertDoesNotExist()
        rule.walletText("¥2.34").assertExists()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertIsEnabled()
        host.assertSummaryReads(3)
    }

    @Test fun serverFailureDoesNotUseDiskCacheOrInventZeroAndRetryReadsExactlyOnce() {
        host.seedCache()
        host.api.answer = WalletVisualFixtures.failure()
        host.launch()
        rule.walletTag(WalletTags.WALLET_ERROR).assertExists()
        rule.walletTag(WalletTags.WALLET_HERO).assertDoesNotExist()
        assertEquals(0, rule.walletTextCount("¥0"))
        assertTrue(rule.walletTextCount("000123") > 0)
        host.api.answer = WalletVisualFixtures.ok()
        rule.walletText("重试").performClick()
        rule.waitForIdle()
        rule.walletText("¥12.34").assertExists()
        host.assertSummaryReads(2)
    }

    @Test fun w17LoadingHasOneAccessibleNameAndNoFakeAmountsOrWithdraw() {
        host.api.hold = CompletableDeferred()
        host.launch()
        rule.onNodeWithContentDescription("加载中").assertExists()
        rule.walletTag(WalletTags.WALLET_HERO).assertDoesNotExist()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertDoesNotExist()
        assertEquals(0, rule.walletTextCount("加载中"))
        assertEquals(0, rule.walletTextCount("¥"))
        host.assertSummaryReads(1)
    }

    private fun invalid(summary: JsonObject) {
        host.api.answer = WalletVisualFixtures.ok(summary)
        host.launch()
        rule.walletTag(WalletTags.WALLET_ERROR).assertExists()
        rule.walletTag(WalletTags.WALLET_HERO).assertDoesNotExist()
        rule.walletTag(WalletTags.WALLET_WITHDRAW).assertDoesNotExist()
        assertEquals(0, rule.walletTextCount("¥0"))
        host.assertSummaryReads(1)
    }
}
