package com.couli.app.me

import com.couli.core.me.MeLevel
import com.couli.core.me.account.AccountRaw
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.feature.me.AccountApi
import com.couli.feature.me.AccountEnv
import com.couli.feature.me.AccountIdentity
import com.couli.feature.me.MeEnv
import com.couli.feature.me.MeIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 账号状态 / 申诉的出口账本（只计数、不实现产品规则）：路由（Appeal / Home / Wallet / Messages）、统一客服出口、退出登录、强更。
 * 10006 进入标记按 fixture 给（G10），只认绑定时的账号（换账号后为 false，与 App 清除口径一致）。
 */
internal class RiskVisualEnv : MeEnv {
    override val identityFlow = MutableStateFlow(member())
    override val withdrawing = MutableStateFlow(false)
    val opened = mutableListOf<String>()
    val cs = mutableListOf<String>()
    val logouts = mutableListOf<MeIdentity>()
    var routes: Set<String> = RiskVisualFixtures.routes(public = true)
    var entered10006 = false
    var forceUpdates = 0

    override fun identity() = identityFlow.value

    override fun sessionScope() = AccountRaw.FULL

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route
    }

    override fun copy(text: String): Boolean = error("账号状态 / 申诉不写剪贴板")

    override fun forceUpdate() {
        forceUpdates += 1
    }

    override fun configData(): JsonObject? = null

    override fun availableRoutes() = routes

    override fun earningsFlag(): Boolean? = null

    override fun customerService(fromRoute: String) {
        cs += fromRoute
    }

    override fun notificationStatus() = "granted"

    override fun notificationDeniedAt(): Long? = null

    override fun openNotificationSettings() = Unit

    override fun permissionEntries() = emptyList<JsonObject>()

    override fun permissionStatus(type: String) = "not_asked"

    override fun openAppSettings() = Unit

    override fun requestFullFunction() = Unit

    override fun forceUpdateContext() = false

    override fun enteredBy10006(identity: MeIdentity) = entered10006 && identity.account == ACCOUNT

    override fun deviceRegistered() = true

    override fun consentedPrivacyVersion(): Int? = 3

    override fun legalPrivacyVersion(): Int? = 3

    override fun cacheBytes(): Long? = null

    override fun clearCache(): Boolean = error("账号状态 / 申诉不清缓存")

    override fun appVersion() = "1.0.0"

    override fun appBuild() = "1"

    override fun diagnosticsFields() = JsonObject(emptyMap())

    override fun logout(
        identity: MeIdentity,
        stillHere: () -> Boolean,
    ) {
        logouts += identity
    }

    override fun withdrawConsent(identity: MeIdentity) = error("撤回同意不在本页组")

    override fun close(entryId: String?) = Unit

    companion object {
        const val ACCOUNT = "risk-visual-a"
        const val ACCOUNT_B = "risk-visual-b"

        fun member(
            account: String = ACCOUNT,
            epoch: Long = 1,
        ) = MeIdentity(MeLevel.MEMBER, account, epoch, CallEpochs(0, 0))
    }
}

/**
 * 传输层替身：按 operationId 回放（[queue] 里的一次性回包优先，其次 [answers]）；没登记的请求记进 [unexpected] 并回 50001（测试断言为空）。
 * [hold] 不为 null 时 [holdOp] 的请求挂起到放行（造「提交中」）。每个请求原样记下（含 body、Idempotency-Key）。
 */
internal class RiskVisualApi : PageApi {
    val calls = mutableListOf<ApiCall>()
    val unexpected = mutableListOf<String>()
    val answers = mutableMapOf<String, ApiResult>()
    val queue = mutableMapOf<String, ArrayDeque<ApiResult>>()
    var hold: CompletableDeferred<Unit>? = null
    var holdOp: String = SUBMIT

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        if (call.operationId == holdOp) hold?.await()
        queue[call.operationId]?.removeFirstOrNull()?.let { return it }
        return answers[call.operationId] ?: RiskVisualFixtures.failure().also { unexpected += call.operationId }
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs() = CallEpochs(0, 0)

    fun count(operationId: String) = calls.count { it.operationId == operationId }

    fun ops(): List<String> = calls.map { it.operationId }

    fun once(
        operationId: String,
        result: ApiResult,
    ) {
        queue.getOrPut(operationId) { ArrayDeque() }.addLast(result)
    }

    companion object {
        const val GET_ME = "getMe"
        const val LIST_APPEALS = "listAppeals"
        const val LIST_WITHDRAWALS = "listWithdrawals"
        const val SUBMIT = "submitAppeal"
        const val UNION_BINDINGS = "listUnionBindings"
    }
}

/** 注销入口的账号环境替身：路由可用性跟 [RiskVisualEnv.routes] 同一份；注销入口只记来源（G01、APP-13a）。 */
internal class RiskVisualAccountEnv(
    private val me: RiskVisualEnv,
) : AccountEnv {
    override val accountIdentityFlow = MutableStateFlow(AccountIdentity(me.identity(), AccountRaw.FULL))
    val opened = mutableListOf<String>()
    val deletions = mutableListOf<String>()

    override fun accountIdentity() = accountIdentityFlow.value

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route
    }

    override fun customerService(fromRoute: String) = Unit

    override fun availableRoutes() = me.routes

    override fun openDeletion(source: String) {
        deletions += source
    }

    override fun takeDeletionSource(entryId: String?) = AccountRaw.RISK_NOTICE

    override fun finishDeletion(
        entryId: String?,
        source: String,
        toastKey: String?,
    ) = false

    override fun stepUpPhone(account: String): String? = null

    override fun forceUpdate() = error("账号状态 / 申诉视觉测试不应经注销接线触发强更")

    override fun sessionEpoch() = me.identity().epoch

    override fun account() = me.identity().account
}

internal class RiskVisualAccountApi(
    api: RiskVisualApi,
) : AccountApi,
    PageApi by api {
    override fun pendingFor(operationId: String): PendingKeyRecord? = null

    override fun restorePending(): Boolean = false

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult = error("本页组不确认未决提交")

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult = error("本页组不放弃未决提交")
}
