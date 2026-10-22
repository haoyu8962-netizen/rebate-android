package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiResult
import com.couli.core.wallet.OutcomeInput
import com.couli.core.wallet.SensitiveOutcome
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w43CalculatingStillAllowsOneSubmitAndKeepsInFlightKey() {
        host.api.estimateHold = CompletableDeferred()
        host.api.writeHold = CompletableDeferred()
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_CALCULATING).assertExists()
        val submit = rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT)
        submit.assertIsEnabled().performTouchInput { click(center) }
        rule.waitForIdle()
        val write = host.api.writes().single()
        assertEquals("createWithdrawal", write.operationId)
        assertEquals("{\"amount_fen\":1000}", write.body)
        assertEquals("withdraw-visual-user", write.expectedAccount)
        assertEquals(1, host.keys)
        assertEquals(write.idempotencyKey, host.saved.get<String>("withdraw.in_flight"))
        submit.assertIsNotEnabled().performClick()
        assertEquals(1, host.api.writes().size)
        assertEquals(0, host.api.confirms.size + host.api.abandons.size)
    }

    @Test fun w39AllAndQuickUseServerValuesWithoutWrites() {
        host.launch()
        rule.onNodeWithTag(WalletTags.WITHDRAW_ALL).performScrollTo().performTouchInput { click(center) }
        assertEquals("12", host.model.controller.state.value.amountText)
        rule.onNodeWithTag(WalletTags.WITHDRAW_QUICK_PREFIX + "500").performScrollTo().performTouchInput { click(center) }
        assertEquals("5", host.model.controller.state.value.amountText)
        assertEquals(0, host.api.writes().size)
        assertEquals(0, host.keys)
    }

    @Test fun w53EmptyZeroAndPartialAmountsRemainUnsubmittable() {
        host.launch()
        listOf("", "0", "5.", ".5").forEach { value ->
            host.input(value)
            rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).assertIsNotEnabled().performClick()
        }
        assertEquals(0, host.api.writes().size)
        assertEquals(0, host.keys)
    }

    @Test fun w53OldSubmitCallbackCannotUseNewIdentity() {
        host.launch()
        host.input()
        val callback =
            rule
                .onNodeWithTag(WalletTags.WITHDRAW_SUBMIT)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        rule.runOnIdle {
            host.env.epoch++
            callback()
        }
        assertEquals(0, host.api.writes().size)
        assertEquals(0, host.keys)
    }

    @Test fun successStillNavigatesOnceAndReleasesNoSecondWrite() {
        host.api.write = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.obj("""{"withdrawal_id":"W_VISUAL_C"}"""))
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, host.api.writes().size)
        assertEquals(listOf("WithdrawRecords"), host.env.replaced.map { it.first })
        assertEquals(
            "W_VISUAL_C",
            host.env.replaced
                .single()
                .second["withdrawal_id"]
                .toString()
                .trim('"'),
        )
        assertEquals(listOf("withdraw-visual-user" to 1L), host.env.dirty)
        assertEquals(true, host.saved.get<Boolean>("withdraw.succeeded"))
        assertTrue(host.model.controller.state.value.successPending)
    }

    @Test fun first40901KeepsOriginalKeyAndRequiresExplicitConfirm() {
        host.api.write = WithdrawVisualFixtures.failure(40901)
        host.api.writeHold = CompletableDeferred()
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        val original =
            host.api
                .writes()
                .single()
                .idempotencyKey!!
        host.api.pending = WithdrawVisualFixtures.pending().copy(key = original)
        rule.runOnIdle { host.api.writeHold!!.complete(Unit) }
        rule.waitForIdle()
        assertEquals(original, host.saved.get<String>("withdraw.in_flight"))
        assertEquals(
            original,
            host.flow.state.value.pending
                ?.key,
        )
        assertEquals(1, host.api.writes().size)
        assertEquals(0, host.api.confirms.size)
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).performScrollTo().performClick()
        assertEquals(listOf(original), host.api.confirms.map { it.key })
        assertEquals(1, host.keys)
    }

    @Test fun businessErrorClassificationAndKeyReleaseRemainUnchanged() {
        host.api.write = WithdrawVisualFixtures.failure(30301)
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        rule.waitForIdle()
        assertNotNull(host.flow.state.value.error)
        assertEquals(null, host.flow.state.value.fieldErrorKey)
        assertEquals(null, host.saved.get<String>("withdraw.in_flight"))
        assertEquals(
            "error.30301",
            host.flow.state.value.error
                ?.key,
        )
        val first =
            host.api
                .writes()
                .single()
                .idempotencyKey
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        assertEquals(2, host.api.writes().size)
        assertFalse(
            first ==
                host.api
                    .writes()
                    .last()
                    .idempotencyKey,
        )
    }

    @Test fun errorPlacementMustNotReclassifyBusinessOutcomesAsFieldErrors() {
        listOf("withdraw" to 30301, "withdraw" to 30303, "payout_account" to 30307, "payout_account" to 30308).forEach { (op, code) ->
            val outcome = SensitiveOutcome.of(op, "submit", false, OutcomeInput(OutcomeInput.ERROR, code))
            val expected = if (code == 30307) SensitiveOutcome.GUIDE else SensitiveOutcome.SHOW_ERROR_KEY_ENDS
            assertEquals("$op/$code：保留底版分类", expected, outcome.actionRaw)
            assertTrue(outcome.keyEnds)
            assertTrue(outcome.nextSubmitNewKey)
            assertEquals(emptyList<String>(), outcome.fields)
        }
    }
}
