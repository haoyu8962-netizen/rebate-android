package com.couli.app.me

import com.couli.app.clipboard.ClipboardDraftTexts
import com.couli.app.login.LoginDraftTexts
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeTexts
import com.couli.generated.DefaultTexts

/**
 * 「我的」类页面的文案（BR-TEXT-12）：契约字典 → APP-12 草稿表（MeDraftTexts）→ 已有草稿表（LoginDraftTexts 的 settings.logout.*、cs.appeal_entry；
 * ClipboardDraftTexts 的 settings.clipboard_auto.*）。已有的键不在 MeDraftTexts 里重复建（brief §2.15）。
 */
internal object AppMeTexts : MeTexts {
    override fun text(key: String): String =
        when {
            MeDraftTexts.textOrNull(key) != null -> MeDraftTexts.text(key)
            else -> LoginDraftTexts.text(key).ifEmpty { ClipboardDraftTexts.text(key) }
        }

    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String =
        if (DefaultTexts.text(key) != null || MeDraftTexts.textOrNull(key) != null) {
            MeDraftTexts.format(key, vars)
        } else {
            LoginDraftTexts.format(key, vars)
        }
}
