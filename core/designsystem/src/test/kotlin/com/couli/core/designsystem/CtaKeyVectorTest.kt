package com.couli.core.designsystem

import com.couli.core.designsystem.CtaKeyVectors.text
import com.couli.core.designsystem.CtaKeyVectors.textOrNull
import com.couli.generated.DefaultTexts
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-CTA-KEY-DRIFT（D10-856 / D10-857 / D10-861）：商品卡 cta.text_key → 购买按钮动词、状态与诊断（纯 JVM，只看映射结果）。
 * 规则 R1–R4 与 23 条向量在 specs/cta-key-vectors.json（三端照抄、逐字节一致）；各页面整行文字、读屏与默认诊断进系统日志见 [CtaKeyButtonTest]，
 * App 里每处真实文案函数与真实页面见 app 的 CtaKeyWiringTest。
 *
 * 被测接口（实现者按此提供；本文件不提供假实现）：
 * - `ProductCardMapper.map(card, texts, nowMillis, buyEnabled, purchaseStatus, onCtaFallback)`：末位新参数 `onCtaFallback: (String) -> Unit`，
 *   缺省值是默认诊断（经 android.util.Log 写一条，只带 text_key；在不带 Robolectric 的纯 JVM 单测里也不能抛错）。每次映射遇到渲染不出的 cta 键
 *   调一次、只给 text_key 原值；没有 cta / 空键（R4）与渲染得出的键都不调。
 * - `ProductCardKeys.BUY_FALLBACK = "btn.buy"`：兜底文案键（先经调用方 texts 取，给不出可用文字时用包内契约字典 DefaultTexts）。
 * - 已知键经契约字典取字、不再写死 [PriceTerms]（a89c1ec3a 起 btn.buy 为「购买」）；「动词已含金额」的写法由实现者定（iOS 评审 S2-2 建议由映射显式带出）。
 */
@Suppress("TooManyFunctions") // 向量 + 逐条性质
class CtaKeyVectorTest {
    private val contract = CtaKeyVectors.texts("contract")

    private fun map(
        card: JsonObject,
        texts: ProductCardTexts = contract,
        buyEnabled: Boolean = true,
        purchaseStatus: String? = null,
        logged: MutableList<String> = mutableListOf(),
    ): MappedProductCard =
        ProductCardMapper.map(
            card,
            texts,
            nowMillis = CtaKeyVectors.NOW,
            buyEnabled = buyEnabled,
            purchaseStatus = purchaseStatus,
            onCtaFallback = { logged += it },
        )

    // ---------------------------------------------------------------- 向量

    @Test
    fun vectorFileIsTheSharedThreePlatformCopy() {
        assertEquals("与 iOS 原件逐字节一致（D10-861）", CtaKeyVectors.SHARED_SHA256, sha256(CtaKeyVectors.file.readBytes()))
        val data = CtaKeyVectors.data
        assertEquals(1, data.getValue("version").jsonPrimitive.int)
        assertEquals(CtaKeyVectors.LOCK_COMMIT, data.text("contractCommit"))
        assertEquals(MODES.toSet(), data.getValue("textsModes").jsonObject.keys)
        val cases = CtaKeyVectors.cases
        assertEquals(23, cases.size)
        val ids = cases.map { it.text("id") }
        assertEquals("id 不重复", ids.size, ids.toSet().size)
        assertEquals("三种 texts 写法都有 case", MODES.toSet(), cases.map { it.text("texts") }.toSet())
        cases.forEach { row ->
            val label = CtaKeyVectors.label(row)
            assertTrue("$label：note 不能为空", row.text("note").isNotBlank())
            val expect = CtaKeyVectors.expect(row)
            assertTrue("$label：expect 有 buyBase / state / diagnostic", listOf("buyBase", "state", "diagnostic").all { it in expect })
            assertTrue("$label：title 只给 list / detail / agent", CtaKeyVectors.titles(row).keys.all { it in CONTEXTS })
        }
    }

    @Test
    fun everyVectorMapsToTheButtonVerbStateAndDiagnostic() {
        CtaKeyVectors.cases.forEach { row ->
            val label = CtaKeyVectors.label(row)
            val expect = CtaKeyVectors.expect(row)
            val logged = mutableListOf<String>()
            val texts = CtaKeyVectors.texts(row.text("texts"))
            val mapped = map(CtaKeyVectors.card(row), texts, CtaKeyVectors.buyEnabled(row), logged = logged)
            val base = mapped.data.buyBase
            assertEquals("$label buyBase", expect.text("buyBase"), base)
            assertEquals("$label state", expect.text("state"), CtaKeyVectors.state(mapped.buyState))
            assertEquals("$label 诊断恰好一条、只带 text_key（null 为一条都不记）", listOfNotNull(expect.textOrNull("diagnostic")), logged)
            assertFalse("$label 按钮不出现花括号：$base", base.contains('{') || base.contains('}'))
            val key = CtaKeyVectors.ctaKey(row)
            assertFalse("$label 按钮不出现键名：$base", key.isNotEmpty() && base.contains(key))
            assertEquals("$label ctaKey 仍是服务端原值（不改写成兜底键）", key, mapped.ctaKey)
        }
    }

    // ---------------------------------------------------------------- 性质（安卓侧另钉）

    /** 锁内没有的键：多种新键 × 三种 texts 写法，按钮都是「购买」、normal、记一条只带键的诊断（缺键本身不置灰）。 */
    @Test
    fun anyKeyTheDictionaryLacksStillBuysWithTheBuyVerb() {
        UNKNOWN_KEYS.forEach { key ->
            MODES.forEach { mode ->
                val logged = mutableListOf<String>()
                val mapped = map(CtaKeyVectors.card(key), CtaKeyVectors.texts(mode), logged = logged)
                assertEquals("$key / $mode", "购买", mapped.data.buyBase)
                assertEquals("$key / $mode：缺键本身不置灰", BuyButtonState.NORMAL, mapped.buyState)
                assertEquals("$key / $mode", listOf(key), logged)
            }
        }
    }

    /** 兜底先经调用方 texts 取 btn.buy（草稿 / 下发文案可覆盖）；texts 给的 btn.buy 本身渲染不出时用包内契约字典。 */
    @Test
    fun fallbackVerbGoesThroughTheCallerTextsBeforeTheBundledDictionary() {
        assertEquals("btn.buy", ProductCardKeys.BUY_FALLBACK)
        val logged = mutableListOf<String>()
        val drafted = ProductCardTexts { key -> if (key == "btn.buy") "立即购买" else "" }
        val mapped = map(CtaKeyVectors.card("btn.buy.future_x"), drafted, logged = logged)
        assertEquals("调用方 texts 给得出 btn.buy 就用它", "立即购买", mapped.data.buyBase)
        assertEquals(BuyButtonState.NORMAL, mapped.buyState)
        assertEquals(listOf("btn.buy.future_x"), logged)
        listOf("", "btn.buy", "{verb}购买").forEach { broken ->
            val texts = ProductCardTexts { key -> if (key == "btn.buy") broken else "" }
            assertEquals("texts(btn.buy)=「$broken」→ 包内契约字典", "购买", map(CtaKeyVectors.card("btn.buy.future_x"), texts).data.buyBase)
        }
    }

    /** 诊断不带用户数据：只有 text_key 原值，不含标题、手机号、商品键、item_ref、link_id、店铺、价格。 */
    @Test
    fun diagnosticCarriesTheTextKeyAndNothingFromTheCard() {
        val logged = mutableListOf<String>()
        map(CtaKeyVectors.card("btn.buy.future_x", SENSITIVE), logged = logged)
        assertEquals(listOf("btn.buy.future_x"), logged)
        SECRETS.forEach { secret -> assertFalse("诊断里不该有 $secret", logged.joinToString().contains(secret)) }
    }

    /** btn.buy.rebate：按钮动词里的金额就是同卡返利标签的那个格式化结果（CouliFormat.money，整数分）。 */
    @Test
    fun rebateVerbUsesTheRebateTagsOwnFormattedAmount() {
        listOf(1L, 269L, 500L, 123_456L).forEach { fen ->
            val amounts = mapOf("rebate_min_fen" to JsonPrimitive(fen), "rebate_max_fen" to JsonPrimitive(fen))
            val mapped = map(CtaKeyVectors.card("btn.buy.rebate", amounts))
            val tag = mapped.data.rebate
            assertEquals("$fen 分：标签单一金额", CouliFormat.money(fen), tag.singleAmount)
            assertEquals("$fen 分：标签整句", "${PriceTerms.EST_REBATE} ${CouliFormat.money(fen)}", tag.text)
            assertEquals("$fen 分：动词里的金额与标签同一格式化结果", "购买预估返 ${tag.singleAmount}", mapped.data.buyBase)
        }
    }

    /** 平台状态照旧压过兜底；未开放隐藏返利金额时 btn.buy.rebate 的动词也不带金额（金额与返利标签同源）。 */
    @Test
    fun platformStatusStillWinsAndNotLaunchedVerbCarriesNoAmount() {
        val texts =
            ProductCardTexts { key ->
                when (key) {
                    "platform.taobao" -> "淘宝"
                    ProductCardKeys.RETRY_LATER -> "稍后再试"
                    else -> DefaultTexts.text(key).orEmpty()
                }
            }
        val unknown = CtaKeyVectors.card("btn.buy.future_x")
        val maintenance = map(unknown, texts, purchaseStatus = ProductCardMapper.PURCHASE_MAINTENANCE)
        assertEquals(BuyButtonState.RETRY_LATER, maintenance.buyState)
        assertEquals("稍后再试", maintenance.retryLaterText)
        val notLaunched = map(unknown, texts, purchaseStatus = ProductCardMapper.PURCHASE_NOT_LAUNCHED)
        assertEquals(BuyButtonState.RETRY_LATER, notLaunched.buyState)
        assertEquals("淘宝返利即将开放", notLaunched.retryLaterText)
        assertEquals("on 照常可点", BuyButtonState.NORMAL, map(unknown, texts, purchaseStatus = ProductCardMapper.PURCHASE_ON).buyState)
        val rebate = map(CtaKeyVectors.card("btn.buy.rebate"), texts, purchaseStatus = ProductCardMapper.PURCHASE_NOT_LAUNCHED)
        assertEquals(BuyButtonState.RETRY_LATER, rebate.buyState)
        assertFalse("未开放不漏返利金额：${rebate.data.buyBase}", rebate.data.buyBase.contains(PriceTerms.YUAN_SIGN))
        assertEquals("购买", rebate.data.buyBase)
    }

    /** R4：没有 cta、空对象、text_key 为 null / 空串 / 非字符串：不兜底、按钮禁用、不记诊断（与详情「没有键不可买」一致）。 */
    @Test
    fun missingCtaNeverFallsBackNorLogs() {
        val shapes: List<Pair<String, Map<String, JsonElement>>> =
            listOf(
                "没有 cta" to mapOf("cta" to JsonNull),
                "cta 为空对象" to mapOf("cta" to JsonObject(emptyMap())),
                "text_key 为 null" to mapOf("cta" to JsonObject(mapOf("text_key" to JsonNull))),
                "text_key 空串" to mapOf("cta" to CtaKeyVectors.cta("")),
                "text_key 不是字符串" to mapOf("cta" to JsonObject(mapOf("text_key" to JsonPrimitive(1)))),
            )
        shapes.forEach { (name, extra) ->
            MODES.forEach { mode ->
                val logged = mutableListOf<String>()
                val mapped = map(CtaKeyVectors.merge(CtaKeyVectors.base, extra), CtaKeyVectors.texts(mode), logged = logged)
                assertEquals("$name / $mode", "", mapped.data.buyBase)
                assertEquals("$name / $mode", BuyButtonState.DISABLED, mapped.buyState)
                assertEquals("$name / $mode", emptyList<String>(), logged)
            }
        }
    }

    /** 详情可买判定（ProductDetailState，资金路径不动）：锁内没有的键照常可买、原样保留键；没有键 / 空键不可买。现状已符合，作守卫。 */
    @Test
    fun detailStillBuysKeysTheDictionaryLacksAndBlocksMissingOnes() {
        val context = JsonObject(emptyMap())
        val unknown = ProductDetailState.map(CtaKeyVectors.card("btn.buy.future_x"), context)
        assertEquals("btn.buy.future_x", unknown.buttonKeyRaw)
        assertTrue(unknown.buyEnabled)
        assertFalse("没有 cta", ProductDetailState.map(CtaKeyVectors.merge(CtaKeyVectors.base, mapOf("cta" to JsonNull)), context).buyEnabled)
        assertFalse("空键", ProductDetailState.map(CtaKeyVectors.card(""), context).buyEnabled)
    }

    /** 没注入 onCtaFallback 时走默认诊断：在不带 Robolectric 的纯 JVM 单测里也照常兜底、不抛错（别的模块的纯 JVM 用例会走到）。 */
    @Test
    fun defaultDiagnosticIsSafeOnThePlainJvm() {
        val mapped = ProductCardMapper.map(CtaKeyVectors.card("btn.buy.future_jvm"), contract, nowMillis = CtaKeyVectors.NOW)
        assertEquals("购买", mapped.data.buyBase)
        assertEquals(BuyButtonState.NORMAL, mapped.buyState)
    }

    // ---------------------------------------------------------------- 锁（提交 1；未升锁的工作树上红）

    @Test
    fun contractLockIsCt26tAndDesignTokensStayPut() {
        val lock = Json.parseToJsonElement(File(CtaKeyVectors.root, "contract.lock").readText()).jsonObject
        assertEquals("D10-857", CtaKeyVectors.LOCK_COMMIT, lock.getValue("rebate_platform").jsonObject.text("commit"))
        assertEquals("design_tokens 不动", DESIGN_LOCK_COMMIT, lock.getValue("design_tokens").jsonObject.text("commit"))
    }

    /** CT-LOCK-02：升锁后与 iOS 同名快照逐字节一致（iOS HomePageApiLockTests.sameNameHashes 四项）；包内字典与快照同一字节。 */
    @Test
    fun lockedSnapshotsMatchTheOtherPlatforms() {
        SAME_NAME_SHA256.forEach { (path, hash) ->
            assertEquals(path, hash, sha256(File(CtaKeyVectors.root, path).readBytes()))
        }
    }

    @Test
    fun lockedDictionaryHasTheBuyTexts() {
        assertEquals("购买", DefaultTexts.text("btn.buy"))
        assertEquals("购买预估返 {amount}", DefaultTexts.text("btn.buy.rebate"))
        assertEquals("购买可返利", DefaultTexts.text("btn.buy.rebate_unknown"))
        assertEquals("登录查看返利", DefaultTexts.text("rebate_login_to_view"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val MODES = listOf("contract", "echo", "none")
        val CONTEXTS = setOf(BuyButtonModel.CONTEXT_LIST, BuyButtonModel.CONTEXT_DETAIL, BuyButtonModel.CONTEXT_AGENT)
        const val DESIGN_LOCK_COMMIT = "f088f0473230d1e70232b8a246b92ef53d723929"

        val UNKNOWN_KEYS =
            listOf(
                "btn.buy.future_x",
                "btn.buy.rebate.v2",
                "cta.next.buy",
                "x",
                "btn.buy.rebate_suffix.new",
                "BTN.BUY",
                "btn.buy.{amount}",
                "购买按钮",
            )

        val SENSITIVE: Map<String, JsonElement> =
            mapOf("title" to JsonPrimitive("张三 13800001111 的收货地址"), "shop_name" to JsonPrimitive("某某旗舰店"))
        val SECRETS = listOf("张三", "13800001111", "tb:cta", "ref-cta", "0199a3b4", "某某旗舰店", "2990", "29.9")

        const val TEXTS_SHA256 = "5cdeb62c000f23d926bc53f8ffa60779d47568a56ff2126a2d87bb12f752776c"
        val SAME_NAME_SHA256 =
            listOf(
                "contracts/snapshot/contracts/enums/admin.json" to "b2f84f6c3749f43793c11fbbaf58b3035752f2e8eab200d2857e6f745b1e0db2",
                "contracts/snapshot/contracts/error-codes.json" to "a312384e185aefcc1db7f0538ac98b63082b064a3c9fe77f19a0a889b6a398db",
                "contracts/snapshot/contracts/openapi.json" to "b8d2295f260f567d780bf628189980279d14c3ce152b5c5644b36b76bda7607d",
                "contracts/snapshot/contracts/texts.default.json" to TEXTS_SHA256,
                "core/generated/src/main/resources/texts.default.json" to TEXTS_SHA256,
            )
    }
}
