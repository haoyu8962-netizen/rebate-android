package com.couli.app.search

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardScreens
import com.couli.app.clipboard.ClipboardVisualDriver
import com.couli.app.clipboard.ClipboardVisualFixtures
import com.couli.app.product.ProductVisualApi
import com.couli.app.product.ProductVisualTestHost
import com.couli.app.search.visual.SearchVisualApi
import com.couli.app.search.visual.SearchVisualFixtures
import com.couli.app.search.visual.SearchVisualHost
import com.couli.core.designsystem.BuyButtonModel
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.MappedProductCard
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductCardVariant
import com.couli.core.network.ApiResult
import com.couli.core.router.PrivacyState
import com.couli.feature.product.ProductTags
import com.couli.feature.search.SearchHistory
import com.couli.feature.search.SearchPageActions
import com.couli.feature.search.SearchPageConfig
import com.couli.feature.search.SearchTags
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * FU-X-CTA-KEY-DRIFT（D10-856 / D10-861）App 接线：每处画购买按钮的地方用的真实文案函数与真实页面，遇到锁内没有的 cta 键都显示「购买」、可点、
 * 记一条只带键的诊断；btn.buy.rebate 显示「购买预估返 ¥2.69」。
 * - 文案函数：搜索结果卡 [searchTexts]（已同意 = [SearchDraftTexts]，基本模式 = BasicModeSearchTexts）；详情大卡 / 底栏 AppSearch 的 ProductPageConfig
 *   （[SearchDraftTexts]）；剪贴板多卡弹层 ClipboardScreens（[SearchDraftTexts]）；首页商品卡 AppModule 的 cardTexts（[SearchDraftTexts]；首页卡不画按钮，
 *   只判映射结果不漏键名）；AI 卡 AgentRoute.mapCard（[SearchDraftTexts]；AI 卡列表 / 网格两种画法另见 feature:agent 的 CtaKeyAgentCardTest）。
 * - [SearchDraftTexts] 对带 {变量} 的原文有一层占位处理（没给值就把变量删掉）：btn.buy.rebate 经 text() 取会变成「购买预估返 」、
 *   platform_coming_soon 会变成「返利即将开放」。映射照样要认出「渲染不出」（R2），按向量结果显示。
 * - 真实页面：搜索结果卡（SearchVisualHost：真实 SearchScreen / Controller / Mapper）、详情底栏（ProductVisualTestHost：真实 ProductDetailScreen）、
 *   剪贴板多卡弹层（ClipboardVisualDriver：真实壳与识别结果宿主）。页面上的诊断走默认诊断（系统日志），每条用例用各自的键（默认诊断可按键去重）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 文案函数 × 页面逐条
class CtaKeyWiringTest {
    @get:Rule
    val rule = createComposeRule()

    private var shown by mutableStateOf<Pair<MappedProductCard, String>?>(null)
    private var contentSet = false

    // ---------------------------------------------------------------- 真实文案函数

    /** 搜索 / 详情 / 剪贴板 / 首页 / AI 卡共用的文案函数：契约字典优先，升锁后 btn.buy 取契约新值「购买」。 */
    @Test
    fun realCardTextsServeTheLockedBuyTexts() {
        REAL_TEXTS.forEach { (name, texts) ->
            assertEquals("$name btn.buy（a89c1ec3a 起「购买」）", "购买", texts.text("btn.buy"))
            assertEquals(name, "购买可返利", texts.text("btn.buy.rebate_unknown"))
            assertEquals(name, "登录查看返利", texts.text("rebate_login_to_view"))
            assertEquals("$name 带金额的原文按给的值填", "购买预估返 ¥2.69", texts.format("btn.buy.rebate", mapOf("amount" to "¥2.69")))
            assertEquals("$name 锁内没有的键空串（兜底在映射里做）", "", texts.text("btn.buy.future_x"))
        }
    }

    /** 每个真实文案函数跑全部 23 条向量（与 texts 写法无关的判据：buyBase / state / diagnostic）。 */
    @Test
    fun everyRealCardTextsFollowsTheSharedVectors() {
        REAL_TEXTS.forEach { (name, texts) ->
            Vectors.cases.forEach { row ->
                val label = "$name / ${Vectors.label(row)}"
                val expect = row.getValue("expect").jsonObject
                val logged = mutableListOf<String>()
                val mapped = map(Vectors.card(row), texts, Vectors.buyEnabled(row), logged)
                assertEquals("$label buyBase", expect.string("buyBase"), mapped.data.buyBase)
                assertEquals("$label state", expect.string("state"), state(mapped.buyState))
                assertEquals("$label 诊断恰好一条、只带 text_key", listOfNotNull(expect.string("diagnostic")), logged)
            }
        }
    }

    /** 真实文案函数下各页面的整行文字（向量 title）：经真实卡片组件画出来再读。 */
    @Test
    fun realCardTextsPutTheVectorTitlesOnTheButton() {
        var checked = 0
        Vectors.cases.forEach { row ->
            val mapped = map(Vectors.card(row), SearchDraftTexts, Vectors.buyEnabled(row), mutableListOf())
            Vectors.titles(row).forEach { (context, title) ->
                show(mapped, context)
                assertEquals("SearchDraftTexts / ${Vectors.label(row)} title.$context", title, buyButtonIn(CARD).visible())
                checked += 1
            }
        }
        assertEquals(9, checked)
    }

    // ---------------------------------------------------------------- 真实页面

    @Test
    fun searchResultCardsBuyUnknownKeysAndShowTheRebateVerb() {
        ShadowLog.clear()
        val host = SearchVisualHost(rule)
        val unknown = unknownCard("btn.buy.future_search", "tb:cta-unknown")
        host.api.answer = { SearchVisualApi.success(SearchVisualFixtures.page(listOf(unknown, rebateCard("tb:cta-rebate")))) }
        val bought = mutableListOf<JsonObject>()
        var leave: () -> Unit = {}
        host.show(
            SearchPageConfig(
                api = host.api,
                config = host.port,
                texts = searchTexts(PrivacyState.CONSENTED),
                history = SearchHistory(),
                query = "洗衣液",
                platform = "taobao",
                clock = { Vectors.NOW },
                bindLeave = { leave = it },
                bindController = { host.controller = it },
                actions = SearchPageActions(onBuyIntent = { card, _ -> bought += card }),
            ),
        )
        rule.waitForIdle()
        try {
            assertEquals("content", host.controller.state.value.phase)
            val tag = SearchTags.CARD_PREFIX + "tb:cta-unknown"
            val button = buyButtonIn(tag)
            assertEquals("搜索结果卡：锁内没有的键显示「购买」", "购买", button.visible())
            assertTrue("可点", button.enabled())
            rule.onNode(buyMatcher(tag)).performClick()
            rule.waitForIdle()
            assertEquals("点了照常发出购买意图", listOf("tb:cta-unknown"), bought.map { it.string("product_key") })
            assertDiagnosed("btn.buy.future_search", unknown)
            val rebate = buyButtonIn(SearchTags.CARD_PREFIX + "tb:cta-rebate")
            assertEquals("搜索结果卡整行（向量 known.rebate.list title.list）", "购买预估返 ¥2.69", rebate.visible())
            assertSpeaksVerbFirstAndAmountOnce(rebate)
        } finally {
            rule.runOnIdle { leave() }
        }
    }

    @Test
    fun detailBottomBarBuysUnknownKeysWithTheBuyVerb() {
        ShadowLog.clear()
        val host = ProductVisualTestHost(rule)
        val card = unknownCard("btn.buy.future_detail", "tb:visual-milk")
        host.api.answer = ApiResult.Success(card, ProductVisualApi.TRACE, null)
        try {
            host.launch()
            val node = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode()
            assertEquals("详情底栏：锁内没有的键显示「购买」", "购买", node.visible())
            assertTrue("可点", node.enabled())
            rule.onNodeWithTag(ProductTags.BUY).performClick()
            rule.waitForIdle()
            assertEquals("点了照常发出购买意图", 1, host.buys.size)
            assertDiagnosed("btn.buy.future_detail", card)
        } finally {
            host.close()
        }
    }

    @Test
    fun detailBottomBarShowsTheRebateVerbAndSpeaksTheAmountOnce() {
        val host = ProductVisualTestHost(rule)
        host.api.answer = ApiResult.Success(rebateCard("tb:visual-milk"), ProductVisualApi.TRACE, null)
        try {
            host.launch()
            val node = rule.onNodeWithTag(ProductTags.BUY).fetchSemanticsNode()
            assertEquals("详情底栏整行（向量 known.rebate.list title.detail）", "购买预估返 ¥2.69", node.visible())
            assertTrue(node.enabled())
            assertSpeaksVerbFirstAndAmountOnce(node)
        } finally {
            host.close()
        }
    }

    @Test
    fun clipboardCardListBuysUnknownKeysAndShowsTheRebateVerb() {
        ShadowLog.clear()
        val driver = ClipboardVisualDriver(rule)
        try {
            driver.launch()
            val unknownCta: Map<String, JsonElement> = mapOf("cta" to Vectors.cta("btn.buy.future_clip"))
            val unknown = Vectors.merge(ClipboardVisualFixtures.card(key = "tb:cta-clip-unknown"), unknownCta)
            val rebate = Vectors.merge(ClipboardVisualFixtures.card(key = "tb:cta-clip-rebate"), REBATE_FIELDS)
            driver.parse(ClipboardVisualFixtures.cards(unknown, rebate))
            val first = buyButtonIn(ClipboardScreens.CARD_PREFIX + 0)
            assertEquals("剪贴板多卡弹层：锁内没有的键显示「购买」", "购买", first.visible())
            assertTrue("可点", first.enabled())
            assertDiagnosed("btn.buy.future_clip", unknown)
            val second = buyButtonIn(ClipboardScreens.CARD_PREFIX + 1)
            assertEquals("剪贴板多卡弹层整行", "购买预估返 ¥2.69", second.visible())
            assertTrue(second.enabled())
        } finally {
            driver.close()
        }
    }

    // ---------------------------------------------------------------- 辅助

    private fun map(
        card: JsonObject,
        texts: ProductCardTexts,
        buyEnabled: Boolean,
        logged: MutableList<String>,
    ): MappedProductCard =
        ProductCardMapper.map(card, texts, nowMillis = Vectors.NOW, buyEnabled = buyEnabled, onCtaFallback = { logged += it })

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

    @Composable
    private fun Card(
        card: MappedProductCard,
        context: String,
    ) {
        ProductCardList(
            data = card.data,
            onBuy = {},
            modifier = Modifier.testTag(CARD),
            variant = if (context == BuyButtonModel.CONTEXT_AGENT) ProductCardVariant.AI else ProductCardVariant.SEARCH,
            buyState = card.buyState,
            context = context,
            retryLaterText = card.retryLaterText,
        )
    }

    private fun buyMatcher(tag: String): SemanticsMatcher = hasAnyAncestor(hasTestTag(tag)) and IS_BUTTON and HAS_TEXT

    /** [tag] 里带字的按钮只有购买按钮（卡片本身可点但不算它的后代；口径 i 只有读屏名、没有字）。 */
    private fun buyButtonIn(tag: String): SemanticsNode {
        val nodes = rule.onAllNodes(buyMatcher(tag)).fetchSemanticsNodes()
        assertEquals("$tag 里带字的按钮只有购买按钮：${nodes.map { it.visible() }}", 1, nodes.size)
        return nodes.single()
    }

    /** 读屏（D10-861 ⑤只钉三条）：以可见动词开头、不读花括号、金额只读一次。 */
    private fun assertSpeaksVerbFirstAndAmountOnce(node: SemanticsNode) {
        val spoken = node.spoken()
        assertTrue("读屏以可见动词开头：$spoken", spoken.startsWith("购买"))
        assertFalse("读屏不读花括号：$spoken", spoken.contains('{') || spoken.contains('}'))
        assertEquals("「预估返」只读一次：$spoken", 1, spoken.split("预估返").size - 1)
        assertEquals("金额只读一次：$spoken", 1, spoken.split("2.69").size - 1)
    }

    /** 页面上的诊断经默认诊断进系统日志：有一条带 [key]，且带它的日志不含这张卡的标题、商品键、item_ref、link_id、店铺。 */
    private fun assertDiagnosed(
        key: String,
        card: JsonObject,
    ) {
        val all = ShadowLog.getLogs().map { "${it.tag} ${it.msg}" }
        val lines = all.filter { it.contains(key) }
        assertTrue("默认诊断写下 $key（共 ${all.size} 行日志）", lines.isNotEmpty())
        listOf("title", "product_key", "item_ref", "link_id", "shop_name").mapNotNull { card.string(it) }.forEach { value ->
            assertTrue("诊断只带 text_key，不带 $value：$lines", lines.none { it.contains(value) })
        }
    }

    private fun SemanticsNode.visible(): String = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun SemanticsNode.spoken(): String = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: visible()

    private fun SemanticsNode.enabled(): Boolean =
        !config.contains(SemanticsProperties.Disabled) && config.contains(SemanticsActions.OnClick)

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun state(state: BuyButtonState): String =
        when (state) {
            BuyButtonState.NORMAL -> "normal"
            BuyButtonState.LOADING -> "loading"
            BuyButtonState.DISABLED -> "disabled"
            BuyButtonState.RETRY_LATER -> "retry_later"
        }

    /** 锁内没有的键：区间返利（整行只有动词，不涉及「 · 预估返」后缀的去留）。 */
    private fun unknownCard(
        key: String,
        productKey: String,
    ): JsonObject =
        Vectors.card(
            key,
            mapOf(
                "product_key" to JsonPrimitive(productKey),
                "rebate_min_fen" to JsonPrimitive(135L),
                "rebate_max_fen" to JsonPrimitive(269L),
            ),
        )

    /** 服务端 btn.buy.rebate（staging 现状）：baseCard 单一返利 ¥2.69。 */
    private fun rebateCard(productKey: String): JsonObject =
        Vectors.card("btn.buy.rebate", mapOf("product_key" to JsonPrimitive(productKey)))

    private companion object {
        const val CARD = "cta_key_card"
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val HAS_TEXT = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        val REAL_TEXTS: List<Pair<String, ProductCardTexts>> =
            listOf(
                "SearchDraftTexts（搜索 / 详情 / 剪贴板 / 首页 / AI 卡）" to SearchDraftTexts,
                "searchTexts(BASIC_MODE)（基本模式搜索）" to searchTexts(PrivacyState.BASIC_MODE),
            )
        val REBATE_FIELDS: Map<String, JsonElement> =
            mapOf(
                "rebate_basis" to JsonPrimitive("normal"),
                "rebate_min_fen" to JsonPrimitive(269L),
                "rebate_max_fen" to JsonPrimitive(269L),
                "est_net_price_fen" to JsonPrimitive(1076L),
                "cta" to Vectors.cta("btn.buy.rebate"),
            )
    }
}

/** specs/cta-key-vectors.json（三端照抄）的读取；harness 同向量：baseCard 逐字段被 card 覆盖，值为 null 表示删掉该字段。 */
private object Vectors {
    const val NOW = 1_794_022_200_000L

    private val root: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }

    private val data: JsonObject by lazy { Json.parseToJsonElement(File(root, "specs/cta-key-vectors.json").readText()).jsonObject }

    private val base: JsonObject get() = data.getValue("baseCard").jsonObject

    val cases: List<JsonObject> get() = data.getValue("cases").jsonArray.map { it.jsonObject }

    fun merge(
        base: JsonObject,
        overrides: Map<String, JsonElement>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        overrides.forEach { (key, value) ->
            if (value is JsonNull) {
                out.remove(key)
            } else {
                out[key] = value
            }
        }
        return JsonObject(out)
    }

    fun card(row: JsonObject): JsonObject = merge(base, row.getValue("card").jsonObject)

    fun card(
        ctaKey: String,
        extra: Map<String, JsonElement>,
    ): JsonObject = merge(merge(base, mapOf("cta" to cta(ctaKey))), extra)

    fun cta(key: String): JsonObject = JsonObject(mapOf("text_key" to JsonPrimitive(key)))

    fun label(row: JsonObject): String = "${row.getValue("id").jsonPrimitive.content}（${row.getValue("note").jsonPrimitive.content}）"

    fun buyEnabled(row: JsonObject): Boolean = (row["buyEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true

    fun titles(row: JsonObject): Map<String, String> =
        (row.getValue("expect").jsonObject["title"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
}
