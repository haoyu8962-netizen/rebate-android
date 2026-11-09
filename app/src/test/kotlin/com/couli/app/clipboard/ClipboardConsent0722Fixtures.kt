package com.couli.app.clipboard

import android.content.Context
import com.couli.core.clipboard.ImportPreferenceStore

/** 回归夹具：enabled=null 保持未选择（默认开启），true / false 经真实入口建立；不替代 UI 行为测试。 */
internal fun regressionImportPreferences(
    context: Context,
    clock: () -> Long,
    enabled: Boolean? = true,
): ImportPreferenceStore {
    val store = ClipboardPreferences(context, SampleCipher).importPreferences(clock)
    if (enabled != null) {
        store.setOn()
        if (!enabled) store.setOff()
    }
    return store
}
