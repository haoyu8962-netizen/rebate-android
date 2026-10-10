package com.couli.core.clipboard

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次 parse 的结局：[action] 交页面执行；[dropped] = 归属已失效或本地拒绝（迟到、作废），页面什么都不做。 */
data class ParseOutcome(
    val action: ParseAction?,
    val dropped: Boolean = false,
)

/**
 * POST /v1/inputs/parse（04 §8.5；brief §2.6）：scene=clipboard（SearchTyping 的粘贴入口同样用 clipboard）。请求绑定发起页与世代
 * （[CallOwnership]：隐私、环境世代 + 调用方给的 stillOwned）；parse 没有幂等键，不做自动重试（网络层只对 GET 重试）。
 * 请求体只含本地抽出的片段（[uploadText]，G04），不含原文其余部分。
 */
class ParseInputService(
    private val api: PageApi,
) {
    /**
     * [title]：请求前从原文本地抽出的「」标题片段（[ClipboardText.quotedTitle]），随这次请求绑定；整体 30132（data=null）时用它预填
     * 【用商品名搜索】，没有就留空（APP-07 共性第 30 条，BR-TEXT-14）。不依赖契约外的 data.raw，也不从上传片段里猜（标题本身可能以
     * http 开头）。原文不保存。
     */
    suspend fun parse(
        uploadText: String,
        owner: CallOwnership,
        title: String? = null,
    ): ParseOutcome {
        val body =
            buildJsonObject {
                put("text", uploadText.take(MAX_TEXT))
                put("scene", SCENE_CLIPBOARD)
            }.toString()
        val result = api.send(ApiCall(OPERATION, body = body, ownership = owner))
        if (!owner.stillOwned()) return ParseOutcome(null, dropped = true)
        return when (result) {
            is ApiResult.Success -> {
                val response =
                    buildJsonObject {
                        put("code", 0)
                        result.data?.let { put("data", it) }
                        result.traceId?.let { put("trace_id", it) }
                    }
                ParseOutcome(ParseResultAction.map(response, errorCode = null, retryAfter = null, traceId = result.traceId))
            }

            is ApiResult.Failure -> {
                val mapped = ParseResultAction.map(null, result.code, retryAfter = null, traceId = result.traceId)
                // 42901 的时长取网络层已解析的 Retry-After（缺省 5 秒）；整体 30132 用请求前本地抽出的「」标题预填（BR-TEXT-14）
                val action =
                    when (mapped.actionRaw) {
                        ParseResultAction.RETRY_AFTER -> mapped.copy(disableMs = ResultSignals.disableMillis(result))
                        ParseResultAction.SEARCH_PREFILL -> mapped.copy(prefill = title)
                        else -> mapped
                    }
                ParseOutcome(action)
            }

            is ApiResult.TransportFailure -> {
                ParseOutcome(ParseAction(ParseResultAction.ERROR, textKey = NETWORK_TEXT_KEY, showTrace6 = false))
            }

            else -> {
                // 本地拒绝（归属失效、撤回、切换环境）、会话结束等：不出卡、不提示
                ParseOutcome(null, dropped = true)
            }
        }
    }

    companion object {
        const val OPERATION = "parseInput"
        const val SCENE_CLIPBOARD = "clipboard"
        const val MAX_TEXT = 4000

        /** 传输失败：通用错误文案（没有 trace_id，不带编号）。 */
        const val NETWORK_TEXT_KEY = "error.50001"
    }
}
