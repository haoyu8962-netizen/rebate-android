package com.couli.core.config

import android.annotation.SuppressLint
import android.content.Context

/**
 * 记录「上次生效的环境」，启动时与本次要用的环境核对（03 §3.6）。
 *
 * 覆盖安装会改变默认环境而不经过调试面板（debug 默认 test → 同包名 staging 覆盖安装后默认 staging；
 * staging → release 后只有 prod），所以不能只在手动切换时清理：
 * - 无记录（首次安装或记录被清）：只记录，不清理；
 * - 记录与本次相同：什么都不做；
 * - 记录与本次不同：先跑全部清理器（[EnvironmentSwitchCleanerRegistry.cleanAll]），**成功后**再记录新环境；
 *   清理失败不记录，异常抛给调用方，下次启动会再次核对并重试。
 *
 * 记录放在 [STATE_PREFERENCES]（不以 `couli.` 开头，不会被命名空间清理器删掉）。
 */
class EnvironmentStartupReconciler(
    context: Context,
    private val cleaners: EnvironmentSwitchCleanerRegistry,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    /** 上次生效的环境；没有记录（或记录无法识别）时为 null。 */
    val lastApplied: AppEnvironment?
        get() = AppEnvironment.fromKey(preferences.getString(KEY_LAST_APPLIED, null))

    /** 核对结果。 */
    enum class Outcome {
        /** 无记录：只记录了 target，没有清理。 */
        RECORDED_FIRST_RUN,

        /** 与记录相同，什么都没做。 */
        UNCHANGED,

        /** 与记录不同：已清理并记录 target。 */
        CLEANED_AND_RECORDED,
    }

    /**
     * 启动时调用：[target] 是本次要用的环境（显式选择或构建默认）。
     * 清理失败时抛出清理器的异常，且不记录 [target]。
     */
    fun reconcile(target: AppEnvironment): Outcome {
        val previous = lastApplied
        return when (previous) {
            null -> {
                record(target)
                Outcome.RECORDED_FIRST_RUN
            }

            target -> {
                Outcome.UNCHANGED
            }

            else -> {
                cleaners.cleanAll(previous, target)
                record(target)
                Outcome.CLEANED_AND_RECORDED
            }
        }
    }

    /** 记录 [environment] 为已生效。只在清理成功之后调用（调试面板手动切换时由 EnvironmentSwitcher 调用）。 */
    fun markApplied(environment: AppEnvironment) {
        record(environment)
    }

    // 同步写：记录必须在返回前落盘，进程随即被杀也不会丢。
    @SuppressLint("ApplySharedPref")
    private fun record(environment: AppEnvironment) {
        val saved = preferences.edit().putString(KEY_LAST_APPLIED, environment.key).commit()
        check(saved) { "记录生效环境 ${environment.key} 失败" }
    }

    companion object {
        const val STATE_PREFERENCES = "couli_environment_state"
        private const val KEY_LAST_APPLIED = "last_applied_environment"
    }
}
