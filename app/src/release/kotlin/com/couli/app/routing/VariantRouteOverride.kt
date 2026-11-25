package com.couli.app.routing

import android.content.Context

/**
 * Release：没有路由可用性覆盖（APP-13a G01）：契约 since.android 已是 1.0.0，DeleteAccount 由 AppRouting 的本地发布闸关门（FU-AND-DELETION-ENTRY-GATE），
 * 注销入口全部隐藏、10007 只提示；开门归 APP-13a-OPEN-android（改闸，不在这里加覆盖）。
 */
@Suppress("UnusedParameter")
internal fun variantRouteOverrides(context: Context): () -> Set<String> = { emptySet() }
