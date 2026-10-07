package com.couli.app.web

import com.couli.core.bridge.BridgeCode
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.router.EntryGuard
import com.couli.core.router.GuardDecision
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.Router
import com.couli.core.router.UpdateState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 桥发起的页面跳转（nav.open、auth.login）经 Router 的统一入口（APP-04 三端共性第 33 条，Android 第 1 轮评审 R1-01；03 §4.4）：
 * - 来源一律 [RouteSource.BRIDGE]，不当成原生主动操作（IN_APP）：基本模式下只放行基本模式页面、其余拒绝（不补同意），
 *   强更未知时只开只读清单。这些判定都在 [Router.open] 里、推页面等任何副作用之前做完。
 * - 强更状态用宿主等待之后的结论 [update]（检查中最多等 5 秒，到时为 unknown）：Router 此刻仍在「检查中」、且宿主是等这一轮
 *   超时得到的 unknown 时按 unknown 判，不再按 update_checking 让桥调用排队（等待已在宿主做完，与启动意图排队器的
 *   afterQueueWait 同一口径）；Router 已有结论时用 Router 的最新结论。宿主拿到的是确定结论（supported 等）而 Router 又在检查中，
 *   说明已开始新一轮检查：不拿旧结论顶替（APP-04 共性第 63 条），按检查中交给 Router 重新等待。
 * - 强更未知而目标不在只读清单（Router 拒绝）：以 90500 结束（不新增码）；其余按是否推了页面回 opened。
 */
internal object BridgeRouting {
    /**
     * APP-04 共性第 59、60 条（Android 在 APP-05a 核对后补上）：会推页面、清栈、进前置页或弹补同意的结论，不在这次调用里执行——
     * 先按此刻的状态用 [EntryGuard.decide]（纯判定，没有副作用）定下回给页面的 opened，把真正的 [Router.open] 放进
     * [BridgeOutcome.Ok.afterReply]：BridgeHost 等这次调用的回复在页面里执行完（传输层确认脚本已执行）、并复核调用所属的页面世代、
     * 会话与隐私之后才执行它，执行时用 [live] 取的当前状态重新走一遍 Router 守卫（不沿用等待前算好的结论）。
     * 被拒、要等待（不推页面）的结论没有破坏性，照旧当场交给 Router（记 route_entry_blocked）。
     *
     * APP-04 共性第 63 条（Android 第 1 轮评审 R1-06）：版本检查结论绑定检查轮次 [round]（AppUpdate.checkRound，每发起一轮检查加 1）。
     * 导航前复核时 Router 处于检查中：是新一轮（轮次变了，或这次拿到的本来就是确定结论）→ 保守取消这次导航，不拿上一轮的 supported
     * 顶替；只有同一轮检查等待超时得到的 unknown，才按该轮的 unknown 规则判。
     *
     * [navigated]：afterReply 执行时报告这次真正交给 [Router.open] 的结论（复核后取消导航时为 null），供 auth.login 判断这次调用
     * 是否真的打开了登录页（Android 第 3 轮评审 R3-01：不靠 Router.lastOutcome 的对象变化判断，相同结论会被状态流按值去重）。
     */
    @Suppress("ReturnCount", "LongParameterList") // 被拒、不受支持、延后导航三个出口；Router、状态源、请求与轮次逐项给
    fun open(
        router: Router,
        live: () -> GuardState,
        route: String,
        params: JsonObject,
        update: String,
        round: () -> Long = { 0L },
        navigated: (GuardDecision?) -> Unit = {},
    ): BridgeOutcome {
        val request = RouteRequest(route, params, RouteSource.BRIDGE)
        val startRound = round()
        val state = effective(live(), update)
        val decision = EntryGuard.decide(request, state)
        // APP-04 共性第 65 条：deferred（检查中等）不交给 Router——Router.open 会把它登记成待办、之后补执行；桥调用的结果以这次回复为准
        if (decision.kind == GuardKind.DEFER) return notOpened(update)
        if (decision.kind == GuardKind.REJECT) {
            router.open(request, state)
            return notOpened(update)
        }
        if (update != BridgeUpdateState.SUPPORTED && !decision.opensPage) return BridgeOutcome.Fail(BridgeCode.NATIVE_ERROR)
        return BridgeOutcome.Ok(
            JsonObject(mapOf(KEY_OPENED to JsonPrimitive(decision.opensPage))),
            afterReply = {
                val now = live()
                val checking = UpdateState.fromRaw(now.update) == UpdateState.CHECKING
                // 新一轮检查进行中：这次调用的结论已过时，保守取消（不导航）
                val stale = checking && (round() != startRound || definite(update))
                val latest = effective(now, update)
                // 复核时 Router 判为 deferred：同样不登记、不补执行（共性第 65 条）
                val executed =
                    if (!stale && EntryGuard.decide(request, latest).kind != GuardKind.DEFER) router.open(request, latest) else null
                navigated(executed)
            },
        )
    }

    /**
     * Router 此刻仍在「检查中」、宿主等这一轮超时得到 unknown 时按 unknown 判；宿主拿到的是确定结论（新一轮已开始）时保持检查中，
     * 不用旧结论顶替。Router 已有结论时用 Router 的最新结论。
     */
    private fun effective(
        live: GuardState,
        update: String,
    ): GuardState =
        if (UpdateState.fromRaw(live.update) == UpdateState.CHECKING && !definite(update)) {
            live.copy(update = update.toRouterUpdate().raw)
        } else {
            live
        }

    /** 没有打开：强更结论不是 supported 时以 90500 结束，否则 opened = false。 */
    private fun notOpened(update: String): BridgeOutcome =
        if (update != BridgeUpdateState.SUPPORTED) {
            BridgeOutcome.Fail(BridgeCode.NATIVE_ERROR)
        } else {
            BridgeOutcome.Ok(JsonObject(mapOf(KEY_OPENED to JsonPrimitive(false))))
        }

    /** 宿主拿到的是这一轮的确定结论（supported / below），不是等待超时的 unknown。 */
    private fun definite(update: String): Boolean = update == BridgeUpdateState.SUPPORTED || update == BridgeUpdateState.BELOW

    /** 宿主的强更结论 → Router 的强更状态（支持 → ok；低于最低版本 → below_min；其余 → unknown）。 */
    private fun String.toRouterUpdate(): UpdateState =
        when (this) {
            BridgeUpdateState.SUPPORTED -> UpdateState.OK
            BridgeUpdateState.BELOW -> UpdateState.BELOW_MIN
            else -> UpdateState.UNKNOWN
        }

    private const val KEY_OPENED = "opened"
}
