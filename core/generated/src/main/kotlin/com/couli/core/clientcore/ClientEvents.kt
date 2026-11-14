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

/** specs/events.yaml 的事件构造函数（events.yaml version 6）。事件族（families）随各自任务补。 */
object ClientEvents {
    /** 全部事件名（契约顺序）。 */
    val names: kotlin.collections.List<kotlin.String> =
        listOf("link_jump", "external_page_union_host", "external_page_nav_host", "external_page_blocked_nav", "route_entry_blocked", "route_params_dropped", "bridge_call", "sdui_card_error", "agent_stream_seq_gap", "agent_stream_frame_too_large", "agent_stream_limit", "agent_stream_unknown_event", "app_launch", "page_view", "exposure", "click", "search", "convert", "h5_white_screen")

    /** link_jump：外跳的每一步结果（BR-ATTR-27 ③；BR-ATTR-21 细则「待跟单卡按实际外跳选」）；另记百川初始化失败（BR-ATTR-27 淘宝行，result=failed 且 failure_reason=sdk_init_failed）（规划/03 §4.5、§4.7、§6.2 第 9 条；04 §6.4 POST /v1/events；08 BR-ATTR-21、BR-ATTR-27；编排裁定 D10-1470 ③ ④） */
    fun linkJump(
        page: kotlin.String,
        phase: LinkJumpPhase,
        unionPlatform: LinkJumpUnionPlatform,
        step: LinkJumpStep,
        result: LinkJumpResult,
        attemptId: kotlin.String? = null,
        linkId: kotlin.String? = null,
        failureReason: LinkJumpFailureReason? = null,
        sdkOpenBy: LinkJumpSdkOpenBy? = null,
        sdkErrorCode: kotlin.String? = null,
        durationMs: kotlin.Long? = null,
        installed: LinkJumpInstalled? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        require(phase != LinkJumpPhase.PURCHASE || attemptId != null) { "link_jump.attempt_id 在 phase=purchase 时必填" }
        require(phase != LinkJumpPhase.PURCHASE || linkId != null) { "link_jump.link_id 在 phase=purchase 时必填" }
        require(result != LinkJumpResult.FAILED || failureReason != null) { "link_jump.failure_reason 在 result=failed 时必填" }
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
                failureReason?.raw?.let { put("failure_reason", kotlinx.serialization.json.JsonPrimitive(it)) }
                sdkOpenBy?.raw?.let { put("sdk_open_by", kotlinx.serialization.json.JsonPrimitive(it)) }
                sdkErrorCode?.let { put("sdk_error_code", kotlinx.serialization.json.JsonPrimitive(it)) }
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

    /** bridge_call：JSBridge 调用被拒（规划/03 §5.3；BR-ATTR-29 细则「桥方法不留没有归因的打开入口」）；当前只记拒绝，强更状态未知时以 90500 拒绝的调用要不要记待定（规划/03 §5.3、§4.7「诊断字段」；08 BR-ATTR-29） */
    fun bridgeCall(
        page: kotlin.String,
        method: kotlin.String,
        code: kotlin.Long,
        schemaVersion: kotlin.String,
        bridgeVersion: kotlin.String,
        configVersion: kotlin.String,
        h5Release: kotlin.String,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("method", kotlinx.serialization.json.JsonPrimitive(method))
                put("code", kotlinx.serialization.json.JsonPrimitive(code))
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(schemaVersion))
                put("bridge_version", kotlinx.serialization.json.JsonPrimitive(bridgeVersion))
                put("config_version", kotlinx.serialization.json.JsonPrimitive(configVersion))
                put("h5_release", kotlinx.serialization.json.JsonPrimitive(h5Release))
            }
        return ClientEvent("bridge_call", page, spm, traceId, fields)
    }

    /** sdui_card_error：SDUI 卡片解码失败，只丢弃这一项（规划/03 §6 解码隔离）（规划/03 §6、§7.2、§4.7「诊断字段」） */
    fun sduiCardError(
        page: kotlin.String,
        type: kotlin.String,
        reason: kotlin.String,
        schemaVersion: kotlin.String,
        bridgeVersion: kotlin.String,
        configVersion: kotlin.String,
        h5Release: kotlin.String,
        version: kotlin.Long? = null,
        pageVersion: kotlin.Long? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                version?.let { put("version", kotlinx.serialization.json.JsonPrimitive(it)) }
                pageVersion?.let { put("page_version", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(schemaVersion))
                put("bridge_version", kotlinx.serialization.json.JsonPrimitive(bridgeVersion))
                put("config_version", kotlinx.serialization.json.JsonPrimitive(configVersion))
                put("h5_release", kotlinx.serialization.json.JsonPrimitive(h5Release))
            }
        return ClientEvent("sdui_card_error", page, spm, traceId, fields)
    }

    /** agent_stream_seq_gap：原生 Agent 页流诊断：text.delta 缺号（收到的 seq 大于本地已应用值 +1），本地结束该条消息（03 §7.2）；不带用户消息原文、输入框原文、帧的 data 与 delta、卡片内容和 fallback_text（规划/03 §4.7「agent_* 事件的字段」、§7.2） */
    fun agentStreamSeqGap(
        page: kotlin.String,
        schemaVersion: kotlin.String,
        bridgeVersion: kotlin.String,
        configVersion: kotlin.String,
        h5Release: kotlin.String,
        expected: kotlin.Long,
        received: kotlin.Long,
        runId: kotlin.String? = null,
        messageId: kotlin.String? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                runId?.let { put("run_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                messageId?.let { put("message_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(schemaVersion))
                put("bridge_version", kotlinx.serialization.json.JsonPrimitive(bridgeVersion))
                put("config_version", kotlinx.serialization.json.JsonPrimitive(configVersion))
                put("h5_release", kotlinx.serialization.json.JsonPrimitive(h5Release))
                put("expected", kotlinx.serialization.json.JsonPrimitive(expected))
                put("received", kotlinx.serialization.json.JsonPrimitive(received))
            }
        return ClientEvent("agent_stream_seq_gap", page, spm, traceId, fields)
    }

    /** agent_stream_frame_too_large：原生 Agent 页流诊断：单帧 data 超过上限，按协议错误本地结束该条消息（03 §7.2）；只有共有字段，不带帧内容（规划/03 §4.7「agent_* 事件的字段」、§7.2） */
    fun agentStreamFrameTooLarge(
        page: kotlin.String,
        schemaVersion: kotlin.String,
        bridgeVersion: kotlin.String,
        configVersion: kotlin.String,
        h5Release: kotlin.String,
        runId: kotlin.String? = null,
        messageId: kotlin.String? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                runId?.let { put("run_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                messageId?.let { put("message_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(schemaVersion))
                put("bridge_version", kotlinx.serialization.json.JsonPrimitive(bridgeVersion))
                put("config_version", kotlinx.serialization.json.JsonPrimitive(configVersion))
                put("h5_release", kotlinx.serialization.json.JsonPrimitive(h5Release))
            }
        return ClientEvent("agent_stream_frame_too_large", page, spm, traceId, fields)
    }

    /** agent_stream_limit：原生 Agent 页流诊断：同一 run 的累计文本、卡片数或内容帧数超过输出上限，本地结束该条消息（03 §7.2；上限见 02 §9.2）（规划/03 §4.7「agent_* 事件的字段」、§7.2） */
    fun agentStreamLimit(
        page: kotlin.String,
        schemaVersion: kotlin.String,
        bridgeVersion: kotlin.String,
        configVersion: kotlin.String,
        h5Release: kotlin.String,
        kind: AgentStreamLimitKind,
        runId: kotlin.String? = null,
        messageId: kotlin.String? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                runId?.let { put("run_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                messageId?.let { put("message_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(schemaVersion))
                put("bridge_version", kotlinx.serialization.json.JsonPrimitive(bridgeVersion))
                put("config_version", kotlinx.serialization.json.JsonPrimitive(configVersion))
                put("h5_release", kotlinx.serialization.json.JsonPrimitive(h5Release))
                put("kind", kotlinx.serialization.json.JsonPrimitive(kind.raw))
            }
        return ClientEvent("agent_stream_limit", page, spm, traceId, fields)
    }

    /** agent_stream_unknown_event：原生 Agent 页流诊断：收到不在 04 §8.2 表内的 event 类型，忽略该帧、不中断流（03 §7.2）（规划/03 §4.7「agent_* 事件的字段」、§7.2） */
    fun agentStreamUnknownEvent(
        page: kotlin.String,
        schemaVersion: kotlin.String,
        bridgeVersion: kotlin.String,
        configVersion: kotlin.String,
        h5Release: kotlin.String,
        type: kotlin.String,
        runId: kotlin.String? = null,
        messageId: kotlin.String? = null,
        spm: kotlin.String? = null,
        traceId: kotlin.String? = null,
    ): ClientEvent {
        val fields =
            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {
                runId?.let { put("run_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                messageId?.let { put("message_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("schema_version", kotlinx.serialization.json.JsonPrimitive(schemaVersion))
                put("bridge_version", kotlinx.serialization.json.JsonPrimitive(bridgeVersion))
                put("config_version", kotlinx.serialization.json.JsonPrimitive(configVersion))
                put("h5_release", kotlinx.serialization.json.JsonPrimitive(h5Release))
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
            }
        return ClientEvent("agent_stream_unknown_event", page, spm, traceId, fields)
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

/** events.yaml link_jump.phase：purchase 是一次购买外跳的某一步；startup 只用于同意隐私政策后、还没有购买时的百川初始化失败（result=failed、failure_reason=sdk_init_failed），这时没有 link 与尝试 */
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

/** events.yaml link_jump.result：启动结果只有三种（03 §4.5）。requested 系统或 SDK 接受了启动请求，或已复制口令（取代原 launched 与 copied）；failed 明确失败，必带 failure_reason（原因码）；unknown 系统返回不确定、SDK 回调超时或提交后进程重启。BR-ATTR-21「至少一步执行成功」按 requested 或 unknown 计，failed 不计；漏斗与看板把 unknown 单列，不并入 requested。requested 不等于用户看到了商品页，更不等于成交（BR-ATTR-23） */
enum class LinkJumpResult(
    val raw: kotlin.String,
) {
    REQUESTED("requested"),
    FAILED("failed"),
    UNKNOWN_VALUE("unknown"),
}

/** events.yaml link_jump.failure_reason：failed 的原因码，result=failed 时必带、其他结果不带。sdk_init_failed 百川初始化失败（原 result 的同名取值，BR-ATTR-27 淘宝行；phase=startup 时只会是它）；step_failed 已初始化但这一步明确失败（原 result=failed）。步骤类型见 step */
enum class LinkJumpFailureReason(
    val raw: kotlin.String,
) {
    SDK_INIT_FAILED("sdk_init_failed"),
    STEP_FAILED("step_failed"),
}

/** events.yaml link_jump.sdk_open_by：step=sdk 且为淘宝百川打开指令时必带，取 open 响应 JumpStep.sdk.open_by 的原值（url 走我方推广链接，code 走商品 ID 由 SDK 转链）；其他步骤不带 */
enum class LinkJumpSdkOpenBy(
    val raw: kotlin.String,
) {
    URL("url"),
    CODE("code"),
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

/** events.yaml agent_stream_limit.kind：超过的是哪一个上限：text 累计文本、cards 卡片数、frames 内容帧数（02 §9.2） */
enum class AgentStreamLimitKind(
    val raw: kotlin.String,
) {
    TEXT("text"),
    CARDS("cards"),
    FRAMES("frames"),
}
