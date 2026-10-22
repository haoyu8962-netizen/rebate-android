package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun malformedBalanceNeverBecomesZeroAndRetryOnlyReadsOnce() {
        host.api.rules = WithdrawVisualFixtures.ok(JsonObject(WithdrawVisualFixtures.rules() + ("withdrawable_fen" to JsonNull)))
        host.launch()
        rule.onNodeWithTag(WalletTags.WITHDRAW_LOAD_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE).assertDoesNotExist()
        assertEquals(1, host.api.calls.count { it.operationId == "getWithdrawRules" })
        host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules())
        rule.onNodeWithText(WalletDraftTexts.text("wallet.retry")).performClick()
        rule.onNodeWithTag(WalletTags.WITHDRAW_BALANCE).assertIsDisplayed()
        assertEquals(2, host.api.calls.count { it.operationId == "getWithdrawRules" })
        assertEquals(0, host.api.writes().size)
    }
}
