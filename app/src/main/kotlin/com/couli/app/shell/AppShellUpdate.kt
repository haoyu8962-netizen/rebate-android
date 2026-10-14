package com.couli.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.hasForceUpdatePage
import com.couli.app.routing.navigateToForceUpdate
import com.couli.app.update.AppUpdate
import com.couli.app.update.ForceUpdateActions
import com.couli.app.update.ForceUpdatePage
import com.couli.app.update.UpdatePromptDialog
import com.couli.app.update.UpdateUi
import com.couli.core.router.GuardState
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateState
import com.couli.core.update.UpdateStatus
import kotlinx.serialization.json.JsonObject

// 外壳的强更部分（APP-02c；03 §4.1）：强更页的进出、次要入口、可关闭更新提示。

/**
 * 强更状态下返回栈里的页（APP-02c）：强更页本身只在 below_min 时显示；强更页之上的页只可能是从强更页进入的次要入口
 * （进入强更页时已清掉起点之上的页面），按 fromForceUpdatePage 判定（03 §4.1：只放行 PrivacyCenter、Agreement、Login、
 * DeleteAccount）。其余同 [stillAllowed]。
 */
internal fun stillAllowedWithUpdate(
    page: RouteDestination,
    state: GuardState,
): Boolean {
    val belowMin = state.updateState == UpdateState.BELOW_MIN
    if (page.name == RouteNames.FORCE_UPDATE) return belowMin
    return stillAllowed(page, if (belowMin) state.copy(fromForceUpdatePage = true) else state)
}

/** 强更结论变化时：进入 below_min → 进强更页（已在强更页或它上面的次要入口时不动）；解除 → 清掉强更页回到起点。 */
internal fun syncForceUpdatePage(
    navController: NavHostController,
    status: UpdateStatus?,
) {
    val onForcePage = hasForceUpdatePage(navController)
    if (status == UpdateStatus.BELOW_MIN && !onForcePage) {
        navigateToForceUpdate(navController)
    } else if (status != null && status != UpdateStatus.BELOW_MIN && onForcePage) {
        navController.popBackStack(HomeDestination, inclusive = false)
    }
}

/**
 * 清栈回到外壳起点（进入基本模式、撤回同意，R1-01）：below_min 时立即重新推入强更页——基本模式保留已知强更（03 §4.4），
 * 不能因为清栈而停在基本模式首页。
 */
internal fun resetToStart(
    navController: NavHostController,
    status: UpdateStatus?,
) {
    navController.popBackStack(HomeDestination, inclusive = false)
    if (status == UpdateStatus.BELOW_MIN) navigateToForceUpdate(navController)
}

/** below_recommended 且该弹（[UpdateUi.state] 的 showPrompt）时弹可关闭更新提示；[hidden] 时让出屏幕。 */
@Composable
internal fun UpdatePromptHost(
    update: AppUpdate,
    ui: UpdateUi?,
    hidden: Boolean,
) {
    if (ui == null || hidden || !ui.state.showPrompt) return
    UpdatePromptDialog(ui = ui, onGoStore = update::goToStore, onLater = update::dismissPrompt)
}

/** 强更页：结论来自 [AppUpdate]；次要入口经 Router 以「从强更页进入」打开（03 §4.1）。 */
@Composable
internal fun ForceUpdateRoute(
    update: AppUpdate,
    routing: AppRouting,
    account: com.couli.app.account.AppAccount? = null,
) {
    val ui by update.ui.collectAsStateWithLifecycle()
    // APP-13a G28：已登录（含 deletion_only）时按 getDeletion 决定标签；DeleteAccount 不可用时不显示（G01 ②）。没接账号模块（更早任务的单测）时沿用「注销账号」
    val noAccount = androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow<String?>("app_update.delete_account") }
    val label by (account?.forceUpdateLabel ?: noAccount).collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(account) { account?.refreshForceUpdateLabel() }
    ForceUpdatePage(
        deleteLabelKey = label,
        ui = ui,
        actions =
            ForceUpdateActions(
                onGoStore = update::goToStore,
                onRetry = { update.recheck(thenPickStore = true) },
                // 03 §4.1：强更页「隐私政策」进 PrivacyCenter（承载协议、两份清单与撤回同意；本轮占位页，APP-12 实现，R1-05）
                onPrivacyPolicy = { openFromForceUpdatePage(routing, inAppRequest(PRIVACY_CENTER_ROUTE)) },
                onDeleteAccount = {
                    // 一律经 Router 打开 DeleteAccount（游客由守卫先进受限登录，登录后打开一次；APP-13a §2 第 8 项）
                    account?.onForceUpdateDeletion() ?: openFromForceUpdatePage(routing, deleteAccountRequest(routing))
                },
            ),
    )
}

private fun openFromForceUpdatePage(
    routing: AppRouting,
    request: RouteRequest,
) = routing.router.open(request, routing.guardState().copy(fromForceUpdatePage = true))

/**
 * 没接账号模块（只测外壳与更早任务的单测）时强更页【注销账号】的旧写法：游客先进登录页；已登录进 DeleteAccount。接了 [com.couli.app.account.AppAccount] 时
 * 一律经 Router 打开 DeleteAccount（APP-13a）。
 */
private fun deleteAccountRequest(routing: AppRouting): RouteRequest {
    val route = if (routing.guardState().sessionState == SessionState.GUEST) RouteNames.LOGIN else DELETE_ACCOUNT_ROUTE
    return RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)
}

private const val DELETE_ACCOUNT_ROUTE = "DeleteAccount"
