package com.couli.core.router

import com.couli.generated.DebugRoutes
import com.couli.generated.RouteSpec

/** debug / staging：debug_only 路由（src/debugFeatures，release 里没有这个文件；03 §3.6）。 */
internal object VariantDebugRoutes {
    val all: Map<String, RouteSpec> get() = DebugRoutes.all
}
