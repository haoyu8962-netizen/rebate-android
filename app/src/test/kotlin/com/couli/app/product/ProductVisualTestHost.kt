package com.couli.app.product

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.DisclaimerStyle
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.feature.product.ProductDetailController
import com.couli.feature.product.ProductDetailScreen
import com.couli.feature.product.ProductPageActions
import com.couli.feature.product.ProductPageConfig
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 真实 Screen + Controller + Mapper；只有 getProduct、图片和出口是假服务。 */
internal class ProductVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val oldZone = TimeZone.getDefault()
    private var leave: () -> Unit = {}
    val api = ProductVisualApi()
    val spoken = mutableListOf<String>()
    val similar = mutableListOf<JsonObject>()
    val buys = mutableListOf<Pair<JsonObject, Boolean>>()
    val aiVisible = MutableStateFlow(true)
    var backs = 0
    var asks = 0
    var researches = 0
    var credit: Boolean? = null
    var status = "on"
    var loggedIn = true
    var privacy = "consented"
    var referenceIcon: ImageVector? = null
    var imageLoader: ProductImageLoader? = ProductVisualFixtures.images
    var observedNavigationBottom = 0
    var placeholder = Color.Unspecified
    var muted = Color.Unspecified
    var surface = Color.Unspecified
    var secondary = Color.Unspecified
    lateinit var controller: ProductDetailController
    private val listeners = mutableListOf<() -> Unit>()

    fun launch(
        name: String = "tb_coupon_range",
        dark: Boolean = false,
        fontScale: Float = 1f,
        statusBar: Boolean = false,
        askEnabled: Boolean = true,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val card = ProductVisualFixtures.card(name)
        if (api.answer == null) api.answer = ApiResult.Success(card, ProductVisualApi.TRACE, null)
        val config = config(card, askEnabled)
        rule.setContent {
            val density = LocalDensity.current
            observedNavigationBottom = WindowInsets.navigationBars.getBottom(density)
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides imageLoader,
                LocalCouliAnnouncer provides CouliAnnouncer { spoken += it },
            ) {
                CouliTheme(darkTheme = dark) {
                    placeholder = CouliColors.backgroundPlaceholder
                    muted = CouliColors.backgroundMuted
                    surface = CouliColors.backgroundSurface
                    secondary = CouliColors.textSecondary
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                            if (statusBar) Spacer(Modifier.height(54.dp))
                            ProductDetailScreen(config)
                        }
                        referenceIcon?.let { icon ->
                            DisclaimerFooter(
                                lines = listOf(DisclaimerLine("参考图标", icon = icon)),
                                style = DisclaimerStyle.DETAIL_PANEL,
                                modifier = Modifier.align(Alignment.TopEnd).width(200.dp),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun config(
        card: JsonObject,
        askEnabled: Boolean,
    ): ProductPageConfig =
        ProductPageConfig(
            api = api,
            texts = SearchDraftTexts,
            platform = card.getValue("platform").jsonPrimitive.content,
            productKey = card.getValue("product_key").jsonPrimitive.content,
            itemRef = null,
            context = {
                JsonObject(
                    mapOf(
                        "loggedIn" to JsonPrimitive(loggedIn),
                        "privacy" to JsonPrimitive(privacy),
                        "platformStatus" to JsonPrimitive(status),
                    ),
                )
            },
            clock = { ProductVisualFixtures.now },
            creditEnabled = { credit },
            bindLeave = { leave = it },
            bindController = { controller = it },
            subscribeConfig = { listener ->
                listeners += listener;
                { listeners -= listener }
            },
            actions =
                ProductPageActions(
                    onBack = {
                        backs += 1
                        leave()
                    },
                    onBuyIntent = { product, public -> buys += product to public },
                    onSimilar = { similar += it },
                    onResearch = { researches += 1 },
                    onAskAi = if (askEnabled) ({ asks += 1 }) else null,
                    askAiVisible = aiVisible,
                ),
        )

    fun refresh() = rule.runOnIdle { listeners.toList().forEach { it() } }

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

    fun systemBack() {
        val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val activity =
            generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<ComponentActivity>()
                .first()
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    override fun close() {
        rule.runOnIdle { leave() }
        TimeZone.setDefault(oldZone)
    }
}

internal class ProductVisualApi : PageApi {
    var answer: ApiResult? = null
    var hold: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<ApiCall>()
    val cancelled = mutableListOf<CallOwnership>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        hold?.await()
        return checkNotNull(answer)
    }

    override fun cancelOwned(owner: CallOwnership) {
        cancelled += owner
    }

    override fun epochs(): CallEpochs = CallEpochs(0, 0)

    fun fail(code: Int) {
        answer =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                TRACE,
                ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
                null,
            )
    }

    companion object {
        const val TRACE = "visual-detail-000123"
    }
}
