package com.couli.app.purchase

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.app.product.ProductVisualFixtures
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.app.trade.AppTrade
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.feature.product.ProductDetailScreen
import com.couli.feature.product.ProductPageConfig
import com.couli.feature.product.ProductTags
import com.couli.feature.search.SearchScreen
import java.util.TimeZone
import javax.inject.Provider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** G-D11 用正式 AppSearch.productConfig 的 banner 接线；不能在测试 Host 里画一条假说明让截图通过。 */
internal class PurchaseVisualAppHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val oldZone = TimeZone.getDefault()
    val api = PurchaseVisualApi()
    val ports =
        PurchaseVisualPorts().apply {
            installation = "false"
            launches = false
        }
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
    private val routing =
        AppRouting(context, gate).also {
            it.privacy.value = PrivacyState.CONSENTED
            it.session.value =
                SessionState.PHONE_BOUND
        }
    private val store =
        ConfigStore(
            { null },
            {},
            { _, _ -> },
            { ProductVisualFixtures.now },
            """{"features":{"flags":{},"search_status":{"taobao":"on"},""" +
                """"platform_status":{"taobao":"on","jd":"on","pdd":"on"}}}""",
        )
    private val client =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { "https://api.example.test/" },
            context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )
    private val queue =
        TrackerQueue(
            readFile = { null },
            writeFile = {},
            sink = { _: List<String> -> false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "fixture-device",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "fixture-session",
            clock = { ProductVisualFixtures.now },
            canUpload = { false },
        )
    private val trade = AppTrade(api, store, routing, Provider { queue }, ports, ports, ports)
    private val search = AppSearch(client, store, routing, { PrivacyState.CONSENTED }, api = api, trade = trade)
    val controller get() = checkNotNull(search.purchaseController(ENTRY))

    fun launch(
        name: String = "tb_coupon_range",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val card = ProductVisualFixtures.card(name)
        api.product = card
        api.readTips =
            JsonObject(
                mapOf(
                    "jump_tip" to
                        JsonObject(
                            listOf("taobao", "jd", "pdd").associateWith { JsonPrimitive("2026-10-22T10:00:00+08:00") },
                        ),
                ),
            )
        val params = JsonObject(mapOf("platform" to card.getValue("platform"), "product_key" to card.getValue("product_key")))
        val base = checkNotNull(search.productConfig(RouteDestination("ProductDetail", params.toString(), false), ENTRY) {})
        val config =
            ProductPageConfig(
                api = base.api,
                texts = base.texts,
                platform = base.platform,
                productKey = base.productKey,
                itemRef = base.itemRef,
                context = base.context,
                actions = base.actions,
                bindLeave = base.bindLeave,
                clock = { ProductVisualFixtures.now },
                creditEnabled = base.creditEnabled,
                purchase = base.purchase,
                bindController = base.bindController,
                subscribeConfig = base.subscribeConfig,
                restoreGate = base.restoreGate,
                seed = base.seed,
            )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides ProductVisualFixtures.images,
            ) {
                CouliTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        Spacer(Modifier.height(54.dp))
                        Box(Modifier.weight(1f)) { ProductDetailScreen(config) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun buy() {
        rule.onNodeWithTag(ProductTags.BUY).performClick()
        rule.waitForIdle()
    }

    /** S2-1：正式 AppSearch → SearchScreen → ProductCardList，不能用详情大卡代替列表入口。 */
    fun launchSearch() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        ports.installation = "true"
        ports.launches = true
        val card = ProductVisualFixtures.card()
        api.product = card
        val destination = RouteDestination("Search", """{"q":"样例","platform":"taobao"}""", false)
        val config = search.searchConfig(destination, ENTRY) {}
        rule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides ProductVisualFixtures.images) {
                CouliTheme { SearchScreen(config) }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            api.succeed(
                JsonObject(
                    mapOf(
                        "items" to JsonArray(listOf(card)),
                        "next_cursor" to JsonNull,
                        "has_more" to JsonPrimitive(false),
                        "fallback_items" to JsonArray(emptyList()),
                    ),
                ),
                "searchProducts",
            )
        }
        rule.waitForIdle()
    }

    fun answer(type: String = "copy_tpwd") {
        // 正式 AppTrade 不注入墙钟；仅测试响应的有效期放远，视觉 quoted_at 仍沿用共享固定值。
        val response = PurchaseVisualFixtures.jump(type, if (type == "copy_tpwd") "FIXTURE-TPWD" else "fixture-shop://item")
        val plan = JsonObject(response.getValue("jump").jsonObject + ("expire_at" to JsonPrimitive("2099-10-22T11:00:00+08:00")))
        rule.runOnIdle { api.succeed(JsonObject(response + ("jump" to plan))) }
        rule.waitForIdle()
    }

    fun platform(): String =
        controller.currentCard
            .getValue("platform")
            .jsonPrimitive.content

    override fun close() {
        rule.runOnIdle { search.purchaseController(ENTRY)?.let(trade::release) }
        TimeZone.setDefault(oldZone)
    }

    companion object {
        const val ENTRY = "purchase-visual-detail"
    }
}
