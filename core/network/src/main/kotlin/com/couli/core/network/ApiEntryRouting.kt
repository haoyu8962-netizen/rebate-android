package com.couli.core.network

/**
 * 请求入口选择（FU-X-STAGING-HOSTS，D10-598 ①）：Agent 接口只由流式入口提供（平台 agent/stream/mount 只挂在 stream 入口），
 * 所以 pathTemplate 以 `/v1/agent/` 开头（区分大小写、精确前缀）的 operation 在流式入口基址非空时发往它，其余都走 api 入口。
 * 契约还没有按 operation 标注入口，先按路径前缀判断；刷新接口 `/v1/auth/refresh` 等非 agent 路径永远走 api。
 */
object ApiEntryRouting {
    private const val STREAM_ENTRY_PREFIX = "/v1/agent/"

    /** [pathTemplate] 是 operation 的路径模板（不是展开后的 URL）。 */
    fun usesStreamEntry(pathTemplate: String): Boolean = pathTemplate.startsWith(STREAM_ENTRY_PREFIX)
}
