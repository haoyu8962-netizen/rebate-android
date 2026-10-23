package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
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

/** 四宿主之二：收款账号页（PayoutAccountScreen:213）共用同一半屏——新版式与资金行为一起回归。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualPayoutTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    @Test fun payoutSheetUsesTheNewLayout() {
        kit.open(payout = true)
        val title = kit.titleNode(PAYOUT_ACTION)
        assertTrue("先正：收款账号页半屏标题", title.drawnText().isNotEmpty())
        assertFalse("说明在标题区（W65）", kit.sentNode().isAncestorTagged(WalletTags.SHEET_MIDDLE))
        val countdown = kit.countdownNode()
        assertEquals(StepUpVisualKit.FULL_COUNTDOWN, countdown.drawnText())
        assertTrue("倒计时在中段（W67）", countdown.isAncestorTagged(WalletTags.SHEET_MIDDLE))
        val label = rule.onAllNodes(kit.inSheet(hasText(WalletDraftTexts.text("step_up.code_label"))), true).fetchSemanticsNodes()
        assertTrue("「验证码」只作读屏标签（W66）", label.isEmpty())
    }

    @Test fun payoutSixDigitsDoNotSaveAndExplicitVerifyReplaysTheSameSaveOnce() {
        kit.open(payout = true)
        val first = host.api.writes().first()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        rule.waitForIdle()
        assertEquals("输满不自动验证", listOf("savePayoutAccount", "sendSmsCode"), host.api.writes().map { it.operationId })
        host.api.writeHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("savePayoutAccount", "sendSmsCode", "stepUp", "savePayoutAccount"), host.api.writes().map { it.operationId })
        val verify =
            WithdrawVisualFixtures.obj(
                host.api.calls
                    .single { it.operationId == "stepUp" }
                    .body
                    .orEmpty(),
            )
        assertEquals(PAYOUT_ACTION, verify["action"]?.jsonPrimitive?.content)
        val replay = host.api.writes().last()
        assertEquals("原键", first.idempotencyKey, replay.idempotencyKey)
        assertEquals("原体", first.body, replay.body)
        assertEquals("fixture-verified-token", replay.stepUpToken)
    }

    @Test fun payoutCloseWritesNothing() {
        kit.open(payout = true)
        rule.onNodeWithTag(WalletTags.STEP_UP_CLOSE, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(host.flow.stepUp.showing)
        assertEquals(listOf("savePayoutAccount", "sendSmsCode"), host.api.writes().map { it.operationId })
    }

    private companion object {
        const val PAYOUT_ACTION = "payout_account_change"
    }
}
