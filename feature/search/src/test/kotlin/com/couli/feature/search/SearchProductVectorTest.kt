/*
 * 实现前测试；只读交付，未编译运行。
 *
 * 接线约定：
 * - 六个符号位于本包或由本包转出；详情共用逻辑下沉 Core，
 *   不让 feature/product 依赖 feature/search。
 * - 对象参数及 query 使用 JsonObject；金额在实现中按 Long 读取。
 * - SearchQuery.build(input) -> { valid: Boolean, query: JsonObject,
 *   reasonRaw: String? }；filters 使用契约蛇形字段，query 为平铺参数。
 *   sort 省略与 relevance 等价；无效输入只判 valid，query:{} 是占位。
 * - SearchTabs.resolve(searchStatus) -> List<String>；只判完整成员及无重复。
 * - SearchPager()；append(response) 接收完整成功 envelope；
 *   items: List<JsonObject> 只含搜索命中，fallback_items 另行展示；
 *   endReached: Boolean，stateRaw: String。
 * - SearchResultState.map(responseOrError) 接收完整 envelope，
 *   返回 stateRaw: String、textKey: String?；复用 Core 错误映射。
 *   content/empty 表示搜索命中有/无，error 表示请求失败。
 * - SearchSession()；begin(kind: String): Long、accept(seq: Long)、leave()；
 *   shownSeq: Long?。向量 seq 是符号引用，不规定实际计数器起点。
 * - ProductDetailState.map(product, context) 接收 ProductCard 和 JsonObject，
 *   返回 buttonKeyRaw: String，以及 presale、rebateUnknown、degraded、
 *   rebateGone、publicMode: Boolean；privacy 使用 consented/basic。
 * - expect 缺字段表示不判定；显式 null 表示必须无值。
 *   degraded、rebateGone 的缺口见向量 gaps，不以 false 补造。
 * - 从 user.dir 向上寻找 contract.lock；读取仓库共享向量。
 * - 模块接入仓库已有 JUnit4、kotlinx.serialization.json 测试依赖；
 *   不使用网络、Android 运行时、真实计时器或被测 API 的假实现。
 */
package com.couli.feature.search

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProductVectorTest {
    private val groups =
        listOf(
            "searchRequest",
            "tabs",
            "paging",
            "resultState",
            "staleResponse",
            "detail",
        )

    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("找不到 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")
        val file = File(root, "specs/search-product-vectors.json")
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also { parsed ->
            assertEquals(file.path, 1, parsed.getValue("version").jsonPrimitive.int)
            assertTrue("sources", parsed.getValue("sources").jsonArray.isNotEmpty())
            assertTrue("gaps", parsed.getValue("gaps").jsonArray.isNotEmpty())
            for (group in groups) {
                val rows = parsed.rows(group)
                assertTrue("$group 不能为空", rows.isNotEmpty())
                val names = rows.map { it.text("name") }
                assertEquals("$group 名称重复", names.size, names.toSet().size)
                assertTrue("$group 名称为空", names.all { it.isNotEmpty() })
            }
        }
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
    }

    @Test
    fun searchRequestVectors() {
        for (row in vectors.rows("searchRequest")) {
            val label = "searchRequest.${row.text("name")}"
            val expected = row.objectAt("expect")
            val actual = SearchQuery.build(row.objectAt("input"))
            val valid = expected.flag("valid")
            assertEquals("$label.valid", valid, actual.valid)

            if (valid) {
                // 仅归一化契约允许省略的默认 sort，不构造被测请求。
                val normalized =
                    JsonObject(
                        actual.query +
                            ("sort" to (actual.query["sort"] ?: JsonPrimitive("relevance"))),
                    )
                assertEquals("$label.query", expected.objectAt("query"), normalized)
            }
            assertText(expected, "reason", actual.reasonRaw, label)
        }
    }

    @Test
    fun tabVectors() {
        for (row in vectors.rows("tabs")) {
            val label = "tabs.${row.text("name")}"
            val actual = SearchTabs.resolve(row.objectAt("searchStatus"))
            val expected =
                row.objectAt("expect").getValue("tabs").jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals("$label.duplicates", actual.size, actual.toSet().size)
            assertEquals("$label.members", expected.sorted(), actual.sorted())
        }
    }

    @Test
    fun pagingVectors() {
        for (row in vectors.rows("paging")) {
            val label = "paging.${row.text("name")}"
            val pages = row.rows("pages")
            val expected = row.objectAt("expect")
            val pager = SearchPager()
            val inputKeys = mutableListOf<String>()
            assertTrue(label, pages.isNotEmpty())

            for (page in pages) {
                pager.append(page)
                inputKeys += page.objectAt("data").rows("items").map { it.text("product_key") }
            }

            assertEquals("$label.items", expected.getValue("items").jsonPrimitive.int, pager.items.size)
            assertEquals("$label.endReached", expected.flag("endReached"), pager.endReached)
            assertEquals("$label.stateRaw", expected.text("stateRaw"), pager.stateRaw)
            // 向量没有重复商品；不在测试中实现排序或去重。
            assertEquals("$label.itemOrder", inputKeys, pager.items.map { it.text("product_key") })
        }
    }

    @Test
    fun resultStateVectors() {
        for (row in vectors.rows("resultState")) {
            val label = "resultState.${row.text("name")}"
            val hasResponse = "response" in row
            val hasError = "error" in row
            assertTrue("$label.oneEnvelope", hasResponse != hasError)
            val envelope = row.objectAt(if (hasResponse) "response" else "error")
            val expected = row.objectAt("expect")
            val actual = SearchResultState.map(envelope)

            assertEquals("$label.stateRaw", expected.text("stateRaw"), actual.stateRaw)
            assertText(expected, "textKey", actual.textKey, label)
        }
    }

    @Test
    fun staleResponseVectors() {
        for (row in vectors.rows("staleResponse")) {
            val label = "staleResponse.${row.text("name")}"
            val session = SearchSession()
            val tokens = mutableMapOf<Int, Long>()
            val issued = mutableSetOf<Long>()

            for ((index, step) in row.rows("steps").withIndex()) {
                val stepLabel = "$label.steps[$index]"
                when (val op = step.text("op")) {
                    "search", "changeTab", "changeSort" -> {
                        val symbol = step.getValue("seq").jsonPrimitive.int
                        assertTrue("$stepLabel.duplicateSymbol", symbol !in tokens)
                        val token = session.begin(op)
                        assertTrue(
                            "$stepLabel.tokenReusedAcrossRequestsOrLeave",
                            issued.add(token),
                        )
                        tokens[symbol] = token
                    }

                    "respond" -> {
                        val symbol = step.getValue("seq").jsonPrimitive.int
                        session.accept(
                            tokens[symbol]
                                ?: throw AssertionError("$stepLabel 未登记 seq=$symbol"),
                        )
                    }

                    "leave" -> session.leave()
                    else -> throw AssertionError("$stepLabel 未知操作 $op")
                }
            }

            val shown = row.objectAt("expect").getValue("shown")
            if (shown == JsonNull) {
                assertNull("$label.shownSeq", session.shownSeq)
            } else {
                val symbol = shown.jsonPrimitive.int
                val token =
                    tokens[symbol]
                        ?: throw AssertionError("$label 未登记 shown=$symbol")
                assertEquals("$label.shownSeq", token, session.shownSeq)
            }
        }
    }

    @Test
    fun detailVectors() {
        for (row in vectors.rows("detail")) {
            val label = "detail.${row.text("name")}"
            val expected = row.objectAt("expect")
            val actual =
                ProductDetailState.map(
                    row.objectAt("product"),
                    row.objectAt("context"),
                )

            assertText(expected, "buttonKeyRaw", actual.buttonKeyRaw, label)
            assertFlag(expected, "presale", actual.presale, label)
            assertFlag(expected, "rebateUnknown", actual.rebateUnknown, label)
            assertFlag(expected, "degraded", actual.degraded, label)
            assertFlag(expected, "rebateGone", actual.rebateGone, label)
            assertFlag(expected, "publicMode", actual.publicMode, label)
        }
    }

    private fun assertText(
        expected: JsonObject,
        key: String,
        actual: String?,
        label: String,
    ) {
        if (key !in expected) return
        val value = expected.getValue(key)
        val text = if (value == JsonNull) null else value.jsonPrimitive.content
        assertEquals("$label.$key", text, actual)
    }

    private fun assertFlag(
        expected: JsonObject,
        key: String,
        actual: Boolean,
        label: String,
    ) {
        if (key in expected) {
            assertEquals("$label.$key", expected.flag(key), actual)
        }
    }
}

private fun JsonObject.objectAt(key: String): JsonObject =
    getValue(key).jsonObject

private fun JsonObject.rows(key: String): List<JsonObject> =
    getValue(key).jsonArray.map { it.jsonObject }

private fun JsonObject.text(key: String): String =
    getValue(key).jsonPrimitive.content

private fun JsonObject.flag(key: String): Boolean =
    getValue(key).jsonPrimitive.boolean
