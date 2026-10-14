package com.couli.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.couli.core.agent.AgentConsentTicket
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentTexts
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography

/**
 * AgentConsent（BR-ID-14、BR-AI-13，G16、G17）：系统模态（Dialog 窗口：系统返回先关它、读屏只能访问弹窗内容、关闭后焦点回原位，APP-11 第 3 条）。
 * 说明文字（草稿，待法务定稿，不得提审，G16）+ **默认不勾选**的勾选框 +【同意并使用】（勾选后可点）+【暂不使用】。回调捕获这一张弹窗票据（控制器编号、弹窗编号、
 * 待重放请求），只提交自己的票据（APP-11 第 17 条）：系统返回 / 点遮罩 = 关闭本实例（按暂不使用显示说明，但不记录 accepted=false，鸿蒙 H09）。
 * 勾选状态只在这一张票据的组合里（换一张票据重新从不勾选开始）。
 */
@Composable
internal fun AgentConsentDialog(
    ticket: AgentConsentTicket,
    controller: AgentController,
    texts: AgentTexts,
) {
    var checked by remember(ticket) { mutableStateOf(false) }
    CouliDialog(
        title = texts.text("agent.draft.consent.title"),
        confirmText = texts.text("agent.draft.consent.agree"),
        onConfirm = { if (checked) controller.consentAgree(ticket) },
        onDismissRequest = { controller.consentDismissed(ticket) },
        dismissText = texts.text("agent.draft.consent.decline"),
        onDismiss = { controller.consentDecline(ticket) },
        confirmEnabled = checked,
        dismissOnClickOutside = true,
        confirmTag = AgentTags.CONSENT_AGREE,
        dismissTag = AgentTags.CONSENT_DECLINE,
        body = {
            Column(modifier = Modifier.testTag(AgentTags.CONSENT_DIALOG), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                listOf(
                    "agent.draft.consent.line1",
                    "agent.draft.consent.line2",
                    "agent.draft.consent.line3",
                    "agent.draft.consent.pending",
                ).forEach { key ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                        Icon(
                            CouliIcons.info,
                            contentDescription = null,
                            tint = CouliColors.textSecondary,
                            modifier = Modifier.padding(top = CouliSpacing.space1),
                        )
                        Text(text = texts.text(key), style = CouliTypography.footnote, color = CouliColors.textPrimary)
                    }
                }
                // 勾选框与说明合成一个可读、可点的控件（语义树里勾选状态与说明可读，brief §3 Android）
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = CouliSizes.touchTarget)
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it })
                            .testTag(AgentTags.CONSENT_CHECK),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(text = texts.text("agent.draft.consent.check"), style = CouliTypography.footnote, color = CouliColors.textPrimary)
                }
            }
        },
    )
}
