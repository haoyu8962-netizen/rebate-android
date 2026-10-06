package com.couli.core.update

import java.io.File
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

/*
 * 实现前测试；只读会话未编译或运行。
 *
 * 接线约定：
 * - 构造器、方法及参数名严格按 brief.md §2.1。
 * - 实现提供 MemoryUpdateStore()，可由重建的 Gate 复用。
 *   不约束存储字段或序列化格式。
 * - clock: () -> Long；now 为毫秒；platform/channel 接受原始字符串。
 * - 内部列表元素：UpdateStoreListing(store: String, version: String)。
 * - UpdateCheckResult 使用 brief 中的同名构造参数。
 * - installSource 是外层已核对并归一化的商店键；
 *   canOpen 是能力替身，不声称示例键已有真实商店配置。
 * - result 是 check.data；listed_version=null 的条目不进入内部已知版本列表。
 *   recheckIntervalSec 来自配置，测试使用 BR-ID-01 包内默认 1800。
 * - prompt 的状态目标/暂不更新记录键是 latest；
 *   选店按 BR-ID-01 使用 recommended；force 使用 minSupported。
 * - coldStart 重建 Gate、复用 store；其他步骤保留当前 Gate。
 * - 本模块声明仓库已有的 JUnit4、kotlinx.serialization.json 测试依赖。
 *   不使用 Robolectric、网络、真实商店或 Thread.sleep。
 */
class UpdateGateVectorTest {
    private class VectorClock {
        var nowMillis: Long = 0L
    }

    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("找不到 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到含 contract.lock 的仓库根")
        val file = File(root, "specs/update-vectors.json")

        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also {
            assertEquals(file.path, 1, it.getValue("version").jsonPrimitive.int)
            assertTrue(file.path, it.getValue("sources").jsonArray.isNotEmpty())
            assertTrue(file.path, it.getValue("gaps").jsonArray.isNotEmpty())
            assertTrue(file.path, it.rows("versionCompare").isNotEmpty())

            for (group in listOf("gate", "pickStore")) {
                val rows = it.rows(group)
                assertTrue("$group 不能为空", rows.isNotEmpty())
                val names = rows.map { row -> row.text("name") }
                assertEquals("$group 名称重复", names.size, names.toSet().size)
                for (row in rows) {
                    assertTrue("$group.name", row.text("name").isNotBlank())
                    assertTrue("$group.note", row.text("note").isNotBlank())
                }
            }
        }
    }

    @Test
    fun versionComparisonVectors() {
        for ((index, row) in vectors.rows("versionCompare").withIndex()) {
            val a = row.text("a")
            val b = row.text("b")
            val expected = row.getValue("expect").jsonPrimitive.int
            val label = "versionCompare[$index] $a / $b"

            assertTrue(label, expected in -1..1)
            assertEquals(
                label,
                expected,
                UpdateGate.compareVersions(a = a, b = b),
            )
        }
    }

    @Test
    fun gateVectors() {
        for (row in vectors.rows("gate")) {
            checkGate(row)
        }
    }

    @Test
    fun storeChoiceVectors() {
        for (row in vectors.rows("pickStore")) {
            checkStoreChoice(row)
        }
    }

    private fun checkGate(row: JsonObject) {
        val label = "${row.text("name")}: ${row.text("note")}"
        val store = MemoryUpdateStore()
        val clock = VectorClock()
        val steps = row.rows("steps")
        assertTrue("$label: steps 不能为空", steps.isNotEmpty())

        fun makeGate(): UpdateGate =
            UpdateGate(
                store = store,
                clock = { clock.nowMillis },
                appVersion = row.text("appVersion"),
                platform = "android",
                channel = "official",
                installSource = "huawei",
            )

        var gate = makeGate()

        for ((index, step) in steps.withIndex()) {
            val op = step.text("op")
            val context = "$label step[$index] $op"
            clock.nowMillis = step.getValue("atMillis").jsonPrimitive.long
            val wire = step.getValue("result")
            var shouldCheck: Boolean? = null

            when (op) {
                "coldStart" -> {
                    gate = makeGate()
                    shouldCheck = gate.onColdStart(now = clock.nowMillis)
                }

                "foreground" -> {
                    shouldCheck = gate.onForeground(now = clock.nowMillis)
                }

                "apply" -> {
                    check(wire != JsonNull) { "$context: apply 缺少 result" }
                    gate.apply(
                        result = adapt(wire.jsonObject),
                        now = clock.nowMillis,
                    )
                }

                "applyFailure" -> gate.applyFailure(now = clock.nowMillis)

                "on10405" -> {
                    // 不调用 apply，不能把 10405 当作一次成功检查。
                    val minimum =
                        if (wire == JsonNull) {
                            null
                        } else {
                            wire.jsonObject.nullableText("min_supported_version")
                        }
                    gate.on10405(
                        minSupported = minimum,
                        now = clock.nowMillis,
                    )
                }

                "dismissPrompt" -> gate.dismissPrompt(now = clock.nowMillis)

                else -> throw AssertionError("$context: 未知 op，不能静默跳过")
            }

            val expected = step.objectAt("expect")
            val expectedCheck = expected["shouldCheck"]?.jsonPrimitive?.boolean
            assertEquals("$context: shouldCheck", expectedCheck, shouldCheck)

            val actual = gate.state(now = clock.nowMillis)
            assertEquals(
                "$context: statusRaw",
                expected.text("status"),
                actual.statusRaw,
            )
            assertEquals(
                "$context: showPrompt",
                expected.flag("showPrompt"),
                actual.showPrompt,
            )
            assertEquals(
                "$context: targetVersion",
                expected.nullableText("targetVersion"),
                actual.targetVersion,
            )
        }
    }

    private fun checkStoreChoice(row: JsonObject) {
        val label = "${row.text("name")}: ${row.text("note")}"
        val clock = VectorClock()
        clock.nowMillis = 1000L

        val gate =
            UpdateGate(
                store = MemoryUpdateStore(),
                clock = { clock.nowMillis },
                appVersion = row.text("appVersion"),
                platform = "android",
                channel = "official",
                installSource = row.nullableText("installSource"),
            )
        val result = adapt(row.objectAt("result"))
        gate.apply(result = result, now = clock.nowMillis)

        val expectedStatus =
            when (row.text("mode")) {
                "force" -> "below_min"
                "prompt" -> "below_recommended"
                else -> throw AssertionError("$label: 未知 mode")
            }
        assertEquals(
            "$label: 场景前置状态",
            expectedStatus,
            gate.state(now = clock.nowMillis).statusRaw,
        )

        val openable =
            row.getValue("canOpen").jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        val attempted = mutableListOf<String>()
        val choice =
            gate.pickStore(
                result = result,
                now = clock.nowMillis,
                canOpen = { storeKey ->
                    attempted += storeKey
                    storeKey in openable
                },
            )
        val expected = row.objectAt("expect")

        assertEquals(
            "$label: storeKey",
            expected.nullableText("storeKey"),
            choice.storeKey,
        )
        assertEquals(
            "$label: unavailable",
            expected.flag("unavailable"),
            choice.unavailable,
        )
        assertEquals(
            "$label: 同一家候选只能检查一次；实际 $attempted",
            attempted.size,
            attempted.toSet().size,
        )
    }

    private fun adapt(input: JsonObject): UpdateCheckResult {
        // 仅投影报文字段；不在适配器里实现判定、排序或选择。
        val listings =
            input.rows("stores").mapNotNull { entry ->
                entry.nullableText("listed_version")?.let { version ->
                    UpdateStoreListing(
                        store = entry.text("store"),
                        version = version,
                    )
                }
            }

        return UpdateCheckResult(
            minSupported = input.nullableText("min_supported_version"),
            recommended = input.nullableText("recommended_version"),
            latest = input.text("latest_version"),
            defaultStore = input.text("default_store"),
            stores = listings,
            recheckIntervalSec = 1800,
        )
    }
}

private fun JsonObject.text(key: String): String =
    getValue(key).jsonPrimitive.content

private fun JsonObject.nullableText(key: String): String? {
    val value = getValue(key)
    return if (value == JsonNull) null else value.jsonPrimitive.content
}

private fun JsonObject.flag(key: String): Boolean =
    getValue(key).jsonPrimitive.boolean

private fun JsonObject.objectAt(key: String): JsonObject =
    getValue(key).jsonObject

private fun JsonObject.rows(key: String): List<JsonObject> =
    getValue(key).jsonArray.map { it.jsonObject }
