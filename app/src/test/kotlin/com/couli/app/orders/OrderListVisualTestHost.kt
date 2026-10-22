package com.couli.app.orders

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.TabBar
import com.couli.core.designsystem.TabItem
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderJson
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderTexts
import com.couli.feature.orders.OrderEnv
import com.couli.feature.orders.OrderListConfig
import com.couli.feature.orders.OrderListScreen
import com.couli.feature.orders.OrderListViewModel
import java.io.File
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject

/** 真实 Screen / ViewModel / Controller / JSON 解析；只替换服务与系统出口。 */
internal class OrderListVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val oldZone = TimeZone.getDefault()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheRoot = File(context.cacheDir, "orders-visual-cache")
    val cache = OrderStaleCache.Store(cacheRoot)
    val mask = AmountMaskStore(context.getSharedPreferences("orders-visual-mask", Context.MODE_PRIVATE))
    val api = OrderListVisualApi()
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val copies = mutableListOf<String>()
    var epoch = 1L
    var texts: OrderTexts = OrderDraftTexts
    var muted = Color.Unspecified
    var surface = Color.Unspecified
    var border = Color.Unspecified
    var successBackground = Color.Unspecified
    var successText = Color.Unspecified
    var secondary = Color.Unspecified
    var emphasis = Color.Unspecified
    var subtle = Color.Unspecified
    var warningBackground = Color.Unspecified
    var referenceIcon: ImageVector? = null
    lateinit var model: OrderListViewModel
    val env =
        object : OrderEnv {
            override fun loggedIn() = true

            override fun sessionEpoch() = epoch

            override fun account() = "orders-visual-user"

            override fun helpLinks(): JsonObject? = null

            override fun copy(text: String): Boolean = copies.add(text)

            override fun open(
                route: String,
                params: JsonObject,
            ) {
                opened += route to params
            }

            override fun forceUpdate() = error("列表视觉场景不应强更")
        }

    fun launch(
        page: JsonObject = OrderListVisualFixtures.page(),
        scope: String = "self",
        dark: Boolean = false,
        fontScale: Float = 1f,
        shell: Boolean = false,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        if (api.answer == null) api.success(page)
        val config =
            OrderListConfig(
                api,
                env,
                texts,
                cache,
                mask,
                pending = null,
                scope = scope,
                clock = { OrderListVisualFixtures.now },
                bindController = { model = it as OrderListViewModel },
            )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides OrderListVisualFixtures.images,
            ) {
                CouliTheme(darkTheme = dark) {
                    ObserveColors()
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (shell) Spacer(Modifier.height(54.dp))
                        Box(Modifier.weight(1f)) { OrderListScreen(config) }
                        referenceIcon?.let {
                            Box(Modifier.size(28.dp).background(CouliColors.statusWarningBackground).padding(4.dp)) {
                                Icon(
                                    it,
                                    contentDescription = null,
                                    tint = CouliColors.statusWarningText,
                                    modifier = Modifier.size(20.dp).testTag("orders_reference_icon"),
                                )
                            }
                        }
                        if (shell) {
                            TabBar(
                                listOf(
                                    TabItem(CouliIcons.home, "首页"),
                                    TabItem(CouliIcons.chat, "AI 助手"),
                                    TabItem(CouliIcons.orders, "订单"),
                                    TabItem(CouliIcons.user, "我的"),
                                ),
                                selectedIndex = 2,
                                onSelect = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun ObserveColors() {
        muted = CouliColors.backgroundMuted
        surface = CouliColors.backgroundSurface
        border = CouliColors.borderSubtle
        successBackground = CouliColors.statusSuccessBackground
        successText = CouliColors.statusSuccessText
        secondary = CouliColors.textSecondary
        emphasis = CouliColors.brandEmphasis
        subtle = CouliColors.brandSubtle
        warningBackground = CouliColors.statusWarningBackground
    }

    fun stale(
        page: JsonObject = OrderListVisualFixtures.page("list_self_pinned"),
        scope: String = "self",
    ) {
        cache.writeList(env.account(), scope, checkNotNull(OrderJson.listData(page)), OrderListVisualFixtures.staleAt)
        api.offline()
    }

    fun refresh(page: JsonObject) {
        api.success(page)
        rule.runOnIdle { model.refresh() }
        rule.waitForIdle()
    }

    override fun close() {
        if (::model.isInitialized) rule.runOnIdle { model.controller.leave() }
        cacheRoot.deleteRecursively()
        if (mask.hidden.value) mask.toggle()
        TimeZone.setDefault(oldZone)
    }
}
