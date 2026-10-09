package com.couli.core.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.couliCard
import kotlinx.coroutines.delay

/** 待跟单卡的测试标记。 */
object PendingTrackTags {
    const val CARD = "pending_track_card"
    const val CLOSE = "pending_track_close"
    const val CLAIM = "pending_track_claim"
    const val NOTIFY = "pending_track_notify"
    const val NOTIFY_ROW = "pending_track_notify_row"
    const val TOAST = "pending_track_toast"
}

/**
 * 待跟单卡区（设计稿 ProductPendingTrack / ProductPendingTrackDecided）：每张卡白底圆角 16，钟表图标 +「订单同步中」17/600 + 说明（{platform_name}
 * 取平台名）；右上关闭（读屏「关闭订单同步提示」，命中区 48）；show_claim_entry 时出「未跟单？去找回」；通知行（分隔线 + 铃铛 + perm.push.card_hint
 * +【开启通知】）只在 [PendingTrackCardView.notificationRow] 时出现。卡片是一个区域（paneTitle 为标题）。42901 禁用、在途、只读时关闭按钮不可点。
 * [onClaim] 经 Router 打开 FindOrder（App 给）。
 */
@Composable
fun PendingTrackCards(
    service: PendingTrackService,
    texts: OrderTexts,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
    readonly: Boolean = false,
) {
    val state by service.state.collectAsStateWithLifecycle()
    val toast = state.toastKey
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        state.cards.forEach { card ->
            PendingTrackCard(
                card = card,
                texts = texts,
                closeEnabled = !state.blocked && !readonly && card.key !in state.busy,
                onClose = { service.dismiss(card) },
                onClaim = onClaim,
                onNotify = { service.requestNotification(card) },
            )
        }
        if (toast != null) {
            LaunchedEffect(state.toastId) {
                delay(TOAST_MILLIS)
                service.consumeToast()
            }
            // 错误提示经统一的错误消息生成（带 trace6；APP-08 共性第 8、9 条）
            val message = state.toastError?.let { OrderErrorAction.message(texts, it) } ?: texts.text(toast)
            CouliToast(
                message = message,
                icon = CouliIcons.alert,
                announcementId = state.toastId,
                modifier = Modifier.testTag(PendingTrackTags.TOAST),
            )
        }
    }
}

@Composable
private fun PendingTrackCard(
    card: PendingTrackCardView,
    texts: OrderTexts,
    closeEnabled: Boolean,
    onClose: () -> Unit,
    onClaim: () -> Unit,
    onNotify: () -> Unit,
) {
    val title = texts.text(PendingTrackService.TITLE_KEY)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .semantics { paneTitle = title }
                .testTag(PendingTrackTags.CARD),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            Row(
                modifier = Modifier.padding(end = CouliSpacing.space8),
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    CouliIcons.clock,
                    contentDescription = null,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconMedium),
                )
                Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                    Text(
                        text = title,
                        style = CouliTextStyles.bodySemibold,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text =
                            texts.format(
                                PendingTrackService.DESC_KEY,
                                mapOf("platform_name" to texts.text("platform.${card.platform}")),
                            ),
                        style = CouliTypography.footnote,
                        color = CouliColors.textSecondary,
                    )
                }
            }
            if (card.claimEntry) {
                CouliButton(
                    text = texts.text(PendingTrackService.CLAIM_KEY),
                    onClick = onClaim,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(PendingTrackTags.CLAIM),
                )
            }
            if (card.notificationRow) NotificationRow(texts, onNotify)
        }
        CouliIconButton(
            icon = CouliIcons.close,
            label = texts.text(PendingTrackService.CLOSE_KEY),
            onClick = onClose,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconMedium,
            enabled = closeEnabled,
            modifier = Modifier.align(Alignment.TopEnd).testTag(PendingTrackTags.CLOSE),
        )
    }
}

/** 通知行（BR-ID-13 细则「通知权限的申请时机」）：说明 +【开启通知】；按钮放不下时换到下一行右对齐。 */
@Composable
private fun NotificationRow(
    texts: OrderTexts,
    onNotify: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
    EndWrapRow(
        modifier = Modifier.fillMaxWidth().testTag(PendingTrackTags.NOTIFY_ROW),
        start = {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2), verticalAlignment = Alignment.Top) {
                Icon(
                    CouliIcons.bell,
                    contentDescription = null,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconMedium),
                )
                Text(text = texts.text(PendingTrackService.HINT_KEY), style = CouliTypography.footnote, color = CouliColors.textPrimary)
            }
        },
        end = {
            CouliButton(
                text = texts.text(PendingTrackService.CTA_KEY),
                onClick = onNotify,
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.testTag(PendingTrackTags.NOTIFY),
            )
        },
    )
}

private const val TOAST_MILLIS = 2_000L
