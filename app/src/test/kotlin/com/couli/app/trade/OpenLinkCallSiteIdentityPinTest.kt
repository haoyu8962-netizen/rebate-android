package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.CallSiteIdentityPin
import com.couli.app.CallSiteIdentityPin.ACCESS_B
import com.couli.app.CallSiteIdentityPin.ACCOUNT_A
import com.couli.app.CallSiteIdentityPin.ACCOUNT_B
import com.couli.app.CallSiteIdentityPin.REFRESH_B
import com.couli.app.CallSitePageApi
import com.couli.app.CallSiteServer
import com.couli.app.CallSiteTokens
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiResult
import com.couli.core.network.HeaderNames
import com.couli.core.network.LocalRejectReason
import com.couli.core.router.PrivacyState
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import java.util.Collections
import javax.inject.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-STEPUP-SUMMARY 调用点级用例（第二位独立写手，补评审 S2-1 / 变异 M7b 的缺口）：03 §4.2「固定账号的核对」在购买（openLink）
 * 真实调用点上的落地。
 *
 * 装配与 AppModule 同一套：AppTrade（`sessionAccount = { client.activeUserId() }`，AppModule.kt:1159）→ AppSearch 的发起页 PurchaseEnv
 * （`account()` 委托 AppTrade）→ 详情页【购买】的真实入口 `ProductPageConfig.actions.onBuyIntent` → OpenPurchaseController.send
 * （在建归属的同一回合取账号，放进 openLink 的 expectedAccount）→ 真实 ApiClient → MockWebServer。
 *
 * - 会员点购买：发出的 open 固定会员账号，请求带该账号的 Authorization；
 * - 会员点购买之后、派发之前会话换了（退成游客 / 换成另一账号 / 只剩 refresh）：本地拒 account_mismatch、传输层 0 个 open、不外跳——
 *   不降成匿名发出（否则转链登记到游客名下）；
 * - 游客点购买：不固定账号，匿名照发（对照）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class OpenLinkCallSiteIdentityPinTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val service = CallSiteServer { openResult() }
    private val launched: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = service
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun memberOpenPinsTheMembersAccountAndCarriesItsToken() {
        val tokens = CallSiteIdentityPin.memberA()
        val api = buy(tokens)

        assertTrue(api.resultOf(TradeOperations.OPEN_LINK).toString(), api.resultOf(TradeOperations.OPEN_LINK) is ApiResult.Success)
        val open = service.to(OPEN_PREFIX).single()
        assertEquals("带该账号的令牌", "Bearer ${CallSiteIdentityPin.ACCESS_A}", open.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals("open 固定点击这一刻的会员账号", ACCOUNT_A, api.callOf(TradeOperations.OPEN_LINK)?.expectedAccount)
    }

    @Test
    fun memberWhoBecomesGuestBeforeDispatchIsRejectedNotSentAnonymously() = assertRejectedAfterSwitch { tokens -> tokens.inner.clear() }

    @Test
    fun memberWhoseSessionSwitchesToAnotherAccountBeforeDispatchIsRejected() =
        assertRejectedAfterSwitch { tokens -> tokens.inner.save(ACCESS_B, REFRESH_B, "full", ACCOUNT_B) }

    @Test
    fun memberWhoseSessionLosesItsAccessTokenBeforeDispatchIsRejectedAsMismatch() =
        assertRejectedAfterSwitch { tokens -> tokens.refreshOnly = true }

    @Test
    fun guestOpenIsNotPinnedAndStillGoesOutAnonymously() {
        val api = buy(CallSiteIdentityPin.guest())

        assertNull("游客不固定账号", api.callOf(TradeOperations.OPEN_LINK)?.expectedAccount)
        assertTrue(api.resultOf(TradeOperations.OPEN_LINK) is ApiResult.Success)
        assertNull("确认游客不带令牌", service.to(OPEN_PREFIX).single().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- 装配

    private fun assertRejectedAfterSwitch(switch: (CallSiteTokens) -> Unit) {
        val tokens = CallSiteIdentityPin.memberA()
        val api = buy(tokens) { switch(tokens) }

        // 先断行为（传输层、结果、外跳），再断调用点取到的账号：变异去掉固定账号时，红在「匿名发出了」上
        assertEquals("传输层收到 0 个 open", 0, service.to(OPEN_PREFIX).size)
        assertEquals(
            "派发前会话已变：固定账号核对本地拒绝，不降成匿名",
            ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH),
            api.resultOf(TradeOperations.OPEN_LINK),
        )
        assertTrue("不外跳", launched.isEmpty())
        assertEquals("调用点取账号时仍是会员 A", ACCOUNT_A, api.callOf(TradeOperations.OPEN_LINK)?.expectedAccount)
    }

    /** 详情页点【购买】（真实入口），等 open 的结果落定；[beforeDispatch] 在调用点取定账号之后、交给 ApiClient 之前执行。 */
    private fun buy(
        tokens: CallSiteTokens,
        beforeDispatch: () -> Unit = {},
    ): CallSitePageApi {
        val client = CallSiteIdentityPin.client(server, tokens)
        val api = CallSitePageApi(ApiClientPageApi(client)) { call -> if (call.operationId == TradeOperations.OPEN_LINK) beforeDispatch() }
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing = AppRouting(context, gate)
        val store = store()
        val trade =
            AppTrade(
                api = api,
                store = store,
                routing = routing,
                tracker = Provider { tracker },
                baichuan = UnavailableBaichuan(),
                webCode = UnavailableWebCodeAuthorizer,
                ports = ports,
                browserNoticeMillis = 0L,
                // 与 AppModule.kt:1159 同一接线
                sessionAccount = { client.activeUserId() },
            )
        val search = AppSearch(client, store, routing, privacy = { PrivacyState.CONSENTED }, api = api, trade = trade)
        shadowOf(Looper.getMainLooper()).idle()
        val page =
            checkNotNull(
                search.productConfig(
                    RouteDestination("ProductDetail", """{"platform":"taobao","product_key":"tb:c1"}""", readonly = false),
                    entryId = ENTRY,
                    onBack = {},
                ),
            )
        page.actions.onBuyIntent(card(), false)
        CallSiteIdentityPin.idleUntil { api.resultOf(TradeOperations.OPEN_LINK) != null }
        CallSiteIdentityPin.settle()
        return api
    }

    private fun store() =
        ConfigStore(
            readFile = { null },
            writeFile = {},
            fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, CONFIG, null)) },
            clock = { 0L },
            bundled = CONFIG,
        )

    private val ports =
        object : JumpPorts {
            override fun installed(platform: String): String = "true"

            override fun launchApp(
                type: String,
                value: String,
            ): Boolean {
                launched += value
                return true
            }

            override fun openBrowser(url: String): Boolean {
                launched += url
                return true
            }

            override fun copy(text: String): Boolean = false
        }

    private var trackerFile: String? = null

    private val tracker =
        TrackerQueue(
            readFile = { trackerFile },
            writeFile = { trackerFile = it },
            sink = { _: List<String> -> false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = CallSiteIdentityPin.DEVICE_ID,
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "session-test",
            clock = { 0L },
            canUpload = { false },
        )

    private fun card(): JsonObject =
        Json
            .parseToJsonElement(
                """{"card_id":"c1","product_key":"tb:c1","item_ref":"v1.r","platform":"taobao","title":"milk","price_fen":5678,""" +
                    """"coupon_fen":1000,"final_price_fen":4678,"est_net_price_fen":4555,"rebate_min_fen":123,"rebate_max_fen":234,""" +
                    """"rebate_basis":"normal","link_id":"$LINK_ID","cta":{"text_key":"btn.buy.coupon"},"benefit_tags":["有券"],""" +
                    """"disclaimer_keys":["price_basis","rebate_estimate"],"availability":"ok"}""",
            ).jsonObject

    private fun openResult(): String =
        """{"attempt_id":"attempt-1","jump":{"primary":{"type":"scheme","value":"demo-app://item"},"fallbacks":[],""" +
            """"expire_at":"2099-01-01T00:00:00+08:00"},"price_changed":false,"old_final_price_fen":4678,"new_final_price_fen":4678,""" +
            """"new_link_id":null,"requote_failed":false,"new_rebate_min_fen":123,"new_rebate_max_fen":234,"no_rebate_cause":null,""" +
            """"availability":"ok","quoted_at":"2026-10-02T09:30:05+08:00"}"""

    private companion object {
        const val ENTRY = "entry-product"
        const val LINK_ID = "link-1"
        const val OPEN_PREFIX = "/v1/links/"
        const val CONFIG = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}"""
    }
}
