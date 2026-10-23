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

/** 只替换端口，不替控制器造状态；所有写入口先记账再等待。 */
internal class WithdrawVisualApi : WalletApi {
    val calls = mutableListOf<ApiCall>()
    val events = mutableListOf<String>()
    val confirms = mutableListOf<PendingTarget>()
    val abandons = mutableListOf<PendingTarget>()
    val confirmTokens = mutableListOf<String?>()
    var rules: ApiResult = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules())
    var estimate: ApiResult = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules("estimated"))
    var payout: ApiResult = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.payout())
    var write: ApiResult = WithdrawVisualFixtures.failure(44001)
    var pending: PendingKeyRecord? = null
    var readHold: CompletableDeferred<Unit>? = null
    var estimateHold: CompletableDeferred<Unit>? = null
    var writeHold: CompletableDeferred<Unit>? = null
    var pendingHold: CompletableDeferred<Unit>? = null
    var pendingAnswer: ApiResult = WithdrawVisualFixtures.failure(40901)

    // APP-09v-d：发码 / 验证的逐次答复（空队列时沿用原固定成功答复，c 的用例行为不变）。
    val smsAnswers = ArrayDeque<ApiResult>()
    val stepUpAnswers = ArrayDeque<ApiResult>()
    var stepUpHold: CompletableDeferred<Unit>? = null

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        events += call.operationId
        return when (call.operationId) {
            "getWithdrawRules" -> {
                if (call.query == null) {
                    readHold?.await()
                    rules
                } else {
                    estimateHold?.await()
                    estimate
                }
            }

            "getPayoutAccount" -> {
                payout
            }

            "createWithdrawal", "savePayoutAccount" -> {
                writeHold?.await()
                write
            }

            "sendSmsCode" -> {
                smsAnswers.removeFirstOrNull()
                    ?: WithdrawVisualFixtures.ok(WithdrawVisualFixtures.obj("""{"resend_after_sec":60,"expires_in_sec":300}"""))
            }

            "stepUp" -> {
                stepUpHold?.await()
                stepUpAnswers.removeFirstOrNull()
                    ?: WithdrawVisualFixtures.ok(
                        WithdrawVisualFixtures.obj(
                            """{"step_up_token":"fixture-verified-token","expire_at":"2099-01-01T00:00:00+08:00"}""",
                        ),
                    )
            }

            else -> {
                error("未登记的请求：${call.operationId}")
            }
        }
    }

    override fun pendingFor(operationId: String) = pending?.takeIf { it.operationId == operationId }

    override fun restorePending() = true

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs() = CallEpochs(0, 0)

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult {
        events += "confirm:$operationId"
        confirms += target
        confirmTokens += stepUpToken
        pendingHold?.await()
        return pendingAnswer
    }

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult {
        events += "abandon:$operationId"
        abandons += target
        pendingHold?.await()
        return pendingAnswer
    }

    fun writes() = calls.filter { it.operationId in setOf("createWithdrawal", "savePayoutAccount", "stepUp", "sendSmsCode") }
}

internal class WithdrawVisualEnv : WalletEnv {
    var epoch = 1L
    var user = "withdraw-visual-user"
    var help = true
    var sla: String? = null
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val replaced = mutableListOf<Pair<String, JsonObject>>()
    val appeals = mutableListOf<String>()
    val dirty = mutableListOf<Pair<String, Long>>()

    override fun loggedIn() = true

    override fun sessionEpoch() = epoch

    override fun account() = user

    override fun appVersion() = "1.0.0"

    override fun earningsSince(): String? = null

    override fun configData(): JsonObject {
        val links =
            if (help) {
                WithdrawVisualFixtures.obj(
                    """{"help_links":{"withdraw_rules":{"route":"Help","params":{"article_id":"withdraw-rules-fixture"}}}}""",
                )
            } else {
                WithdrawVisualFixtures.obj("{}")
            }
        val text = sla ?: return links
        return JsonObject(links + ("texts" to WithdrawVisualFixtures.obj("""{"withdraw.sla_text":"$text"}""")))
    }

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun replaceTop(
        route: String,
        params: JsonObject,
        entryId: String?,
    ) {
        replaced += route to params
    }

    override fun contactService(fromRoute: String) {
        appeals += fromRoute
    }

    override fun markWalletDirty(
        account: String,
        epoch: Long,
    ) {
        dirty += account to epoch
    }

    override fun copy(text: String) = false

    override fun forceUpdate() = Unit

    override fun stepUpPhone(account: String): String = "13800005678"
}
