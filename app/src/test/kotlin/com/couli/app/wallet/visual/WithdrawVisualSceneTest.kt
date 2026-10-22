package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 验证截图入口确实能到达状态；不调用任何截图 API、不写 PNG。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualSceneTest(
    private val scene: String,
    private val payout: Boolean,
) {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun fixtureReachesRealPageWithoutAutomaticFinancialWrite() {
        WithdrawVisualScenes(rule, host).show(scene, payout)
        rule.onNodeWithTag(if (payout) WalletTags.PAYOUT_PAGE else WalletTags.WITHDRAW_PAGE).assertExists()
        val writes = if (scene in setOf("risk", "submitting", "amount_error", "above_max", "name_error", "account_error")) 1 else 0
        assertEquals(scene, writes, host.api.writes().size)
        assertEquals(scene, if (scene == "still") 1 else 0, host.api.confirms.size)
        assertEquals(scene, if (scene in setOf("abandoned", "completed")) 1 else 0, host.api.abandons.size)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-payout={1}")
        fun cases(): List<Array<Any>> =
            listOf(
                "normal",
                "calculating",
                "estimate_failed",
                "help",
                "submitting",
                "risk",
                "pending",
                "busy",
                "still",
                "abandon",
                "abandoned",
                "completed",
                "loading",
                "error",
                "stress",
                "amount_error",
                "above_max",
                "name_error",
                "account_error",
            ).map { arrayOf<Any>(it, false) } +
                listOf("pending", "busy", "still", "abandon", "abandoned", "completed", "risk", "name_error", "account_error")
                    .map { arrayOf<Any>(it, true) }
    }
}
