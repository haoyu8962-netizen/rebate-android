package com.couli.core.router

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 测试接线约定：
// RouteRequest.params / PendingAction.params 为 JsonObject；状态与来源接受原始字符串。
// 实现提供 DeepLinkConfig(schemes: List<String>, hosts: List<String>)。
// PendingActionStore(backend = ..., clock = { ... })；now/clock 的单位为毫秒 Long。
// 实现提供 MemoryPendingBackend，read(): String? 返回单条原始存储记录；删除后为 null。
// PendingAction 暴露 action、params；不限定时间字段的内部命名。
// 每步重建 Store、复用后端，验证持久化边界；不调用 Android API，故无需 Robolectric。
// SharedPreferences 适配器的 Android 环境测试应另用 RobolectricTestRunner，不能冒充本纯逻辑测试。

class RouterVectorTest {
    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("缺少 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")
        val file = File(root, "specs/router-vectors.json")
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.also {
            assertEquals("${file.path}: version", 1, it.getValue("version").jsonPrimitive.int)
            assertTrue("${file.path}: sources", it.getValue("sources").jsonArray.isNotEmpty())
            assertTrue("${file.path}: gaps", it.getValue("gaps").jsonArray.isNotEmpty())
            for (group in listOf("guard", "deeplink", "pending")) {
                assertTrue("$group: 向量不能为空", it.getValue(group).jsonArray.isNotEmpty())
            }
        }
    }

    @Test
    fun guardVectors() {
        val reasons = vectors.getValue("reasons").jsonObject
        each("guard") { row, label ->
            val input = row.getValue("request").jsonObject
            val state = row.getValue("state").jsonObject
            val expected = row.getValue("expect").jsonObject
            val request =
                RouteRequest(
                    route = input.string("route"),
                    params = input.getValue("params").jsonObject,
                    source = input.string("source"),
                )
            val guardState =
                GuardState(
                    privacy = state.string("privacy"),
                    update = state.string("update"),
                    session = state.string("session"),
                    environmentSettled = state.boolean("environmentSettled"),
                    appVersion = state.string("appVersion"),
                    isDebugBuild = state.boolean("isDebugBuild"),
                    fromForceUpdatePage = state.boolean("fromForceUpdatePage"),
                )
            val actual: GuardDecision = EntryGuard.decide(request, guardState)
            val reason = expected.string("reason")

            assertTrue("$label: reason 未定义", reasons.containsKey(reason))
            assertTrue("$label: reason 说明不能为空", reasons.string(reason).isNotBlank())
            assertEquals("$label: kindRaw", expected.string("kind"), actual.kindRaw)
            assertEquals("$label: route", expected.nullableString("route"), actual.route)
            assertEquals("$label: reason", reason, actual.reason)
        }
    }

    @Test
    fun deepLinkVectors() {
        each("deeplink") { row, label ->
            val config = row.getValue("config").jsonObject
            val actual =
                DeepLinkParser.parse(
                    url = row.string("url"),
                    config =
                        DeepLinkConfig(
                            schemes = config.getValue("schemes").jsonArray.map { it.jsonPrimitive.content },
                            hosts = config.getValue("hosts").jsonArray.map { it.jsonPrimitive.content },
                        ),
                )
            val expectedValue = row.getValue("expect")
            if (expectedValue == JsonNull) {
                assertNull("$label: 应拒绝深链", actual)
            } else {
                assertNotNull("$label: 应解析成功", actual)
                val request = actual ?: throw AssertionError("$label: parse 返回 null")
                val expected = expectedValue.jsonObject
                assertEquals("$label: route", expected.string("route"), request.route)
                assertEquals("$label: source", expected.string("source"), request.source)
                // JsonObject 的结构比较忽略对象键顺序，保留字符串/数字/布尔的区别。
                assertEquals("$label: params", expected.getValue("params").jsonObject, request.params)
            }
        }
    }

    @Test
    fun pendingVectors() {
        each("pending") { row, label ->
            val backend = MemoryPendingBackend()
            var nowMillis = 0L
            val steps = row.getValue("steps").jsonArray
            assertFalse("$label: steps 不能为空", steps.isEmpty())

            steps.forEachIndexed { index, element ->
                val step = element.jsonObject
                val stepLabel = "$label: step[$index] ${step.string("op")}"
                nowMillis = step.getValue("atMillis").jsonPrimitive.long

                // 不复用 Store 内存缓存：每个操作均从相同后端恢复。
                val store = PendingActionStore(backend = backend, clock = { nowMillis })
                when (step.string("op")) {
                    "save" -> {
                        val actual =
                            store.save(
                                action = step.string("action"),
                                params = step.getValue("params").jsonObject,
                                now = nowMillis,
                            )
                        assertEquals(
                            "$stepLabel: save",
                            step.getValue("expect").jsonPrimitive.boolean,
                            actual,
                        )
                    }
                    "load" -> {
                        val actual = store.load(now = nowMillis)
                        val expectedValue = step.getValue("expect")
                        if (expectedValue == JsonNull) {
                            assertNull("$stepLabel: load", actual)
                            assertNull("$stepLabel: 后端不得残留记录", backend.read())
                        } else {
                            assertNotNull("$stepLabel: load", actual)
                            val pending = actual ?: throw AssertionError("$stepLabel: 缺少记录")
                            val expected = expectedValue.jsonObject
                            assertEquals("$stepLabel: action", expected.string("action"), pending.action)
                            assertEquals(
                                "$stepLabel: params",
                                expected.getValue("params").jsonObject,
                                pending.params,
                            )
                        }
                    }
                    "markStepDone" -> {
                        // 不规定返回值；后续 load 验证续时及过期后不复活。
                        store.markStepDone(now = nowMillis)
                    }
                    "clear" -> {
                        store.clear()
                        assertNull("$stepLabel: clear 必须删除后端记录", backend.read())
                    }
                    else -> throw AssertionError("$stepLabel: 未知 op")
                }
            }
        }
    }

    private fun each(
        group: String,
        check: (JsonObject, String) -> Unit,
    ) {
        val names = mutableSetOf<String>()
        vectors.getValue(group).jsonArray.forEachIndexed { index, element ->
            val row = element.jsonObject
            val name = row.string("name")
            val note = row.string("note")
            val label = "$group[$index] $name: $note"
            assertTrue("$label: name", name.isNotBlank())
            assertTrue("$label: note", note.isNotBlank())
            assertTrue("$label: 重复 name", names.add(name))
            try {
                check(row, label)
            } catch (failure: AssertionError) {
                throw AssertionError(label, failure)
            } catch (failure: Exception) {
                throw AssertionError(label, failure)
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun JsonObject.boolean(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean

    private fun JsonObject.nullableString(key: String): String? {
        val value = getValue(key)
        return if (value == JsonNull) null else value.jsonPrimitive.content
    }
}
