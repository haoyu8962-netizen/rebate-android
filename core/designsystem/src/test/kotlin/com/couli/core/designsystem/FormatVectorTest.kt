// FILE: core/designsystem/src/test/kotlin/com/couli/core/designsystem/FormatVectorTest.kt
package com.couli.core.designsystem

import java.io.File
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatVectorTest {
    private val repositoryRoot: File by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("找不到系统属性 user.dir"),
            ).canonicalFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: throw AssertionError("从 $start 向上找不到含 contract.lock 的仓库根目录")
    }

    private val vectors: JsonObject by lazy {
        val file = File(repositoryRoot, "specs/format-vectors.json")
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also {
            assertEquals("${file.path}: version", 1, it.getValue("version").jsonPrimitive.int)
            assertTrue("${file.path}: source", it.string("source").isNotBlank())
            it.getValue("gaps").jsonArray
        }
    }

    @Test
    fun money() {
        each("money") { row, label ->
            assertEquals(
                label,
                row.string("expect"),
                CouliFormat.money(fen = row.long("fen")),
            )
        }
        each("moneySigned") { row, label ->
            assertEquals(
                label,
                row.string("expect"),
                CouliFormat.moneySigned(fen = row.long("fen")),
            )
        }
    }

    @Test
    fun rebateRange() {
        each("rebateRange") { row, label ->
            assertEquals(
                label,
                row.nullableString("expect"),
                CouliFormat.rebateRange(
                    minFen = row.long("minFen"),
                    maxFen = row.long("maxFen"),
                ),
            )
        }
    }

    @Test
    fun timesIgnoreDeviceTimeZone() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/Los_Angeles", "Asia/Tokyo", "Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                each("timeListPast") { row, label ->
                    assertEquals(
                        "$label; deviceTimeZone=$zone",
                        row.string("expect"),
                        CouliFormat.timeListPast(
                            epochMillis = row.long("epochMillis"),
                            nowEpochMillis = row.long("nowEpochMillis"),
                        ),
                    )
                }
                each("timeListFuture") { row, label ->
                    assertEquals(
                        "$label; deviceTimeZone=$zone",
                        row.string("expect"),
                        CouliFormat.timeListFuture(
                            epochMillis = row.long("epochMillis"),
                            nowEpochMillis = row.long("nowEpochMillis"),
                        ),
                    )
                }
                each("dateOnly") { row, label ->
                    assertEquals(
                        "$label; deviceTimeZone=$zone",
                        row.string("expect"),
                        CouliFormat.dateOnly(
                            isoDate = row.string("isoDate"),
                            nowEpochMillis = row.long("nowEpochMillis"),
                        ),
                    )
                }
                each("timeMinute") { row, label ->
                    assertEquals(
                        "$label; deviceTimeZone=$zone",
                        row.string("expect"),
                        CouliFormat.timeMinute(epochMillis = row.long("epochMillis")),
                    )
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun priceTag() {
        each("priceTag") { row, label ->
            val input = row.objectAt("input")
            val expected = row.objectAt("expect")
            val actual =
                PriceTagModel.make(
                    platform = input.string("platform"),
                    priceFen = input.long("priceFen"),
                    couponFen = input.long("couponFen"),
                    finalPriceFen = input.long("finalPriceFen"),
                    isPresale = input.boolean("isPresale"),
                )
            assertEquals("$label: mainLabel", expected.string("mainLabel"), actual.mainLabel)
            assertEquals("$label: mainAmount", expected.string("mainAmount"), actual.mainAmount)
            assertEquals("$label: subLine", expected.nullableString("subLine"), actual.subLine)
            assertEquals("$label: noCoupon", expected.boolean("noCoupon"), actual.noCoupon)
            assertEquals(
                "$label: presaleLabel",
                expected.nullableString("presaleLabel"),
                actual.presaleLabel,
            )
        }
    }

    @Test
    fun rebateTag() {
        each("rebateTag") { row, label ->
            assertRebate(
                actual = makeRebate(row.objectAt("input")),
                expected = row.objectAt("expect"),
                label = label,
            )
        }
    }

    @Test
    fun estNet() {
        each("estNet") { row, label ->
            val input = row.objectAt("input")
            assertEquals(
                label,
                row.nullableString("expect"),
                EstNetModel.make(
                    basis = input.string("basis"),
                    minFen = input.nullableLong("minFen"),
                    estNetPriceFen = input.nullableLong("estNetPriceFen"),
                ),
            )
        }
    }

    @Test
    fun buyButton() {
        each("buyButton") { row, label ->
            val input = row.objectAt("input")
            val rebate = rebateFixture(input.objectAt("rebate"), label)
            assertEquals(
                label,
                row.string("expect"),
                BuyButtonModel.text(
                    base = input.string("base"),
                    rebate = rebate,
                    amountHidden = input.boolean("amountHidden"),
                    context = input.string("context"),
                ),
            )
        }
    }

    private fun makeRebate(input: JsonObject): RebateTagContent =
        RebateTagModel.make(
            basis = input.string("basis"),
            minFen = input.nullableLong("minFen"),
            maxFen = input.nullableLong("maxFen"),
            loginRequired = input.boolean("loginRequired"),
        )

    private fun assertRebate(
        actual: RebateTagContent,
        expected: JsonObject,
        label: String,
    ) {
        assertEquals("$label: kindRaw", expected.string("kind"), actual.kindRaw)
        assertEquals("$label: text", expected.string("text"), actual.text)
    }

    private fun rebateFixture(
        expected: JsonObject,
        label: String,
    ): RebateTagContent {
        // 不假设 RebateTagContent 的构造器或 kind 的内部表示。
        val row =
            rows("rebateTag").firstOrNull {
                val candidate = it.objectAt("expect")
                candidate.string("kind") == expected.string("kind") &&
                    candidate.string("text") == expected.string("text")
            } ?: throw AssertionError("$label: 缺少匹配的 rebateTag 夹具")
        val actual = makeRebate(row.objectAt("input"))
        assertRebate(actual, expected, "$label: rebate fixture")
        return actual
    }

    private fun rows(group: String): List<JsonObject> {
        val result =
            vectors.objectAt("cases")
                .getValue(group)
                .jsonArray
                .map { it.jsonObject }
        assertTrue("$group: 向量必须非空", result.isNotEmpty())
        return result
    }

    private fun each(
        group: String,
        check: (JsonObject, String) -> Unit,
    ) {
        rows(group).forEachIndexed { index, row ->
            val note = row.string("note")
            val label = "$group[$index]: $note"
            assertTrue("$group[$index]: note 不能为空", note.isNotBlank())
            try {
                check(row, label)
            } catch (failure: AssertionError) {
                throw AssertionError(label, failure)
            } catch (failure: Exception) {
                throw AssertionError(label, failure)
            }
        }
    }

    private fun JsonObject.objectAt(key: String): JsonObject =
        getValue(key).jsonObject

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun JsonObject.boolean(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean

    // 直接从 JSON 数字文本读取 Long，不经 Double 中转。
    private fun JsonObject.long(key: String): Long =
        getValue(key).jsonPrimitive.long

    private fun JsonObject.nullableLong(key: String): Long? {
        val value = getValue(key)
        return if (value == JsonNull) null else value.jsonPrimitive.long
    }

    private fun JsonObject.nullableString(key: String): String? {
        val value = getValue(key)
        return if (value == JsonNull) null else value.jsonPrimitive.content
    }
}
