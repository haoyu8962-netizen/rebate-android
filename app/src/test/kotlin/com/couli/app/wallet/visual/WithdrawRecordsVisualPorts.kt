package com.couli.app.wallet.visual

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class WithdrawRecordsVisualApi : WalletApi {
    var answer: ApiResult? = null
    var hold: CompletableDeferred<Unit>? = null
    var nextAnswer = WithdrawRecordsVisualFixtures.ok(WithdrawRecordsVisualFixtures.page("next"))
    var nextHold: CompletableDeferred<Unit>? = CompletableDeferred()
    var detailHold: CompletableDeferred<Unit>? = null
    val detailAnswers = mutableMapOf<String, ApiResult>()

    /** v2 连续分页：按完整 query 回答；空表仍走 v1 的 nextAnswer / nextHold。 */
    val pageAnswers = mutableMapOf<String, ApiResult>()
    val pageHolds = mutableMapOf<String, CompletableDeferred<Unit>>()
    val calls = mutableListOf<ApiCall>()
    val pendingWrites = mutableListOf<String>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        return when (call.operationId) {
            "listWithdrawals" -> {
                if (call.query.orEmpty().startsWith("cursor=")) {
                    (pageHolds[call.query] ?: nextHold)?.await()
                    pageAnswers[call.query] ?: nextAnswer
                } else {
                    hold?.await()
                    checkNotNull(answer)
                }
            }

            "getWithdrawal" -> {
                val id = call.pathParams.getValue("withdrawal_id")
                val result =
                    detailAnswers[id] ?: WithdrawRecordsVisualFixtures.details.values
                        .map { it.jsonObject }
                        .first { it.getValue("withdrawal_id").toString() == "\"$id\"" }
                        .let(WithdrawRecordsVisualFixtures::ok)
                detailHold?.await()
                result
            }

            else -> {
                error("提现记录不应发送 ${call.operationId}")
            }
        }
    }

    override fun epochs() = CallEpochs(0, 0)

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun pendingFor(operationId: String): PendingKeyRecord? = null

    override fun restorePending() = true

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult {
        pendingWrites += "confirm:$operationId"
        return WithdrawRecordsVisualFixtures.failure()
    }

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult {
        pendingWrites += "abandon:$operationId"
        return WithdrawRecordsVisualFixtures.failure()
    }
}

internal class WithdrawRecordsVisualEnv : WalletEnv {
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val copies = mutableListOf<String>()
    val contacts = mutableListOf<String>()
    var copyAccepted = true
    var epoch = 1L

    override fun loggedIn() = true

    override fun sessionEpoch() = epoch

    override fun account() = "records-visual-user"

    override fun appVersion() = "1.0.0"

    override fun earningsSince(): String? = null

    override fun configData(): JsonObject = WithdrawRecordsVisualFixtures.group.getValue("config").jsonObject

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun contactService(fromRoute: String) {
        contacts += fromRoute
    }

    override fun replaceTop(
        route: String,
        params: JsonObject,
        entryId: String?,
    ) = error("记录页不可替换栈顶")

    override fun copy(text: String): Boolean {
        copies += text
        return copyAccepted
    }

    override fun forceUpdate() = Unit

    override fun stepUpPhone(account: String): String? = null
}
