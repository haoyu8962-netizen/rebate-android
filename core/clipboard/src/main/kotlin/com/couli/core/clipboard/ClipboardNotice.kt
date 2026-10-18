package com.couli.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.couli.core.designsystem.CouliDialog

/**
 * 首次用途说明弹层（BR-ID-16 细则「首次用途说明弹层」；TEXT14 表 D clipboard.consent.*）：居中弹窗（设计系统 [CouliDialog]，
 * 系统 Dialog 窗口），主按钮【开启】→ [onEnable]，次按钮【暂不开启】→ [onNotNow]；返回键与点外部关闭等同【暂不开启】（G27）。
 * 受控展示：是否显示由宿主决定；文案经 [text] 按键取，本模块不写中文。弹层只遮住当前页面，不拦截系统分享的导入流程（分享在后台照常暂存）。
 */
@Composable
fun ClipboardNotice(
    text: (String) -> String,
    onEnable: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CouliDialog(
        title = text(KEY_TITLE),
        message = text(KEY_BODY),
        confirmText = text(KEY_ENABLE),
        onConfirm = onEnable,
        dismissText = text(KEY_NOT_NOW),
        onDismiss = onNotNow,
        onDismissRequest = onNotNow,
        confirmTag = TAG_ENABLE,
        dismissTag = TAG_NOT_NOW,
        modifier = modifier.testTag(TAG_ROOT),
    )
}

private const val KEY_TITLE = "clipboard.consent.title"
private const val KEY_BODY = "clipboard.consent.body"
private const val KEY_ENABLE = "clipboard.consent.enable"
private const val KEY_NOT_NOW = "clipboard.consent.not_now"

// 测试标记（与结果轻提示的 clipboard_notice 区分）
private const val TAG_ROOT = "clipboard_consent_notice"
private const val TAG_ENABLE = "clipboard_consent_enable"
private const val TAG_NOT_NOW = "clipboard_consent_not_now"
