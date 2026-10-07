package com.couli.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 第 2 轮评审 R2-06 / 共性第 49 条：「是否命中规则表」与导航去向分开保存。只命中 promo 的地址照常放行（load、不带
 * 决定去向的 match），hit 仍为 true；不命中任何规则的为 false；拦下的 union_host 为 true。
 */
class ExternalNavHitTest {
    private val patterns =
        Json
            .parseToJsonElement(
                """
                {"version":"hit-test","rules":[
                  {"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]},
                  {"platform":"taobao","category":"promo","hosts":["page.example.test"],"path_patterns":["^/promo/"]}
                ]}
                """.trimIndent(),
            ).jsonObject
    private val policy = ExternalNavPolicy(patterns)

    private fun decide(url: String) = policy.decide(url, isMainFrame = true, isNewWindow = false, linkPatterns = patterns)

    @Test
    fun aPromoOnlyMatchLoadsButIsAHit() {
        val decision = decide("https://page.example.test/promo/deal")
        assertEquals(ExternalNavAction.LOAD, decision.actionRaw)
        assertNull(decision.match)
        assertTrue(decision.hit)
    }

    @Test
    fun noMatchIsNotAHitAndABlockIsAHit() {
        assertFalse(decide("https://page.example.test/help").hit)
        assertTrue(decide("https://shop.taobao.example.test/x").hit)
    }
}
