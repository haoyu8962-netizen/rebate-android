package com.couli.app.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** H5 路由地址（APP-08 共性第 22 条）：h5_base_url + routes.json 的 h5_path，参数走查询串；拼不出的不加载。 */
class H5RouteUrlTest {
    private fun params(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun joinsBaseAndPathWithOneSlash() {
        assertEquals("https://h5.example.test/find-order", H5RouteUrl.of("https://h5.example.test/", "/find-order", params()))
        assertEquals("https://h5.example.test/app/ledger", H5RouteUrl.of("https://h5.example.test/app", "/ledger", params()))
    }

    @Test
    fun paramsBecomeAnEncodedSortedQuery() {
        assertEquals(
            "https://h5.example.test/help?a=x%20y&article_id=price-compare",
            H5RouteUrl.of("https://h5.example.test/", "/help", params("article_id" to "price-compare", "a" to "x y")),
        )
    }

    @Test
    fun unusableBaseOrPathLoadsNothing() {
        assertNull("没有下发", H5RouteUrl.of(null, "/help", params()))
        assertNull("不是 https", H5RouteUrl.of("http://h5.example.test/", "/help", params()))
        assertNull("带查询串", H5RouteUrl.of("https://h5.example.test/?x=1", "/help", params()))
        assertNull("没有 h5_path", H5RouteUrl.of("https://h5.example.test/", null, params()))
    }
}
