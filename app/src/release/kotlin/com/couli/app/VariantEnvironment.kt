package com.couli.app

import android.content.Context
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.ProdEndpoints

/** Release 只有 prod：上次生效的若是别的环境（staging 覆盖安装为 release），先清理再记录 prod。 */
internal fun reconcileEnvironmentAtStartup(
    context: Context,
    cleaners: EnvironmentSwitchCleanerRegistry,
): EnvironmentStartupReconciler.Outcome = EnvironmentStartupReconciler(context, cleaners).reconcile(AppEnvironment.PROD)

/** 当前环境的接口基址：Release 只有 prod。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun currentApiBaseUrl(
    context: Context,
    cleaners: EnvironmentSwitchCleanerRegistry,
): String = ProdEndpoints.endpoints.apiBaseUrl

/** 流式（Agent）入口基址：prod 不设单独入口（null），ApiClient 全部按 api 基址发，与原行为一致（FU-X-STAGING-HOSTS）。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun currentStreamBaseUrl(
    context: Context,
    cleaners: EnvironmentSwitchCleanerRegistry,
): String? = ProdEndpoints.endpoints.streamBaseUrl

/** Release 没有 Debug 替身，不需要接环境闸门。 */
@Suppress("UnusedParameter")
internal fun variantBindEnvironmentGate(ready: () -> Boolean) = Unit
