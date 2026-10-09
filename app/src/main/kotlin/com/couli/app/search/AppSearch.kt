package com.couli.app.search

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.SearchTypingPasteEntry
import com.couli.app.clipboard.SearchTypingPasteResult
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.trade.AppTrade
import com.couli.app.trade.TradeDraftTexts
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
import com.couli.core.router.UpdateCheck
import com.couli.feature.product.ProductDetailController
import com.couli.feature.product.ProductDetailScreen
import com.couli.feature.product.ProductPageActions
import com.couli.feature.product.ProductPageConfig
import com.couli.feature.product.ProductPurchaseSlots
import com.couli.feature.search.SearchConfigPort
import com.couli.feature.search.SearchController
import com.couli.feature.search.SearchHistory
import com.couli.feature.search.SearchPageActions
import com.couli.feature.search.SearchPageConfig
import com.couli.feature.search.SearchPurchaseSlots
import com.couli.feature.search.SearchScreen
import com.couli.feature.trade.CardPatch
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.PurchaseOverlay
import com.couli.feature.trade.PurchaseTimeoutBanner
import com.couli.feature.trade.purchaseButton
import com.couli.feature.trade.searchPurchaseButton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
 * - 购买（APP-06b）：每个发起页（详情、搜索结果）一份 feature:trade 的 OpenPurchaseController（[AppTrade.purchaseController]）：open
 *   （8 秒超时、同键重试、可取消加载层）、TradeJump、AuthSheet、JumpTip；按 open 响应更新卡片；页面出栈时作废。没有接 [trade]（只测外壳的
 *   单测）时只发出购买意图。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList") // 两个页面的配置、路由出口与回退栈同步集中在一处
class AppSearch(
    client: ApiClient,
    private val store: ConfigStore,
    private val routing: AppRouting,
    private val privacy: () -> PrivacyState,
    private val api: PageApi = ApiClientPageApi(client),
    val trade: AppTrade? = null,
    /** 剪贴板识别（APP-07）：SearchTyping 的「粘贴查返利」与 parse 卡片种子；没有接（只测外壳的单测）时不显示粘贴入口。 */
    private val clipboard: AppClipboard? = null,
) {
    /** 回退栈条目 id → 该页的购买执行器（随页面出栈作废）。 */
    private val purchases = mutableMapOf<String, PurchasePage>()

    /** 一个发起页的购买：执行器、它的协程作用域、卡片更新的落点。 */
    private inner class PurchasePage(
        trade: AppTrade,
    ) : PurchaseEnv {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var product: ProductDetailController? = null
        var search: SearchController? = null
        val controller: OpenPurchaseController = trade.purchaseController(this, scope)
        private val owner = trade

        override fun loggedIn(): Boolean = owner.loggedIn()

        override fun basicMode(): Boolean = privacy() == PrivacyState.BASIC_MODE

        override fun requestConsent() = owner.requestConsent()

        override fun platformStatus(platform: String): String? = owner.platformStatus(platform)

        override fun markPlatformStatus(
            platform: String,
            status: String,
        ) = owner.markPlatformStatus(platform, status)

        override fun updateCard(
            target: JsonObject,
            transform: (JsonObject) -> JsonObject,
        ) {
            product?.updateProduct { if (CardPatch.sameCard(it, target)) transform(it) else it }
            search?.updateItem({ CardPatch.sameCard(it, target) }, transform)
        }

        override fun open(
            route: String,
            params: JsonObject,
        ) = owner.open(route, params)

        /**
         * 详情：静默重取本页商品（不进加载态）；搜索卡：按这张卡的 product_key + item_ref 重取（A-R1-07 / 09）。两处都按执行器给的 [merge]
         * 落到页面（补齐价格只补未知字段、重新加载带版本校验，A-R2-02）。
         */
        override fun refetchCard(
            target: JsonObject,
            merge: (current: JsonObject, fresh: JsonObject) -> JsonObject,
        ) {
            product?.refreshCard(merge) ?: search?.refetchItem(target, merge)
        }

        override fun helpLink(key: String): Pair<String, JsonObject>? = owner.helpLink(key)

        fun release() {
            owner.release(controller)
            scope.cancel()
        }
    }

    @Suppress("ReturnCount") // 没接购买、没有条目、已有 / 新建
    private fun purchase(entryId: String?): PurchasePage? {
        val trade = trade ?: return null
        val id = entryId ?: return null
        return purchases.getOrPut(id) {
            PurchasePage(trade).also { page ->
                bind(LEAVE_PURCHASE_PREFIX + id) {
                    purchases.remove(id)
                    page.release()
                }
            }
        }
    }

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

            override fun platformStatus(platform: String): String? = this@AppSearch.platformStatus(platform)

            override fun subscribe(listener: () -> Unit): () -> Unit {
                // 取消订阅之后已排进主线程的通知也不再回调（页面已离开）
                val active = AtomicBoolean(true)
                val unsubscribe = store.subscribe { main.post { if (active.get()) listener() } }
                // 点击结果确认的平台状态（50301）变了也重新映射卡片（A-R1-06）
                val unsubscribeStatus = trade?.subscribeStatus { main.post { if (active.get()) listener() } }
                return {
                    active.set(false)
                    unsubscribe()
                    unsubscribeStatus?.invoke()
                }
            }
        }

    /** 回退栈变了（主线程、同一条消息里）：已登记的页面条目不在栈里了 → 立即作废它的在途请求（含购买执行器）。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        if (entries.isEmpty()) return
        followBackStackIds(entries.map { it.id }.toSet())
    }

    /**
     * [followBackStack] 的主体（按条目 id）：不在 [ids] 里的已登记页面立即调它的出栈出口——搜索页同时作废执行器、滚动恢复任务与位置记录
     * （SearchViewModel，A-R2-04），购买执行器作废在途请求。
     */
    internal fun followBackStackIds(ids: Set<String>) {
        if (ids.isEmpty() || leaves.isEmpty()) return
        val gone = leaves.keys.filter { it.removePrefix(LEAVE_PURCHASE_PREFIX) !in ids }
        gone.forEach { id -> leaves.remove(id)?.invoke() }
    }

    fun searchConfig(
        destination: RouteDestination,
        entryId: String?,
        onBack: () -> Unit,
    ): SearchPageConfig {
        val params = params(destination)
        val purchase = purchase(entryId)
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
                    onBuyIntent = { card, spm ->
                        purchase?.controller?.buy(card, spm)
                            ?: buyIntent(card, context().let { ProductDetailState.map(card, it).publicMode })
                    },
                    // 搜索卡比价无返利原因行的【看看相似商品】：用这张卡构造参数（A-R1-16）
                    onSimilar = { card -> purchase?.controller?.similar(card) ?: openSimilar(card) },
                ),
            bindLeave = { leave -> bind(entryId, leave) },
            purchase = purchase?.let { searchSlots(it) } ?: SearchPurchaseSlots(),
            bindController = { controller -> purchase?.search = controller },
            restoreGate = ::awaitUpdateCheck,
            // 「粘贴查返利」（APP-07）：基本模式不读剪贴板，不显示
            pasteEntry =
                clipboard?.takeIf { privacy() == PrivacyState.CONSENTED }?.let { board ->
                    { SearchTypingPasteEntry(board) }
                },
            // 粘贴结果（30132）：不依赖搜索阶段显示，仍只认本页发起的结果（A-R2-02）
            pasteResult =
                clipboard?.let { board ->
                    { fill -> SearchTypingPasteResult(board, entryId, fill) }
                },
        )
    }

    private fun searchSlots(page: PurchasePage): SearchPurchaseSlots {
        val texts = trade?.texts ?: TradeDraftTexts
        return SearchPurchaseSlots(
            overlay = { PurchaseOverlay(page.controller, texts, buyFocus = null) },
            banner = { PurchaseTimeoutBanner(page.controller, texts) },
            button = { card ->
                val state by page.controller.state.collectAsStateWithLifecycle()
                // 42901 置灰本页每一张卡（共性第 36 条）；加载、未安装提示只给正在购买的那张卡
                searchPurchaseButton(state, texts, card, page.controller.currentCard)
            },
        )
    }

    private fun productSlots(page: PurchasePage): ProductPurchaseSlots {
        val texts = trade?.texts ?: TradeDraftTexts
        return ProductPurchaseSlots(
            overlay = { focus -> PurchaseOverlay(page.controller, texts, focus) },
            banner = { PurchaseTimeoutBanner(page.controller, texts) },
            button = {
                val state by page.controller.state.collectAsStateWithLifecycle()
                purchaseButton(state, texts)
            },
        )
    }

    /** 发起页的生命周期（ON_STOP：被盖住或进入后台，之后到达的响应只更新卡片；ON_START：拼多多授权回来后复查）。 */
    fun purchaseController(entryId: String?): OpenPurchaseController? = entryId?.let { purchases[it]?.controller }

    @Suppress("ReturnCount") // 路由参数不全即 null
    fun productConfig(
        destination: RouteDestination,
        entryId: String?,
        onBack: () -> Unit,
    ): ProductPageConfig? {
        val params = params(destination)
        val platform = params.text("platform") ?: return null
        val key = params.text("product_key") ?: return null
        val purchase = purchase(entryId)
        return ProductPageConfig(
            api = api,
            texts = SearchDraftTexts,
            platform = platform,
            productKey = key,
            itemRef = params.text("item_ref"),
            context = { context(platform) },
            actions =
                ProductPageActions(
                    onBack = onBack,
                    onBuyIntent = { product, publicMode ->
                        purchase?.controller?.buy(product, PRODUCT_BUY_SPM) ?: buyIntent(product, publicMode)
                    },
                    // 用回调传入的当前商品构造参数：首次进入详情、没点过购买也带 q 与 platform（A-R1-15）
                    onSimilar = { product -> purchase?.controller?.similar(product) ?: openSimilar(product) },
                    onResearch = { open(SEARCH_ROUTE, JsonObject(emptyMap())) },
                    onAskAi = { open(AGENT_ROUTE, JsonObject(emptyMap())) },
                ),
            bindLeave = { leave -> bind(entryId, leave) },
            creditEnabled = ::creditEnabled,
            purchase = purchase?.let { productSlots(it) } ?: ProductPurchaseSlots(),
            bindController = { controller -> purchase?.product = controller },
            subscribeConfig = { listener -> configPort.subscribe(listener) },
            restoreGate = ::awaitUpdateCheck,
            // 剪贴板 parse 只有 1 张卡时的进程内种子（APP-07 G09）：沿用 parse 卡的 link_id
            seed = clipboard?.seeds?.claim(entryId, key),
        )
    }

    /**
     * 自动恢复页面（进程被回收后重开）先等本轮版本检查结果，最长 5 秒（03 §4.4，APP-06a 共性第 28 条）；之后由外壳按结论处理（below_min 进强更、
     * 清掉本页，本页的执行器随之作废，不发请求）。
     */
    private suspend fun awaitUpdateCheck() {
        withTimeoutOrNull(UPDATE_WAIT_MILLIS) { routing.update.first { it != UpdateCheck.CHECKING } }
    }

    /** 比价无返利【看看相似商品】（没有购买执行器时）：同平台、原样标题（BR-PRICE-08），参数与执行器同一个函数。 */
    private fun openSimilar(product: JsonObject) {
        open(SEARCH_ROUTE, OpenPurchaseController.similarParams(product))
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

    /**
     * 平台购买状态 features.platform_status.<platform>（生效配置：当前 → LKG → 包内；APP-06a 共性第 27、31 条）。没有接购买（只测外壳的
     * 单测）时按生效配置直接读。
     */
    fun platformStatus(platform: String): String? = trade?.platformStatus(platform) ?: AppTrade.platformStatusIn(store, platform)

    /**
     * 详情判定上下文（ProductDetailState.map 的 context）：游客或基本模式为公开态；给了 [platform] 时带上此刻的平台购买状态（platformStatus，
     * 按钮禁用与 not_launched 隐藏返利金额，共性第 27、31 条）。
     */
    fun context(platform: String? = null): JsonObject =
        JsonObject(
            listOfNotNull(platform?.let(::platformStatus)?.let { "platformStatus" to JsonPrimitive(it) }).toMap() +
                contextBase(),
        )

    private fun contextBase(): Map<String, JsonElement> =
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

    /** 别的路由页（AuthManage）也用同一个回退栈出口作废在途请求。 */
    fun bindPageLeave(
        entryId: String?,
        leave: () -> Unit,
    ) = bind(entryId, leave)

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

    /** 没有接购买执行器（只测外壳的单测）时的购买意图：debug / staging 提示一句，release 不提示。 */
    @Suppress("UnusedParameter") // 卡片与公开态只在接了购买执行器时用
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
        internal const val AGENT_ROUTE = "AgentChat"
        private const val LEAVE_PURCHASE_PREFIX = "purchase:"
        private const val UPDATE_WAIT_MILLIS = 5_000L

        /** 详情底栏购买按钮的 spm（页面.模块.坑位，03 §4.7）。 */
        const val PRODUCT_BUY_SPM = "product_detail.buy_bar.1"

        /** 同一张卡：card_id 相同；没有 card_id 时 product_key 相同（open 响应换了 link_id 也认得出）。 */
        internal fun sameCard(
            a: JsonObject,
            b: JsonObject,
        ): Boolean {
            fun JsonObject.key(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = a.key("card_id")
            return if (id != null) id == b.key("card_id") else a.key("product_key") != null && a.key("product_key") == b.key("product_key")
        }
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
    val config = search.searchConfig(destination, entryId, onBack)
    Box(Modifier.fillMaxSize()) {
        SearchScreen(config)
        Box(modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) { config.purchase.banner() }
    }
    config.purchase.overlay()
    PurchaseLifecycle(search.purchaseController(entryId))
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
    PurchaseLifecycle(search.purchaseController(entryId))
    BuyIntentNotice(search)
}

/** 发起页的生命周期交给购买执行器：ON_STOP（被盖住、进入后台）→ 之后的响应只更新卡片；ON_START → 授权回来后复查。 */
@Composable
private fun PurchaseLifecycle(controller: OpenPurchaseController?) {
    controller ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> controller.onBackground()
                    Lifecycle.Event.ON_START -> controller.onForeground()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
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
