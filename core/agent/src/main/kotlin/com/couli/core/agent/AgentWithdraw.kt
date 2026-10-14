package com.couli.core.agent

import com.couli.core.wallet.WalletRateGate
import kotlinx.serialization.json.JsonObject

/**
 * 一次撤回 AI 服务同意的结论（G18；鸿蒙 R2-06、R3-02，APP-10 共性第 21、23 条）：失败时保留完整结果（错误码、trace_id、data、Retry-After），按统一规则
 * 处理，不压成布尔值。[text]：要提示的文字（空串不提示）；[route]：统一前置路由（null = 不打开）；[disableMs]：42901 的禁用时长。
 */
class AgentWithdrawOutcome(
    val ok: Boolean,
    val text: String,
    val route: String?,
    val disableMs: Long,
    val result: AgentHttpResult,
) {
    companion object {
        /**
         * - 成功 → 「已撤回 AI 服务同意」；本地没发出 → 静默；
         * - 42901 → 本入口按 Retry-After 禁用（缺省 5 秒），提示 error.42901；10405 → ApiClient 已交强更分流，不再提示；
         * - 10001 / 10005 / 10004(labor_agreement、id_verification) → 统一前置路由（in_app、参数为空）；其余 → 统一文案（5xxxx 按 trace_id 追加 trace6）。
         */
        @Suppress("ReturnCount") // 按结果逐类返回
        fun of(r: AgentHttpResult): AgentWithdrawOutcome {
            if (r.ok) return AgentWithdrawOutcome(true, AgentDraftTexts.text("agent.draft.withdraw.done"), null, 0, r)
            if (r.notSent) return AgentWithdrawOutcome(false, "", null, 0, r)
            if (r.code == AgentErrorCodes.RATE_LIMITED) {
                return AgentWithdrawOutcome(
                    false,
                    AgentController.errorText(AgentErrorCodes.RATE_LIMITED, ""),
                    null,
                    AgentSendErrorPolicy.retryAfterMs(r.retryAfter),
                    r,
                )
            }
            if (r.code == AgentErrorCodes.FORCE_UPDATE) return AgentWithdrawOutcome(false, "", null, 0, r)
            AgentPrereq.route(r.code, r.data)?.let { return AgentWithdrawOutcome(false, "", it, 0, r) }
            return AgentWithdrawOutcome(false, AgentController.errorText(r.codeOrGeneric, r.traceId), null, 0, r)
        }
    }
}

/**
 * 设置页【撤回 AI 服务同意】的执行（G18；界面无关，App 组合 recordConsent（真实 ApiClient）、撤回成功后的作废、Router）：发起前核对本入口的 42901 禁用期；
 * 结果按 [AgentWithdrawOutcome] 分流。失败一律不当成已撤回（AI 同意世代不变、控制器不重建），用户可以再点重试。
 *
 * 导航许可（鸿蒙 R3-02 / RC-01，APP-12 第 8、14 条，APP-10 共性第 23、24 条）：[navigable] 是发起时固定的「固定身份仍是此刻身份 + 发起页仍可见 + 导航世代与
 * 设置内部子页世代未变」，只在打开前置页之前核对；成功后的应用级作废与 42901 限流结算与页面无关，照常完成。
 */
class AgentWithdrawFlow(
    private val gate: WalletRateGate,
    private val record: suspend () -> AgentHttpResult,
    private val withdrawn: () -> Unit,
    private val open: (String) -> Unit,
    private val navigable: () -> Boolean,
) {
    /** 撤回一次；返回结论（页面按 text 提示）。禁用期内不发请求。 */
    suspend fun run(): AgentWithdrawOutcome {
        if (gate.active) {
            val r =
                AgentHttpResult(false, HTTP_TOO_MANY, AgentErrorCodes.RATE_LIMITED, "", JsonObject(emptyMap()), "", null, notSent = true)
            return AgentWithdrawOutcome(false, AgentController.errorText(AgentErrorCodes.RATE_LIMITED, ""), null, 0, r)
        }
        val r = runCatching { record() }.getOrElse { AgentHttpResult.network() }
        val o = AgentWithdrawOutcome.of(r)
        when {
            o.ok -> withdrawn()

            o.disableMs > 0 -> gate.start(o.disableMs)

            // 离页后迟到的前置码（10001 / 10005 / 10004 前置）不导航，不覆盖用户后续导航
            o.route != null && runCatching(navigable).getOrDefault(false) -> open(o.route)
        }
        return o
    }

    private companion object {
        const val HTTP_TOO_MANY = 429
    }
}
