package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
 * APP-09v-d 评审 r1 修复（实现代理补，编排 2026-10-24 指令）：
 * S2-2 200% × w320 输错码时，格下一行的错误不被倒计时挤成一字一行（放不下时错误独占整行、倒计时换下一行靠右）；
 * S2-1 【重新获取】48 命中区的下缘真实点击仍发码（中段滚动裁切区不吃掉向下扩出的半截）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualR1FixTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun font200W320WrongCodeKeepsTheErrorReadableNotOneGlyphPerLine() {
        wrongCode()
        val text = errorText()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val countdown = kit.countdownNode()
        val layout = text.textLayout()
        val message = WalletDraftTexts.text(WRONG_CODE_KEY)
        assertEquals("先正：真 200%", 2f, layout.layoutInput.density.fontScale, .01f)
        assertTrue(
            "错误文字宽度不小于格子宽度的 60%（不被挤成窄条）：${text.boundsInRoot.width} / ${code.boundsInRoot.width}",
            text.boundsInRoot.width >= code.boundsInRoot.width * MIN_WIDTH_RATIO,
        )
        val perLine = message.length.toFloat() / layout.lineCount
        assertTrue("错误不按字折行：每行平均 ≥ $MIN_CHARS_PER_LINE 字（${layout.lineCount} 行）", perLine >= MIN_CHARS_PER_LINE)
        assertFalse("错误不截断", layout.hasVisualOverflow)
        text.assertNotClipped("错误文字")
        rule.onNode(byId(countdown.id), true).scrollIfPossible()
        val fresh = kit.node(byId(countdown.id), "倒计时")
        fresh.assertNotClipped("倒计时")
        assertTrue("错误与倒计时不重叠", !text.boundsInRoot.overlaps(fresh.boundsInRoot))
        assertTrue("错误在倒计时之前（读屏顺序）", text.boundsInRoot.top <= fresh.boundsInRoot.top)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 2f)
    fun font200W320WrongCodeSnapshot() {
        wrongCode()
        rule.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/stepup-v/WithdrawStepUp-v2-font200-w320-error-light.png")
    }

    @Test fun resendBottomEdgeOutsideTheVisualLineStillSendsExactlyOnce() {
        host.api.smsAnswers += WithdrawVisualFixtures.failure()
        kit.open()
        val resend = kit.tagged(WalletTags.STEP_UP_RESEND)
        assertTrue("先正：【重新获取】可点", resend.clickable())
        assertTrue("先正：在中段", resend.isAncestorTagged(WalletTags.SHEET_MIDDLE))
        val d = resend.density()
        val touch = resend.touchBoundsInRoot
        assertTrue("命中区高 ≥ 48dp：$touch", touch.height >= 48 * d)
        val point = Offset(touch.center.x, touch.bottom - d)
        assertFalse("下缘点落在视觉行之外（扩展命中区）", resend.unclipped().contains(point))
        assertFalse("下缘点不在【验证并提交】上", kit.tagged(WalletTags.STEP_UP_SUBMIT).touchBoundsInRoot.contains(point))
        val sheet = kit.tagged(WalletTags.STEP_UP_SHEET)
        rule.onNodeWithTag(WalletTags.STEP_UP_SHEET).performTouchInput { click(point - sheet.boundsInRoot.topLeft) }
        rule.waitForIdle()
        assertEquals("下缘真实点击恰好再发一次码", 2, host.api.calls.count { it.operationId == "sendSmsCode" })
        assertEquals("不验证、不重放", listOf("createWithdrawal", "sendSmsCode", "sendSmsCode"), host.api.writes().map { it.operationId })
    }

    private fun wrongCode() {
        host.api.stepUpAnswers += WithdrawVisualFixtures.failure(WRONG_CODE)
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE, useUnmergedTree = true).performTextReplacement("000000")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).scrollIfPossible().performClick()
        rule.waitForIdle()
        assertEquals("先正：一次验证", 1, host.api.calls.count { it.operationId == "stepUp" })
    }

    private fun errorText(): SemanticsNode =
        kit.node(
            hasText(WalletDraftTexts.text(WRONG_CODE_KEY), substring = true) and
                (hasTestTag(WalletTags.STEP_UP_ERROR) or hasAnyAncestor(hasTestTag(WalletTags.STEP_UP_ERROR))),
            "错误文字",
        )

    /** 降级时整块可滚、三段时中段可滚；底部操作区本来固定可见，没有滚动祖先。 */
    private fun SemanticsNodeInteraction.scrollIfPossible(): SemanticsNodeInteraction = apply { runCatching { performScrollTo() } }

    private fun byId(id: Int) = SemanticsMatcher("semantics id $id") { it.id == id }

    private companion object {
        const val WRONG_CODE = 20002
        const val WRONG_CODE_KEY = "error.20002"
        const val MIN_WIDTH_RATIO = 0.6f
        const val MIN_CHARS_PER_LINE = 4f
    }
}
