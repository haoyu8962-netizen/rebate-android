package com.couli.app.orders

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderJson
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderTexts
import com.couli.feature.orders.OrderDetailConfig
import com.couli.feature.orders.OrderDetailScreen
import com.couli.feature.orders.OrderEnv
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object OrderDetailVisualFixtures {
    fun detail(name: String = "detail_estimated"): JsonObject =
        OrderListVisualFixtures.group
            .getValue(if (name.startsWith("detail_reason_")) "reasonDetails" else "details")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun change(
        source: JsonObject,
        vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>,
    ): JsonObject = JsonObject(source + fields)
}

/** 直接运行真实详情 Screen/控制器；服务、剪贴板、导航、Agent 出口才替换为计数器。 */
internal class OrderDetailVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val oldZone = TimeZone.getDefault()
    private val cacheRoot = File(context.cacheDir, "order-detail-visual")
    val cache = OrderStaleCache.Store(cacheRoot)
    val mask = AmountMaskStore(context.getSharedPreferences("detail-visual-mask", Context.MODE_PRIVATE))
    val api = OrderListVisualApi()
    private val gateScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val gate =
        com.couli.core.orders
            .OrderRateGate(gateScope) { OrderListVisualFixtures.now }
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val copies = mutableListOf<String>()
    val askVisible = MutableStateFlow(true)
    var askCount = 0
    var backCount = 0
    var loadedCount = 0
    var contactCount = 0
    var observedNavigationBottom = 0
    var epoch = 1L
    var texts: OrderTexts = OrderDraftTexts
    var help: JsonObject? =
        kotlinx.serialization.json.Json
            .parseToJsonElement(
                """{"price_compare":{"route":"Help","params":{"article_id":"price-compare-fixture"}}}""",
            ).jsonObject
    var surface = Color.Unspecified
    var muted = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var emphasis = Color.Unspecified
    var success = Color.Unspecified
    var control = Color.Unspecified
    var border = Color.Unspecified
    var brand = Color.Unspecified
    var leave: () -> Unit = {}
    var session: () -> Unit = {}
    val env =
        object : OrderEnv {
            override fun loggedIn() = true

            override fun sessionEpoch() = epoch

            override fun account() = "detail-visual-user"

            override fun helpLinks() = help

            override fun copy(text: String) = copies.add(text)

            override fun open(
                route: String,
                params: JsonObject,
            ) {
                opened += route to params
            }

            override fun contactService() {
                contactCount++
                open("Help", JsonObject(emptyMap()))
            }

            override fun forceUpdate() = error("详情视觉场景不应强更")

            override fun onDetailLoaded(detail: com.couli.core.orders.OrderDetail) {
                loadedCount++
            }
        }

    fun launch(
        detail: JsonObject = OrderDetailVisualFixtures.detail(),
        dark: Boolean = false,
        fontScale: Float = 1f,
        shell: Boolean = false,
        agent: Boolean = true,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        if (api.answer == null) api.success(detail)
        val config =
            OrderDetailConfig(
                api,
                env,
                texts,
                cache,
                mask,
                detail.getValue("order_id").jsonPrimitive.content,
                onBack = { backCount++ },
                bindLeave = { leave = it },
                clock = { OrderListVisualFixtures.now },
                rateGate = gate,
                bindSession = { session = it },
                askAi = if (agent) ({ askCount++ }) else null,
                askAiVisible = askVisible,
            )
        rule.setContent {
            val density = LocalDensity.current
            observedNavigationBottom = WindowInsets.navigationBars.getBottom(density)
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides OrderListVisualFixtures.images,
            ) {
                CouliTheme(darkTheme = dark) {
                    surface = CouliColors.backgroundSurface
                    muted = CouliColors.backgroundMuted
                    primary = CouliColors.textPrimary
                    secondary = CouliColors.textSecondary
                    emphasis = CouliColors.brandEmphasis
                    success = CouliColors.statusSuccessText
                    control = CouliColors.borderControl
                    border = CouliColors.borderSubtle
                    brand = CouliColors.brandPrimary
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (shell) Spacer(Modifier.height(54.dp))
                        OrderDetailScreen(config)
                    }
                }
            }
        }
        rule.waitForIdle()
        val request = api.calls.single()
        org.junit.Assert.assertEquals("getOrder", request.operationId)
        org.junit.Assert.assertEquals(mapOf("order_id" to detail.getValue("order_id").jsonPrimitive.content), request.pathParams)
        org.junit.Assert.assertNull(request.query)
        org.junit.Assert.assertNull(request.body)
    }

    fun stale(detail: JsonObject = OrderDetailVisualFixtures.detail()) {
        cache.writeDetail(env.account(), checkNotNull(OrderJson.detail(detail)), OrderListVisualFixtures.staleAt)
        api.offline()
    }

    /** 复用 APP-06v 的真实系统 inset 注入；不在测试容器代产品补 padding。 */
    fun navigationInset(bottomDp: Int) {
        val node = rule.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val pixels = (bottomDp * node.layoutInfo.density.density).toInt()
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, pixels))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
        rule.runOnUiThread { view.rootView.dispatchApplyWindowInsets(checkNotNull(insets.toWindowInsets())) }
        rule.waitForIdle()
        check(observedNavigationBottom == pixels) { "测试安全区注入没有到达 Compose：$observedNavigationBottom != $pixels" }
    }

    override fun close() {
        rule.runOnIdle {
            leave()
            gate.reset()
        }
        gateScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        cacheRoot.deleteRecursively()
        if (mask.hidden.value) mask.toggle()
        TimeZone.setDefault(oldZone)
    }
}
