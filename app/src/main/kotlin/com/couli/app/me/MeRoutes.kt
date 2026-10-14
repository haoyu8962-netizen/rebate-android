package com.couli.app.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.couli.app.routing.PlaceholderRoutePage
import com.couli.app.routing.RouteDestination
import com.couli.core.designsystem.CouliDialog
import com.couli.feature.me.AboutScreen
import com.couli.feature.me.AppealConfig
import com.couli.feature.me.AppealScreen
import com.couli.feature.me.CsSheetHost
import com.couli.feature.me.MeTabScreen
import com.couli.feature.me.PrivacyCenterConfig
import com.couli.feature.me.PrivacyCenterScreen
import com.couli.feature.me.RiskNoticeConfig
import com.couli.feature.me.RiskNoticeScreen
import com.couli.feature.me.SettingsConfig
import com.couli.feature.me.SettingsScreen

/** 本模块接管的路由名（Me 是 Tab 根，在 TabRoot 里渲染）。 */
val ME_ROUTES: Set<String> = setOf("Settings", "PrivacyCenter", "About", "RiskNotice", "Appeal")

/**
 * 「我的」类路由的页面（AppShell 注册）：设置、隐私中心、关于、封禁说明、申诉。每页登记出栈的同步出口（[AppMe.followBackStack]）。没有接线（只测外壳的单测）时是占位页。
 * [clipboardRow]：Android 设置页「通用」组的剪贴板开关一行（APP-07 原样挪进来）。
 */
fun meRoutePages(
    me: AppMe?,
    onBack: () -> Unit,
    clipboardRow: (@Composable () -> Unit)?,
    agentRow: (@Composable () -> Unit)? = null,
): Map<String, @Composable (RouteDestination) -> Unit> =
    ME_ROUTES.associateWith { _ ->
        { page: RouteDestination ->
            if (me == null) {
                PlaceholderRoutePage(page)
            } else {
                val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
                val bind: (() -> Unit) -> Unit = { leave -> me.bindLeave(entryId, leave) }
                when (page.name) {
                    "Settings" -> SettingsScreen(SettingsConfig(me.deps, entryId, onBack, clipboardRow, bind, agentRow))
                    "PrivacyCenter" -> PrivacyCenterScreen(PrivacyCenterConfig(me.deps, onBack, bind))
                    "About" -> AboutScreen(me.deps, onBack, bind)
                    "RiskNotice" -> RiskNoticeScreen(RiskNoticeConfig(me.deps, onBack, bind))
                    else -> AppealScreen(AppealConfig(me.deps, onBack, bind))
                }
            }
        }
    }

/** 「我的」Tab 根。 */
@Composable
fun MeTabRoute(me: AppMe) {
    MeTabScreen(me.deps, me.mask)
}

/** 外壳上的全局界面：统一客服出口的半屏、撤回同意落盘失败的提示（鸿蒙 R2-04：在当前可见界面明确提示并可重试）。 */
@Composable
fun MeOverlayHost(me: AppMe) {
    CsSheetHost(me.cs, me.deps.texts)
    val failed by me.withdrawFailed.collectAsStateWithLifecycle()
    val id = failed
    if (id != null) {
        val texts = me.deps.texts
        // 回调捕获这一次提示的编号（R1-02）：关闭或重试之后旧回调不再生效
        CouliDialog(
            title = texts.text("privacy_center.withdraw_failed"),
            confirmText = texts.text("me.retry"),
            onConfirm = { me.retryWithdrawCleanup(id) },
            dismissText = texts.text("me.close"),
            onDismissRequest = { me.dismissWithdrawFailed(id) },
        )
    }
}
