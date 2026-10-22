package com.couli.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography

/**
 * 改价且返利归零的警告块（ProductPriceChangedRebateGone-v2）：警告底色圆角 12、内边距 12；20 警告图标（装饰）与标题首行顶对齐，
 * 「该商品当前暂无返利」14/600 +「继续购买不会获得返利」14，均为警告文字色。只改样式，金额口径与回调不变。
 */
@Composable
internal fun RebateGoneWarning(
    title: String,
    note: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text(text = title, style = CouliTextStyles.footnoteSemibold, color = CouliColors.statusWarningText)
            Text(text = note, style = CouliTypography.footnote, color = CouliColors.statusWarningText)
        }
    }
}

// 购买浮层的视觉小件（APP-06v-b）：淘宝未安装常驻说明、带按钮错误提示的主次选择、比价正文链接、轻提示图标分类。

/**
 * 淘宝未安装的常驻说明（ProductJumpNotInstalled-v2，08 BR-TEXT-14 表 C，G-D11）：底栏上方 info 图标 +「安装淘宝后下单才有返利」，只在本次
 * 口令复制成功（[PurchaseState.notInstalledTpwd]）时出现；轻提示消失后仍在，新一次购买开始即清除。
 */
@Composable
fun PurchaseNotInstalledBanner(
    controller: OpenPurchaseController,
    texts: TradeTexts,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (!state.notInstalled || !state.notInstalledTpwd) return
    val vars = mapOf("platform_name" to texts.text(TradeKeys.platform(state.platform)))
    Column(modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface).testTag(TradeTags.NOT_INSTALLED)) {
        Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = CouliSpacing.space4,
                        end = CouliSpacing.space4,
                        top = CouliSpacing.space3,
                        bottom = CouliSpacing.space1,
                    ),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                CouliIcons.info,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
            Text(text = texts.format(TradeKeys.NOT_INSTALLED, vars), style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    }
}

/** 带按钮错误提示的一个按钮：文案键 + 控制器回调。 */
internal typealias NoticeAction = Pair<String, (OpenPurchaseController) -> Unit>

/** 30103 / 30151 / 30153 同时给【联系客服】与【仍去购买（无返利）】时按画板换主次（G-D08）。 */
private fun PurchaseDialog.Notice.contactFirst(): Boolean = contact && noRebate && !retry

/** 主按钮：50303 →【稍后再试】只关弹窗（G-D09）；客服类 →【联系客服】；30121 →【仍去购买（无返利）】；其余【知道了】。 */
internal fun noticePrimary(dialog: PurchaseDialog.Notice): NoticeAction =
    when {
        dialog.retry -> TradeKeys.RETRY_LATER to OpenPurchaseController::dismissDialog
        dialog.contactFirst() -> TradeKeys.CONTACT to OpenPurchaseController::contactService
        dialog.noRebate -> TradeKeys.BUY_NO_REBATE to OpenPurchaseController::buyWithoutRebate
        else -> TradeKeys.OK to OpenPurchaseController::dismissDialog
    }

/** 次按钮（没有为 null）：50303 / 客服类 →【仍去购买（无返利）】；只给客服时 →【联系客服】。 */
internal fun noticeSecondary(dialog: PurchaseDialog.Notice): NoticeAction? =
    when {
        dialog.retry && dialog.noRebate -> TradeKeys.BUY_NO_REBATE to OpenPurchaseController::buyWithoutRebate
        dialog.contactFirst() -> TradeKeys.BUY_NO_REBATE to OpenPurchaseController::buyWithoutRebate
        dialog.contact -> TradeKeys.CONTACT to OpenPurchaseController::contactService
        else -> null
    }

/** 比价确认的正文：说明句 + 行内强调色「查看说明」链接（[link] 为 null 不显示）；链接是独立可点项（Role.Button，命中区由系统补到 48）。 */
@Composable
internal fun CompareBody(
    body: String,
    link: String?,
    onLink: () -> Unit,
) {
    val style = CouliTypography.footnote
    if (link == null) {
        Text(text = body, style = style, color = CouliColors.textPrimary)
        return
    }
    val linkStyle = CouliTextStyles.footnoteSemibold
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val placeholder =
        remember(link, density) {
            val size = measurer.measure(link, linkStyle).size
            with(density) {
                Placeholder(
                    width = (size.width + CouliSpacing.space1.roundToPx()).toSp(),
                    height = size.height.toSp(),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )
            }
        }
    Text(
        text =
            buildAnnotatedString {
                append(body)
                // 替代文字留空：读屏读正文，链接作为下一个可点项单独读出
                appendInlineContent(LINK_ID, " ")
            },
        style = style,
        color = CouliColors.textPrimary,
        inlineContent =
            mapOf(
                LINK_ID to
                    InlineTextContent(placeholder) {
                        Text(
                            text = link,
                            style = linkStyle,
                            color = CouliColors.textLink,
                            modifier =
                                Modifier
                                    .padding(start = CouliSpacing.space1)
                                    .clickable(role = Role.Button, onClick = onLink),
                        )
                    },
            ),
    )
}

/** 轻提示类别（B4）：取消 / 复核缓存 → 提示；平台维护 → 警告；口令已复制 → 复制。其余不带图标。 */
internal enum class ToastKind { INFO, WARNING, COPY }

internal fun toastKind(key: String): ToastKind? =
    when {
        key.startsWith(CANCELLED_PREFIX) || key == OpenPurchaseController.REQUOTE_KEY -> ToastKind.INFO
        key.startsWith(MAINTENANCE_PREFIX) -> ToastKind.WARNING
        key in COPIED_KEYS -> ToastKind.COPY
        else -> null
    }

/** 轻提示左侧图标（纯函数，视图层按键分类；不改提示时机与文案）。 */
internal fun toastIcon(key: String): ImageVector? =
    when (toastKind(key)) {
        ToastKind.INFO -> CouliIcons.info
        ToastKind.WARNING -> CouliIcons.alert
        ToastKind.COPY -> CouliIcons.copy
        null -> null
    }

private const val CANCELLED_PREFIX = "trade.cancelled."
private const val MAINTENANCE_PREFIX = "error.50301"
private val COPIED_KEYS =
    setOf(
        OpenPurchaseController.COPIED_KEY,
        OpenPurchaseController.NOT_INSTALLED_COPIED_KEY,
        OpenPurchaseController.NOT_INSTALLED_TPWD_COPIED_KEY,
    )
private const val LINK_ID = "compare_explain"
