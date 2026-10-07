// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.core.clientcore

/**
 * 一个待采集的事件（specs/events.yaml）：事件名、业务传入的公共字段（page、spm、trace_id）与事件字段。
 * 其余公共字段（event_id、client_at、session_id、device_id、user_id、platform、app_version、channel）由 Tracker 补齐。
 * 构造函数不公开：业务代码只能调用 [ClientEvents] 的生成函数（03 §4.7、拍板第二批 TECH-21）。
 */
class ClientEvent internal constructor(
    val name: kotlin.String,
    val page: kotlin.String,
    val spm: kotlin.String?,
    val traceId: kotlin.String?,
    val fields: kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonPrimitive>,
)

/** specs/events.yaml 的事件构造函数（events.yaml version 1）。事件族（families）随各自任务补。 */
object ClientEvents {
    /** 全部事件名（契约顺序）。 */
    val names: kotlin.collections.List<kotlin.String> =
        listOf("link_jump", "external_page_union_host", "external_page_nav_host", "external_page_blocked_nav", "route_entry_blocked", "route_params_dropped", "bridge_call", "sdui_card_error", "app_launch", "page_view", "exposure", "click", "search", "convert", "h5_white_screen")

    /** link_jump：外跳的每一步结果（BR-ATTR-27 ③；BR-ATTR-21 细则「待跟单卡按实际外跳选」）；另记百川初始化失败（BR-ATTR-27 淘宝行）（规划/03 §4.5、§4.7；08 BR-ATTR-21、BR-ATTR-27） */
    fun linkJump(
        page: kotlin.String,
        phase: LinkJumpPhase,
        unionPlatform: LinkJumpUnionPlatform,
        step: LinkJumpStep,
        result: LinkJumpResult,
        attemptId: kotlin.String? = null,
        linkId: kotlin.String? = null,
        durationMs: kotlin.Long? = null,
        installed: LinkJumpInstalled? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        require(phase != LinkJumpPhase.PURCHASE || attemptId != null) { "link_jump.attempt_id 在 phase=purchase 时必填" }
        require(phase != LinkJumpPhase.PURCHASE || linkId != null) { "link_jump.link_id 在 phase=purchase 时必填" }
        require(phase != LinkJumpPhase.PURCHASE || durationMs != null) { "link_jump.duration_ms 在 phase=purchase 时必填" }
        require(phase != LinkJumpPhase.PURCHASE || installed != null) { "link_jump.installed 在 phase=purchase 时必填" }
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("phase", kotlinx.serialization.json.JsonPrimitive(phase.raw))
                attemptId?.let { put("attempt_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                linkId?.let { put("link_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("union_platform", kotlinx.serialization.json.JsonPrimitive(unionPlatform.raw))
                put("step", kotlinx.serialization.json.JsonPrimitive(step.raw))
                put("result", kotlinx.serialization.json.JsonPrimitive(result.raw))
                durationMs?.let { put("duration_ms", kotlinx.serialization.json.JsonPrimitive(it)) }
                installed?.raw?.let { put("installed", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        return ClientEvent("link_jump", page, spm, traceId, fields)
    }

    /** external_page_union_host：第三方页容器里命中联盟平台网页域名的导航（BR-ATTR-29 ②，每次命中一条）（规划/03 §5.1；08 BR-ATTR-29） */
    fun externalPageUnionHost(
        page: kotlin.String,
        unionPlatform: ExternalPageUnionHostUnionPlatform,
        category: ExternalPageUnionHostCategory,
        frame: ExternalPageUnionHostFrame,
        outcome: ExternalPageUnionHostOutcome,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("union_platform", kotlinx.serialization.json.JsonPrimitive(unionPlatform.raw))
                put("category", kotlinx.serialization.json.JsonPrimitive(category.raw))
                put("frame", kotlinx.serialization.json.JsonPrimitive(frame.raw))
                put("outcome", kotlinx.serialization.json.JsonPrimitive(outcome.raw))
            }
        return ClientEvent("external_page_union_host", page, spm, traceId, fields)
    }

    /** external_page_nav_host：容器内文档级导航的抽样埋点（采样率 /v1/config external_page.nav_host_sample_bp；BR-ATTR-29 细则「覆盖限制与待定事项」）（规划/03 §5.1；08 BR-ATTR-29） */
    fun externalPageNavHost(
        page: kotlin.String,
        registeredDomain: kotlin.String,
        frame: ExternalPageNavHostFrame,
        hit: kotlin.Boolean,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("registered_domain", kotlinx.serialization.json.JsonPrimitive(registeredDomain))
                put("frame", kotlinx.serialization.json.JsonPrimitive(frame.raw))
                put("hit", kotlinx.serialization.json.JsonPrimitive(hit))
            }
        return ClientEvent("external_page_nav_host", page, spm, traceId, fields)
    }

    /** external_page_blocked_nav：第三方页容器取消的非 https 跳转与下载（只记类别）（规划/03 §5.1） */
    fun externalPageBlockedNav(
        page: kotlin.String,
        kind: ExternalPageBlockedNavKind,
        frame: ExternalPageBlockedNavFrame,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("kind", kotlinx.serialization.json.JsonPrimitive(kind.raw))
                put("frame", kotlinx.serialization.json.JsonPrimitive(frame.raw))
            }
        return ClientEvent("external_page_blocked_nav", page, spm, traceId, fields)
    }

    /** route_entry_blocked：深链或推送命中不允许的入口（BR-ID-10 细则「路由入口矩阵」）（规划/03 §4.4；08 BR-ID-10） */
    fun routeEntryBlocked(
        page: kotlin.String,
        route: kotlin.String,
        entry: RouteEntryBlockedEntry,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("route", kotlinx.serialization.json.JsonPrimitive(route))
                put("entry", kotlinx.serialization.json.JsonPrimitive(entry.raw))
            }
        return ClientEvent("route_entry_blocked", page, spm, traceId, fields)
    }

    /** route_params_dropped：打开不接受参数的资金与实名页面时丢弃了参数（功能对照 G-69）（规划/03 §4.4；08 BR-WDR 细则「资金与实名页面不接受外部预填」） */
    fun routeParamsDropped(
        page: kotlin.String,
        route: RouteParamsDroppedRoute,
        droppedCount: kotlin.Long,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("route", kotlinx.serialization.json.JsonPrimitive(route.raw))
                put("dropped_count", kotlinx.serialization.json.JsonPrimitive(droppedCount))
            }
        return ClientEvent("route_params_dropped", page, spm, traceId, fields)
    }

    /** bridge_call：JSBridge 调用被拒（规划/03 §5.3；BR-ATTR-29 细则「桥方法不留没有归因的打开入口」）；当前只记拒绝，强更状态未知时以 90500 拒绝的调用要不要记待定（规划/03 §5.3；08 BR-ATTR-29） */
    fun bridgeCall(
        page: kotlin.String,
        method: kotlin.String,
        code: kotlin.Long,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("method", kotlinx.serialization.json.JsonPrimitive(method))
                put("code", kotlinx.serialization.json.JsonPrimitive(code))
            }
        return ClientEvent("bridge_call", page, spm, traceId, fields)
    }

    /** sdui_card_error：SDUI 卡片解码失败，只丢弃这一项（规划/03 §6 解码隔离）（规划/03 §6） */
    fun sduiCardError(
        page: kotlin.String,
        type: kotlin.String,
        pageVersion: kotlin.Long,
        reason: kotlin.String,
        version: kotlin.Long? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                version?.let { put("version", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("page_version", kotlinx.serialization.json.JsonPrimitive(pageVersion))
                put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
            }
        return ClientEvent("sdui_card_error", page, spm, traceId, fields)
    }

    /** app_launch：App 启动（字段待定）（规划/03 §4.7） */
    fun appLaunch(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("app_launch", page, spm, traceId, fields)
    }

    /** page_view：页面浏览（字段待定）（规划/03 §4.7） */
    fun pageView(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("page_view", page, spm, traceId, fields)
    }

    /** exposure：卡片曝光（字段待定）（规划/03 §4.7） */
    fun exposure(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("exposure", page, spm, traceId, fields)
    }

    /** click：点击（字段待定）（规划/03 §4.7） */
    fun click(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("click", page, spm, traceId, fields)
    }

    /** search：搜索（字段待定）（规划/03 §4.7） */
    fun search(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("search", page, spm, traceId, fields)
    }

    /** convert：转链（字段待定）（规划/03 §4.7） */
    fun convert(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("convert", page, spm, traceId, fields)
    }

    /** h5_white_screen：H5 白屏（字段待定）（规划/03 §4.7） */
    fun h5WhiteScreen(
        page: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
            }
        return ClientEvent("h5_white_screen", page, spm, traceId, fields)
    }
}

/** events.yaml link_jump.phase：purchase 是一次购买外跳的某一步；startup 只用于同意隐私政策后、还没有购买时的百川初始化失败（result=sdk_init_failed），这时没有 link 与尝试 */
enum class LinkJumpPhase(
    val raw: kotlin.String,
) {
    PURCHASE("purchase"),
    STARTUP("startup"),
}

/** events.yaml link_jump.union_platform：电商平台（contracts/enums/platform.yaml platform） */
enum class LinkJumpUnionPlatform(
    val raw: kotlin.String,
) {
    TAOBAO("taobao"),
    JD("jd"),
    PDD("pdd"),
    MEITUAN("meituan"),
    VIP("vip"),
    DOUYIN("douyin"),
    ELEME("eleme"),
    KUAISHOU("kuaishou"),
    SUNING("suning"),
}

/** events.yaml link_jump.step：只记步骤类型，不带完整推广 URL、口令、推广位或 relation_id */
enum class LinkJumpStep(
    val raw: kotlin.String,
) {
    SDK("sdk"),
    SCHEME("scheme"),
    UNIVERSAL_LINK("universal_link"),
    H5("h5"),
    COPY_TPWD("copy_tpwd"),
}

/** events.yaml link_jump.result：launched 拉起了目标 App 或页面；copied 已复制口令；sdk_init_failed 百川初始化失败（与已初始化但拉起失败的 failed 分开，BR-ATTR-27 淘宝行） */
enum class LinkJumpResult(
    val raw: kotlin.String,
) {
    LAUNCHED("launched"),
    FAILED("failed"),
    SDK_INIT_FAILED("sdk_init_failed"),
    COPIED("copied"),
}

/** events.yaml link_jump.installed：请求体里 installed 的同一取值（contracts/enums/trade.yaml installed_state） */
enum class LinkJumpInstalled(
    val raw: kotlin.String,
) {
    TRUE("true"),
    FALSE("false"),
    UNKNOWN_VALUE("unknown"),
}

/** events.yaml external_page_union_host.union_platform： */
enum class ExternalPageUnionHostUnionPlatform(
    val raw: kotlin.String,
) {
    TAOBAO("taobao"),
    JD("jd"),
    PDD("pdd"),
    MEITUAN("meituan"),
    VIP("vip"),
    DOUYIN("douyin"),
    ELEME("eleme"),
    KUAISHOU("kuaishou"),
    SUNING("suning"),
}

/** events.yaml external_page_union_host.category：命中的链接形态类别（link_pattern_category） */
enum class ExternalPageUnionHostCategory(
    val raw: kotlin.String,
) {
    PRODUCT("product"),
    PROMO("promo"),
    UNION_HOST("union_host"),
}

/** events.yaml external_page_union_host.frame： */
enum class ExternalPageUnionHostFrame(
    val raw: kotlin.String,
) {
    INITIAL("initial"),
    MAIN("main"),
    SUBFRAME("subframe"),
    NEW_WINDOW("new_window"),
}

/** events.yaml external_page_union_host.outcome：parse_ok / parse_failed 是交识别后成功转回原生详情或留在原页提示；block 是拦下（与 specs/external-navigation.vectors.json 的 action 对应）；不带完整 URL */
enum class ExternalPageUnionHostOutcome(
    val raw: kotlin.String,
) {
    PARSE_OK("parse_ok"),
    PARSE_FAILED("parse_failed"),
    BLOCK("block"),
}

/** events.yaml external_page_nav_host.frame： */
enum class ExternalPageNavHostFrame(
    val raw: kotlin.String,
) {
    INITIAL("initial"),
    MAIN("main"),
    SUBFRAME("subframe"),
    NEW_WINDOW("new_window"),
}

/** events.yaml external_page_blocked_nav.kind：只记 scheme 类别（下载另记 download），不带完整 URL */
enum class ExternalPageBlockedNavKind(
    val raw: kotlin.String,
) {
    HTTP("http"),
    APP_SCHEME("app_scheme"),
    INTENT("intent"),
    MARKET("market"),
    TEL("tel"),
    SMS("sms"),
    MAILTO("mailto"),
    OTHER_SCHEME("other_scheme"),
    DOWNLOAD("download"),
}

/** events.yaml external_page_blocked_nav.frame： */
enum class ExternalPageBlockedNavFrame(
    val raw: kotlin.String,
) {
    INITIAL("initial"),
    MAIN("main"),
    SUBFRAME("subframe"),
    NEW_WINDOW("new_window"),
}

/** events.yaml route_entry_blocked.entry： */
enum class RouteEntryBlockedEntry(
    val raw: kotlin.String,
) {
    IN_APP("in_app"),
    PUSH("push"),
    DEEPLINK("deeplink"),
}

/** events.yaml route_params_dropped.route： */
enum class RouteParamsDroppedRoute(
    val raw: kotlin.String,
) {
    WITHDRAW("Withdraw"),
    PAYOUT_ACCOUNT("PayoutAccount"),
    LABOR_AGREEMENT("LaborAgreement"),
    REAL_NAME("RealName"),
}
