package com.couli.app.wallet.visual

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.couli.core.network.ApiResult
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/** 场景用生产事件到达；仅网络回复和本地未决 fixture 可控，不伪造 FlowState。 */
internal class WithdrawVisualScenes(
    private val rule: ComposeContentTestRule,
    private val host: WithdrawVisualTestHost,
) {
    fun show(
        scene: String,
        payout: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ): Boolean {
        prepare(scene, payout)
        host.launch(payout = payout, dark = dark, fontScale = fontScale, shell = true)
        when (scene) {
            "pending", "busy", "loading", "error" -> Unit
            "still" -> rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).performScrollTo().performClick()
            "abandon", "abandoned", "completed" -> abandon(scene)
            else -> if (!scene.startsWith("blocked_")) form(scene, payout)
        }
        rule.waitForIdle()
        if (scene == "submitting") assertEquals("submitting", host.flow.state.value.phase)
        if (scene == "risk") assertNotNull(host.flow.state.value.riskDialog)
        return scene in setOf("risk", "abandon", "help", "abandoned", "completed")
    }

    private fun prepare(
        scene: String,
        payout: Boolean,
    ) {
        when {
            scene.startsWith("blocked_") -> {
                host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules(scene))
            }

            scene in setOf("pending", "busy", "still", "abandon", "abandoned", "completed") -> {
                host.api.pending = WithdrawVisualFixtures.pending(payout, scene == "busy")
            }

            scene == "loading" -> {
                host.api.readHold = CompletableDeferred()
            }

            scene == "error" -> {
                host.api.rules = WithdrawVisualFixtures.failure()
            }

            scene == "calculating" -> {
                host.api.estimateHold = CompletableDeferred()
            }

            scene == "estimate_failed" -> {
                host.api.estimate = WithdrawVisualFixtures.failure()
            }

            scene == "submitting" -> {
                host.api.writeHold = CompletableDeferred()
            }

            scene == "stress" -> {
                host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules("stress"))
                host.api.estimate = host.api.rules
            }
        }
        if (scene == "sla_auto") host.env.sla = "预计几分钟内到账"
        if (scene == "amount_error") host.api.write = WithdrawVisualFixtures.failure(30301)
        if (scene == "above_max") host.api.write = WithdrawVisualFixtures.failure(30303, """{"reason":"above_max"}""")
        if (scene == "name_error") host.api.write = WithdrawVisualFixtures.failure(30307)
        if (scene == "account_error") host.api.write = WithdrawVisualFixtures.failure(30308)
    }

    private fun form(
        scene: String,
        payout: Boolean,
    ) {
        if (payout) {
            rule.onNodeWithTag(WalletTags.PAYOUT_CHANGE).performScrollTo().performClick()
            rule.onNodeWithTag(WalletTags.PAYOUT_LOGON).performScrollTo().performTextReplacement("13800005678")
            rule.onNodeWithTag(WalletTags.PAYOUT_PAYEE).performScrollTo().performTextReplacement("张三")
        } else {
            host.input(if (scene == "stress") "999999999" else "10")
            if (scene != "calculating") host.settleEstimate()
        }
        if (scene in setOf("risk", "submitting", "amount_error", "above_max", "name_error", "account_error")) {
            rule.onNodeWithTag(if (payout) WalletTags.PAYOUT_SAVE else WalletTags.WITHDRAW_SUBMIT).performClick()
        }
        if (scene == "help") {
            rule.onNodeWithContentDescription(WalletDraftTexts.text("wallet.help.fee_tax_label")).performScrollTo().performClick()
        }
    }

    private fun abandon(scene: String) {
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).performScrollTo().performClick()
        if (scene == "abandon") return
        val key = checkNotNull(host.api.pending).key
        host.api.pendingHold = CompletableDeferred()
        host.api.pendingAnswer =
            if (scene == "abandoned") {
                ApiResult.Abandoned(key, "visual-c-abc123")
            } else {
                WithdrawVisualFixtures.ok(WithdrawVisualFixtures.obj("""{"withdrawal_id":"W_VISUAL_C"}"""))
            }
        rule.onNodeWithText(WalletDraftTexts.text("pending_confirm.abandon.ok")).performClick()
        // 模拟网络层已完成该原键的持久化结算；仅测试 fixture 显式清场。
        rule.runOnIdle {
            host.api.pending = null
            host.api.pendingHold!!.complete(Unit)
        }
        rule.waitForIdle()
        assertEquals(1, host.api.abandons.size)
        assertEquals(0, host.api.writes().size)
    }
}
