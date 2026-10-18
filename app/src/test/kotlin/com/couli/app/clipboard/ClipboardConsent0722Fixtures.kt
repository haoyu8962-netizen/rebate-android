package com.couli.app.clipboard

import android.content.Context
import com.couli.core.clipboard.ImportConsentRecord
import com.couli.core.clipboard.ImportPreferenceStore
import org.junit.Assert.assertTrue

/** 旧回归配置显式下发 consent_version="1"，本机同意与该版本匹配；不依赖缺键兜底，不替代 UI 行为测试。 */
internal fun regressionImportPreferences(
    context: Context,
    clock: () -> Long,
    enabled: Boolean? = true,
): ImportPreferenceStore {
    val store = ClipboardPreferences(context, SampleCipher).importPreferences(clock)
    if (enabled != null) {
        store.enable("1")
        if (!enabled) store.setOff()
    }
    return store
}

/** G26：只检查撤回没有新增同意，允许原记录继续排队或由补报消费；重复记录也按数量核对。 */
internal fun assertNoNewImportConsents(
    before: List<ImportConsentRecord>,
    after: List<ImportConsentRecord>,
) {
    val unmatched = before.toMutableList()
    val noNewRecords = after.all { unmatched.remove(it) }
    assertTrue("撤回不能新增已同意记录", noNewRecords)
}
