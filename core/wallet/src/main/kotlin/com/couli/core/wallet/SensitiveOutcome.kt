// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.core.wallet

import com.couli.core.network.ErrorActionMapper
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次敏感写操作（提现、收款账号变更）的已解包结果：[kind] 为 ok / error / transport（没有统一外壳：超时、断连、进程被杀、网关错误页）/
 * abandon_result（作废接口的 abandoned 或 completed）。[retryAfterMillis] 是 42901 的禁用时长（Retry-After，缺省或非法 5 秒）。
 */
data class OutcomeInput(
    val kind: String,
    val code: Int? = null,
    val data: JsonObject = JsonObject(emptyMap()),
    val retryAfterMillis: Long? = null,
    val hasTrace: Boolean = false,
    val abandonOutcome: String? = null,
    val originalCode: Int? = null,
    /**
     * 网络层是否判定强更（F-X-09，FU-AND-0715；[com.couli.core.network.ResultSignals.forcesUpdate]）。10405 而没有判定强更（受限会话刷新、刷新失败）
     * 不进强更：首次提交按统一错误文案（error.50001 + trace6）回到可编辑、可重试；确认 / 放弃保持待确认（「暂时无法确认，请稍后再试」）。缺省 true 只给
     * 三端向量（向量只给错误码）。
     */
    val forced: Boolean = true,
) {
    companion object {
        const val OK = "ok"
        const val ERROR = "error"
        const val TRANSPORT = "transport"
        const val ABANDON_RESULT = "abandon_result"
    }
}

/**
 * 判定结论：[actionRaw] 是页面要做的事；[keyEnds] = 这个键结束（对从未结果未知的键表示本次键不用了）；[nextSubmitNewKey] = 用户下次点【提交】用新键；
 * [textKey] 文案键；[route] 引导或展示的目的地（不是补办后的返回路由）；[disableMillis] 42901 禁用时长；[showTrace6] 文案附 trace_id 末 6 位。
 */
data class Outcome(
    val actionRaw: String,
    val keyEnds: Boolean,
    val nextSubmitNewKey: Boolean,
    val textKey: String? = null,
    val route: String? = null,
    val disableMillis: Long? = null,
    val showTrace6: Boolean = false,
    /** 10004 的 consent_type（privacy / agreement 交给已有的协议重新同意组件；本端还没有，A02）。 */
    val consentType: String? = null,
    /** 20001 的 data.fields。 */
    val fields: List<String> = emptyList(),
    /** 44001 的 data.risk_msg_code（弹窗标题先查 risk_msg.<code>）。 */
    val riskMsgCode: String? = null,
)

/**
 * 提现与收款账号变更两个操作的结局表（BR-ID-10 细则「敏感操作的幂等键」、BR-WDR-07 细则、brief §0 第 3、4 条、§2.5）：
 * - 键只在三种结果下结束：业务成功；写入幂等记录的 3xxxx；已作废（abandoned 或 20903）。1xxxx、20903 以外的 2xxxx、40901、42901、5xxxx、
 *   10405、没有响应都不结束（从未结果未知的键在这些情况下「这次键不用了」，下次用新键；10405 也一样）。提现首次提交的 40901 例外：原键仍在处理，
 *   进待确认，下次不用新键（FU-AND-0721）。
 * - 10003 沿用原键（拿到 token 后重放 1 次）；10002 / 10402 由网络层沿用原键自动重发至多 1 次。
 * - 10001 / 10004 / 10005 / 30304 / 30305 / 30307 只引导补办，补办完回到发起页，不重放、不登记 pending_action，由用户再点（用新键；结果未知过的
 *   回到待确认）。
 * - 结果未知：不换键、不自动重发，进待确认；【确认上一笔】【放弃上一笔】都只由用户点击。
 */
@Suppress("TooManyFunctions") // 三个阶段、共用分支与向量接线各一个入口
object SensitiveOutcome {
    const val WITHDRAW = "withdraw"
    const val PAYOUT_ACCOUNT = "payout_account"
    const val SUBMIT = "submit"
    const val CONFIRM = "confirm"
    const val ABANDON = "abandon"

    const val DONE = "done"
    const val STEP_UP = "step_up_same_key"
    const val AUTO_RESEND = "auto_resend_same_key"
    const val GUIDE = "guide_prereq"
    const val SHOW_ERROR_KEY_ENDS = "show_error_key_ends"
    const val SHOW_ERROR_KEEP_PENDING = "show_error_keep_pending"
    const val RISK_BLOCKED = "risk_blocked"
    const val FIELD_ERROR = "field_error"
    const val RETRY_AFTER = "retry_after"
    const val ERROR_TRACE6 = "error_trace6"
    const val FORCE_UPDATE = "force_update"
    const val PENDING_CONFIRM = "pending_confirm"
    const val ABANDONED = "abandoned"
    const val ALREADY_DONE = "already_done"
    const val ABANDON_BUSY = "abandon_busy"

    /**
     * 同键在途（40901）且这个键从未结果未知：提示 error.40901、恢复按钮，不自动重发；键结束，下次点击用新键（APP-09 共性第 12 条、编排者 2026-10-10
     * 口径：从未进入结果未知的提交收到 42901 / 明确 40901 时该键结束）。只用于收款账号；提现的 40901 自 FU-AND-0721 起进待确认（[PENDING_CONFIRM]）。
     */
    const val BUSY_RETRY = "busy_retry"

    const val TITLE_KEY = "pending_confirm.title"
    const val RETRY_LATER_KEY = "pending_confirm.retry_later"
    const val CANNOT_CONFIRM_KEY = "pending_confirm.cannot_confirm"
    const val ABANDONED_KEY = "pending_confirm.abandoned"
    const val ALREADY_DONE_KEY = "pending_confirm.already_done"
    const val ABANDON_BUSY_KEY = "pending_confirm.abandon_busy"

    private const val LABOR = "labor_agreement"
    private const val ID_VERIFICATION = "id_verification"
    private const val GENERIC_SERVER = 50_001
    private const val ABANDONED_CODE = 20903
    private const val BUSY = 40901
    private const val RATE_LIMITED = 42901
    private const val FORCE_UPDATE_CODE = 10405

    fun of(
        op: String,
        phase: String,
        keyEverUnknown: Boolean,
        input: OutcomeInput,
    ): Outcome =
        when (phase) {
            CONFIRM -> confirm(op, input)
            ABANDON -> abandon(op, input)
            else -> submit(op, keyEverUnknown, input)
        }

    // ---------------------------------------------------------------- 首次提交

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 按结果类别逐项
    private fun submit(
        op: String,
        keyEverUnknown: Boolean,
        input: OutcomeInput,
    ): Outcome {
        if (input.kind == OutcomeInput.OK) return Outcome(DONE, true, true, route = successRoute(op))
        if (input.kind == OutcomeInput.TRANSPORT) return Outcome(PENDING_CONFIRM, false, false, TITLE_KEY)
        val code = input.code ?: return Outcome(PENDING_CONFIRM, false, false, TITLE_KEY)
        shared(op, code, input, pending = false)?.let { return it }
        return when {
            code == FORCE_UPDATE_CODE && input.forced -> {
                Outcome(FORCE_UPDATE, false, !keyEverUnknown, "error.10405")
            }

            // 非强更的 10405（F-X-09）：键的处理同 10405（不结束；从未结果未知的下次用新键），页面给统一错误文案 + 可重试
            code == FORCE_UPDATE_CODE && keyEverUnknown -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY, showTrace6 = true)
            }

            code == FORCE_UPDATE_CODE -> {
                Outcome(ERROR_TRACE6, false, true, "error.$GENERIC_SERVER", showTrace6 = true)
            }

            code == RATE_LIMITED -> {
                Outcome(
                    RETRY_AFTER,
                    true,
                    true,
                    "error.42901",
                    disableMillis =
                        input.retryAfterMillis ?: DEFAULT_RETRY_MS,
                )
            }

            // 提现的 40901 不是终态（FU-AND-0721 F-AND-44）：原键仍在处理，进待确认，不结束、不换新键（PendingKeyStore 同步转为结果未知）
            code == BUSY && op == WITHDRAW -> {
                Outcome(PENDING_CONFIRM, false, false, "error.40901")
            }

            code == BUSY -> {
                Outcome(BUSY_RETRY, true, true, "error.40901")
            }

            code / 10_000 == 5 -> {
                Outcome(ERROR_TRACE6, true, true, serverKey(code), showTrace6 = true)
            }

            code == ABANDONED_CODE -> {
                Outcome(SHOW_ERROR_KEY_ENDS, true, true, "error.20903")
            }

            code == 20001 -> {
                fieldError(input)
            }

            code == 44001 -> {
                Outcome(RISK_BLOCKED, true, true, "error.44001", riskMsgCode = input.data.textOrNull("risk_msg_code"))
            }

            ErrorActionMapper.isBusinessCode(code) -> {
                businessError(op, code, input)
            }

            else -> {
                Outcome(SHOW_ERROR_KEY_ENDS, true, true, errorKey(code))
            }
        }
    }

    // ---------------------------------------------------------------- 确认上一笔

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 按结果类别逐项
    private fun confirm(
        op: String,
        input: OutcomeInput,
    ): Outcome {
        if (input.kind == OutcomeInput.OK) return Outcome(DONE, true, true, route = successRoute(op))
        if (input.kind == OutcomeInput.TRANSPORT) return Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
        val code = input.code ?: return Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
        shared(op, code, input, pending = true)?.let { return it }
        return when {
            code == FORCE_UPDATE_CODE && input.forced -> {
                Outcome(FORCE_UPDATE, false, false, "error.10405")
            }

            // 非强更的 10405（F-X-09）：保持待确认，统一的「暂时无法确认，请稍后再试」+ trace6，用户可再点
            code == FORCE_UPDATE_CODE -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY, showTrace6 = true)
            }

            code == RATE_LIMITED -> {
                Outcome(
                    PENDING_CONFIRM,
                    false,
                    false,
                    RETRY_LATER_KEY,
                    disableMillis =
                        input.retryAfterMillis ?: DEFAULT_RETRY_MS,
                )
            }

            code == BUSY -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
            }

            code / 10_000 == 5 -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY, showTrace6 = true)
            }

            code == ABANDONED_CODE -> {
                Outcome(SHOW_ERROR_KEY_ENDS, true, true, "error.20903")
            }

            // 写入幂等记录的业务错误（3xxxx）：键结束，显示该错误并恢复【提交】（AC-S2-62 ⑯）；收款账号页的账号类错误留在本页
            ErrorActionMapper.isBusinessCode(code) -> {
                Outcome(SHOW_ERROR_KEY_ENDS, true, true, businessKey(code, input), stayRoute(op, code))
            }

            code / 10_000 == 2 -> {
                Outcome(SHOW_ERROR_KEEP_PENDING, false, false, CANNOT_CONFIRM_KEY)
            }

            else -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
            }
        }
    }

    // ---------------------------------------------------------------- 放弃上一笔

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 按结果类别逐项
    private fun abandon(
        op: String,
        input: OutcomeInput,
    ): Outcome {
        if (input.kind == OutcomeInput.ABANDON_RESULT) {
            if (input.abandonOutcome == ABANDONED) return Outcome(ABANDONED, true, true, ABANDONED_KEY)
            val original = input.originalCode ?: 0
            return if (original == 0) {
                Outcome(ALREADY_DONE, true, true, ALREADY_DONE_KEY, route = successRoute(op))
            } else {
                Outcome(ALREADY_DONE, true, true, businessKey(original, input), stayRoute(op, original))
            }
        }
        if (input.kind != OutcomeInput.ERROR) return Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
        val code = input.code ?: return Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
        return when {
            code == 10002 || code == 10402 -> {
                Outcome(AUTO_RESEND, false, false)
            }

            // 前置码（BR-ID-10、APP-09 共性第 18 条）：10001 / 10004 / 10005 与确认共用映射，打开对应页、保留未决键，补办回来等用户再点
            code == 10001 || code == 10004 || code == 10005 -> {
                shared(op, code, input, pending = true) ?: Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
            }

            code == FORCE_UPDATE_CODE && input.forced -> {
                Outcome(FORCE_UPDATE, false, false, "error.10405")
            }

            // 非强更的 10405（F-X-09）：同确认
            code == FORCE_UPDATE_CODE -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY, showTrace6 = true)
            }

            code == BUSY -> {
                Outcome(ABANDON_BUSY, false, false, ABANDON_BUSY_KEY)
            }

            code == ABANDONED_CODE -> {
                Outcome(ABANDONED, true, true, ABANDONED_KEY)
            }

            code == RATE_LIMITED -> {
                Outcome(
                    PENDING_CONFIRM,
                    false,
                    false,
                    RETRY_LATER_KEY,
                    disableMillis =
                        input.retryAfterMillis ?: DEFAULT_RETRY_MS,
                )
            }

            code / 10_000 == 5 -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY, showTrace6 = true)
            }

            else -> {
                Outcome(PENDING_CONFIRM, false, false, RETRY_LATER_KEY)
            }
        }
    }

    /** 提交与确认共用：二次验证、自动重发、前置补办（补办期间：首次提交的键结束；结果未知过的键保持待确认）。 */
    @Suppress("ReturnCount")
    private fun shared(
        op: String,
        code: Int,
        input: OutcomeInput,
        pending: Boolean,
    ): Outcome? {
        val ends = !pending
        return when (code) {
            10003 -> {
                Outcome(STEP_UP, false, false, "error.10003")
            }

            10002, 10402 -> {
                Outcome(AUTO_RESEND, false, false)
            }

            10001 -> {
                Outcome(GUIDE, ends, ends, "error.10001", WalletRoutes.LOGIN)
            }

            10005 -> {
                Outcome(GUIDE, ends, ends, "error.10005", WalletRoutes.BIND_PHONE)
            }

            10004 -> {
                val consent = input.data.textOrNull("consent_type")
                val route =
                    when (consent) {
                        LABOR -> WalletRoutes.LABOR_AGREEMENT
                        ID_VERIFICATION -> WalletRoutes.REAL_NAME
                        else -> null
                    }
                Outcome(GUIDE, ends, ends, "error.10004", route, consentType = consent)
            }

            else -> {
                if (pending) null else prerequisite(op, code)
            }
        }
    }

    /** 首次提交得到 3xxxx 前置码：引导补办（30304 → RealName；30305 / 30307 → PayoutAccount）。 */
    private fun prerequisite(
        op: String,
        code: Int,
    ): Outcome? =
        when {
            code == 30304 -> Outcome(GUIDE, true, true, "error.30304", WalletRoutes.REAL_NAME)
            code == 30305 && op == WITHDRAW -> Outcome(GUIDE, true, true, "error.30305", WalletRoutes.PAYOUT_ACCOUNT)
            code == 30307 -> Outcome(GUIDE, true, true, "error.30307", WalletRoutes.PAYOUT_ACCOUNT)
            else -> null
        }

    private fun businessError(
        op: String,
        code: Int,
        input: OutcomeInput,
    ): Outcome = Outcome(SHOW_ERROR_KEY_ENDS, true, true, businessKey(code, input), stayRoute(op, code))

    /** 收款账号页的账号类错误（30303、30307、30308）：显示在本页（route 表示留在 PayoutAccount，不重复压入同名页）。 */
    private fun stayRoute(
        op: String,
        code: Int,
    ): String? = WalletRoutes.PAYOUT_ACCOUNT.takeIf { op == PAYOUT_ACCOUNT && code in setOf(30303, 30307, 30308) }

    private fun businessKey(
        code: Int,
        input: OutcomeInput,
    ): String = if (code == 30303) WithdrawConditionText.view(input.data.textOrNull("reason")).textKey else errorKey(code)

    private fun fieldError(input: OutcomeInput): Outcome {
        val reason = input.data.textOrNull("reason")
        val key = reason?.let { "error.20001.$it" }?.takeIf { DefaultTexts.text(it) != null } ?: "error.20001"
        val fields =
            (input.data["fields"] as? JsonArray)
                ?.mapNotNull {
                    (it as? JsonPrimitive)
                        ?.takeIf { p ->
                            p.isString
                        }?.content
                }.orEmpty()
        return Outcome(FIELD_ERROR, true, true, key, fields = fields)
    }

    private fun successRoute(op: String): String? = WalletRoutes.WITHDRAW_RECORDS.takeIf { op == WITHDRAW }

    /** 5xxxx：已收录且非空的用 error.<code>，未收录回落 error.50001（trace6 由统一错误消息函数另外追加）。 */
    fun serverKey(code: Int): String = "error.$code".takeIf { !DefaultTexts.text(it).isNullOrEmpty() } ?: "error.$GENERIC_SERVER"

    private fun errorKey(code: Int): String = "error.$code".takeIf { DefaultTexts.text(it) != null } ?: "error.$GENERIC_SERVER"

    const val DEFAULT_RETRY_MS = ErrorActionMapper.DEFAULT_RETRY_AFTER_MILLIS

    // ---------------------------------------------------------------- 向量接线

    fun of(
        op: String,
        phase: String,
        keyEverUnknown: Boolean,
        result: JsonObject,
    ): JsonObject {
        val headers = (result["headers"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
        val code = result.longOrNullValue("code")?.toInt()
        val original = result["original"] as? JsonObject
        val input =
            OutcomeInput(
                kind = result.textOrNull("kind") ?: OutcomeInput.TRANSPORT,
                code = code,
                data = result["data"] as? JsonObject ?: JsonObject(emptyMap()),
                retryAfterMillis = if (code == RATE_LIMITED) ErrorActionMapper.retryAfterMillis(headers) else null,
                hasTrace = result.textOrNull("traceId") != null,
                abandonOutcome = result.textOrNull("outcome"),
                originalCode = original?.longOrNullValue("code")?.toInt(),
            )
        val out = of(op, phase, keyEverUnknown, input)
        val pairs =
            linkedMapOf(
                "actionRaw" to JsonPrimitive(out.actionRaw),
                "keyEnds" to out.keyEnds.json(),
                "nextSubmitNewKey" to out.nextSubmitNewKey.json(),
            )
        out.textKey?.let { pairs["textKey"] = JsonPrimitive(it) }
        out.route?.let { pairs["route"] = JsonPrimitive(it) }
        out.disableMillis?.let { pairs["disableMs"] = JsonPrimitive(it) }
        if (out.showTrace6) pairs["showTrace6"] = true.json()
        return JsonObject(pairs)
    }
}

/**
 * 二次验证 token 的复用（G17；APP-13a §2 第 7 项）：只放内存，按账号与 action 隔离（APP-13a 起另按会话世代与作用域隔离，[put] / [take] 的带世代重载），
 * 不落盘。本机时钟早于 expire_at 才带上；成功提交（服务端核销）后立即丢弃；会话换代、登出、换账号、撤回同意、作用域变化时清空；换手机号成功清全部
 * action（[clearAll]，APP-13a 共性第 9 条）；得到 10003 就丢弃并重新弹面板。[take] 只读，不核销服务端 token。
 */
class StepUpTokenCache {
    private data class Entry(
        val account: String,
        val token: String,
        val expireAtMs: Long,
        /** 取得时的登录世代与会话作用域（APP-13a）；APP-09 旧入口写入时为 null。 */
        val epoch: Long? = null,
        val scope: String? = null,
    )

    private val entries = mutableMapOf<String, Entry>()

    /**
     * 失效世代（APP-13a R3-01、共性第 43 条）：整体清空（会话换代、换手机号成功清全部 action、作用域变化）时加 1。二次验证在验证请求发起时记下它，
     * 回包写缓存、交付 token 之前复核，变了就丢弃迟到的 token（不让已作废的 token 重新进缓存或被沿用）。
     */
    @Volatile
    var generation: Long = 0L
        private set

    @Synchronized
    fun put(
        action: String,
        account: String,
        token: String,
        expireAtMs: Long,
    ) {
        entries[action] = Entry(account, token, expireAtMs)
    }

    /** 带会话世代与作用域写入（APP-13a）：之后按同一账号、世代、作用域、action 才复用。 */
    @Suppress("LongParameterList") // 隔离维度逐项给
    @Synchronized
    fun put(
        action: String,
        account: String,
        token: String,
        expireAtMs: Long,
        epoch: Long,
        scope: String,
    ) {
        entries[action] = Entry(account, token, expireAtMs, epoch, scope)
    }

    /** APP-09 旧入口：按 action、账号与 expire_at。 */
    @Synchronized
    fun take(
        action: String,
        account: String,
        nowMs: Long,
    ): String? = entries[action]?.takeIf { it.account == account && nowMs < it.expireAtMs }?.token

    /** 按账号、会话世代、作用域、action 与 expire_at（APP-13a StepUpTokenPolicy 同口径）：任一不符都不复用。 */
    @Synchronized
    fun take(
        action: String,
        account: String,
        nowMs: Long,
        epoch: Long,
        scope: String,
    ): String? =
        entries[action]
            ?.takeIf { it.account == account && it.epoch == epoch && it.scope == scope && nowMs < it.expireAtMs }
            ?.token

    @Synchronized
    fun onSuccess(action: String) {
        entries.remove(action)
    }

    @Synchronized
    fun onSessionChanged() {
        entries.clear()
        generation += 1
    }

    /** 换手机号成功（G04）：丢弃全部 action 的 token。 */
    @Synchronized
    fun clearAll() {
        entries.clear()
        generation += 1
    }

    /** 会话作用域变化（deletion_only ↔ full）：清空，不跨作用域复用。 */
    @Synchronized
    fun onScopeChanged() {
        entries.clear()
        generation += 1
    }

    @Synchronized
    fun on10003(action: String) {
        entries.remove(action)
    }
}
