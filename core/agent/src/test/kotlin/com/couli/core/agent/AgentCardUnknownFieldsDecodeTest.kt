package com.couli.core.agent

import com.couli.core.agent.UnknownFieldsSamples.FALLBACK
import com.couli.core.agent.UnknownFieldsSamples.NESTED_UNKNOWN
import com.couli.core.agent.UnknownFieldsSamples.UNKNOWN
import com.couli.core.agent.UnknownFieldsSamples.adding
import com.couli.core.agent.UnknownFieldsSamples.addingAll
import com.couli.core.agent.UnknownFieldsSamples.dropping
import com.couli.core.agent.UnknownFieldsSamples.envelope
import com.couli.core.agent.UnknownFieldsSamples.historyEnvelope
import com.couli.core.agent.UnknownFieldsSamples.json
import com.couli.core.agent.UnknownFieldsSamples.patchItem
import com.couli.core.agent.UnknownFieldsSamples.patchObject
import com.couli.core.agent.UnknownFieldsSamples.withPriceSpecHint
import com.couli.generated.Routes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-AGENT-UNKNOWN-FIELDS（D10-1287，C2①；03 §7.2 第 872 行、§7.4；docs/changes/20261112 §1.4、§2.14）卡片解码层：
 * - 先红：九种类型（page_guide 除外）的 data 带本端不认识的可选字段、或嵌套对象（商品卡、cta、tlj、素材、动作、提现摘要）里带未知键 → 照常 known；
 *   商品卡带主干的 `price_spec_hint`（字符串或 null）→ 照常 known；历史接口来源同样。
 * - 钉住（现在就绿，实现后也必须绿）：缺必需字段、类型不符 → 整卡 fallback（即使同时带未知字段）；page_guide 多一个键 → 仍 fallback 并带 blockedRoute；
 *   收益卡按钮多一个键 → 仍 fallback；收益卡实时形状缺一个字段不得被当成历史形状；卡片外壳多键 → 整帧 invalid；suggestions 帧的快捷回复多键 → invalid_frame。
 * 只用现有签名（AgentCardDecoder.parse / AgentHistory.card / AgentFrameDecoder.frame / PageGuidePolicy.tap / EarningsCardView.view），没有桩。
 */
@Suppress("TooManyFunctions", "LargeClass") // 每种卡、每条规则一个用例
class AgentCardUnknownFieldsDecodeTest {
    private fun parse(
        type: String,
        data: JsonObject,
        origin: String = AgentCardOrigin.STREAM,
    ): AgentCard = AgentCardDecoder.parse(envelope(type, data), origin, Routes.all)

    /** 照常渲染：known、类型与卡号不变、本来的已知字段原样保留（未知字段留不留不管）。 */
    private fun assertRenders(
        what: String,
        type: String,
        data: JsonObject,
        base: JsonObject,
        origin: String = AgentCardOrigin.STREAM,
    ): AgentCard {
        val card = parse(type, data, origin)
        assertEquals("$what：应照常渲染（known），实际 ${card.kind}", AgentCardKind.KNOWN, card.kind)
        assertEquals(type, card.type)
        assertEquals("c1", card.cardId)
        base.forEach { (k, v) -> assertEquals("$what：已知字段 $k 应原样保留", v, card.data[k]) }
        return card
    }

    private fun assertFallback(
        what: String,
        type: String,
        data: JsonObject,
        origin: String = AgentCardOrigin.STREAM,
    ): AgentCard {
        val card = parse(type, data, origin)
        assertEquals("$what：应整卡 fallback，实际 ${card.kind}", AgentCardKind.FALLBACK, card.kind)
        assertEquals("$what：只显示 fallback_text", FALLBACK, card.fallbackText)
        assertEquals("$what：fallback 不保留任何字段", AgentJson.EMPTY, card.data)
        return card
    }

    // ---------------------------------------------------------------- 基线（绿）：样例本身合锁定契约

    @Test
    fun baselineSamplesDecodeAsKnownToday() {
        UnknownFieldsSamples.lenientTypes().forEach { (type, data) -> assertRenders("基线 $type", type, data, data) }
        assertRenders("基线 page_guide", AgentCardType.PAGE_GUIDE, UnknownFieldsSamples.pageGuide(), UnknownFieldsSamples.pageGuide())
        val history = parse(AgentCardType.EARNINGS_SUMMARY, UnknownFieldsSamples.earningsHistory())
        assertEquals(EarningsShape.HISTORY, history.shape)
    }

    // ---------------------------------------------------------------- 先红：顶层未知可选字段（每种类型一条）

    private fun assertTopLevelUnknownRenders(type: String): AgentCard {
        val base = UnknownFieldsSamples.lenientTypes().getValue(type)
        return assertRenders("$type 顶层带未知字段", type, base.addingAll(UNKNOWN), base)
    }

    @Test
    fun productListTopLevelUnknownFieldsRender() {
        assertTopLevelUnknownRenders(AgentCardType.PRODUCT_LIST)
    }

    @Test
    fun rebateQuoteTopLevelUnknownFieldsRender() {
        assertTopLevelUnknownRenders(AgentCardType.REBATE_QUOTE)
    }

    @Test
    fun orderStatusTopLevelUnknownFieldsRender() {
        // order_status 契约里没有嵌套对象：UNKNOWN 里的 future_detail（值是带嵌套的对象）同时覆盖「未知键的值是对象」
        assertTopLevelUnknownRenders(AgentCardType.ORDER_STATUS)
    }

    @Test
    fun claimDraftTopLevelUnknownFieldsRender() {
        assertTopLevelUnknownRenders(AgentCardType.CLAIM_DRAFT)
    }

    @Test
    fun handoffTopLevelUnknownFieldsRender() {
        // handoff 契约里没有嵌套对象（同上）
        assertTopLevelUnknownRenders(AgentCardType.HANDOFF)
    }

    @Test
    fun authRequiredTopLevelUnknownFieldsRender() {
        // auth_required 契约里没有嵌套对象（同上）
        assertTopLevelUnknownRenders(AgentCardType.AUTH_REQUIRED)
    }

    @Test
    fun noticeTopLevelUnknownFieldsRender() {
        assertTopLevelUnknownRenders(AgentCardType.NOTICE)
    }

    @Test
    fun ruleRefTopLevelUnknownFieldsRender() {
        assertTopLevelUnknownRenders(AgentCardType.RULE_REF)
    }

    @Test
    fun earningsLiveTopLevelUnknownFieldsRenderLiveShape() {
        val card = assertTopLevelUnknownRenders(AgentCardType.EARNINGS_SUMMARY)
        assertEquals("实时形状带未知字段仍按实时形状（带金额）", EarningsShape.LIVE, card.shape)
        assertTrue(card.liveEarnings)
    }

    @Test
    fun earningsHistoryTopLevelUnknownFieldsRenderHistoryShape() {
        val base = UnknownFieldsSamples.earningsHistory()
        val card = assertRenders("收益卡历史形状带未知字段", AgentCardType.EARNINGS_SUMMARY, base.addingAll(UNKNOWN), base)
        assertEquals(EarningsShape.HISTORY, card.shape)
    }

    @Test
    fun historyOriginEarningsWithUnknownFieldsKeepsOnlyHistoryShape() {
        // G13 不变：历史来源一律历史形状、不保留金额字段；多了未知字段也一样
        val live = UnknownFieldsSamples.earningsLive()
        val card = parse(AgentCardType.EARNINGS_SUMMARY, live.addingAll(UNKNOWN), AgentCardOrigin.HISTORY)
        assertEquals(AgentCardKind.KNOWN, card.kind)
        assertEquals(EarningsShape.HISTORY, card.shape)
        assertEquals(setOf("as_of", "actions"), card.data.keys)
    }

    // ---------------------------------------------------------------- 先红：嵌套对象里的未知键

    @Test
    fun productListItemCarriesMainPriceSpecHintAndUnknownKeys() {
        val base = UnknownFieldsSamples.productList(listOf(UnknownFieldsSamples.product("c2"), UnknownFieldsSamples.product("c3")))
        val data =
            base
                .patchItem("items", 0) { withPriceSpecHint(it, "spec_uncertain").addingAll(NESTED_UNKNOWN) }
                .patchItem("items", 1) { withPriceSpecHint(it, null) }
        val card = assertRenders("商品卡带 price_spec_hint 与未知键", AgentCardType.PRODUCT_LIST, data, base.dropping("items"))
        val items = card.data["items"] as JsonArray
        assertEquals("两件商品都在", listOf("c2", "c3"), items.map { AgentJson.stringOf((it as JsonObject)["card_id"]) })
        assertEquals(JsonPrimitive(5990), (items[0] as JsonObject)["final_price_fen"])
    }

    @Test
    fun productListPriceFromHintRenders() {
        val base = UnknownFieldsSamples.productList()
        val data = base.patchItem("items", 0) { withPriceSpecHint(it, "price_from") }
        assertRenders("price_spec_hint=price_from", AgentCardType.PRODUCT_LIST, data, base.dropping("items"))
    }

    @Test
    fun productCtaAndTljUnknownKeysRender() {
        val tlj = json("""{"amount_fen":500,"remain":10,"kind":"ours"}""").addingAll(NESTED_UNKNOWN)
        val base = UnknownFieldsSamples.productList()
        val data =
            base.patchItem("items", 0) { p ->
                p.patchObject("cta") { it.addingAll(NESTED_UNKNOWN) }.adding("tlj" to tlj)
            }
        assertRenders("cta 与 tlj 里带未知键", AgentCardType.PRODUCT_LIST, data, base.dropping("items"))
    }

    @Test
    fun rebateQuoteProductAndMaterialUnknownKeysRender() {
        val base = UnknownFieldsSamples.rebateQuote()
        val data =
            base
                .patchObject("product") { withPriceSpecHint(it, "spec_uncertain").addingAll(NESTED_UNKNOWN) }
                .patchObject("material") { it.addingAll(NESTED_UNKNOWN) }
        val card = assertRenders("返利卡商品与素材里带未知键", AgentCardType.REBATE_QUOTE, data, base.dropping("product").dropping("material"))
        assertEquals(JsonPrimitive("演示纯牛奶整箱"), (card.data["material"] as JsonObject)["title_hint"])
        assertEquals(JsonPrimitive("c5"), (card.data["product"] as JsonObject)["card_id"])
    }

    @Test
    fun claimDraftSubmitActionUnknownKeyRenders() {
        val base = UnknownFieldsSamples.claimDraft()
        val data = base.patchObject("submit_action") { it.addingAll(NESTED_UNKNOWN) }
        val card = assertRenders("找回草稿动作里带未知键", AgentCardType.CLAIM_DRAFT, data, base.dropping("submit_action"))
        assertEquals(JsonPrimitive("submit_claim"), (card.data["submit_action"] as JsonObject)["action"])
    }

    @Test
    fun noticeQuickReplyUnknownKeyRenders() {
        val base = UnknownFieldsSamples.notice()
        val data = base.patchItem("actions", 0) { it.addingAll(NESTED_UNKNOWN) }
        val card = assertRenders("notice 快捷回复里带未知键", AgentCardType.NOTICE, data, base.dropping("actions"))
        assertEquals(1, (card.data["actions"] as JsonArray).size)
    }

    @Test
    fun ruleRefActionUnknownKeyRendersWithSameRouteAndParams() {
        val base = UnknownFieldsSamples.ruleRef()
        val data = base.patchObject("action") { it.addingAll(NESTED_UNKNOWN) }
        val card = assertRenders("规则卡动作里带未知键", AgentCardType.RULE_REF, data, base.dropping("action"))
        val action = card.data["action"] as JsonObject
        assertEquals(JsonPrimitive("Help"), action["route"])
        assertEquals(json("""{"article_id":"demo-article-0001"}"""), action["params"])
    }

    @Test
    fun earningsLatestWithdrawalUnknownKeyRendersLiveShape() {
        val base = UnknownFieldsSamples.earningsLive()
        val data = base.patchObject("latest_withdrawal") { it.addingAll(NESTED_UNKNOWN) }
        val card = assertRenders("收益卡提现摘要里带未知键", AgentCardType.EARNINGS_SUMMARY, data, base.dropping("latest_withdrawal"))
        assertEquals(EarningsShape.LIVE, card.shape)
        val view = EarningsCardView.view(card, amountMasked = false, nowMs = AgentTestKit.START_MS)
        assertTrue("提现摘要照常显示", view.latestWithdrawalShown)
        assertEquals(listOf(EarningsCardView.WALLET, EarningsCardView.RECORDS), view.buttons)
    }

    @Test
    fun historyApiCardWithUnknownFieldsRenders() {
        val base = UnknownFieldsSamples.notice()
        val card = AgentHistory.card(historyEnvelope(AgentCardType.NOTICE, base.addingAll(UNKNOWN)), Routes.all)
        assertNotNull("历史卡外壳合法，不应丢弃", card)
        assertEquals("历史接口来源的卡同样忽略未知字段", AgentCardKind.KNOWN, card!!.kind)
        assertEquals(base["level"], card.data["level"])
    }

    // ---------------------------------------------------------------- 钉住（绿）：缺必需字段 → fallback（带不带未知字段都一样）

    private fun case(
        what: String,
        type: String,
        data: JsonObject,
    ): Triple<String, String, JsonObject> = Triple(what, type, data)

    private fun JsonObject.firstItem(block: (JsonObject) -> JsonObject): JsonObject = patchItem("items", 0, block)

    private fun missingCases(): List<Triple<String, String, JsonObject>> {
        val pl = UnknownFieldsSamples.productList()
        val rq = UnknownFieldsSamples.rebateQuote()
        val cd = UnknownFieldsSamples.claimDraft()
        val rr = UnknownFieldsSamples.ruleRef()
        val nt = UnknownFieldsSamples.notice()
        val el = UnknownFieldsSamples.earningsLive()
        val os = UnknownFieldsSamples.orderStatus()
        return listOf(
            case("product_list 缺 layout", PRODUCT_LIST, pl.dropping("layout")),
            case("product_list 缺 items", PRODUCT_LIST, pl.dropping("items")),
            case("商品卡缺 final_price_fen", PRODUCT_LIST, pl.firstItem { it.dropping("final_price_fen") }),
            case("商品卡缺 link_id", PRODUCT_LIST, pl.firstItem { it.dropping("link_id") }),
            case("商品卡 cta 缺 text_key", PRODUCT_LIST, pl.firstItem { p -> p.patchObject("cta") { it.dropping("text_key") } }),
            case("rebate_quote 缺 tlj_kind", REBATE_QUOTE, rq.dropping("tlj_kind")),
            case("rebate_quote 商品缺 price_fen", REBATE_QUOTE, rq.patchObject("product") { it.dropping("price_fen") }),
            case("rebate_quote 素材缺 conditions", REBATE_QUOTE, rq.patchObject("material") { it.dropping("conditions") }),
            case("order_status 缺 display_status", ORDER_STATUS, os.dropping("display_status")),
            case("order_status 缺 order_id", ORDER_STATUS, os.dropping("order_id")),
            case("claim_draft 缺 order_no", CLAIM_DRAFT, cd.dropping("order_no")),
            case("claim_draft 动作缺 action", CLAIM_DRAFT, cd.patchObject("submit_action") { it.dropping("action") }),
            case("handoff 缺 summary", HANDOFF, UnknownFieldsSamples.handoff().dropping("summary")),
            case("auth_required 缺 reason", AUTH_REQUIRED, UnknownFieldsSamples.authRequired().dropping("reason")),
            case("notice 缺 level", NOTICE, nt.dropping("level")),
            case("notice 快捷回复缺 send_text", NOTICE, nt.patchItem("actions", 0) { it.dropping("send_text") }),
            case("rule_ref 缺 action", RULE_REF, rr.dropping("action")),
            case("rule_ref 动作缺 route", RULE_REF, rr.patchObject("action") { it.dropping("route") }),
            case("rule_ref 缺 excerpt", RULE_REF, rr.dropping("excerpt")),
            case("收益卡缺 as_of", EARNINGS, el.dropping("as_of")),
            case("收益卡提现摘要缺 amount_fen", EARNINGS, el.patchObject("latest_withdrawal") { it.dropping("amount_fen") }),
        )
    }

    @Test
    fun missingRequiredFieldStillFallsBackForEveryType() {
        missingCases().forEach { (what, type, data) -> assertFallback(what, type, data) }
    }

    @Test
    fun missingRequiredFieldPlusUnknownFieldsStillFallsBack() {
        missingCases().forEach { (what, type, data) -> assertFallback("$what（同时带未知字段）", type, data.addingAll(UNKNOWN)) }
    }

    @Test
    fun earningsLiveMissingOneFieldIsNotReadAsHistoryShape() {
        // G13：实时与历史两种形状都不合（例如缺一个金额字段）→ fallback；放宽后不得因为 as_of + actions 齐全就当成历史形状
        val el = UnknownFieldsSamples.earningsLive()
        listOf("credit_overdue", "withdrawable_fen", "estimated_total_fen", "next_credit_period", "latest_withdrawal").forEach { key ->
            assertFallback("收益卡实时形状缺 $key", AgentCardType.EARNINGS_SUMMARY, el.dropping(key))
            assertFallback("收益卡实时形状缺 $key（带未知字段）", AgentCardType.EARNINGS_SUMMARY, el.dropping(key).addingAll(UNKNOWN))
        }
    }

    // ---------------------------------------------------------------- 钉住（绿）：类型不符 → fallback（带不带未知字段都一样）

    private fun mismatchCases(): List<Triple<String, String, JsonObject>> {
        val pl = UnknownFieldsSamples.productList()
        val rq = UnknownFieldsSamples.rebateQuote()
        val el = UnknownFieldsSamples.earningsLive()
        val os = UnknownFieldsSamples.orderStatus()
        return listOf(
            case("layout 是数字", PRODUCT_LIST, pl.adding("layout" to JsonPrimitive(1))),
            case("items 是对象", PRODUCT_LIST, pl.adding("items" to UnknownFieldsSamples.product())),
            case("商品 price_fen 是字符串", PRODUCT_LIST, pl.firstItem { it.adding("price_fen" to JsonPrimitive("6990")) }),
            case("商品 cta.text_key 是数字", PRODUCT_LIST, pl.firstItem { it.adding("cta" to json("""{"text_key":5}""")) }),
            case("商品 tlj 是字符串", PRODUCT_LIST, pl.firstItem { it.adding("tlj" to JsonPrimitive("x")) }),
            case("素材 conditions 是字符串", REBATE_QUOTE, rq.patchObject("material") { it.adding("conditions" to JsonPrimitive("88vip")) }),
            case("is_other_product 是字符串", ORDER_STATUS, os.adding("is_other_product" to JsonPrimitive("false"))),
            case("click_times 是字符串", CLAIM_DRAFT, UnknownFieldsSamples.claimDraft().adding("click_times" to JsonPrimitive("x"))),
            case("handoff entry 是 null", HANDOFF, UnknownFieldsSamples.handoff().adding("entry" to JsonNull)),
            case("auth_required reason 是布尔", AUTH_REQUIRED, UnknownFieldsSamples.authRequired().adding("reason" to JsonPrimitive(true))),
            case("notice level 是数字", NOTICE, UnknownFieldsSamples.notice().adding("level" to JsonPrimitive(5))),
            case("rule_ref title 是数字", RULE_REF, UnknownFieldsSamples.ruleRef().adding("title" to JsonPrimitive(123))),
            case("收益卡 withdrawable_fen 是字符串", EARNINGS, el.adding("withdrawable_fen" to JsonPrimitive("1000"))),
        )
    }

    @Test
    fun typeMismatchStillFallsBackForEveryType() {
        mismatchCases().forEach { (what, type, data) -> assertFallback(what, type, data) }
    }

    @Test
    fun typeMismatchPlusUnknownFieldsStillFallsBack() {
        mismatchCases().forEach { (what, type, data) -> assertFallback("$what（同时带未知字段）", type, data.addingAll(UNKNOWN)) }
    }

    // ---------------------------------------------------------------- 钉住（绿）：page_guide 严格校验不变（§7.4）

    @Test
    fun pageGuideWithAnyExtraKeyStaysBlocked() {
        val base = UnknownFieldsSamples.pageGuide()
        listOf(
            "多 params" to base.adding("params" to AgentJson.EMPTY),
            "多一个未知字段" to base.adding("future_hint" to JsonPrimitive("新字段")),
            "多一组未知字段" to base.addingAll(UNKNOWN),
        ).forEach { (what, data) ->
            val card = assertFallback("page_guide $what", AgentCardType.PAGE_GUIDE, data)
            assertEquals("page_guide $what：被拦的路由照现状带出（route_entry_blocked 用）", "WithdrawRecords", card.blockedRoute)
            val tap = PageGuidePolicy.tap(envelope(AgentCardType.PAGE_GUIDE, data), Routes.all)
            assertEquals("page_guide $what：不画按钮", JsonPrimitive(false), tap["showButton"])
            assertEquals(json("""{"route":"WithdrawRecords","entry":"in_app"}"""), tap["blockedEvent"])
        }
    }

    // ---------------------------------------------------------------- 钉住（绿）：收益卡按钮严格校验不变（§7.4）

    @Test
    fun earningsButtonWithExtraKeyStillFallsBack() {
        val live = UnknownFieldsSamples.earningsLive()
        val history = UnknownFieldsSamples.earningsHistory()
        listOf(
            "实时形状 · 第一个按钮多 params" to live.patchItem("actions", 0) { it.adding("params" to AgentJson.EMPTY) },
            "实时形状 · 第二个按钮多未知键" to live.patchItem("actions", 1) { it.adding("future_hint" to JsonPrimitive("x")) },
            "实时形状 · 顶层带未知字段且按钮多键" to live.addingAll(UNKNOWN).patchItem("actions", 0) { it.addingAll(NESTED_UNKNOWN) },
            "历史形状 · 按钮多未知键" to history.patchItem("actions", 1) { it.addingAll(NESTED_UNKNOWN) },
        ).forEach { (what, data) ->
            listOf(AgentCardOrigin.STREAM, AgentCardOrigin.HISTORY).forEach { origin ->
                val card = assertFallback("收益卡$what（$origin）", AgentCardType.EARNINGS_SUMMARY, data, origin)
                assertNull(card.blockedRoute)
                val view = EarningsCardView.view(card, amountMasked = false, nowMs = AgentTestKit.START_MS)
                assertEquals(EarningsShape.FALLBACK, view.shape)
                assertTrue("收益卡$what：不画按钮", view.buttons.isEmpty())
                assertNull("收益卡$what：不出金额", view.withdrawable)
            }
        }
    }

    // ---------------------------------------------------------------- 钉住（绿）：外壳与非卡片帧不在本任务范围（任务书 §1「不改」）

    @Test
    fun cardEnvelopeExtraKeyStaysInvalid() {
        val env = envelope(AgentCardType.NOTICE, UnknownFieldsSamples.notice()).adding("future_hint" to JsonPrimitive("x"))
        assertEquals(AgentCardKind.INVALID, AgentCardDecoder.parse(env, AgentCardOrigin.STREAM, Routes.all).kind)
        val frame = AgentFrameDecoder.frame(AgentSse.fromNdjsonLine(UnknownFieldsSamples.cardLine(env)))
        assertEquals("卡片外壳多键仍按协议错误丢帧", AgentFrameResult.INVALID, frame.result)
        assertNull("历史卡外壳多键仍整卡丢弃", AgentHistory.card(JsonObject(env - "seq"), Routes.all))
    }

    @Test
    fun suggestionsQuickReplyExtraKeyStaysInvalidFrame() {
        // notice.actions 与 suggestions.items 共用 quickReply 判定：放宽 notice 时不得把 suggestions 帧一起放宽
        val ok = """{"event":"suggestions","id":9,"data":{"items":[{"text":"换一批","send_text":"请换一批商品"}]}}"""
        assertEquals(AgentFrameResult.OK, AgentFrameDecoder.frame(AgentSse.fromNdjsonLine(ok)).result)
        val extra = """{"event":"suggestions","id":9,"data":{"items":[{"text":"换一批","send_text":"请换一批商品","future_hint":"x"}]}}"""
        assertEquals(AgentFrameResult.INVALID, AgentFrameDecoder.frame(AgentSse.fromNdjsonLine(extra)).result)
    }

    @Test
    fun unknownCardTypeStillFallsBack() {
        val card = parse("future_card", json("""{"future_field":{"nested":["未来扩展字段"]}}"""))
        assertEquals(AgentCardKind.FALLBACK, card.kind)
        assertFalse(card.known)
    }

    private companion object {
        const val PRODUCT_LIST = AgentCardType.PRODUCT_LIST
        const val REBATE_QUOTE = AgentCardType.REBATE_QUOTE
        const val ORDER_STATUS = AgentCardType.ORDER_STATUS
        const val CLAIM_DRAFT = AgentCardType.CLAIM_DRAFT
        const val HANDOFF = AgentCardType.HANDOFF
        const val AUTH_REQUIRED = AgentCardType.AUTH_REQUIRED
        const val NOTICE = AgentCardType.NOTICE
        const val RULE_REF = AgentCardType.RULE_REF
        const val EARNINGS = AgentCardType.EARNINGS_SUMMARY
    }
}
