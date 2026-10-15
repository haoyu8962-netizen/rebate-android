package com.couli.feature.product

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.BuyButton
import com.couli.core.designsystem.BuyButtonModel
import com.couli.core.designsystem.BuyButtonOverride
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.DisclaimerStyle
import com.couli.core.designsystem.ErrorAnnouncement
import com.couli.core.designsystem.MappedProductCard
import com.couli.core.designsystem.ProductCardKeys
import com.couli.core.designsystem.ProductCardLarge
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductDetailView
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 详情页的文案（App 给：契约字典 → 草稿表）。 */
interface ProductTexts : ProductCardTexts {
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/** 详情页的出口（App 给）。 */
class ProductPageActions(
    val onBack: () -> Unit = {},
    /** 【购买】：交给 App 层的购买流程（APP-06b：feature:trade 的 OpenPurchaseController，open、外跳、授权）。 */
    val onBuyIntent: (product: JsonObject, publicMode: Boolean) -> Unit = { _, _ -> },
    /** 比价无返利原因行的【看看相似商品】（BR-PRICE-08：同平台、原样标题，经 Router 打开 Search）。 */
    val onSimilar: (product: JsonObject) -> Unit = {},
    /** 30143 商品信息已失效：【重新搜索】经 Router 打开 Search。 */
    val onResearch: () -> Unit = {},
    /** 【问问 AI】经 Router 打开 AgentChat（App 给；null 不绘制）。 */
    val onAskAi: (() -> Unit)? = null,
    /**
     * 【问问 AI】此刻是否绘制（App 订阅 `agent.entry.visible` / Agent 可用性，运行中变化随之更新，Android R1-04、共性第 39 条）；null 时只看 [onAskAi]。
     * 为 false 时不绘制按钮，也没有可点的回退回调。
     */
    val askAiVisible: StateFlow<Boolean>? = null,
)

/** 没给 [ProductPageActions.askAiVisible] 时的常量「可见」。 */
private val ASK_AI_ALWAYS: StateFlow<Boolean> = MutableStateFlow(true)

/** 详情页配置。[platform] / [productKey] / [itemRef] 是路由参数（routes.json ProductDetail）。 */
@Suppress("LongParameterList") // 执行器依赖、文案、路由参数与出口逐项给
class ProductPageConfig(
    val api: PageApi,
    val texts: ProductTexts,
    val platform: String,
    val productKey: String,
    val itemRef: String?,
    /** 详情判定上下文 { loggedIn, privacy }（取这一刻的值）。 */
    val context: () -> JsonObject,
    val actions: ProductPageActions = ProductPageActions(),
    val bindLeave: (leave: () -> Unit) -> Unit = {},
    val clock: () -> Long = System::currentTimeMillis,
    /**
     * 平台的入账开关 credit.enabled.<platform>（BR-FUND-04、BR-TEXT-04）：true 开、false 关、null 拿不到（锁定契约的 /v1/config 还没有
     * 下发这个开关，TODO(N-27)）。只有明确关闭时隐藏入账说明。
     */
    val creditEnabled: (platform: String) -> Boolean? = { null },
    /** 购买流程的界面（App 给，APP-06b）：浮层、底栏上方提示条与购买按钮覆盖。缺省没有。 */
    val purchase: ProductPurchaseSlots = ProductPurchaseSlots(),
    /** 执行器建好后交给 App（按 open 响应更新卡片、30144 重取卡片）。 */
    val bindController: (ProductDetailController) -> Unit = {},
    /** 订阅配置变化（features.platform_status，主线程回调），返回取消订阅（APP-06a 共性第 27 条）。 */
    val subscribeConfig: (listener: () -> Unit) -> (() -> Unit) = { { } },
    /** 进程被回收后自动恢复本页：先等本轮版本检查结果（最长 5 秒，03 §4.4，APP-06a 共性第 28 条）再取详情；没有给时不等。 */
    val restoreGate: (suspend () -> Unit)? = null,
    /**
     * 进程内的卡片种子（APP-07 G09：剪贴板 parse 只有 1 张卡时）：首次进入直接以这张卡显示，不重取详情，详情与购买沿用它的 link_id
     * （entry_source=parse）。之后的【重试】、重新加载照常取详情；进程被回收后恢复本页没有种子，按路由参数取详情。
     */
    val seed: JsonObject? = null,
)

/** 购买流程的界面插槽（App 用 feature:trade 的组件填；feature 之间不互相依赖）。 */
class ProductPurchaseSlots(
    /** 浮层（加载层、确认框、半屏、轻提示）；参数是购买按钮的焦点（弹层关闭后回到它）。 */
    val overlay: @Composable (buyFocus: FocusRequester) -> Unit = {},
    /** 底栏上方的提示条（超时【重试】）。 */
    val banner: @Composable () -> Unit = {},
    /** 购买按钮覆盖；没有为 null。 */
    val button: @Composable () -> BuyButtonOverride? = { null },
)

/**
 * 详情页的入账说明「确认收货后随联盟月度结算入账」（BR-TEXT-04、F-PROD-05，A-R1-08）：有返利的商品显示（normal、price_compare_risk、
 * 金额未知的 amount_unknown，以及返利从无到有、口径待补的临时展示状态；返利确定为 0 的不显示），入账开关明确关闭（credit.enabled.<platform>=off）时不显示；不写天数与日期。
 */
object CreditNote {
    const val TEXT_KEY = "product.credit_note"
    private val REBATE_BASES = setOf("normal", "price_compare_risk", "amount_unknown")

    fun visible(
        product: JsonObject,
        creditSwitch: Boolean?,
    ): Boolean {
        if (creditSwitch == false) return false
        val basis = (product["rebate_basis"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val max = (product["rebate_max_fen"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        // 返利从无到有、口径还没补上的临时展示状态（A-R2-01）也是有返利的商品
        val provisional = ProductCardMapper.provisionalRebate(product) && (max ?: 0L) > 0L
        return (basis in REBATE_BASES && max != 0L) || provisional
    }
}

/** 详情页 ViewModel：执行器随回退栈条目；清理时作废（出栈的同步出口由 App 经 [ProductPageConfig.bindLeave] 先调）。 */
class ProductDetailViewModel(
    initial: ProductPageConfig,
    saved: SavedStateHandle,
) : ViewModel() {
    var config: ProductPageConfig = initial
        private set
    val controller = ProductDetailController(initial.api, initial.context, viewModelScope, initial.clock)

    /** 本 ViewModel 是进程被回收后按保存状态重建的（同一回退栈条目第二次建 ViewModel）。 */
    val restored: Boolean = saved.contains(K_CREATED)
    private var gone = false

    init {
        saved[K_CREATED] = true
        initial.bindLeave {
            gone = true
            controller.leave()
        }
        initial.bindController(controller)
        val gate = initial.restoreGate
        if (restored && gate != null) {
            viewModelScope.launch {
                gate()
                if (!gone) controller.load(initial.platform, initial.productKey, initial.itemRef)
            }
        } else if (initial.seed != null && !restored) {
            controller.loadSeeded(initial.platform, initial.productKey, initial.itemRef, initial.seed)
        } else {
            controller.load(initial.platform, initial.productKey, initial.itemRef)
        }
    }

    fun update(next: ProductPageConfig) {
        config = next
    }

    override fun onCleared() {
        gone = true
        controller.leave()
        super.onCleared()
    }

    private companion object {
        const val K_CREATED = "product_vm_created"
    }
}

/** 测试与冒烟用的标记。 */
object ProductTags {
    const val PAGE = "product_page"
    const val BUY = "product_buy"
    const val ERROR = "product_error"
    const val RETRY = "product_retry"
    const val RESEARCH = "product_research"
    const val LOADING = "product_loading"
    const val DISCLAIMER = "product_disclaimer"
    const val COMPARE_REASON = "product_compare_reason"
    const val ASK_AI = "product_ask_ai"
}

private object ProductKeys {
    const val TITLE = "product.title"
    const val BACK = "product.back"
    const val ASK_AI = "product.ask_ai"
    const val IMAGE = "product.image"
    const val RESEARCH = "product.research"
    const val RETRY = "external_page.retry"
    const val GENERIC_ERROR = "error.50001"
    const val LOADING = "search.loading"
    const val COMPARE_REASON = "no_rebate.price_compare"
    const val COMPARE_SIMILAR = "no_rebate.price_compare.similar"
}

/** 商品详情（设计稿 Product、ProductPublic、ProductCardStress、A11yFocusProduct）。 */
@Composable
fun ProductDetailScreen(config: ProductPageConfig) {
    val model: ProductDetailViewModel = viewModel { ProductDetailViewModel(config, createSavedStateHandle()) }
    model.update(config)
    val state by model.controller.state.collectAsStateWithLifecycle()
    val texts = config.texts
    BackHandler { config.actions.onBack() }
    // 配置变化（platform_status）时重新判定按钮（APP-06a 共性第 27 条）
    DisposableEffect(model) {
        val unsubscribe = config.subscribeConfig { model.controller.refreshView() }
        onDispose(unsubscribe)
    }
    val buyFocus = remember { FocusRequester() }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(ProductTags.PAGE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH.dp).fillMaxWidth().weight(1f)) {
            TitleBar(texts, config.actions.onBack)
            when (state.phase) {
                ProductPhase.LOADING -> Loading(texts)
                ProductPhase.ERROR -> ErrorContent(state, model, texts)
                else -> Content(state, model, texts, buyFocus)
            }
        }
    }
    if (state.phase == ProductPhase.CONTENT) config.purchase.overlay(buyFocus)
}

@Composable
private fun TitleBar(
    texts: ProductTexts,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = CouliSpacing.space1), contentAlignment = Alignment.Center) {
        Row(modifier = Modifier.fillMaxWidth()) {
            CouliIconButton(icon = CouliIcons.back, label = texts.text(ProductKeys.BACK), onClick = onBack)
        }
        Text(
            text = texts.text(ProductKeys.TITLE),
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Loading(texts: ProductTexts) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4).testTag(ProductTags.LOADING),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(IMAGE_RATIO))
        Skeleton(Modifier.fillMaxWidth().height(CouliSpacing.space16))
        Text(text = texts.text(ProductKeys.LOADING), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/**
 * 错误态：30141 商品已下架（置灰、不给购买，BR-PRICE-21）；30143 商品信息已失效 +【重新搜索】；其余通用错误态（文案带 trace6，共性第 6 条）
 * +【重试】（42901 限时禁用期间置灰，共性第 4 条）。
 */
@Composable
private fun ErrorContent(
    state: ProductSnapshot,
    model: ProductDetailViewModel,
    texts: ProductTexts,
) {
    val trace = ResultSignals.trace6(state.traceId)
    val vars =
        mapOf(
            "trace6" to trace,
            "platform_name" to texts.text(ProductCardKeys.platform(model.controller.platformRaw)),
        )
    // 追踪编号统一附上（共性第 12 条）：键里没有 {trace6} 的错误也带，已带的不重复
    val message =
        ResultSignals.withTrace6(
            texts.format(state.errorTextKey ?: ProductKeys.GENERIC_ERROR, vars).ifEmpty { texts.format(ProductKeys.GENERIC_ERROR, vars) },
            state.traceId,
        )
    // 加载失败：立即播报，读完后焦点到【重试】/【重新搜索】；下架没有动作，只播报（adaptation-a11y-spec §10，03 §10.5，A-R1-09）
    val actionFocus = remember { FocusRequester() }
    ErrorAnnouncement(message, actionFocus.takeIf { state.errorCode != ProductDetailController.OFF_SHELF })
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space6).testTag(ProductTags.ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Icon(CouliIcons.alert, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSpacing.space8))
        Text(
            text = message,
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        // 30141 商品已下架：置灰、不给购买，也没有重试（BR-PRICE-21）
        when (state.errorCode) {
            ProductDetailController.REF_EXPIRED -> {
                CouliButton(
                    text = texts.text(ProductKeys.RESEARCH),
                    onClick = model.config.actions.onResearch,
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.couliFocusTarget(actionFocus).testTag(ProductTags.RESEARCH),
                )
            }

            ProductDetailController.OFF_SHELF -> {
                Spacer(Modifier.height(CouliSpacing.space1))
            }

            else -> {
                CouliButton(
                    text = texts.text(ProductKeys.RETRY),
                    onClick = { model.controller.retry() },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    enabled = !state.retryBlocked,
                    modifier = Modifier.couliFocusTarget(actionFocus).testTag(ProductTags.RETRY),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Content(
    state: ProductSnapshot,
    model: ProductDetailViewModel,
    texts: ProductTexts,
    buyFocus: FocusRequester,
) {
    val product = state.product ?: return
    val view = state.view ?: return
    val card =
        ProductCardMapper.map(
            product,
            texts,
            model.config.clock(),
            buyEnabled = view.buyEnabled,
            purchaseStatus = view.purchaseStatus,
        )
    Column(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        ProductThumbnail(
            label = card.data.imageLabel,
            modifier = Modifier.fillMaxWidth().aspectRatio(IMAGE_RATIO),
            description = texts.format(ProductKeys.IMAGE, mapOf("title" to card.data.title)),
            imageUrl = card.data.imageUrl,
        )
        ProductCardLarge(data = card.data)
        // 比价无返利（open 复核后卡片的 no_rebate_cause=price_compare）：原因行 +【看看相似商品】（ProductPriceCompareCancelled，BR-PRICE-08）
        if (product.textOf("no_rebate_cause") == PRICE_COMPARE) {
            CompareReasonRow(texts) { model.config.actions.onSimilar(product) }
        }
        // 口径说明常驻全文（BR-PRICE-03：详情常驻）+ 入账说明（BR-TEXT-04、F-PROD-05）
        val credit =
            texts
                .text(CreditNote.TEXT_KEY)
                .takeIf { it.isNotEmpty() && CreditNote.visible(product, model.config.creditEnabled(model.controller.platformRaw)) }
        val lines = card.data.disclaimer + listOfNotNull(credit)
        if (lines.isNotEmpty()) {
            DisclaimerFooter(
                lines = lines.map { DisclaimerLine(it) },
                style = DisclaimerStyle.DETAIL_PANEL,
                modifier = Modifier.testTag(ProductTags.DISCLAIMER),
            )
        }
    }
    model.config.purchase.banner()
    BottomBar(card, view, model, texts, buyFocus)
}

/** 比价无返利的原因行（no_rebate.price_compare +【看看相似商品】）。 */
@Composable
private fun CompareReasonRow(
    texts: ProductTexts,
    onSimilar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ProductTags.COMPARE_REASON),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(
            text = texts.text(ProductKeys.COMPARE_REASON),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        CouliButton(
            text = texts.text(ProductKeys.COMPARE_SIMILAR),
            onClick = onSimilar,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
        )
    }
}

private fun JsonObject.textOf(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val PRICE_COMPARE = "price_compare"

/** 底栏：【问问 AI】+【购买】（价格不可用为「稍后再试」禁用，下架 / 没有按钮键时禁用）。读屏按可见文字 + 返利区间（A11yFocusProduct）。 */
@Composable
private fun BottomBar(
    card: MappedProductCard,
    view: ProductDetailView,
    model: ProductDetailViewModel,
    texts: ProductTexts,
    buyFocus: FocusRequester,
) {
    val purchaseOverride = model.config.purchase.button()
    val cardState =
        when {
            card.priceUnavailable || view.priceUnavailable -> BuyButtonState.RETRY_LATER

            // 平台维护 / 未开放（features.platform_status，共性第 27、31 条）：「稍后再试」/「X返利即将开放」禁用
            card.buyState == BuyButtonState.RETRY_LATER -> BuyButtonState.RETRY_LATER

            view.buyEnabled -> BuyButtonState.NORMAL

            else -> BuyButtonState.DISABLED
        }
    // 购买流程的覆盖只在卡片本来可买时生效（卡片的禁用优先）
    val override = purchaseOverride?.takeIf { cardState == BuyButtonState.NORMAL }
    val buyState = override?.state ?: cardState
    val retryLater = override?.text?.takeIf { buyState == BuyButtonState.RETRY_LATER } ?: card.retryLaterText
    val base = override?.text?.takeIf { buyState == BuyButtonState.NORMAL } ?: card.data.buyBase
    Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
    Row(
        modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface).padding(CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        val ask = model.config.actions.onAskAi
        val askShown by (model.config.actions.askAiVisible ?: ASK_AI_ALWAYS).collectAsStateWithLifecycle()
        // 入口关闭（agent.entry.visible=false、Agent 不可用）时不绘制、不回退成打开 AgentChat（R1-04、G56）
        if (ask != null && askShown) {
            CouliButton(
                text = texts.text(ProductKeys.ASK_AI),
                onClick = ask,
                variant = CouliButtonVariant.TEXT,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.testTag(ProductTags.ASK_AI),
            )
        }
        BuyButton(
            base = base,
            rebate = card.data.displayedRebate,
            onClick = {
                model.controller.state.value.product
                    ?.let { model.config.actions.onBuyIntent(it, view.publicMode) }
            },
            modifier = Modifier.weight(1f).couliFocusTarget(buyFocus).testTag(ProductTags.BUY),
            context = BuyButtonModel.CONTEXT_DETAIL,
            state = buyState,
            // 覆盖文字（如「安装X后下单才有返利」）时不拼返利金额
            amountHidden = override?.text != null,
            retryLaterText = retryLater,
            size = CouliButtonSize.STANDARD,
        )
    }
}

private const val CONTENT_MAX_WIDTH = 600
private const val IMAGE_RATIO = 1.4f
