package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/**
 * 资金红线（brief §3 / G-W19）：发码 / 验证 / 关闭的发起条件、次数、顺序、身份票据不变；**不新增提交入口**（输满 6 位、输入法完成键都不验证）。
 * 这些用例对底版应为绿，用来防止改输入方式时顺手改了资金行为。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    @Test fun sixDigitsTypedOneByOneOrPastedOrImeDoneNeverVerify() {
        kit.open()
        val code = rule.onNodeWithTag(WalletTags.STEP_UP_CODE)
        "123456".forEach { code.performTextInput(it.toString()) }
        rule.waitForIdle()
        assertEquals("先正：6 位已输入", "123456", fieldText())
        assertEquals("逐位输满不自动验证", 0, verifies())
        code.performTextReplacement("654321")
        rule.waitForIdle()
        assertEquals("粘贴 / 自动填充满 6 位不自动验证", 0, verifies())
        runCatching { code.performImeAction() }
        rule.waitForIdle()
        assertEquals("输入法完成键不是提交入口", 0, verifies())
        assertEquals(listOf("createWithdrawal", "sendSmsCode"), host.api.writes().map { it.operationId })
    }

    @Test fun explicitVerifyIsOnceWithPanelOwnerThenReplaysTheSameKeyOnce() {
        kit.open()
        val first = host.api.writes().first()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        host.api.writeHold = CompletableDeferred()
        // v3：验证答复先挂住，第二次点击落在 VERIFYING 期间（半屏仍在）。
        // 不挂住时验证立即成功、半屏收起，第二次点击找不到节点（底版同样红，属测试时序错）。
        host.api.stepUpHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("验证中连点只发一次验证、未重放", listOf("createWithdrawal", "sendSmsCode", "stepUp"), host.api.writes().map { it.operationId })
        rule.runOnIdle { host.api.stepUpHold!!.complete(Unit) }
        rule.waitForIdle()
        assertEquals(listOf("createWithdrawal", "sendSmsCode", "stepUp", "createWithdrawal"), host.api.writes().map { it.operationId })
        val verify = host.api.calls.single { it.operationId == "stepUp" }
        val body = WithdrawVisualFixtures.obj(verify.body.orEmpty())
        assertEquals("withdraw", body["action"]?.jsonPrimitive?.content)
        assertEquals("123456", body["code"]?.jsonPrimitive?.content)
        assertEquals("验证带面板身份的账号", host.env.user, verify.expectedAccount)
        val replay = host.api.writes().last()
        assertEquals("原键重放", first.idempotencyKey, replay.idempotencyKey)
        assertEquals("原体重放", first.body, replay.body)
        assertEquals("fixture-verified-token", replay.stepUpToken)
        assertEquals("不生成新键", 1, host.keys)
    }

    @Test fun verifyingLocksInputAndSubmitUntilTheAnswerArrives() {
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        host.api.stepUpHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        assertTrue("验证中输入框不可改", code.config.getOrNull(SemanticsProperties.Disabled) != null)
        assertFalse("验证中【验证并提交】不可再点", kit.tagged(WalletTags.STEP_UP_SUBMIT).clickable())
        runCatching { rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("999999") }
        assertEquals("验证中改码无效", "123456", fieldText())
        rule.runOnIdle { host.api.stepUpHold!!.complete(Unit) }
        rule.waitForIdle()
        assertEquals(1, verifies())
    }

    @Test fun wrongCodeClearsRefocusesAndNextExplicitVerifyIsTheOnlySecondVerify() {
        host.api.stepUpAnswers += WithdrawVisualFixtures.failure(WRONG_CODE)
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("000000")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("清空输入", "", fieldText())
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        assertTrue("出错后焦点回到验证码输入（focusRequester 仍接在实际输入节点上）", code.config.getOrNull(SemanticsProperties.Focused) == true)
        assertEquals("验证码，已输入 0 位，共 6 位", code.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(""))
        val error = kit.tagged(WalletTags.STEP_UP_ERROR)
        val live =
            error.config.getOrNull(SemanticsProperties.LiveRegion)
                ?: error.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.LiveRegion) }
        assertEquals("错误仍 assertive 播报", LiveRegionMode.Assertive, live)
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        assertEquals("重输满 6 位仍不自动验证", 1, verifies())
        host.api.writeHold = CompletableDeferred()
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(2, verifies())
        assertEquals("一次重放", 2, host.api.calls.count { it.operationId == "createWithdrawal" })
        assertEquals(1, host.keys)
    }

    @Test fun closeButtonVerifiesNothingAndReopeningSharesTheCountdown() {
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CLOSE, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(host.flow.stepUp.showing)
        assertEquals(listOf("createWithdrawal", "sendSmsCode"), host.api.writes().map { it.operationId })
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        rule.waitForIdle()
        assertTrue(host.flow.stepUp.showing)
        assertEquals("重开面板不再发码（共享倒计时）", 1, host.api.calls.count { it.operationId == "sendSmsCode" })
    }

    @Test fun staleSessionPanelCallbacksDoNothing() {
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("123456")
        host.env.epoch += 1
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("会话已换：旧面板不以新身份验证", 0, verifies())
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("111111")
        assertEquals("旧面板输入不受理", "123456", fieldText())
        assertEquals(listOf("createWithdrawal", "sendSmsCode"), host.api.writes().map { it.operationId })
    }

    /** W65 / W67 落地后才成立（说明挪位、倒计时进中段）：底版应红。 */
    @Test fun readingOrderFollowsA11yFocusStepUp() {
        host.api.stepUpAnswers += WithdrawVisualFixtures.failure(WRONG_CODE)
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("000000")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val order =
            rule
                .onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.id }
        val ids =
            listOf(
                "标题" to kit.titleNode().id,
                "说明" to kit.sentNode().id,
                "验证码" to kit.tagged(WalletTags.STEP_UP_CODE).id,
                "错误" to kit.tagged(WalletTags.STEP_UP_ERROR).id,
                "倒计时" to kit.countdownNode().id,
                "提交" to kit.tagged(WalletTags.STEP_UP_SUBMIT).id,
            )
        val positions = ids.map { (name, id) -> name to order.indexOf(id) }
        assertEquals("读屏顺序 标题→说明→验证码→错误→倒计时→提交：$positions", positions.sortedBy { it.second }, positions)
    }

    private fun verifies() = host.api.calls.count { it.operationId == "stepUp" }

    private fun fieldText(): String =
        kit
            .tagged(WalletTags.STEP_UP_CODE)
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private companion object {
        const val WRONG_CODE = 20002
    }
}
