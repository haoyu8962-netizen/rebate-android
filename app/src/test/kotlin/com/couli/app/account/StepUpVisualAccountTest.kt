package com.couli.app.account

import android.app.Application
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.AccountTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 四宿主之三、四（APP-09v-d / G-W19「换手机号、注销两页同变并回归」）：经真实设置页、真实 10003 打开共用半屏；新版式（说明在标题区、倒计时
 * 「秒后可重新获取」在中段、「验证码」只读屏）+ 资金 / 身份行为（输满不验证、显式验证恰一次、原键原体重放一次）。
 * 另核对 AccountHarness:993 的 STEP2_COUNTDOWN_TEXT 属于换号第 2 步（phone_change.resend_in），不随半屏措辞变化。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualAccountTest : AccountHarness() {
    @Test fun phoneChangeSheetUsesTheNewLayout() {
        openPhoneChangeSheet()
        assertNewLayout()
    }

    @Test fun phoneChangeSheetKeepsVerifyCountAndReplaysTheOriginalKeyOnce() {
        openPhoneChangeSheet()
        val verifies = all("POST", STEP_UP).size
        type(STEP_UP_CODE, CODE_OK)
        assertEquals("输满 6 位不自动验证", verifies, all("POST", STEP_UP).size)
        tap(STEP_UP_SUBMIT)
        idleUntil("换号完成") { has(AccountTags.PHONE_DONE) }
        assertEquals("显式验证恰好一次", verifies + 1, all("POST", STEP_UP).size)
        assertEquals("phone_change", body(all("POST", STEP_UP).last())["action"].toString().trim('"'))
        val posts = all("POST", BIND)
        assertEquals("首发 10003 + 重放一次", 2, posts.size)
        assertEquals("沿用原键", posts[0].key, posts[1].key)
        assertEquals("原体", posts[0].body, posts[1].body)
    }

    @Test fun deletionSheetUsesTheNewLayout() {
        openDeletionSheet()
        assertNewLayout()
    }

    @Test fun deletionSheetKeepsVerifyCountAndReplaysTheOriginalKeyOnce() {
        openDeletionSheet()
        type(STEP_UP_CODE, CODE_OK)
        assertEquals("输满 6 位不自动验证", 0, all("POST", STEP_UP).size)
        tap(STEP_UP_SUBMIT)
        idleUntil("冷静期") { has(AccountTags.DELETE_COOLING) }
        assertEquals("显式验证恰好一次", 1, all("POST", STEP_UP).size)
        assertEquals("account_deletion", body(all("POST", STEP_UP).single())["action"].toString().trim('"'))
        val applies = all("POST", DELETION)
        assertEquals("首发 10003 + 重放一次", 2, applies.size)
        assertEquals("同一个键", 1, applies.map { it.key }.toSet().size)
    }

    @Test fun deletionSheetCloseWritesNothing() {
        openDeletionSheet()
        tap(STEP_UP_CLOSE)
        assertFalse("关闭收起半屏", has(STEP_UP_SHEET))
        assertEquals("关闭不验证", 0, all("POST", STEP_UP).size)
        assertEquals("关闭不重放", 1, all("POST", DELETION).size)
    }

    @Test fun phoneChangeHostSnapshot() {
        openPhoneChangeSheet()
        captureScreenRoboImage("src/test/snapshots/stepup-v/WithdrawStepUp-v2-host-phone-change-light.png")
    }

    @Test fun deletionHostSnapshot() {
        openDeletionSheet()
        captureScreenRoboImage("src/test/snapshots/stepup-v/WithdrawStepUp-v2-host-deletion-light.png")
    }

    private fun openPhoneChangeSheet() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        assertTrue("换号第 2 步自己的倒计时仍是 phone_change.resend_in（AccountHarness:993 不失效）", hasText(STEP2_COUNTDOWN_TEXT))
        serverState.validTokens.clear()
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("二次验证面板（发码已有结论）") { stepUpReady() }
    }

    private fun openDeletionSheet() {
        launch()
        openDeleteFromSettings()
        checkWaiverAndSubmit()
        idleUntil("二次验证面板（发码已有结论）") { stepUpReady() }
    }

    private fun assertNewLayout() {
        val sheet = hasAnyAncestor(hasTestTag(STEP_UP_SHEET))
        val middle = hasAnyAncestor(hasTestTag(SHEET_MIDDLE))
        val countdown = textMatcher(COUNTDOWN_TAIL, substring = true) and sheet
        assertTrue("半屏倒计时取 login.code.countdown「秒后可重新获取」（W67）", count(countdown) > 0)
        assertTrue("倒计时在中段格下一行（W67）", count(countdown and middle) > 0)
        val sent = textMatcher(SENT) and sheet
        assertTrue("先正：有说明句", count(sent) > 0)
        assertEquals("说明在标题区，不在中段（W65）", 0, count(sent and middle))
        assertEquals("「验证码」只作读屏标签（W66）", 0, count(textMatcher(CODE_LABEL) and sheet))
        assertFalse("不显示号码（G16）", count(textMatcher(PHONE_OLD.takeLast(4), substring = true) and sheet) > 0)
    }

    private fun count(matcher: SemanticsMatcher): Int = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size
}

private const val SHEET_MIDDLE = "sheet_middle"
private const val COUNTDOWN_TAIL = "秒后可重新获取"
private const val SENT = "验证码已发送至你绑定的手机号"
private const val CODE_LABEL = "验证码"
