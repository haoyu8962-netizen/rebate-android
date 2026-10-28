@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-a 截图入口（brief-android §4）：app/src/test/snapshots/account-v/。宽 390、视口高取画板高（DeleteAccount-v2 1164、PendingConfirm 1463 等），
 * 两张深色稿出深色；200% × w390 / w320；840 一档；无稿态浅色（另抽查深色）。只核部分区块或结构不对照的加后缀（-page-only-GD06、-02-risk-source、
 * -03-cancel-expired 等）。每个入口先经真实设置页 / 真实触摸到达状态再截图；弹层用 captureScreenRoboImage。本轮只编写、不录制（录图由实现方在宿主锁内做）。
 * 不对照、也造不出同状态的：DeleteAccountStepUpOauth（G-D19，Android 不支持第三方重新授权）、DeleteAccountStates ④⑤（同）。
 * stepup-v 两张宿主图（WithdrawStepUp-v2-host-deletion / -phone-change）仍由 StepUpVisualAccountTest 录制，按 G-D06 由实现方在最终产品代码上重录。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1164dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一张基准一个入口
class AccountVisualSnapshotTest : AccountVisualDeletionHarness() {
    // ---------------------------------------------------------------- 说明与确认（有稿）

    @Test fun deleteAccount() {
        enterInitial()
        shot("DeleteAccount-v2-light")
    }

    @Test fun deleteAccountChecked() {
        enterInitial(checked = true)
        shot("DeleteAccount-v2-checked-light")
    }

    /** 测试 v3（评审 r1 S2-1）：深色已勾选——勾是勾选态唯一的形状信号，深色下须是 text.inverse 白勾压在 brand.primary 实心圆上。 */
    @Test fun deleteAccountCheckedDark() {
        dark = true
        enterInitial(checked = true)
        shot("DeleteAccount-v2-checked-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1158dp-xhdpi")
    fun negative() {
        enterNegative()
        shot("DeleteAccountNegative-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1200dp-xhdpi")
    fun stepUpPageOnly() {
        enterInitial(checked = true)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("半屏（发码已有结论）") { stepUpReady() }
        shot("DeleteAccountStepUp-page-only-GD06-light")
    }

    @Test fun states01Error30412() {
        enter30412()
        shot("DeleteAccountStates-01-30412-light")
    }

    @Test fun states02RiskSource() {
        enterFromRisk()
        shot("DeleteAccountStates-02-risk-source-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun states03CancelExpired() {
        enterCooling(expired = true)
        shot("DeleteAccountStates-03-cancel-expired-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1087dp-xhdpi")
    fun states06NoPhoneInPage() {
        serverState.phoneBound = false
        enterInitial()
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_NO_PHONE) }
        shot("DeleteAccountStates-06-no-phone-in-page-GD14-light")
    }

    // ---------------------------------------------------------------- 冷静期（有稿）

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun cooling() {
        enterCooling()
        shot("DeleteAccountCooling-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1155dp-xhdpi")
    fun coolingDark() {
        dark = true
        enterCooling()
        shot("DeleteAccountCoolingDark-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1246dp-xhdpi")
    fun deleteAccountDark() {
        dark = true
        enterInitial()
        shot("DeleteAccountDark-dark")
    }

    // ---------------------------------------------------------------- 待确认一族（有稿）

    @Test
    @Config(qualifiers = "w390dp-h1463dp-xhdpi")
    fun pendingConfirm() {
        enterPending()
        shot("DeleteAccountPendingConfirm-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1463dp-xhdpi")
    fun pendingConfirmDark() {
        dark = true
        enterPending()
        shot("DeleteAccountPendingConfirm-v2-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1463dp-xhdpi")
    fun pendingAbandonConfirm() {
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        shot("DeleteAccountPendingAbandonConfirm-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1463dp-xhdpi")
    fun pendingAbandonConfirmDark() {
        dark = true
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        shot("DeleteAccountPendingAbandonConfirm-v2-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h1494dp-xhdpi")
    fun pendingBusy() {
        enterPending()
        val (arrived, release) = hold("POST", DELETION) { envelope(deletionFixture("cooling")) }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            settle()
            shot("DeleteAccountPendingBusy-v2-light")
        } finally {
            release.countDown()
        }
        idleUntil { has(AccountTags.DELETE_COOLING) }
    }

    @Test
    @Config(qualifiers = "w390dp-h1520dp-xhdpi")
    fun pendingStill() {
        enterPending()
        enqueueCode("POST", DELETION, 40901)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { hasText(t("pending_confirm.retry_later")) }
        shot("DeleteAccountPendingStill-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1200dp-xhdpi")
    fun pendingAbandoned() {
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("已放弃轻提示") { has(MeTags.TOAST) }
        shot("DeleteAccountPendingAbandoned-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pendingCompleted() {
        val serial = enterPending()
        serverState.results[serial] = 0 to deletionFixture("completed_after_abandon")
        serverState.deletion = deletionFixture("completed_after_abandon")
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("已处理轻提示") { has(MeTags.TOAST) && has(AccountTags.DELETE_COOLING) }
        shot("DeleteAccountPendingCompleted-v2-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1087dp-xhdpi")
    fun pendingStates01AbandonBusy() {
        enterPending()
        enqueueCode("POST", ABANDON, 40901)
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil { hasText(t("pending_confirm.abandon_busy")) }
        shot("DeleteAccountPendingStates-01-abandon-busy-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h1087dp-xhdpi")
    fun pendingStates02Confirm20903() {
        val serial = enterPending()
        enqueueCode("POST", DELETION, 20903)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { records().none { it.key == serial } && !has(AccountTags.PENDING_CARD) }
        shot("DeleteAccountPendingStates-02-confirm-20903-light")
    }

    // ---------------------------------------------------------------- 无稿态（G-D25）

    @Test fun loading() {
        serverState.wallet = walletFixture("funds")
        var gate: Pair<java.util.concurrent.CountDownLatch, java.util.concurrent.CountDownLatch>? = null
        try {
            enterVia { gate = holdAll("GET", DELETION) { envelope(deletionFixture("none")) } }
            assertTrue(gate!!.first.await(WAIT_S, TimeUnit.SECONDS))
            idleUntil { has(AccountTags.DELETE_LOADING) }
            shot("deletion-loading-light")
        } finally {
            gate?.second?.countDown()
        }
    }

    @Test fun loadError() {
        serverState.wallet = walletFixture("funds")
        enterVia { failAlways("GET", DELETION, 50001, trace = "trace-visual-000001") }
        idleUntil { has(AccountTags.DELETE_LOAD_ERROR) }
        shot("deletion-load-error-light")
    }

    @Test fun loadErrorDark() {
        dark = true
        enterVia { failAlways("GET", DELETION, 50001, trace = "trace-visual-000001") }
        idleUntil { has(AccountTags.DELETE_LOAD_ERROR) }
        shot("deletion-load-error-dark")
    }

    @Test fun fundsError() {
        serverState.deletion = deletionFixture("none")
        enterVia { failAlways("GET", WALLET, 50001, trace = "trace-visual-000001") }
        awaitFunds()
        // 测试 v2：v1 只等资金区落地不核态，一次性回答落在被作废的读取上，录成了资金正常的图；先核确是资金读取失败态再录
        assertTrue("资金读取失败态（不是资金正常）", has(AccountTags.DELETE_FUNDS_ERROR) && !has(AccountTags.DELETE_WITHDRAWABLE))
        shot("deletion-funds-error-light")
    }

    @Test fun processing() {
        serverState.deletion = deletionFixture("processing")
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_PROCESSING) }
        shot("deletion-processing-light")
    }

    @Test fun noPhoneFull() {
        serverState.phoneBound = false
        enterInitial()
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_BIND_PHONE) }
        shot("deletion-no-phone-full-light")
    }

    @Test fun noPhoneRestricted() {
        serverState.phoneBound = false
        enterRestricted()
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_NO_PHONE) }
        shot("deletion-no-phone-restricted-light")
    }

    @Test fun restrictedNoWithdraw() {
        enterRestricted()
        shot("deletion-restricted-no-withdraw-light")
    }

    @Test fun guide() {
        serverState.requireStepUp = false
        enterInitial(checked = true)
        val (arrived, release) = hold("POST", DELETION) { envelope("null", 10005) }
        try {
            tap(AccountTags.DELETE_SUBMIT)
            assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
            open(SETTINGS)
            idleUntil { top() == SETTINGS }
        } finally {
            release.countDown()
        }
        settle()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_GUIDE) }
        shot("deletion-guide-bind-phone-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h993dp-xhdpi")
    fun processingDark() {
        dark = true
        serverState.deletion = deletionFixture("processing")
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_PROCESSING) }
        shot("deletion-processing-dark")
    }

    // ---------------------------------------------------------------- 200% / 窄屏 / 大屏

    @Test fun font200W390() {
        fontScale = 2f
        enterInitial(checked = true)
        shot("DeleteAccount-v2-font200-w390-light")
    }

    @Test fun font200W390Dark() {
        fontScale = 2f
        dark = true
        enterInitial(checked = true)
        shot("DeleteAccount-v2-font200-w390-dark")
    }

    @Test
    @Config(qualifiers = "w320dp-h1164dp-xhdpi")
    fun font200W320() {
        fontScale = 2f
        enterInitial(checked = true)
        shot("DeleteAccount-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1158dp-xhdpi")
    fun negativeFont200W320() {
        fontScale = 2f
        enterNegative()
        shot("DeleteAccountNegative-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h993dp-xhdpi")
    fun coolingFont200W320() {
        fontScale = 2f
        enterCooling()
        shot("DeleteAccountCooling-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1463dp-xhdpi")
    fun pendingFont200W320() {
        fontScale = 2f
        enterPending()
        shot("DeleteAccountPendingConfirm-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h1463dp-xhdpi")
    fun abandonDialogFont200W320() {
        fontScale = 2f
        enterPending()
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        shot("DeleteAccountPendingAbandonConfirm-v2-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w840dp-h1164dp-xhdpi")
    fun w840() {
        enterInitial()
        shot("DeleteAccount-v2-w840-light")
    }

    @Test
    @Config(qualifiers = "w840dp-h993dp-xhdpi")
    fun coolingW840() {
        enterCooling()
        shot("DeleteAccountCooling-v2-w840-light")
    }

    private fun shot(name: String) {
        settle()
        captureScreenRoboImage("src/test/snapshots/account-v/$name.png")
    }
}
