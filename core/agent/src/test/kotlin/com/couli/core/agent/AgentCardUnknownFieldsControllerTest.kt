package com.couli.core.agent

import com.couli.core.agent.AgentTestKit.Companion.META
import com.couli.core.agent.AgentTestKit.Companion.fixtureLines
import com.couli.core.agent.UnknownFieldsSamples.NESTED_UNKNOWN
import com.couli.core.agent.UnknownFieldsSamples.UNKNOWN
import com.couli.core.agent.UnknownFieldsSamples.adding
import com.couli.core.agent.UnknownFieldsSamples.addingAll
import com.couli.core.agent.UnknownFieldsSamples.cardLine
import com.couli.core.agent.UnknownFieldsSamples.doneLine
import com.couli.core.agent.UnknownFieldsSamples.dropping
import com.couli.core.agent.UnknownFieldsSamples.envelope
import com.couli.core.agent.UnknownFieldsSamples.json
import com.couli.core.agent.UnknownFieldsSamples.patchItem
import com.couli.core.agent.UnknownFieldsSamples.patchObject
import com.couli.core.agent.UnknownFieldsSamples.patchProductsInLine
import com.couli.core.agent.UnknownFieldsSamples.withPriceSpecHint
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-AGENT-UNKNOWN-FIELDS 任务书 §2 第 1、2 条（执行层）：真实 AgentController + StreamClient + Reducer，按 SSE 下发一张卡，看页面拿到的
 * AgentCardView——带未知可选字段（含嵌套对象里的未知键）的卡照常出类型对应的内容与按钮票据，不是只剩 fallback_text；不记 sdui_card_error。
 * 专门一条：锁内 fixture normal 的三张商品卡补上主干后端下发的 `price_spec_hint`（rebate-platform origin/main d85c2501：
 * contracts/agent-stream.schema.json:521-525，apps/api/src/modules/agent/tools/search/card.ts:159，tools/quote/index.ts:250-253）后整条流照常出商品卡。
 * 反例（钉住，现在就绿）：page_guide 多键仍不画按钮并记一次 route_entry_blocked；收益卡按钮多键仍 fallback；缺必需字段的商品列表仍 fallback。
 */
@Suppress("TooManyFunctions") // 每种卡一个用例
class AgentCardUnknownFieldsControllerTest {
    private val kit = AgentTestKit()

    private fun playLines(lines: List<String>): Pair<AgentController, AgentTurnView> {
        val c = kit.controller()
        assertTrue("发送应受理", c.send(c.snapshot.value.page, "帮我找纯牛奶"))
        val stream = kit.port.last
        stream.headers()
        lines.forEach { stream.line(it) }
        stream.end()
        val turn =
            c.snapshot.value.turns
                .single()
        return c to turn
    }

    /** meta → 一张卡（seq 2）→ done，返回这张卡的展示。 */
    private fun playCard(
        type: String,
        data: JsonObject,
    ): AgentCardView {
        val (_, turn) = playLines(listOf(META, cardLine(envelope(type, data)), doneLine(3)))
        return turn.cards.single()
    }

    private fun assertKnown(
        what: String,
        view: AgentCardView,
    ) {
        assertTrue("$what：应照常渲染，实际只剩 fallback_text（kind=${view.card.kind}）", view.card.known)
        assertEquals(
            "$what：带未知字段的卡不记 sdui_card_error",
            emptyList<String>(),
            kit.tracks.filter { it.name == SDUI_CARD_ERROR }.map { it.name },
        )
        assertTrue("$what：不记 route_entry_blocked", kit.tracks.none { it.name == ROUTE_ENTRY_BLOCKED })
    }

    // ---------------------------------------------------------------- 先红：每种类型带未知字段（含嵌套）照常出内容

    @Test
    fun productListWithUnknownFieldsShowsProductsAndBuyTickets() {
        val base = UnknownFieldsSamples.productList().addingAll(UNKNOWN)
        val data =
            base.patchItem("items", 0) { p ->
                withPriceSpecHint(p, "spec_uncertain").addingAll(NESTED_UNKNOWN).patchObject("cta") { it.addingAll(NESTED_UNKNOWN) }
            }
        val view = playCard(AgentCardType.PRODUCT_LIST, data)
        assertKnown("product_list", view)
        assertEquals(listOf("c2"), view.products.map { it.itemCardId })
        assertNotNull("购买按钮票据照常给", view.products.single().buy)
        assertNotNull("商品详情票据照常给", view.products.single().detail)
    }

    @Test
    fun rebateQuoteWithUnknownFieldsShowsProductAndMaterialLine() {
        val data =
            UnknownFieldsSamples
                .rebateQuote()
                .addingAll(UNKNOWN)
                .patchObject("product") { withPriceSpecHint(it, "spec_uncertain").addingAll(NESTED_UNKNOWN) }
                .patchObject("material") { it.addingAll(NESTED_UNKNOWN) }
        val view = playCard(AgentCardType.REBATE_QUOTE, data)
        assertKnown("rebate_quote", view)
        assertEquals(listOf("c5"), view.products.map { it.itemCardId })
        assertNotNull(view.products.single().buy)
        assertTrue("素材行照常显示：${view.lines}", view.lines.any { it.contains("演示纯牛奶整箱") })
    }

    @Test
    fun orderStatusWithUnknownFieldsShowsOrderEntry() {
        val view = playCard(AgentCardType.ORDER_STATUS, UnknownFieldsSamples.orderStatus().addingAll(UNKNOWN))
        assertKnown("order_status", view)
        assertNotNull("【查看订单详情】票据照常给", view.order)
        assertEquals("演示纯牛奶", view.title)
    }

    @Test
    fun claimDraftWithUnknownFieldsShowsOrderNo() {
        val data = UnknownFieldsSamples.claimDraft().addingAll(UNKNOWN).patchObject("submit_action") { it.addingAll(NESTED_UNKNOWN) }
        val view = playCard(AgentCardType.CLAIM_DRAFT, data)
        assertKnown("claim_draft", view)
        assertTrue("订单号照常显示：${view.lines}", view.lines.any { it.contains("123456789012") })
    }

    @Test
    fun handoffWithUnknownFieldsShowsCustomerServiceEntry() {
        val view = playCard(AgentCardType.HANDOFF, UnknownFieldsSamples.handoff().addingAll(UNKNOWN))
        assertKnown("handoff", view)
        assertNotNull(view.handoff)
        assertEquals(listOf("没有找到相关规则，可以联系客服确认。"), view.lines)
    }

    @Test
    fun authRequiredWithUnknownFieldsShowsAuthEntry() {
        val view = playCard(AgentCardType.AUTH_REQUIRED, UnknownFieldsSamples.authRequired().addingAll(UNKNOWN))
        assertKnown("auth_required", view)
        assertNotNull(view.auth)
    }

    @Test
    fun noticeWithUnknownFieldsShowsQuickReplyChips() {
        val data = UnknownFieldsSamples.notice().addingAll(UNKNOWN).patchItem("actions", 0) { it.addingAll(NESTED_UNKNOWN) }
        val view = playCard(AgentCardType.NOTICE, data)
        assertKnown("notice", view)
        assertEquals(listOf("换个平台看看"), view.chips.map { it.text })
    }

    @Test
    fun ruleRefWithUnknownFieldsOpensSameRouteAndParams() {
        val data = UnknownFieldsSamples.ruleRef().addingAll(UNKNOWN).patchObject("action") { it.addingAll(NESTED_UNKNOWN) }
        val view = playCard(AgentCardType.RULE_REF, data)
        assertKnown("rule_ref", view)
        val rule = view.rule
        assertNotNull(rule)
        assertEquals("Help", rule!!.route)
        assertEquals(json("""{"article_id":"demo-article-0001"}"""), rule.params)
    }

    @Test
    fun earningsLiveWithUnknownFieldsShowsAmountsAndBothButtons() {
        val data = UnknownFieldsSamples.earningsLive().addingAll(UNKNOWN).patchObject("latest_withdrawal") { it.addingAll(NESTED_UNKNOWN) }
        val view = playCard(AgentCardType.EARNINGS_SUMMARY, data)
        assertKnown("earnings_summary 实时", view)
        assertEquals(EarningsShape.LIVE, view.earnings?.shape)
        assertEquals(listOf("Wallet", "WithdrawRecords"), view.earningsTaps.map { it.route })
    }

    @Test
    fun earningsHistoryShapeWithUnknownFieldsShowsBothButtons() {
        val view = playCard(AgentCardType.EARNINGS_SUMMARY, UnknownFieldsSamples.earningsHistory().addingAll(UNKNOWN))
        assertKnown("earnings_summary 历史形状", view)
        assertEquals(EarningsShape.HISTORY, view.earnings?.shape)
        assertEquals(listOf("Wallet", "WithdrawRecords"), view.earningsTaps.map { it.route })
    }

    // ---------------------------------------------------------------- 先红：主干真实形态（price_spec_hint）走完整条流

    @Test
    fun mainBackendProductCardsWithPriceSpecHintRenderAsProductCards() {
        // c2、c3 是 product_list 的两件（jd 商品，主干 priceSpecHintFor：item 模式 spec_uncertain，sku 模式 null），c5 是 rebate_quote 的商品
        val hints = listOf("spec_uncertain", null, "spec_uncertain").iterator()
        val lines = fixtureLines("normal").map { patchProductsInLine(it, hints) }
        assertFalse("三张商品卡都补上了 price_spec_hint", hints.hasNext())
        assertEquals(3, lines.sumOf { Regex("\"price_spec_hint\"").findAll(it).count() })
        val (_, turn) = playLines(lines)
        assertEquals(listOf("c1", "c4"), turn.cards.map { it.card.cardId })
        turn.cards.forEach { assertKnown("主干形态 ${it.card.type}#${it.card.cardId}", it) }
        assertEquals(listOf("c2", "c3"), turn.cards[0].products.map { it.itemCardId })
        assertEquals(listOf("c5"), turn.cards[1].products.map { it.itemCardId })
        assertTrue("每件商品都有购买票据", turn.cards.flatMap { it.products }.all { it.buy != null })
    }

    // ---------------------------------------------------------------- 钉住（绿）

    @Test
    fun pageGuideWithExtraKeyStillHasNoButtonAndReportsOnce() {
        val view = playCard(AgentCardType.PAGE_GUIDE, UnknownFieldsSamples.pageGuide().adding("future_hint" to json("""{"a":1}""")))
        assertFalse(view.card.known)
        assertNull("page_guide 多键：不画按钮", view.guide)
        val blocked = kit.tracks.filter { it.name == ROUTE_ENTRY_BLOCKED }
        assertEquals(1, blocked.size)
        assertEquals("WithdrawRecords", blocked.single().fields["route"]?.content)
        assertTrue(kit.opens.isEmpty())
    }

    @Test
    fun earningsButtonWithExtraKeyStillFallsBackWithoutButtons() {
        val data = UnknownFieldsSamples.earningsLive().patchItem("actions", 0) { it.addingAll(NESTED_UNKNOWN) }
        val view = playCard(AgentCardType.EARNINGS_SUMMARY, data)
        assertFalse("收益卡按钮多键：整卡 fallback", view.card.known)
        assertTrue(view.earningsTaps.isEmpty())
        assertNull(view.earnings)
    }

    @Test
    fun productMissingRequiredFieldStillFallsBackEvenWithUnknownFields() {
        val data = UnknownFieldsSamples.productList().addingAll(UNKNOWN).patchItem("items", 0) { it.dropping("final_price_fen") }
        val view = playCard(AgentCardType.PRODUCT_LIST, data)
        assertFalse(view.card.known)
        assertTrue("fallback 不渲染部分字段", view.products.isEmpty())
        assertEquals(UnknownFieldsSamples.FALLBACK, view.card.fallbackText)
    }

    private companion object {
        const val SDUI_CARD_ERROR = "sdui_card_error"
        const val ROUTE_ENTRY_BLOCKED = "route_entry_blocked"
    }
}
