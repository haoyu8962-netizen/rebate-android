package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-AGENT-UNKNOWN-FIELDS（D10-1287，C2①）界面层：锁内 fixture 经真实 AgentController + StreamClient + Reducer 解码，再由 AgentScreen 画出来。
 * - 先红：主干后端给每张商品卡下发 `price_spec_hint`（rebate-platform origin/main d85c2501：contracts/agent-stream.schema.json:521-525，字符串或 null；
 *   apps/api/src/modules/agent/tools/search/card.ts:159、tools/quote/index.ts:250-253 照抄目录卡取值）→ 商品卡照常画出（有商品块），不是只剩一句兜底文字；
 *   卡片顶层与嵌套对象（商品、cta、素材、提现摘要、规则动作）里带未知键同样照常画出。
 * - 钉住（绿）：缺必需字段 → 只有兜底文字、不画商品块；page_guide 多键 → 不画按钮；收益卡按钮多键 → 只有兜底文字、没有按钮。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 每条规则一个用例 + 构造流 + 读节点
class AgentUnknownFieldsCardUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(lines: List<String>) {
        val kit = AgentUiKit()
        val controller = kit.controller()
        val ui = kit.ui()
        composeRule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides AgentUiKit.images) { CouliTheme { AgentScreen(controller, ui) } }
        }
        idle()
        composeRule.runOnIdle { kit.playLines(controller, lines) }
        idle()
    }

    // ---------------------------------------------------------------- 先红

    @Test
    fun mainBackendProductCardsWithPriceSpecHintRenderAsProductCards() {
        val hints = listOf("spec_uncertain", null, "spec_uncertain").iterator()
        val listed = patchCard(AgentUiKit.fixtureLines("normal"), PRODUCT_LIST) { d -> d.items { p -> withHint(p, hints.next()) } }
        val lines = patchCard(listed, REBATE_QUOTE) { d -> d.withKeys("product" to withHint(d.obj("product"), hints.next())) }
        assertFalse("三张商品卡都补上了 price_spec_hint", hints.hasNext())
        show(lines)
        assertProductsShown()
    }

    @Test
    fun unknownFieldsAtEveryLevelOfProductCardsRender() {
        val listed =
            patchCard(AgentUiKit.fixtureLines("normal"), PRODUCT_LIST) { d ->
                val top = d.withAll(UNKNOWN)
                top.items { p -> withHint(p, "spec_uncertain").withAll(UNKNOWN).withKeys("cta" to p.obj("cta").withAll(UNKNOWN)) }
            }
        val lines =
            patchCard(listed, REBATE_QUOTE) { d ->
                d
                    .withAll(UNKNOWN)
                    .withKeys("product" to d.obj("product").withAll(UNKNOWN))
                    .withKeys("material" to d.obj("material").withAll(UNKNOWN))
            }
        show(lines)
        assertProductsShown()
    }

    @Test
    fun earningsWithUnknownFieldsShowsBothButtons() {
        val lines =
            patchCard(AgentUiKit.fixtureLines("normal-earnings"), EARNINGS) { d ->
                d.withAll(UNKNOWN).withKeys("latest_withdrawal" to d.obj("latest_withdrawal").withAll(UNKNOWN))
            }
        show(lines)
        assertTrue("【去钱包】照常画出", shown(AgentTags.earningsButton("c1", "Wallet")))
        assertTrue("【看提现记录】照常画出", shown(AgentTags.earningsButton("c1", "WithdrawRecords")))
        assertFalse("不该只剩兜底文字", textShown("请到钱包页查看收益与提现进度"))
    }

    @Test
    fun ruleRefWithUnknownFieldsShowsRuleEntry() {
        val lines =
            patchCard(AgentUiKit.fixtureLines("unknown-card"), RULE_REF) { d ->
                d.withAll(UNKNOWN).withKeys("action" to d.obj("action").withAll(UNKNOWN))
            }
        show(lines)
        assertTrue("【查看完整规则】照常画出", shown(AgentTags.rule("c2")))
    }

    // ---------------------------------------------------------------- 钉住（绿）

    @Test
    fun productMissingRequiredFieldShowsOnlyFallbackText() {
        val lines =
            patchCard(AgentUiKit.fixtureLines("normal"), PRODUCT_LIST) { d ->
                d.withAll(UNKNOWN).items { p -> JsonObject(p - "final_price_fen") }
            }
        show(lines)
        assertTrue(shown(AgentTags.card("c1")))
        assertTrue("缺必需字段：整卡只显示兜底文字", textShown("请在商品列表查看"))
        assertFalse("不渲染部分字段", shown(AgentTags.product("c1", "c2")))
        assertFalse(shown(AgentTags.product("c1", "c3")))
    }

    @Test
    fun pageGuideWithExtraKeyShowsNoButton() {
        val lines =
            patchCard(AgentUiKit.fixtureLines("normal-page-guide"), PAGE_GUIDE) { d -> d.withKeys("future_hint" to JsonPrimitive("x")) }
        show(lines)
        assertTrue(shown(AgentTags.card("c1")))
        assertFalse("page_guide 多键：不画按钮", shown(AgentTags.guide("c1")))
    }

    @Test
    fun earningsButtonWithExtraKeyShowsOnlyFallbackText() {
        val lines =
            patchCard(AgentUiKit.fixtureLines("normal-earnings"), EARNINGS) { d ->
                val actions = d.getValue("actions") as JsonArray
                val first = actions[0].jsonObject.withKeys("params" to JsonObject(emptyMap()))
                d.withKeys("actions" to JsonArray(listOf(first) + actions.drop(1)))
            }
        show(lines)
        assertFalse(shown(AgentTags.earningsButton("c1", "Wallet")))
        assertFalse(shown(AgentTags.earningsButton("c1", "WithdrawRecords")))
        assertTrue(textShown("请到钱包页查看收益与提现进度"))
    }

    // ---------------------------------------------------------------- 工具

    private fun assertProductsShown() {
        listOf("c1" to "c2", "c1" to "c3", "c4" to "c5").forEach { (card, item) ->
            assertTrue("商品块 $card/$item 应照常画出", shown(AgentTags.product(card, item)))
        }
        assertFalse("product_list 不该只剩兜底文字", textShown("请在商品列表查看"))
        assertFalse("rebate_quote 不该只剩兜底文字", textShown("请在商品页查看"))
    }

    private fun shown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun textShown(part: String): Boolean =
        composeRule.onAllNodesWithText(part, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun JsonObject.withKeys(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)

    private fun JsonObject.withAll(extra: Map<String, JsonElement>): JsonObject = JsonObject(this + extra)

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.items(block: (JsonObject) -> JsonObject): JsonObject =
        withKeys("items" to JsonArray((getValue("items") as JsonArray).map { block(it.jsonObject) }))

    /** 主干形态：price_spec_hint 紧跟 spec_text（取值 price_from / spec_uncertain / null）。 */
    private fun withHint(
        product: JsonObject,
        hint: String?,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        product.forEach { (k, v) ->
            out[k] = v
            if (k == "spec_text") out["price_spec_hint"] = hint?.let { JsonPrimitive(it) } ?: JsonNull
        }
        assertTrue("商品卡里有 spec_text", "price_spec_hint" in out)
        return JsonObject(out)
    }

    /** 把 [type] 那一张 card 帧的 data 换成 [block] 的结果（恰好一张）；其他行原样。 */
    private fun patchCard(
        lines: List<String>,
        type: String,
        block: (JsonObject) -> JsonObject,
    ): List<String> {
        var hits = 0
        val out =
            lines.map { line ->
                val frame = Json.parseToJsonElement(line).jsonObject
                val env = frame["data"] as? JsonObject
                val isTarget = frame["event"]?.jsonPrimitive?.content == "card" && env?.get("type")?.jsonPrimitive?.content == type
                if (!isTarget || env == null) {
                    line
                } else {
                    hits += 1
                    frame.withKeys("data" to env.withKeys("data" to block(env.obj("data")))).toString()
                }
            }
        assertEquals("fixture 里 $type 卡应恰好一张", 1, hits)
        return out
    }

    private companion object {
        const val ROUNDS = 4
        const val PRODUCT_LIST = "product_list"
        const val REBATE_QUOTE = "rebate_quote"
        const val EARNINGS = "earnings_summary"
        const val RULE_REF = "rule_ref"
        const val PAGE_GUIDE = "page_guide"

        /** 本端（锁定契约）不认识的可选字段：字符串、数字、null、数组、带嵌套的对象。 */
        val UNKNOWN: Map<String, JsonElement> =
            linkedMapOf(
                "future_hint" to JsonPrimitive("新字段"),
                "future_rank" to JsonPrimitive(3),
                "future_null" to JsonNull,
                "future_list" to JsonArray(listOf(JsonPrimitive("a"))),
                "future_detail" to Json.parseToJsonElement("""{"level":2,"inner":{"x":null}}"""),
            )
    }
}
