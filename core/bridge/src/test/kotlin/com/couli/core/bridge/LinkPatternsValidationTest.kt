package com.couli.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APP-04 三端共性第 40 条（iOS I-R2-01）：规则逐条完整校验——类别必须是契约枚举 link_pattern_category 的取值，主机只能是合法主机名
 * （不带协议、路径、端口）；任一条无效整表算坏表，由 [ExternalNavPolicy] 整表回落到包内快照。
 */
class LinkPatternsValidationTest {
    private fun table(
        host: String,
        category: String = "union_host",
    ): JsonObject =
        Json
            .parseToJsonElement(
                """{"version":"t","rules":[
                  {"platform":"jd","category":"union_host","hosts":["jd.example.test"],"path_patterns":[]},
                  {"platform":"taobao","category":"$category","hosts":["$host"],"path_patterns":[]}
                ]}""",
            ).jsonObject

    @Test
    fun legalHostsAndContractCategoriesPass() {
        listOf("taobao.example.test", "M.Taobao.example.test.", "a-b.c1.example.test").forEach {
            assertNotNull(it, LinkPatterns.parse(table(it)))
        }
        listOf("product", "promo", "union_host").forEach { assertNotNull(it, LinkPatterns.parse(table("t.example.test", it))) }
    }

    @Test
    fun anyRuleWithAMalformedHostMakesTheWholeTableBad() {
        listOf(
            "https://taobao.example.test",
            "taobao.example.test/cart",
            "taobao.example.test:443",
            "user@taobao.example.test",
            "*.taobao.example.test",
            "taobao..example.test",
            "-taobao.example.test",
            "taobao example.test",
            " ",
        ).forEach { assertNull("bad host $it", LinkPatterns.parse(table(it))) }
    }

    @Test
    fun aCategoryOutsideTheContractEnumMakesTheWholeTableBad() {
        listOf("unknown", "Union_Host", "").forEach { assertNull("bad category '$it'", LinkPatterns.parse(table("t.example.test", it))) }
    }

    @Test
    fun aBadCurrentTableFallsBackWholeToTheBundledSnapshot() {
        val bundled = table("taobao.example.test")
        val policy = ExternalNavPolicy(bundled)
        // 当前表的第二条主机带了协议：整表作废，不逐条跳过；按包内快照判，jd 规则（只在当前表里）不生效
        val current =
            Json
                .parseToJsonElement(
                    """{"version":"c","rules":[
                      {"platform":"pdd","category":"union_host","hosts":["pdd.example.test"],"path_patterns":[]},
                      {"platform":"taobao","category":"union_host","hosts":["https://taobao.example.test"],"path_patterns":[]}
                    ]}""",
                ).jsonObject
        assertEquals(ExternalNavAction.LOAD, policy.decide("https://pdd.example.test/x", true, false, current).actionRaw)
        assertEquals(ExternalNavAction.BLOCK, policy.decide("https://taobao.example.test/x", true, false, current).actionRaw)
    }
}
