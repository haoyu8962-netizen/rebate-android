package com.couli.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.BuyButtonOverride
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliDialogScrim
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.KeyValueRow
import com.couli.core.designsystem.PlatformIcon
import com.couli.core.designsystem.PlatformIconSize
import com.couli.core.designsystem.PriceTagModel
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.WholeNumberText
import com.couli.core.network.ResultSignals
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** 购买流程与授权管理的文案（App 给：契约字典 → 草稿表）。 */
interface TradeTexts : ProductCardTexts {
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/** 文案键（契约有的读契约；没有的在 App 的草稿表 TradeDraftTexts，三端同名，TODO(N-28)）。 */
object TradeKeys {
    const val OPENING = "buy.opening"
    const val OPENING_CANCEL = "buy.opening.cancel"
    const val TIMEOUT = "buy.timeout"
    const val RETRY = "external_page.retry"
    const val BROWSER_NOTICE = "trade.browser_notice"
    const val PRICE_TITLE = "trade.price_changed.title"
    const val PRICE_BEFORE = "trade.price_changed.before"
    const val PRICE_NOW = "trade.price_changed.now"

    /** 新价口径未补齐时「现在」一行不带口径词（三端同名，共性第 30、37、45 条）。 */
    const val PRICE_NOW_PLAIN = "trade.price_changed.now_plain"

    /** 原价口径未知时「之前」一行不带口径词（共性第 47 条）。 */
    const val PRICE_BEFORE_PLAIN = "trade.price_changed.before_plain"
    const val PRICE_ASK = "trade.price_changed.ask"
    const val COUPON_TITLE = "trade.coupon_gone.title"
    const val REBATE_GONE_LINE = "trade.rebate_gone.line"
    const val REBATE_GONE_NOTE = "trade.rebate_gone.note"
    const val REBATE_GONE_TITLE = "trade.rebate_gone.title"
    const val REBATE_GONE_BODY = "trade.rebate_gone.body"

    /** 返利归零弹窗、新价与原价不同（变动未达阈值）：「{price_now} {price}。继续购买不会获得返利」（三端同名，共性第 45 条）。 */
    const val REBATE_GONE_BODY_CHANGED = "trade.rebate_gone.body_changed"

    /** 返利归零、价格没有变化但口径未知：「价格没有变化，{price}。继续购买不会获得返利」（不默认「券后」，共性第 47 条）。 */
    const val REBATE_GONE_BODY_PLAIN = "trade.rebate_gone.body_plain"
    const val CANCEL = "trade.cancel"
    const val CONTINUE = "trade.continue"
    const val COMPARE_TITLE = "no_rebate.price_compare.confirm"
    const val COMPARE_BODY = "trade.compare.body"
    const val COMPARE_EXPLAIN = "trade.compare.explain"
    const val COMPARE_SIMILAR = "no_rebate.price_compare.similar"
    const val BUY_NO_REBATE = "trade.buy_no_rebate"
    const val CLOSE = "trade.close"
    const val AUTH_REASON = "trade.auth.reason"
    const val AUTH_PRIVACY = "trade.auth.privacy"
    const val REBIND_NOTE = "auth_manage.rebind_note"
    const val AUTHORIZE = "auth_manage.action.authorize"
    const val DECLINED_TITLE = "trade.auth_declined.title"
    const val DECLINED_BODY = "trade.auth_declined.body"
    const val FAILED_TITLE = "error.30104.credential_invalid"
    const val FAILED_BODY = "trade.auth_failed.body"
    const val UNAVAILABLE_BODY = "trade.auth_unavailable.body"
    const val OK = "trade.ok"
    const val TIP_TITLE = "trade.jump_tip.title"
    const val TIP_LINE_1 = "trade.jump_tip.line1"
    const val TIP_LINE_2 = "trade.jump_tip.line2"
    const val TIP_GO = "trade.jump_tip.go"
    const val SDK_FAILED_TITLE = "jump.taobao_sdk_unavailable"
    const val SDK_FAILED_BODY = "trade.sdk_failed.body"
    const val NOT_INSTALLED = "trade.not_installed"

    /** 淘宝未安装、口令已复制后的按钮文字「复制淘口令」（G-D11，三端同名，CT-16f）。 */
    const val NOT_INSTALLED_COPY_TPWD = "trade.not_installed.copy_tpwd"
    const val CONTACT = "trade.contact"
    const val RETRY_LATER = "card.retry_later"
    const val NOT_LAUNCHED = "error.50301.not_launched"

    fun platform(platform: String): String = "platform.$platform"

    /** AuthSheet 标题：30101 / 30102 / 30111 的提示原文（ProductAuthSheet）。 */
    fun authTitle(platform: String): String = if (platform == "pdd") "error.30111" else "error.30101"
}

/** 测试与冒烟用的标记。 */
object TradeTags {
    const val LOADING = "trade_loading"
    const val LOADING_CANCEL = "trade_loading_cancel"
    const val TIMEOUT = "trade_timeout"
    const val TIMEOUT_RETRY = "trade_timeout_retry"
    const val BROWSER = "trade_browser_notice"
    const val AUTH_SHEET = "trade_auth_sheet"
    const val AUTHORIZE = "trade_authorize"
    const val JUMP_TIP = "trade_jump_tip"
    const val TOAST = "trade_toast"
    const val COMPARE = "trade_compare"
    const val NOT_INSTALLED = "trade_not_installed"
}

/**
 * 购买流程 → 按钮覆盖（加载、限流、未安装提示）；没有覆盖为 null。点击结果的禁用不在这里：下架写进那张卡、平台停用按平台进卡片映射
 * （A-R1-05 / 06），由卡片自己的按钮状态表达。
 *
 * 淘宝未安装且口令已复制（[PurchaseState.notInstalledTpwd]）：详情底栏上方有常驻说明（[PurchaseNotInstalledBanner]），按钮写【复制淘口令】
 * （G-D11；再点仍是一次新的购买，复制由服务端下发的步骤执行）。[copyTpwd] = false 时（搜索卡，没有常驻说明行）仍写安装说明句。
 */
fun purchaseButton(
    state: PurchaseState,
    texts: TradeTexts,
    copyTpwd: Boolean = true,
): BuyButtonOverride? {
    val vars = mapOf("platform_name" to texts.text(TradeKeys.platform(state.platform)))
    return when {
        state.phase == PurchasePhase.LOADING -> {
            BuyButtonOverride(BuyButtonState.LOADING, null)
        }

        state.rateLimited -> {
            BuyButtonOverride(BuyButtonState.DISABLED, null)
        }

        state.notInstalled && state.notInstalledTpwd && copyTpwd -> {
            BuyButtonOverride(BuyButtonState.NORMAL, texts.text(TradeKeys.NOT_INSTALLED_COPY_TPWD))
        }

        state.notInstalled -> {
            BuyButtonOverride(BuyButtonState.NORMAL, texts.format(TradeKeys.NOT_INSTALLED, vars))
        }

        else -> {
            null
        }
    }
}

/**
 * 搜索结果卡的购买按钮覆盖（[card] 是列表里的这张卡，[current] 是本页购买流程正在处理的卡）：42901 限流按 Retry-After 置灰本页每一张卡
 * （执行层同样拦下，与详情一致，APP-06b 共性第 36 条）；加载、未安装提示只给正在购买的那张卡。
 */
fun searchPurchaseButton(
    state: PurchaseState,
    texts: TradeTexts,
    card: JsonObject,
    current: JsonObject,
): BuyButtonOverride? =
    when {
        state.rateLimited -> BuyButtonOverride(BuyButtonState.DISABLED, null)
        CardPatch.sameCard(card, current) -> purchaseButton(state, texts, copyTpwd = false)
        else -> null
    }

/**
 * 超时提示条（ProductBuyTimeout）：底栏上方「网络不稳定，请重试」+【重试】（沿用原键，TRADE-22）。出现时播报一次（03 §10.5）。
 */
@Composable
fun PurchaseTimeoutBanner(
    controller: OpenPurchaseController,
    texts: TradeTexts,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (state.phase != PurchasePhase.TIMEOUT) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                .background(CouliColors.statusErrorBackground, RoundedCornerShape(CouliRadius.button))
                .padding(start = CouliSpacing.space3)
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .testTag(TradeTags.TIMEOUT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Text(
            text = texts.text(TradeKeys.TIMEOUT),
            style = CouliTypography.footnote,
            color = CouliColors.statusErrorText,
            modifier = Modifier.weight(1f).padding(vertical = CouliSpacing.space3),
        )
        CouliButton(
            text = texts.text(TradeKeys.RETRY),
            onClick = controller::retryAfterTimeout,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.testTag(TradeTags.TIMEOUT_RETRY),
        )
    }
}

/**
 * 购买浮层：加载层（ProductBuyOpening，可取消）、「将通过浏览器打开」（ProductJumpBrowser）、各确认框与半屏、轻提示。
 * [buyFocus] 是购买按钮的焦点：弹层关闭后焦点回到它（A11yFocusAuthSheet「返回」）。
 */
@Composable
fun PurchaseOverlay(
    controller: OpenPurchaseController,
    texts: TradeTexts,
    buyFocus: FocusRequester?,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val vars = mapOf("platform_name" to texts.text(TradeKeys.platform(state.platform)))
    if (state.phase == PurchasePhase.LOADING) {
        BlockingLayer(
            texts.format(TradeKeys.OPENING, vars),
            texts.text(TradeKeys.OPENING_CANCEL),
            controller::cancelLoading,
            TradeTags.LOADING,
        )
    }
    if (state.browserNotice) {
        BlockingLayer(
            texts.format(TradeKeys.BROWSER_NOTICE, vars),
            texts.text(TradeKeys.OPENING_CANCEL),
            controller::cancelBrowser,
            TradeTags.BROWSER,
        )
    }
    state.dialog?.let { PurchaseDialogs(it, controller, texts, vars, buyFocus) }
    state.toast?.let { toast ->
        LaunchedEffect(toast.id) {
            delay(TOAST_MILLIS)
            controller.consumeToast()
        }
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            val kind = toastKind(toast.key)
            CouliToast(
                message = ResultSignals.withTrace6(texts.format(toast.key, vars), toast.traceId),
                modifier =
                    Modifier
                        .padding(
                            top = CouliSpacing.space12,
                            start = CouliSpacing.space4,
                            end = CouliSpacing.space4,
                        ).testTag(TradeTags.TOAST),
                icon = toastIcon(toast.key),
                announcementId = toast.id,
                iconTint = if (kind == ToastKind.WARNING) CouliColors.statusWarningText else CouliColors.textSecondary,
            )
        }
    }
}

/** 居中的阻断层：转圈 + 一句 +【取消】（返回键 = 取消；点外部不关）。只写平台名，不显示任何金额（03 §4.5）。 */
@Composable
private fun BlockingLayer(
    message: String,
    cancel: String,
    onCancel: () -> Unit,
    tag: String,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        // 遮罩用令牌 overlay.scrim（关系统 dim，ProductBuyOpening-v2）
        CouliDialogScrim(CouliColors.overlayScrim)
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .padding(CouliSpacing.space6)
                    .widthIn(min = LAYER_MIN_WIDTH.dp, max = LAYER_MAX_WIDTH.dp)
                    .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.panel))
                    .padding(CouliSpacing.space5)
                    .semantics { paneTitle = message }
                    .testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            CircularProgressIndicator(
                color = CouliColors.textSecondary,
                strokeWidth = SPINNER_STROKE.dp,
                modifier = Modifier.size(CouliSizes.iconLarge),
            )
            Text(
                text = message,
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            CouliButton(
                text = cancel,
                onClick = onCancel,
                variant = CouliButtonVariant.TEXT,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.testTag(TradeTags.LOADING_CANCEL),
            )
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // 每种弹层对应一个画板
@Composable
private fun PurchaseDialogs(
    dialog: PurchaseDialog,
    controller: OpenPurchaseController,
    texts: TradeTexts,
    vars: Map<String, String>,
    buyFocus: FocusRequester?,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (dialog) {
        is PurchaseDialog.ConfirmPrice -> {
            CouliDialog(
                title = texts.text(if (dialog.coupon) TradeKeys.COUPON_TITLE else TradeKeys.PRICE_TITLE),
                confirmText = texts.text(TradeKeys.CONTINUE),
                onConfirm = controller::confirm,
                onDismissRequest = controller::dismissDialog,
                dismissText = texts.text(TradeKeys.CANCEL),
                restoreFocusTo = buyFocus,
                scrim = CouliColors.overlayScrim,
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                        PriceCompareBox(
                            dialog.oldFen,
                            dialog.newFen,
                            beforeLine(controller.cardBeforeResponse, texts),
                            nowLine(state.latest ?: controller.currentCard, dialog.newFen, texts),
                        )
                        if (dialog.rebateGone) {
                            // 改价且返利归零（ProductPriceChangedRebateGone-v2）：警告底 + 警告图标 + 警告色两行
                            RebateGoneWarning(texts.text(TradeKeys.REBATE_GONE_LINE), texts.text(TradeKeys.REBATE_GONE_NOTE))
                        }
                        Text(text = texts.text(TradeKeys.PRICE_ASK), style = CouliTypography.footnote, color = CouliColors.textPrimary)
                    }
                },
            )
        }

        is PurchaseDialog.ConfirmRebateGone -> {
            CouliDialog(
                title = texts.text(TradeKeys.REBATE_GONE_TITLE),
                message = rebateGoneBody(dialog, controller.cardBeforeResponse, state.latest ?: controller.currentCard, texts),
                confirmText = texts.text(TradeKeys.CONTINUE),
                onConfirm = controller::confirm,
                onDismissRequest = controller::dismissDialog,
                dismissText = texts.text(TradeKeys.CANCEL),
                restoreFocusTo = buyFocus,
                scrim = CouliColors.overlayScrim,
            )
        }

        PurchaseDialog.CompareNoRebate -> {
            // 比价无返利确认（ProductPriceCompareNoRebate-v2，BR-PRICE-08，G-D10）：居中弹窗 + 右上关闭；主【看看相似商品】、次【仍去购买（无返利）】；
            // 「查看说明」是正文里的链接（没有配置文章时不显示）。回调不变：主 → similar、次 → confirm、关闭 / 遮罩 / 返回 → dismissDialog
            CouliDialog(
                title = texts.text(TradeKeys.COMPARE_TITLE),
                confirmText = texts.text(TradeKeys.COMPARE_SIMILAR),
                onConfirm = { controller.similar() },
                onDismissRequest = controller::dismissDialog,
                modifier = Modifier.testTag(TradeTags.COMPARE),
                dismissText = texts.text(TradeKeys.BUY_NO_REBATE),
                onDismiss = controller::confirm,
                restoreFocusTo = buyFocus,
                body = {
                    CompareBody(
                        body = texts.text(TradeKeys.COMPARE_BODY),
                        link = texts.text(TradeKeys.COMPARE_EXPLAIN).takeIf { controller.explanationAvailable },
                        onLink = controller::viewExplanation,
                    )
                },
                closeLabel = texts.text(TradeKeys.CLOSE),
                scrim = CouliColors.overlayScrim,
            )
        }

        is PurchaseDialog.AuthSheet -> {
            AuthSheet(dialog, controller, texts, vars, buyFocus)
        }

        is PurchaseDialog.AuthDeclined -> {
            CouliDialog(
                title = texts.text(if (dialog.failed) TradeKeys.FAILED_TITLE else TradeKeys.DECLINED_TITLE),
                message = texts.format(if (dialog.failed) TradeKeys.FAILED_BODY else TradeKeys.DECLINED_BODY, vars),
                confirmText = texts.text(TradeKeys.RETRY),
                onConfirm = controller::retryPurchase,
                onDismissRequest = controller::dismissDialog,
                dismissText = texts.text(TradeKeys.BUY_NO_REBATE),
                onDismiss = controller::buyWithoutRebate,
                restoreFocusTo = buyFocus,
                // 授权失败（ProductAuthFailed-v2）标题前错误色警告；用户拒绝（ProductAuthDeclined-v2）无图标
                titleIcon = CouliIcons.alert.takeIf { dialog.failed },
                titleIconTint = CouliColors.statusErrorText,
                scrim = CouliColors.overlayScrim,
            )
        }

        is PurchaseDialog.AuthCheckFailed -> {
            // 授权结果查不到（A-R2-06）：对应错误（5xxxx 带 trace6）+【重试】重新查询（42901 期间置灰）+【取消】
            val title =
                ResultSignals.withTrace6(
                    texts.format(
                        dialog.textKey ?: OpenPurchaseController.GENERIC_KEY,
                        vars + ("trace6" to ResultSignals.trace6(dialog.traceId)),
                    ),
                    dialog.traceId,
                )
            CouliDialog(
                title = title,
                confirmText = texts.text(TradeKeys.RETRY),
                onConfirm = controller::recheckAuth,
                confirmEnabled = !state.rateLimited,
                onDismissRequest = controller::dismissDialog,
                dismissText = texts.text(TradeKeys.CANCEL),
                restoreFocusTo = buyFocus,
                scrim = CouliColors.overlayScrim,
            )
        }

        is PurchaseDialog.AuthUnavailable -> {
            CouliDialog(
                title = texts.format(dialog.textKey ?: TradeKeys.SDK_FAILED_TITLE, vars),
                message = texts.text(TradeKeys.UNAVAILABLE_BODY),
                confirmText = texts.text(TradeKeys.OK),
                onConfirm = controller::dismissDialog,
                onDismissRequest = controller::dismissDialog,
                restoreFocusTo = buyFocus,
                // ProductAuthUnavailable-v2：标题前次文字色 info
                titleIcon = CouliIcons.info,
                titleIconTint = CouliColors.textSecondary,
                scrim = CouliColors.overlayScrim,
            )
        }

        is PurchaseDialog.JumpTip -> {
            CouliSheet(
                title = texts.text(TradeKeys.TIP_TITLE),
                onDismissRequest = controller::dismissDialog,
                restoreFocusTo = buyFocus,
                // 容器保持半屏（定稿画板 ProductJumpTip-v2，G-D15），标题 20/700
                titleStyle = CouliTextStyles.sheetTitle,
                actions = {
                    CouliButton(
                        text = texts.format(TradeKeys.TIP_GO, vars),
                        onClick = controller::confirm,
                        modifier = Modifier.fillMaxWidth().testTag(TradeTags.JUMP_TIP),
                    )
                },
            ) {
                // 下单须知正文 17 / 25.5（ProductJumpTip-v2）；授权说明仍 14 原档
                BulletLines(
                    listOf(texts.text(TradeKeys.TIP_LINE_1), texts.text(TradeKeys.TIP_LINE_2)),
                    boxed = false,
                    style = CouliTypography.body,
                    bulletTop = CouliSpacing.space2 + CouliSpacing.space1 / 2,
                )
            }
        }

        PurchaseDialog.SdkFailed -> {
            CouliDialog(
                title = texts.text(TradeKeys.SDK_FAILED_TITLE),
                message = texts.text(TradeKeys.SDK_FAILED_BODY),
                confirmText = texts.text(TradeKeys.OK),
                onConfirm = controller::dismissDialog,
                onDismissRequest = controller::dismissDialog,
                restoreFocusTo = buyFocus,
                // ProductJumpSdkFailed-v2：标题前错误色警告
                titleIcon = CouliIcons.alert,
                titleIconTint = CouliColors.statusErrorText,
                scrim = CouliColors.overlayScrim,
            )
        }

        is PurchaseDialog.Notice -> {
            NoticeDialog(dialog, controller, texts, vars, buyFocus)
        }
    }
}

@Composable
private fun NoticeDialog(
    dialog: PurchaseDialog.Notice,
    controller: OpenPurchaseController,
    texts: TradeTexts,
    vars: Map<String, String>,
    buyFocus: FocusRequester?,
) {
    val title = ResultSignals.withTrace6(texts.format(dialog.textKey ?: OpenPurchaseController.GENERIC_KEY, vars), dialog.traceId)
    // 50303（ProductPriceUnconfirmed，G-D09）：主【稍后再试】只关弹窗、留在本页（用户再点购买才发新一次 open），次【仍去购买（无返利）】；
    // 30103 / 30151 / 30153（ProductAuthBlocked，G-D08）：主【联系客服】、次【仍去购买（无返利）】（只换位置与样式，回调不变）、标题前警告图标；
    // 30121：只有无返利购买
    val primary = noticePrimary(dialog)
    val secondary = noticeSecondary(dialog)
    CouliDialog(
        title = title,
        confirmText = texts.text(primary.first),
        onConfirm = { primary.second(controller) },
        onDismissRequest = controller::dismissDialog,
        dismissText = secondary?.let { texts.text(it.first) },
        onDismiss = { (secondary?.second ?: OpenPurchaseController::dismissDialog)(controller) },
        restoreFocusTo = buyFocus,
        titleIcon =
            when {
                dialog.contact -> CouliIcons.alert
                dialog.retry -> CouliIcons.info
                else -> null
            },
        titleIconTint = if (dialog.contact) CouliColors.statusWarningText else CouliColors.textSecondary,
        scrim = CouliColors.overlayScrim,
    )
}

/**
 * 平台授权说明半屏（ProductAuthSheet、AdaptShortAuthSheet、A11yFocusAuthSheet）：标题 → 关闭 → 正文（说明框，内部滚动）→【去授权】
 * （固定在底部）。读屏焦点打开时进标题、只在半屏内循环；关闭（按钮、遮罩、返回）= 用户拒绝授权，焦点回到购买按钮。
 */
@Composable
private fun AuthSheet(
    dialog: PurchaseDialog.AuthSheet,
    controller: OpenPurchaseController,
    texts: TradeTexts,
    vars: Map<String, String>,
    buyFocus: FocusRequester?,
) {
    val taobao = dialog.platform == TAOBAO
    CouliSheet(
        title = texts.text(TradeKeys.authTitle(dialog.platform)),
        onDismissRequest = controller::dismissDialog,
        restoreFocusTo = buyFocus,
        closeLabel = texts.text(TradeKeys.CLOSE),
        // 标题 20/700 + 前置 32 平台官方图标（ProductAuthSheet-v2，G-D14；装饰，读屏只读标题）
        titleStyle = CouliTextStyles.sheetTitle,
        titleLeading = { PlatformIcon(platformCode = dialog.platform, size = PlatformIconSize.SHEET) },
        actions = {
            CouliButton(
                text = texts.text(TradeKeys.AUTHORIZE),
                onClick = controller::authorize,
                loading = dialog.busy,
                modifier = Modifier.fillMaxWidth().testTag(TradeTags.AUTHORIZE),
            )
        },
    ) {
        val lines =
            listOfNotNull(
                texts.format(TradeKeys.AUTH_REASON, vars),
                texts.text(TradeKeys.AUTH_PRIVACY).takeIf { taobao },
                texts.text(TradeKeys.REBIND_NOTE).takeIf { taobao },
            ).filter(String::isNotEmpty)
        Box(Modifier.testTag(TradeTags.AUTH_SHEET)) { BulletLines(lines, boxed = true) }
    }
}

/** 圆点列表（说明框）。[style] 默认 14 档（授权说明）；[bulletTop] 让圆点与首行垂直居中。 */
@Composable
private fun BulletLines(
    lines: List<String>,
    boxed: Boolean,
    style: TextStyle = CouliTypography.footnote,
    bulletTop: Dp = CouliSpacing.space2,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (boxed) {
                        Modifier
                            .background(
                                CouliColors.backgroundMuted,
                                RoundedCornerShape(CouliRadius.button),
                            ).padding(CouliSpacing.space3)
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Box(
                    Modifier
                        .padding(top = bulletTop)
                        .size(BULLET.dp)
                        .background(CouliColors.textSecondary, RoundedCornerShape(CouliRadius.pill)),
                )
                Text(text = line, style = style, color = CouliColors.textPrimary)
            }
        }
    }
}

/** 价格变动的对照框：之前（[beforeText]：原卡口径已知才带口径词）/ 现在（[nowText]：新价口径已补齐才带口径词）。 */
@Composable
private fun PriceCompareBox(
    oldFen: Long?,
    newFen: Long?,
    beforeText: String,
    nowText: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        PriceRow(beforeText, oldFen, emphasized = false)
        PriceRow(nowText, newFen, emphasized = true)
    }
}

@Composable
private fun PriceRow(
    label: String,
    fen: Long?,
    emphasized: Boolean,
) {
    // 金额未知时不显示数字（不补 0）
    // 判据 1：价格经共用整段数字件，贴右；标签旁放不下时整行改上下排
    KeyValueRow(modifier = Modifier.fillMaxWidth(), gap = 0.dp, centerVertically = true, valueAtEnd = true) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        fen?.let {
            WholeNumberText(
                text = CouliFormat.money(it),
                style = if (emphasized) CouliTextStyles.bodySemibold else CouliTypography.footnote,
                color = CouliColors.textPrimary,
            )
        }
    }
}

/**
 * 确认框「现在」一行的口径词（共性第 21、30、37 条）：新价格按当前可用的券重算（coupon_gone 可能换了新券，普通改价也可能新增或去掉了券），
 * 所以券额未定前不能沿用点击时的口径，也不能由 coupon_gone 推断「售价」：[latest]（同一次 open 写入、权威重取补齐后的卡）的券后价与弹窗
 * 新价一致、三个价格字段都已知时按它的券额给口径词；否则为 null（调用方只写「现在」，trade.price_changed.now_plain）。
 */
@Suppress("ReturnCount", "ComplexCondition") // 没有卡、价格构成不全或与新价不符两个出口
internal fun nowPriceLabel(
    latest: JsonObject?,
    newFen: Long?,
): String? {
    val card = latest ?: return null
    val price = card.fen("price_fen")
    val coupon = card.fen("coupon_fen")
    val final = card.fen("final_price_fen")
    if (price == null || coupon == null || final == null || newFen == null || final != newFen) return null
    return PriceTagModel.make(card.text("platform").orEmpty(), price, coupon, final, isPresale = false).mainLabel
}

/** 「现在（券后）」或口径未补齐时的「现在」。 */
internal fun nowLine(
    latest: JsonObject?,
    newFen: Long?,
    texts: TradeTexts,
): String =
    nowPriceLabel(latest, newFen)?.let { texts.format(TradeKeys.PRICE_NOW, mapOf("price_label" to it)) }
        ?: texts.text(TradeKeys.PRICE_NOW_PLAIN)

/**
 * 返利归零弹窗正文（BR-PRICE-13，共性第 45 条）：只有新旧金额相同才写「价格没有变化，<原卡口径> ¥x」；新价不同（变动未达阈值）写
 * 「现在（<新价口径>）¥新」，券额未补齐时用不带口径词的「现在」；新价未知只说明继续购买不会获得返利。
 */
internal fun rebateGoneBody(
    dialog: PurchaseDialog.ConfirmRebateGone,
    before: JsonObject,
    latest: JsonObject?,
    texts: TradeTexts,
): String {
    val newFen = dialog.newFen ?: return texts.text(TradeKeys.REBATE_GONE_NOTE)
    val price = CouliFormat.money(newFen)
    return if (dialog.oldFen == newFen) {
        // 口径取原卡；原卡口径未知时用补齐后的新卡（金额相同）；都未知就不带口径词（共性第 47 条）
        val label = priceLabel(before) ?: nowPriceLabel(latest, newFen)
        label?.let { texts.format(TradeKeys.REBATE_GONE_BODY, mapOf("price_label" to it, "price" to price)) }
            ?: texts.format(TradeKeys.REBATE_GONE_BODY_PLAIN, mapOf("price" to price))
    } else {
        texts.format(TradeKeys.REBATE_GONE_BODY_CHANGED, mapOf("price_now" to nowLine(latest, newFen, texts), "price" to price))
    }
}

/** 「之前（券后）」或原价口径未知时的「之前」（共性第 47 条）。 */
internal fun beforeLine(
    before: JsonObject,
    texts: TradeTexts,
): String =
    priceLabel(before)?.let { texts.format(TradeKeys.PRICE_BEFORE, mapOf("price_label" to it)) }
        ?: texts.text(TradeKeys.PRICE_BEFORE_PLAIN)

/**
 * 卡片的价格口径词（券后 / 售价 / 拼单券后…，BR-PRICE-04 / 05）；价格字段不全（口径未知）时为 null，调用方用不带口径词的写法，
 * 不默认按有券写「券后」（共性第 47 条）。
 */
internal fun priceLabel(card: JsonObject): String? {
    val price = card.fen("price_fen")
    val coupon = card.fen("coupon_fen")
    val final = card.fen("final_price_fen")
    if (price == null || coupon == null || final == null) return null
    return PriceTagModel.make(card.text("platform").orEmpty(), price, coupon, final, isPresale = false).mainLabel
}

/** 页面级标题（读屏标题角色）。 */
internal fun Modifier.headingRole(): Modifier = semantics { heading() }

private const val TAOBAO = "taobao"
private const val TOAST_MILLIS = 2_500L
private const val LAYER_MIN_WIDTH = 200
private const val LAYER_MAX_WIDTH = 320
private const val SPINNER_STROKE = 2.5f
private const val BULLET = 5
