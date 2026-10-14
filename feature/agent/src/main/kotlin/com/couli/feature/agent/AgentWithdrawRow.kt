package com.couli.feature.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.couli.core.agent.AgentDraftTexts
import com.couli.core.agent.AgentTexts
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography

/**
 * 设置页【撤回 AI 服务同意】一行（G18，鸿蒙 H07：设置条目表受 me-vectors 锁定，单独一张卡一行）。只在 Agent 可用的构建与环境、且已同意隐私政策时由 App 放进设置页
 * （Release 现为不显示，G01）。确认框的实例由 App 持有（Android R1-01，APP-10 共性第 25 条）：点行时 [onOpen] 在**打开确认框的这一刻**固定身份、页面许可与
 * 一次性编号，[confirmId] 不为 null 时显示这一个实例；身份变化时 App 销毁它（[confirmId] 变为 null，框随之消失）。[onConfirm] / [onCancel] 只提交渲染时的
 * 那个编号，App 提交前比较，失效直接拒绝；这里不读任何当前状态。[enabled]：撤回进行中、42901 禁用期为 false。
 */
@Composable
fun AgentWithdrawRow(
    confirmId: Long?,
    onOpen: () -> Unit,
    onConfirm: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    enabled: Boolean,
    texts: AgentTexts = AgentDraftTexts,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                .testTag(AgentTags.WITHDRAW_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = texts.text("agent.draft.withdraw.row"),
            style = CouliTypography.body,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Icon(CouliIcons.chevron, contentDescription = null, tint = CouliColors.textSecondary)
    }
    val id = confirmId ?: return
    CouliDialog(
        title = texts.text("agent.draft.withdraw.confirm_title"),
        message = texts.text("agent.draft.withdraw.confirm_body"),
        // 撤回确认框（与 APP-12 撤回同意一致）：【撤回】是次按钮、点遮罩不执行也不关闭
        confirmText = texts.text("agent.draft.withdraw.cancel"),
        onConfirm = { onCancel(id) },
        dismissText = texts.text("agent.draft.withdraw.confirm"),
        onDismiss = { onConfirm(id) },
        onDismissRequest = { onCancel(id) },
        dismissOnClickOutside = false,
        dismissTag = AgentTags.WITHDRAW_CONFIRM,
    )
}
