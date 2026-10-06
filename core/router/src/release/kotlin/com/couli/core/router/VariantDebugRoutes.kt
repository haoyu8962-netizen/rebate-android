package com.couli.core.router

import com.couli.generated.RouteSpec

/** release：没有 debug_only 路由；命中这些名字按未知路由处理（03 §3.6、§4.4）。 */
internal object VariantDebugRoutes {
    val all: Map<String, RouteSpec> get() = emptyMap()
}
