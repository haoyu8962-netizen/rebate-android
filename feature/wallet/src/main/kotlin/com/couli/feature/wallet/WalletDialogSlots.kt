package com.couli.feature.wallet

/**
 * 两种弹窗的按钮槽位（APP-09v-c G-W16，纯函数、无副作用）：[Pair.first] 是主按钮（右、实心）的文案键，[Pair.second] 是次按钮（左、描边）的文案键。
 *
 * 回调跟着「动作」走，不跟着槽位走（[RiskBlockedDialog]、[AbandonDialog] 按本映射取文案，再把每个文案接到它原来的回调）：
 * - 44001：主【联系客服申诉】→ onAppeal；次【知道了】→ onOk；返回键 / 点遮罩 = 【知道了】。
 * - 放弃上一笔：主【再想想】→ onCancel；次【确定放弃】→ abandon(key, owner)，`.tapped(ABANDON_DIALOG)` 跟着它；返回键 / 点遮罩 = 【再想想】。
 */
internal fun riskDialogSlots(): Pair<String, String> = RISK_APPEAL_KEY to RISK_OK_KEY

internal fun abandonDialogSlots(): Pair<String, String> = ABANDON_CANCEL_KEY to ABANDON_OK_KEY

private const val RISK_APPEAL_KEY = "cs.appeal_entry"
private const val RISK_OK_KEY = "withdraw.risk_blocked.ok"
private const val ABANDON_CANCEL_KEY = "pending_confirm.abandon.cancel"
private const val ABANDON_OK_KEY = "pending_confirm.abandon.ok"
