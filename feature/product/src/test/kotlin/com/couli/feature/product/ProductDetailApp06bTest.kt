package com.couli.feature.product

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.initializer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.DETAIL_TITLE_TAG
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.ProductDetailState
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-06b 在详情页上的接线与 F-AND-17（APP-06a 共性第 22、27、30、31 条在 Android 上的核对）：
 * - 22：商品图接契约 image 字段（经 [LocalProductImageLoader] 按 image 地址加载）；
 * - 27：按 features.platform_status 禁用购买（maintenance「稍后再试」），订阅配置变化后立即生效，点击不发出购买意图；
 * - 30：合并的「平台 + 商品名」有读屏标题角色；
 * - 31：not_launched 隐藏返利金额、预估返后及读屏金额，只留价格与禁用按钮；
 * - 比价无返利原因行（open 复核后卡片 no_rebate_cause=price_compare）与【看看相似商品】；按 open 响应更新卡片。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class ProductDetailApp06bTest {
    @get:Rule
    val rule = createComposeRule()

    private var status: String? = "on"
    private val listeners = mutableListOf<() -> Unit>()
    private val buys = mutableListOf<JsonObject>()
    private val similar = mutableListOf<JsonObject>()
    private val loaded = mutableListOf<String>()
    private var controller: ProductDetailController? = null

    private fun show(card: String = CARD) {
        val config =
            ProductPageConfig(
                api = OneAnswerApi(ApiResult.Success(Json.parseToJsonElement(card), TRACE, null)),
                texts = ScreenTexts,
                platform = "taobao",
                productKey = "tb:1",
                itemRef = null,
                context = {
                    JsonObject(
                        mapOf(
                            "loggedIn" to JsonPrimitive(true),
                            "privacy" to JsonPrimitive(ProductDetailState.PRIVACY_CONSENTED),
                            "platformStatus" to (status?.let(::JsonPrimitive) ?: JsonPrimitive("on")),
                        ),
                    )
                },
                actions = ProductPageActions(onBuyIntent = { product, _ -> buys += product }, onSimilar = { similar += it }),
                bindController = { controller = it },
                subscribeConfig = { listener ->
                    listeners += listener
                    val unsubscribe: () -> Unit = { listeners -= listener }
                    unsubscribe
                },
            )
        val loader =
            ProductImageLoader { url ->
                loaded += url
                null
            }
        rule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides loader) {
                CouliTheme { ProductDetailScreen(config) }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun lesson22_productImageComesFromTheContractImageField() {
        show()
        rule.waitUntil(TIMEOUT_MS) { loaded.isNotEmpty() }
        assertEquals(listOf("https://img.example.test/p/1.png"), loaded)
    }

    @Test
    fun lesson30_mergedPlatformAndTitleIsAHeading() {
        show()
        rule.onNodeWithTag(DETAIL_TITLE_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun lesson27_maintenanceDisablesBuyingAndFollowsConfigChanges() {
        show()
        rule.onNodeWithTag(ProductTags.BUY).assertIsEnabled()
        status = "maintenance"
        rule.runOnIdle { listeners.toList().forEach { it() } }
        rule.waitForIdle()
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled()
        rule.onNodeWithText("稍后再试").assertExists()
        rule.onNodeWithTag(ProductTags.BUY).performClick()
        assertTrue("禁用时不发出购买意图", buys.isEmpty())
        status = "on"
        rule.runOnIdle { listeners.toList().forEach { it() } }
        rule.waitForIdle()
        rule.onNodeWithTag(ProductTags.BUY).assertIsEnabled()
    }

    @Test
    fun lesson31_notLaunchedHidesRebateAmountsAndEstNet() {
        status = "not_launched"
        show()
        // 返利标签的位置与禁用按钮各一处
        rule.onAllNodesWithText("淘宝返利即将开放", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("预估返后", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("¥1.23", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("¥46.78", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled()
    }

    @Test
    fun openResponseUpdatesTheCardAndCompareReasonOffersSimilarItems() {
        show()
        rule.runOnIdle {
            controller!!.updateProduct { card ->
                JsonObject(
                    card +
                        mapOf(
                            "rebate_min_fen" to JsonPrimitive(0),
                            "rebate_max_fen" to JsonPrimitive(0),
                            "rebate_basis" to JsonPrimitive("no_rebate"),
                            "no_rebate_cause" to JsonPrimitive("price_compare"),
                            "cta" to JsonObject(mapOf("text_key" to JsonPrimitive("btn.buy.no_rebate"))),
                        ),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ProductTags.COMPARE_REASON).assertExists()
        rule.onNodeWithText("去购买（无返利）").assertExists()
        rule.onNodeWithText("看看相似商品").performClick()
        assertEquals("tb:1", (similar.single()["product_key"] as JsonPrimitive).content)
    }

    @Test
    fun lesson28_restoredDetailWaitsForTheUpdateCheckBeforeLoading() {
        var calls = 0
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult {
                    calls += 1
                    return ApiResult.Success(Json.parseToJsonElement(CARD), TRACE, null)
                }

                override fun cancelOwned(owner: CallOwnership) = Unit

                override fun epochs(): CallEpochs = CallEpochs(0, 0)
            }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val saved = androidx.lifecycle.SavedStateHandle()

        fun config() =
            ProductPageConfig(
                api = api,
                texts = ScreenTexts,
                platform = "taobao",
                productKey = "tb:1",
                itemRef = null,
                context = { JsonObject(emptyMap()) },
                restoreGate = { gate.await() },
            )

        fun model() =
            androidx.lifecycle.ViewModelProvider.create(
                androidx.lifecycle.ViewModelStore(),
                androidx.lifecycle.viewmodel.viewModelFactory {
                    initializer { ProductDetailViewModel(config(), saved) }
                },
            )[ProductDetailViewModel::class]
        val first = model()
        rule.waitForIdle()
        assertEquals("用户打开不等", 1, calls)
        val restored = model()
        rule.waitForIdle()
        assertTrue(restored.restored && !first.restored)
        assertEquals("进程回收后自动恢复：等版本检查", 1, calls)
        gate.complete(Unit)
        rule.waitUntil(TIMEOUT_MS) { calls == 2 }
    }

    private class OneAnswerApi(
        private val answer: ApiResult,
    ) : PageApi {
        override suspend fun send(call: ApiCall): ApiResult = answer

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    private object ScreenTexts : ProductTexts {
        private val drafts = mapOf("platform.taobao" to "淘宝", "card.retry_later" to "稍后再试")
        private val placeholder = Regex("\\{[a-z0-9_]+\\}")

        override fun text(key: String): String = DefaultTexts.text(key) ?: drafts[key] ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: drafts[key] ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return if (placeholder.containsMatchIn(text)) DefaultTexts.fallback(key) ?: text.replace(placeholder, "") else text
        }
    }

    private companion object {
        const val TRACE = "trace-000777"
        const val TIMEOUT_MS = 5_000L
        const val CARD =
            """{"card_id":"c1","product_key":"tb:1","platform":"taobao","title":"milk","image":"https://img.example.test/p/1.png",""" +
                """"price_fen":5678,"coupon_fen":1000,"final_price_fen":4678,"est_net_price_fen":4555,"rebate_min_fen":123,""" +
                """"rebate_max_fen":234,"rebate_basis":"normal","is_presale":false,""" +
                """"link_id":"link-1","cta":{"text_key":"btn.buy.coupon"},""" +
                """"stale":false,"age_sec":12,"quoted_at":"2026-10-02T09:30:00+08:00",""" +
                """"disclaimer_keys":["price_basis","rebate_estimate"],""" +
                """"availability":"ok"}"""
    }
}
