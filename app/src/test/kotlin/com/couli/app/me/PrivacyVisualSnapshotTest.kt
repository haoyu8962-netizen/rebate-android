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
 * APP-12w-b 截图入口（brief-b §2.1 / brief-b-android §4）：app/src/test/snapshots/privacy-v/。宽 390、页面高取画板去掉设计说明后的高度（隐私中心 864、
 * 基本模式 844、关于 844、客服 1032 含「我的」底稿）、顶部 54dp 状态栏占位；三张深色稿出深色；200% × w390 / w320；840 一档。只核部分区块的加后缀：
 * -no-permission-rows-GP15（Android 契约无条目）、-no-icp-GP23（备案号未配置）、-GP02（CsHandoff 作废稿、行为未改）。
 * 本页组没有加载态（进入零请求），「错误态」= 个性化切换失败、复制失败、客服打不开 / 复制失败。每个入口先经真实触摸到达状态再截图；弹层用 captureScreenRoboImage。
 * 本轮只编写、不录制（录图由实现方在宿主锁内做）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h864dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一张基准一个入口
class PrivacyVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    // ---------------------------------------------------------------- 隐私中心（有稿）

    @Test fun privacyMember() = privacy("PrivacyCenter-no-permission-rows-GP15-light")

    @Test fun privacyGuest() = privacy("privacy-guest-no-permission-rows-GP15-light", session = "privacy_guest")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun privacyBasic() = privacy("PrivacyCenterBasic-v2-no-permission-rows-GP15-light", session = "privacy_basic")

    @Test fun privacyWithdraw() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        assertTrue(host.has(MeTags.WITHDRAW_DIALOG))
        screen("PrivacyCenterWithdraw-light")
    }

    @Test fun privacyForceUpdate() = privacy("PrivacyCenterForceUpdate-light", session = "privacy_force_update")

    @Test fun privacyDark() = privacy("PrivacyCenterDark-no-permission-rows-GP15-dark", dark = true)

    // ---------------------------------------------------------------- 隐私中心（无稿）

    @Test fun privacyPersonalizationOff() {
        host.launchPrivacy(personalization = false)
        shot("privacy-personalization-off-light")
    }

    @Test fun privacyWithdrawing() {
        host.launchPrivacy(withdrawing = true)
        shot("privacy-withdrawing-light")
    }

    @Test fun privacyPersonalizationFailed() {
        host.api.consent = PrivacyVisualFixtures.failure()
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_SWITCH)
        host.settle("个性化失败提示") { host.has(MeTags.TOAST) }
        shot("privacy-personalization-failed-toast-light")
    }

    @Test fun privacyWithdrawDark() {
        host.launchPrivacy(dark = true)
        host.tap(MeTags.PRIVACY_WITHDRAW)
        assertTrue(host.has(MeTags.WITHDRAW_DIALOG))
        screen("PrivacyCenterWithdraw-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun privacyBasicDark() = privacy("PrivacyCenterBasic-v2-dark", session = "privacy_basic", dark = true)

    // ---------------------------------------------------------------- 关于

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun aboutFiled() = about("About-v2-no-icp-GP23-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun aboutBeforeFilingCopied() {
        host.launchAbout(filed = false)
        host.tap(MeTags.ABOUT_COPY)
        host.settle("复制成功提示") { host.has(MeTags.TOAST) }
        shot("AboutBeforeFiling-no-icp-GP23-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun aboutDark() = about("AboutDark-no-icp-GP23-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun aboutCopyFailed() {
        host.launchAbout(copyOk = false)
        host.tap(MeTags.ABOUT_COPY)
        host.settle("复制失败提示") { host.has(MeTags.TOAST) }
        shot("about-copy-failed-toast-light")
    }

    // ---------------------------------------------------------------- 客服半屏

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csOpenFailed() = cs("CsOpenFailed-v2-light", "cs_open_failed")

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csWechatMissing() = cs("CsWechatMissing-v2-light", "cs_wechat_missing")

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csWechatMissingDark() = cs("CsWechatMissingDark-dark", "cs_wechat_missing", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csOpenFailedDark() = cs("CsOpenFailed-v2-dark", "cs_open_failed", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csHelpOnly() = cs("cs-help-only-light", "cs_help_only")

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csHandoff() = cs("cs-handoff-GP02-light", "cs_handoff")

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csCopiedInSheet() {
        host.launchCs("cs_wechat_missing")
        host.tap(MeTags.CS_COPY)
        assertTrue(host.has(MeTags.TOAST))
        screen("cs-copied-in-sheet-light")
    }

    // ---------------------------------------------------------------- 200%

    @Test
    @Config(qualifiers = "w390dp-h864dp-xhdpi")
    fun privacyFont200W390() = privacy("PrivacyCenter-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h864dp-xhdpi")
    fun privacyFont200W390Dark() = privacy("PrivacyCenter-font200-w390-dark", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h864dp-xhdpi")
    fun privacyFont200W320() = privacy("PrivacyCenter-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun basicFont200W390() = privacy("PrivacyCenterBasic-v2-font200-w390-light", session = "privacy_basic", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun basicFont200W320() = privacy("PrivacyCenterBasic-v2-font200-w320-light", session = "privacy_basic", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h864dp-xhdpi")
    fun withdrawFont200W390() = withdrawLarge("PrivacyCenterWithdraw-font200-w390-light")

    @Test
    @Config(qualifiers = "w320dp-h864dp-xhdpi")
    fun withdrawFont200W320() = withdrawLarge("PrivacyCenterWithdraw-font200-w320-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun aboutFont200W390() = about("About-v2-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun aboutFont200W320() = about("About-v2-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h1032dp-xhdpi")
    fun csFont200W390() = cs("CsOpenFailed-v2-font200-w390-light", "cs_open_failed", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1032dp-xhdpi")
    fun csFont200W320() = cs("CsOpenFailed-v2-font200-w320-light", "cs_open_failed", fontScale = 2f)

    // ---------------------------------------------------------------- 840

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun privacyWide() = privacy("PrivacyCenter-w840-light")

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun aboutWide() = about("About-v2-w840-light")

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun csWide() = cs("CsOpenFailed-v2-w840-light", "cs_open_failed")

    // ---------------------------------------------------------------- 工具

    private fun privacy(
        name: String,
        session: String = "privacy_member",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launchPrivacy(session = session, dark = dark, fontScale = fontScale)
        assertTrue("隐私中心已显示", host.has(MeTags.PRIVACY_PAGE))
        shot(name)
    }

    private fun withdrawLarge(name: String) {
        host.launchPrivacy(fontScale = 2f)
        host.tap(MeTags.PRIVACY_WITHDRAW)
        assertTrue(host.has(MeTags.WITHDRAW_DIALOG))
        screen(name)
    }

    private fun about(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launchAbout(dark = dark, fontScale = fontScale)
        assertTrue(host.has(MeTags.ABOUT_FILING))
        shot(name)
    }

    private fun cs(
        name: String,
        case: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launchCs(case, dark = dark, fontScale = fontScale)
        assertTrue(host.has(MeTags.CS_SHEET))
        screen(name)
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
        const val DIR = "src/test/snapshots/privacy-v"
        const val FRAME_SETTLE_MS = 300L
        const val SHEET_SETTLE_MS = 600L
    }
}
