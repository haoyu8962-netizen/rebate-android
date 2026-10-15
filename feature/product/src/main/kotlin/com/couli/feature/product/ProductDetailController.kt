package com.couli.feature.product

import com.couli.core.designsystem.ProductDetailState
import com.couli.core.designsystem.ProductDetailView
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.network.RetryAfterGate
import com.couli.core.network.percentEncode
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 详情页的阶段。 */
object ProductPhase {
    const val LOADING = "loading"
    const val CONTENT = "content"
    const val ERROR = "error"
}

/** 详情页的一次状态快照（不可变）。 */
data class ProductSnapshot(
    val phase: String = ProductPhase.LOADING,
    val product: JsonObject? = null,
    val view: ProductDetailView? = null,
    /** 错误态：code（30141 下架、30143 失效…）；没有外壳为 -1。 */
    val errorCode: Int = 0,
    /** 错误文案键（error.<code>[.<reason>]）；没有为 null（页面用通用失败键）。 */
    val errorTextKey: String? = null,
    val traceId: String? = null,
    val shownSeq: Long? = null,
    /** 42901 限时禁用中：【重试】置灰，到期恢复（共性第 4 条）。 */
    val retryBlocked: Boolean = false,
)

/**
 * 商品详情执行器（APP-06a）：getProduct（product_key 路径参数、item_ref 原样透传，BR-PROD-11）→ [ProductDetailState]。
 * 与搜索同一套归属规则：重新加载、离开页面都让旧请求失效并取消（[CallOwnership] + cancelOwned），迟到响应只丢弃，不改界面。
 * 购买（open、外跳、授权）由 App 层接 feature:trade 的 OpenPurchaseController（APP-06b）；本执行器只按 open 响应更新卡片（[updateProduct]）。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions") // 加载、重试、按响应更新、静默重取与离开各一个入口
class ProductDetailController(
    private val api: PageApi,
    /** 详情判定的上下文：{ loggedIn, privacy: consented | basic }（取这一刻的值）。 */
    private val context: () -> JsonObject,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) {
    private var counter = 0L
    private var current: Long? = null
    private var refreshSeq = 0L
    private val gate = RetryAfterGate(clock)
    private var gateTimer: Job? = null
    private val owners = mutableListOf<CallOwnership>()
    private var left = false
    private var platform = ""
    private var productKey = ""
    private var itemRef: String? = null

    private val mutableState = MutableStateFlow(ProductSnapshot())
    val state: StateFlow<ProductSnapshot> = mutableState.asStateFlow()

    /** 路由参数的平台（页面显示平台名用）。 */
    val platformRaw: String get() = platform

    fun load(
        platform: String,
        productKey: String,
        itemRef: String?,
    ) {
        left = false
        this.platform = platform
        this.productKey = productKey
        this.itemRef = itemRef
        fetch()
    }

    /**
     * 以进程内的卡片种子显示（APP-07 G09：剪贴板 parse 的卡）：直接进内容态，不发 getProduct；卡上的 link_id 原样保留给购买。
     */
    fun loadSeeded(
        platform: String,
        productKey: String,
        itemRef: String?,
        seed: JsonObject,
    ) {
        left = false
        this.platform = platform
        this.productKey = productKey
        this.itemRef = itemRef
        counter += 1
        current = counter
        mutableState.value =
            ProductSnapshot(
                phase = ProductPhase.CONTENT,
                product = seed,
                view = ProductDetailState.map(seed, context()),
                shownSeq = counter,
            )
    }

    /** 【重试】：42901 禁用中、不在错误态时不发请求（返回 false）。 */
    fun retry(): Boolean {
        if (mutableState.value.phase != ProductPhase.ERROR || gate.active) return false
        fetch()
        return true
    }

    /**
     * 用 open 响应更新卡片（APP-06b：新价格、new_link_id、无返利状态，BR-PRICE-12）：只在内容态改，按此刻的上下文重新判定详情模型。
     */
    fun updateProduct(transform: (JsonObject) -> JsonObject) {
        val current = mutableState.value
        val product = current.product ?: return
        if (left || current.phase != ProductPhase.CONTENT) return
        val next = transform(product)
        mutableState.value = current.copy(product = next, view = ProductDetailState.map(next, context()))
    }

    /** 配置变了（features.platform_status 等）：按此刻的上下文重新判定详情模型（APP-06a 共性第 27 条：订阅配置变化）。 */
    fun refreshView() {
        val current = mutableState.value
        val product = current.product ?: return
        if (left) return
        mutableState.value = current.copy(view = ProductDetailState.map(product, context()))
    }

    /** 重新加载（进加载态）。 */
    fun reload() {
        if (left) return
        fetch()
    }

    /**
     * 静默重取本页商品（APP-06b：30144、open 响应给不全价格 / 返利字段时，A-R1-07 / 08 / 09）：不进加载态、不打断弹层；成功时用 [merge]
     * （此刻的卡, 重取到的卡）→ 新卡 落到页面并重新判定详情模型（之后仍须用户再点购买）。[merge] 由购买流程给：补齐价格只补未知字段、保留
     * 本次 open 确认的 link_id 与返利状态，重新加载带卡片版本校验（A-R2-02）。失败保留原卡；重新加载、离开之后到达的丢弃。
     */
    fun refreshCard(merge: (current: JsonObject, fresh: JsonObject) -> JsonObject = { _, fresh -> fresh }) {
        if (left || productKey.isEmpty() || mutableState.value.phase != ProductPhase.CONTENT) return
        refreshSeq += 1
        val seq = refreshSeq
        val shown = current
        val owner = CallOwnership(api.epochs()) { !left && current == shown && refreshSeq == seq }
        owners += owner
        val query = itemRef?.let { "item_ref=${percentEncode(it)}" }
        scope.launch {
            val result = api.send(ApiCall(GET_PRODUCT, pathParams = mapOf("product_key" to productKey), query = query, ownership = owner))
            if (left || current != shown || refreshSeq != seq) return@launch
            owners -= owner
            val product = (result as? ApiResult.Success)?.data as? JsonObject ?: return@launch
            val now = mutableState.value
            if (now.phase != ProductPhase.CONTENT) return@launch
            val shownProduct = now.product ?: return@launch
            val next = merge(shownProduct, product)
            mutableState.value = now.copy(product = next, view = ProductDetailState.map(next, context()))
        }
    }

    /** 页面出栈（同步出口）：作废当前请求并取消传输；之后迟到的响应一律丢弃。 */
    fun leave() {
        left = true
        current = null
        cancelAll()
        gateTimer?.cancel()
        gateTimer = null
    }

    private fun fetch() {
        if (left || productKey.isEmpty()) return
        counter += 1
        val seq = counter
        current = seq
        cancelAll()
        mutableState.value = mutableState.value.copy(phase = ProductPhase.LOADING, errorCode = 0, errorTextKey = null)
        val owner = CallOwnership(api.epochs()) { !left && current == seq }
        owners += owner
        val query = itemRef?.let { "item_ref=${percentEncode(it)}" }
        scope.launch {
            val result = api.send(ApiCall(GET_PRODUCT, pathParams = mapOf("product_key" to productKey), query = query, ownership = owner))
            // 等待之后复核归属：已离开、已重新加载的旧响应只丢弃
            if (left || current != seq) return@launch
            owners.clear()
            settle(seq, result)
        }
    }

    private fun settle(
        seq: Long,
        result: ApiResult,
    ) {
        val next =
            when (result) {
                is ApiResult.Success -> {
                    val product = result.data as? JsonObject ?: JsonObject(emptyMap())
                    ProductSnapshot(
                        phase = ProductPhase.CONTENT,
                        product = product,
                        view = ProductDetailState.map(product, context()),
                        traceId = result.traceId,
                        shownSeq = seq,
                    )
                }

                is ApiResult.Failure -> {
                    val reason = (result.data["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    // 10405 按网络层 Effect 判（F-X-09，第 1 轮评审 R1-03）：不是强更（REFRESH_ONLY、刷新失败）→ 统一错误文案 + 【重试】（错误码不在
                    // 30141 / 30143 之列，页面给重试），不显示「版本过低」；强更照旧 error.10405（强更页由网络层打开）
                    val key =
                        if (ResultSignals.nonForcedUpdate(result)) {
                            GENERIC_ERROR_KEY
                        } else {
                            ErrorActionMapper.resolveTextKey(result.code, reason) { DefaultTexts.text(it) != null }
                        }
                    ProductSnapshot(
                        phase = ProductPhase.ERROR,
                        errorCode = result.code,
                        errorTextKey = key,
                        traceId = result.traceId,
                        shownSeq = seq,
                    )
                }

                else -> {
                    ProductSnapshot(phase = ProductPhase.ERROR, errorCode = -1, shownSeq = seq)
                }
            }
        val millis = ResultSignals.disableMillis(result)
        mutableState.value = next.copy(retryBlocked = millis > 0 || gate.active)
        if (millis > 0) startBlock(millis)
    }

    private fun startBlock(millis: Long) {
        gate.start(millis)
        gateTimer?.cancel()
        gateTimer =
            scope.launch {
                while (gate.active) delay(gate.remainingMillis())
                if (!left) mutableState.value = mutableState.value.copy(retryBlocked = false)
            }
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    companion object {
        const val GET_PRODUCT = "getProduct"

        /** 30141 商品已下架（置灰、不给购买，BR-PRICE-21）。 */
        const val OFF_SHELF = 30141

        /** 30143 商品信息已失效：引导重新搜索（error-codes：跳搜索）。 */
        const val REF_EXPIRED = 30143

        /** 统一错误文案（非强更 10405，F-X-09）。 */
        const val GENERIC_ERROR_KEY = "error.50001"
    }
}
