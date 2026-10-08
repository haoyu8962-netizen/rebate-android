package com.couli.app.search

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.variantBuyIntentNotice
import com.couli.core.clientcore.ConfigStore
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.ProductDetailState
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.PageApi
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.feature.product.ProductDetailScreen
import com.couli.feature.product.ProductPageActions
import com.couli.feature.product.ProductPageConfig
import com.couli.feature.search.SearchConfigPort
import com.couli.feature.search.SearchHistory
import com.couli.feature.search.SearchPageActions
import com.couli.feature.search.SearchPageConfig
import com.couli.feature.search.SearchScreen
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * App 侧的搜索与商品详情（APP-06a）：
 * - 网络经 ApiClient（[ApiClientPageApi]，请求带页面归属）；配置经 ConfigStore（search_status 三层、刷新、确认编号，回调切到主线程）；
 * - 路由：Search（可选 q、platform）、ProductDetail（platform、product_key 必填，item_ref 原样透传，routes.json）；跨页跳转只经 Router；
 * - 页面世代：页面所在的回退栈条目一离开回退栈（[followBackStack]，同一条主线程消息），立即作废该页在途请求（APP-05a 共性第 9 条；
 *   ViewModel 清理只是兜底）；
 * - 详情判定上下文：{ loggedIn = 有会话, privacy = basic（基本模式）/ consented }；
 * - 购买：只发出购买意图。TODO(APP-06b)：open 流程（8 秒超时、同键重试、可取消加载层）、TradeJump、AuthSheet。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions") // 两个页面的配置、路由出口与回退栈同步集中在一处
class AppSearch(
    client: ApiClient,
    private val store: ConfigStore,
    private val routing: AppRouting,
    private val privacy: () -> PrivacyState,
    private val api: PageApi = ApiClientPageApi(client),
) {
    private val main = Handler(Looper.getMainLooper())
    private val history = SearchHistory()

    /** 回退栈条目 id → 该页的作废入口。 */
    private val leaves = mutableMapOf<String, () -> Unit>()

    private val mutableNotice = MutableStateFlow<String?>(null)

    /** 购买意图的提示（只在 debug / staging 有文字，release 为 null）。 */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    /** 搜索页用的配置端口：ConfigStore 的回调在拉取线程上，这里切到主线程。 */
    val configPort: SearchConfigPort =
        object : SearchConfigPort {
            override fun statusLayers(): JsonObject = store.searchStatusLayers()

            override fun refresh() = store.requestFresh()

            override fun issuedMark(): Long = store.issuedFetchId

            override fun appliedMark(): Long = store.appliedFetchId

            override fun subscribe(listener: () -> Unit): () -> Unit {
                // 取消订阅之后已排进主线程的通知也不再回调（页面已离开）
                val active = AtomicBoolean(true)
                val unsubscribe = store.subscribe { main.post { if (active.get()) listener() } }
                return {
                    active.set(false)
                    unsubscribe()
                }
            }
        }

    /** 回退栈变了（主线程、同一条消息里）：已登记的页面条目不在栈里了 → 立即作废它的在途请求。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        if (entries.isEmpty() || leaves.isEmpty()) return
        val ids = entries.map { it.id }.toSet()
        val gone = leaves.keys.filter { it !in ids }
        gone.forEach { id -> leaves.remove(id)?.invoke() }
    }

    fun searchConfig(
        destination: RouteDestination,
        entryId: String?,
        onBack: () -> Unit,
    ): SearchPageConfig {
        val params = params(destination)
        return SearchPageConfig(
            api = api,
            config = configPort,
            texts = SearchDraftTexts,
            history = history,
            query = params.text("q"),
            platform = params.text("platform"),
            actions =
                SearchPageActions(
                    onBack = onBack,
                    onOpenProduct = ::openProduct,
                    onBuyIntent = { card -> buyIntent(card, publicMode = context().let { ProductDetailState.map(card, it).publicMode }) },
                ),
            bindLeave = { leave -> bind(entryId, leave) },
        )
    }

    @Suppress("ReturnCount") // 路由参数不全即 null
    fun productConfig(
        destination: RouteDestination,
        entryId: String?,
        onBack: () -> Unit,
    ): ProductPageConfig? {
        val params = params(destination)
        val platform = params.text("platform") ?: return null
        val key = params.text("product_key") ?: return null
        return ProductPageConfig(
            api = api,
            texts = SearchDraftTexts,
            platform = platform,
            productKey = key,
            itemRef = params.text("item_ref"),
            context = ::context,
            actions =
                ProductPageActions(
                    onBack = onBack,
                    onBuyIntent = ::buyIntent,
                    onResearch = { open(SEARCH_ROUTE, JsonObject(emptyMap())) },
                    onAskAi = { open(AGENT_ROUTE, JsonObject(emptyMap())) },
                ),
            bindLeave = { leave -> bind(entryId, leave) },
            creditEnabled = ::creditEnabled,
        )
    }

    /**
     * 平台入账开关 credit.enabled.<platform>（BR-FUND-04、BR-TEXT-04：off 时详情不显示入账说明）。锁定契约 9cd1e6f 的 /v1/config 没有
     * 下发它（04 §10 是服务端配置）；这里按 features.flags 里同名的布尔键读，没有即 null（详情照常显示）。TODO(N-27)：契约补字段后改读正式位置。
     */
    fun creditEnabled(platform: String): Boolean? {
        val flags = (store.snapshots.value.data["features"] as? JsonObject)?.get("flags") as? JsonObject ?: return null
        return (flags["credit.enabled.$platform"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }

    fun consumeNotice() {
        mutableNotice.value = null
    }

    /** 详情判定上下文（ProductDetailState.map 的 context）：游客或基本模式为公开态。 */
    fun context(): JsonObject =
        JsonObject(
            mapOf(
                "loggedIn" to JsonPrimitive(routing.session.value != SessionState.GUEST),
                "privacy" to
                    JsonPrimitive(
                        if (privacy() ==
                            PrivacyState.BASIC_MODE
                        ) {
                            ProductDetailState.PRIVACY_BASIC
                        } else {
                            ProductDetailState.PRIVACY_CONSENTED
                        },
                    ),
            ),
        )

    private fun bind(
        entryId: String?,
        leave: () -> Unit,
    ) {
        if (entryId != null) leaves[entryId] = leave
    }

    private fun openProduct(
        platform: String,
        productKey: String,
        itemRef: String?,
    ) {
        val fields = linkedMapOf<String, JsonElement>("platform" to JsonPrimitive(platform), "product_key" to JsonPrimitive(productKey))
        itemRef?.let { fields["item_ref"] = JsonPrimitive(it) }
        open(PRODUCT_ROUTE, JsonObject(fields))
    }

    private fun open(
        route: String,
        params: JsonObject,
    ) {
        routing.router.open(RouteRequest(route, params, RouteSource.IN_APP))
    }

    /** 购买意图（TODO(APP-06b)：open 流程）。debug / staging 提示一句，release 不提示。 */
    @Suppress("UnusedParameter") // 卡片与公开态留给 APP-06b 的 open 流程
    private fun buyIntent(
        product: JsonObject,
        publicMode: Boolean,
    ) {
        mutableNotice.value = variantBuyIntentNotice()
    }

    private fun params(destination: RouteDestination): JsonObject =
        runCatching { Json.parseToJsonElement(destination.paramsJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)
            ?.takeIf {
                it.isString
            }?.content
            ?.takeIf(String::isNotEmpty)

    companion object {
        const val SEARCH_ROUTE = "Search"
        const val PRODUCT_ROUTE = "ProductDetail"
        private const val AGENT_ROUTE = "AgentChat"
    }
}

/** Search 路由：真实搜索页（APP-06a）。 */
@Composable
fun SearchRoute(
    search: AppSearch,
    destination: RouteDestination,
    onBack: () -> Unit,
) {
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    SearchScreen(search.searchConfig(destination, entryId, onBack))
    BuyIntentNotice(search)
}

/** ProductDetail 路由：真实详情页（APP-06a）。参数不全（Router 已校验，这里兜底）时返回。 */
@Composable
fun ProductDetailRoute(
    search: AppSearch,
    destination: RouteDestination,
    onBack: () -> Unit,
) {
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    val config = search.productConfig(destination, entryId, onBack)
    if (config == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    ProductDetailScreen(config)
    BuyIntentNotice(search)
}

@Composable
private fun BuyIntentNotice(search: AppSearch) {
    val notice by search.notice.collectAsStateWithLifecycle()
    val text = notice ?: return
    LaunchedEffect(text) {
        delay(NOTICE_MILLIS)
        search.consumeNotice()
    }
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        CouliToast(message = text, modifier = Modifier.padding(top = CouliSpacing.space12))
    }
}

private const val NOTICE_MILLIS = 2_000L
