package com.couli.app.search.visual

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.feature.search.SearchConfigPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** 所有请求只进入内存；挂起用于真实 Controller 的首屏 / 翻页在途态。 */
internal class SearchVisualApi : PageApi {
    val calls = mutableListOf<ApiCall>()
    var answer: (ApiCall) -> ApiResult = { success(SearchVisualFixtures.page()) }
    var hold: CompletableDeferred<Unit>? = null
    var holdPaging = false

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        if (!holdPaging || call.query.orEmpty().contains("cursor=")) hold?.await()
        return answer(call)
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs(): CallEpochs = CallEpochs(0, 0)

    companion object {
        fun success(page: JsonObject): ApiResult = ApiResult.Success(page, "sample-search-000123", null)

        fun failure(name: String): ApiResult {
            val row = SearchVisualFixtures.error(name)
            val code = row.getValue("code").jsonPrimitive.int
            val millis = row["retry_after_seconds"]?.jsonPrimitive?.int?.times(1000L)
            val kind = if (millis != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.GENERIC_ERROR
            val detail = JsonObject(row.filterKeys { it == "reason" || it == "platform" })
            return ApiResult.Failure(
                code,
                "",
                detail,
                row.getValue("trace_id").jsonPrimitive.content,
                ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = millis),
                null,
            )
        }
    }
}

internal class SearchVisualPort : SearchConfigPort {
    var platforms = listOf("taobao", "jd", "pdd")
    var refreshes = 0

    override fun statusLayers(): JsonObject {
        val statuses = listOf("taobao", "jd", "pdd").associateWith { JsonPrimitive(if (it in platforms) "on" else "off") }
        return JsonObject(mapOf("current" to JsonObject(statuses)))
    }

    override fun refresh() {
        refreshes += 1
    }

    override fun issuedMark(): Long = 0

    override fun appliedMark(): Long = 0

    override fun subscribe(listener: () -> Unit): () -> Unit = {}
}
