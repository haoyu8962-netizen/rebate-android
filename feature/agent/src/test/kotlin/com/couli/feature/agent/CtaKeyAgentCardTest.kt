package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * FU-X-CTA-KEY-DRIFT（D10-856）AI 卡的两种画法（ProductItem：list 走 ProductCardList 的 AI 版式；grid 走 ProductCardGrid + 单独的 BuyButton）都要：
 * 锁内没有的键显示「购买」、可点；btn.buy.rebate 显示「购买预估返 ¥2.69」、读屏以可见动词开头且金额只读一次。
 * 映射用本模块界面测试共用的 [AgentUiKit.ui]（ProductCardMapper + CARD_TEXTS，不传诊断回调）；App 里 AgentRoute.mapCard 用的真实文案函数
 * SearchDraftTexts 见 app 的 CtaKeyWiringTest。数据用契约 fixture normal 的第一张 product_list 卡（c1：c2、c3），只改 cta 与返利。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 两种画法 + 构造流 + 读节点
class CtaKeyAgentCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listCardsBuyUnknownKeysAndShowTheRebateVerb() = assertCards(layout = "list")

    @Test
    fun gridCardsBuyUnknownKeysAndShowTheRebateVerb() = assertCards(layout = "grid")

    private fun assertCards(layout: String) {
        val kit = AgentUiKit()
        val controller = kit.controller()
        val ui = kit.ui()
        composeRule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides AgentUiKit.images) { CouliTheme { AgentScreen(controller, ui) } }
        }
        idle()
        composeRule.runOnIdle { kit.playLines(controller, lines(layout)) }
        idle()
        val unknown = buyButton(AgentTags.product("c1", "c2"))
        assertEquals("$layout：锁内没有的键显示「购买」", "购买", unknown.visible())
        assertTrue("$layout：可点", unknown.enabled())
        val rebate = buyButton(AgentTags.product("c1", "c3"))
        assertEquals("$layout：btn.buy.rebate 整行", "购买预估返 ¥2.69", rebate.visible())
        assertTrue("$layout：可点", rebate.enabled())
        val spoken = rebate.spoken()
        assertTrue("$layout 读屏以可见动词开头：$spoken", spoken.startsWith("购买"))
        assertFalse("$layout 读屏不读花括号：$spoken", spoken.contains('{') || spoken.contains('}'))
        assertEquals("$layout「预估返」只读一次：$spoken", 1, spoken.split("预估返").size - 1)
        assertEquals("$layout 金额只读一次：$spoken", 1, spoken.split("2.69").size - 1)
    }

    /** 契约 fixture normal，第一张 product_list 卡：c2 换成锁内没有的键（区间返利），c3 换成 btn.buy.rebate（单一返利 ¥2.69）；按 [layout] 排。 */
    private fun lines(layout: String): List<String> {
        var done = false
        return AgentUiKit.fixtureLines("normal").map { line ->
            if (done || !line.contains("\"type\":\"product_list\"")) {
                line
            } else {
                done = true
                patch(Json.parseToJsonElement(line).jsonObject, layout).toString()
            }
        }
    }

    private fun patch(
        event: JsonObject,
        layout: String,
    ): JsonObject {
        val card = event.getValue("data").jsonObject
        val body = card.getValue("data").jsonObject
        val items = body.getValue("items").jsonArray.map { it.jsonObject }
        val patched =
            listOf(item(items[0], "btn.buy.future_agent", 135L, 269L), item(items[1], "btn.buy.rebate", 269L, 269L)) + items.drop(2)
        val newBody = JsonObject(body + mapOf("items" to JsonArray(patched), "layout" to JsonPrimitive(layout)))
        return JsonObject(event + mapOf("data" to JsonObject(card + mapOf("data" to newBody))))
    }

    private fun item(
        source: JsonObject,
        ctaKey: String,
        minFen: Long,
        maxFen: Long,
    ): JsonObject {
        val cta: JsonElement = JsonObject(mapOf("text_key" to JsonPrimitive(ctaKey)))
        val fields = mapOf("cta" to cta, "rebate_min_fen" to JsonPrimitive(minFen), "rebate_max_fen" to JsonPrimitive(maxFen))
        return JsonObject(source + fields)
    }

    /** 商品块里带字的按钮只有购买按钮（商品块本身可点打开详情，不算它的后代；口径 i 只有读屏名、没有字）。 */
    private fun buyButton(productTag: String): SemanticsNode {
        val nodes = composeRule.onAllNodes(hasAnyAncestor(hasTestTag(productTag)) and IS_BUTTON and HAS_TEXT).fetchSemanticsNodes()
        assertEquals("$productTag 里带字的按钮只有购买按钮：${nodes.map { it.visible() }}", 1, nodes.size)
        return nodes.single()
    }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun SemanticsNode.visible(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun SemanticsNode.spoken(): String = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: visible()

    private fun SemanticsNode.enabled(): Boolean =
        !config.contains(SemanticsProperties.Disabled) && config.contains(SemanticsActions.OnClick)

    private companion object {
        const val ROUNDS = 4
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val HAS_TEXT = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
    }
}
