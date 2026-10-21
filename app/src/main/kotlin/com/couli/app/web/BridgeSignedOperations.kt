package com.couli.app.web

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations

/**
 * net.signedRequest 代发的接口查找（signed_paths 已在协议层核对）：按 method + path（去掉查询串）找 openapi 里 x-signed 的 operation。
 * 契约即白名单（CT-LOCK-02 编排裁定）：signed_paths 里的每一项只要 openapi 有对应签名接口就代发，原生侧不另设拦截表；
 * 服务端 bridge_disabled_methods 紧急关闭三端尚未接入（F-X-24）。
 * 找不到时按「本端不支持」结束、不发请求（AppWeb.Services.signedRequest）。
 */
internal object BridgeSignedOperations {
    /** 契约里 method + path 对应的签名 operation；没有为 null（不发请求）。 */
    fun operationFor(
        method: String,
        path: String,
    ): ApiOperation? {
        val bare = path.substringBefore('?')
        return ApiOperations.all.values.firstOrNull { it.method == method && it.pathTemplate == bare && it.signed }
    }
}
