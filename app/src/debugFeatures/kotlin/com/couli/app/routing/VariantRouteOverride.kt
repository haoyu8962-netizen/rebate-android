package com.couli.app.routing

import android.content.Context
import com.couli.app.DebugMockGate
import java.io.File

/**
 * debug / staging：路由可用性覆盖（APP-13a G01，编排者补充 1）。DeleteAccount 在三端 since 都是 null，Release 里注销入口全部隐藏；这里给冒烟、设计稿截图与
 * 集成测试一个只在非 Release 编译的开关：状态文件 files/[ROUTE_OVERRIDE_FILE] 每行一个路由名（如 `DeleteAccount`），**并且**统一闸门放行（开关 + MOCK_DATA +
 * 环境就绪，F-X-02）时才生效。每次调用现读（不缓存）；读取出错按空集。release-scan 查 [ROUTE_OVERRIDE_MARKER]（Release 制品里不得出现）。
 */
internal fun variantRouteOverrides(context: Context): () -> Set<String> {
    val app = context.applicationContext
    val gate = DebugMockGate.of(app)
    return {
        runCatching {
            if (!gate()) return@runCatching emptySet()
            val file = File(DebugMockGate.stateDir(app), ROUTE_OVERRIDE_FILE)
            if (!file.isFile) {
                emptySet()
            } else {
                file
                    .readLines()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
        }.getOrDefault(emptySet())
    }
}

/** 覆盖开关的状态文件名，也是 release-scan 查的标记（Release 制品里不得出现）。 */
internal const val ROUTE_OVERRIDE_FILE = "COULI_ROUTE_OVERRIDE_APP13A"

/** release-scan 标记（与 [ROUTE_OVERRIDE_FILE] 同值，便于扫描与自测）。 */
internal const val ROUTE_OVERRIDE_MARKER = ROUTE_OVERRIDE_FILE
