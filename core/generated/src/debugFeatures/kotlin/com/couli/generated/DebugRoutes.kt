// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** debug_only 路由；本文件只在 debug、staging 变体里编译，release 制品里没有（03 §3.6）。 */
object DebugRoutes {
    /**
     * 足够长的唯一标记：tools/ci/release-scan.sh 在 release 制品里查它，命中即失败；
     * 路由名可能很短、容易与其他字符串误撞，这个标记不会。由 app 的调试面板引用，不会被裁掉。
     */
    const val MARKER: kotlin.String = "COULI_DEBUG_ROUTES_MARKER_8d1f4c2a9e7b"

    val all: kotlin.collections.Map<kotlin.String, RouteSpec> =
        linkedMapOf(
            "HomePreview" to
                RouteSpec(
                    name = "HomePreview",
                    kind = RouteKind.NATIVE,
                    auth = "none",
                    sinceAndroid = "1.0.0",
                    entries = listOf(RouteEntry.IN_APP, RouteEntry.DEEPLINK),
                    h5Path = null,
                    debugOnly = true,
                    agentGuide = false,
                    agentGuideAccount = null,
                    params =
                        listOf(
                            RouteParamSpec(
                                name = "page_key",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                            RouteParamSpec(
                                name = "token",
                                type = RouteParamType.STRING,
                                required = true,
                                enumValues = null,
                                minLength = 1,
                                maxLength = null,
                                pattern = null,
                            ),
                        ),
                ),
        )
}
