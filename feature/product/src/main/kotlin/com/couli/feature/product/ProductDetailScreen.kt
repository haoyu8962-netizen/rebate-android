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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.BuyButton
import com.couli.core.designsystem.BuyButtonModel
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 详情页的文案（App 给：契约字典 → 草稿表）。 */
interface ProductTexts : ProductCardTexts {
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/** 详情页的出口（App 给）。 */
class ProductPageActions(
    val onBack: () -> Unit = {},
    /** 【购买】：只发出购买意图（TODO(APP-06b)：open 流程、TradeJump、AuthSheet）。 */
    val onBuyIntent: (product: JsonObject, publicMode: Boolean) -> Unit = { _, _ -> },
    /** 30143 商品信息已失效：【重新搜索】经 Router 打开 Search。 */
    val onResearch: () -> Unit = {},
    /** 【问问 AI】经 Router 打开 AgentChat。 */
    val onAskAi: () -> Unit = {},
)

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
)

/**
 * 详情页的入账说明「确认收货后随联盟月度结算入账」（BR-TEXT-04、F-PROD-05，A-R1-08）：有返利的商品显示（normal、price_compare_risk、
 * 金额未知的 amount_unknown；返利确定为 0 的不显示），入账开关明确关闭（credit.enabled.<platform>=off）时不显示；不写天数与日期。
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
        return basis in REBATE_BASES && max != 0L
    }
}

/** 详情页 ViewModel：执行器随回退栈条目；清理时作废（出栈的同步出口由 App 经 [ProductPageConfig.bindLeave] 先调）。 */
class ProductDetailViewModel(
    initial: ProductPageConfig,
) : ViewModel() {
    var config: ProductPageConfig = initial
        private set
    val controller = ProductDetailController(initial.api, initial.context, viewModelScope, initial.clock)

    init {
        initial.bindLeave(controller::leave)
        controller.load(initial.platform, initial.productKey, initial.itemRef)
    }

    fun update(next: ProductPageConfig) {
        config = next
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
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
}

/** 商品详情（设计稿 Product、ProductPublic、ProductCardStress、A11yFocusProduct）。 */
@Composable
fun ProductDetailScreen(config: ProductPageConfig) {
    val model: ProductDetailViewModel = viewModel { ProductDetailViewModel(config) }
    model.update(config)
    val state by model.controller.state.collectAsStateWithLifecycle()
    val texts = config.texts
    BackHandler { config.actions.onBack() }
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
                else -> Content(state, model, texts)
            }
        }
    }
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
) {
    val product = state.product ?: return
    val view = state.view ?: return
    val card = ProductCardMapper.map(product, texts, model.config.clock(), buyEnabled = view.buyEnabled)
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
        )
        ProductCardLarge(data = card.data)
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
    BottomBar(card, view, model, texts)
}

/** 底栏：【问问 AI】+【购买】（价格不可用为「稍后再试」禁用，下架 / 没有按钮键时禁用）。读屏按可见文字 + 返利区间（A11yFocusProduct）。 */
@Composable
private fun BottomBar(
    card: MappedProductCard,
    view: ProductDetailView,
    model: ProductDetailViewModel,
    texts: ProductTexts,
) {
    val buyState =
        when {
            card.priceUnavailable || view.priceUnavailable -> BuyButtonState.RETRY_LATER
            view.buyEnabled -> BuyButtonState.NORMAL
            else -> BuyButtonState.DISABLED
        }
    Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
    Row(
        modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface).padding(CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        CouliButton(
            text = texts.text(ProductKeys.ASK_AI),
            onClick = model.config.actions.onAskAi,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
        )
        BuyButton(
            base = card.data.buyBase,
            rebate = card.data.displayedRebate,
            onClick = {
                model.controller.state.value.product
                    ?.let { model.config.actions.onBuyIntent(it, view.publicMode) }
            },
            modifier = Modifier.weight(1f).testTag(ProductTags.BUY),
            context = BuyButtonModel.CONTEXT_DETAIL,
            state = buyState,
            retryLaterText = card.retryLaterText,
            size = CouliButtonSize.STANDARD,
        )
    }
}

private const val CONTENT_MAX_WIDTH = 600
private const val IMAGE_RATIO = 1.4f
