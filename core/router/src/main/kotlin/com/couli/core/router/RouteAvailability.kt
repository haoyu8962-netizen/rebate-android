package com.couli.core.router

import com.couli.generated.Routes

/**
 * 路由在本端是否可用（APP-13a G01；APP-12 G31 的宫格裁剪同一口径）：路由表里有、since.android 非空且本端版本不低于它；或在 Debug / Staging 的
 * 「路由可用性覆盖」[overrides] 里（只用于 since=null 的路由冒烟、截图、集成测试；Release 恒为空）。Router 的版本判定与页面入口裁剪都按它，不写死。
 * 契约给路由填上 since 后不改代码自动生效。
 */
object RouteAvailability {
    fun available(
        name: String,
        appVersion: String,
        overrides: Set<String> = emptySet(),
    ): Boolean {
        val spec = Routes.spec(name)
        val since = spec?.sinceAndroid
        return spec != null && (name in overrides || (since != null && AppVersion.atLeast(appVersion, since)))
    }

    /** 本端可用的全部路由名（宫格、设置入口裁剪）。 */
    fun all(
        appVersion: String,
        overrides: Set<String> = emptySet(),
    ): Set<String> =
        Routes.all.keys
            .filter { available(it, appVersion, overrides) }
            .toSet()
}
