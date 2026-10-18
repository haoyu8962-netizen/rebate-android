package com.couli.core.clipboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 自动任务一次判定的结论（轮次与「已有活动任务」由协调器先判，这里只看页面、同意、配置与偏好）。 */
enum class ImportGateDecision {
    /** 敏感页面或敏感浮层：本轮跳过并视同已消费，之后切回首页也不补（R13 / R14，03 §4.6 markSkipped）。 */
    SKIP_SENSITIVE,

    /** 已就绪的非目标页（G11）：本轮 pending 失效、不读不弹，切回首页也不补；不计为「已消费」。 */
    DROP_NON_TARGET,

    /** 隐私、功能同意版本、服务端按端开关、H5 暂停、本机开关不满足：本次不读，pending 保留（条件变好后同轮可再试）。 */
    BLOCKED,

    /** 普通互斥模态，或目标页未就绪 / 不可交互 / 未获焦：等待，pending 保留（R13，G10）。 */
    WAIT,

    /** 可读取场景、没看过当前版本的用途说明：拉起弹层并消费本轮，不读（R02 ①，G21）。 */
    NOTICE,

    /** 7 项门槛全部通过：领取本轮并发起读取。 */
    READ,
}

/**
 * 自动任务的门槛判定（BR-ID-16 正文「自动任务的读取门槛」7 项；03 §4.6「前台轮次与门控」；decision G05 / G10 / G11 / G21 / G22 / G47）。
 * 纯函数，不读剪贴板。判定顺序（先命中先返回）：
 * 1. 敏感路由或敏感浮层 → [ImportGateDecision.SKIP_SENSITIVE]（优先于未就绪，R13）；
 * 2. 已就绪的非目标页（只有首页根页与 AgentChat 是目标）→ [ImportGateDecision.DROP_NON_TARGET]；
 * 3. ① 隐私、② 当前 consent_version 缺失（G47，不兜底）、③ 服务端 auto_read.android 明确为 false（G05）、H5 暂停 → BLOCKED，且不弹用途层；
 * 4. ⑤ 普通互斥模态、④ 未就绪 / 不可交互 / 未获焦 → WAIT（等待期间也不弹用途层，G22）；
 * 5. 没看过当前版本的用途说明 → NOTICE（包括开关已开但版本到达前打开的、明确关闭但版本已变的，G30 / G47）；
 * 6. ② ③ 本机开关开且功能同意等于当前版本 → READ，否则 BLOCKED。
 * ⑥ 本轮未消费由协调器判；⑦ Android 没有跨轮节流，恒通过。
 */
object ImportGates {
    const val ROUTE_HOME = "Home"
    const val ROUTE_AGENT_CHAT = "AgentChat"

    /** 现行敏感清单（BR-ID-16 细则「敏感页面不识别」，10-18 加 InviteBackfill）：InviteBackfill 还没登记进 routes.json（G01），所以不并入旧清单。 */
    val sensitiveRoutes: Set<String> = ClipboardSensitive.routes + "InviteBackfill"

    /** 敏感浮层：二次验证、AuthSheet、第三方授权进行中 / 授权跳转、首启隐私、强更。 */
    val sensitiveModals: Set<String> = ClipboardSensitive.overlays

    const val MODAL_NONE = ClipboardSensitive.OVERLAY_NONE
    const val MODAL_PAYMENT = "payment"
    const val MODAL_LOGIN = "login"
    const val MODAL_TRADE_JUMP = "trade_jump"
    const val MODAL_TRADE_RETURN_CONFIRMATION = "trade_return_confirmation"
    const val MODAL_UPDATE_PROMPT = "update_prompt"

    /** 本端在 features.clipboard.auto_read 里的键。 */
    const val CLIENT_ANDROID = "android"

    fun sensitive(env: ImportEnvironment): Boolean = env.page.route in sensitiveRoutes || env.modal in sensitiveModals

    fun target(page: ImportPageScope): Boolean = (page.route == ROUTE_HOME && page.homeRoot) || page.route == ROUTE_AGENT_CHAT

    /** features.clipboard.auto_read.android：缺键（含整节缺失、非布尔）按开启，只有明确的 false 才停自动读取（G05、R09）。 */
    fun autoReadEnabled(configData: JsonObject): Boolean {
        val value =
            configData
                .child("features")
                ?.child("clipboard")
                ?.child("auto_read")
                ?.get(CLIENT_ANDROID) as? JsonPrimitive
        return value?.takeUnless { it.isString }?.booleanOrNull != false
    }

    @Suppress("ReturnCount") // 门槛逐项短路
    fun decide(
        env: ImportEnvironment,
        prefs: ImportPreferenceStore,
    ): ImportGateDecision {
        if (sensitive(env)) return ImportGateDecision.SKIP_SENSITIVE
        if (env.page.ready && !target(env.page)) return ImportGateDecision.DROP_NON_TARGET
        val version = ImportPreferenceStore.currentConsentVersion(env.configData)
        if (version == null || externallyBlocked(env)) return ImportGateDecision.BLOCKED
        if (env.modal != MODAL_NONE || !pageUsable(env.page)) return ImportGateDecision.WAIT
        if (!prefs.hasSeenNotice(version)) return ImportGateDecision.NOTICE
        return if (prefs.isAutoReadAllowed(version)) ImportGateDecision.READ else ImportGateDecision.BLOCKED
    }

    /** 隐私未同意（门槛①）、服务端停了本端自动读取（③）、当前页面实例被 H5 暂停。 */
    private fun externallyBlocked(env: ImportEnvironment): Boolean =
        !env.privacyAgreed || !autoReadEnabled(env.configData) || env.webSuppressed

    /** 门槛④：就绪、可交互、窗口获焦。 */
    private fun pageUsable(page: ImportPageScope): Boolean = page.ready && page.interactive && page.focused

    private fun JsonObject.child(key: String): JsonObject? = get(key) as? JsonObject
}
