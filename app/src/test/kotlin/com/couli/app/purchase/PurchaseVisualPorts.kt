package com.couli.app.purchase

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.tradejump.BaichuanAdapter
import com.couli.core.tradejump.BaichuanAuthResult
import com.couli.core.tradejump.BaichuanOpenResult
import com.couli.feature.trade.AuthCredential
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.WebCodeAuthorizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

internal class PurchaseVisualApi : PageApi {
    data class Pending(
        val call: ApiCall,
        val result: CompletableDeferred<ApiResult>,
    )

    val calls = mutableListOf<Pending>()
    val cancelled = mutableListOf<CallOwnership>()
    var product: JsonObject? = null
    var readTips: JsonObject? = null

    override suspend fun send(call: ApiCall): ApiResult {
        val pending = Pending(call, CompletableDeferred())
        calls += pending
        return when {
            call.operationId == "getProduct" -> ApiResult.Success(checkNotNull(product), TRACE, null)
            call.operationId == TradeOperations.GET_TIPS && readTips != null -> ApiResult.Success(checkNotNull(readTips), TRACE, null)
            else -> pending.result.await()
        }
    }

    override fun epochs(): CallEpochs = CallEpochs(0, 0)

    override fun cancelOwned(owner: CallOwnership) {
        cancelled += owner
    }

    fun calls(op: String): List<Pending> = calls.filter { it.call.operationId == op }

    fun last(op: String = TradeOperations.OPEN_LINK): Pending = calls(op).last()

    fun succeed(
        data: JsonObject,
        op: String = TradeOperations.OPEN_LINK,
    ) {
        val pending = last(op)
        pending.result.complete(ApiResult.Success(data, TRACE, pending.call.idempotencyKey))
    }

    fun fail(
        code: Int,
        data: JsonObject = JsonObject(emptyMap()),
        op: String = TradeOperations.OPEN_LINK,
    ) {
        val pending = last(op)
        pending.result.complete(
            ApiResult.Failure(
                code,
                "",
                data,
                TRACE,
                ErrorEffect(code, ErrorEffectKind.PAGE_ACTION, ReplayMode.NONE, false, null),
                pending.call.idempotencyKey,
            ),
        )
    }

    companion object {
        const val TRACE = "purchase-visual-000321"
    }
}

/** 全部替身只在 src/test；记录每次实际端口调用，绝不发真实外跳或复制剪贴板。 */
internal class PurchaseVisualPorts :
    JumpPorts,
    BaichuanAdapter,
    WebCodeAuthorizer {
    var installation = "true"
    var launches = true
    var copySucceeds = true
    var authResult: AuthStepResult = AuthStepResult.Credential(AuthCredential.WebCode("fixture-code"))
    val jumps = mutableListOf<String>()
    val copies = mutableListOf<String>()
    val webAuthorizations = mutableListOf<Pair<String, String>>()
    var sdkAuthorizations = 0
    var sdkOpens = 0

    override fun installed(platform: String): String = installation

    override fun launchApp(
        type: String,
        value: String,
    ): Boolean {
        jumps += "$type:$value"
        return launches
    }

    override fun openBrowser(url: String): Boolean {
        jumps += "browser:$url"
        return launches
    }

    override fun copy(text: String): Boolean {
        copies += text
        return copySucceeds
    }

    override val ready = false
    override val supportsSdkToken = false

    override fun initialize(): Boolean = false

    override fun retryInitIfFailed(): Boolean = false

    override fun open(value: String): BaichuanOpenResult {
        sdkOpens += 1
        return BaichuanOpenResult.INIT_FAILED
    }

    override suspend fun authorize(): BaichuanAuthResult {
        sdkAuthorizations += 1
        return BaichuanAuthResult.Failed
    }

    override val available = true

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult {
        webAuthorizations += authUrl to state
        return authResult
    }
}
