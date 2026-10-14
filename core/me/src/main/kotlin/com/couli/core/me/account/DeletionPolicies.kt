// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.core.me.account

import com.couli.core.designsystem.CouliFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 注销页资金区用的钱包摘要三个字段（G11：只用服务端字段，不相加、不推算；不显示冻结中）。 */
data class WalletFunds(
    val withdrawableFen: Long,
    val estimatedFen: Long,
    val negativeFen: Long,
) {
    companion object {
        /** 从 getWalletSummary 的 data 取三个整数分字段；缺任一项或类型不符为 null（按读取失败）。 */
        @Suppress("ReturnCount") // 三个字段逐项
        fun of(element: JsonElement?): WalletFunds? {
            val w = element.long("withdrawable_fen") ?: return null
            val e = element.long("estimated_total_fen") ?: return null
            val n = element.long("negative_fen") ?: return null
            return WalletFunds(w, e, n)
        }
    }
}

/**
 * 注销页的显示（brief §2 第 2 项；DeleteAccount*；G10–G15、G20、G24、G35、G51）。资金区金额只经 [CouliFormat.money]；时间按 BR-TEXT-11
 * （冷静期截止用 [CouliFormat.timeListFuture]，G36 / G50）。
 */
data class DeletionView(
    val stateRaw: String,
    val applyEnabledWhenChecked: Boolean,
    val showGoWithdraw: Boolean,
    val showCancelDeletion: Boolean,
    val negativeAmountFen: Long?,
    val textKeys: List<String>,
    val deletion: Deletion?,
    val funds: WalletFunds?,
    val coolingUntilText: String?,
    val applyAtText: String?,
    /** 资金区渲染串（G35 观察口）。 */
    val withdrawableText: String?,
    val estimatedText: String?,
    val negativeText: String?,
    /** 勾选框可用（initial / no_phone_step_up）。 */
    val waiverEnabled: Boolean,
    /** 【联系客服】（negative）。 */
    val showContactService: Boolean,
    /** 【去绑定手机号】（no_phone_step_up 且 full 会话，G10）。 */
    val showBindPhone: Boolean,
) {
    companion object {
        const val INITIAL = "initial"
        const val NEGATIVE = "negative"
        const val FUNDS_UNAVAILABLE = "funds_unavailable"
        const val COOLING = "cooling"
        const val PROCESSING = "processing"
        const val PENDING_CONFIRM = "pending_confirm"
        const val NO_PHONE = "no_phone_step_up"
        const val LOAD_FAILED = "load_failed"
    }
}

object DeletionPresentation {
    const val PENDING_NONE = "none"
    const val PENDING_IN_FLIGHT = "in_flight"
    const val PENDING_UNKNOWN = "unknown"

    /** 注销须知六条（静态草稿，G13）。 */
    val NOTICE_KEYS: List<String> = (1..6).map { "deletion.notice.$it" }

    /**
     * [read] 为 [DeletionRead.Bad] 表示读取失败或坏数据；[funds] 为 null 表示钱包摘要读取失败；[phoneBound] 为 null 表示未知；[pendingRaw] 为本账号
     * requestDeletion 的未决状态（none / in_flight / unknown；held 不进待确认，按 in_flight 置灰由调用方决定）。
     * 优先级：pending_confirm > load_failed > cooling / processing > negative > funds_unavailable > no_phone_step_up > initial（G51）。
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod", "ReturnCount") // 按优先级逐级返回
    fun view(
        read: DeletionRead,
        funds: WalletFunds?,
        phoneBound: Boolean?,
        pendingRaw: String,
        scopeRaw: String,
        sourceRaw: String,
        nowMs: Long,
    ): DeletionView {
        val deletion = (read as? DeletionRead.Ok)?.deletion
        val fundTexts =
            Triple(
                funds?.let { CouliFormat.money(it.withdrawableFen) },
                funds?.let { CouliFormat.money(it.estimatedFen) },
                funds?.negativeFen?.takeIf { it > 0 }?.let(CouliFormat::money),
            )
        val coolingText = deletion?.let { CouliFormat.timeListFuture(it.coolingUntilMillis, nowMs) }
        val applyText = deletion?.let { CouliFormat.timeListFuture(it.applyAtMillis, nowMs) }
        val full = scopeRaw == AccountRaw.FULL
        val canWithdraw = full && sourceRaw != AccountRaw.FORCE_UPDATE
        val fundsKeys = listOf("deletion.funds.title", "deletion.withdrawable", "deletion.estimated", "deletion.withdraw_hint")
        val formKeys = NOTICE_KEYS + listOf("deletion.notice.title", "deletion.waiver", "deletion.submit", "deletion.submit_hint")

        fun make(
            state: String,
            apply: Boolean,
            keys: List<String>,
            goWithdraw: Boolean = false,
            cancel: Boolean = false,
            negative: Long? = null,
            waiver: Boolean = false,
            cs: Boolean = false,
            bind: Boolean = false,
        ) = DeletionView(
            state,
            apply,
            goWithdraw,
            cancel,
            negative,
            keys,
            deletion,
            funds,
            coolingText,
            applyText,
            fundTexts.first,
            fundTexts.second,
            fundTexts.third,
            waiver,
            cs,
            bind,
        )

        if (pendingRaw == PENDING_UNKNOWN) {
            return make(
                DeletionView.PENDING_CONFIRM,
                false,
                listOf(
                    AccountRaw.TITLE_KEY,
                    "pending_confirm.deletion.desc",
                    "pending_confirm.deletion.action",
                    "pending_confirm.abandon",
                    "deletion.pending_hint",
                ) + fundsKeys + formKeys,
            )
        }
        if (read == DeletionRead.Bad) return make(DeletionView.LOAD_FAILED, false, listOf("deletion.load_failed"))
        if (deletion?.status == Deletion.COOLING) {
            val keys =
                if (deletion.canCancel) {
                    listOf(
                        "deletion.cooling.title",
                        "deletion.cooling.deadline",
                        "deletion.cooling.restore",
                        "deletion.cooling.timeline.apply",
                        "deletion.cooling.timeline.cooling",
                        "deletion.cooling.timeline.process",
                        "deletion.cooling.readonly",
                        "deletion.cooling.cancel",
                    )
                } else {
                    // G15：can_cancel=false 只看服务端，不按本机时钟推算
                    listOf(
                        "error.10007",
                        "deletion.cooling.cancel_expired",
                        "deletion.cooling.timeline.apply",
                        "deletion.cooling.timeline.cooling",
                        "deletion.cooling.timeline.process",
                    )
                }
            return make(DeletionView.COOLING, false, keys, cancel = deletion.canCancel)
        }
        if (deletion?.status == Deletion.PROCESSING || deletion?.status == Deletion.DONE) {
            return make(DeletionView.PROCESSING, false, listOf("error.10007"))
        }
        // 从未申请或 cancelled（G20：是否为负只看这一次 negative_fen）
        val inFlight = pendingRaw == PENDING_IN_FLIGHT
        return when {
            funds == null -> {
                make(DeletionView.FUNDS_UNAVAILABLE, false, listOf("deletion.funds_failed") + formKeys)
            }

            funds.negativeFen > 0 -> {
                make(
                    DeletionView.NEGATIVE,
                    false,
                    listOf("error.30416", "deletion.negative", "deletion.negative.note", "deletion.contact_cs") + fundsKeys + formKeys,
                    negative = funds.negativeFen,
                    cs = true,
                )
            }

            phoneBound == false -> {
                make(
                    DeletionView.NO_PHONE,
                    !inFlight,
                    fundsKeys + formKeys + "deletion.step_up_unsupported",
                    goWithdraw = canWithdraw,
                    waiver = !inFlight,
                    bind = full,
                )
            }

            else -> {
                make(DeletionView.INITIAL, !inFlight, fundsKeys + formKeys, goWithdraw = canWithdraw, waiver = !inFlight)
            }
        }
    }

    /** 向量接线：deletion 为 JsonNull / 对象 / "load_failed"；wallet 为对象 / "failed"。 */
    @Suppress("LongParameterList")
    fun of(
        deletion: JsonElement,
        wallet: JsonElement,
        phoneBoundRaw: String,
        pendingRaw: String,
        scopeRaw: String,
        sourceRaw: String,
        nowMs: Long,
    ): JsonObject {
        val read = if (deletion is JsonPrimitive && deletion.isString) DeletionRead.Bad else AccountJson.read(deletion)
        val funds = (wallet as? JsonObject)?.let(WalletFunds::of)
        val phone =
            when (phoneBoundRaw) {
                "true" -> true
                "false" -> false
                else -> null
            }
        val v = view(read, funds, phone, pendingRaw, scopeRaw, sourceRaw, nowMs)
        val map =
            linkedMapOf<String, JsonElement>(
                "stateRaw" to JsonPrimitive(v.stateRaw),
                "applyEnabledWhenChecked" to v.applyEnabledWhenChecked.json(),
                "showGoWithdraw" to v.showGoWithdraw.json(),
                "showCancelDeletion" to v.showCancelDeletion.json(),
                "negativeAmountFen" to v.negativeAmountFen.json(),
                "textKeys" to JsonArray(v.textKeys.map(::JsonPrimitive)),
                "withdrawableText" to v.withdrawableText.json(),
                "estimatedText" to v.estimatedText.json(),
            )
        if (v.stateRaw == DeletionView.COOLING) map["coolingUntilText"] = v.coolingUntilText.json()
        return JsonObject(map)
    }
}

/**
 * 申请、确认、放弃三个阶段的结局（brief §0 第 1 条、§2 第 3 项；G26、G27、G40、G41、G58）。[keyEndsRaw]：ends = 键结束；keeps = 不结束
 * （从未结果未知的键此时 nextKeyRaw 决定下次用不用原键：same = held 沿用原键原体，new = 键不再使用）。[nextStateRaw]：initial / negative /
 * cooling / pending_confirm / step_up / force_update / unchanged（等待重取 getDeletion，不凭错误码伪造 Deletion）。
 */
data class DeletionOutcome(
    val keyEndsRaw: String,
    val nextKeyRaw: String,
    val autoResendSameKey: Boolean,
    val nextStateRaw: String,
    val textKey: String?,
    val navigateRaw: String = AccountRaw.NONE,
    val refetchDeletion: Boolean = false,
    val stepUpAction: String? = null,
    val disableMs: Long? = null,
    val trace6: String? = null,
    /** 30416 的本次金额（data.amount_fen；缺失为 null，用 fallbacks 不带金额的句子，共性第 10 条）。 */
    val negativeAmountFen: Long? = null,
    /** 前置补办的去向（不重放，补办后回本页）。 */
    val guideRoute: String? = null,
    /** 成功或 completed+0 带回的 Deletion。 */
    val deletion: Deletion? = null,
    /** completed 时提示「已经处理完成」（与 [textKey] 的原错误同时出现，G39）。 */
    val alreadyDone: Boolean = false,
    /** 表外码没有契约键时回落的服务端 msg（G26）。 */
    val serverMsg: String? = null,
    /** 10007 的 stage（cooling / processing）。 */
    val stage: String? = null,
) {
    val keyEnds: Boolean get() = keyEndsRaw == AccountRaw.ENDS

    companion object {
        const val INITIAL = "initial"
        const val NEGATIVE = "negative"
        const val COOLING = "cooling"
        const val PENDING_CONFIRM = "pending_confirm"
        const val STEP_UP = "step_up"
        const val FORCE_UPDATE = "force_update"
        const val UNCHANGED = "unchanged"
    }
}

object DeletionOutcomePolicy {
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    fun of(
        phaseRaw: String,
        result: AccountResult,
        sourceRaw: String,
        hadUnknownResult: Boolean,
    ): DeletionOutcome {
        if (phaseRaw == AccountRaw.ABANDON) return abandon(result, sourceRaw)
        val confirm = phaseRaw == AccountRaw.CONFIRM
        // 出现过结果未知的键：非终结响应一律保持待确认
        val pending = confirm || hadUnknownResult
        val fu = sourceRaw == AccountRaw.FORCE_UPDATE
        val keep = DeletionOutcome.PENDING_CONFIRM
        val code = result.code
        if (code == null) {
            return DeletionOutcome(
                AccountRaw.KEEPS,
                AccountRaw.SAME,
                false,
                keep,
                if (pending) AccountRaw.RETRY_LATER_KEY else AccountRaw.TITLE_KEY,
            )
        }
        val data = result.data
        val trace = AccountCodes.trace6(result.traceId)
        return when {
            code == 0 -> {
                val d = AccountJson.response(data)
                DeletionOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NONE,
                    false,
                    DeletionOutcome.COOLING,
                    null,
                    navigateRaw = if (fu) AccountRaw.FORCE_UPDATE else AccountRaw.NONE,
                    refetchDeletion = fu || d == null,
                    deletion = d,
                )
            }

            code == AccountCodes.STEP_UP -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    true,
                    DeletionOutcome.STEP_UP,
                    "error.10003",
                    stepUpAction = AccountRaw.ACCOUNT_DELETION,
                )
            }

            code == AccountCodes.DELETION -> {
                val stage = data.text("stage")
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    if (pending) keep else DeletionOutcome.UNCHANGED,
                    "error.10007",
                    refetchDeletion = stage == "cooling",
                    stage = stage,
                )
            }

            code == AccountCodes.FORCE_UPDATE -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    DeletionOutcome.FORCE_UPDATE,
                    "error.10405",
                    navigateRaw = AccountRaw.FORCE_UPDATE,
                )
            }

            code in AccountCodes.PREREQUISITES -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    if (pending) AccountRaw.SAME else AccountRaw.NEW,
                    false,
                    if (pending) keep else DeletionOutcome.INITIAL,
                    "error.$code",
                    guideRoute = AccountCodes.guideRoute(code, data),
                )
            }

            code == AccountCodes.ABANDONED -> {
                DeletionOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, DeletionOutcome.INITIAL, "error.20903")
            }

            code == AccountCodes.NEGATIVE -> {
                DeletionOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NEW,
                    false,
                    DeletionOutcome.NEGATIVE,
                    "error.30416",
                    negativeAmountFen = data.long("amount_fen")?.takeIf { it > 0 },
                )
            }

            AccountCodes.business(code) -> {
                val key = AccountCodes.errorKey(code)
                DeletionOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NEW,
                    false,
                    DeletionOutcome.INITIAL,
                    key,
                    serverMsg =
                        result.msg.takeIf {
                            key ==
                                null
                        },
                )
            }

            code == AccountCodes.RATE_LIMITED -> {
                val ms = result.retryAfterMillis ?: AccountCodes.retryAfter(null)
                if (pending) {
                    DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY, disableMs = ms)
                } else {
                    DeletionOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, DeletionOutcome.INITIAL, "error.42901", disableMs = ms)
                }
            }

            code == AccountCodes.BUSY -> {
                if (pending) {
                    DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY)
                } else {
                    DeletionOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, DeletionOutcome.INITIAL, "error.40901")
                }
            }

            AccountCodes.server(code) -> {
                if (pending) {
                    DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY, trace6 = trace)
                } else {
                    DeletionOutcome(
                        AccountRaw.KEEPS,
                        AccountRaw.SAME,
                        false,
                        DeletionOutcome.INITIAL,
                        AccountCodes.serverKey(code),
                        trace6 = trace,
                    )
                }
            }

            code / 10_000 == 2 -> {
                // 20001 / 20002 / 20003 等：从未未知时键结束；出现过结果未知的保持待确认（cannot_confirm）
                if (pending) {
                    DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.CANNOT_CONFIRM_KEY)
                } else {
                    val ends = code in setOf(AccountCodes.INVALID, AccountCodes.WRONG_CODE, AccountCodes.EXPIRED_CODE)
                    DeletionOutcome(
                        if (ends) AccountRaw.ENDS else AccountRaw.KEEPS,
                        if (ends) AccountRaw.NEW else AccountRaw.SAME,
                        false,
                        DeletionOutcome.INITIAL,
                        AccountCodes.errorKey(code) ?: "error.20001",
                    )
                }
            }

            code == AccountCodes.RISK_BLOCKED -> {
                if (pending) {
                    DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY)
                } else {
                    DeletionOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, DeletionOutcome.INITIAL, "error.44001")
                }
            }

            else -> {
                // 表外 1xxxx / 4xxxx（G26）：不结束键
                if (pending) {
                    DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY)
                } else {
                    DeletionOutcome(
                        AccountRaw.KEEPS,
                        AccountRaw.SAME,
                        false,
                        DeletionOutcome.INITIAL,
                        AccountCodes.errorKey(code),
                        serverMsg = result.msg,
                    )
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod") // 作废结果逐类
    private fun abandon(
        result: AccountResult,
        sourceRaw: String,
    ): DeletionOutcome {
        val keep = DeletionOutcome.PENDING_CONFIRM
        val code = result.code ?: return DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY)
        if (code == 0) {
            val view =
                AbandonView.of(result.data)
                    ?: return DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY)
            if (view.abandoned) {
                return DeletionOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, DeletionOutcome.INITIAL, AccountRaw.ABANDONED_KEY)
            }
            val original = view.originalCode ?: 0
            if (original == 0) {
                val fu = sourceRaw == AccountRaw.FORCE_UPDATE
                val d = AccountJson.response(view.originalData)
                return DeletionOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NONE,
                    false,
                    DeletionOutcome.COOLING,
                    AccountRaw.ALREADY_DONE_KEY,
                    navigateRaw = if (fu) AccountRaw.FORCE_UPDATE else AccountRaw.NONE,
                    refetchDeletion = fu || d == null,
                    deletion = d,
                    alreadyDone = true,
                )
            }
            val negative = original == AccountCodes.NEGATIVE
            val key = AccountCodes.errorKey(original)
            return DeletionOutcome(
                AccountRaw.ENDS,
                AccountRaw.NEW,
                false,
                if (negative) DeletionOutcome.NEGATIVE else DeletionOutcome.INITIAL,
                key,
                negativeAmountFen = if (negative) view.originalData.long("amount_fen")?.takeIf { it > 0 } else null,
                alreadyDone = true,
                serverMsg = view.originalMsg.takeIf { key == null },
            )
        }
        return when {
            code == AccountCodes.BUSY -> {
                DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.ABANDON_BUSY_KEY)
            }

            code == AccountCodes.ABANDONED -> {
                DeletionOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, DeletionOutcome.INITIAL, AccountRaw.ABANDONED_KEY)
            }

            code == AccountCodes.FORCE_UPDATE -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    DeletionOutcome.FORCE_UPDATE,
                    "error.10405",
                    AccountRaw.FORCE_UPDATE,
                )
            }

            code in AccountCodes.PREREQUISITES -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    keep,
                    "error.$code",
                    guideRoute = AccountCodes.guideRoute(code, result.data),
                )
            }

            code == AccountCodes.RATE_LIMITED -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    keep,
                    AccountRaw.RETRY_LATER_KEY,
                    disableMs = result.retryAfterMillis ?: AccountCodes.retryAfter(null),
                )
            }

            AccountCodes.server(code) -> {
                DeletionOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    keep,
                    AccountRaw.RETRY_LATER_KEY,
                    trace6 = AccountCodes.trace6(result.traceId),
                )
            }

            else -> {
                DeletionOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, keep, AccountRaw.RETRY_LATER_KEY)
            }
        }
    }

    /** 向量接线。 */
    @Suppress("LongParameterList")
    fun of(
        phaseRaw: String,
        http: Int?,
        code: Int?,
        data: JsonElement,
        retryAfter: String?,
        traceId: String?,
        sourceRaw: String,
        hadUnknownResult: Boolean,
    ): JsonObject = json(of(phaseRaw, accountResult(http, code, data, retryAfter, traceId), sourceRaw, hadUnknownResult))

    fun json(o: DeletionOutcome): JsonObject {
        val map =
            linkedMapOf<String, JsonElement>(
                "keyEndsRaw" to JsonPrimitive(o.keyEndsRaw),
                "nextKeyRaw" to JsonPrimitive(o.nextKeyRaw),
                "autoResendSameKey" to o.autoResendSameKey.json(),
                "nextStateRaw" to JsonPrimitive(o.nextStateRaw),
                "textKey" to o.textKey.json(),
                "navigateRaw" to JsonPrimitive(o.navigateRaw),
                "refetchDeletion" to o.refetchDeletion.json(),
                "alreadyDone" to o.alreadyDone.json(),
            )
        o.stepUpAction?.let { map["stepUpAction"] = JsonPrimitive(it) }
        o.disableMs?.let { map["disableMs"] = JsonPrimitive(it) }
        o.trace6?.let { map["trace6"] = JsonPrimitive(it) }
        o.negativeAmountFen?.let { map["negativeAmountFen"] = JsonPrimitive(it) }
        o.guideRoute?.let { map["guideRoute"] = JsonPrimitive(it) }
        return JsonObject(map)
    }
}

/** 撤回注销的结局（brief §2 第 4 项；G15–G18、G38）。不写未决记录，不自动重发。 */
data class DeletionCancelOutcome(
    val nextStateRaw: String,
    val textKey: String,
    val navigateRaw: String,
    val refetchDeletion: Boolean,
    val disableMs: Long? = null,
    val trace6: String? = null,
    val deletion: Deletion? = null,
) {
    val autoResend: Boolean get() = false
    val writesPendingKey: Boolean get() = false
}

object DeletionCancelPolicy {
    @Suppress("CyclomaticComplexMethod")
    fun of(
        result: AccountResult,
        sourceRaw: String,
    ): DeletionCancelOutcome {
        val cooling = DeletionOutcome.COOLING
        val code = result.code ?: return DeletionCancelOutcome(cooling, "deletion.cooling.cancel_unknown", AccountRaw.NONE, true)
        return when {
            code == 0 -> {
                val navigate =
                    when (sourceRaw) {
                        AccountRaw.SETTINGS -> AccountRaw.SETTINGS
                        AccountRaw.FORCE_UPDATE -> AccountRaw.FORCE_UPDATE
                        else -> AccountRaw.NONE
                    }
                DeletionCancelOutcome(
                    DeletionOutcome.INITIAL,
                    "deletion.cancelled_toast",
                    navigate,
                    refetchDeletion = navigate == AccountRaw.FORCE_UPDATE,
                    deletion = AccountJson.response(result.data),
                )
            }

            code == AccountCodes.DELETION -> {
                DeletionCancelOutcome(cooling, "deletion.cooling.cancel_expired", AccountRaw.NONE, true)
            }

            code == AccountCodes.RATE_LIMITED -> {
                DeletionCancelOutcome(
                    cooling,
                    "error.42901",
                    AccountRaw.NONE,
                    false,
                    disableMs = result.retryAfterMillis ?: AccountCodes.retryAfter(null),
                )
            }

            AccountCodes.server(code) -> {
                DeletionCancelOutcome(
                    cooling,
                    AccountCodes.serverKey(code),
                    AccountRaw.NONE,
                    false,
                    trace6 = AccountCodes.trace6(result.traceId),
                )
            }

            code == AccountCodes.FORCE_UPDATE -> {
                DeletionCancelOutcome(cooling, "error.10405", AccountRaw.NONE, false)
            }

            else -> {
                DeletionCancelOutcome(cooling, AccountCodes.errorKey(code) ?: AccountRaw.RETRY_LATER_KEY, AccountRaw.NONE, false)
            }
        }
    }

    /** 带 Retry-After 原串与 traceId 的重载（G38）。 */
    @Suppress("LongParameterList") // 向量的四项输入加 Retry-After 与 traceId
    fun of(
        http: Int?,
        code: Int?,
        data: JsonElement,
        sourceRaw: String,
        retryAfter: String?,
        traceId: String?,
    ): DeletionCancelOutcome = of(accountResult(http, code, data, retryAfter, traceId), sourceRaw)

    /** 向量接线。 */
    fun of(
        http: Int?,
        code: Int?,
        data: JsonElement,
        sourceRaw: String,
    ): JsonObject {
        val o = of(http, code, data, sourceRaw, null, null)
        val map =
            linkedMapOf<String, JsonElement>(
                "nextStateRaw" to JsonPrimitive(o.nextStateRaw),
                "textKey" to JsonPrimitive(o.textKey),
                "navigateRaw" to JsonPrimitive(o.navigateRaw),
                "autoResend" to o.autoResend.json(),
                "refetchDeletion" to o.refetchDeletion.json(),
                "writesPendingKey" to o.writesPendingKey.json(),
            )
        o.disableMs?.let { map["disableMs"] = JsonPrimitive(it) }
        o.trace6?.let { map["trace6"] = JsonPrimitive(it) }
        return JsonObject(map)
    }
}

/** 撤回未知后的核对（G17、G61）：cancelled 且不是 negative_balance 才按撤回成功结算。 */
object DeletionCancelReconcile {
    fun cancelledByUser(read: DeletionRead): Boolean =
        (read as? DeletionRead.Ok)?.deletion?.let { it.status == Deletion.CANCELLED && it.cancelReason != Deletion.REASON_NEGATIVE } == true
}

/** 注销待确认摘要（G24）：固定两句加提交时间；标签都是草稿键。 */
object DeletionSummary {
    val LINES: List<Pair<String, String>> =
        listOf(
            "pending_confirm.summary.operation" to "pending_confirm.summary.operation.deletion",
            "pending_confirm.summary.waiver" to "deletion.waiver",
        )

    /** 写未决记录时的摘要原文（不含手机号与请求体）。 */
    const val RECORD = "deletion"

    /** 提交时间（BR-TEXT-11，过去时刻）。 */
    fun submittedAt(
        createdAtMillis: Long,
        nowMs: Long,
    ): String = CouliFormat.timeListPast(createdAtMillis, nowMs)
}
