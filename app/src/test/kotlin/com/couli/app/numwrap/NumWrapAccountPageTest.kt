package com.couli.app.numwrap

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.account.AccountVisualFixtures
import com.couli.app.account.AccountVisualPhoneHarness
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.me.account.AccountDraftTexts
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 账号侧真实页面接线（[AccountVisualPhoneHarness]：真实 AppShell + Router + MockWebServer）。
 * - FU-AND-ADAPT-CHECK ①（D10-1223 第 4、5 条）：绑号页与换号页（第 1、2 步）的发码入口在 200% 下三种状态都要像按钮——发码前「获取验证码」、
 *   倒计时（禁用）、倒计时走完后可再次点击。换号第 1、2 步三态都在这里（harness 假时钟推进 61 秒）；绑号页倒计时用的是系统时钟、这个 harness
 *   推不动，「走完后」一态改在 feature:login 的 NumWrapSendCodeTest 用真实 BindPhoneScreen + 注入时钟补齐。外观只看入口节点自身（[SendCodeLook]）。
 * - 默认字号：只保证「仍是右对齐的链接色光文字（没有围住文字的底色或描边）、按钮语义、≥48dp、一行、footnote 令牌」，不宣称更多。
 * - 判据 1 同类自查（Codex 复审 S1 ⑤）：绑号页手机号输入、换号待确认明细（AccountVisualParts.kt:490）、注销页金额两列（AccountVisualParts.kt:214、229）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapAccountPageTest : AccountVisualPhoneHarness() {
    // ---------------------------------------------------------------- 绑号页发码入口

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun bindGetCodeW320Font200Light() = bindLook(dark = false, state = BEFORE)

    @Test fun bindGetCodeW390Font200Light() = bindLook(dark = false, state = BEFORE)

    @Test fun bindGetCodeW390Font200Dark() = bindLook(dark = true, state = BEFORE)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun bindCountdownW320Font200Light() = bindLook(dark = false, state = COUNTDOWN)

    @Test fun bindCountdownW390Font200Dark() = bindLook(dark = true, state = COUNTDOWN)

    @Test fun bindGetCodeDefaultFontStaysARightAlignedPlainLink() {
        fontScale = 1f
        enterBind(typed = true)
        val where = "绑号页获取验证码（默认字号）"
        SendCodeLook.assertButtonSemantics(node(BindPhoneTags.GET_CODE).fetchSemanticsNode(), where)
        SendCodeLook.assertPlainText(composeRule, node(BindPhoneTags.GET_CODE).fetchSemanticsNode(), where)
        val text = composeRule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(BindPhoneTags.GET_CODE), useUnmergedTree = true)[0]
        val layout = NumWrap.layout(text)
        assertEquals("$where：文字一行", 1, layout.lineCount)
        assertEquals("$where：字号 footnote 令牌", CouliTypography.footnote.fontSize, layout.layoutInput.style.fontSize)
        assertEquals("$where：可点时仍是 text.link 色", palette.link, layout.layoutInput.style.color)
        val d = densityOf(BindPhoneTags.GET_CODE)
        assertEquals("$where：仍在验证码格右侧对齐", boxOf(BindPhoneTags.CODE).right / d, text.fetchSemanticsNode().boundsInRoot.right / d, 2f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun bindPhoneNumberW320Font200StaysOneRun() {
        fontScale = 2f
        enterBind(typed = true)
        val field = NumField(phone("bind"), "绑号页手机号输入（w320×200%）", NumWrap.within(BindPhoneTags.PHONE), NumToken(fontScale = 2f))
        NumWrap.assertField(composeRule, field)
    }

    // ---------------------------------------------------------------- 换号页发码入口与待确认明细

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun phoneChangeStep1GetCodeW320Font200Light() {
        fontScale = 2f
        enterStep1()
        look(AccountTags.PHONE_STEP1_SEND, "换号第 1 步获取验证码（w320×200%）")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun phoneChangeStep1CountdownW320Font200Light() {
        fontScale = 2f
        enterStep1(sent = true)
        look(AccountTags.PHONE_STEP1_SEND, "换号第 1 步倒计时（w320×200%）")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun phoneChangeStep1ResendReadyW320Font200Light() {
        fontScale = 2f
        enterStep1(sent = true)
        countdownEnds(AccountTags.PHONE_STEP1_SEND)
        look(AccountTags.PHONE_STEP1_SEND, "换号第 1 步倒计时走完（w320×200%）")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun phoneChangeStep2GetCodeW320Font200Light() {
        fontScale = 2f
        enterStep2(typed = true)
        look(AccountTags.PHONE_STEP2_SEND, "换号第 2 步获取验证码（w320×200%）")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun phoneChangeStep2CountdownW320Font200Light() {
        fontScale = 2f
        enterStep2(sent = true)
        look(AccountTags.PHONE_STEP2_SEND, "换号第 2 步倒计时（w320×200%）")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun phoneChangeStep2ResendReadyW320Font200Light() {
        fontScale = 2f
        enterStep2(sent = true)
        countdownEnds(AccountTags.PHONE_STEP2_SEND)
        look(AccountTags.PHONE_STEP2_SEND, "换号第 2 步倒计时走完（w320×200%）")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun phoneChangeStep2GetCodeW390Font200Dark() {
        dark = true
        fontScale = 2f
        enterStep2(typed = true)
        look(AccountTags.PHONE_STEP2_SEND, "换号第 2 步获取验证码（w390×200%，深）")
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun phoneChangePendingDetailW320Font200Light() {
        fontScale = 2f
        enterPhonePending()
        val masked = AccountVisualFixtures.pendingSummary("phone_change")
        val card = NumWrap.within(AccountTags.PENDING_CARD)
        val newLabel = AccountDraftTexts.text("pending_confirm.summary.new_phone")
        val timeLabel = AccountDraftTexts.text("pending_confirm.summary.submitted_at")
        // 新号在待确认卡里是两个字段，按标签取字段容器（范围与号码无关）：明细「提交的新手机号」行（标签之后、「提交时间」之前，整串）；
        // 说明句区（卡内第一个明细标签之前）。各自核完整出现 1 次与有无被拆开的副本
        val detail = NumWrap.segment(composeRule, card, newLabel, timeLabel)
        NumWrap.assertField(composeRule, NumField(masked, "换号待确认明细新号（w320×200%）", detail, FOOTNOTE, exact = true))
        val sentence = NumWrap.segment(composeRule, card, null, newLabel)
        NumWrap.assertField(composeRule, NumField(masked, "换号待确认说明句（w320×200%）", sentence, NumToken(fontScale = 2f)))
    }

    // ---------------------------------------------------------------- 注销页金额两列

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun deletionAmountsW320Font200Light() = deletionCase(dark = false)

    @Test
    @Config(qualifiers = "w390dp-h1000dp-xhdpi")
    fun deletionAmountsW390Font200Dark() = deletionCase(dark = true)

    // ---------------------------------------------------------------- D10-1265 注销页金额两列的横滑提示

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun deletionWithdrawableHintExtremeW320Font200() = deletionHint(MAX_FEN, AccountTags.DELETE_WITHDRAWABLE, expect = true)

    /** 预估收益的极端值单独成例，不被可提现的断言短路（r5 S2-1）。 */
    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun deletionEstimatedHintExtremeW320Font200() = deletionHint(MAX_FEN, AccountTags.DELETE_ESTIMATED, expect = true)

    /** 中等长度：半宽列放不下、整行放得下——两列必须先上下排，不准横滑。 */
    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun deletionWithdrawableHintMediumW320Font200() = deletionHint(MEDIUM_FEN, AccountTags.DELETE_WITHDRAWABLE, expect = false)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun deletionWithdrawableHintRegularW320Font200() = deletionHint(REGULAR_FEN, AccountTags.DELETE_WITHDRAWABLE, expect = false)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun deletionEstimatedHintRegularW320Font200() = deletionHint(REGULAR_FEN, AccountTags.DELETE_ESTIMATED, expect = false)

    /** 字段定位用各自的列；所在行检查用整个资金卡（两列一起看）。 */
    private fun deletionHint(
        fen: Long,
        column: String,
        expect: Boolean,
    ) {
        fontScale = 2f
        serverState.deletion = deletionFixture("none")
        val fields = Regex("\"(available_fen|withdrawable_fen|estimated_total_fen)\":\\d+")
        serverState.wallet = walletFixture("funds").replace(fields, "\"$1\":$fen")
        launch()
        openDeleteFromSettings()
        awaitFunds()
        node(column).performScrollTo()
        val name = if (column == AccountTags.DELETE_WITHDRAWABLE) "可提现" else "预估收益"
        val field = NumField(CouliFormat.money(fen), "注销页$name（w320×200%）", NumWrap.within(column), HEADING, exact = true)
        val funds = NumWrap.within(AccountTags.DELETE_FUNDS)
        val spec = NumHintSpec(field, row = funds, spoken = CouliFormat.moneySpoken(fen), expect = expect)
        NumHint.assertHint(composeRule, spec)
        NumWrap.assertField(composeRule, field)
    }

    private fun bindLook(
        dark: Boolean,
        state: String,
    ) {
        this.dark = dark
        fontScale = 2f
        enterBind(typed = true, sent = state != BEFORE)
        look(BindPhoneTags.GET_CODE, "绑号页$state（200%，${if (dark) "深" else "浅"}）")
    }

    private fun look(
        tag: String,
        where: String,
    ) {
        node(tag).performScrollTo()
        settle()
        SendCodeLook.assertLooksLikeButton(composeRule, node(tag).fetchSemanticsNode(), where)
        val text = composeRule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(tag), useUnmergedTree = true)[0]
        val layout = NumWrap.layout(text)
        assertEquals("$where：不许缩字（footnote 令牌）", CouliTypography.footnote.fontSize, layout.layoutInput.style.fontSize)
        assertEquals("$where：按 200% 排版", 2f, layout.layoutInput.density.fontScale, 0.001f)
    }

    /** 倒计时走完：推进假时钟、主线程与 Compose 时钟 61 秒，等入口重新可点。 */
    private fun countdownEnds(tag: String) {
        now += COUNTDOWN_MS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(COUNTDOWN_MS))
        composeRule.mainClock.advanceTimeBy(COUNTDOWN_MS)
        settle()
        idleUntil("倒计时走完后发码入口重新可点") { enabled(tag) }
    }

    private fun deletionCase(dark: Boolean) {
        this.dark = dark
        fontScale = 2f
        serverState.deletion = deletionFixture("none")
        val fields = Regex("\"(available_fen|withdrawable_fen|estimated_total_fen)\":\\d+")
        serverState.wallet = walletFixture("funds").replace(fields, "\"$1\":$MAX_FEN")
        launch()
        openDeleteFromSettings()
        awaitFunds()
        val where = "注销页资金两列（200%，${if (dark) "深" else "浅"}）"
        val amount = CouliFormat.money(MAX_FEN)
        node(AccountTags.DELETE_WITHDRAWABLE).performScrollTo()
        val left = NumWrap.within(AccountTags.DELETE_WITHDRAWABLE)
        val right = NumWrap.within(AccountTags.DELETE_ESTIMATED)
        NumWrap.assertField(composeRule, NumField(amount, "$where 可提现", left, HEADING, exact = true))
        NumWrap.assertField(composeRule, NumField(amount, "$where 预估收益", right, HEADING, exact = true))
    }

    private companion object {
        const val BEFORE = "获取验证码"
        const val COUNTDOWN = "倒计时"
        const val COUNTDOWN_MS = 61_000L

        /** ¥999999999.99。 */
        const val MAX_FEN = 99_999_999_999L

        /** ¥12.34：常规金额。 */
        const val REGULAR_FEN = 1_234L

        /** ¥99999.99：注销页 28/700 在 200% 下半宽列放不下、整行放得下。 */
        const val MEDIUM_FEN = 9_999_999L
        val FOOTNOTE = NumToken(CouliTypography.footnote.fontSize, 2f)
        val HEADING = NumToken(CouliTextStyles.amountHeading.fontSize, 2f)
    }
}
