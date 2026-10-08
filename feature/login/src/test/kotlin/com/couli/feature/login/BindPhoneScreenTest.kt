package com.couli.feature.login

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 绑手机页与登录页第三方按钮（Robolectric + 真实页面 + 本地假服务；APP-05b）：
 * - 共性第 7 条：320dp × 200% 字号下手机号、验证码、获取验证码与底部【绑定】都在屏幕内；
 * - 共性第 6 条：20002 验证码错误 → 错误提示是实时区域，验证码输入框带 error 语义，输入保留；
 * - 共性第 5 条：44001 → 风控弹窗，有【联系客服申诉】；
 * - 共性第 10 条：10007 stage=cooling → 经出口打开 DeleteAccount；
 * - 并号：30411 mergeable → 并号确认；【取消】不发请求、输入保留；【并入】不发请求、提示暂不可用；
 * - 登录页：有可显示的第三方按钮才出现「其他登录方式」与「微信登录」按钮；正式（不可用）适配器下整块不出现。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class BindPhoneScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val tokens = MemorySessionTokens("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val opened = mutableListOf<String>()

    @Volatile
    private var bindAnswer: () -> MockResponse = { envelope(0, "{}") }

    @Volatile
    private var smsAnswer: () -> MockResponse = { envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""") }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path.orEmpty()
                    return when (request.path) {
                        "/v1/me/phone" -> bindAnswer()
                        "/v1/auth/sms-codes" -> smsAnswer()
                        else -> envelope(0, """{"phone_bound":true}""")
                    }
                }
            }
        server.start()
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
    }

    @After
    fun stop() = server.shutdown()

    private fun showBind() {
        val login = LoginService(api, tokens, NoHost)
        val service = BindPhoneService(api, tokens, login, PhoneBindingState(), NoHost)
        val config =
            BindPhonePageConfig(
                service = service,
                texts = KeyTexts,
                providerName = "wechat",
                actions = BindPhonePageActions(onOpenRoute = { opened += it }),
            )
        composeRule.setContent { BindPhoneScreen(config) }
    }

    private fun fillAndSubmit() {
        composeRule.onNodeWithTag(BindPhoneTags.PHONE).performTextInput(PHONE)
        composeRule.onNodeWithTag(BindPhoneTags.CODE).performTextInput("123456")
        composeRule.onNodeWithTag(BindPhoneTags.SUBMIT).performClick()
        waitUntil { paths.contains("/v1/me/phone") }
        composeRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi", fontScale = 2f)
    fun inputsAndTheBindButtonStayOnScreenAtNarrowWidthAndDoubleFontScale() {
        showBind()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        listOf(BindPhoneTags.PHONE, BindPhoneTags.CODE, BindPhoneTags.GET_CODE, BindPhoneTags.SUBMIT).forEach { tag ->
            val rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag 在屏幕内：$rect / $root", rect.top >= root.top && rect.bottom <= root.bottom + 1f && rect.width > 0f)
        }
    }

    @Test
    fun aWrongCodeIsAnnouncedNextToTheCodeInputAndTheInputIsKept() {
        bindAnswer = { envelope(20002, "null") }
        showBind()
        fillAndSubmit()
        val error = composeRule.onNodeWithTag(BindPhoneTags.CODE_ERROR).fetchSemanticsNode()
        assertNotNull("实时区域", error.config.getOrNull(SemanticsProperties.LiveRegion))
        val input = composeRule.onNodeWithTag(BindPhoneTags.CODE).fetchSemanticsNode()
        assertEquals("error.20002", input.config.getOrNull(SemanticsProperties.Error))
        assertEquals("123456", input.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    @Test
    fun aRiskBlockShowsTheAppealEntry() {
        bindAnswer = { envelope(44001, "null") }
        showBind()
        fillAndSubmit()
        composeRule.onNodeWithText("error.44001").assertExists()
        composeRule.onNodeWithText("cs.appeal_entry").assertExists()
    }

    @Test
    fun aDeletionCoolingAccountOpensTheDeletionProgressPage() {
        bindAnswer = { envelope(10007, """{"stage":"cooling"}""") }
        showBind()
        fillAndSubmit()
        waitUntil { opened.isNotEmpty() }
        assertEquals(listOf("DeleteAccount"), opened)
    }

    /** 共性第 10、16 条（R1-05）：绑手机发码收到 10007 stage=cooling → 经出口（App 接 Router）打开 DeleteAccount，不弹提示。 */
    @Test
    fun aBindCodeDuringDeletionCoolingOpensTheDeletionProgressPage() {
        smsAnswer = { envelope(10007, """{"stage":"cooling"}""") }
        showBind()
        composeRule.onNodeWithTag(BindPhoneTags.PHONE).performTextInput(PHONE)
        composeRule.onNodeWithTag(BindPhoneTags.GET_CODE).performClick()
        waitUntil { opened.isNotEmpty() }
        assertEquals(listOf("DeleteAccount"), opened)
        assertTrue("不弹处理中提示", composeRule.onAllNodesWithText("error.10007", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** 共性第 10 条（R1-05）：stage=processing 只提示 error.10007，不打开页面。 */
    @Test
    fun aBindCodeDuringDeletionProcessingOnlyShowsTheMessage() {
        smsAnswer = { envelope(10007, """{"stage":"processing"}""") }
        showBind()
        composeRule.onNodeWithTag(BindPhoneTags.PHONE).performTextInput(PHONE)
        composeRule.onNodeWithTag(BindPhoneTags.GET_CODE).performClick()
        waitUntil { composeRule.onAllNodesWithText("error.10007", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun mergeConfirmationCancelAndConfirmSendNothing() {
        bindAnswer = { envelope(30411, """{"reason":"mergeable","merge_ticket":"ticket-placeholder"}""") }
        showBind()
        fillAndSubmit()
        composeRule.onNodeWithText("bind_phone.merge.title").assertExists()
        composeRule.onNodeWithText("error.30411.mergeable").assertExists()
        val before = paths.size
        composeRule.onNodeWithText("bind_phone.merge.cancel").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("bind_phone.merge.title").fetchSemanticsNodes().isEmpty())
        assertEquals("取消不发请求", before, paths.size)
        assertEquals(
            PHONE,
            composeRule
                .onNodeWithTag(BindPhoneTags.PHONE)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text,
        )

        composeRule.onNodeWithTag(BindPhoneTags.SUBMIT).performClick()
        waitUntil { paths.size > before }
        composeRule.waitForIdle()
        val afterBind = paths.size
        composeRule.onNodeWithText("bind_phone.merge.ok").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("bind_phone.merge.unavailable").assertExists()
        assertEquals("并入不伪造请求", afterBind, paths.size)
    }

    @Test
    fun theLoginPageShowsTheWechatButtonOnlyWhenAProviderIsAvailable() {
        val login = LoginService(api, tokens, NoHost)
        val oauth = OAuthLoginService(api, tokens, login, listOf(UnavailableWechatAdapter()), PhoneBindingState())
        val providers = mutableListOf<String>()
        composeRule.setContent {
            LoginScreen(
                LoginPageConfig(service = login, texts = KeyTexts, restricted = false, oauth = oauth, thirdParty = providers.toList()),
            )
        }
        assertTrue("正式适配器不可用：整块不出现", composeRule.onAllNodesWithText("login.other_methods").fetchSemanticsNodes().isEmpty())
        assertTrue(oauth.visibleProviders().isEmpty())
    }

    @Test
    fun theWechatButtonHasItsAccessibleNameWhenShown() {
        val login = LoginService(api, tokens, NoHost)
        val oauth = OAuthLoginService(api, tokens, login, emptyList(), PhoneBindingState())
        composeRule.setContent {
            LoginScreen(
                LoginPageConfig(
                    service = login,
                    texts = KeyTexts,
                    restricted = false,
                    oauth = oauth,
                    thirdParty = listOf(OAuthProvider.WECHAT),
                ),
            )
        }
        val button = composeRule.onNodeWithTag(LoginTags.OAUTH_PREFIX + OAuthProvider.WECHAT).fetchSemanticsNode()
        assertEquals(listOf("login.wechat"), button.config.getOrNull(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithText("login.other_methods").assertExists()
    }

    /** 共性第 11 条：发码成功播报一次脱敏的发送对象；同一号码倒计时走完、可以重发时播报一次；不逐秒播报。 */
    @Test
    fun sendingAnnouncesTheMaskedTargetOnceAndTheEndOfTheCountdownOnce() {
        var now = 1_000_000L
        val login = LoginService(api, tokens, NoHost) { now }
        val service = BindPhoneService(api, tokens, login, PhoneBindingState(), NoHost) { now }
        val model = BindPhoneViewModel(BindPhonePageConfig(service, KeyTexts, "wechat", clock = { now }), SavedStateHandle())
        model.onPhoneChange(PHONE)
        model.onGetCode()
        waitUntil {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            model.state.value.announceId > 0
        }
        assertEquals("login.code.sent_to", model.state.value.announce)
        assertEquals(1, model.state.value.announceId)
        now += 30_000L
        model.onPageVisible()
        assertEquals("倒计时中不逐秒播报", 1, model.state.value.announceId)
        now += 31_000L
        model.onPageVisible()
        assertEquals(2, model.state.value.announceId)
        assertEquals("bind_phone.can_resend", model.state.value.announce)
        model.onPageVisible()
        assertEquals("只播报一次", 2, model.state.value.announceId)
    }

    /** 发码成功（号码 [PHONE]）并等到发码播报；返回 ViewModel 与可调的时钟。 */
    private fun sentModel(clock: LongArray): BindPhoneViewModel {
        val login = LoginService(api, tokens, NoHost) { clock[0] }
        val service = BindPhoneService(api, tokens, login, PhoneBindingState(), NoHost) { clock[0] }
        val model = BindPhoneViewModel(BindPhonePageConfig(service, KeyTexts, "wechat", clock = { clock[0] }), SavedStateHandle())
        model.onPhoneChange(PHONE)
        model.onGetCode()
        waitUntil {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            model.state.value.announceId > 0
        }
        assertEquals("login.code.sent_to", model.state.value.announce)
        return model
    }

    /** 共性第 11、17 条（R1-07）：倒计时中清空号码 → 不播报「可以重新获取」，之后旧号码的截止时刻到了也不播报。 */
    @Test
    fun clearingThePhoneDuringTheCountdownAnnouncesNothing() {
        val clock = longArrayOf(1_000_000L)
        val model = sentModel(clock)
        clock[0] += 10_000L
        model.onClearPhone()
        assertEquals("清空不播报", 1, model.state.value.announceId)
        assertEquals(0, model.state.value.resendSec)
        clock[0] += 60_000L
        model.onPageVisible()
        assertEquals("旧号码的倒计时结束不播报", 1, model.state.value.announceId)
    }

    /** 共性第 17 条（R1-07）：倒计时中改成另一个号码 → 不播报；旧号码截止时刻到了也不播报。 */
    @Test
    fun switchingToAnotherNumberDuringTheCountdownAnnouncesNothing() {
        val clock = longArrayOf(1_000_000L)
        val model = sentModel(clock)
        clock[0] += 10_000L
        model.onPhoneChange(OTHER_PHONE)
        assertEquals("换号不播报", 1, model.state.value.announceId)
        clock[0] += 60_000L
        model.onPageVisible()
        assertEquals("旧号码的倒计时结束不播报", 1, model.state.value.announceId)
    }

    /**
     * 共性第 17 条（R1-07）：换号后在倒计时内切回原号码 → 切换本身不播报；原号码真实的截止时刻到了才播报一次。
     */
    @Test
    fun switchingBackToTheOriginalNumberAnnouncesOnlyAtItsRealDeadline() {
        val clock = longArrayOf(1_000_000L)
        val model = sentModel(clock)
        clock[0] += 10_000L
        model.onPhoneChange(OTHER_PHONE)
        clock[0] += 10_000L
        model.onPhoneChange(PHONE)
        assertEquals("切回不播报", 1, model.state.value.announceId)
        assertTrue("原号码仍在倒计时", model.state.value.resendSec in 1..40)
        clock[0] += 39_000L
        model.onPageVisible()
        assertEquals("截止时刻还没到", 1, model.state.value.announceId)
        clock[0] += 2_000L
        model.onPageVisible()
        assertEquals(2, model.state.value.announceId)
        assertEquals("bind_phone.can_resend", model.state.value.announce)
        model.onPageVisible()
        assertEquals("只播报一次", 2, model.state.value.announceId)
    }

    /** 共性第 17 条（R1-07）：原号码的倒计时在显示别的号码时结束，之后才切回 → 不播报（切换引起的变化不算）。 */
    @Test
    fun switchingBackAfterTheOriginalDeadlineAnnouncesNothing() {
        val clock = longArrayOf(1_000_000L)
        val model = sentModel(clock)
        model.onPhoneChange(OTHER_PHONE)
        clock[0] += 70_000L
        model.onPageVisible()
        model.onPhoneChange(PHONE)
        assertEquals(0, model.state.value.resendSec)
        model.onPageVisible()
        assertEquals(1, model.state.value.announceId)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_NS
        while (!condition() && System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        assertTrue(condition())
    }

    private object KeyTexts : LoginTexts {
        override fun text(key: String) = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ) = key
    }

    private object NoHost : LoginHost, BindPhoneHost {
        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = false

        override fun closeLogin() = Unit

        override fun resumePending() = Unit

        override fun discardPending() = Unit

        override fun enterForceUpdate() = Unit

        override fun sessionChanged() = Unit

        override fun sessionSynced() = Unit

        override fun sessionEnded() = Unit

        override fun phoneBound() = Unit
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val PHONE = "13800138000"
        const val OTHER_PHONE = "13900139000"
        const val WAIT_NS = 5_000_000_000L
        const val POLL_MS = 20L

        fun envelope(
            code: Int,
            data: String,
        ) = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-000888"}""")
    }
}
