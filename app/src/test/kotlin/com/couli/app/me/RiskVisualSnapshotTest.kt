@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-12w-c 截图入口（brief-c §2.1 / brief-c-android §4）：app/src/test/snapshots/risk-v/。宽 390、页面高取画板去掉设计说明后的高度（844；维持结果 1180、
 * 冻结维持 1240）、顶部 54dp 状态栏占位；三张深色稿出深色；200% × w390 / w320；840 一档；无稿态浅深各一。只核部分区块的加后缀：
 * -no-subline-GR08、-choice-not-compared-GR01、-01-frozen-no-until、-back-kept-GR12、-no-reason-row-GR11。每个入口先经真实触摸到达状态再截图；
 * 弹层用 captureScreenRoboImage。本轮只编写、不录制（录图由实现方在宿主锁内做）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一张基准一个入口
class RiskVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    // ---------------------------------------------------------------- 账号状态（有稿）

    @Test fun banned() = board("RiskNoticeBanned-v2", "RiskNoticeBanned-v2-no-subline-GR08-light")

    @Test fun bannedAppealing() = board("RiskNoticeBannedAppealing-v2", "RiskNoticeBannedAppealing-v2-choice-not-compared-GR01-light")

    @Test fun bannedUpheld() = board("RiskNoticeBannedUpheld-v2", "RiskNoticeBannedUpheld-v2-light")

    @Test fun frozen() = board("RiskNoticeFrozen", "RiskNoticeFrozen-light")

    @Test fun frozenAppealing() = board("RiskNoticeFrozenAppealing", "RiskNoticeFrozenAppealing-choice-not-compared-GR01-light")

    @Test fun frozenNoUntil() = board("RiskNoticeStates", "RiskNoticeStates-01-frozen-no-until-light")

    @Test fun riskDark() = board("RiskNoticeDark", "RiskNoticeDark-dark")

    @Test fun bannedDark() = board("RiskNoticeBanned-v2", "RiskNoticeBanned-v2-dark", dark = true)

    // ---------------------------------------------------------------- 账号状态（无稿，G-R25）

    @Test fun frozenUpheld() {
        host.launchRisk(risk = "risk_frozen", appeals = "appeals_upheld_frozen", withdrawals = "withdrawals_none")
        shot("risk-frozen-upheld-light")
    }

    @Test fun normal() {
        host.launchRisk(risk = "risk_normal", withdrawals = "withdrawals_none")
        shot("risk-normal-light")
    }

    @Test fun normalDark() {
        host.launchRisk(risk = "risk_normal", withdrawals = "withdrawals_none", dark = true)
        shot("risk-normal-dark")
    }

    @Test fun loadError() {
        host.api.answers[RiskVisualApi.GET_ME] = RiskVisualFixtures.failure()
        host.launchRisk()
        assertTrue(host.has(MeTags.RISK_ERROR))
        shot("risk-load-error-light")
    }

    @Test fun loadErrorDark() {
        host.api.answers[RiskVisualApi.GET_ME] = RiskVisualFixtures.failure()
        host.launchRisk(dark = true)
        shot("risk-load-error-dark")
    }

    @Test fun sectionsError() {
        host.api.answers[RiskVisualApi.LIST_APPEALS] = RiskVisualFixtures.failure()
        host.api.answers[RiskVisualApi.LIST_WITHDRAWALS] = RiskVisualFixtures.failure()
        host.launchRisk()
        host.settle("两个分区错误") { host.has(MeTags.RISK_APPEALS_ERROR) && host.has(MeTags.RISK_WITHDRAWALS_ERROR) }
        shot("risk-sections-error-light")
    }

    @Test fun logoutDialog() {
        host.launchRisk()
        host.tap(MeTags.RISK_LOGOUT)
        assertTrue(host.has(MeTags.RISK_LOGOUT_DIALOG))
        screen("risk-logout-dialog-light")
    }

    @Test fun logoutDialogDark() {
        host.launchRisk(dark = true)
        host.tap(MeTags.RISK_LOGOUT)
        screen("risk-logout-dialog-dark")
    }

    @Test fun internalBuild() {
        host.launchRisk(public = false)
        shot("risk-banned-internal-build-light")
    }

    // ---------------------------------------------------------------- 申诉（有稿）

    @Test fun appealSubmit() = board("Appeal", "Appeal-light")

    @Test fun appealSubmitFrozen() = board("AppealFrozen", "AppealFrozen-light")

    @Test fun appealProgress() = board("AppealProgress-v2", "AppealProgress-v2-no-reason-row-GR11-light")

    @Test fun appealProgressFrozen() = board("AppealProgressFrozen-v2", "AppealProgressFrozen-v2-no-reason-row-GR11-light")

    @Test fun appealLifted() = board("AppealResultLifted-v2", "AppealResultLifted-v2-back-kept-GR12-light")

    @Test fun appealLiftedFrozen() = board("AppealResultLiftedFrozen-v2", "AppealResultLiftedFrozen-v2-light")

    @Test
    @Config(qualifiers = "w390dp-h1180dp-xhdpi")
    fun appealUpheld() = board("AppealResultUpheld-v2", "AppealResultUpheld-v2-light")

    @Test
    @Config(qualifiers = "w390dp-h1240dp-xhdpi")
    fun appealUpheldFrozen() = board("AppealResultUpheldFrozen-v2", "AppealResultUpheldFrozen-v2-light")

    @Test fun appealDark() = board("AppealDark", "AppealDark-dark")

    @Test fun appealProgressDark() = board("AppealProgressDark", "AppealProgressDark-dark")

    @Test
    @Config(qualifiers = "w390dp-h1180dp-xhdpi")
    fun appealUpheldDark() = board("AppealResultUpheld-v2", "AppealResultUpheld-v2-dark", dark = true)

    // ---------------------------------------------------------------- 申诉（无稿，G-R25）

    @Test fun appealNeutral() {
        host.launchAppeal(risk = "risk_normal")
        shot("appeal-submit-neutral-light")
    }

    @Test fun appealTyped() {
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        shot("appeal-submit-typed-light")
    }

    @Test fun appealSubmitting() {
        host.holdSubmit()
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.ok(RiskVisualFixtures.submitted(RiskVisualFixtures.input))
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        host.tap(MeTags.APPEAL_SUBMIT)
        shot("appeal-submitting-light")
    }

    @Test fun appealFieldError() = submitError(RiskVisualFixtures.code("fieldCode"), "appeal-field-error-20001-light")

    @Test fun appealServerError() = submitError(RiskVisualFixtures.code("serverCode"), "appeal-error-50001-light")

    @Test fun appealRateLimited() = submitError(RiskVisualFixtures.code("rateCode"), "appeal-rate-limited-light")

    @Test fun appealLoadError() {
        host.api.answers[RiskVisualApi.LIST_APPEALS] = RiskVisualFixtures.failure()
        host.launchAppeal()
        assertTrue(host.has(MeTags.APPEAL_LOAD_ERROR))
        shot("appeal-load-error-light")
    }

    @Test fun appealLiftedHistory() {
        host.launchAppeal(risk = "risk_banned", appeals = "appeals_revoked_banned")
        shot("appeal-lifted-history-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1180dp-xhdpi")
    fun appealUpheldInternal() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = false)
        shot("appeal-upheld-internal-build-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1180dp-xhdpi")
    fun appealLogoutDialog() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        host.tap(MeTags.APPEAL_LOGOUT)
        screen("appeal-logout-dialog-light")
    }

    // ---------------------------------------------------------------- 200% / 320 / 840

    @Test fun bannedLarge() = board("RiskNoticeBanned-v2", "RiskNoticeBanned-v2-font200-w390-light", fontScale = 2f)

    @Test fun bannedLargeDark() = board("RiskNoticeBanned-v2", "RiskNoticeBanned-v2-font200-w390-dark", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun bannedLargeNarrow() = board("RiskNoticeBanned-v2", "RiskNoticeBanned-v2-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun frozenLargeNarrow() = board("RiskNoticeFrozen", "RiskNoticeFrozen-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun appealLargeNarrow() {
        host.launchAppeal(fontScale = 2f)
        host.input(RiskVisualFixtures.input)
        shot("Appeal-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun progressLargeNarrow() = board("AppealProgress-v2", "AppealProgress-v2-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun upheldLargeNarrow() = board("AppealResultUpheld-v2", "AppealResultUpheld-v2-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun upheldLarge() = board("AppealResultUpheld-v2", "AppealResultUpheld-v2-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun logoutDialogLargeNarrow() {
        host.launchRisk(fontScale = 2f)
        host.tap(MeTags.RISK_LOGOUT)
        screen("risk-logout-dialog-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun bannedWide() = board("RiskNoticeBanned-v2", "RiskNoticeBanned-v2-w840-light")

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun appealWide() = board("Appeal", "Appeal-w840-light")

    @Test
    @Config(qualifiers = "w840dp-h1180dp-xhdpi")
    fun upheldWide() = board("AppealResultUpheld-v2", "AppealResultUpheld-v2-w840-light")

    // ---------------------------------------------------------------- 工具

    private fun board(
        board: String,
        name: String,
        dark: Boolean = RiskVisualFixtures.dark(board),
        fontScale: Float = 1f,
    ) {
        host.launchBoard(board, dark = dark, fontScale = fontScale)
        assertTrue("画板状态已显示：$board", host.has(MeTags.RISK_PAGE) || host.has(MeTags.APPEAL_PAGE))
        shot(name)
    }

    private fun submitError(
        code: Int,
        name: String,
    ) {
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.failure(code)
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        host.tap(MeTags.APPEAL_SUBMIT)
        host.settle("提交失败提示") { host.has(MeTags.APPEAL_ERROR) }
        shot(name)
    }

    private fun shot(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        rule.onRoot().captureRoboImage("$DIR/$name.png")
    }

    private fun screen(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(DIALOG_SETTLE_MS)
        captureScreenRoboImage("$DIR/$name.png")
    }

    private companion object {
        const val DIR = "src/test/snapshots/risk-v"
        const val FRAME_SETTLE_MS = 300L
        const val DIALOG_SETTLE_MS = 600L
    }
}
