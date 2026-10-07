package com.couli.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.couli.app.catalog.CATALOG_EXTRA
import com.couli.app.catalog.CatalogPage
import com.couli.app.catalog.ComponentCatalog
import com.couli.app.debug.COULI_DEBUG_PANEL
import com.couli.app.debug.DebugPanel
import com.couli.app.debug.rememberEnvironmentSwitcher
import com.couli.app.routing.RoutingTrace
import com.couli.app.update.DebugUpdateSection
import com.couli.app.web.BRIDGE_CONFORMANCE_MARKER
import com.couli.app.web.CONFORMANCE_EXTRA
import com.couli.app.web.CONFORMANCE_SOURCE
import com.couli.app.web.DebugConformancePage
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.generated.DesignTokens

/**
 * debug / staging 首屏内容（src/debugFeatures，release 里没有）：令牌版本、构建类型、当前环境与调试面板入口。
 * 这些是开发字样，用英文；入口以后由 AppShell 任务挪走（TODO(APP-02)）。
 */
@Composable
internal fun VariantRootContent(trace: RoutingTrace? = null) {
    val switcher = rememberEnvironmentSwitcher(LocalContext.current)
    var environment by remember { mutableStateOf(switcher.current) }
    var panelOpen by remember { mutableStateOf(false) }
    // 组件目录（DS-01）：从调试面板进入；adb 可用 intent extra couli.catalog=<页面 key> 直接打开（截图用）
    val context = LocalContext.current
    val requested = remember(context) { CatalogPage.fromKey(context.findActivity()?.intent?.getStringExtra(CATALOG_EXTRA)) }
    var catalog by rememberSaveable { mutableStateOf(requested) }
    catalog?.let { page ->
        ComponentCatalog(initial = page, onClose = { catalog = null })
        return
    }
    // 桥一致性页（APP-04，03 §5.5）：从调试面板进入；adb 可用字符串 extra couli.conformance（任意值）直接打开
    val conformanceMode = remember(context) { context.findActivity()?.intent?.getStringExtra(CONFORMANCE_EXTRA) }
    // 本机测试源只在 debug（staging 为 null：不显示入口，extra 也不生效）
    val conformanceSource = CONFORMANCE_SOURCE
    var conformance by rememberSaveable { mutableStateOf(conformanceMode != null && conformanceSource != null) }
    if (conformance && conformanceSource != null) {
        DebugConformancePage(source = conformanceSource, mode = conformanceMode, onClose = { conformance = false })
        return
    }

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
        // 最近一次入站请求的守卫结论（APP-02a 冒烟截图用）
        trace?.let {
            Text(
                text = "Last route: ${it.source} ${it.route ?: "-"} -> ${it.kind} (${it.reason})",
                style = CouliTypography.body,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(ROUTE_TRACE_TAG),
            )
        }
        TextButton(
            onClick = { panelOpen = !panelOpen },
            modifier = Modifier.testTag(COULI_DEBUG_PANEL),
        ) {
            Text(text = if (panelOpen) "Close debug panel" else "Debug panel")
        }
        if (panelOpen) {
            TextButton(onClick = { catalog = CatalogPage.INDEX }) {
                Text(text = "Component catalog")
            }
            conformanceSource?.let { source ->
                TextButton(onClick = { conformance = true }, modifier = Modifier.testTag(BRIDGE_CONFORMANCE_MARKER)) {
                    Text(text = "Bridge conformance (${source.url})")
                }
            }
            DebugPanel(
                switcher = switcher,
                current = environment,
                onSwitched = { environment = it },
            )
            // 版本检查假数据（APP-02c）：正常 / 建议更新 / 强制更新 / 商店都不可用 / 请求失败
            DebugUpdateSection()
        }
    }
}

/** 调试首屏上「最近一次路由结论」那一行。 */
internal const val ROUTE_TRACE_TAG = "route_trace"

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
