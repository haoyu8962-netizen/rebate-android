package com.couli.core.network

import com.couli.core.clientcore.ConfigReply
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject

/**
 * ConfigStore 的拉取器（APP-03b，03 §4.3）：在后台线程调 `GET /v1/config`（getConfig，基本模式白名单内），把 [ApiResult] 换成
 * [ConfigReply]：code=0 且 data 是对象 → ok（带响应头 ETag）；304 → not_modified；其余（业务错误、本地拒绝、网络错误、
 * 会话读不出 session_unreadable 等）→ failure，ConfigStore 继续用 LKG / 包内配置。
 * TODO(N-21): getConfig 在契约里还没有声明 If-None-Match / ETag / 304（rule-gaps 第 3 条）。
 */
class ApiConfigFetcher(
    private val client: () -> ApiClient,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "couli-config-fetch").apply { isDaemon = true } },
) {
    /** 与 ConfigStore 的 fetcher 参数同形：第一个参数是实际发送的 If-None-Match。 */
    fun fetch(
        ifNoneMatch: String?,
        completion: (ConfigReply) -> Unit,
    ) {
        executor.execute { completion(fetchNow(ifNoneMatch)) }
    }

    /** 阻塞拉取一次（不在主线程调用）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 任何异常都按拉取失败（继续用 LKG）
    fun fetchNow(ifNoneMatch: String?): ConfigReply =
        try {
            when (val result = client().execute(ApiCall(GET_CONFIG, ifNoneMatch = ifNoneMatch))) {
                is ApiResult.Success -> {
                    (result.data as? JsonObject)?.let { ConfigReply(ConfigReply.OK, it.toString(), result.etag) } ?: failure()
                }

                is ApiResult.NotModified -> {
                    ConfigReply(ConfigReply.NOT_MODIFIED, null, result.etag)
                }

                else -> {
                    failure()
                }
            }
        } catch (error: Exception) {
            failure()
        }

    private fun failure() = ConfigReply(ConfigReply.FAILURE, null, null)

    private companion object {
        const val GET_CONFIG = "getConfig"
    }
}
