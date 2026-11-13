package com.couli.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * FU-AND-AGENT-UNKNOWN-FIELDS（D10-1287，C2①）测试共用样例：十种已注册卡片各一份合锁定契约（a89c1ec）的 data，取自 specs/agent-vectors.json 的
 * cards 合法用例与锁内 fixture；外加加键、删键、改数组项的小工具与卡片外壳、帧行的构造。只在测试里用，不改任何已有样例。
 *
 * 依据：03 §7.2（2026-11-12 补）——同一 schema_version 下 data 里本端不认识的可选字段忽略、照常渲染；嵌套对象里的未知键同样忽略（任务书 §1）；
 * 缺必需字段、类型不符仍整卡 fallback_text；page_guide 卡与收益卡按钮的严格校验不变（§7.4）。
 */
@Suppress("TooManyFunctions") // 每种卡一份样例 + 构造小工具
internal object UnknownFieldsSamples {
    const val FALLBACK = "兜底文字：请到对应页面查看。"

    fun json(text: String): JsonObject = AgentJson.objectOf(AgentJson.parse(text)) ?: error("样例不是 JSON 对象：$text")

    /** 加键（同名键覆盖）。 */
    fun JsonObject.adding(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)

    /** 加一组键（同名键覆盖）。 */
    fun JsonObject.addingAll(extra: Map<String, JsonElement>): JsonObject = JsonObject(this + extra)

    /** 删键；样例里本来没有这个键时直接报错（免得删键用例白测）。 */
    fun JsonObject.dropping(key: String): JsonObject {
        check(key in this) { "样例里没有 $key" }
        return JsonObject(this - key)
    }

    /** 数组 [key] 的第 [index] 项（对象）换成 [block] 的结果。 */
    fun JsonObject.patchItem(
        key: String,
        index: Int,
        block: (JsonObject) -> JsonObject,
    ): JsonObject {
        val items = (this[key] as JsonArray).toMutableList()
        items[index] = block(items[index] as JsonObject)
        return adding(key to JsonArray(items))
    }

    /** 对象字段 [key] 换成 [block] 的结果。 */
    fun JsonObject.patchObject(
        key: String,
        block: (JsonObject) -> JsonObject,
    ): JsonObject = adding(key to block(this[key] as JsonObject))

    /** 本端（锁定契约）不认识的可选字段：字符串、数字、null、数组、带嵌套的对象各一种取值。 */
    val UNKNOWN: Map<String, JsonElement> =
        linkedMapOf(
            "future_hint" to JsonPrimitive("新字段"),
            "future_rank" to JsonPrimitive(3),
            "future_null" to JsonNull,
            "future_list" to JsonArray(listOf(JsonPrimitive("a"))),
            "future_detail" to json("""{"level":2,"inner":{"x":null}}"""),
        )

    /** 放进嵌套对象（商品、cta、tlj、素材、动作、提现摘要……）里的未知键。 */
    val NESTED_UNKNOWN: Map<String, JsonElement> =
        linkedMapOf(
            "future_nested" to json("""{"k":"v"}"""),
            "future_flag" to JsonPrimitive(true),
        )

    fun product(cardId: String = "c2"): JsonObject =
        json(
            """
            {"card_id":"$cardId","product_key":"jd:demo-0004","item_ref":"demo-item-ref-0004","platform":"jd","shop_type":null,
             "title":"演示纯牛奶 250ml×24 盒（样例 4）","image":"https://example.com/images/demo-0004.png","shop_name":"演示旗舰店",
             "price_fen":6990,"coupon_fen":1000,"final_price_fen":5990,"est_net_price_fen":5790,"rebate_min_fen":200,"rebate_max_fen":200,
             "rebate_basis":"normal","benefit_tags":["有券"],"match_tag":"matched","spec_text":"250ml×24","is_presale":false,"tlj":null,
             "link_id":"019a0000-0000-7000-8000-000000000304","cta":{"text_key":"btn.buy.coupon"},"quoted_at":"2026-10-06T10:00:00+08:00",
             "stale":false,"age_sec":3,"source":"jd_union",
             "disclaimer_keys":["price_basis","rebate_estimate","agent.disclaimer.commission"],"ad_label":null,"availability":"ok"}
            """.trimIndent(),
        )

    fun productList(items: List<JsonObject> = listOf(product("c2"))): JsonObject =
        JsonObject(
            linkedMapOf(
                "result_set_id" to JsonPrimitive("demo-result-set-0003"),
                "items" to JsonArray(items),
                "layout" to JsonPrimitive("list"),
            ),
        )

    fun material(): JsonObject =
        json(
            """
            {"title_hint":"演示纯牛奶整箱","spec":"250ml×24","claimed_price_fen":5590,"claimed_unit_price_fen":233,
             "conditions":["88vip"],"benefits":["coupon"],"tpwd":null}
            """.trimIndent(),
        )

    fun rebateQuote(): JsonObject =
        JsonObject(linkedMapOf("product" to product("c5"), "material" to material(), "tlj_kind" to JsonPrimitive("unknown")))

    fun orderStatus(): JsonObject =
        json(
            """
            {"order_id":"019a0000-0000-7000-8000-000000000501","platform":"jd","title":"演示纯牛奶","is_other_product":false,
             "display_status":"WAITING","reason":null,"est_rebate_fen":200,"expected_credit_period":"2026-11"}
            """.trimIndent(),
        )

    fun claimDraft(): JsonObject =
        json(
            """
            {"platform":"jd","order_no":"123456789012","click_times":["2026-10-06T10:00:00+08:00"],"evidence_summary":null,
             "submit_action":{"text_key":"agent.rule.not_found","action":"submit_claim"}}
            """.trimIndent(),
        )

    fun handoff(): JsonObject = json("""{"entry":"wecom","summary":"没有找到相关规则，可以联系客服确认。"}""")

    fun authRequired(): JsonObject = json("""{"platform":null,"reason":"login","resume_link_id":null}""")

    fun notice(): JsonObject =
        json("""{"level":"warn","text_key":"agent.notice.no_result","actions":[{"text":"换个平台看看","send_text":"换个平台看看"}]}""")

    fun ruleRef(): JsonObject =
        json(
            """
            {"chunk_id":"019a0000-0000-7000-8000-000000000401","title":"演示规则：返利什么时候到账",
             "excerpt":"演示摘要：订单确认收货并结算后，返利按规则进入账户。","published_version":"demo-v1",
             "action":{"route":"Help","params":{"article_id":"demo-article-0001"}}}
            """.trimIndent(),
        )

    fun pageGuide(): JsonObject = json("""{"route":"WithdrawRecords","text_key":"agent.guide.WithdrawRecords"}""")

    private const val WALLET_ACTION = """{"route":"Wallet","text_key":"agent.earnings.open_wallet"}"""
    private const val RECORDS_ACTION = """{"route":"WithdrawRecords","text_key":"agent.earnings.open_records"}"""
    private const val ACTIONS = "[$WALLET_ACTION,$RECORDS_ACTION]"

    fun earningsLive(): JsonObject =
        json(
            """
            {"as_of":"2026-10-06T10:00:00+08:00","withdrawable_fen":1000,"estimated_total_fen":5678,"next_credit_period":"2026-11",
             "credit_overdue":false,"latest_withdrawal":{"title_key":"withdrawal_status.PENDING_REVIEW.label","amount_fen":2000},
             "actions":$ACTIONS}
            """.trimIndent(),
        )

    fun earningsHistory(): JsonObject = json("""{"as_of":"2026-10-06T10:00:00+08:00","actions":$ACTIONS}""")

    /** 除 page_guide 外九种已注册类型各一份合法 data（收益卡取实时形状）。 */
    fun lenientTypes(): Map<String, JsonObject> =
        linkedMapOf(
            AgentCardType.PRODUCT_LIST to productList(),
            AgentCardType.REBATE_QUOTE to rebateQuote(),
            AgentCardType.ORDER_STATUS to orderStatus(),
            AgentCardType.CLAIM_DRAFT to claimDraft(),
            AgentCardType.HANDOFF to handoff(),
            AgentCardType.AUTH_REQUIRED to authRequired(),
            AgentCardType.NOTICE to notice(),
            AgentCardType.RULE_REF to ruleRef(),
            AgentCardType.EARNINGS_SUMMARY to earningsLive(),
        )

    /** SSE 卡片外壳 `{seq, card_id, type, schema_version, data, fallback_text}`。 */
    @Suppress("LongParameterList") // 外壳字段逐项
    fun envelope(
        type: String,
        data: JsonObject,
        cardId: String = "c1",
        seq: Int = 2,
        version: Int = 1,
        fallback: String = FALLBACK,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "seq" to JsonPrimitive(seq),
                "card_id" to JsonPrimitive(cardId),
                "type" to JsonPrimitive(type),
                "schema_version" to JsonPrimitive(version),
                "data" to data,
                "fallback_text" to JsonPrimitive(fallback),
            ),
        )

    /** 历史接口里的卡（没有 seq）。 */
    fun historyEnvelope(
        type: String,
        data: JsonObject,
        version: Int = 1,
    ): JsonObject = JsonObject(envelope(type, data, version = version) - "seq")

    /** 一行 NDJSON 的 card 帧（id 与 seq 相同）。 */
    fun cardLine(envelope: JsonObject): String = """{"event":"card","id":${envelope["seq"]},"data":$envelope}"""

    fun doneLine(id: Int): String = """{"event":"done","id":$id,"data":{"finish_reason":"stop","quota_left":29}}"""

    /**
     * 主干后端实际下发的商品卡形态：在锁内商品卡上补 `price_spec_hint`（放在 spec_text 之后，取值 price_from / spec_uncertain / null）。
     * 出处：rebate-platform origin/main d85c2501 的 contracts/agent-stream.schema.json:521-525（product_card 的可选字段，字符串或 null，不是对象）、
     * apps/api/src/modules/agent/tools/search/card.ts:159（搜索卡照抄目录卡取值，缺省为 null）、
     * apps/api/src/modules/agent/tools/quote/index.ts:250-253（返利卡同）。
     */
    fun withPriceSpecHint(
        item: JsonObject,
        hint: String?,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        item.forEach { (k, v) ->
            out[k] = v
            if (k == "spec_text") out["price_spec_hint"] = hint?.let(::JsonPrimitive) ?: JsonNull
        }
        check("price_spec_hint" in out) { "商品卡里没有 spec_text" }
        return JsonObject(out)
    }

    /** 把一行 card 帧里的所有商品卡（product_list.items[*]、rebate_quote.product）补上 price_spec_hint；其他行原样。 */
    fun patchProductsInLine(
        line: String,
        hints: Iterator<String?>,
    ): String {
        val frame = json(line)
        val env = (frame["data"] as? JsonObject)?.takeIf { AgentJson.stringOf(frame["event"]) == AgentEvent.CARD }
        val data = env?.get("data") as? JsonObject ?: AgentJson.EMPTY
        val patched =
            when (env?.let { AgentJson.stringOf(it["type"]) }) {
                AgentCardType.PRODUCT_LIST -> {
                    val items = (data["items"] as JsonArray).map { withPriceSpecHint(it as JsonObject, hints.next()) }
                    data.adding("items" to JsonArray(items))
                }

                AgentCardType.REBATE_QUOTE -> {
                    data.adding("product" to withPriceSpecHint(data["product"] as JsonObject, hints.next()))
                }

                else -> {
                    null
                }
            }
        return if (env == null || patched == null) line else frame.adding("data" to env.adding("data" to patched)).toString()
    }
}
