package com.couli.app.trade

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import com.couli.app.routing.AppRouting
import com.couli.app.search.AppSearch
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.feature.trade.AuthManageConfig
import com.couli.feature.trade.AuthManageScreen
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonObject

/**
 * AuthManage 路由（APP-06b）：授权管理页。执行器随回退栈条目（ViewModel）；页面一确定出栈就作废在途请求（与搜索、详情同一个回退栈出口）。
 * 【联系客服】统一客服出口未接入，经 Router 打开帮助中心（TODO(N-28)）。
 */
@Composable
fun AuthManageRoute(
    trade: AppTrade,
    search: AppSearch,
    onBack: () -> Unit,
) {
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    val config =
        remember(trade, entryId) {
            AuthManageConfig(
                controller = { scope -> trade.authManageController(scope) },
                texts = trade.texts,
                onBack = onBack,
                onContact = { trade.open(HELP_ROUTE, JsonObject(emptyMap())) },
                bindLeave = { leave -> search.bindPageLeave(entryId, leave) },
            )
        }
    AuthManageScreen(config)
}

private const val HELP_ROUTE = "Help"

/** 「我的」与设置里的授权管理入口（R1-14 测试与冒烟用）。 */
const val AUTH_MANAGE_ENTRY_TAG = "auth_manage_entry"

/** 授权管理的路由名（routes.json）。 */
internal const val AUTH_MANAGE_ROUTE_NAME = "AuthManage"

/**
 * 授权管理的正式入口（BR-ID-17 细则「授权管理页」，A-R1-14）：「我的」与设置里一行【授权管理】，点了经 Router 以 App 内来源打开 AuthManage
 * （登录、隐私守卫照常：游客先登录、基本模式先补同意）。只打开页面，页面本身不自动发起授权。
 */
@Composable
fun AuthManageEntry(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                .testTag(AUTH_MANAGE_ENTRY_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DefaultTexts.text(AUTH_MANAGE_TITLE_KEY).orEmpty(),
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(CouliIcons.chevron, contentDescription = null, tint = CouliColors.textSecondary)
    }
}

/** 经 Router 打开授权管理（App 内来源）。 */
fun openAuthManage(routing: AppRouting) {
    routing.router.open(RouteRequest(AUTH_MANAGE_ROUTE_NAME, JsonObject(emptyMap()), RouteSource.IN_APP))
}

private const val AUTH_MANAGE_TITLE_KEY = "auth_manage.title"
