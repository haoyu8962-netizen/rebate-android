package com.couli.core.clipboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 敏感清单（BR-ID-16 细则「敏感页面不识别」；规则空白 G12）：这些路由或浮层在前台时，回前台不读剪贴板，离开后也不补做。集中放这一处，
 * Router（当前路由）与浮层（当前浮层）都按它判断。
 */
object ClipboardSensitive {
    val routes: Set<String> =
        setOf(
            "Login",
            "BindPhone",
            "RealName",
            "PayoutAccount",
            "Withdraw",
            "LaborAgreement",
            "DeleteAccount",
            "FindOrder",
            "ForceUpdate",
        )

    const val OVERLAY_NONE = "none"
    const val OVERLAY_STEP_UP = "step_up"
    const val OVERLAY_AUTH_SHEET = "auth_sheet"
    const val OVERLAY_OAUTH = "oauth_in_progress"
    const val OVERLAY_AUTH_JUMP = "auth_jump"
    const val OVERLAY_PRIVACY_FIRST_LAUNCH = "privacy_first_launch"
    const val OVERLAY_FORCE_UPDATE = "force_update"

    /** 二次验证面板、AuthSheet、第三方登录或授权进行中（含 auth_jump 执行中）、首启隐私弹窗、强更（G12）。 */
    val overlays: Set<String> =
        setOf(OVERLAY_STEP_UP, OVERLAY_AUTH_SHEET, OVERLAY_OAUTH, OVERLAY_AUTH_JUMP, OVERLAY_PRIVACY_FIRST_LAUNCH, OVERLAY_FORCE_UPDATE)

    fun sensitive(
        route: String?,
        overlay: String?,
    ): Boolean = route in routes || (overlay != null && overlay in overlays)
}

/** 读取判定的输入（各端同名字段，向量 readPolicy.state）。 */
data class ClipboardReadState(
    /** agreed / basic / outdated（基本模式 / 同意的不是当前版本隐私政策）。 */
    val consent: String,
    /** cold_start_foreground / foreground_after_background / refocus_without_background / in_app_navigation / window_focus。 */
    val event: String,
    val route: String?,
    val overlay: String = ClipboardSensitive.OVERLAY_NONE,
    /** 配置开关 clipboard.enabled.<本端>（G05：只有明确 false 才关）。 */
    val configEnabled: Boolean = true,
    /** 栈顶网页容器经 clipboard.setAutoDetect(false) 抑制（G06）。 */
    val webSuppressed: Boolean = false,
    /** 本次前台已经读过。 */
    val readThisForeground: Boolean = false,
    /** Android 设备级开关「打开 App 自动识别剪贴板」。 */
    val androidAutoSwitch: Boolean = true,
    /** Android 窗口已获得焦点（10+ 未获焦点时 getPrimaryClip 返回 null，不能当成空）。 */
    val androidWindowFocused: Boolean = true,
    /** iOS changeCount 自上次以来变了。 */
    val iosChangeCountChanged: Boolean = true,
    /** iOS detectPatterns 的结果；null = 还没探测。 */
    val iosDetectHit: Boolean? = null,
)

/** 判定结果：[actionRaw] read / detect_patterns / show_prompt / paste_only / skip。 */
data class ClipboardReadDecision(
    val actionRaw: String,
    val reasonRaw: String? = null,
)

/**
 * 读取判定（brief §2.1、§3；BR-ID-16、CAP-X-02；规则空白 G05、G06、G12、G13）。顺序：隐私（基本模式 / 隐私政策版本过期）→ 敏感路由与浮层
 * → 鸿蒙只给 PasteButton → 触发事件（只有冷启动进入前台、经过后台再回前台，以及 Android 回前台后等焦点的 window_focus 才算）→ 本次前台
 * 已读 → 配置开关 → 网页抑制 → Android 设备级开关、窗口焦点 → iOS changeCount 与 detectPatterns。
 */
object ClipboardReadPolicy {
    const val PLATFORM_IOS = "ios"
    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_HARMONY = "harmony"

    const val CONSENT_AGREED = "agreed"
    const val CONSENT_BASIC = "basic"
    const val CONSENT_OUTDATED = "outdated"

    const val EVENT_COLD_START = "cold_start_foreground"
    const val EVENT_FOREGROUND = "foreground_after_background"
    const val EVENT_REFOCUS = "refocus_without_background"
    const val EVENT_NAVIGATION = "in_app_navigation"
    const val EVENT_WINDOW_FOCUS = "window_focus"

    const val ACTION_READ = "read"
    const val ACTION_DETECT = "detect_patterns"
    const val ACTION_SHOW_PROMPT = "show_prompt"
    const val ACTION_PASTE_ONLY = "paste_only"
    const val ACTION_SKIP = "skip"

    const val REASON_BASIC_MODE = "basic_mode"
    const val REASON_PRIVACY_OUTDATED = "privacy_outdated"
    const val REASON_SENSITIVE = "sensitive"
    const val REASON_HARMONY_PASTE_ONLY = "harmony_paste_only"
    const val REASON_NOT_FOREGROUND = "not_foreground"
    const val REASON_ALREADY_READ = "already_read"
    const val REASON_CONFIG_OFF = "config_off"
    const val REASON_WEB_SUPPRESSED = "web_suppressed"
    const val REASON_SWITCH_OFF = "switch_off"
    const val REASON_NO_FOCUS = "no_focus"
    const val REASON_UNCHANGED = "unchanged"
    const val REASON_NO_DETECT_HIT = "no_detect_hit"

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 前提逐项短路
    fun decide(
        platform: String,
        state: ClipboardReadState,
    ): ClipboardReadDecision {
        when (state.consent) {
            CONSENT_AGREED -> Unit
            CONSENT_BASIC -> return skip(REASON_BASIC_MODE)
            else -> return skip(REASON_PRIVACY_OUTDATED)
        }
        if (ClipboardSensitive.sensitive(state.route, state.overlay)) return skip(REASON_SENSITIVE)
        if (platform == PLATFORM_HARMONY) return ClipboardReadDecision(ACTION_PASTE_ONLY, REASON_HARMONY_PASTE_ONLY)
        val triggers = if (platform == PLATFORM_ANDROID) ANDROID_TRIGGERS else FOREGROUND_TRIGGERS
        if (state.event !in triggers) return skip(REASON_NOT_FOREGROUND)
        if (state.readThisForeground) return skip(REASON_ALREADY_READ)
        if (!state.configEnabled) return skip(REASON_CONFIG_OFF)
        if (state.webSuppressed) return skip(REASON_WEB_SUPPRESSED)
        return when (platform) {
            PLATFORM_ANDROID -> {
                when {
                    !state.androidAutoSwitch -> skip(REASON_SWITCH_OFF)
                    !state.androidWindowFocused -> skip(REASON_NO_FOCUS)
                    else -> ClipboardReadDecision(ACTION_READ)
                }
            }

            PLATFORM_IOS -> {
                when {
                    !state.iosChangeCountChanged -> skip(REASON_UNCHANGED)
                    state.iosDetectHit == null -> ClipboardReadDecision(ACTION_DETECT)
                    state.iosDetectHit -> ClipboardReadDecision(ACTION_SHOW_PROMPT)
                    else -> skip(REASON_NO_DETECT_HIT)
                }
            }

            else -> {
                skip(REASON_NOT_FOREGROUND)
            }
        }
    }

    /** 向量与桥的 JSON 形态（readPolicy.state）。缺的可选字段按 [ClipboardReadState] 的缺省值。 */
    fun decide(
        platform: String,
        state: JsonObject,
    ): ClipboardReadDecision =
        decide(
            platform,
            ClipboardReadState(
                consent = state.text("consent") ?: CONSENT_OUTDATED,
                event = state.text("event").orEmpty(),
                route = state.text("route"),
                overlay = state.text("overlay") ?: ClipboardSensitive.OVERLAY_NONE,
                configEnabled = state.flag("configEnabled") ?: true,
                webSuppressed = state.flag("webSuppressed") ?: false,
                readThisForeground = state.flag("readThisForeground") ?: false,
                androidAutoSwitch = state.flag("androidAutoSwitch") ?: true,
                androidWindowFocused = state.flag("androidWindowFocused") ?: true,
                iosChangeCountChanged = state.flag("iosChangeCountChanged") ?: true,
                iosDetectHit = state.flag("iosDetectHit"),
            ),
        )

    private val FOREGROUND_TRIGGERS = setOf(EVENT_COLD_START, EVENT_FOREGROUND)
    private val ANDROID_TRIGGERS = FOREGROUND_TRIGGERS + EVENT_WINDOW_FOCUS

    private fun skip(reason: String) = ClipboardReadDecision(ACTION_SKIP, reason)

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
