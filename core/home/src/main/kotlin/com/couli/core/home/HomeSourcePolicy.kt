package com.couli.core.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 当前显示的来源级别（G16、G31）。 */
object HomeShowing {
    const val NONE = "none"
    const val LKG = "lkg"
    const val DEFAULT = "default"
    const val NETWORK = "network"
    const val LOAD_FAILED = "load_failed"
}

/** 拉取失败的种类（向量 network_fail.kind）。 */
object HomeFailKind {
    const val HTTP_5XX = "http_5xx"
    const val HTTP_42901 = "http_42901"
    const val CODE_10405 = "code_10405"
    const val TIMEOUT = "timeout"
    const val OFFLINE = "offline"
    const val INVALID_PAGE = "invalid_page"
    const val OTHER = "other"
}

/** 三级兜底的输入事件。 */
sealed interface HomeSourceEvent {
    data class ColdStart(
        val hasLkg: Boolean,
    ) : HomeSourceEvent

    data class LkgLoaded(
        val valid: Boolean,
    ) : HomeSourceEvent

    data class NetworkOk(
        val valid: Boolean,
    ) : HomeSourceEvent

    data class NetworkFail(
        val kind: String,
        val retryAfter: String? = null,
    ) : HomeSourceEvent

    data object Timer1500 : HomeSourceEvent

    data object UserInteracted : HomeSourceEvent

    data object PullRefresh : HomeSourceEvent

    data class DefaultLoaded(
        val valid: Boolean,
    ) : HomeSourceEvent

    data object IdentityChanged : HomeSourceEvent
}

/** 一步的输出：动作（多重集合）与之后显示的来源；禁用下拉的时长只在有 disable_refresh 时有意义。 */
data class HomeSourceStep(
    val actions: List<String>,
    val showing: String,
    val refreshDisabledMs: Long? = null,
) {
    fun has(action: String): Boolean = action in actions
}

/**
 * 三级兜底状态机（G16 ①–⑧，TECH-10，F-HOME-04）：网络最新 → 本机 LKG → 包内默认。
 * - 有 LKG：立即显示 LKG（show_lkg）；网络版到达只写 LKG，不替换当前屏；LKG 读出无效 → 当作没有 LKG；
 * - 无 LKG：等网络，1.5 秒内到达且有效 → 直接显示网络版；超时先显示包内默认；之后网络版到达时用户还没交互过就替换一次，交互过只写 LKG；
 * - 下拉刷新：成功 → 替换并写 LKG；失败 → 保留当前屏、提示一次（5xxxx 带 trace6，由调用方的统一错误消息函数追加）；42901 按 Retry-After（缺省或非法 5 秒）禁用下拉；
 *   10405 → 强更分流；
 * - 冷启动的后台拉取失败不提示（42901 只禁用、不提示）；整页无效当作这一级失败，继续显示当前那一级；
 * - 包内默认也解码失败 → StateLoadFailed（最后防线）；
 * - 身份变化后旧控制器作废：迟到的结果一律 drop_stale，直到按新身份重新冷启动。
 */
@Suppress("TooManyFunctions") // 每个事件一个处理函数
class HomeSourcePolicy {
    var showing: String = HomeShowing.NONE
        private set

    private var interacted = false
    private var replaced = false
    private var pulling = false
    private var invalidated = false

    @Suppress("CyclomaticComplexMethod") // 事件逐项
    fun on(event: HomeSourceEvent): HomeSourceStep {
        if (invalidated && event !is HomeSourceEvent.ColdStart && event !is HomeSourceEvent.IdentityChanged) {
            return step(DROP_STALE)
        }
        return when (event) {
            is HomeSourceEvent.ColdStart -> coldStart(event.hasLkg)
            is HomeSourceEvent.LkgLoaded -> lkgLoaded(event.valid)
            is HomeSourceEvent.NetworkOk -> if (event.valid) networkOk() else networkFail(HomeFailKind.INVALID_PAGE, null)
            is HomeSourceEvent.NetworkFail -> networkFail(event.kind, event.retryAfter)
            HomeSourceEvent.Timer1500 -> timer()
            HomeSourceEvent.UserInteracted -> step(NONE).also { interacted = true }
            HomeSourceEvent.PullRefresh -> step(NONE).also { pulling = true }
            is HomeSourceEvent.DefaultLoaded -> defaultLoaded(event.valid)
            HomeSourceEvent.IdentityChanged -> identityChanged()
        }
    }

    private fun coldStart(hasLkg: Boolean): HomeSourceStep {
        invalidated = false
        interacted = false
        replaced = false
        pulling = false
        showing = if (hasLkg) HomeShowing.LKG else HomeShowing.NONE
        return step(if (hasLkg) SHOW_LKG else NONE)
    }

    private fun lkgLoaded(valid: Boolean): HomeSourceStep {
        if (!valid && showing == HomeShowing.LKG) showing = HomeShowing.NONE
        return step(NONE)
    }

    private fun timer(): HomeSourceStep {
        if (showing != HomeShowing.NONE) return step(NONE)
        showing = HomeShowing.DEFAULT
        return step(SHOW_DEFAULT)
    }

    private fun networkOk(): HomeSourceStep {
        val replace =
            when {
                pulling -> true
                showing == HomeShowing.NONE || showing == HomeShowing.LOAD_FAILED -> true
                showing == HomeShowing.DEFAULT && !interacted && !replaced -> true
                else -> false
            }
        pulling = false
        if (!replace) return step(WRITE_LKG)
        if (showing == HomeShowing.DEFAULT) replaced = true
        showing = HomeShowing.NETWORK
        return step(SHOW_NETWORK, WRITE_LKG)
    }

    private fun networkFail(
        kind: String,
        retryAfter: String?,
    ): HomeSourceStep {
        val pulled = pulling
        pulling = false
        return when {
            kind == HomeFailKind.HTTP_42901 && pulled -> {
                HomeSourceStep(listOf("$TOAST_ERROR:$KEY_42901", DISABLE_REFRESH), showing, retryAfterMillis(retryAfter))
            }

            // 冷启动的后台拉取被限流：不提示，只按 Retry-After 禁用（H04 同口径）
            kind == HomeFailKind.HTTP_42901 -> {
                HomeSourceStep(listOf(DISABLE_REFRESH), showing, retryAfterMillis(retryAfter))
            }

            kind == HomeFailKind.CODE_10405 -> {
                step(FORCE_UPDATE)
            }

            pulled -> {
                step("$TOAST_ERROR:$KEY_50001")
            }

            else -> {
                step(NONE)
            }
        }
    }

    private fun defaultLoaded(valid: Boolean): HomeSourceStep {
        if (valid || (showing != HomeShowing.DEFAULT && showing != HomeShowing.NONE)) return step(NONE)
        showing = HomeShowing.LOAD_FAILED
        return step(SHOW_LOAD_FAILED)
    }

    private fun identityChanged(): HomeSourceStep {
        invalidated = true
        pulling = false
        showing = HomeShowing.NONE
        return step(NONE)
    }

    private fun step(vararg actions: String): HomeSourceStep = HomeSourceStep(actions.toList(), showing)

    /** 向量接线：event 名 + arg 对象 → `{actionsRaw, showing, refreshDisabledMs?}`。 */
    fun on(
        event: String,
        arg: JsonObject,
    ): JsonObject {
        val typed =
            when (event) {
                "cold_start" -> HomeSourceEvent.ColdStart(arg.flag("hasLkg"))
                "lkg_loaded" -> HomeSourceEvent.LkgLoaded(arg.flag("valid"))
                "network_ok" -> HomeSourceEvent.NetworkOk(arg.flag("valid"))
                "network_fail" -> HomeSourceEvent.NetworkFail(arg.text("kind") ?: HomeFailKind.OTHER, arg.text("retryAfter"))
                "timer_1500" -> HomeSourceEvent.Timer1500
                "user_interacted" -> HomeSourceEvent.UserInteracted
                "pull_refresh" -> HomeSourceEvent.PullRefresh
                "default_loaded" -> HomeSourceEvent.DefaultLoaded(arg.flag("valid"))
                "identity_changed" -> HomeSourceEvent.IdentityChanged
                else -> error("未知事件 $event")
            }
        val result = on(typed)
        val map =
            linkedMapOf(
                "actionsRaw" to JsonArray(result.actions.map(::JsonPrimitive)),
                "showing" to JsonPrimitive(result.showing),
            )
        if (result.has(DISABLE_REFRESH)) map["refreshDisabledMs"] = JsonPrimitive(result.refreshDisabledMs)
        return JsonObject(map)
    }

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val NONE = "none"
        const val SHOW_LKG = "show_lkg"
        const val SHOW_DEFAULT = "show_default"
        const val SHOW_NETWORK = "show_network"
        const val WRITE_LKG = "write_lkg"
        const val SHOW_LOAD_FAILED = "show_load_failed"
        const val DISABLE_REFRESH = "disable_refresh"
        const val FORCE_UPDATE = "force_update"
        const val DROP_STALE = "drop_stale"
        const val TOAST_ERROR = "toast_error"
        const val KEY_42901 = "error.42901"
        const val KEY_50001 = "error.50001"

        /** Retry-After（秒，正整数）→ 毫秒；缺省或非法 5 秒（brief §0）。 */
        fun retryAfterMillis(value: String?): Long {
            val seconds = value?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            return seconds?.let { it * MILLIS } ?: DEFAULT_RETRY_AFTER_MS
        }

        const val DEFAULT_RETRY_AFTER_MS = 5_000L
        private const val MILLIS = 1_000L
    }
}
