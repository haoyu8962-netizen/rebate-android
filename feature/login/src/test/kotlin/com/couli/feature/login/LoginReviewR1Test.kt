package com.couli.feature.login

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 第 1 轮评审（android-review-r1.md）里落在登录页上的几条，经过真实 [LoginViewModel] / [LoginScreen] 与本地假服务复现：
 * - R1-03（共性第 15 条）：粘贴「138a00138000」不先删字母，本地报 phone_invalid、不发短信；
 * - R1-04（共性第 16 条）：发码回 50401（结果不确定）时，手机号页仍可经独立的【继续输入验证码】进验证码步，且不再发码；成功发码 60 秒后
 *   （已可重发、未满 5 分钟）同样可以直接继续输入，不作废旧码；
 * - R1-08（共性第 17 条）：最小宽度 ≥ 600dp（平板、折叠屏内屏）时登录内容限宽 480dp 并水平居中。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class LoginReviewR1Test {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private var now = 1_700_000_000_000L

    @Volatile
    private var sendReply: () -> MockResponse = { envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""") }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = sendReply()
            }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun service(): LoginService {
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = MemorySessionTokens(),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
        return LoginService(api, MemorySessionTokens(), NoHost, clock = { now })
    }

    private fun model(): LoginViewModel = LoginViewModel(LoginPageConfig(service(), KeyTexts, restricted = false, clock = { now }))

    /** 等网络回到主线程（Robolectric 的主线程 Looper 是暂停模式）。 */
    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertTrue(condition())
    }

    // ---- R1-03

    @Test
    fun pastedPhoneWithALetterIsKeptAndRejectedLocallyWithoutSendingAnSms() {
        val model = model()
        model.onToggleAgreement()
        model.onPhoneChange(PASTED_WITH_LETTER)
        assertEquals("输入原样保留，不先删字母", PASTED_WITH_LETTER, model.state.value.phone)

        model.onGetCode()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("本地报 phone_invalid（与服务端 20001 同一文案）", "error.20001.phone_invalid", model.state.value.phoneError)
        assertEquals(LoginStep.PHONE, model.state.value.step)
        assertEquals("不发短信", 0, server.requestCount)
    }

    // ---- R1-04

    @Test
    fun anUncertainSendStillOffersContinuingWithTheCodeActuallyReceived() {
        sendReply = { envelope(50401, "null") }
        val model = model()
        model.onToggleAgreement()
        model.onPhoneChange("13800138000")
        model.onGetCode()
        idleUntil { server.requestCount == 1 && !model.state.value.sending }

        assertEquals("提示错误、仍在手机号步", LoginStep.PHONE, model.state.value.step)
        assertTrue("短信可能已送达：出现【继续输入验证码】", model.state.value.issuedCodeSec > 0)

        model.onContinueCode()
        assertEquals("进入验证码步", LoginStep.CODE, model.state.value.step)
        assertEquals("继续输入不发码", 1, server.requestCount)
    }

    @Test
    fun continuingAfterTheResendCountdownDoesNotResendAndInvalidateTheCode() {
        val model = model()
        model.onToggleAgreement()
        model.onPhoneChange("13800138000")
        model.onGetCode()
        idleUntil { model.state.value.step == LoginStep.CODE }
        model.onBackToPhone()

        // 60 秒过了（已可重发）、5 分钟还没到
        now += 61_000
        model.onContinueCode()

        assertEquals("直接回验证码步继续输入", LoginStep.CODE, model.state.value.step)
        assertEquals("没有再发码（旧码不作废）", 1, server.requestCount)
    }

    // ---- 共性第 20、21 条（iOS 第 1 轮评审追加）

    @Test
    fun theSendLimitFollowsTheNormalisedNumberShownOnThePage() {
        sendReply = { envelope(42901, "null").setResponseCode(429).setHeader("Retry-After", "120") }
        val model = model()
        model.onToggleAgreement()
        model.onPhoneChange("13900000000")
        model.onGetCode()
        idleUntil { server.requestCount == 1 && !model.state.value.sending }
        assertTrue("这个号码进入发码限流", model.state.value.sendLimitedSec > 0)

        model.onPhoneChange("13800138000")
        assertEquals("换号：按新号码的截止时间，不再禁用", 0, model.state.value.sendLimitedSec)

        model.onPhoneChange("+86 139-0000-0000")
        assertTrue("换回旧号码（另一种写法）：旧限制仍然生效", model.state.value.sendLimitedSec > 0)
    }

    @Test
    fun reappearingRecomputesTheCountdownFromTheCurrentClock() {
        val model = model()
        model.onToggleAgreement()
        model.onPhoneChange("13800138000")
        model.onGetCode()
        idleUntil { model.state.value.step == LoginStep.CODE }
        assertEquals(60, model.state.value.resendSec)

        // 页面在后台 / 被协议页盖住 30 秒，期间每秒刷新没有推进
        now += 30_000
        model.onPageVisible()
        assertEquals("重新出现时立即按当前时钟重算", 30, model.state.value.resendSec)

        now += 31_000
        model.onPageVisible()
        assertEquals("倒计时已结束：可以重新获取", 0, model.state.value.resendSec)
    }

    // ---- R1-08

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletWidthLimitsTheLoginContentAndCentresIt() = assertCentredAndLimited(WINDOW_TABLET_DP)

    @Test
    @Config(qualifiers = "w673dp-h841dp-xhdpi")
    fun unfoldedFoldableWidthLimitsTheLoginContentAndCentresIt() = assertCentredAndLimited(WINDOW_FOLDABLE_DP)

    @Test
    fun phoneWidthStillFillsTheWindow() {
        show()
        val content = composeRule.onNodeWithTag(LoginTags.CONTENT).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("手机宽度铺满", root.width, content.width, 1f)
    }

    private fun assertCentredAndLimited(windowDp: Float) {
        show()
        val density = composeRule.density.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("窗口宽度", windowDp, root.width / density, 1f)
        val content = composeRule.onNodeWithTag(LoginTags.CONTENT).fetchSemanticsNode().boundsInRoot
        val button = composeRule.onNodeWithTag(LoginTags.GET_CODE).fetchSemanticsNode().boundsInRoot
        assertTrue("内容最大 480dp：${content.width / density}", content.width / density <= MAX_CONTENT_DP + 0.5f)
        assertTrue("主按钮不随窗口拉宽：${button.width / density}", button.width / density <= MAX_CONTENT_DP)
        val leftGap = content.left - root.left
        val rightGap = root.right - content.right
        assertEquals("水平居中", leftGap, rightGap, 1f)
        assertTrue("两侧留白", leftGap > 0f)
    }

    private fun show() {
        val config = LoginPageConfig(service(), KeyTexts, restricted = false, clock = { now })
        composeRule.setContent { LoginScreen(config) }
        composeRule.waitForIdle()
    }

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-000123"}""")

    private object KeyTexts : LoginTexts {
        override fun text(key: String) = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ) = key
    }

    private object NoHost : LoginHost {
        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = false

        override fun closeLogin() = Unit

        override fun resumePending() = Unit

        override fun discardPending() = Unit

        override fun enterForceUpdate() = Unit

        override fun sessionChanged() = Unit

        override fun sessionSynced() = Unit

        override fun sessionEnded() = Unit
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val PASTED_WITH_LETTER = "138a00138000"
        const val MAX_CONTENT_DP = 480f
        const val WINDOW_TABLET_DP = 800f
        const val WINDOW_FOLDABLE_DP = 673f
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
