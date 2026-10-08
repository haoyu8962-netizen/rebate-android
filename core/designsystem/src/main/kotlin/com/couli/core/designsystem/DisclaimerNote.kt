package com.couli.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester

/**
 * 口径说明弹层（设计稿 SearchDisclaimerNote；BR-PRICE-03 展开全文，APP-06a 共性第 5 条）：居中弹窗，标题 → 全部说明行（按宽度换行，
 * 长内容在弹窗内滚动，[CouliDialog] 的正文区）→【知道了】。遮罩、系统返回也能关闭；关闭后焦点回到 [restoreFocusTo]（卡片上的 i）。
 * 搜索与详情共用。
 */
@Composable
fun DisclaimerNoteDialog(
    title: String,
    lines: List<String>,
    closeText: String,
    onDismiss: () -> Unit,
    restoreFocusTo: FocusRequester? = null,
) {
    CouliDialog(
        title = title,
        confirmText = closeText,
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
        restoreFocusTo = restoreFocusTo,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                lines.forEach { line ->
                    Text(text = line, style = CouliTypography.body, color = CouliColors.textSecondary)
                }
            }
        },
    )
}
