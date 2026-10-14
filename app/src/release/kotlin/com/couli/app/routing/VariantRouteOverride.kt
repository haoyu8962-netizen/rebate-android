package com.couli.app.routing

import android.content.Context

/** Release：没有路由可用性覆盖（APP-13a G01）：DeleteAccount since=null 时注销入口全部隐藏、10007 只提示。TODO(N-37) 契约给 since 后自动生效。 */
@Suppress("UnusedParameter")
internal fun variantRouteOverrides(context: Context): () -> Set<String> = { emptySet() }
