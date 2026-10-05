package com.couli.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.couli.app.debug.COULI_DEBUG_PANEL
import com.couli.app.debug.DebugPanel
import com.couli.app.debug.rememberEnvironmentSwitcher
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.generated.DesignTokens

/**
 * debug / staging 首屏内容（src/debugFeatures，release 里没有）：令牌版本、构建类型、当前环境与调试面板入口。
 * 这些是开发字样，用英文；入口以后由 AppShell 任务挪走（TODO(APP-02)）。
 */
@Composable
internal fun VariantRootContent() {
    val switcher = rememberEnvironmentSwitcher(LocalContext.current)
    var environment by remember { mutableStateOf(switcher.current) }
    var panelOpen by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = "Design tokens ${DesignTokens.version}", style = CouliTypography.title, color = CouliColors.textPrimary)
        Text(text = "Build type: ${BuildConfig.BUILD_KIND}", style = CouliTypography.body, color = CouliColors.textPrimary)
        Text(text = "Environment: ${environment.key}", style = CouliTypography.body, color = CouliColors.textSecondary)
        TextButton(
            onClick = { panelOpen = !panelOpen },
            modifier = Modifier.testTag(COULI_DEBUG_PANEL),
        ) {
            Text(text = if (panelOpen) "Close debug panel" else "Debug panel")
        }
        if (panelOpen) {
            DebugPanel(
                switcher = switcher,
                current = environment,
                onSwitched = { environment = it },
            )
        }
    }
}
