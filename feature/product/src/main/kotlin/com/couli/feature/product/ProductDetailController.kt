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
 * 购买只到「发出购买意图」为止（TODO(APP-06b)：open 流程）。只在主线程使用。
 */
class ProductDetailController(
    private val api: PageApi,
    /** 详情判定的上下文：{ loggedIn, privacy: consented | basic }（取这一刻的值）。 */
    private val context: () -> JsonObject,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) {
    private var counter = 0L
    private var current: Long? = null
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

    /** 【重试】：42901 禁用中、不在错误态时不发请求（返回 false）。 */
    fun retry(): Boolean {
        if (mutableState.value.phase != ProductPhase.ERROR || gate.active) return false
        fetch()
        return true
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
                    ProductSnapshot(
                        phase = ProductPhase.ERROR,
                        errorCode = result.code,
                        errorTextKey = ErrorActionMapper.resolveTextKey(result.code, reason) { DefaultTexts.text(it) != null },
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
    }
}
