package com.couli.core.clipboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonPair
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliTypography

/**
 * 首次用途说明弹层（BR-ID-16 细则「首次用途说明弹层」；TEXT14 表 D clipboard.consent.*）：底部半屏（HomeClipboardConsent-v3，APP-11v A16；
 * 设计系统 [CouliSheet]，M3 ModalBottomSheet 系统窗口）——把手、标题 22/600 左对齐、正文 14、底部两按钮并排等宽（左【暂不开启】描边次按钮 → [onNotNow]，
 * 右【开启】主按钮 → [onEnable]；大字号放不下时纵排、主按钮在上）。无右上关闭（画板如此）；返回键、点遮罩、下滑关闭都等同【暂不开启】（G27）。
 * 受控展示：是否显示由宿主决定；文案经 [text] 按键取，本模块不写中文。弹层只遮住当前页面，不拦截系统分享的导入流程（分享在后台照常暂存）。
 */
@Composable
fun ClipboardNotice(
    text: (String) -> String,
    onEnable: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CouliSheet(
        title = text(KEY_TITLE),
        onDismissRequest = onNotNow,
        titleStyle = CouliTypography.title,
        modifier = modifier.testTag(TAG_ROOT),
        actions = {
            CouliButtonPair(
                primary = {
                    CouliButton(
                        text = text(KEY_ENABLE),
                        onClick = onEnable,
                        modifier = Modifier.fillMaxWidth().testTag(TAG_ENABLE),
                    )
                },
                secondary = {
                    CouliButton(
                        text = text(KEY_NOT_NOW),
                        onClick = onNotNow,
                        variant = CouliButtonVariant.SECONDARY,
                        modifier = Modifier.fillMaxWidth().testTag(TAG_NOT_NOW),
                    )
                },
            )
        },
    ) {
        Text(text = text(KEY_BODY), style = CouliTypography.footnote, color = CouliColors.textPrimary)
    }
}

private const val KEY_TITLE = "clipboard.consent.title"
private const val KEY_BODY = "clipboard.consent.body"
private const val KEY_ENABLE = "clipboard.consent.enable"
private const val KEY_NOT_NOW = "clipboard.consent.not_now"

// 测试标记（与结果轻提示的 clipboard_notice 区分）
private const val TAG_ROOT = "clipboard_consent_notice"
private const val TAG_ENABLE = "clipboard_consent_enable"
private const val TAG_NOT_NOW = "clipboard_consent_not_now"
