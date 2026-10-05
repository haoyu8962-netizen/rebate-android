package com.couli.app.debug

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.couli.app.buildDefaultEnvironment
import com.couli.core.config.AppEnvironment
import com.couli.core.config.DebugCapability
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTypography
import com.couli.generated.DebugRoutes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 调试面板的固定标记：tools/ci/release-scan.sh 在 release 制品里查它，命中即失败。 */
const val COULI_DEBUG_PANEL = "COULI_DEBUG_PANEL"

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugPanelEntryPoint {
    fun environmentSwitchCleaners(): EnvironmentSwitchCleanerRegistry
}

/** 环境切换器：debug 默认 test，staging 默认 staging（BuildConfig.DEFAULT_ENVIRONMENT）。 */
@Composable
internal fun rememberEnvironmentSwitcher(context: Context): EnvironmentSwitcher =
    remember(context) {
        val appContext = context.applicationContext
        val cleaners =
            EntryPointAccessors
                .fromApplication(appContext, DebugPanelEntryPoint::class.java)
                .environmentSwitchCleaners()
        EnvironmentSwitcher(
            context = appContext,
            defaultEnvironment = buildDefaultEnvironment(),
            cleaners = cleaners,
        )
    }

/**
 * 最小调试面板：显示当前环境、切换环境（切换前先清本机会话与缓存，03 §3.6）。
 * 当前环境是 prod 时只保留切换服务器地址，其余调试能力不显示。
 */
@Composable
internal fun DebugPanel(
    switcher: EnvironmentSwitcher,
    current: AppEnvironment,
    onSwitched: (AppEnvironment) -> Unit,
) {
    Column {
        Text(text = "Current: ${current.key}", style = CouliTypography.footnote, color = CouliColors.textSecondary)
        if (switcher.capabilities.isAvailable(DebugCapability.SWITCH_SERVER)) {
            AppEnvironment.entries.forEach { environment ->
                TextButton(
                    onClick = {
                        // 清理失败时不切换（环境保持不变），只记日志。
                        runCatching { switcher.switchTo(environment) }
                            .onFailure { Log.e("DebugPanel", "environment switch failed", it) }
                        onSwitched(switcher.current)
                    },
                    enabled = environment != current,
                ) {
                    Text(text = "Switch to ${environment.key}")
                }
            }
        }
        val others = DebugCapability.entries.filter { it != DebugCapability.SWITCH_SERVER }
        val available = others.filter { switcher.capabilities.isAvailable(it) }
        Text(
            text = "Other debug capabilities: " + if (available.isEmpty()) "off (prod)" else available.joinToString(),
            style = CouliTypography.caption,
            color = CouliColors.textSecondary,
        )
        // DebugRoutes.MARKER 作测试标记：引用它让 debug / staging 制品里一定带着这个长标记，
        // tools/ci/release-scan.sh --self-test 据此证明扫描能命中，release 里查它必须不命中。
        Text(
            text = "Debug routes: " + DebugRoutes.all.keys.joinToString(),
            style = CouliTypography.caption,
            color = CouliColors.textSecondary,
            modifier = Modifier.testTag(DebugRoutes.MARKER),
        )
    }
}
