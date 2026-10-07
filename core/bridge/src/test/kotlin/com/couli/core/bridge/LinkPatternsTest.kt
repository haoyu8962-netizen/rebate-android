package com.couli.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-04 三端共性第 17、18 条的判定层：
 * - 17：URL 先按浏览器方式规范化（消除点段，含编码过的点段与反斜杠变体），再按 BR-ATTR-29 解码一次去匹配；
 * - 18：规则表完整校验，坏正则等任何一条不合格即坏表，整表回落到下一层；都不可用时拒绝加载；union_host 整域阻断，不看路径规则。
 * 夹具只用保留测试域。
 */
class LinkPatternsTest {
    private fun table(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val bundled =
        table(
            """
            {"version":"bundled","rules":[
              {"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]}
            ]}
            """.trimIndent(),
        )

    private val products =
        table(
            """
            {"version":"current","rules":[
              {"platform":"jd","category":"union_host","hosts":["jd.example.test"],"path_patterns":["^/never$"]},
              {"platform":"jd","category":"product","hosts":["item.jd.example.test"],"path_patterns":["^/item/[0-9]+$"]}
            ]}
            """.trimIndent(),
        )

    private val policy = ExternalNavPolicy(bundled)

    private fun action(
        url: String,
        patterns: JsonObject? = products,
    ): String = policy.decide(url, isMainFrame = true, isNewWindow = false, linkPatterns = patterns).actionRaw

    @Test
    fun dotSegmentsAreResolvedLikeABrowserBeforeMatching() {
        assertEquals("/item/42", NormalizedUrl.parse("https://item.jd.example.test/a/../item/42")?.path)
        assertEquals("/item/42", NormalizedUrl.parse("https://item.jd.example.test/a/%2e%2E/item/42")?.path)
        assertEquals("/item/42", NormalizedUrl.parse("https://item.jd.example.test/a/.%2e/./item/42")?.path)
        assertEquals("/item/42", NormalizedUrl.parse("https://item.jd.example.test/a\\..\\item\\42")?.path)
        assertEquals("/item/42", NormalizedUrl.parse("https://item.jd.example.test/a/..%5Citem/42")?.path)
        assertEquals(ExternalNavAction.TO_NATIVE_PRODUCT, action("https://item.jd.example.test/x/%2e%2e/item/42"))
        assertEquals(ExternalNavAction.TO_NATIVE_PRODUCT, action("https://item.jd.example.test/x\\..\\item/42"))
    }

    @Test
    fun backslashBeforeTheHostIsAPathSeparatorNotUserInfo() {
        val url = NormalizedUrl.parse("https://jd.example.test\\@page.example.test/")
        assertEquals("jd.example.test", url?.host)
        assertEquals(ExternalNavAction.BLOCK, action("https://jd.example.test\\@page.example.test/"))
    }

    @Test
    fun pathIsPercentDecodedOnceAfterNormalisation() {
        assertEquals("/item/42", NormalizedUrl.parse("https://item.jd.example.test/%69tem/42")?.path)
        assertEquals("解码只做一次", "/item/%34%32", NormalizedUrl.parse("https://item.jd.example.test/item/%2534%2532")?.path)
        assertNull("不合法的百分号编码无法规范化", NormalizedUrl.parse("https://item.jd.example.test/%zz"))
    }

    @Test
    fun unionHostBlocksTheWholeDomainEvenWhenItsPathRuleDoesNotMatch() {
        assertEquals(ExternalNavAction.BLOCK, action("https://jd.example.test/cart"))
        assertEquals(ExternalNavAction.BLOCK, action("https://m.jd.example.test/anything"))
    }

    @Test
    fun aSingleBadRegexMakesTheWholeTableBadAndItFallsBackToTheBundledTable() {
        val broken =
            table(
                """
                {"version":"broken","rules":[
                  {"platform":"jd","category":"union_host","hosts":["jd.example.test"],"path_patterns":[]},
                  {"platform":"taobao","category":"product","hosts":["item.taobao.example.test"],"path_patterns":["["]}
                ]}
                """.trimIndent(),
            )
        assertFalse(LinkPatterns.isValid(broken))
        assertEquals("坏表整表回落到包内表：包内表里的淘宝整域拦下", ExternalNavAction.BLOCK, action("https://taobao.example.test/cart", broken))
        assertEquals("不与坏表逐条合并", ExternalNavAction.LOAD, action("https://jd.example.test/cart", broken))
    }

    @Test
    fun malformedRulesMakeTheTableBad() {
        listOf(
            """{"version":"x","rules":[{"platform":"jd","category":"union_host","hosts":[],"path_patterns":[]}]}""",
            """{"version":"x","rules":[{"platform":"jd","category":"unknown","hosts":["jd.example.test"],"path_patterns":[]}]}""",
            """{"version":"x","rules":[{"platform":"jd","category":"union_host","hosts":["jd.example.test"]}]}""",
            """{"version":"x","rules":[{"platform":"jd","category":"union_host","hosts":[1],"path_patterns":[]}]}""",
            """{"version":"x","rules":["not an object"]}""",
        ).forEach { assertFalse(it, LinkPatterns.isValid(table(it))) }
        assertTrue(LinkPatterns.isValid(products))
    }

    @Test
    fun noUsableTableAnywhereRefusesToLoad() {
        val empty = ExternalNavPolicy(table("""{"version":"empty","rules":[]}"""))
        val broken =
            table("""{"version":"x","rules":[{"platform":"jd","category":"product","hosts":["a.example.test"],"path_patterns":["("]}]}""")
        val decision = empty.decide("https://page.example.test/", isMainFrame = true, isNewWindow = false, linkPatterns = broken)
        assertEquals(ExternalNavAction.BLOCK, decision.actionRaw)
        assertEquals(ExternalNavPolicy.RULES_UNAVAILABLE, decision.reason)
        assertEquals(0, empty.ruleCount(broken))
        assertFalse(empty.canOpenInBrowser("https://page.example.test/", broken))
    }

    @Test
    fun bridgeLinkChecksDenyEverythingWhenTheTableIsBad() {
        val protocol = BridgeProtocol(setOf("https://h5.example.test"), JsonObject(emptyMap()), emptySet())
        assertFalse(protocol.linkAllowedForBrowser("https://page.example.test/"))
        val good = BridgeProtocol(setOf("https://h5.example.test"), products, emptySet())
        assertTrue(good.linkAllowedForBrowser("https://page.example.test/"))
        assertFalse(good.linkAllowedForBrowser("https://item.jd.example.test/a/../item/1"))
    }
}
