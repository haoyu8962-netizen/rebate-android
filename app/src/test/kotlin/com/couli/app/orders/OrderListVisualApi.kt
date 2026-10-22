package com.couli.app.orders

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

internal class OrderListVisualApi : PageApi {
    var answer: ApiResult? = null
    var hold: CompletableDeferred<Unit>? = null
    var nextAnswer: ApiResult = ApiResult.Success(OrderListVisualFixtures.page("next_page"), TRACE, null)
    var nextHold: CompletableDeferred<Unit>? = CompletableDeferred()
    val calls = mutableListOf<ApiCall>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        return if (call.query?.split('&')?.any { it.substringBefore('=') == "cursor" } == true) {
            nextHold?.await()
            nextAnswer
        } else {
            hold?.await()
            checkNotNull(answer)
        }
    }

    override fun epochs() = CallEpochs(0, 0)

    override fun cancelOwned(owner: CallOwnership) = Unit

    fun success(page: JsonObject) {
        answer = ApiResult.Success(page, TRACE, null)
    }

    fun offline() {
        answer = ApiResult.TransportFailure("disconnect", null, null, false)
    }

    fun fail(code: Int = 50401) {
        answer = failure(code)
    }

    companion object {
        const val TRACE = "orders-visual-abc123"

        fun failure(code: Int = 50401): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                TRACE,
                ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, false, null),
                null,
            )
    }
}
