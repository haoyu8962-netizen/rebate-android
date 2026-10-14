package com.couli.core.me.account

import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 换号与注销入口的裁剪（brief §0 第 4 条、§2 第 8、9 项；G01、G02、G22、G28、G34、G48）。
 * - 设置「手机号」行：非已登录（游客、基本模式、deletion_only）或强更 → 不显示；phone_bound=false → BindPhone；未知 → 不可点；
 *   true → 本机知道当前账号号码（[phoneKnown]，G34）才进换号第 1 步，否则不可点（编排者补充 2）。
 * - 设置「注销账号」行：已登录（full）、非强更、DeleteAccount 路由可用（G01）才显示；deletion_only 的设置页不显示（G48）。
 * - 强更页标签：强更且路由可用；基本模式不显示（G48）；游客「注销账号」；已登录按 getDeletion：cooling 且 can_cancel → 「撤销注销」，其余、读失败 →
 *   「注销账号」。
 * - 10007 cooling：路由可用才进注销页，否则只提示（G01）。
 */
data class AccountEntries(
    val phoneRowRaw: String,
    val deletionRowShown: Boolean,
    val forceUpdateLabelKey: String?,
    val error10007Raw: String,
) {
    companion object {
        const val HIDDEN = "hidden"
        const val BIND_PHONE = "bind_phone"
        const val DISABLED = "disabled"
        const val PHONE_CHANGE = "phone_change"
        const val NAVIGATE = "navigate"
        const val PROMPT_ONLY = "prompt_only"
    }
}

object AccountEntryPolicy {
    const val GUEST = "guest"
    const val BASIC = "basic"
    const val MEMBER = "member"
    const val DELETION_ONLY = "deletion_only"

    /** [deletion]：强更页取到的注销进度（null = 没读到或读取失败）。 */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // 三类入口逐项裁剪
    fun entries(
        sessionRaw: String,
        forceUpdate: Boolean,
        phoneBound: Boolean?,
        deleteAccountAvailable: Boolean,
        deletion: DeletionRead?,
        phoneKnown: Boolean,
    ): AccountEntries {
        val member = sessionRaw == MEMBER
        val phoneRow =
            when {
                !member || forceUpdate -> AccountEntries.HIDDEN
                phoneBound == false -> AccountEntries.BIND_PHONE
                phoneBound == true && phoneKnown -> AccountEntries.PHONE_CHANGE
                else -> AccountEntries.DISABLED
            }
        val label =
            when {
                !forceUpdate || !deleteAccountAvailable || sessionRaw == BASIC -> {
                    null
                }

                sessionRaw == GUEST -> {
                    "app_update.delete_account"
                }

                else -> {
                    val d = (deletion as? DeletionRead.Ok)?.deletion
                    if (d?.status == Deletion.COOLING && d.canCancel) "app_update.cancel_deletion" else "app_update.delete_account"
                }
            }
        return AccountEntries(
            phoneRow,
            member && !forceUpdate && deleteAccountAvailable,
            label,
            if (deleteAccountAvailable) AccountEntries.NAVIGATE else AccountEntries.PROMPT_ONLY,
        )
    }

    /** 向量接线：[deletion] 为 null = 没读到；JsonNull = 无记录。 */
    @Suppress("LongParameterList")
    fun of(
        sessionRaw: String,
        forceUpdate: Boolean,
        phoneBoundRaw: String,
        deleteAccountAvailable: Boolean,
        deletion: JsonElement?,
        phoneKnown: Boolean = false,
    ): JsonObject {
        val bound =
            when (phoneBoundRaw) {
                "true" -> true
                "false" -> false
                else -> null
            }
        val e = entries(sessionRaw, forceUpdate, bound, deleteAccountAvailable, deletion?.let(AccountJson::read), phoneKnown)
        return JsonObject(
            linkedMapOf(
                "phoneRowRaw" to JsonPrimitive(e.phoneRowRaw),
                "deletionRowShown" to e.deletionRowShown.json(),
                "forceUpdateLabelKey" to e.forceUpdateLabelKey.json(),
                "error10007Raw" to JsonPrimitive(e.error10007Raw),
            ),
        )
    }
}

/** 二次验证 token 的复用条件（APP-09 G17、G23；brief §2 第 7 项）。 */
data class StepUpTokenKey(
    val action: String,
    val scopeRaw: String,
    val sessionGen: Long,
    val accountId: String,
)

object StepUpTokenPolicy {
    const val NONE = "none"
    const val SCOPE_DENIED = "scope_denied"

    /** 受限会话下只允许 account_deletion，其他 action 本地拒绝（不发请求）。 */
    fun localReject(
        action: String,
        scopeRaw: String,
    ): String = if (scopeRaw == AccountRaw.DELETION_ONLY && action != AccountRaw.ACCOUNT_DELETION) SCOPE_DENIED else NONE

    /** 账号、会话世代、作用域、action 全同且本机时钟早于 expire_at 才复用（作用域换代不复用）。 */
    fun reuse(
        cached: StepUpTokenKey?,
        cachedExpireAtMs: Long,
        request: StepUpTokenKey,
        nowMs: Long,
    ): Boolean = localReject(request.action, request.scopeRaw) == NONE && cached == request && nowMs < cachedExpireAtMs

    /** 向量接线。 */
    fun reuse(
        cached: JsonObject?,
        request: JsonObject,
    ): JsonObject {
        val req = key(request)
        val reject = req?.let { localReject(it.action, it.scopeRaw) } ?: NONE
        val reuse =
            req != null &&
                cached != null &&
                reuse(key(cached), cached.longValue("expireAtMs") ?: 0L, req, request.longValue("nowMs") ?: Long.MAX_VALUE)
        return JsonObject(mapOf("reuse" to reuse.json(), "localRejectRaw" to JsonPrimitive(reject)))
    }

    @Suppress("ReturnCount") // 四个字段逐项
    private fun key(o: JsonObject): StepUpTokenKey? {
        val action = o.text("action") ?: return null
        val scope = o.text("scopeRaw") ?: return null
        val gen = o.longValue("sessionGen") ?: return null
        val account = o.text("accountId") ?: return null
        return StepUpTokenKey(action, scope, gen, account)
    }

    private fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

/** BindPhone 首次绑定与换号共用 `bindPhone` 未决记录时的恢复（G07、G44）。 */
data class BindPhoneRestore(
    val showPendingConfirm: Boolean,
    val skipsStep1: Boolean,
    val record: PendingKeyRecord? = null,
)

object BindPhonePendingPolicy {
    const val OPERATION = "bindPhone"
    const val ENTRY_BIND_PHONE = "bind_phone"
    const val ENTRY_PHONE_CHANGE = "phone_change"

    /**
     * 只认本账号、bindPhone、结果未知（unknown）的记录显示待确认卡；held（不结束的明确响应，G58）与本进程在途的不显示卡片。
     * 从换号入口进入时跳过第 1 步（flow-map §11 共同约定）。
     */
    fun restoreRecords(
        records: List<PendingKeyRecord>,
        account: String,
        entryRaw: String,
    ): BindPhoneRestore {
        val record =
            records.firstOrNull { it.operationId == OPERATION && it.account == account && it.stateRaw == PendingKeyStore.UNKNOWN }
        return BindPhoneRestore(record != null, record != null && entryRaw == ENTRY_PHONE_CHANGE, record)
    }

    /** 向量接线（records 的 state 是 pendingFor 输出的测试投影）。 */
    fun restore(
        records: List<JsonObject>,
        account: String,
        entryRaw: String,
    ): JsonObject {
        val show =
            records.any {
                it.text("operation") == OPERATION && it.text("account") == account && it.text("state") == PendingKeyStore.UNKNOWN
            }
        return JsonObject(
            mapOf(
                "showPendingConfirm" to show.json(),
                "skipsStep1" to (show && entryRaw == ENTRY_PHONE_CHANGE).json(),
            ),
        )
    }
}
