package com.couli.app.purchase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.product.ProductVisualFixtures
import com.couli.app.search.SearchDraftTexts
import com.couli.app.trade.TradeDraftTexts
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.feature.product.ProductDetailController
import com.couli.feature.product.ProductDetailScreen
import com.couli.feature.product.ProductPageActions
import com.couli.feature.product.ProductPageConfig
import com.couli.feature.product.ProductPurchaseSlots
import com.couli.feature.product.ProductTags
import com.couli.feature.trade.AuthSheetCounter
import com.couli.feature.trade.CardPatch
import com.couli.feature.trade.JumpTipService
import com.couli.feature.trade.MemoryPurchasePending
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.PurchaseOverlay
import com.couli.feature.trade.PurchaseTimeoutBanner
import com.couli.feature.trade.TradeJumpExecutor
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.purchaseButton
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals

/** 真详情 + 真购买控制器 + 真外跳执行器；只替换网络/系统端口，不注入 PurchaseState。 */
internal class PurchaseVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    val api = PurchaseVisualApi()
    val ports = PurchaseVisualPorts()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val spoken = mutableListOf<String>()
    val routes = mutableListOf<Pair<String, JsonObject>>()
    val reports = mutableListOf<JsonObject>()
    val refetches = mutableListOf<JsonObject>()
    var fillQuote: JsonObject? = null
    var contacts = 0
    var buyClicks = 0
    var reference: @Composable () -> Unit = {}
    var extraBanner: @Composable () -> Unit = {}
    var showTip = false
    var explanation = false
    var card = ProductVisualFixtures.card()
    var status = "on"
    var primaryColor = Color.Unspecified
    var secondaryColor = Color.Unspecified
    var warningColor = Color.Unspecified
    var surfaceColor = Color.Unspecified
    private var sequence = 0
    private var pageLeave: () -> Unit = {}
    private var refresh: () -> Unit = {}
    private val zone = TimeZone.getDefault()
    lateinit var product: ProductDetailController
    val env =
        object : PurchaseEnv {
            override fun loggedIn(): Boolean = true

            override fun platformStatus(platform: String): String = status

            override fun markPlatformStatus(
                platform: String,
                status: String,
            ) {
                this@PurchaseVisualTestHost.status = status
                refresh()
            }

            override fun updateCard(
                target: JsonObject,
                transform: (JsonObject) -> JsonObject,
            ) {
                if (CardPatch.sameCard(card, target)) card = transform(card)
                product.updateProduct { if (CardPatch.sameCard(it, target)) transform(it) else it }
            }

            override fun open(
                route: String,
                params: JsonObject,
            ) {
                routes += route to params
            }

            override fun contactService() {
                contacts += 1
            }

            override fun refetchCard(
                target: JsonObject,
                merge: (JsonObject, JsonObject) -> JsonObject,
            ) {
                refetches += target
                fillQuote?.let { quote ->
                    card = merge(card, quote)
                    product.updateProduct { merge(it, quote) }
                }
            }

            override fun helpLink(key: String): Pair<String, JsonObject>? =
                if (explanation) "HelpArticle" to JsonObject(mapOf("article_id" to JsonPrimitive("compare-fixture"))) else null
        }
    val controller =
        OpenPurchaseController(
            api = api,
            executor = TradeJumpExecutor(ports, ports, { reports += it }, { 0L }, { ProductVisualFixtures.now }),
            ports = ports,
            env = env,
            pending = MemoryPurchasePending { ProductVisualFixtures.now },
            counter = AuthSheetCounter(),
            tips = JumpTipService(api) { if (showTip) "fixture-user" else null },
            webCode = ports,
            baichuan = ports,
            scope = scope,
            clock = { ProductVisualFixtures.now },
            keyGenerator = { "purchase-v-${++sequence}" },
            browserNoticeMillis = 60_000L,
        )

    fun launch(
        name: String = "tb_coupon_range",
        dark: Boolean = false,
        fontScale: Float = 1f,
        shell: Boolean = false,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        card = ProductVisualFixtures.card(name)
        api.product = card
        val config = pageConfig()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides ProductVisualFixtures.images,
                LocalCouliAnnouncer provides CouliAnnouncer { spoken += it },
            ) {
                CouliTheme(darkTheme = dark) {
                    primaryColor = CouliColors.buttonPrimaryDefaultBackground
                    secondaryColor = CouliColors.textSecondary
                    warningColor = CouliColors.statusWarningText
                    surfaceColor = CouliColors.backgroundSurface
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                            if (shell) Spacer(Modifier.height(54.dp))
                            Box(Modifier.weight(1f)) { ProductDetailScreen(config) }
                        }
                        reference()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pageConfig(): ProductPageConfig =
        ProductPageConfig(
            api = api,
            texts = SearchDraftTexts,
            platform = card.getValue("platform").jsonPrimitive.content,
            productKey = card.getValue("product_key").jsonPrimitive.content,
            itemRef = null,
            context = {
                JsonObject(
                    mapOf(
                        "loggedIn" to JsonPrimitive(true),
                        "privacy" to JsonPrimitive("consented"),
                        "platformStatus" to JsonPrimitive(status),
                    ),
                )
            },
            clock = { ProductVisualFixtures.now },
            bindLeave = { pageLeave = it },
            bindController = { product = it },
            subscribeConfig = { listener ->
                refresh = listener;
                {}
            },
            actions =
                ProductPageActions(onBuyIntent = { item, _ ->
                    buyClicks += 1
                    controller.buy(item, "product_detail.buy_bar.1")
                }),
            purchase =
                ProductPurchaseSlots(
                    overlay = { PurchaseOverlay(controller, TradeDraftTexts, it) },
                    banner = {
                        PurchaseTimeoutBanner(controller, TradeDraftTexts)
                        extraBanner()
                    },
                    button = {
                        val state by controller.state.collectAsStateWithLifecycle()
                        purchaseButton(state, TradeDraftTexts)
                    },
                ),
        )

    fun buy() {
        rule.onNodeWithTag(ProductTags.BUY).performClick()
        rule.waitForIdle()
    }

    fun answer(name: String) = answer(PurchaseVisualFixtures.response(name))

    fun answer(data: JsonObject) {
        rule.runOnIdle { api.succeed(data) }
        rule.waitForIdle()
    }

    fun fail(
        code: Int,
        data: JsonObject = JsonObject(emptyMap()),
    ) {
        rule.runOnIdle { api.fail(code, data) }
        rule.waitForIdle()
    }

    fun auth() {
        buy()
        fail(30101, PurchaseVisualFixtures.auth)
    }

    fun count(op: String): Int = api.calls(op).size

    fun counts(
        open: Int,
        jumps: Int = 0,
        auth: Pair<Int, Int> = 0 to 0,
        copies: Int = 0,
        callbacks: Pair<Int, Int> = 0 to 0,
    ) {
        assertEquals("open 调用次数", open, count(TradeOperations.OPEN_LINK))
        assertEquals("外跳端口次数", jumps, ports.jumps.size)
        assertEquals("授权端口次数", auth.first, ports.webAuthorizations.size + ports.sdkAuthorizations)
        assertEquals("bindUnion 次数", auth.second, count(TradeOperations.BIND_UNION))
        assertEquals("未额外获取授权地址", 0, count(TradeOperations.GET_UNION_AUTH_URL))
        assertEquals("未额外查询授权结果", 0, count(TradeOperations.LIST_UNION_BINDINGS))
        assertEquals("剪贴板次数", copies, ports.copies.size)
        assertEquals("客服回调次数", callbacks.first, contacts)
        assertEquals("路由回调次数", callbacks.second, routes.size)
        println(
            "COUNTS open=$open jumps=$jumps auth=${auth.first} bind=${auth.second} copy=$copies " +
                "contact=${callbacks.first} route=${callbacks.second}",
        )
    }

    override fun close() {
        rule.runOnIdle {
            controller.leave()
            pageLeave()
            scope.cancel()
        }
        TimeZone.setDefault(zone)
    }
}
