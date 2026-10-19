package com.couli.core.clipboard

/**
 * 敏感清单（BR-ID-16 细则「敏感页面不识别」；规则空白 G12）：这些路由或浮层在前台时不自动读剪贴板，离开后也不补做；系统分享到达时同样丢弃。
 * 集中放这一处，自动识别门槛（[ImportGates]）与系统分享（[ShareImportAction]）都按它判断。FU-AND-0724 退役旧读取判定时把 10-18 加入的
 * InviteBackfill 并入同一份清单（0723 交回 G08）：它还没登记进 routes.json（契约 G01），不能打开，但一旦出现也按敏感页处理。
 */
object ClipboardSensitive {
    const val ROUTE_INVITE_BACKFILL = "InviteBackfill"

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
            ROUTE_INVITE_BACKFILL,
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

/** 隐私结论的三端同名取值（系统分享判定 state.consent、桥 clipboard.read 的读前核对）。 */
object ClipboardConsentRaw {
    /** 已同意当前版本隐私政策。 */
    const val AGREED = "agreed"

    /** 基本模式。 */
    const val BASIC = "basic"

    /** 同意的不是当前版本（政策升级后未决定）。 */
    const val OUTDATED = "outdated"
}
