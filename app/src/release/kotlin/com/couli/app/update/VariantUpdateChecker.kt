package com.couli.app.update

import android.content.Context
import com.couli.core.update.PlaceholderUpdateChecker
import com.couli.core.update.SharedPreferencesUpdateStore
import com.couli.core.update.UpdateChecker
import com.couli.core.update.UpdateStore

/**
 * Release 的版本检查器：网络层（APP-03）之前永远失败，按 unknown 处理，不会误拦用户。没有调试假数据（release-scan 查标记）。
 * TODO(APP-03): 换成真实请求 GET /v1/app-versions/check。
 */
@Suppress("UnusedParameter")
internal fun variantUpdateChecker(context: Context): UpdateChecker = PlaceholderUpdateChecker

/** Release 的版本检查结论存储：couli.update（debug / staging 的假结果存在 couli.update_debug，这里不读，R1-03）。 */
internal fun variantUpdateStore(context: Context): UpdateStore = SharedPreferencesUpdateStore(context)
