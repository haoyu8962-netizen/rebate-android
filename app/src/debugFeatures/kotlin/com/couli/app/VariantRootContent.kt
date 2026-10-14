package com.couli.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.couli.app.debug.DebugPanelEntryPoint
import com.couli.app.debug.rememberEnvironmentSwitcher
import com.couli.app.routing.RoutingTrace
import com.couli.app.update.DebugUpdateSection
import com.couli.app.web.BRIDGE_CONFORMANCE_MARKER
import com.couli.app.web.CONFORMANCE_EXTRA
import com.couli.app.web.CONFORMANCE_SOURCE
import com.couli.app.web.ConformanceSource
import com.couli.app.web.DebugConformancePage
import com.couli.core.config.DebugCapabilities
import com.couli.core.config.DebugCapability
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.generated.DesignTokens
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.json.JsonObject

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
    // 启动参数在 prod 不生效（F-X-02 ③）：一致性页以「假定已登录」身份跑、注入示例规则，属于 BRIDGE_CONFORMANCE 调试能力，prod 关闭
    var conformance by rememberSaveable {
        mutableStateOf(conformanceLaunchAllowed(conformanceMode, conformanceSource, switcher.capabilities))
    }
    if (conformance && conformanceSource != null) {
        DebugConformancePage(source = conformanceSource, mode = conformanceMode, onClose = { conformance = false })
        return
    }

    // 剪贴板样例（APP-07 设计稿截图）：adb 用 extra couli.clip 把样例文字写进系统剪贴板，模拟别的 App 复制（不经 App 的写入口、不记 hash）；
    // 写一次后移除 extra。执行时过统一闸门（F-X-02 ③）：prod / 切换中丢弃，不写剪贴板
    LaunchedEffect(context) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        val sample = activity.intent?.getStringExtra(DEBUG_CLIP_EXTRA) ?: return@LaunchedEffect
        activity.intent?.removeExtra(DEBUG_CLIP_EXTRA)
        applyDebugClipSample(sample, DebugMockGate.of(context)) { text ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("debug-sample", text))
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
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
            // 设置页入口（APP-05a：「我的」还是占位，退出登录从这里进设置页；经 Router 以 App 内来源打开）
            TextButton(onClick = {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, DebugPanelEntryPoint::class.java)
                    .appRouting()
                    .router
                    .open(RouteRequest("Settings", JsonObject(emptyMap()), RouteSource.IN_APP))
            }) {
                Text(text = "Open Settings")
            }
            // 授权管理（APP-06b）：「我的」还是占位，从这里经 Router 以 App 内来源打开（需要登录，守卫负责）
            TextButton(onClick = {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, DebugPanelEntryPoint::class.java)
                    .appRouting()
                    .router
                    .open(RouteRequest("AuthManage", JsonObject(emptyMap()), RouteSource.IN_APP))
            }) {
                Text(text = "Open AuthManage")
            }
            // LoginSessionExpired：与网络层刷新失败清会话（clear_session_login）同一入口
            TextButton(onClick = {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, DebugPanelEntryPoint::class.java)
                    .appLogin()
                    .onNetworkEffect(
                        com.couli.core.network.ErrorEffect(
                            code = SESSION_EXPIRED_CODE,
                            kind = com.couli.core.network.ErrorEffectKind.CLEAR_SESSION_LOGIN,
                            replay = com.couli.core.network.ReplayMode.NONE,
                            registerPending = false,
                            route = "Login",
                        ),
                    )
            }) {
                Text(text = "Login: session expired")
            }
            // 本机假登录服务（APP-05a）：开关文件在应用私有目录，打开后发码、登录、登出都在本机回答
            // 开关本身（显示与切换）是唯一直接读开关文件的地方；替身是否交假数据另看统一闸门（F-X-02：prod 下开关在也不生效）
            var fakeOn by remember { mutableStateOf(panelSwitchShown(context)) }
            TextButton(onClick = { fakeOn = panelToggleSwitch(context, !fakeOn) }) {
                Text(
                    text =
                        "Fake login server: " + (if (fakeOn) "on" else "off") + if (DebugMockGate.of(context)()) "" else " (mock blocked)",
                )
            }
            TextButton(onClick = { catalog = CatalogPage.INDEX }) {
                Text(text = "Component catalog")
            }
            conformanceSource?.takeIf { switcher.capabilities.isAvailable(DebugCapability.BRIDGE_CONFORMANCE) }?.let { source ->
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

private const val SESSION_EXPIRED_CODE = 10404

/** 调试首屏上「最近一次路由结论」那一行。 */
internal const val ROUTE_TRACE_TAG = "route_trace"

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** 调试面板的开关按钮：显示开关文件本身的状态（不代表替身生效，生效看统一闸门）。 */
@OptIn(RawFakeServerSwitch::class)
private fun panelSwitchShown(context: Context): Boolean = DebugMockGate.switchOn(context)

/** 调试面板的开关按钮：打开 / 关闭开关文件。 */
@OptIn(RawFakeServerSwitch::class)
private fun panelToggleSwitch(
    context: Context,
    on: Boolean,
): Boolean = DebugMockGate.setSwitch(context, on)

/** 剪贴板样例的启动 extra（APP-07 截图，debug / staging）。 */
internal const val DEBUG_CLIP_EXTRA = "couli.clip"

/** 剪贴板样例启动参数（F-X-02 ③）：执行这一刻统一闸门放行才写，否则丢弃。返回是否写了。 */
internal fun applyDebugClipSample(
    sample: String,
    gate: () -> Boolean,
    write: (String) -> Unit,
): Boolean {
    if (!runCatching(gate).getOrDefault(false)) return false
    write(sample)
    return true
}

/** 一致性页启动参数（F-X-02 ③）：有本机源、带了 extra，且当前环境允许 BRIDGE_CONFORMANCE（prod 不允许）才直接打开。 */
internal fun conformanceLaunchAllowed(
    mode: String?,
    source: ConformanceSource?,
    capabilities: DebugCapabilities,
): Boolean = mode != null && source != null && capabilities.isAvailable(DebugCapability.BRIDGE_CONFORMANCE)
