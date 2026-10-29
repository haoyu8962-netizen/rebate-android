@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-b 截图入口（brief-android §4）：app/src/test/snapshots/account-v/（与 a 段同目录，名字不重）。类名以 AccountVisualSnapshotTest 结尾，
 * 落进 verify.sh 已有的 `*AccountVisualSnapshotTest` 一步，不必改 CI 脚本。宽 390、视口高取画板高；四张深色稿中本段两张（PhoneChangeCodeDark、
 * BindPhoneDark）出深色；200% × w390 / w320；840 一档；无稿态与深色抽查。结构不对照（G-D04）、只对框（G-D20）、页面部分（G-D06）、页内（G-D10）的加后缀。
 * 每个入口经真实设置页「手机号」行 + 真实触摸到达状态再截图；弹层用 captureScreenRoboImage。本轮只编写、不录制（录图由实现方在宿主锁内做）。
 * 名单与 fixture segmentB.snapshots 逐一对应（AccountVisualPhoneFixtureTest 核）。stepup-v 换号宿主图仍由冻结的 StepUpVisualAccountTest 重录（G-D02 / G-D06）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1127dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一张基准一个入口
class PhoneAccountVisualSnapshotTest : AccountVisualPhoneHarness() {
    // ---------------------------------------------------------------- 换号 第 1 步

    @Test fun step1() {
        enterStep1()
        shot("PhoneChange-v2-no-current-number-GD05-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun step1Sent() {
        enterStep1(sent = true)
        type(AccountTags.PHONE_STEP1_CODE, "12")
        node(AccountTags.PHONE_STEP1_CODE).performClick()
        shot("PhoneChangeCode-single-page-GD04-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h957dp-xhdpi")
    fun step1Error() {
        enterStep1Error()
        shot("PhoneChangeCodeError-cells-GD04-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1199dp-xhdpi")
    fun step1SentDark() {
        dark = true
        enterStep1(sent = true)
        type(AccountTags.PHONE_STEP1_CODE, "12")
        node(AccountTags.PHONE_STEP1_CODE).performClick()
        shot("PhoneChangeCodeDark-cells-GD04-dark")
    }

    // ---------------------------------------------------------------- 换号 第 2 步

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun step2() {
        enterStep2()
        shot("PhoneChangeNew-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1011dp-xhdpi")
    fun step2Sent() {
        enterStep2()
        sendStep2()
        shot("PhoneChangeNewCode-single-page-GD04-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1011dp-xhdpi")
    fun step2SentDark() {
        dark = true
        enterStep2()
        sendStep2()
        shot("phone-change-step2-sent-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1065dp-xhdpi")
    fun step2StepUpPageOnly() {
        enterStepUpOnSubmit()
        shot("PhoneChangeStepUp-page-only-GD06-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h975dp-xhdpi")
    fun disabledInline() {
        submitStep2With(30414)
        idleUntil("30414 页内") { has(AccountTags.PHONE_CONTACT_CS) }
        shot("PhoneChangeDisabled-inline-GD10-light")
    }

    @Test fun error30411() {
        submitStep2With(30411)
        idleUntil("30411 页内") { has(AccountTags.PHONE_ERROR) }
        shot("phone-change-30411-light")
    }

    @Test fun rateLimited() {
        enterStep2()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        enqueueCode("POST", SMS, 42901, headers = mapOf("Retry-After" to "30"))
        click(AccountTags.PHONE_STEP2_SEND)
        idleUntil("发码限流") { all("POST", SMS).size >= 2 && !enabled(AccountTags.PHONE_STEP2_SEND) }
        shot("phone-change-rate-limited-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun blocked() {
        enterRisk44001()
        shot("PhoneChangeNewBlocked-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun blockedDark() {
        dark = true
        enterRisk44001()
        shot("PhoneChangeNewBlocked-v2-dark")
    }

    // ---------------------------------------------------------------- 换号 完成

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun done() {
        enterDone()
        shot("PhoneChangeDone-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun doneDark() {
        dark = true
        enterDone()
        shot("PhoneChangeDone-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pendingCompleted() {
        val key = enterPhonePending()
        serverState.results[key] = 0 to "{}"
        abandonOk()
        idleUntil("放弃时已完成") { has(AccountTags.PHONE_DONE) && has(MeTags.TOAST) }
        shot("PhoneChangePendingCompleted-light")
    }

    // ---------------------------------------------------------------- 换号 待确认一族

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun pendingConfirm() {
        enterPhonePending()
        shot("PhoneChangePendingConfirm-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun pendingConfirmDark() {
        dark = true
        enterPhonePending()
        shot("PhoneChangePendingConfirm-v2-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun pendingBusy() {
        enterPhonePending()
        val (arrived, release) = hold("POST", BIND) { envelope("{}") }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue(arrived.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
            settle()
            shot("PhoneChangePendingBusy-v2-light")
        } finally {
            release.countDown()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun pendingStill() {
        enterPhonePending()
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("仍无结果") { has(AccountTags.PENDING_NOTICE) }
        shot("PhoneChangePendingStill-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun pendingAbandonConfirm() {
        enterPhonePending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        shot("PhoneChangePendingAbandonConfirm-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1029dp-xhdpi")
    fun pendingAbandoned() {
        enterPhonePending()
        abandonOk()
        idleUntil("已放弃") { has(AccountTags.PHONE_STEP1) && has(MeTags.TOAST) }
        shot("PhoneChangePendingAbandoned-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1071dp-xhdpi")
    fun pendingStates01AbandonBusy() {
        enterPhonePending()
        enqueueCode("POST", ABANDON, 40901)
        abandonOk()
        idleUntil("40901") { has(AccountTags.PENDING_NOTICE) }
        shot("PhoneChangePendingStates-01-abandon-busy-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1071dp-xhdpi")
    fun pendingStates02Confirm20903() {
        val key = enterPhonePending()
        serverState.results[key] = 20903 to "null"
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil("20903 回第 1 步") { has(AccountTags.PHONE_STEP1) }
        shot("PhoneChangePendingStates-02-confirm-20903-light")
    }

    // ---------------------------------------------------------------- 绑号

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bind() {
        enterBind()
        shot("BindPhone-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1155dp-xhdpi")
    fun bindDark() {
        dark = true
        enterBind()
        shot("BindPhoneDark-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1091dp-xhdpi")
    fun bindMerge() {
        enterBindMerge()
        shot("BindPhoneMerge-v2-dialog-only-GD20-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindTyped() {
        enterBind(typed = true)
        shot("bind-phone-typed-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindSent() {
        enterBind(sent = true)
        type(BindPhoneTags.CODE, "12")
        shot("bind-phone-sent-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindCodeError() {
        bindSubmitWith(20002)
        idleUntil("验证码错误") { has(BindPhoneTags.CODE_ERROR) }
        shot("bind-phone-code-error-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bind44001() {
        enterBind44001()
        shot("bind-phone-44001-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindPending() {
        enterBindPending()
        shot("bind-phone-pending-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindPendingDark() {
        dark = true
        enterBindPending()
        shot("bind-phone-pending-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindAbandonConfirm() {
        enterBindPending()
        tap(BindPhonePendingTags.ABANDON)
        idleUntil { has(BindPhonePendingTags.ABANDON_OK) }
        shot("bind-phone-abandon-confirm-light")
    }

    // ---------------------------------------------------------------- 200%

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun step1SentFont200W390() {
        fontScale = 2f
        enterStep1(sent = true)
        shot("PhoneChangeCode-font200-w390-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h957dp-xhdpi")
    fun step1ErrorFont200W320() {
        fontScale = 2f
        enterStep1Error()
        shot("PhoneChangeCodeError-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1109dp-xhdpi")
    fun blockedFont200W320() {
        fontScale = 2f
        enterRisk44001()
        shot("PhoneChangeNewBlocked-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun doneFont200W320() {
        fontScale = 2f
        enterDone()
        shot("PhoneChangeDone-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h993dp-xhdpi")
    fun pendingFont200W320() {
        fontScale = 2f
        enterPhonePending()
        shot("PhoneChangePendingConfirm-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1261dp-xhdpi")
    fun bindFont200W390() {
        fontScale = 2f
        enterBind()
        shot("BindPhone-v2-font200-w390-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1261dp-xhdpi")
    fun bindFont200W320() {
        fontScale = 2f
        enterBind()
        shot("BindPhone-v2-font200-w320-light")
    }

    // ---------------------------------------------------------------- 840

    @Test
    @Config(qualifiers = "w840dp-h1127dp-xhdpi")
    fun step1W840() {
        enterStep1()
        shot("PhoneChange-v2-w840-light")
    }

    @Test
    @Config(qualifiers = "w840dp-h1109dp-xhdpi")
    fun step2W840() {
        enterStep2()
        shot("PhoneChangeNew-v2-w840-light")
    }

    @Test
    @Config(qualifiers = "w840dp-h1261dp-xhdpi")
    fun bindW840() {
        enterBind()
        shot("BindPhone-v2-w840-light")
    }

    private fun abandonOk() {
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
    }

    private fun shot(name: String) {
        settle()
        captureScreenRoboImage("src/test/snapshots/account-v/$name.png")
    }
}
