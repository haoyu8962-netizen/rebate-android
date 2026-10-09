package com.couli.feature.trade

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0607：确认框「现在」一行与返利归零正文经真实购买浮层（[PurchaseOverlay]）渲染（APP-06b 共性第 30、37、45 条）。文案用键原样
 * 返回的替身，断言看用了哪个键与变量。撤掉修复时断言失败（NATIVE/FU-AND-0607/revert-checks.txt）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class FollowUp0607UiTest {
    @get:Rule
    val rule = createComposeRule()

    private val api = FakeTradeApi()
    private val ports = FakePorts()
    private val baichuan = FakeBaichuan()
    private val env = FakeEnv(loggedIn = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var keySeq = 0

    private val controller =
        OpenPurchaseController(
            api = api,
            executor = TradeJumpExecutor(ports, baichuan, { }, { 0L }, { TradeFixtures.NOW }),
            ports = ports,
            env = env,
            pending = MemoryPurchasePending(),
            counter = AuthSheetCounter(),
            tips = JumpTipService(api) { null },
            webCode = FakeWebCode(),
            baichuan = baichuan,
            scope = scope,
            keyGenerator = {
                keySeq += 1
                "key-$keySeq"
            },
            browserNoticeMillis = 0L,
        )

    private object KeyTexts : TradeTexts {
        override fun text(key: String): String = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String = key + vars.entries.joinToString("") { "|${it.key}=${it.value}" }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun opens() = api.calls.filter { it.call.operationId == TradeOperations.OPEN_LINK }

    private fun quote(
        price: Long,
        coupon: Long,
        final: Long,
    ): JsonObject =
        Json
            .parseToJsonElement(
                """{"product_key":"tb:c1","item_ref":"v1.r","platform":"taobao","price_fen":$price,"coupon_fen":$coupon,""" +
                    """"final_price_fen":$final,"rebate_min_fen":123,"rebate_max_fen":234,"rebate_basis":"normal"}""",
            ).jsonObject

    private fun open(
        response: String,
        card: JsonObject = TradeFixtures.card(),
    ) {
        rule.setContent { PurchaseOverlay(controller, KeyTexts, buyFocus = null) }
        env.card = card
        rule.runOnIdle { controller.buy(env.card!!, null) }
        rule.waitForIdle()
        rule.runOnIdle { api.succeed(opens().single(), response) }
        rule.waitForIdle()
    }

    private fun count(text: String): Int = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /** 第 30、37 条：普通改价、新价券额未补齐：「现在」不带口径词；补齐后按券额给「券后」。 */
    @Test
    fun c30_c37_theNowLineHasNoBasisWordUntilTheCouponIsKnown() {
        open(TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newLink = "link-2"))
        rule.onNodeWithText(TradeKeys.PRICE_NOW_PLAIN).assertExists()
        assertEquals("补齐前不沿用旧口径词", 0, count("${TradeKeys.PRICE_NOW}|"))
        rule.runOnIdle { env.answerRefetch(quote(price = 5990, coupon = 1000, final = 4990)) }
        rule.waitForIdle()
        rule.onNodeWithText("${TradeKeys.PRICE_NOW}|price_label=券后").assertExists()
    }

    /** 第 30 条：券失效（可能换了新券）补齐前同样只写「现在」，不由 coupon_gone 推断「售价」。 */
    @Test
    fun c30_couponGoneBeforeTheFillHasNoBasisWord() {
        open(TradeFixtures.openResult(newFinal = 5678, newLink = "link-2", availability = "coupon_gone"))
        rule.onNodeWithText(TradeKeys.PRICE_NOW_PLAIN).assertExists()
        assertEquals(0, count("售价"))
    }

    /** 第 45 条：价格变动未达阈值（price_changed=false）但新价不同：返利归零弹窗不写「价格没有变化」，新价口径未补齐用「现在」。 */
    @Test
    fun c45_rebateGoneWithADifferentPriceDoesNotSayThePriceIsUnchanged() {
        open(TradeFixtures.openResult(newFinal = 4768, newMin = 0, newMax = 0))
        assertEquals("不写「价格没有变化」", 0, count("${TradeKeys.REBATE_GONE_BODY}|"))
        rule
            .onNodeWithText(
                "${TradeKeys.REBATE_GONE_BODY_CHANGED}|price_now=${TradeKeys.PRICE_NOW_PLAIN}|price=${CouliFormat.money(4768)}",
            ).assertExists()
    }

    /** 原卡价格构成未知（券额、券前价缺失）。 */
    private fun unknownBasisCard(): JsonObject =
        Json
            .parseToJsonElement(
                TradeFixtures
                    .card()
                    .toString()
                    .replace("\"price_fen\":5678", "\"price_fen\":null")
                    .replace("\"coupon_fen\":1000", "\"coupon_fen\":null"),
            ).jsonObject

    /** 第 47 条：原价口径未知：「之前」一行不默认「券后」。 */
    @Test
    fun c47_theBeforeLineHasNoBasisWordWhenTheOriginalBasisIsUnknown() {
        open(TradeFixtures.openResult(priceChanged = true, newFinal = 4990, newLink = "link-2"), unknownBasisCard())
        rule.onNodeWithText(TradeKeys.PRICE_BEFORE_PLAIN).assertExists()
        assertEquals("不默认「券后」", 0, count("券后"))
    }

    /** 第 47 条：价格没有变化但口径未知：「价格没有变化」一行不带口径词。 */
    @Test
    fun c47_rebateGoneUnchangedWithAnUnknownBasisHasNoBasisWord() {
        open(TradeFixtures.openResult(newFinal = 4678, newMin = 0, newMax = 0), unknownBasisCard())
        rule.onNodeWithText("${TradeKeys.REBATE_GONE_BODY_PLAIN}|price=${CouliFormat.money(4678)}").assertExists()
        assertEquals(0, count("券后"))
    }

    /** 第 45 条：新旧金额相同才写「价格没有变化」（原卡口径）。 */
    @Test
    fun c45_rebateGoneWithTheSamePriceSaysUnchanged() {
        open(TradeFixtures.openResult(newFinal = 4678, newMin = 0, newMax = 0))
        rule.onNodeWithText("${TradeKeys.REBATE_GONE_BODY}|price_label=券后|price=${CouliFormat.money(4678)}").assertExists()
    }
}
