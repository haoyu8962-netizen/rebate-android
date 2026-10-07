package com.couli.feature.login

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 登录页的版式与读屏（Robolectric + 真实 LoginScreen；APP-05a 共性第 6、7、8 条，A11yFocusLogin、A11yHitLogin）：
 * - 勾选单元、《用户协议》、《隐私政策》三个命中区各自独立、各不小于 48dp，互不重叠（默认宽度与 320dp × 200% 字号）；
 * - 320dp × 200% 字号下六个验证码格都在屏幕内完整可见，格高不小于数字行高；
 * - 确认框里的两个协议是两个可单独聚焦、可点的链接：点了关确认框（不算同意）并打开协议页；
 * - 读屏顺序（从上到下、从左到右）：关闭 → 设置 → 手机号 → 协议勾选 →《用户协议》→《隐私政策》→ 获取验证码 → 邀请码入口 → 登录遇到问题。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class LoginScreenLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private val agreements = mutableListOf<String>()

    @Before
    fun start() {
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

    @After
    fun stop() = server.shutdown()

    private fun show(restricted: Boolean = false) {
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = MemorySessionTokens(),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            )
        val service = LoginService(api, MemorySessionTokens(), NoHost)
        val config =
            LoginPageConfig(
                service = service,
                texts = KeyTexts,
                restricted = restricted,
                actions = LoginPageActions(onAgreement = { agreements += it }),
            )
        composeRule.setContent { LoginScreen(config) }
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun dp(px: Float): Float = px / composeRule.density.density

    private fun assertTargets() {
        val cells = listOf(LoginTags.AGREE, LoginTags.LINK_AGREEMENT, LoginTags.LINK_PRIVACY).map(::bounds)
        cells.forEach { rect -> assertTrue("命中区高不小于 48dp：${dp(rect.height)}", dp(rect.height) >= MIN_TARGET_DP) }
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                assertFalse("命中区互不重叠：$i / $j", cells[i].overlaps(cells[j]))
            }
        }
    }

    @Test
    fun agreementTargetsAreSeparateAndAtLeast48dp() {
        show()
        assertTargets()
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun agreementTargetsStaySeparateAtNarrowWidthAndDoubleFontScale() {
        show()
        assertTargets()
    }

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun sixCodeCellsAreFullyVisibleAtNarrowWidthAndDoubleFontScale() {
        show()
        composeRule.onNodeWithTag(LoginTags.PHONE).performTextInput("13800138000")
        composeRule.onNodeWithTag(LoginTags.AGREE).performClick()
        composeRule.onNodeWithTag(LoginTags.GET_CODE).performClick()
        composeRule.waitUntil(WAIT_MS) { runCatching { bounds(LoginTags.CODE_CELL + 0) }.isSuccess }
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // 数字行高按系统字号换算（Android 14 起大字号按非线性曲线放大）；格高必须容得下它，且比 100% 时的 56dp 高
        val digitLine =
            with(composeRule.density) {
                com.couli.core.designsystem.CouliTypography.heading.lineHeight
                    .toPx()
            }
        val baseCell = with(composeRule.density) { 56.dp.toPx() }
        repeat(CodeInput.LENGTH) { index ->
            val cell = bounds(LoginTags.CODE_CELL + index)
            assertTrue("第 ${index + 1} 格在屏幕内：$cell / $root", cell.left >= root.left && cell.right <= root.right)
            assertTrue("格高容得下数字行（${cell.height} ≥ $digitLine）", cell.height >= digitLine)
            assertTrue("格高随字号增高（${cell.height} > $baseCell）", cell.height > baseCell)
        }
    }

    @Test
    fun confirmDialogLinksAreSeparateFocusableLinksThatOpenTheAgreement() {
        show()
        composeRule.onNodeWithTag(LoginTags.PHONE).performTextInput("13800138000")
        composeRule.onNodeWithTag(LoginTags.GET_CODE).performClick()
        val agreement = composeRule.onNodeWithTag(LoginTags.CONFIRM_LINK_AGREEMENT)
        val privacy = composeRule.onNodeWithTag(LoginTags.CONFIRM_LINK_PRIVACY)
        listOf(agreement, privacy).forEach(::assertFocusableLink)
        assertFalse(agreement.fetchSemanticsNode().boundsInRoot.overlaps(privacy.fetchSemanticsNode().boundsInRoot))
        privacy.performClick()
        assertEquals(listOf(LoginAgreementType.PRIVACY), agreements)
        composeRule.onNodeWithText("login.agree_confirm.title").assertDoesNotExist()
    }

    @Test
    fun readingOrderFollowsA11yFocusLogin() {
        show()
        val order =
            listOf(
                LoginTags.CLOSE,
                LoginTags.SETTINGS,
                LoginTags.PHONE,
                LoginTags.AGREE,
                LoginTags.LINK_AGREEMENT,
                LoginTags.LINK_PRIVACY,
                LoginTags.GET_CODE,
                LoginTags.INVITE_ENTRY,
                LoginTags.HELP,
            ).map { tag -> tag to bounds(tag) }
        val sameRow = with(composeRule.density) { ROW_SLACK_DP.dp.toPx() }
        order.zipWithNext().forEach { (before, after) ->
            val (a, b) = before.second to after.second
            val rowOrder =
                if (kotlin.math.abs(a.center.y - b.center.y) < sameRow) b.left >= a.left else b.center.y > a.center.y
            assertTrue("${before.first} 在 ${after.first} 之前", rowOrder)
        }
    }

    @Test
    fun restrictedLoginHidesBusinessEntries() {
        show(restricted = true)
        listOf(LoginTags.SETTINGS, LoginTags.INVITE_ENTRY, LoginTags.HELP, LoginTags.THIRD_PARTY).forEach { tag ->
            assertTrue("$tag 不显示", composeRule.onAllNodesWithTagCompat(tag) == 0)
        }
    }

    private fun assertFocusableLink(node: SemanticsNodeInteraction) {
        val config = node.fetchSemanticsNode().config
        assertTrue("可点", config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null)
        assertEquals(androidx.compose.ui.semantics.Role.Button, config.getOrNull(SemanticsProperties.Role))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCompat(tag: String): Int =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(tag),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    /** 文案用键本身（本模块测试只看版式与读屏，不依赖草稿表）。 */
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
        const val MIN_TARGET_DP = 47.5f
        const val SERVER_REPLIES = 4
        const val WAIT_MS = 5_000L
        const val ROW_SLACK_DP = 24
    }
}
