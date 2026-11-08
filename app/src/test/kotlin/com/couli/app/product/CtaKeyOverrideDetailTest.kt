package com.couli.app.product

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.BuyButtonOverride
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.network.ApiResult
import com.couli.feature.product.ProductDetailScreen
import com.couli.feature.product.ProductPageConfig
import com.couli.feature.product.ProductPurchaseSlots
import com.couli.feature.product.ProductTags
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-X-CTA-KEY-DRIFT 测试 v2（Codex 评审 r1 S2，详情底栏一路）：真实 ProductDetailScreen（文案函数 SearchDraftTexts，与 App 同），服务端
 * btn.buy.rebate。先不覆盖：底栏是映射填好的「购买预估返 ¥2.69」（对照）；再由购买流程给覆盖文字（ProductPurchaseSlots.button，
 * BuyButtonOverride NORMAL「安装淘宝后下单才有返利」）：底栏只显示覆盖文字（「替换动词且不拼金额」），读屏从覆盖文字开头、不沿用原来的
 * 「购买预估返」金额句、不读金额。
 * 说明：详情底栏覆盖时另传 amountHidden=true，这一条单独删掉 ProductCard.kt「按钮文字不是映射那句就不算含金额」的判断仍然是绿的；
 * 它与卡片一路的 CtaKeyOverrideTextTest 一起守：卡片一路杀「删掉该判断」，这一条杀「详情覆盖时不再隐藏金额」以及两者同时发生。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CtaKeyOverrideDetailTest {
    @get:Rule
    val rule = createComposeRule()

    private var overriding by mutableStateOf(false)

    @Test
    fun detailBottomBarOverrideTextDropsTheMappedAmountSentence() {
        val api = ProductVisualApi().apply { answer = ApiResult.Success(rebateCard(), ProductVisualApi.TRACE, null) }
        var leave: () -> Unit = {}
        val config =
            ProductPageConfig(
                api = api,
                texts = SearchDraftTexts,
                platform = "taobao",
                productKey = "tb:cta",
                itemRef = null,
                context = { CONTEXT },
                bindLeave = { leave = it },
                clock = { NOW },
                purchase =
                    ProductPurchaseSlots(
                        button = { if (overriding) BuyButtonOverride(BuyButtonState.NORMAL, OVERRIDE) else null },
                    ),
            )
        rule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides null) { CouliTheme { ProductDetailScreen(config) } }
        }
        rule.waitForIdle()
        try {
            assertEquals("对照：没覆盖时底栏是映射填好的整句", "购买预估返 ¥2.69", buy().visible())
            rule.runOnIdle { overriding = true }
            rule.waitForIdle()
            val button = buy()
            val spoken = button.spoken()
            assertEquals("覆盖文字替换动词且不拼金额", OVERRIDE, button.visible())
            assertTrue("读屏从覆盖文字开头：$spoken", spoken.startsWith(OVERRIDE))
            assertFalse("读屏不再沿用映射的金额句：$spoken", spoken.contains(MAPPED_VERB))
            assertFalse("覆盖时读屏不读金额：$spoken", spoken.contains("2.69"))
        } finally {
            rule.runOnIdle { leave() }
        }
    }

    private fun buy(): SemanticsNode = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode()

    private fun SemanticsNode.visible(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** 读屏读到的：有 contentDescription 读它，否则读可见文字。 */
    private fun SemanticsNode.spoken(): String = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: visible()

    /** 共用向量 specs/cta-key-vectors.json 的 baseCard（cta = btn.buy.rebate，单一返利 ¥2.69）。 */
    private fun rebateCard(): JsonObject =
        Json
            .parseToJsonElement(File(ProductVisualFixtures.root, "specs/cta-key-vectors.json").readText())
            .jsonObject
            .getValue("baseCard")
            .jsonObject

    private companion object {
        /** 2026-11-07 11:30:00 +08:00（与向量同一时刻）。 */
        const val NOW = 1_794_022_200_000L
        const val OVERRIDE = "安装淘宝后下单才有返利"
        const val MAPPED_VERB = "购买预估返"
        val CONTEXT =
            JsonObject(
                mapOf(
                    "loggedIn" to JsonPrimitive(true),
                    "privacy" to JsonPrimitive("consented"),
                    "platformStatus" to JsonPrimitive("on"),
                ),
            )
    }
}
