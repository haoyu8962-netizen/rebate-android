package com.couli.app.routing

import android.content.Intent

/**
 * 外部启动 Intent 的清洗（R1-01，S0）：Navigation Compose 在 NavHost 设置导航图时会自动读取 Activity 的 Intent——
 * 框架导航 extras（`android-support-nav:controller:deepLinkIds`、`deepLinkExtras`、`deepLinkArgs`、`deepLinkIntent` 等）
 * 与 data 里的 URI——并直接导航到对应页面，绕过 Router 与统一入口守卫。
 *
 * 所以 Activity 先把原始 data 交给 [AppRouting]（深链解析 → 排队 → EntryGuard → Router），再把自己的 Intent 换成
 * [sanitize] 的结果：不带 data、不带任何框架导航 extras，只保留 [allowedStringExtras] 列出的字符串 extra（debug 的组件目录入口；
 * release 为空）。[AppNavHost] 在创建导航图之前也做一次（幂等），任何宿主 Activity 都不会把原始 Intent 交给 Navigation。
 */
object LaunchIntentSanitizer {
    /** Navigation 读取的 extras 键前缀（NavController.KEY_DEEP_LINK_*）。 */
    const val NAVIGATION_EXTRA_PREFIX = "android-support-nav:"

    fun sanitize(
        intent: Intent?,
        allowedStringExtras: Set<String>,
    ): Intent {
        val clean = Intent(intent?.action)
        intent?.component?.let { clean.component = it }
        intent?.categories?.forEach(clean::addCategory)
        allowedStringExtras
            .filterNot { it.startsWith(NAVIGATION_EXTRA_PREFIX) }
            .forEach { key -> readString(intent, key)?.let { clean.putExtra(key, it) } }
        return clean
    }

    /** 外部 Intent 的 extras 可能是畸形 Parcel：读取失败按没有处理。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readString(
        intent: Intent?,
        key: String,
    ): String? =
        try {
            intent?.getStringExtra(key)
        } catch (failure: RuntimeException) {
            null
        }
}
