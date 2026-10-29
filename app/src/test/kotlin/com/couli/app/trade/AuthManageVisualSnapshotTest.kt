@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.AuthManageVisualHost.Companion.AUTHORIZED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.BIND
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT
import com.couli.core.network.ResultSignals
import com.couli.feature.trade.AuthManageTags
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-06w 截图入口（brief-android §4）：app/src/test/snapshots/auth-manage-v/，34 张，与 fixture snapshots 逐一对应（AuthManageVisualFixtureTest 核）。
 * 宽 390、高 844（顶部 54dp 状态栏占位 + 页面区）、xhdpi；深色稿出深色（+night 且 CouliTheme(darkTheme = true)）；200% × w390 / w320；840 一档；
 * 无稿态与深色抽查。每个入口经真实触摸到达状态再截图；轻提示类先停主时钟自动推进（宿主 toastAfter）。结构不对照（G-A09）的列表失败照常出图。
 * 本轮只编写、不录制（录图由实现方在宿主锁内做）：verify.sh 新增一步 `:app:verifyRoborazziDebug --tests "*AuthManageVisualSnapshotTest"`。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 一张基准一个入口
class AuthManageVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { AuthManageVisualHost(rule) }

    @After fun tearDown() = host.close()

    // ---------------------------------------------------------------- 有稿

    @Test fun normal() = page("normal", "AuthManage-v2-light")

    @Test fun blocked() = page("blocked", "AuthManageBlocked-v2-light")

    @Test fun allAuthorizedAfterReturn() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.sticky[BIND] = AuthManageVisualFixtures.bound()
        host.launch("taobao_unbound")
        host.nextList("all_active")
        host.tap(host.grantTag("taobao"))
        host.settle("两个都已授权") { host.countText(host.text(AUTHORIZED_KEY)) == 2 }
        shot("AuthManageStates-01-all-authorized-light")
    }

    @Test fun authUnavailableToast() = authUnavailable("AuthManageStates-02-auth-unavailable-toast-light")

    @Test fun bind30151Toast() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_web_code")
        host.api.sticky[BIND] = AuthManageVisualFixtures.failure("c30151")
        host.launch("taobao_unbound")
        host.toastAfter(host.text(AuthManageVisualFixtures.wording("c30151"))) { host.tap(host.grantTag("taobao")) }
        shot("AuthManageStates-03-30151-toast-light")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun dark() = page("normal", "AuthManageDark-dark", dark = true)

    // ---------------------------------------------------------------- 深色抽查

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun blockedDark() = page("blocked", "AuthManageBlocked-v2-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun authUnavailableToastDark() = authUnavailable("AuthManageStates-02-auth-unavailable-toast-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun loadErrorDark() = listError("c50001", "auth-manage-load-error-50001-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun loadingDark() {
        host.launch(bindings = null, dark = true, wait = false)
        shot("auth-manage-loading-dark")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun bothBlockedDark() = page("both_blocked", "auth-manage-both-blocked-dark", dark = true)

    // ---------------------------------------------------------------- 无稿

    @Test fun loading() {
        host.launch(bindings = null, wait = false)
        shot("auth-manage-loading-light")
    }

    @Test fun loadError50001() = listError("c50001", "auth-manage-load-error-50001-light")

    @Test fun networkError() {
        host.launchListError(AuthManageVisualFixtures.offline())
        shot("auth-manage-network-error-light")
    }

    @Test fun listRateLimited() = listError("c42901", "auth-manage-list-rate-limited-light")

    @Test fun authorizing() {
        host.launch("both_unbound")
        host.tap(host.grantTag("taobao"))
        shot("auth-manage-authorizing-light")
    }

    @Test fun authorizeRateLimited() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c42901")
        host.launch("both_unbound")
        host.toastAfter(host.text(AuthManageVisualFixtures.wording("c42901"))) { host.tap(host.grantTag("taobao")) }
        shot("auth-manage-authorize-rate-limited-light")
    }

    @Test fun blockedBy30153() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30153")
        host.launch("both_unbound")
        host.tap(host.grantTag("taobao"))
        host.settle("该行改停用") { host.has(AuthManageTags.CONTACT) }
        shot("auth-manage-blocked-by-30153-light")
    }

    @Test fun bothBlocked() = page("both_blocked", "auth-manage-both-blocked-light")

    @Test fun credentialInvalidToast() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.grant("taobao_sdk")
        host.launch("taobao_unbound")
        host.toastAfter(host.text("error.30104.credential_invalid")) { host.tap(host.grantTag("taobao")) }
        shot("auth-manage-credential-invalid-toast-light")
    }

    @Test fun timeoutToast() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.offline()
        host.launch("taobao_unbound")
        host.toastAfter(host.text(AuthManageVisualFixtures.wording("offline"))) { host.tap(host.grantTag("taobao")) }
        shot("auth-manage-timeout-toast-light")
    }

    @Test fun serverErrorToast() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c50001")
        host.launch("taobao_unbound")
        val message = ResultSignals.withTrace6(host.text("error.50001"), AuthManageVisualFixtures.traceId)
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        shot("auth-manage-server-error-toast-light")
    }

    @Test fun withJdNotListed() = page("with_jd", "auth-manage-with-jd-not-listed-light")

    // ---------------------------------------------------------------- 200%

    @Test fun font200W390() = page("normal", "AuthManage-v2-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun font200W390Dark() = page("normal", "AuthManage-v2-font200-w390-dark", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320() = page("normal", "AuthManage-v2-font200-w320-light", fontScale = 2f)

    @Test fun blockedFont200W390() = page("blocked", "AuthManageBlocked-v2-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun blockedFont200W320() = page("blocked", "AuthManageBlocked-v2-font200-w320-light", fontScale = 2f)

    @Test fun toastFont200W390() = authUnavailable("AuthManageStates-02-auth-unavailable-toast-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun toastFont200W320() = authUnavailable("AuthManageStates-02-auth-unavailable-toast-font200-w320-light", fontScale = 2f)

    @Test fun loadErrorFont200W390() = listError("c50001", "auth-manage-load-error-50001-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun loadErrorFont200W320() = listError("c50001", "auth-manage-load-error-50001-font200-w320-light", fontScale = 2f)

    // ---------------------------------------------------------------- 840

    @Test
    @Config(qualifiers = "w840dp-h844dp-xhdpi")
    fun w840() = page("normal", "AuthManage-v2-w840-light")

    @Test
    @Config(qualifiers = "w840dp-h844dp-xhdpi")
    fun blockedW840() = page("blocked", "AuthManageBlocked-v2-w840-light")

    // ---------------------------------------------------------------- 入口

    private fun page(
        bindings: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(bindings, dark = dark, fontScale = fontScale)
        shot(name)
    }

    private fun authUnavailable(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30101")
        host.launch("taobao_unbound", dark = dark, fontScale = fontScale)
        host.toastAfter(host.text(AuthManageVisualFixtures.wording("c30101"))) { host.tap(host.grantTag("taobao")) }
        shot(name)
    }

    private fun listError(
        code: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launchListError(AuthManageVisualFixtures.failure(code), dark = dark, fontScale = fontScale)
        shot(name)
    }

    private fun shot(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        rule.onRoot().captureRoboImage("$DIR/$name.png")
    }

    private companion object {
        const val DIR = "src/test/snapshots/auth-manage-v"
        const val FRAME_SETTLE_MS = 300L
    }
}
