package com.couli.app.trade

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import javax.inject.Provider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-AND-17（APP-06a 共性第 27、31 条）的 App 接线：生效配置的 features.platform_status 进到详情判定上下文（platformStatus）与搜索页的
 * 配置端口；配置变化（拉取成功）后取到的是新值。只测执行器（单测）时漏掉这层接线也不会失败，这里从真实的 ConfigStore 读。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppSearchPlatformStatusWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var status = "not_launched"

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

    private fun search(): AppSearch {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        val routing = AppRouting(context, gate)
        val api = ApiClientPageApi(client)
        val trade =
            AppTrade(
                api = api,
                store = store,
                routing = routing,
                tracker = Provider { error("not used") },
                baichuan = UnavailableBaichuan(),
                webCode = UnavailableWebCodeAuthorizer,
                ports = AndroidJumpPorts(context),
            )
        return AppSearch(client, store, routing, privacy = { PrivacyState.CONSENTED }, api = api, trade = trade)
    }

    @Test
    fun productContextAndSearchPortCarryTheEffectivePlatformStatus() {
        val search = search()
        assertEquals("拉取之前：包内配置", "on", search.configPort.platformStatus("taobao"))
        store.onColdStart()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("not_launched", search.configPort.platformStatus("taobao"))
        val config =
            search.productConfig(
                RouteDestination("ProductDetail", """{"platform":"taobao","product_key":"tb:1"}""", readonly = false),
                entryId = "entry-1",
                onBack = {},
            )
        assertNotNull(config)
        assertEquals(JsonPrimitive("not_launched"), config!!.context()["platformStatus"])
        status = "maintenance"
        store.requestFresh()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("配置变化后取新值", JsonPrimitive("maintenance"), config.context()["platformStatus"])
    }
}
