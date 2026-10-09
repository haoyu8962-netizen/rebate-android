package com.couli.feature.login

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 登录页的邀请码剪贴板来源（APP-07；BR-INV-04、AC-S1-63 b；G16）：
 * - 游客在首页邀请提示条点了确认：进入验证码页时预填一次（取走即丢弃），随登录提交 LoginBySmsRequest.invite_code；输入框已有内容时以用户输入为准；
 * - 邀请码行【粘贴】：抽到码填入；抽不到轻提示「未识别到邀请码」，不清空已有输入；
 * - 受限登录（从强更页进入）不显示【粘贴】、不预填。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class LoginInvitePasteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<Pair<String, String>>()
    private var now = 1_700_000_000_000L

    private class Source(
        var prefill: String? = null,
        var pasted: String? = null,
    ) : InvitePasteSource {
        var pastes = 0

        override val blocked = MutableStateFlow(false)

        override fun takePrefill(): String? = prefill.also { prefill = null }

        override fun paste(): String? {
            pastes += 1
            return pasted
        }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request.path.orEmpty() to request.body.readUtf8() }
                    return if (request.path.orEmpty().startsWith("/v1/auth/sms-codes")) {
                        envelope("""{"resend_after_sec":60,"expires_in_sec":300}""")
                    } else {
                        envelope(
                            """{"user_id":"user-one","is_new_user":true,"tokens":{"access_token":"access-one",""" +
                                """"access_expires_at":"2099-01-01T00:00:00+08:00","refresh_token":"refresh-one",""" +
                                """"refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"full"},""" +
                                """"invite_bind":{"result":"bound","code":null}}""",
                        )
                    }
                }
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

    private fun model(
        source: Source,
        restricted: Boolean = false,
    ): LoginViewModel = LoginViewModel(LoginPageConfig(service(), KeyTexts, restricted = restricted, clock = { now }, invite = source))

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertTrue(condition())
    }

    private fun toCodeStep(model: LoginViewModel) {
        model.onToggleAgreement()
        model.onPhoneChange("13800138000")
        model.onGetCode()
        idleUntil { model.state.value.step == LoginStep.CODE }
    }

    @Test
    fun guestPrefillIsFilledOnceOnTheCodeStepAndSubmittedWithLogin() {
        val source = Source(prefill = "K7M2QX")
        val model = model(source)
        assertEquals("手机号页不预填", "", model.state.value.invite)
        toCodeStep(model)
        assertEquals("进入验证码页时预填", "K7M2QX", model.state.value.invite)
        assertNull("预填取走即丢弃", source.prefill)

        model.onCodeChange("123456")
        model.onLogin()
        idleUntil { synchronized(requests) { requests.any { it.first.startsWith("/v1/auth/login/sms") } } }
        val body = synchronized(requests) { requests.first { it.first.startsWith("/v1/auth/login/sms") }.second }
        val json = Json.parseToJsonElement(body) as JsonObject
        assertEquals("随登录提交 invite_code", "K7M2QX", (json["invite_code"] as JsonPrimitive).content)
        assertEquals("预填不发任何别的请求（发码、登录各一次）", 2, synchronized(requests) { requests.size })
    }

    @Test
    fun userInputWinsOverThePrefill() {
        val source = Source(prefill = "K7M2QX")
        val model = model(source)
        model.onExpandInvite()
        model.onInviteChange("AB23CD")
        toCodeStep(model)
        assertEquals("已有输入以用户为准", "AB23CD", model.state.value.invite)
        assertNull("预填仍然丢弃", source.prefill)
    }

    @Test
    fun pasteFillsTheCodeOrKeepsInputWithANotice() {
        val source = Source(pasted = "K7M2QX")
        val model = model(source)
        model.onPasteInvite()
        assertEquals("K7M2QX", model.state.value.invite)

        source.pasted = null
        model.onInviteChange("AB23")
        model.onPasteInvite()
        assertEquals("抽不到码不清空已有输入", "AB23", model.state.value.invite)
        assertEquals("轻提示「未识别到邀请码」", "clipboard.login_invite.not_found", model.state.value.toast)
        assertEquals(2, source.pastes)
    }

    /** A-R1-06：42901 共享禁用期内点【粘贴】：执行层不读剪贴板，给限流提示，不误报「未识别到邀请码」、不动已有输入。 */
    @Test
    fun pasteIsBlockedDuringTheRetryAfterWindow() {
        val source = Source(pasted = "K7M2QX")
        source.blocked.value = true
        val model = model(source)
        model.onInviteChange("AB23")
        model.onPasteInvite()
        assertEquals("禁用期内不读", 0, source.pastes)
        assertEquals("AB23", model.state.value.invite)
        assertEquals("限流提示", "error.42901", model.state.value.toast)
        assertTrue(model.invitePasteBlocked.value)
        source.blocked.value = false
        model.onPasteInvite()
        assertEquals(1, source.pastes)
        assertEquals("K7M2QX", model.state.value.invite)
    }

    @Test
    fun pasteButtonIsGreyedDuringTheRetryAfterWindow() {
        val source = Source(pasted = "K7M2QX")
        source.blocked.value = true
        val config = LoginPageConfig(service(), KeyTexts, restricted = false, clock = { now }, invite = source)
        composeRule.setContent { LoginScreen(config) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LoginTags.INVITE_ENTRY).performClick()
        composeRule.onNodeWithTag(LoginTags.INVITE_PASTE).assertIsDisplayed().assertIsNotEnabled()
        source.blocked.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LoginTags.INVITE_PASTE).assertIsEnabled()
    }

    @Test
    fun restrictedLoginNeitherPastesNorPrefills() {
        val source = Source(prefill = "K7M2QX", pasted = "K7M2QX")
        val model = model(source, restricted = true)
        assertFalse(model.invitePasteAvailable)
        model.onPasteInvite()
        assertEquals("", model.state.value.invite)
        assertEquals(0, source.pastes)
    }

    @Test
    fun pasteButtonOnTheCodeStepReadsOnlyWhenTapped() {
        val source = Source(pasted = "K7M2QX")
        val config = LoginPageConfig(service(), KeyTexts, restricted = false, clock = { now }, invite = source)
        composeRule.setContent { LoginScreen(config) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LoginTags.INVITE_ENTRY).performClick()
        composeRule.onNodeWithTag(LoginTags.INVITE_PASTE).assertIsDisplayed()
        assertEquals("没点不读", 0, source.pastes)
        composeRule.onNodeWithTag(LoginTags.INVITE_PASTE).performClick()
        composeRule.waitForIdle()
        assertEquals(1, source.pastes)
    }

    private fun envelope(data: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000123"}""")

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
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
