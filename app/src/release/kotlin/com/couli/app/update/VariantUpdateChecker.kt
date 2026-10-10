package com.couli.app.update

import android.content.Context
import com.couli.core.update.SharedPreferencesUpdateStore
import com.couli.core.update.UpdateChecker
import com.couli.core.update.UpdateStore

/**
 * Release 的版本检查器：原样用 App 组合的真实检查器（[com.couli.core.network.ApiUpdateChecker]，经 ApiClient 请求
 * GET /v1/app-versions/check，F-X-01）。没有调试假数据（release-scan 查标记）。
 */
@Suppress("UnusedParameter")
internal fun variantUpdateChecker(
    context: Context,
    real: UpdateChecker,
): UpdateChecker = real

/** Release 的版本检查结论存储：couli.update（debug / staging 的假结果存在 couli.update_debug，这里不读，R1-03）。 */
internal fun variantUpdateStore(context: Context): UpdateStore = SharedPreferencesUpdateStore(context)
