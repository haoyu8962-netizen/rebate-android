package com.couli.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CtaKeyVectors.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * FU-X-CTA-KEY-DRIFT：各页面购买按钮上的整行文字与读屏（向量 title 字段；R1 的「动词已含金额不再拼后缀」），以及默认诊断进系统日志（R3）。
 * 整行文字从映射结果经真实卡片组件 [ProductCardList]（列表 / 详情 / AI 三种 context）画出来再读，不直接拼 [BuyButtonModel.text]：
 * 「动词已含金额」的标志怎么从映射带到按钮由实现者定（iOS 评审 S2-2），只要真实组件上的字对。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
@Suppress("TooManyFunctions") // 向量整行 + 读屏 + 默认诊断
class CtaKeyButtonTest {
    @get:Rule
    val rule = createComposeRule()

    private var shown by mutableStateOf<Pair<MappedProductCard, String>?>(null)
    private var contentSet = false

    private fun mapped(
        card: JsonObject,
        texts: ProductCardTexts = CtaKeyVectors.texts("contract"),
        buyEnabled: Boolean = true,
    ): MappedProductCard = ProductCardMapper.map(card, texts, nowMillis = CtaKeyVectors.NOW, buyEnabled = buyEnabled, onCtaFallback = {})

    private fun show(
        card: MappedProductCard,
        context: String,
    ) {
        if (!contentSet) {
            contentSet = true
            rule.setContent { CouliTheme { shown?.let { (m, page) -> Card(m, page) } } }
        }
        rule.runOnIdle { shown = card to context }
        rule.waitForIdle()
    }

    /**
     * 画卡：向量的 contract / none 写法给不出平台名（平台名是草稿键），而 BadgedTitle 的平台徽标不能是空名（appendInlineContent 的
     * alternateText 不许空串，PriceComponents.kt:501）。只在画卡时补上平台名，映射结果的其余字段（含按钮相关的）原样。
     */
    @Composable
    private fun Card(
        card: MappedProductCard,
        context: String,
    ) {
        ProductCardList(
            data = card.data.copy(platformName = PLATFORM_NAME),
            onBuy = {},
            modifier = Modifier.testTag(CARD),
            variant = if (context == BuyButtonModel.CONTEXT_AGENT) ProductCardVariant.AI else ProductCardVariant.SEARCH,
            buyState = card.buyState,
            context = context,
            retryLaterText = card.retryLaterText,
        )
    }

    /** 卡里带字的按钮只有购买按钮（口径 i 只有读屏名、没有字）。 */
    private fun buyButton(): SemanticsNode {
        val nodes = rule.onAllNodes(hasAnyAncestor(hasTestTag(CARD)) and IS_BUTTON and HAS_TEXT).fetchSemanticsNodes()
        assertEquals("卡里带字的按钮只有购买按钮：${nodes.map { it.visible() }}", 1, nodes.size)
        return nodes.single()
    }

    @Test
    fun vectorTitlesAreTheWholeLineOnTheBuyButton() {
        var checked = 0
        CtaKeyVectors.cases.filter { CtaKeyVectors.titles(it).isNotEmpty() }.forEach { row ->
            val label = CtaKeyVectors.label(row)
            val card = mapped(CtaKeyVectors.card(row), CtaKeyVectors.texts(row.text("texts")), CtaKeyVectors.buyEnabled(row))
            assertEquals("$label：title 是 normal 状态下的整行", BuyButtonState.NORMAL, card.buyState)
            CtaKeyVectors.titles(row).forEach { (context, title) ->
                show(card, context)
                assertEquals("$label title.$context", title, buyButton().visible())
                checked += 1
            }
        }
        assertEquals("向量里 title 共 9 处（list / detail / agent）", 9, checked)
    }

    /**
     * 读屏（D10-861 ⑤只钉三条，句式转开发验收）：以可见动词开头、不读花括号、金额只读一次。btn.buy.rebate 的动词已含金额；btn.buy 仍拼后缀，
     * 也只读一次（防止把后缀那份读屏也一并去掉）。
     */
    @Test
    fun buyButtonSpeaksTheVerbFirstAndTheAmountExactlyOnce() {
        listOf("btn.buy.rebate", "btn.buy").forEach { key ->
            val card = mapped(CtaKeyVectors.card(key))
            CONTEXTS.forEach { context ->
                show(card, context)
                val button = buyButton()
                val spoken = button.spoken()
                if (key == "btn.buy.rebate") assertEquals("$context 可见整行", "购买预估返 ¥2.69", button.visible())
                assertTrue("$key / $context 读屏以可见动词开头：$spoken", spoken.startsWith("购买"))
                assertFalse("$key / $context 读屏不读花括号：$spoken", spoken.contains('{') || spoken.contains('}'))
                assertEquals("$key / $context「预估返」只读一次：$spoken", 1, spoken.split(PriceTerms.EST_REBATE).size - 1)
                assertEquals("$key / $context 金额只读一次：$spoken", 1, spoken.split("2.69").size - 1)
            }
        }
    }

    /** 没注入 onCtaFallback 时：默认诊断经 android.util.Log 写一条（任意 tag / 级别），只带 text_key；渲染得出的键不写。 */
    @Test
    fun defaultDiagnosticGoesToTheSystemLogWithTheTextKeyOnly() {
        ShadowLog.clear()
        val probe = "btn.buy.default_log_probe"
        val card =
            ProductCardMapper.map(CtaKeyVectors.card(probe, SENSITIVE), CtaKeyVectors.texts("contract"), nowMillis = CtaKeyVectors.NOW)
        assertEquals("购买", card.data.buyBase)
        val lines = ShadowLog.getLogs().map { "${it.tag} ${it.msg}" }
        val diagnostics = lines.filter { it.contains(probe) }
        assertTrue("默认诊断经 android.util.Log 写下 text_key：$lines", diagnostics.isNotEmpty())
        SECRETS.forEach { secret -> assertTrue("诊断日志里不该有 $secret：$diagnostics", diagnostics.none { it.contains(secret) }) }
        ShadowLog.clear()
        ProductCardMapper.map(CtaKeyVectors.card("btn.buy.rebate"), CtaKeyVectors.texts("contract"), nowMillis = CtaKeyVectors.NOW)
        val after = ShadowLog.getLogs().map { "${it.tag} ${it.msg}" }
        assertTrue("渲染得出的键不写诊断：$after", after.none { it.contains("btn.buy") })
    }

    private fun SemanticsNode.visible(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** 读屏读到的：有 contentDescription 读它，否则读可见文字。 */
    private fun SemanticsNode.spoken(): String = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: visible()

    private companion object {
        const val CARD = "cta_key_card"
        const val PLATFORM_NAME = "淘宝"
        val CONTEXTS = listOf(BuyButtonModel.CONTEXT_LIST, BuyButtonModel.CONTEXT_DETAIL, BuyButtonModel.CONTEXT_AGENT)
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val HAS_TEXT = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        val SENSITIVE: Map<String, JsonElement> =
            mapOf("title" to JsonPrimitive("张三 13800001111 的收货地址"), "shop_name" to JsonPrimitive("某某旗舰店"))
        val SECRETS = listOf("张三", "13800001111", "tb:cta", "ref-cta", "0199a3b4", "某某旗舰店", "2990", "29.9")
    }
}
