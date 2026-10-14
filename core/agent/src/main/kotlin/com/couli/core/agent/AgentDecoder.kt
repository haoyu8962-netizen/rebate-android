package com.couli.core.agent

import com.couli.generated.RouteSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10 §2 第 3 条：帧解码（AgentFrameDecoder）与卡片解码（AgentCardDecoder），按锁定契约 agent-stream.schema.json 校验（G07–G10、G13）。
// - 帧：id 为正整数；data 是 JSON 对象；带 seq 的事件 seq 必须等于 id；7 种 event 各按 $defs 校验必填、类型与多键。
//   未知 event → unknown_event（忽略，G05：不发 agent_stream_unknown_event）；不合 schema → invalid_frame（丢弃，只记本机日志）。
// - card 帧外壳不合 → invalid_frame；外壳合法而内层 data 不合、schema_version 高于本端支持（全部为 1）或类型未注册 → 卡片 fallback
//   （只显示 fallback_text，G09）。page_guide 另按包内 routes.json 的 agent_guide 判定（不在 → fallback 并带 blockedRoute，G10）；
//   earnings_summary 判形状（实时 / 历史，history 来源一律按历史，G13）。
// 不认任何 url / scheme / js 字段：卡片动作只认契约给的 route_target 与固定按钮（brief §0 第 5 条）。三端同一套判定（鸿蒙 AgentDecoder.ets）。

object AgentEvent {
    const val META = "meta"
    const val TEXT_DELTA = "text.delta"
    const val TOOL_STATUS = "tool.status"
    const val CARD = "card"
    const val SUGGESTIONS = "suggestions"
    const val ERROR = "error"
    const val DONE = "done"
    val ALL: Set<String> = setOf(META, TEXT_DELTA, TOOL_STATUS, CARD, SUGGESTIONS, ERROR, DONE)
}

object AgentFrameResult {
    const val OK = "ok"
    const val INVALID = "invalid_frame"
    const val UNKNOWN_EVENT = "unknown_event"
}

/** 一帧的解码结果（不可变）。 */
data class AgentFrame(
    val result: String,
    val event: String,
    val id: Long,
    val data: JsonObject,
) {
    val ok: Boolean get() = result == AgentFrameResult.OK

    /** `{resultRaw, event, id, data}`（向量与 Reducer 输入用；invalid 只有 resultRaw）。 */
    fun json(): JsonObject =
        if (result == AgentFrameResult.INVALID) {
            JsonObject(mapOf("resultRaw" to JsonPrimitive(result)))
        } else {
            JsonObject(
                linkedMapOf(
                    "resultRaw" to JsonPrimitive(result),
                    "event" to JsonPrimitive(event),
                    "id" to JsonPrimitive(id),
                    "data" to data,
                ),
            )
        }

    companion object {
        val INVALID: AgentFrame = AgentFrame(AgentFrameResult.INVALID, "", 0, AgentJson.EMPTY)

        /** decode 的 JSON 形状 → 强类型（Reducer 的 frame 输入）。 */
        @Suppress("ReturnCount", "ComplexCondition") // 缺一项即 invalid
        fun ofJson(o: JsonObject?): AgentFrame {
            o ?: return INVALID
            val result = AgentJson.stringOf(o["resultRaw"])
            val event = AgentJson.stringOf(o["event"])
            val id = AgentJson.intOf(o["id"])
            val data = AgentJson.objectOf(o["data"])
            if (result == null || result == AgentFrameResult.INVALID || event == null || id == null || data == null) return INVALID
            return AgentFrame(result, event, id, data)
        }
    }
}

@Suppress("TooManyFunctions") // 每种 event 一个判定
object AgentFrameDecoder {
    private val ID = Regex("^[1-9][0-9]*$")
    private val FINISH_REASONS =
        listOf("stop", "cancelled", "limit", "budget", "error", "auth_required", "safety", "fallback", "timeout")
    private const val CODE_MIN = 10_000L
    private const val CODE_MAX = 99_999L
    private const val SUGGESTIONS_MAX = 3

    /** SSE 条目（[AgentSse.feed] / [AgentSse.fromNdjsonLine] 的输出）→ 解码结果的 JSON 形状。 */
    fun decode(item: JsonObject): JsonObject = frame(item).json()

    @Suppress("ReturnCount", "ComplexCondition") // 逐项校验，不合即 invalid
    fun frame(item: JsonObject): AgentFrame {
        if (AgentJson.stringOf(item["kindRaw"]) != AgentSse.KIND_FRAME) return AgentFrame.INVALID
        val event = AgentJson.stringOf(item["event"])
        val idText = AgentJson.stringOf(item["id"])
        val dataText = AgentJson.stringOf(item["dataText"])
        if (event == null || idText == null || dataText == null || !ID.matches(idText)) return AgentFrame.INVALID
        val id = idText.toLongOrNull()?.takeIf { it >= 1 } ?: return AgentFrame.INVALID
        val data = AgentJson.objectOf(AgentJson.parse(dataText)) ?: return AgentFrame.INVALID
        if (event !in AgentEvent.ALL) return AgentFrame(AgentFrameResult.UNKNOWN_EVENT, event, id, data)
        if (AgentJson.has(data, "seq") && AgentJson.intOf(data["seq"]) != id) return AgentFrame.INVALID
        return if (valid(event, data)) AgentFrame(AgentFrameResult.OK, event, id, data) else AgentFrame.INVALID
    }

    private fun valid(
        event: String,
        d: JsonObject,
    ): Boolean =
        when (event) {
            AgentEvent.META -> {
                meta(d)
            }

            AgentEvent.TEXT_DELTA -> {
                AgentJson.exactKeys(d, listOf("seq", "delta")) && AgentJson.seq(d["seq"]) &&
                    AgentJson.str(d["delta"], 1)
            }

            AgentEvent.TOOL_STATUS -> {
                toolStatus(d)
            }

            AgentEvent.CARD -> {
                AgentCardDecoder.envelopeValid(d)
            }

            AgentEvent.SUGGESTIONS -> {
                AgentJson.exactKeys(d, listOf("items")) &&
                    AgentJson.arrayAll(d["items"], 0, SUGGESTIONS_MAX) { AgentCardDecoder.quickReply(it) }
            }

            AgentEvent.ERROR -> {
                errorValid(d)
            }

            AgentEvent.DONE -> {
                AgentJson.exactKeys(d, listOf("finish_reason", "quota_left")) &&
                    AgentJson.oneOf(d["finish_reason"], FINISH_REASONS) &&
                    AgentJson.int32(d["quota_left"], 0)
            }

            else -> {
                false
            }
        }

    private fun meta(d: JsonObject): Boolean =
        AgentJson.exactKeys(
            d,
            listOf("session_id", "run_id", "message_id", "prompt_version", "model_label", "ai_label"),
            listOf("duplicate"),
        ) &&
            AgentJson.entityId(d["session_id"]) &&
            AgentJson.entityId(d["run_id"]) &&
            AgentJson.entityId(d["message_id"]) &&
            AgentJson.str(d["prompt_version"], 1) &&
            AgentJson.str(d["model_label"], 1) &&
            AgentJson.str(d["ai_label"], 1) &&
            (!AgentJson.has(d, "duplicate") || AgentJson.bool(d["duplicate"]))

    private fun toolStatus(d: JsonObject): Boolean =
        AgentJson.exactKeys(d, listOf("seq", "tool", "phase", "display_text")) &&
            AgentJson.seq(d["seq"]) &&
            AgentJson.str(d["tool"], 1) &&
            AgentJson.oneOf(d["phase"], listOf("start", "end", "failed")) &&
            AgentJson.str(d["display_text"], 1)

    @Suppress("ReturnCount") // 外壳、类型、if-then 依次判定
    private fun errorValid(d: JsonObject): Boolean {
        if (!AgentJson.exactKeys(d, listOf("code", "msg", "retryable", "fallback"), listOf("fallback_q"))) return false
        if (!AgentJson.int32(d["code"], CODE_MIN, CODE_MAX) || !AgentJson.str(d["msg"], 1) || !AgentJson.bool(d["retryable"])) return false
        if (!AgentJson.strOrNull(d["fallback"], 1)) return false
        if (AgentJson.has(d, "fallback_q")) {
            if (!AgentJson.strOrNull(d["fallback_q"], 1)) return false
            // if fallback_q 是字符串 then fallback 必须是 search_page。
            if (AgentJson.stringOf(d["fallback_q"]) != null && AgentJson.stringOf(d["fallback"]) != "search_page") return false
        }
        return true
    }
}

// ---------------------------------------------------------------- 卡片

object AgentCardType {
    const val PRODUCT_LIST = "product_list"
    const val REBATE_QUOTE = "rebate_quote"
    const val ORDER_STATUS = "order_status"
    const val CLAIM_DRAFT = "claim_draft"
    const val HANDOFF = "handoff"
    const val AUTH_REQUIRED = "auth_required"
    const val NOTICE = "notice"
    const val RULE_REF = "rule_ref"
    const val PAGE_GUIDE = "page_guide"
    const val EARNINGS_SUMMARY = "earnings_summary"
    val REGISTERED: Set<String> =
        setOf(
            PRODUCT_LIST,
            REBATE_QUOTE,
            ORDER_STATUS,
            CLAIM_DRAFT,
            HANDOFF,
            AUTH_REQUIRED,
            NOTICE,
            RULE_REF,
            PAGE_GUIDE,
            EARNINGS_SUMMARY,
        )

    /** 本端支持的最高 schema_version（本任务全部为 1，G09）。 */
    const val MAX_SCHEMA_VERSION = 1L
}

object AgentCardKind {
    const val KNOWN = "known"
    const val FALLBACK = "fallback"

    /** 外壳不合（Reducer 丢弃整帧；history 来源同样不显示）。 */
    const val INVALID = "invalid"
}

object AgentCardOrigin {
    const val STREAM = "stream"
    const val HISTORY = "history"
}

object EarningsShape {
    const val LIVE = "live"
    const val HISTORY = "history"
    const val FALLBACK = "fallback"
}

/**
 * 一张卡的解码结果（不可变；视图只展示它）。[data]：known 卡的内层 data（历史形状的收益卡只保留 as_of 与 actions，金额字段不保留，G13）；
 * fallback 为空对象。[blockedRoute]：page_guide 被客户端拦下且 route 满足路由名正则时卡里的 route（URL 一类为 null，G10）。
 */
data class AgentCard(
    val kind: String,
    val type: String,
    val cardId: String,
    val fallbackText: String,
    val blockedRoute: String?,
    val shape: String?,
    val origin: String,
    val data: JsonObject,
) {
    val known: Boolean get() = kind == AgentCardKind.KNOWN

    fun json(): JsonObject =
        JsonObject(
            buildMap {
                put("kindRaw", JsonPrimitive(kind))
                put("type", JsonPrimitive(type))
                put("cardId", JsonPrimitive(cardId))
                put("fallbackText", if (kind == AgentCardKind.FALLBACK) JsonPrimitive(fallbackText) else JsonNull)
                put("blockedRoute", blockedRoute?.let(::JsonPrimitive) ?: JsonNull)
                shape?.let { put("shapeRaw", JsonPrimitive(it)) }
            },
        )

    /** 收益卡的实时形状（只有它带金额）。 */
    val liveEarnings: Boolean get() = known && type == AgentCardType.EARNINGS_SUMMARY && shape == EarningsShape.LIVE
}

/** page_guide 的判定（G09、G10）：[ok] = 画按钮；[blockedRoute] = 被拦下且 route 满足路由名正则（才上报 route_entry_blocked，G10）。 */
data class PageGuideCheck(
    val ok: Boolean,
    val route: String?,
    val blockedRoute: String?,
) {
    companion object {
        private val GUIDE_KEY = Regex("^agent\\.guide\\.[A-Z][A-Za-z0-9]*$")

        /**
         * data 只允许 route、text_key 两个键；route 满足路由名正则且是包内 agent_guide=true 的路由；text_key 等于 agent.guide.<route>；
         * schema_version 不高于本端支持。任一不满足 → 不画按钮（G09、G10）。客户端不读服务端开关与名单。
         */
        fun of(
            data: JsonObject?,
            schemaVersion: Long,
            routes: Map<String, RouteSpec>,
        ): PageGuideCheck {
            data ?: return PageGuideCheck(false, null, null)
            val route = AgentJson.stringOf(data["route"])?.takeIf { AgentJson.routeName(data["route"]) }
            val key = AgentJson.stringOf(data["text_key"])
            val shapeOk =
                schemaVersion in 1..AgentCardType.MAX_SCHEMA_VERSION &&
                    AgentJson.exactKeys(data, listOf("route", "text_key")) &&
                    route != null &&
                    key != null &&
                    GUIDE_KEY.matches(key)
            val spec = route?.let(routes::get)
            val ok = shapeOk && spec != null && spec.agentGuide && !spec.debugOnly && key == "$GUIDE_PREFIX$route"
            return if (ok) PageGuideCheck(true, route, null) else PageGuideCheck(false, route, route)
        }

        const val GUIDE_PREFIX = "agent.guide."
    }
}

@Suppress("TooManyFunctions") // 每种卡一个判定
object AgentCardDecoder {
    private const val PRODUCTS_MAX = 8
    private const val PRODUCT_KEY_MIN = 3
    private const val PRODUCT_KEY_MAX = 128
    private const val ITEM_REF_MAX = 1024
    private const val RESUME_LINK_MAX = 64
    private const val EXCERPT_MAX = 200

    /**
     * 向量与视图共用：卡片外壳（`{seq, card_id, type, schema_version, data, fallback_text}`）→
     * `{kindRaw, type, cardId, fallbackText, blockedRoute, shapeRaw?}`。
     */
    fun decode(
        card: JsonObject,
        origin: String,
        routes: Map<String, RouteSpec>,
    ): JsonObject = parse(card, origin, routes).json()

    /** 外壳校验（additionalProperties:false；内层 data 只要求是对象）。 */
    fun envelopeValid(c: JsonObject): Boolean =
        AgentJson.exactKeys(c, listOf("seq", "card_id", "type", "schema_version", "data", "fallback_text")) &&
            AgentJson.seq(c["seq"]) &&
            AgentJson.cardId(c["card_id"]) &&
            AgentJson.str(c["type"], 1) &&
            AgentJson.int32(c["schema_version"], 1) &&
            c["data"] is JsonObject &&
            AgentJson.str(c["fallback_text"], 1)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 外壳、page_guide、未注册、收益卡、其余依次返回
    fun parse(
        card: JsonObject,
        origin: String,
        routes: Map<String, RouteSpec>,
    ): AgentCard {
        if (!envelopeValid(card)) {
            return AgentCard(
                AgentCardKind.INVALID,
                AgentJson.stringOf(card["type"]).orEmpty(),
                AgentJson.stringOf(card["card_id"]).orEmpty(),
                "",
                null,
                null,
                origin,
                AgentJson.EMPTY,
            )
        }
        val type = AgentJson.stringOf(card["type"]).orEmpty()
        val cardId = AgentJson.stringOf(card["card_id"]).orEmpty()
        val fallbackText = AgentJson.stringOf(card["fallback_text"]).orEmpty()
        val version = AgentJson.intOf(card["schema_version"]) ?: 1L
        val data = card["data"] as JsonObject

        fun fallback(blocked: String?) =
            AgentCard(AgentCardKind.FALLBACK, type, cardId, fallbackText, blocked, null, origin, AgentJson.EMPTY)

        fun known(
            kept: JsonObject,
            shape: String? = null,
        ) = AgentCard(AgentCardKind.KNOWN, type, cardId, fallbackText, null, shape, origin, kept)

        if (type == AgentCardType.PAGE_GUIDE) {
            val check = PageGuideCheck.of(data, version, routes)
            return if (check.ok) known(data) else fallback(check.blockedRoute)
        }
        if (type !in AgentCardType.REGISTERED || version > AgentCardType.MAX_SCHEMA_VERSION) return fallback(null)
        if (type == AgentCardType.EARNINGS_SUMMARY) {
            val live = earningsLive(data)
            if (!live && !earningsHistory(data)) return fallback(null)
            // history 来源一律按历史形状（即便误带了金额字段也不保留，G13）；SSE 来源按实际形状
            val historyShape = origin == AgentCardOrigin.HISTORY || !live
            return if (historyShape) known(historyOnly(data), EarningsShape.HISTORY) else known(data, EarningsShape.LIVE)
        }
        return if (dataValid(type, data)) known(data) else fallback(null)
    }

    private fun historyOnly(data: JsonObject): JsonObject =
        JsonObject(linkedMapOf("as_of" to (data["as_of"] ?: JsonNull), "actions" to (data["actions"] ?: JsonNull)))

    fun quickReply(v: JsonElement?): Boolean {
        val o = AgentJson.objectOf(v) ?: return false
        return AgentJson.exactKeys(o, listOf("text", "send_text")) && AgentJson.str(o["text"], 1) && AgentJson.str(o["send_text"], 1)
    }

    @Suppress("CyclomaticComplexMethod") // 每种已注册卡一个分支
    private fun dataValid(
        type: String,
        d: JsonObject,
    ): Boolean =
        when (type) {
            AgentCardType.PRODUCT_LIST -> {
                AgentJson.exactKeys(d, listOf("result_set_id", "items", "layout")) &&
                    AgentJson.str(d["result_set_id"], 1) &&
                    AgentJson.arrayAll(d["items"], 1, PRODUCTS_MAX) { productCard(it) } &&
                    AgentJson.oneOf(d["layout"], listOf("list", "grid"))
            }

            AgentCardType.REBATE_QUOTE -> {
                AgentJson.exactKeys(d, listOf("product", "material", "tlj_kind")) &&
                    productCard(d["product"]) &&
                    (AgentJson.isNull(d["material"]) || material(d["material"])) &&
                    AgentJson.oneOf(d["tlj_kind"], listOf("ours", "brand_open", "third_party", "unknown"))
            }

            AgentCardType.ORDER_STATUS -> {
                orderStatus(d)
            }

            AgentCardType.CLAIM_DRAFT -> {
                claimDraft(d)
            }

            AgentCardType.HANDOFF -> {
                AgentJson.exactKeys(d, listOf("entry", "summary")) && AgentJson.str(d["entry"], 1) && AgentJson.str(d["summary"], 1)
            }

            AgentCardType.AUTH_REQUIRED -> {
                authRequired(d)
            }

            AgentCardType.NOTICE -> {
                AgentJson.exactKeys(d, listOf("level", "text_key", "actions")) &&
                    AgentJson.oneOf(d["level"], listOf("info", "warn")) &&
                    AgentJson.textKey(d["text_key"]) &&
                    AgentJson.arrayAll(d["actions"]) { quickReply(it) }
            }

            AgentCardType.RULE_REF -> {
                ruleRef(d)
            }

            else -> {
                false
            }
        }

    private fun authRequired(d: JsonObject): Boolean =
        AgentJson.exactKeys(d, listOf("platform", "reason", "resume_link_id")) &&
            (AgentJson.isNull(d["platform"]) || AgentJson.platform(d["platform"])) &&
            AgentJson.oneOf(d["reason"], listOf("union_auth", "login", "bind_phone")) &&
            AgentJson.strOrNull(d["resume_link_id"], 1, RESUME_LINK_MAX) &&
            (AgentJson.stringOf(d["reason"]) != "union_auth" || AgentJson.platform(d["platform"]))

    private val PRODUCT_KEYS =
        listOf(
            "card_id",
            "product_key",
            "item_ref",
            "platform",
            "shop_type",
            "title",
            "image",
            "shop_name",
            "price_fen",
            "coupon_fen",
            "final_price_fen",
            "est_net_price_fen",
            "rebate_min_fen",
            "rebate_max_fen",
            "rebate_basis",
            "benefit_tags",
            "match_tag",
            "spec_text",
            "is_presale",
            "tlj",
            "link_id",
            "cta",
            "quoted_at",
            "stale",
            "age_sec",
            "source",
            "disclaimer_keys",
            "ad_label",
            "availability",
        )

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition") // 契约 product_card 逐字段
    private fun productCard(v: JsonElement?): Boolean {
        val p = AgentJson.objectOf(v) ?: return false
        if (!AgentJson.exactKeys(p, PRODUCT_KEYS)) return false
        val identity =
            AgentJson.cardId(p["card_id"]) &&
                AgentJson.str(p["product_key"], PRODUCT_KEY_MIN, PRODUCT_KEY_MAX) &&
                AgentJson.strOrNull(p["item_ref"], 1, ITEM_REF_MAX) &&
                AgentJson.platform(p["platform"]) &&
                AgentJson.strOrNull(p["shop_type"]) &&
                AgentJson.strOrNull(p["title"]) &&
                AgentJson.strOrNull(p["image"]) &&
                AgentJson.strOrNull(p["shop_name"])
        val prices =
            AgentJson.int(p["price_fen"]) &&
                AgentJson.int(p["coupon_fen"]) &&
                AgentJson.int(p["final_price_fen"]) &&
                AgentJson.intOrNull(p["est_net_price_fen"]) &&
                AgentJson.intOrNull(p["rebate_min_fen"]) &&
                AgentJson.intOrNull(p["rebate_max_fen"]) &&
                AgentJson.oneOf(p["rebate_basis"], listOf("normal", "price_compare_risk", "amount_unknown", "login_required"))
        val display =
            AgentJson.arrayAll(p["benefit_tags"]) { AgentJson.str(it, 1) } &&
                AgentJson.oneOf(p["match_tag"], listOf("matched", "relaxed", "spec_unconfirmed")) &&
                AgentJson.strOrNull(p["spec_text"]) &&
                AgentJson.bool(p["is_presale"]) &&
                (AgentJson.isNull(p["tlj"]) || tlj(p["tlj"])) &&
                AgentJson.entityId(p["link_id"]) &&
                cta(p["cta"]) &&
                AgentJson.timestamp(p["quoted_at"]) &&
                AgentJson.bool(p["stale"]) &&
                AgentJson.int32(p["age_sec"], 0) &&
                AgentJson.oneOf(p["source"], listOf("taobao_union", "jd_union", "pdd_union")) &&
                disclaimers(p["disclaimer_keys"]) &&
                AgentJson.strOrNull(p["ad_label"]) &&
                AgentJson.oneOf(
                    p["availability"],
                    listOf("ok", "off_shelf", "coupon_gone", "ref_expired", "price_unavailable", "unknown"),
                )
        if (!identity || !prices || !display) return false
        val basis = AgentJson.stringOf(p["rebate_basis"])
        if (basis == "normal" || basis == "price_compare_risk") {
            if (!AgentJson.int(p["rebate_min_fen"]) || !AgentJson.int(p["rebate_max_fen"])) return false
        } else if (!AgentJson.isNull(p["rebate_min_fen"]) || !AgentJson.isNull(p["rebate_max_fen"])) {
            return false
        }
        val tags = AgentJson.arrayOf(p["benefit_tags"]).orEmpty().map { AgentJson.stringOf(it) }
        val ctaKey = AgentJson.stringOf(AgentJson.objectOf(p["cta"])?.get("text_key")).orEmpty()
        return !((TAOLIJIN in tags || ctaKey.startsWith(CLAIM_TLJ)) && AgentJson.isNull(p["tlj"]))
    }

    private const val TAOLIJIN = "taolijin"
    private const val CLAIM_TLJ = "claim_tlj"

    private fun tlj(v: JsonElement?): Boolean {
        val t = AgentJson.objectOf(v) ?: return false
        return AgentJson.exactKeys(t, listOf("amount_fen", "remain", "kind")) &&
            AgentJson.int(t["amount_fen"]) &&
            AgentJson.int32(t["remain"], 0) &&
            AgentJson.oneOf(t["kind"], listOf("ours", "brand_open", "third_party", "unknown"))
    }

    private fun cta(v: JsonElement?): Boolean {
        val c = AgentJson.objectOf(v) ?: return false
        return AgentJson.exactKeys(c, listOf("text_key")) && AgentJson.textKey(c["text_key"])
    }

    private fun disclaimers(v: JsonElement?): Boolean {
        if (!AgentJson.arrayAll(v, 1) { AgentJson.str(it, 1) }) return false
        val keys = AgentJson.arrayOf(v).orEmpty().map { AgentJson.stringOf(it) }
        return "price_basis" in keys || "price_basis.general" in keys
    }

    private fun material(v: JsonElement?): Boolean {
        val m = AgentJson.objectOf(v) ?: return false
        return AgentJson.exactKeys(
            m,
            listOf("title_hint", "spec", "claimed_price_fen", "claimed_unit_price_fen", "conditions", "benefits", "tpwd"),
        ) &&
            AgentJson.strOrNull(m["title_hint"]) &&
            AgentJson.strOrNull(m["spec"]) &&
            AgentJson.intOrNull(m["claimed_price_fen"]) &&
            AgentJson.intOrNull(m["claimed_unit_price_fen"]) &&
            AgentJson.arrayAll(m["conditions"]) { AgentJson.str(it, 1) } &&
            AgentJson.arrayAll(m["benefits"]) { AgentJson.str(it, 1) } &&
            AgentJson.strOrNull(m["tpwd"])
    }

    private val ORDER_STATUSES =
        listOf(
            "PAID",
            "DEPOSIT_PAID",
            "WAITING",
            "CREDITING",
            "REVIEWING",
            "RIGHTS_PENDING",
            "CREDITED",
            "CREDITED_PART_CLAWED",
            "NO_REBATE",
            "INVALID",
            "CLAWED_BACK",
        )
    private val ORDER_REASONS =
        listOf(
            "REFUND",
            "RIGHTS",
            "PUNISH",
            "PRESALE_UNPAID",
            "COMMISSION_ZERO",
            "OTHER",
            "BLACKLIST",
            "PART_REFUND",
            "PRICE_COMPARE",
            "PRICE_PROTECT",
            "SETTLE_DIFF",
            "NOT_TRACKED",
            "EXPIRED_CLICK",
            "OTHER_TLJ",
            "RELATION_INVALID",
            "CANCELLED",
        )

    private fun orderStatus(d: JsonObject): Boolean =
        AgentJson.exactKeys(
            d,
            listOf(
                "order_id",
                "platform",
                "title",
                "is_other_product",
                "display_status",
                "reason",
                "est_rebate_fen",
                "expected_credit_period",
            ),
        ) &&
            AgentJson.entityId(d["order_id"]) &&
            AgentJson.platform(d["platform"]) &&
            AgentJson.strOrNull(d["title"]) &&
            AgentJson.bool(d["is_other_product"]) &&
            AgentJson.oneOf(d["display_status"], ORDER_STATUSES) &&
            (AgentJson.isNull(d["reason"]) || AgentJson.oneOf(d["reason"], ORDER_REASONS)) &&
            AgentJson.intOrNull(d["est_rebate_fen"]) &&
            AgentJson.yearMonthOrNull(d["expected_credit_period"]) &&
            (AgentJson.boolOf(d["is_other_product"]) != true || AgentJson.isNull(d["title"]))

    @Suppress("ReturnCount") // 外壳、动作依次判定
    private fun claimDraft(d: JsonObject): Boolean {
        if (!AgentJson.exactKeys(d, listOf("platform", "order_no", "click_times", "evidence_summary", "submit_action"))) return false
        val action = AgentJson.objectOf(d["submit_action"]) ?: return false
        return AgentJson.platform(d["platform"]) &&
            AgentJson.str(d["order_no"], 1) &&
            AgentJson.arrayAll(d["click_times"]) { AgentJson.timestamp(it) } &&
            AgentJson.isNull(d["evidence_summary"]) &&
            AgentJson.exactKeys(action, listOf("text_key", "action")) &&
            AgentJson.textKey(action["text_key"]) &&
            AgentJson.stringOf(action["action"]) == "submit_claim"
    }

    @Suppress("ReturnCount") // 外壳、动作依次判定
    private fun ruleRef(d: JsonObject): Boolean {
        if (!AgentJson.exactKeys(d, listOf("chunk_id", "title", "excerpt", "published_version", "action"))) return false
        val action = AgentJson.objectOf(d["action"]) ?: return false
        return AgentJson.entityId(d["chunk_id"]) &&
            AgentJson.str(d["title"], 1) &&
            AgentJson.str(d["excerpt"], 1, EXCERPT_MAX) &&
            AgentJson.str(d["published_version"], 1) &&
            AgentJson.exactKeys(action, listOf("route"), listOf("params")) &&
            AgentJson.routeName(action["route"]) &&
            (!AgentJson.has(action, "params") || action["params"] is JsonObject)
    }

    /** 收益卡 actions 恰好两项且依次为 {Wallet, agent.earnings.open_wallet}、{WithdrawRecords, agent.earnings.open_records}（brief §0 第 1 条）。 */
    @Suppress("ReturnCount") // 逐项不合即 false
    private fun earningsActions(v: JsonElement?): Boolean {
        val a = AgentJson.arrayOf(v) ?: return false
        if (a.size != 2) return false
        val w = AgentJson.objectOf(a[0]) ?: return false
        val r = AgentJson.objectOf(a[1]) ?: return false
        return AgentJson.exactKeys(w, listOf("route", "text_key")) &&
            AgentJson.stringOf(w["route"]) == EARNINGS_WALLET &&
            AgentJson.stringOf(w["text_key"]) == "agent.earnings.open_wallet" &&
            AgentJson.exactKeys(r, listOf("route", "text_key")) &&
            AgentJson.stringOf(r["route"]) == EARNINGS_RECORDS &&
            AgentJson.stringOf(r["text_key"]) == "agent.earnings.open_records"
    }

    const val EARNINGS_WALLET = "Wallet"
    const val EARNINGS_RECORDS = "WithdrawRecords"

    @Suppress("ReturnCount") // 外壳、提现摘要依次判定
    fun earningsLive(d: JsonObject): Boolean {
        val keys =
            listOf(
                "as_of",
                "withdrawable_fen",
                "estimated_total_fen",
                "next_credit_period",
                "credit_overdue",
                "latest_withdrawal",
                "actions",
            )
        if (!AgentJson.exactKeys(d, keys)) return false
        val latest = d["latest_withdrawal"]
        val latestOk =
            AgentJson.isNull(latest) ||
                AgentJson.objectOf(latest)?.let { l ->
                    AgentJson.exactKeys(l, listOf("title_key", "amount_fen")) && AgentJson.textKey(l["title_key"]) &&
                        AgentJson.int(l["amount_fen"])
                } == true
        return AgentJson.timestamp(d["as_of"]) &&
            AgentJson.int(d["withdrawable_fen"]) &&
            AgentJson.int(d["estimated_total_fen"]) &&
            AgentJson.yearMonthOrNull(d["next_credit_period"]) &&
            AgentJson.bool(d["credit_overdue"]) &&
            latestOk &&
            earningsActions(d["actions"])
    }

    fun earningsHistory(d: JsonObject): Boolean =
        AgentJson.exactKeys(d, listOf("as_of", "actions")) && AgentJson.timestamp(d["as_of"]) && earningsActions(d["actions"])
}
