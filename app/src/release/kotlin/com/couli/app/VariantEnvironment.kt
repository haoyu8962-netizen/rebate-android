package com.couli.app

import android.content.Context
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchCleanerRegistry

/** Release 只有 prod：上次生效的若是别的环境（staging 覆盖安装为 release），先清理再记录 prod。 */
internal fun reconcileEnvironmentAtStartup(
    context: Context,
    cleaners: EnvironmentSwitchCleanerRegistry,
): EnvironmentStartupReconciler.Outcome = EnvironmentStartupReconciler(context, cleaners).reconcile(AppEnvironment.PROD)
