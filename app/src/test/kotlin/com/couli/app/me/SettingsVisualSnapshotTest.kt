@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-12w-a 截图入口（brief-android §4）：app/src/test/snapshots/settings-v/。宽 390、页面高取画板去掉设计说明后的高度（设置 1039、昵称 / 通知 844）、
 * 顶部 54dp 状态栏占位；SettingsDark / NotifySettingsDark 出深色；200% 为 844 高视口（不宣称整页长图）；840 为 1000 高。
 * 每个入口先经真实触摸 / 真实回复到达状态并核对关键节点再截图；弹层（半屏、确认框、用途说明）用 captureScreenRoboImage。
 * 本轮只编写、不录制（录图由实现方在宿主锁内做）。G-S03 / G-S10 的只核部分区块加后缀说明。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一张基准一个入口
class SettingsVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    // ---------------------------------------------------------------- 设置主页

    @Test fun settingsPublic() = page("Settings-v2-light")

    @Test fun settingsBeta() = page("settings-beta-no-delete-light", public = false)

    @Test
    @Config(qualifiers = "w390dp-h780dp-xhdpi")
    fun guest() = page("SettingsGuest-v2-order-GS03-light", session = "guest_consented")

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun basic() = page("SettingsBasic-order-GS03-light", session = "basic")

    @Test fun dark() = page("SettingsDark-dark", dark = true)

    @Test fun meLoading() {
        host.api.hold = CompletableDeferred()
        page("settings-me-loading-light")
    }

    @Test fun meLoadingDark() {
        host.api.hold = CompletableDeferred()
        page("settings-me-loading-dark", dark = true)
    }

    @Test fun meError() {
        host.api.answers[SettingsVisualApi.GET_ME] = SettingsVisualFixtures.failure()
        page("settings-me-error-50001-light")
    }

    // ---------------------------------------------------------------- 弹层与轻提示

    @Test fun agreementSheet() {
        host.launch()
        host.tapRow(SettingsRows.AGREEMENT)
        assertTrue(host.has(MeTags.AGREEMENT_SHEET))
        screen("SettingsAgreementSheet-v2-light")
    }

    @Test fun clearCache() {
        host.launch()
        host.tapRow(SettingsRows.CLEAR_CACHE)
        assertTrue(host.has(MeTags.CLEAR_DIALOG))
        screen("SettingsClearCache-v2-light")
    }

    @Test fun cacheCleared() {
        host.launch()
        host.tapRow(SettingsRows.CLEAR_CACHE)
        host.tap(MeTags.CLEAR_DIALOG)
        host.settle("已清除") { host.has(MeTags.TOAST) && host.count("0 KB") >= 1 }
        shot("SettingsCacheCleared-v2-light")
    }

    @Test fun logoutConfirm() {
        host.launch()
        host.tap(MeTags.SETTINGS_LOGOUT)
        assertTrue(host.has(MeTags.LOGOUT_DIALOG))
        screen("SettingsLogoutConfirm-v2-light")
    }

    @Test fun logoutConfirmDark() {
        host.launch(dark = true)
        host.tap(MeTags.SETTINGS_LOGOUT)
        assertTrue(host.has(MeTags.LOGOUT_DIALOG))
        screen("SettingsLogoutConfirm-dark")
    }

    @Test fun deletionWithdrawn() {
        host.launch(pendingSettingsToast = "deletion.cancelled_toast")
        host.settle("撤回注销提示") { host.has(MeTags.TOAST) }
        shot("SettingsDeletionWithdrawn-v2-light")
    }

    @Test fun resetTipToast() {
        host.launch()
        host.tapRow(SettingsRows.RESET_TIP)
        host.settle("已重新显示") { host.has(MeTags.TOAST) }
        shot("settings-reset-tip-toast-light")
    }

    @Test fun rateLimitedToast() {
        host.api.answers[SettingsVisualApi.RESET_TIP] = SettingsVisualFixtures.failure(42901)
        host.launch()
        host.tapRow(SettingsRows.RESET_TIP)
        host.settle("限流") { host.has(MeTags.TOAST) }
        shot("settings-rate-limited-toast-light")
    }

    @Test fun nicknameSaved() {
        host.launch()
        host.openNickname()
        host.type(SettingsVisualFixtures.savedName)
        host.tap(MeTags.NICKNAME_SAVE)
        host.settle("昵称已修改") { !host.has(MeTags.NICKNAME_PAGE) && host.has(MeTags.TOAST) }
        shot("SettingsNicknameSaved-v2-light")
    }

    // ---------------------------------------------------------------- 修改昵称

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun nickname() {
        nicknamePage()
        shot("SettingsNickname-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun nicknameLimit() {
        host.nickname.answer = SettingsVisualFixtures.failure(SettingsVisualFixtures.limitCode)
        nicknameError(SettingsVisualFixtures.limitInput)
        shot("SettingsNicknameLimit-save-enabled-GS10-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun nicknameSensitive() {
        host.nickname.answer = SettingsVisualFixtures.sensitive()
        nicknameError(SettingsVisualFixtures.sensitiveInput)
        shot("SettingsNicknameSensitive-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun nicknameTooLong() {
        nicknamePage()
        host.type(SettingsVisualFixtures.tooLong)
        shot("nickname-too-long-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun nicknameSaving() {
        host.nickname.hold = CompletableDeferred()
        nicknamePage()
        host.type(SettingsVisualFixtures.savedName)
        host.tap(MeTags.NICKNAME_SAVE)
        shot("nickname-saving-light")
        host.nickname.hold?.complete(Unit)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun nicknameDark() {
        host.nickname.answer = SettingsVisualFixtures.failure(SettingsVisualFixtures.limitCode)
        nicknameError(SettingsVisualFixtures.limitInput, dark = true)
        shot("SettingsNicknameLimit-dark")
    }

    // ---------------------------------------------------------------- 通知设置

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifyDenied() = notifyPage("NotifySettings-light", "denied")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifyGranted() = notifyPage("NotifySettingsOn-light", "granted")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifyNotDetermined() = notifyPage("NotifySettings-not-determined-GS14-light", "notDetermined")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifyDark() = notifyPage("NotifySettingsDark-dark", "denied", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifyLoadError() {
        host.notify.getAnswer = SettingsVisualFixtures.failure()
        host.env.status = SettingsVisualFixtures.notify("granted")
        host.launch()
        host.tapRow(SettingsRows.NOTIFY)
        host.settle("读取失败") { host.has(MeTags.NOTIFY_ERROR) }
        shot("notify-load-error-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifySwitchFailed() {
        host.notify.putAnswer = SettingsVisualFixtures.failure()
        host.env.status = SettingsVisualFixtures.notify("granted")
        host.launch()
        host.openNotify()
        host.tap(MeTags.NOTIFY_SWITCH)
        host.settle("切换失败") { host.has(MeTags.TOAST) }
        shot("notify-switch-failed-toast-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun notifyRelease() {
        host.env.status = SettingsVisualFixtures.notify("granted")
        host.launch(debugNotify = false)
        host.openNotify()
        shot("notify-release-no-switch-light")
    }

    // ---------------------------------------------------------------- 200% / 320 / 840

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun font200w390() = page("Settings-font200-w390-light", fontScale = 2f, profile = "settings_long_nickname")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun font200w390Dark() = page("Settings-font200-w390-dark", dark = true, fontScale = 2f, profile = "settings_long_nickname")

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200w320() = page("Settings-font200-w320-light", fontScale = 2f, profile = "settings_long_nickname")

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun guestFont200w320() = page("SettingsGuest-font200-w320-light", session = "guest_consented", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun basicFont200w320() = page("SettingsBasic-font200-w320-light", session = "basic", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun nicknameLimitFont200w320() {
        host.nickname.answer = SettingsVisualFixtures.failure(SettingsVisualFixtures.limitCode)
        nicknameError(SettingsVisualFixtures.limitInput, fontScale = 2f)
        shot("SettingsNicknameLimit-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun notifyFont200w320() = notifyPage("NotifySettings-font200-w320-light", "denied", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun logoutConfirmFont200w320() {
        host.launch(fontScale = 2f)
        host.tap(MeTags.SETTINGS_LOGOUT)
        assertTrue(host.has(MeTags.LOGOUT_DIALOG))
        screen("SettingsLogoutConfirm-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun agreementSheetFont200w320() {
        host.launch(fontScale = 2f)
        host.tapRow(SettingsRows.AGREEMENT)
        assertTrue(host.has(MeTags.AGREEMENT_SHEET))
        screen("SettingsAgreementSheet-font200-w320-light")
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = page("Settings-w840-light")

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun nicknameWide() {
        nicknamePage()
        shot("SettingsNickname-w840-light")
    }

    // ---------------------------------------------------------------- 工具

    @Suppress("LongParameterList") // 一张基准的会话、版本、主题、字号、报文逐项给
    private fun page(
        name: String,
        session: String = "settings_member",
        public: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        profile: String = "settings_member",
    ) {
        host.launch(session = session, public = public, dark = dark, fontScale = fontScale, profile = profile)
        assertTrue("设置页已显示", host.has(MeTags.SETTINGS_PAGE))
        shot(name)
    }

    private fun nicknamePage(
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(dark = dark, fontScale = fontScale)
        host.openNickname()
    }

    private fun nicknameError(
        input: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        nicknamePage(dark, fontScale)
        host.type(input)
        host.tap(MeTags.NICKNAME_SAVE)
        host.settle("昵称错误行") { host.has(MeTags.NICKNAME_ERROR) }
    }

    private fun notifyPage(
        name: String,
        status: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.env.status = SettingsVisualFixtures.notify(status)
        host.launch(dark = dark, fontScale = fontScale)
        host.openNotify()
        shot(name)
    }

    private fun shot(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        rule.onRoot().captureRoboImage("$DIR/$name.png")
    }

    private fun screen(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(SHEET_SETTLE_MS)
        captureScreenRoboImage("$DIR/$name.png")
    }

    private companion object {
        const val DIR = "src/test/snapshots/settings-v"
        const val FRAME_SETTLE_MS = 300L
        const val SHEET_SETTLE_MS = 600L
    }
}
