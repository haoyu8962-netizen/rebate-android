package com.couli.app

import android.content.Context
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher

/** 构建默认环境：debug 为 test，staging 为 staging（BuildConfig.DEFAULT_ENVIRONMENT）。 */
internal fun buildDefaultEnvironment(): AppEnvironment = AppEnvironment.fromKey(BuildConfig.DEFAULT_ENVIRONMENT) ?: AppEnvironment.TEST

/** 本次要用的环境（显式选择，否则构建默认）与上次生效的环境不同则先清理再记录。 */
internal fun reconcileEnvironmentAtStartup(
    context: Context,
    cleaners: EnvironmentSwitchCleanerRegistry,
): EnvironmentStartupReconciler.Outcome = EnvironmentSwitcher(context, buildDefaultEnvironment(), cleaners).reconcileAtStartup()

/** 当前环境的接口基址（网络层 ApiClient 用；debug / staging 按显式选择或构建默认）。 */
internal fun currentApiBaseUrl(
    context: Context,
    cleaners: EnvironmentSwitchCleanerRegistry,
): String = EnvironmentSwitcher(context, buildDefaultEnvironment(), cleaners).endpoints.apiBaseUrl
