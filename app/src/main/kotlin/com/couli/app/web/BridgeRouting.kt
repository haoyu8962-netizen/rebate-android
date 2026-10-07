package com.couli.app.web

import com.couli.core.bridge.BridgeCode
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgeUpdateState
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
 * - 强更状态用宿主等待之后的结论 [update]（检查中最多等 5 秒，到时为 unknown）：Router 此刻仍在「检查中」时换成这个结论，
 *   不再按 update_checking 让桥调用排队（等待已在宿主做完，与启动意图排队器的 afterQueueWait 同一口径）；Router 已有结论时
 *   用 Router 的最新结论。
 * - 强更未知而目标不在只读清单（Router 拒绝）：以 90500 结束（不新增码）；其余按是否推了页面回 opened。
 */
internal object BridgeRouting {
    fun open(
        router: Router,
        live: GuardState,
        route: String,
        params: JsonObject,
        update: String,
    ): BridgeOutcome {
        val state = if (UpdateState.fromRaw(live.update) == UpdateState.CHECKING) live.copy(update = update.toRouterUpdate().raw) else live
        val decision = router.open(RouteRequest(route, params, RouteSource.BRIDGE), state)
        if (update != BridgeUpdateState.SUPPORTED && !decision.opensPage) return BridgeOutcome.Fail(BridgeCode.NATIVE_ERROR)
        return BridgeOutcome.Ok(JsonObject(mapOf(KEY_OPENED to JsonPrimitive(decision.opensPage))))
    }

    /** 宿主的强更结论 → Router 的强更状态（支持 → ok；低于最低版本 → below_min；其余 → unknown）。 */
    private fun String.toRouterUpdate(): UpdateState =
        when (this) {
            BridgeUpdateState.SUPPORTED -> UpdateState.OK
            BridgeUpdateState.BELOW -> UpdateState.BELOW_MIN
            else -> UpdateState.UNKNOWN
        }

    private const val KEY_OPENED = "opened"
}
