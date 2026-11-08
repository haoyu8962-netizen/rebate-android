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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-X-CTA-KEY-DRIFT 测试 v2（Codex 评审 r1 S2，卡片一路）：购买流程把按钮文字换掉——搜索结果卡的写法是 `card.data.copy(buyBase = 覆盖文字)`
 * 再交给 [ProductCardList]——之后，映射为 btn.buy.rebate 填好的金额句不能再被沿用：整行与读屏都从覆盖文字开头、不出现原来的「购买预估返」、
 * 金额最多读一次。同一张卡先不覆盖画一次作对照（确认它确实带着「动词已含金额」），再覆盖后画。三种 context 都过一遍。
 * 守的是 ProductCard.kt 里「按钮文字不是映射填好的那句就不算含金额」的判断：删掉它，覆盖后的读屏会变成原来的「购买预估返 2.69 元」。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class CtaKeyOverrideTextTest {
    @get:Rule
    val rule = createComposeRule()

    private var shown by mutableStateOf<Pair<ProductCardData, String>?>(null)
    private var contentSet = false

    private fun show(
        data: ProductCardData,
        context: String,
    ) {
        if (!contentSet) {
            contentSet = true
            rule.setContent { CouliTheme { shown?.let { (card, page) -> Card(card, page) } } }
        }
        rule.runOnIdle { shown = data to context }
        rule.waitForIdle()
    }

    @Composable
    private fun Card(
        data: ProductCardData,
        context: String,
    ) {
        ProductCardList(
            data = data,
            onBuy = {},
            modifier = Modifier.testTag(CARD),
            variant = if (context == BuyButtonModel.CONTEXT_AGENT) ProductCardVariant.AI else ProductCardVariant.SEARCH,
            buyState = BuyButtonState.NORMAL,
            context = context,
        )
    }

    @Test
    fun overrideTextNoLongerReusesTheMappedAmountSentence() {
        val mapped =
            ProductCardMapper.map(
                CtaKeyVectors.card("btn.buy.rebate"),
                CtaKeyVectors.texts("contract"),
                nowMillis = CtaKeyVectors.NOW,
                onCtaFallback = {},
            )
        // 向量 contract 写法给不出平台名，画卡补上（BadgedTitle 不许空名）；其余字段原样
        val data = mapped.data.copy(platformName = PLATFORM_NAME)
        CONTEXTS.forEach { context ->
            show(data, context)
            assertEquals("$context 对照：没覆盖时是映射填好的整句", "购买预估返 ¥2.69", buyButton().visible())
            show(data.copy(buyBase = OVERRIDE), context)
            val button = buyButton()
            val visible = button.visible()
            val spoken = button.spoken()
            assertTrue("$context 整行从覆盖文字开头：$visible", visible.startsWith(OVERRIDE))
            assertFalse("$context 整行不再沿用映射的金额句：$visible", visible.contains(MAPPED_VERB))
            assertTrue("$context 读屏从覆盖文字开头：$spoken", spoken.startsWith(OVERRIDE))
            assertFalse("$context 读屏不再沿用映射的金额句：$spoken", spoken.contains(MAPPED_VERB))
            assertTrue("$context 金额最多读一次：$spoken", spoken.split("2.69").size - 1 <= 1)
        }
    }

    private fun buyButton(): SemanticsNode {
        val nodes = rule.onAllNodes(hasAnyAncestor(hasTestTag(CARD)) and IS_BUTTON and HAS_TEXT).fetchSemanticsNodes()
        assertEquals("卡里带字的按钮只有购买按钮：${nodes.map { it.visible() }}", 1, nodes.size)
        return nodes.single()
    }

    private fun SemanticsNode.visible(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** 读屏读到的：有 contentDescription 读它，否则读可见文字。 */
    private fun SemanticsNode.spoken(): String = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: visible()

    private companion object {
        const val CARD = "cta_key_override_card"
        const val PLATFORM_NAME = "淘宝"

        /** 购买流程的覆盖文字（BuyButtonOverride：未安装「安装X后下单才有返利」）。 */
        const val OVERRIDE = "安装淘宝后下单才有返利"

        /** 映射为 btn.buy.rebate 填好的动词开头（「购买预估返 ¥2.69」/ 读屏「购买预估返 2.69 元」）。 */
        const val MAPPED_VERB = "购买预估返"
        val CONTEXTS = listOf(BuyButtonModel.CONTEXT_LIST, BuyButtonModel.CONTEXT_DETAIL, BuyButtonModel.CONTEXT_AGENT)
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val HAS_TEXT = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
    }
}
