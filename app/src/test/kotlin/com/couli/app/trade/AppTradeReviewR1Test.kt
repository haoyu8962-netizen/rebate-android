package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.app.search.AppSearch
import com.couli.app.shell.MeWithEntries
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliTheme
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteSource
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.trade.CardPatch
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第 1 轮评审在 App 接线上的回归测试：
 * - R1-04：基本模式下点购买 → 登记待办并弹补同意，不发 open；隐私变为已同意后 AppTrade 让发起页恢复一次；
 * - R1-06：点击结果确认的 50301 按平台进到搜索页配置端口与详情判定上下文（列表、详情、读屏同一映射），订阅者收到通知；分界之后发出、
 *   生效的新配置覆盖它；
 * - R1-14：「我的」Tab 有正式的【授权管理】入口，经 Router 以 App 内来源打开 AuthManage（守卫照常）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppTradeReviewR1Test {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var status = "on"
    private val calls = mutableListOf<ApiCall>()

    private val store =
        ConfigStore(
            readFile = { null },
            writeFile = {},
            fetcher = { _, done ->
                done(ConfigReply(ConfigReply.OK, """{"features":{"flags":{},"platform_status":{"taobao":"$status"}}}""", null))
            },
            clock = { 0L },
            bundled = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}""",
        )

    private val client =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { "https://api.example.test/" },
            context = {
                NetworkContext(
                    PrivacyRaw.FULL,
                    environmentSettled = true,
                    credentialsReadable = true,
                    update = UpdateRaw.SUPPORTED,
                )
            },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )

    /** 记下每次调用、永不回答的假服务。 */
    private val api =
        object : PageApi {
            override suspend fun send(call: ApiCall): ApiResult {
                calls += call
                return CompletableDeferred<ApiResult>().await()
            }

            override fun cancelOwned(owner: CallOwnership) = Unit

            override fun epochs(): CallEpochs = CallEpochs(0, 0)
        }

    private fun routing(): AppRouting {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        return AppRouting(context, gate)
    }

    private fun trade(
        routing: AppRouting,
        consent: () -> Unit = {},
    ): AppTrade =
        AppTrade(
            api = api,
            store = store,
            routing = routing,
            tracker = Provider { error("not used") },
            baichuan = UnavailableBaichuan(),
            webCode = UnavailableWebCodeAuthorizer,
            ports = AndroidJumpPorts(context),
            consentPrompt = consent,
        )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun r1_04_basicModePurchaseAsksForConsentAndResumesAfterConsent() {
        val routing = routing()
        routing.privacy.value = PrivacyState.BASIC_MODE
        idle()
        var prompts = 0
        val trade = trade(routing) { prompts += 1 }
        val card =
            Json
                .parseToJsonElement(
                    """{"card_id":"c1","product_key":"tb:1","platform":"taobao","title":"milk","link_id":"link-1","availability":"ok"}""",
                ).jsonObject
        val env =
            object : PurchaseEnv {
                override fun loggedIn(): Boolean = true

                override fun basicMode(): Boolean = routing.privacy.value == PrivacyState.BASIC_MODE

                override fun requestConsent() = trade.requestConsent()

                override fun platformStatus(platform: String): String? = trade.platformStatus(platform)

                override fun updateCard(
                    target: JsonObject,
                    transform: (JsonObject) -> JsonObject,
                ) = Unit

                override fun open(
                    route: String,
                    params: JsonObject,
                ) = Unit

                override fun refetchCard(
                    target: JsonObject,
                    merge: (JsonObject, JsonObject) -> JsonObject,
                ) = Unit

                override fun helpLink(key: String): Pair<String, JsonObject>? = null
            }
        val controller = trade.purchaseController(env, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        controller.buy(card, "product_detail.buy_bar.1")
        idle()
        assertEquals("弹补同意", 1, prompts)
        assertTrue("基本模式不发 open", calls.none { it.operationId == TradeOperations.OPEN_LINK })
        assertTrue("登记了购买待办", trade.pending.peek() != null)
        // 用户在补同意弹窗里同意
        routing.privacy.value = PrivacyState.CONSENTED
        idle()
        assertEquals("同意后恢复一次 open", 1, calls.count { it.operationId == TradeOperations.OPEN_LINK })
        assertEquals(mapOf("link_id" to "link-1"), calls.single { it.operationId == TradeOperations.OPEN_LINK }.pathParams)
        trade.release(controller)
    }

    @Test
    fun r1_06_aConfirmedNotLaunchedReachesTheListAndTheDetailMappingUntilNewConfig() {
        val routing = routing()
        val trade = trade(routing)
        val search = AppSearch(client, store, routing, privacy = { PrivacyState.CONSENTED }, api = api, trade = trade)
        store.onColdStart()
        idle()
        var notified = 0
        val unsubscribe = search.configPort.subscribe { notified += 1 }
        assertEquals("on", search.configPort.platformStatus("taobao"))
        trade.markPlatformStatus("taobao", "not_launched")
        idle()
        assertTrue("列表、详情收到通知重新映射", notified > 0)
        assertEquals("搜索卡映射用点击结果", "not_launched", search.configPort.platformStatus("taobao"))
        assertEquals("详情判定上下文同样", JsonPrimitive("not_launched"), search.context("taobao")["platformStatus"])
        // 分界之后发出、生效的新配置覆盖它
        status = "on"
        store.requestFresh()
        idle()
        assertEquals("on", search.configPort.platformStatus("taobao"))
        unsubscribe()
        assertTrue(
            CardPatch.sameCard(JsonObject(mapOf("card_id" to JsonPrimitive("x"))), JsonObject(mapOf("card_id" to JsonPrimitive("x")))),
        )
    }

    @Test
    fun r1_14_theMeTabHasAFormalAuthManageEntryThroughTheRouter() {
        val routing = routing()
        var settings = 0
        rule.setContent {
            CouliTheme {
                MeWithEntries(onAuthManage = { openAuthManage(routing) }, onSettings = { settings += 1 }) { Text("me") }
            }
        }
        rule.onNodeWithTag(AUTH_MANAGE_ENTRY_TAG).performClick()
        rule.waitForIdle()
        val outcome = routing.router.lastOutcome.value!!
        assertEquals("经 Router 打开 AuthManage", "AuthManage", outcome.request.route)
        assertEquals("App 内来源", RouteSource.IN_APP.raw, outcome.request.source)
        assertEquals(0, settings)
    }
}
