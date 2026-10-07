package com.couli.core.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 环境闸门（03 §3.6）：启动对账（[EnvironmentStartupReconciler]）成功之前，本次启动不得进入新环境。
 *
 * - [settleAtStartup]：Application.onCreate 调用；对账失败自动再试一次（共 [STARTUP_ATTEMPTS] 次），仍失败则 [State.Blocked]。
 * - [retry]：阻断页的【重试】调用，再跑一次对账（清理）；成功即 [State.Settled]。
 * - [state]：只读。初始为 [State.Blocked]（还没对账，按失败处理：fail-closed）。
 *
 * 处于 [State.Blocked] 时：首屏只显示阻断页，不进入正常界面；会话、缓存、网络等层（APP-02/03 实现）
 * 读取任何本地会话或缓存、发出任何请求之前必须先看 [state] 或调用 [requireSettled]。
 * 对账失败时「上次生效的环境」记录不变（见 [EnvironmentStartupReconciler.reconcile]），下次启动或重试会再清理。
 *
 * @param reconcile 本次构建的对账动作：debug / staging 为 `EnvironmentSwitcher.reconcileAtStartup()`，
 *   release 为 `EnvironmentStartupReconciler.reconcile(PROD)`。抛异常即视为清理失败。
 */
class EnvironmentGate(
    private val reconcile: () -> EnvironmentStartupReconciler.Outcome,
) {
    /** 闸门状态。 */
    sealed interface State {
        /** 对账成功：可以进入正常界面、读取本环境的会话与缓存。 */
        data class Settled(
            val outcome: EnvironmentStartupReconciler.Outcome,
        ) : State

        /** 对账未完成或失败：只能显示阻断页。[cause] 为最后一次失败的异常（尚未对账时为 [NotReconciledYet]）。 */
        data class Blocked(
            val cause: Throwable,
        ) : State
    }

    /** 还没调用过 [settleAtStartup]。 */
    class NotReconciledYet : IllegalStateException("environment not reconciled yet")

    private val mutableState = MutableStateFlow<State>(State.Blocked(NotReconciledYet()))

    /** 只读状态（Settled / Blocked）。 */
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** 运行中切换环境（调试面板）进行中：清理与提交期间闸门关闭（lessons 10）。 */
    @Volatile
    private var switching = false

    /** 已对账且不在切换环境中：可以读本环境的会话与缓存、发请求。 */
    val isSettled: Boolean
        get() = !switching && state.value is State.Settled

    /** 切换环境开始（任何清理之前同步调用）：立即关闭闸门，之后的设备注册与一切请求都不发。 */
    fun closeForSwitch() {
        switching = true
    }

    /** 切换环境结束（新环境已提交，或失败后环境保持不变）：重新打开闸门。 */
    fun reopenAfterSwitch() {
        switching = false
    }

    /** 启动时调用：失败自动再试一次。 */
    fun settleAtStartup(): State = attempt(STARTUP_ATTEMPTS)

    /** 阻断页【重试】：再跑一次对账。已 Settled 时什么都不做。 */
    fun retry(): State = attempt(RETRY_ATTEMPTS)

    /** 会话、缓存、网络层的守卫：未 Settled 即抛 [IllegalStateException]。 */
    fun requireSettled() {
        check(isSettled) { "environment gate is blocked; cleanup has not succeeded" }
    }

    @Synchronized
    private fun attempt(times: Int): State {
        if (state.value !is State.Settled) mutableState.value = runAttempts(times)
        return state.value
    }

    /** 最多跑 [times] 次对账，成功即停。 */
    private fun runAttempts(times: Int): State {
        var result = runCatching(reconcile)
        var remaining = times - 1
        while (result.isFailure && remaining > 0) {
            result = runCatching(reconcile)
            remaining--
        }
        return result.fold(onSuccess = { State.Settled(it) }, onFailure = { State.Blocked(it) })
    }

    companion object {
        /** 启动时最多尝试次数：首次 + 自动重试一次。 */
        const val STARTUP_ATTEMPTS = 2
        private const val RETRY_ATTEMPTS = 1
    }
}
