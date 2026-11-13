package com.couli.feature.login

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
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
import java.time.Duration
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-ADAPT-CHECK ①（D10-1223 第 4、5 条，判据 4），测试 v2：发码入口在 200% 下要像按钮——入口节点自身（不借祖先）画出围住文字的底色或描边、
 * Role.Button、命中区 ≥48dp、footnote 令牌（[SendCodeLook]）。
 * - 组件级：绑号页 [BindCodeSendRow]，「获取验证码」（可点）与「60 秒后重新获取」（禁用）。
 * - 绑号页真实接线（BindPhoneScreen + BindPhoneService + LoginService，注入可推进的假时钟）：发码前、倒计时、倒计时走完后可再点，三态都要像按钮。
 * - 短信登录页真实接线（LoginScreen + LoginService + ApiClient + MockWebServer，注入可推进的假时钟）：手机号步【获取验证码】（已是实底按钮，阳性对照）；
 *   验证码步倒计时「N 秒后可重新获取」与倒计时走完后可点的「重新获取」；「验证码已发送至 138****8000」脱敏号整段不断（判据 1）。
 * - 默认字号：只保证「仍是右侧的链接色光文字（没有围住文字的底色或描边）、按钮语义、≥48dp、一行、footnote 令牌」。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumWrapSendCodeTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var server: MockWebServer
    private var now = START
    private var link = Color.Unspecified

    @Before fun start() {
        server = MockWebServer()
        repeat(SERVER_REPLIES) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":0,"msg":"","data":{"resend_after_sec":60,"expires_in_sec":300},"trace_id":"trace-000001"}"""),
            )
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    // ---------------------------------------------------------------- 组件级：BindCodeSendRow

    @Test fun bindRowW320Font200LightGetCode() = bindRow(320, dark = false, text = "获取验证码", enabled = true)

    @Test fun bindRowW320Font200LightCountdown() = bindRow(320, dark = false, text = "60 秒后重新获取", enabled = false)

    @Test fun bindRowW390Font200DarkGetCode() = bindRow(390, dark = true, text = "获取验证码", enabled = true)

    @Test fun bindRowDefaultFontStaysARightAlignedPlainLink() {
        rowContent(390, dark = false, scale = 1f, text = "获取验证码", enabled = true)
        val where = "BindCodeSendRow（默认字号）"
        val tagged = rule.onNodeWithTag(ROW_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val button = SendCodeLook.assertButtonSemantics(tagged, where)
        SendCodeLook.assertPlainText(rule, tagged, where)
        assertPlainStructure(ROW_TAG, where)
        val box = rule.onNodeWithTag(BOX_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val d = button.layoutInfo.density.density
        assertEquals("$where：仍靠右（和改前一样）", (box.right - 16 * d) / d, button.boundsInRoot.right / d, 2f)
    }

    // ---------------------------------------------------------------- 绑号页（真实 BindPhoneScreen + 注入时钟：发码前 / 倒计时 / 倒计时走完）

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun bindPageGetCodeW320Font200Light() = bindPage(dark = false, state = BIND_BEFORE)

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun bindPageCountdownW320Font200Light() = bindPage(dark = false, state = BIND_COUNTDOWN)

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun bindPageResendReadyW320Font200Light() = bindPage(dark = false, state = BIND_READY)

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xhdpi", fontScale = 2f)
    fun bindPageResendReadyW390Font200Dark() = bindPage(dark = true, state = BIND_READY)

    // ---------------------------------------------------------------- 短信登录页（真实接线）

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginPhoneStepGetCodeW320Font200IsAButton() {
        show(dark = false)
        rule.onNodeWithTag(LoginTags.GET_CODE, useUnmergedTree = true).performScrollTo()
        SendCodeLook.assertLooksLikeButton(rule, tagged(LoginTags.GET_CODE), "短信登录手机号步【获取验证码】")
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginCountdownW320Font200Light() = resendCase(dark = false, ready = false)

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginCountdownW390Font200Dark() = resendCase(dark = true, ready = false)

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginResendReadyW320Font200Light() = resendCase(dark = false, ready = true)

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginResendReadyW390Font200Light() = resendCase(dark = false, ready = true)

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginResendReadyW390Font200Dark() = resendCase(dark = true, ready = true)

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi", fontScale = 2f)
    fun smsLoginSentToMaskedPhoneW320Font200StaysOneRun() {
        toCodeStep(dark = false)
        val field = NumField(MASKED, "验证码步「已发送至」", NumWrap.within(LoginTags.PAGE), NumToken(CouliTypography.footnote.fontSize, 2f))
        NumWrap.assertField(rule, field)
    }

    @Test fun smsLoginResendDefaultFontStaysAPlainLinkOnTheRight() {
        toCodeStep(dark = false)
        resendReady()
        val where = "短信登录验证码步重新获取（默认字号）"
        val tagged = tagged(LoginTags.RESEND)
        val button = SendCodeLook.assertButtonSemantics(tagged, where)
        SendCodeLook.assertPlainText(rule, tagged, where)
        assertPlainStructure(LoginTags.RESEND, where)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$where：仍在右侧", button.boundsInRoot.center.x > root.center.x)
    }

    private fun resendCase(
        dark: Boolean,
        ready: Boolean,
    ) {
        toCodeStep(dark)
        if (ready) resendReady()
        rule.onNodeWithTag(LoginTags.RESEND, useUnmergedTree = true).performScrollTo()
        val where = "短信登录验证码步${if (ready) "「重新获取」" else "倒计时"}（200%，${if (dark) "深" else "浅"}）"
        SendCodeLook.assertLooksLikeButton(rule, tagged(LoginTags.RESEND), where)
        val text = NumWrap.layout(rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(LoginTags.RESEND), useUnmergedTree = true)[0])
        assertEquals("$where：不许缩字", CouliTypography.footnote.fontSize, text.layoutInput.style.fontSize)
    }

    private fun bindPage(
        dark: Boolean,
        state: String,
    ) {
        showBind(dark)
        rule.onNodeWithTag(BindPhoneTags.PHONE).performTextInput(PHONE)
        rule.waitForIdle()
        if (state != BIND_BEFORE) {
            rule.onNodeWithTag(BindPhoneTags.GET_CODE, useUnmergedTree = true).performScrollTo().performClick()
            waitForText(BindPhoneTags.GET_CODE, "60 秒后重新获取")
        }
        if (state == BIND_READY) {
            now += COUNTDOWN_MS
            waitForText(BindPhoneTags.GET_CODE, "获取验证码")
        }
        rule.onNodeWithTag(BindPhoneTags.GET_CODE, useUnmergedTree = true).performScrollTo()
        val where = "绑号页$state（200%，${if (dark) "深" else "浅"}）"
        SendCodeLook.assertLooksLikeButton(rule, tagged(BindPhoneTags.GET_CODE), where)
        val text = NumWrap.layout(rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(BindPhoneTags.GET_CODE), useUnmergedTree = true)[0])
        assertEquals("$where：不许缩字", CouliTypography.footnote.fontSize, text.layoutInput.style.fontSize)
    }

    private fun showBind(dark: Boolean) {
        // 绑号页要求已登录会话（同 BindPhoneScreenTest）：ApiClient 与两个服务共用同一份令牌
        val tokens = MemorySessionTokens("access-numwrap", "refresh-numwrap", SessionScopeRaw.FULL, "user-numwrap")
        val api = client(tokens)
        val login = LoginService(api, tokens, NoHost, pendingInvite = PendingInviteCode(), clock = { now })
        val service = BindPhoneService(api, tokens, login, PhoneBindingState(), NoHost, clock = { now })
        val config = BindPhonePageConfig(service = service, texts = DraftTexts, providerName = "wechat", clock = { now })
        rule.setContent { CouliTheme(darkTheme = dark) { BindPhoneScreen(config) } }
        rule.waitForIdle()
    }

    /** 推进主线程（每秒 tick）直到 [tag] 之下出现整串等于 [text] 的文字。 */
    private fun waitForText(
        tag: String,
        text: String,
    ) {
        rule.waitUntil(WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK_MS))
            rule.onAllNodes(hasText(text) and NumWrap.within(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun bindRow(
        screenDp: Int,
        dark: Boolean,
        text: String,
        enabled: Boolean,
    ) {
        rowContent(screenDp, dark, FONT_SCALE, text, enabled)
        val where = "BindCodeSendRow「$text」（w$screenDp×200%，${if (dark) "深" else "浅"}）"
        SendCodeLook.assertLooksLikeButton(rule, tagged(ROW_TAG), where)
        val layout = NumWrap.layout(rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(ROW_TAG), useUnmergedTree = true)[0])
        assertEquals("$where：不许缩字", CouliTypography.footnote.fontSize, layout.layoutInput.style.fontSize)
    }

    /** 默认字号的结构：一行、footnote 令牌、可点时是 text.link 色。 */
    private fun assertPlainStructure(
        tag: String,
        where: String,
    ) {
        val layout = NumWrap.layout(rule.onAllNodes(NumWrap.HAS_LAYOUT and NumWrap.within(tag), useUnmergedTree = true)[0])
        assertEquals("$where：文字一行", 1, layout.lineCount)
        assertEquals("$where：字号 footnote 令牌", CouliTypography.footnote.fontSize, layout.layoutInput.style.fontSize)
        assertEquals("$where：可点时是 text.link 色", link, layout.layoutInput.style.color)
    }

    private fun rowContent(
        screenDp: Int,
        dark: Boolean,
        scale: Float,
        text: String,
        enabled: Boolean,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                CouliTheme(darkTheme = dark) {
                    link = CouliColors.textLink
                    Column(
                        Modifier
                            .width(screenDp.dp)
                            .background(CouliColors.backgroundSurface)
                            .testTag(BOX_TAG)
                            .padding(horizontal = 16.dp),
                    ) {
                        BindCodeSendRow(text = text, enabled = enabled, tag = ROW_TAG, onClick = {})
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun client(tokens: MemorySessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixtureDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { now },
        )

    private fun show(dark: Boolean) {
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = MemorySessionTokens(),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { now },
            )
        val service = LoginService(api, MemorySessionTokens(), NoHost, pendingInvite = PendingInviteCode(), clock = { now })
        val config = LoginPageConfig(service = service, texts = DraftTexts, restricted = false, clock = { now })
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                link = CouliColors.textLink
                LoginScreen(config)
            }
        }
        rule.waitForIdle()
    }

    private fun toCodeStep(dark: Boolean) {
        show(dark)
        rule.onNodeWithTag(LoginTags.PHONE).performTextInput(PHONE)
        rule.onNodeWithTag(LoginTags.AGREE).performClick()
        rule.onNodeWithTag(LoginTags.GET_CODE).performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(hasTestTag(LoginTags.RESEND), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** 倒计时走完：推进假时钟 61 秒，让每秒的 tick 跑一次，等「重新获取」出现。 */
    private fun resendReady() {
        now += COUNTDOWN_MS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK_MS))
        rule.waitForIdle()
        rule.waitUntil(WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TICK_MS))
            rule.onAllNodes(hasText(RESEND) and NumWrap.within(LoginTags.RESEND), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tagged(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    /** 短信登录页用到的草稿文案（与 app 层 LoginDraftTexts 同值；其余键回落为键名）。 */
    private object DraftTexts : LoginTexts {
        private val map =
            mapOf(
                "login.get_code" to "获取验证码",
                "login.code.title" to "输入验证码",
                "login.code.sent_to" to "验证码已发送至 {phone}",
                "login.code.label" to "验证码",
                "login.code.countdown" to "{n} 秒后可重新获取",
                "login.code.resend" to RESEND,
                "login.phone.label" to "手机号",
                "login.phone.prefix" to "+86",
                "login.phone.placeholder" to "请输入手机号",
                "bind_phone.countdown" to "{sec} 秒后重新获取",
            )

        override fun text(key: String) = map[key] ?: key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ) = vars.entries.fold(text(key)) { acc, (k, v) -> acc.replace("{$k}", v) }
    }

    private object NoHost : LoginHost, BindPhoneHost {
        override fun phoneBound(owner: BindPhoneOwner) = Unit

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
        const val FONT_SCALE = 2f
        const val ROW_TAG = "numwrap_send_row"
        const val BOX_TAG = "numwrap_send_box"
        const val SERVER_REPLIES = 4
        const val WAIT_MS = 5_000L
        const val TICK_MS = 1_100L
        const val COUNTDOWN_MS = 61_000L
        const val START = 1_790_000_000_000L
        const val PHONE = "13800138000"
        const val MASKED = "138****8000"
        const val RESEND = "重新获取"
        const val BIND_BEFORE = "获取验证码"
        const val BIND_COUNTDOWN = "倒计时"
        const val BIND_READY = "倒计时走完"
    }
}
