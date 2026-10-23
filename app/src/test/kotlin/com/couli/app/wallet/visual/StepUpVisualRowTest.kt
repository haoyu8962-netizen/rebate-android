package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
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
 * APP-09v-d W67：格下一行——左错误（14 红字 + 图标）、右「{n} 秒后可重新获取」/【重新获取】；可用条件与 resend(owner) 原样；
 * 命中区 48 不撑高视觉行、不与关闭 / 提交重叠（a/b/c 评审抓过的问题）。行为计数用例与几何用例分开，几何红灯不遮住计数检查。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualRowTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    @Test fun w67CountdownUsesLoginCodeCountdownWording() {
        kit.open()
        val countdown = kit.countdownNode()
        assertEquals("倒计时取 login.code.countdown 草稿（G-W19）", StepUpVisualKit.FULL_COUNTDOWN, countdown.drawnText())
        assertEquals(WalletDraftTexts.format("login.code.countdown", mapOf("n" to "60")), countdown.drawnText())
        assertFalse("等待中倒计时不是可点按钮", countdown.clickable())
        val style = countdown.textLayout().layoutInput.style
        assertEquals("倒计时 14", 14f, style.fontSize.value, .01f)
        assertSameColor("倒计时次文字色", kit.tokens().secondary, style.color)
    }

    @Test fun w67CountdownSitsRightUnderTheCellsInTheMiddleNotTheFooter() {
        kit.open()
        val countdown = kit.countdownNode()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val submit = kit.tagged(WalletTags.STEP_UP_SUBMIT)
        val d = code.density()
        assertTrue("倒计时行放中段（W67），不在底部操作区", countdown.isAncestorTagged(WalletTags.SHEET_MIDDLE))
        assertTrue("在格子下方", countdown.boundsInRoot.top >= code.boundsInRoot.bottom)
        assertTrue("紧贴格下（≤ 16dp）", (countdown.boundsInRoot.top - code.boundsInRoot.bottom) / d <= 16f)
        assertTrue("在【验证并提交】上方", countdown.boundsInRoot.bottom <= submit.boundsInRoot.top)
        assertEquals("右对齐到格子右缘", code.boundsInRoot.right / d, countdown.boundsInRoot.right / d, 2f)
        countdown.assertNotClipped("倒计时")
    }

    @Test fun w67ErrorAndCountdownShareOneRowErrorLeft() {
        val error = wrongCode()
        val text = errorText()
        val countdown = kit.countdownNode()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val d = code.density()
        assertTrue("错误在中段", error.isAncestorTagged(WalletTags.SHEET_MIDDLE))
        assertTrue(
            "错误与倒计时同一行（纵向重叠）：${text.boundsInRoot} / ${countdown.boundsInRoot}",
            text.boundsInRoot.top < countdown.boundsInRoot.bottom && countdown.boundsInRoot.top < text.boundsInRoot.bottom,
        )
        assertTrue("错误在左、倒计时在右", text.boundsInRoot.right <= countdown.boundsInRoot.left + d)
        assertTrue("错误行在格子下方", text.boundsInRoot.top >= code.boundsInRoot.bottom)
        text.assertNotClipped("错误文字")
        countdown.assertNotClipped("倒计时")
    }

    @Test fun w67ErrorIs14RedWithLeadingIconInk() {
        wrongCode()
        val text = errorText()
        val style = text.textLayout().layoutInput.style
        assertEquals("错误 14（W67）", 14f, style.fontSize.value, .01f)
        assertSameColor("错误红字", kit.tokens().error, style.color)
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val shot = kit.shot(text)
        val t = shot.px(text.boundsInRoot)
        val left = shot.px(code.boundsInRoot).left
        val red =
            (left.toInt() until t.left.toInt()).sumOf { x ->
                (t.top.toInt() until t.bottom.toInt()).count { y -> shot.near(x, y, kit.tokens().error, ICON_TOLERANCE) }
            }
        assertTrue("错误文字左侧要有错误色图标墨迹（W67），实际 $red 像素", red >= MIN_ICON_INK)
    }

    @Test fun w67ResendAfterFailedSendIsInTheRowWith48TargetNotStretchingTheLine() {
        host.api.smsAnswers += WithdrawVisualFixtures.failure()
        kit.open()
        val resend = kit.tagged(WalletTags.STEP_UP_RESEND)
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val d = resend.density()
        assertEquals("先正：发码失败后出【重新获取】", WalletDraftTexts.text("step_up.resend"), resendLabel(resend))
        assertTrue("先正：可点", resend.clickable())
        assertTrue("【重新获取】在中段格下一行（W67）", resend.isAncestorTagged(WalletTags.SHEET_MIDDLE))
        assertTrue(resend.boundsInRoot.top >= code.boundsInRoot.bottom)
        val touch = resend.touchBoundsInRoot
        assertTrue("命中区 ≥ 48×48dp：$touch", touch.width >= 48 * d && touch.height >= 48 * d)
        val visual = resend.unclipped()
        assertTrue("视觉行不被 48 命中区撑高（≤ 28dp，画板行高 21）：${visual.height / d}dp", visual.height / d <= 28f)
        listOf(WalletTags.STEP_UP_CLOSE, WalletTags.STEP_UP_SUBMIT).forEach {
            assertSeparate(touch, kit.tagged(it).touchBoundsInRoot, it)
        }
    }

    @Test fun w67ResendTopEdgeOutsideTheVisualLineStillSendsExactlyOnce() {
        host.api.smsAnswers += WithdrawVisualFixtures.failure()
        kit.open()
        val resend = kit.tagged(WalletTags.STEP_UP_RESEND)
        val d = resend.density()
        val touch = resend.touchBoundsInRoot
        val point = Offset(touch.center.x, touch.top + d)
        val outside = !resend.unclipped().contains(point)
        val sheet = kit.tagged(WalletTags.STEP_UP_SHEET)
        rule.onNodeWithTag(WalletTags.STEP_UP_SHEET).performTouchInput { click(point - sheet.boundsInRoot.topLeft) }
        rule.waitForIdle()
        assertEquals("命中区上缘点击恰好再发一次码", 2, sends().size)
        assertTrue("上缘点应落在视觉行之外（扩展命中区）", outside)
    }

    @Test fun w67ResendClickSendsOnceWithTheSameSlotAndNoVerifyOrWrite() {
        host.api.smsAnswers += WithdrawVisualFixtures.failure()
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_RESEND, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val again = rule.onAllNodesWithTag(WalletTags.STEP_UP_RESEND, true).fetchSemanticsNodes().firstOrNull()
        if (again?.clickable() == true) {
            rule.onNodeWithTag(WalletTags.STEP_UP_RESEND, useUnmergedTree = true).performClick()
            rule.waitForIdle()
        }
        val sends = sends()
        assertEquals("连点只再发一次", 2, sends.size)
        assertEquals("同一号码 / purpose / action", sends[0].body, sends[1].body)
        val body = WithdrawVisualFixtures.obj(sends[1].body ?: "{}")
        assertEquals("withdraw", body["action"]?.jsonPrimitive?.content)
        assertEquals("step_up", body["purpose"]?.jsonPrimitive?.content)
        assertEquals(sends[0].expectedAccount, sends[1].expectedAccount)
        assertEquals("不验证、不重放", listOf("createWithdrawal", "sendSmsCode", "sendSmsCode"), host.api.writes().map { it.operationId })
        assertEquals(1, host.keys)
    }

    @Test fun w67ResendStaysDisabledWhileSmsIsRateLimited() {
        host.api.smsAnswers += WithdrawVisualFixtures.failure(RATE_LIMITED)
        kit.open()
        val resend = kit.tagged(WalletTags.STEP_UP_RESEND)
        assertFalse("42901 限流期【重新获取】置灰（可用条件原样：smsBlocked / smsLimited / phase）", resend.clickable())
        rule.onNodeWithTag(WalletTags.STEP_UP_RESEND, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("置灰点击不发码", 1, sends().size)
    }

    private fun sends() = host.api.calls.filter { it.operationId == "sendSmsCode" }

    private fun wrongCode(): SemanticsNode {
        host.api.stepUpAnswers += WithdrawVisualFixtures.failure(WRONG_CODE)
        kit.open()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("000000")
        rule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("先正：一次验证", 1, host.api.calls.count { it.operationId == "stepUp" })
        return kit.tagged(WalletTags.STEP_UP_ERROR)
    }

    /** 错误文字节点（标签可能在含图标的整行上；取带原文的文字节点）。 */
    private fun errorText(): SemanticsNode {
        val message = WalletDraftTexts.text("error.20002")
        return kit.node(
            hasText(message, substring = true) and
                (hasTestTag(WalletTags.STEP_UP_ERROR) or hasAnyAncestor(hasTestTag(WalletTags.STEP_UP_ERROR))),
            "错误文字「$message」",
        )
    }

    private fun resendLabel(node: SemanticsNode): String {
        val own = node.drawnText()
        if (own.isNotEmpty()) return own
        return rule
            .onAllNodes(hasAnyAncestor(hasTestTag(WalletTags.STEP_UP_RESEND)) and hasText(WalletDraftTexts.text("step_up.resend")), true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.drawnText()
            .orEmpty()
    }

    private fun assertSeparate(
        first: Rect,
        second: Rect,
        what: String,
    ) {
        val overlap = first.intersect(second)
        assertTrue("【重新获取】命中区不能与 $what 重叠：$first / $second", overlap.width <= 0f || overlap.height <= 0f)
    }

    private companion object {
        const val WRONG_CODE = 20002
        const val RATE_LIMITED = 42901
        const val ICON_TOLERANCE = 60
        const val MIN_ICON_INK = 6
    }
}
