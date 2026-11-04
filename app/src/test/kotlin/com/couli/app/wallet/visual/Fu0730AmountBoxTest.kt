package com.couli.app.wallet.visual

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0730 K3（F-AND-83，native 主线 2026-10-31 三端口径）：真实提现页金额框（WithdrawScreen → AmountField → WalletField 的 BasicTextField），
 * 先输入 12.34，再追加被过滤拒绝的字符（第三位小数 5、第二个小数点、字母、全角数字、超长整数），框内**显示文字**（EditableText）仍是 12.34，
 * 控制器金额（state.amountText / inputFen）一致；删除 / 合法改写后两边一起变。
 *
 * 依据 iOS origin/main `UITests/WithdrawVisualAmountUITests.swift:58-76`（testRejectedCharsDoNotStayInBox）与
 * `Packages/Features/Sources/Wallet/WithdrawVisualParts.swift:35-60`（框内文字单独记、每次输入后对回金额）。
 *
 * 三条输入路径都走：语义插入（performTextInput，等同输入法提交）、硬件按键（performKeyInput）、整段替换（粘贴 / 自动填充）。
 * 资金侧只做调用计数（零改动护栏，底版应为绿）：被拒字符不多发估算、不发写请求；之后【提交】的金额 = 12.34 元（1234 分）恰一次。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0730AmountBoxTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    /** K3-1：语义插入（输入法提交）——12.34 之后逐个追加被拒字符，框内与金额都停在 12.34。 */
    @Test fun k3InsertedRejectedCharsDoNotStayInBox() {
        host.launch()
        focusBox()
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput("12.34")
        rule.waitForIdle()
        assertBoth("12.34", "先正：合法输入照常")
        listOf("5", ".", "a", "１", "¥", " 6").forEach { rejected ->
            rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput(rejected)
            rule.waitForIdle()
            assertBoth("12.34", "追加被拒的「$rejected」后")
        }
    }

    /** K3-2：连续两次同一个被拒字符（底版若只在第一次显示残留、第二次才对回，也要抓到）。 */
    @Test fun k3RepeatedRejectedCharStaysOut() {
        host.launch()
        focusBox()
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput("12.34")
        rule.waitForIdle()
        repeat(2) { round ->
            rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput("5")
            rule.waitForIdle()
            assertBoth("12.34", "第 ${round + 1} 次追加 5")
        }
        // 被拒之后合法改写照常（两边一起变）
        host.input("12.3")
        assertBoth("12.3", "合法改写")
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput("9")
        rule.waitForIdle()
        assertBoth("12.39", "再追加合法字符")
    }

    /** K3-3：硬件按键路径——12.34 后按 5、句点、字母 A，框内与金额都停在 12.34；退格后一起变成 12.3。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun k3KeyPressedRejectedCharsDoNotStayInBox() {
        host.launch()
        focusBox()
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput("12.34")
        rule.waitForIdle()
        assertBoth("12.34", "先正")
        listOf(Key.Five, Key.Period, Key.A).forEach { key ->
            rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performKeyInput { pressKey(key) }
            rule.waitForIdle()
            assertBoth("12.34", "按键 $key 后")
        }
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertBoth("12.3", "退格后两边一起变")
    }

    /** K3-4：整段替换（粘贴 / 自动填充）成不合法的串——框内仍是原值，金额不变。 */
    @Test fun k3ReplacedRejectedTextDoesNotStayInBox() {
        host.launch()
        host.input("12.34")
        assertBoth("12.34", "先正")
        listOf("12.345", "12.3.4", "12a", "1234567890").forEach { bad ->
            rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextReplacement(bad)
            rule.waitForIdle()
            assertBoth("12.34", "整段换成被拒的「$bad」后")
        }
    }

    /**
     * K3-5：平台输入法路径（InputConnection，真机键盘走的就是这条）——12.34 后 commitText 被拒的 5、a，框内显示、控制器金额、
     * 输入法看到的光标前文字三者都是 12.34；再 deleteSurroundingText(1, 0) 删一个字，两边一起变成 12.3（输入法缓冲里若残留 12.345，
     * 删一个字会回到 12.34 而不是 12.3）。
     */
    @Test fun k3ImeCommittedRejectedCharsDoNotStayInBoxOrImeBuffer() {
        host.launch()
        focusBox()
        val ic = inputConnection()
        ic.commitText("12.34", 1)
        rule.waitForIdle()
        assertBoth("12.34", "先正：输入法提交合法金额")
        assertEquals("先正：输入法缓冲", "12.34", ic.getTextBeforeCursor(50, 0)?.toString())
        listOf("5", "a", ".").forEach { rejected ->
            ic.commitText(rejected, 1)
            rule.waitForIdle()
            assertBoth("12.34", "输入法提交被拒的「$rejected」后")
            assertEquals("输入法提交被拒的「$rejected」后：输入法缓冲须对回 12.34", "12.34", ic.getTextBeforeCursor(50, 0)?.toString())
        }
        ic.deleteSurroundingText(1, 0)
        rule.waitForIdle()
        assertBoth("12.3", "被拒之后删一个字")
    }

    /**
     * 零改动护栏（调用计数，底版应为绿）：被拒字符不触发估算（只有 12.34 那一次带金额的 rules），不发写请求、不生成幂等键；
     * 之后【提交】恰一次，金额 = 1234 分（控制器金额，不是框里残留的字）。
     */
    @Test fun k3RejectedCharsSendNoEstimateNoWriteAndSubmitUsesAcceptedAmount() {
        host.launch()
        focusBox()
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput("12.34")
        host.settleEstimate()
        val estimates = estimateQueries()
        assertEquals("12.34 恰一次带金额估算", listOf("amount_fen=1234"), estimates.map { it.substringAfter("?") })
        listOf("5", ".", "a").forEach { rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performTextInput(it) }
        host.settleEstimate()
        assertEquals("被拒字符不多发估算", estimates, estimateQueries())
        assertEquals("被拒字符不发写请求", 0, host.api.writes().size)
        assertEquals("被拒字符不生成幂等键", 0, host.keys)
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        val write = host.api.writes().single()
        assertEquals("createWithdrawal", write.operationId)
        assertEquals("{\"amount_fen\":1234}", write.body)
    }

    private fun focusBox() {
        rule.onNodeWithTag(WalletTags.WITHDRAW_INPUT).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
    }

    /** 当前聚焦的 Compose 宿主视图向输入法给出的 InputConnection（与真机键盘同一入口）。 */
    private fun inputConnection(): InputConnection {
        var found: InputConnection? = null
        rule.runOnIdle {
            val activity =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .single()
            val editor = textEditor(activity.window.decorView)
            found = checkNotNull(editor) { "前置：金额框聚焦后应有接收输入法的视图" }.onCreateInputConnection(EditorInfo())
        }
        return checkNotNull(found) { "前置：InputConnection" }
    }

    private fun textEditor(view: View): View? =
        when {
            view.onCheckIsTextEditor() && view.hasFocus() -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { textEditor(view.getChildAt(it)) }
            else -> null
        }

    private fun estimateQueries(): List<String> =
        host.api.calls
            .filter { it.operationId == "getWithdrawRules" && it.query != null }
            .map { "getWithdrawRules?" + it.query }

    private fun boxText(): String =
        rule
            .onNodeWithTag(WalletTags.WITHDRAW_INPUT, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun assertBoth(
        expected: String,
        what: String,
    ) {
        val state = host.model.controller.state.value
        assertEquals("$what：控制器金额", expected, state.amountText)
        assertEquals("$what：框内显示文字须与控制器金额一致（不留被拒字符）", expected, boxText())
    }
}
