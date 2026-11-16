package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.CallSiteIdentityPin
import com.couli.app.CallSiteIdentityPin.ACCESS_A
import com.couli.app.CallSiteIdentityPin.ACCOUNT_A
import com.couli.app.CallSitePageApi
import com.couli.app.CallSiteServer
import com.couli.app.CallSiteTokens
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.ParseInputService
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiResult
import com.couli.core.network.HeaderNames
import com.couli.core.network.LocalRejectReason
import com.couli.core.router.GuardDecision
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.search.SearchViewModel
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-STEPUP-SUMMARY 调用点级用例（第二位独立写手，补评审 S2-1）：03 §4.2「固定账号的核对」在解析输入（parseInput）三处真实调用点上的落地。
 * 装配与 AppModule 同一套：AppClipboard 的 `sessionAccount = { client.activeUserId() }`（AppModule.kt:1282），真实 ApiClient → MockWebServer；
 * 入口都是生产入口：自动识别（进程 ON_START）、手动粘贴（paste）——两者都经 `launchParse`（AppClipboard.kt:1151）；系统分享提示条的按钮
 * （`onPromptAction`，AppClipboard.kt:1264）；搜索框识别（SearchViewModel.onSubmit → AppSearch 的 ClipboardSearchSubmit，AppSearch.kt:362）。
 *
 * 剪贴板的登录世代来源（sessionEpoch）两种接法：
 * - 生产接法 `{ tokens.loginEpoch }`（[Wiring.PRODUCTION]）：`launchParse` 的在途归属只在会话通知（生产经 main.post 晚到）时作废，点击之后、
 *   派发之前退成游客的这段窗口里只有固定账号挡着——用例按生产接法钉它；
 * - 提示条与搜索框的归属自己同步比对登录世代，生产接法下退成游客会先被归属拦下（零发送，用例也钉了），固定账号是第二层；要单独钉住这一层，
 *   用 [Wiring.EPOCH_NOT_OBSERVED]：登录世代来源不跟着令牌变（与已有夹具 Clipboard0724Fixture / SearchSubmitFixture 一样由测试控制），
 *   模拟「会话已变、剪贴板还没看到」，这时只有固定账号能挡住匿名发出。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个入口 × 会员 / 换成游客 各一个用例
class ParseInputCallSiteIdentityPinTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val service = CallSiteServer { request -> if (request.path.orEmpty().startsWith(PARSE_PATH)) PARSED else EMPTY_PAGE }
    private val harnesses = mutableListOf<Harness>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = service
        server.start()
    }

    @After
    fun stop() {
        harnesses.forEach(Harness::close)
        server.shutdown()
    }

    // ---------------------------------------------------------------- launchParse（AppClipboard.kt:1151）：自动、手动

    @Test
    fun automaticParseByAMemberPinsTheAccountAndCarriesItsToken() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = true)
        h.system.content = ClipRead.Text(LINK)
        h.foreground()
        h.awaitParse()

        assertMemberParse(h)
    }

    @Test
    fun automaticParseWhoseSessionBecomesGuestBeforeDispatchIsRejected() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = true)
        h.api.beforeSend = { if (it.operationId == ParseInputService.OPERATION) h.tokens.inner.clear() }
        h.system.content = ClipRead.Text(LINK)
        h.foreground()
        h.awaitParse()

        assertMismatch(h)
    }

    @Test
    fun manualPasteByAMemberPinsTheAccountAndCarriesItsToken() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false)
        h.foreground()
        h.system.content = ClipRead.Text(LINK)
        h.board.paste(ManualPasteAction.ENTRY_HOME)
        h.awaitParse()

        assertMemberParse(h)
    }

    @Test
    fun manualPasteWhoseSessionBecomesGuestBeforeDispatchIsRejected() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false)
        h.foreground()
        h.api.beforeSend = { if (it.operationId == ParseInputService.OPERATION) h.tokens.inner.clear() }
        h.system.content = ClipRead.Text(LINK)
        h.board.paste(ManualPasteAction.ENTRY_HOME)
        h.awaitParse()

        assertMismatch(h)
    }

    @Test
    fun manualPasteByAGuestIsNotPinnedAndGoesOutAnonymously() {
        val h = harness(CallSiteIdentityPin.guest(), autoOn = false)
        h.foreground()
        h.system.content = ClipRead.Text(LINK)
        h.board.paste(ManualPasteAction.ENTRY_HOME)
        h.awaitParse()

        assertNull("游客不固定账号", h.api.callOf(ParseInputService.OPERATION)?.expectedAccount)
        assertTrue(h.api.resultOf(ParseInputService.OPERATION) is ApiResult.Success)
        assertNull("确认游客不带令牌", service.to(PARSE_PATH).single().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- 提示条按钮（AppClipboard.kt:1264）

    @Test
    fun sharePromptActionByAMemberPinsTheAccountAndCarriesItsToken() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false)
        h.share()
        h.board.onPromptAction(checkNotNull(h.board.prompt.value).id)
        h.awaitParse()

        assertMemberParse(h)
    }

    @Test
    fun sharePromptActionWhoseSessionBecomesGuestBeforeDispatchIsRejectedByThePin() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false, wiring = Wiring.EPOCH_NOT_OBSERVED)
        h.share()
        h.api.beforeSend = { if (it.operationId == ParseInputService.OPERATION) h.tokens.inner.clear() }
        h.board.onPromptAction(checkNotNull(h.board.prompt.value).id)
        h.awaitParse()

        assertMismatch(h)
    }

    @Test
    fun sharePromptActionWithProductionEpochWiringAlsoSendsNothingAfterTheMemberLeaves() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false)
        h.share()
        h.api.beforeSend = { if (it.operationId == ParseInputService.OPERATION) h.tokens.inner.clear() }
        h.board.onPromptAction(checkNotNull(h.board.prompt.value).id)
        h.awaitParse()

        val result = h.api.resultOf(ParseInputService.OPERATION)
        assertTrue(result.toString(), result is ApiResult.LocalReject)
        assertEquals("不降成匿名发出", 0, service.to(PARSE_PATH).size)
    }

    // ---------------------------------------------------------------- 搜索框识别（AppSearch.kt:362）

    @Test
    fun searchBoxRecognitionByAMemberPinsTheAccountAndCarriesItsToken() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false)
        h.submitInSearchBox(LINK)
        h.awaitParse()

        assertMemberParse(h)
    }

    @Test
    fun searchBoxRecognitionWhoseSessionBecomesGuestBeforeDispatchIsRejectedByThePin() {
        val h = harness(CallSiteIdentityPin.memberA(), autoOn = false, wiring = Wiring.EPOCH_NOT_OBSERVED)
        h.api.beforeSend = { if (it.operationId == ParseInputService.OPERATION) h.tokens.inner.clear() }
        h.submitInSearchBox(LINK)
        h.awaitParse()

        assertMismatch(h)
    }

    // ---------------------------------------------------------------- 断言

    private fun assertMemberParse(h: Harness) {
        assertTrue(h.api.resultOf(ParseInputService.OPERATION).toString(), h.api.resultOf(ParseInputService.OPERATION) is ApiResult.Success)
        assertEquals("带该账号的令牌", "Bearer $ACCESS_A", service.to(PARSE_PATH).single().getHeader(HeaderNames.AUTHORIZATION))
        assertEquals("parse 固定发起这一刻的会员账号", ACCOUNT_A, h.api.callOf(ParseInputService.OPERATION)?.expectedAccount)
    }

    private fun assertMismatch(h: Harness) {
        // 先断行为，再断调用点取到的账号：变异去掉固定账号时，红在「匿名发出了」上
        assertEquals("传输层收到 0 个 parse", 0, service.to(PARSE_PATH).size)
        assertEquals(
            "派发前退成游客：固定账号核对本地拒绝，不降成匿名",
            ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH),
            h.api.resultOf(ParseInputService.OPERATION),
        )
        assertEquals("调用点取账号时仍是会员 A", ACCOUNT_A, h.api.callOf(ParseInputService.OPERATION)?.expectedAccount)
    }

    // ---------------------------------------------------------------- 装配

    private enum class Wiring { PRODUCTION, EPOCH_NOT_OBSERVED }

    private fun harness(
        tokens: CallSiteTokens,
        autoOn: Boolean,
        wiring: Wiring = Wiring.PRODUCTION,
    ): Harness = Harness(tokens, autoOn, wiring).also { harnesses += it }

    private inner class Harness(
        val tokens: CallSiteTokens,
        autoOn: Boolean,
        wiring: Wiring,
    ) {
        private val preferenceName = "callsite-pin-${UUID.randomUUID()}"
        private val disk = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        private val prefs = ClipboardPreferences(disk, SampleCipher)
        private val importPreferences = prefs.importPreferences { NOW }
        val system = CountingClipboard0724().also { it.content = ClipRead.Unavailable }
        val client: ApiClient = CallSiteIdentityPin.client(server, tokens)
        val api = CallSitePageApi(ApiClientPageApi(client))
        private val routing =
            AppRouting(
                context,
                EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
                clock = { NOW },
                initialPrivacy = PrivacyState.CONSENTED,
                initialUpdate = UpdateCheck.OK,
            ).also { it.attach(Navigator) }
        private val fixedEpoch = tokens.loginEpoch
        val board: AppClipboard
        private val process = Process()
        private val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, CONFIG, null)) },
                clock = { NOW },
                bundled = CONFIG,
            )

        init {
            if (autoOn) assertTrue(importPreferences.setOn()) else assertTrue(importPreferences.setOn() && importPreferences.setOff())
            board =
                AppClipboard(
                    system = system,
                    api = api,
                    routing = routing,
                    privacy = { PrivacyState.CONSENTED },
                    promptShowing = { false },
                    configData = { config },
                    prefs = prefs,
                    importPreferences = importPreferences,
                    sessionEpoch =
                        when (wiring) {
                            // 与 AppModule.kt:1281 同一接线
                            Wiring.PRODUCTION -> ({ tokens.loginEpoch })

                            Wiring.EPOCH_NOT_OBSERVED -> ({ fixedEpoch })
                        },
                    // 与 AppModule.kt:1282 同一接线
                    sessionAccount = { client.activeUserId() },
                    clock = { NOW },
                    randomSalt = { SAMPLE_SALT.copyOf() },
                    pendingInvite = PendingInviteCode(),
                )
            board.bindProcessLifecycle(process.lifecycle)
            process.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            board.onShellAttached()
            board.onShell("Home", false)
            board.onBackStack(null, null, emptySet())
            board.onWindowFocus(true)
            CallSiteIdentityPin.settle()
        }

        fun foreground() {
            process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            CallSiteIdentityPin.settle()
        }

        /** 系统分享到达（接收 Activity 调的同一个入口），回到首页前台后出确认提示条。 */
        fun share() {
            assertTrue(board.receiveShare("text/plain", LINK))
            foreground()
            CallSiteIdentityPin.idleUntil { board.prompt.value?.share == true }
        }

        /** 搜索结果页输入框提交（SearchViewModel 的生产提交入口）。 */
        fun submitInSearchBox(text: String) {
            val app = AppSearch(client, store, routing, privacy = { PrivacyState.CONSENTED }, api = api, clipboard = board)
            val config = app.searchConfig(RouteDestination("Search", "{}", readonly = false), SEARCH_ENTRY, {})
            val model = SearchViewModel(config, SavedStateHandle())
            CallSiteIdentityPin.settle()
            model.onInput(text)
            model.onSubmit()
        }

        fun awaitParse() {
            CallSiteIdentityPin.idleUntil { api.resultOf(ParseInputService.OPERATION) != null }
            CallSiteIdentityPin.settle()
        }

        fun close() {
            process.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private class Process : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private object Navigator : RouteNavigator {
        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) = Unit

        override fun showUpgradePrompt(request: RouteRequest) = Unit

        override fun requestConsent(request: RouteRequest) = Unit

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) = Unit
    }

    private val config: JsonObject = Json.parseToJsonElement(CONFIG) as JsonObject

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val LINK = "https://e.tb.cn/h.AbCdEf?tk=Xy12"
        const val PARSE_PATH = "/v1/inputs/parse"
        const val SEARCH_ENTRY = "search-entry"
        const val CONFIG =
            """{"clipboard":{"consent_version":"1"},"share_domains":["share.example.test"],""" +
                """"features":{"flags":{},"search_status":{"taobao":"on","jd":"off","pdd":"off"},"platform_status":{"taobao":"on"}}}"""
        const val EMPTY_PAGE = """{"items":[],"next_cursor":null,"has_more":false,"fallback_items":[]}"""
        const val PARSED =
            """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},""" +
                """"card":{"product_key":"tb:pin","item_ref":"v1.ref.sig",""" +
                """"platform":"taobao","title":"固定账号商品","price_fen":3990,"final_price_fen":2990,"coupon_fen":1000,"rebate_min_fen":269,""" +
                """"rebate_max_fen":269,"rebate_basis":"normal","link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70","availability":"ok"}}]}"""
    }
}
