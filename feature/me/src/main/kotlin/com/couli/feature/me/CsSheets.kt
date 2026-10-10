package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.me.CsEntryDecision
import com.couli.core.me.CustomerServiceEntry
import com.couli.core.me.MeTexts
import kotlinx.coroutines.delay

/**
 * 统一客服出口的三张半屏（设计稿 CsHandoff、CsWechatMissing、CsOpenFailed；brief §2.9，G20；AdaptSheetRules）：标题 → 关闭 → 正文 → 安全提示「客服不会向你索要登录
 * 验证码和密码」→ 按钮；正文在半屏内滚动、按钮固定可见（短视口）。kf 缺失或不是 https 时是只有【去帮助中心】的降级半屏（CsOpenFailed 版式）。关闭后焦点回到
 * 触发控件由 CouliSheet 处理；按钮回调捕获半屏编号，过期的点击不生效。复制成功提示 cs.copy_link.done（polite 一次）。
 */
@Suppress("CyclomaticComplexMethod") // 三张半屏的版式与按钮逐项
@Composable
fun CsSheetHost(
    entry: CustomerServiceEntry,
    texts: MeTexts,
) {
    val sheet by entry.sheet.collectAsStateWithLifecycle()
    val toast by entry.toast.collectAsStateWithLifecycle()
    val current = sheet
    if (current != null) {
        val serial = current.serial
        val decision = current.decision
        val (titleKey, descKey) =
            when (decision.sheetRaw) {
                CsEntryDecision.HANDOFF -> "cs.title" to "cs.handoff.desc"
                CsEntryDecision.WECHAT_MISSING -> "cs.title" to "cs.wechat_unavailable"
                CsEntryDecision.OPEN_FAILED -> "cs.open_failed.title" to "cs.open_failed.desc"
                else -> "cs.open_failed.title" to "cs.help_only.desc"
            }
        CouliSheet(
            title = texts.text(titleKey),
            onDismissRequest = { entry.dismiss(serial) },
            closeLabel = texts.text("me.close"),
            actions = {
                Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3), modifier = Modifier.fillMaxWidth()) {
                    // 复制结果显示在半屏里（放在固定的操作区：半屏盖住了页面上的轻提示，正文区可能要滚动才看得到），polite 播报一次
                    toast?.let { t ->
                        Text(
                            text = texts.text(t.key),
                            style = CouliTypography.footnote,
                            color = CouliColors.statusSuccessText,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(MeTags.TOAST),
                        )
                        LaunchedEffect(t.serial) {
                            delay(TOAST_MS)
                            entry.consumeToast(t.serial)
                        }
                    }
                    decision.buttons.forEachIndexed { index, raw ->
                        val (key, tag, action) =
                            when (raw) {
                                CsEntryDecision.OPEN_WECHAT -> {
                                    Triple("cs.open_wechat", MeTags.CS_OPEN) { entry.openWechat(serial) }
                                }

                                CsEntryDecision.RETRY -> {
                                    Triple("cs.retry", MeTags.CS_RETRY) { entry.openWechat(serial) }
                                }

                                CsEntryDecision.COPY_LINK -> {
                                    Triple("cs.copy_link", MeTags.CS_COPY) { entry.copyLink(serial) }
                                }

                                else -> {
                                    val helpKey = if (decision.sheetRaw == CsEntryDecision.HANDOFF) "cs.handoff.go_help" else "cs.go_help"
                                    Triple(helpKey, MeTags.CS_HELP) { entry.goHelp(serial) }
                                }
                            }
                        CouliButton(
                            text = texts.text(key),
                            onClick = action,
                            variant =
                                if (index ==
                                    0
                                ) {
                                    com.couli.core.designsystem.CouliButtonVariant.PRIMARY
                                } else {
                                    CouliButtonVariant.SECONDARY
                                },
                            modifier = Modifier.fillMaxWidth().testTag(tag),
                        )
                    }
                }
            },
        ) {
            Column(modifier = Modifier.testTag(MeTags.CS_SHEET), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                Text(text = texts.text(descKey), style = CouliTypography.body, color = CouliColors.textPrimary)
                NoteStrip(text = texts.text("cs.safety"), icon = CouliIcons.info)
            }
        }
    }
    if (current == null) MeToastHost(toast?.let { texts.text(it.key) }, toast?.serial) { toast?.serial?.let(entry::consumeToast) }
}

private const val TOAST_MS = 2_000L
