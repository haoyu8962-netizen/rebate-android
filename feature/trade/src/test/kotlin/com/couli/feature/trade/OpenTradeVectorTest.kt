/*
 * 实现前测试；只读交付，未编译运行。
 * feature:trade 复用已有 JUnit4 / kotlinx.serialization.json 依赖。
 *
 * 接线约定：
 * - 以下七个生产类型在 com.couli.feature.trade 中可见；
 *   测试直接调用生产纯逻辑，不实现替身。
 * - OpenAttempt(keyGenerator: () -> String)；
 *   start()/timeout()/retry()/authRequired(code: Int)/
 *   resendAfterAuth()/cancel()/abandon()；
 *   currentKey: String?、abandonCalled: Boolean。
 *   每行从 k1 开始；只在 start/retry/resendAfterAuth 后采样键。
 *   cancel/abandon 后键是否清空不判定。
 *   abandonCalled 是服务端 abandon 请求标记，不是 abandon() 的调用标记；
 *   open 不属于该接口的四类 action，本组应始终为 false。
 * - OpenResponseAction.map(response: JsonObject, context: JsonObject)
 *   -> { actionRaw: String, replaceLinkId: String?, requoteNotice: Boolean }。
 *   response 是完整成功 envelope；金额读为 Long，保留 JsonNull、不补 0。
 *   confirm_coupon 表示券失效确认；replaceLinkId 不等待确认/取消结果。
 * - OpenErrorAction.map(code: Int, data: JsonObject)
 *   -> { actionRaw: String, textKey: String?, showTrace6: Boolean }。
 *   接已有错误映射；notice 为业务提示分类，error 为购买受阻错误态。
 *   按钮、路由、卡片置灰等输出缺口见 gaps，不能视为已验收。
 * - LateResponsePolicy.decide(interrupt: String)
 *   -> { jump: Boolean, updateCard: Boolean }。
 *   只判最近一次点击；jump 是继续响应处理的许可，不越过确认框。
 * - JumpPlanRunner.simulate(plan: JsonObject, installed: JsonObject,
 *   stepResults: List<String>) -> { executed: List<String>, final: String }。
 *   installed.app 是注入的目标 App 检测结果，不访问设备。
 *   stepResults 按 primary + fallbacks 位置排列，未执行结果不消费。
 *   executed 包括初始化失败的 sdk；sdk_init_failed 是重试后仍失败。
 *   browser 是系统浏览器；not_installed 行是未安装复制口令后的安装提示。
 *   不读取系统时钟，不据示例 expire_at 判断过期。
 * - LinkJumpEvents.build(attemptId: String?, linkId: String?,
 *   steps: List<JsonObject>, isAuthJump: Boolean): List<JsonObject>。
 *   steps 为每步观测：phase/union_platform/step/result，
 *   purchase 另含 duration_ms、installed 字符串；value 不得上报。
 *   返回事件业务字段，Tracker 公共外壳另加；完整比较，不排序事件、不去重。
 * - AuthMethodPicker.pick(platform: String, authMethods: List<String>,
 *   supported: List<String>): String?；platform 是联盟平台，
 *   supported 注入当前端能力，不表示三端 SDK 已验证。
 * - actionRaw/final 是接线标签；expect 缺字段不判定，null 必须无值。
 * - 从 user.dir 向上找 contract.lock，再只读共享向量文件。
 */
@file:Suppress("MagicNumber")

package com.couli.feature.trade

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTradeVectorTest {
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
        Json.parseToJsonElement(
            File(root, "specs/open-trade-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
    }

    @Test
    fun idempotency() {
        each("idempotency") { row, expected, label ->
            var generated = 0
            val attempt =
                OpenAttempt(keyGenerator = {
                    generated += 1
                    "k$generated"
                })
            val keys = mutableListOf<String>()

            row.getValue("steps").jsonArray.forEachIndexed { index, element ->
                val step = element.jsonObject
                val op = step.string("op")
                val stepLabel = "$label.steps[$index].$op"
                var sent = false

                when (op) {
                    "start" -> {
                        attempt.start()
                        sent = true
                    }
                    "timeout" -> {
                        val before = attempt.currentKey
                        attempt.timeout()
                        assertEquals(stepLabel, before, attempt.currentKey)
                    }
                    "retry" -> {
                        attempt.retry()
                        sent = true
                    }
                    "authRequired" -> {
                        attempt.authRequired(step.getValue("code").jsonPrimitive.int)
                    }
                    "resendAfterAuth" -> {
                        attempt.resendAfterAuth()
                        sent = true
                    }
                    "cancel" -> attempt.cancel()
                    "abandon" -> attempt.abandon()
                    else -> throw AssertionError("$stepLabel: 未知操作")
                }

                if (sent) {
                    val key = attempt.currentKey
                    assertNotNull(stepLabel, key)
                    keys.add(key ?: throw AssertionError("$stepLabel: 缺少发送键"))
                }
            }

            val expectedKeys = expected.strings("keys")
            assertEquals("$label.keys", expectedKeys, keys)
            assertEquals("$label.generated", expectedKeys.toSet().size, generated)
            assertFlag(expected, "abandonCalled", attempt.abandonCalled, label)
        }
    }

    @Test
    fun openResponse() {
        each("openResponse") { row, expected, label ->
            val actual =
                OpenResponseAction.map(
                    row.getValue("response").jsonObject,
                    row.getValue("context").jsonObject,
                )
            assertEquals("$label.actionRaw", expected.string("actionRaw"), actual.actionRaw)
            assertText(expected, "replaceLinkId", actual.replaceLinkId, label)
            assertFlag(expected, "requoteNotice", actual.requoteNotice, label)
        }
    }

    @Test
    fun openError() {
        each("openError") { row, expected, label ->
            val data =
                if ("data" in row) {
                    row.getValue("data").jsonObject
                } else {
                    JsonObject(emptyMap())
                }
            val actual =
                OpenErrorAction.map(
                    row.getValue("code").jsonPrimitive.int,
                    data,
                )
            assertEquals("$label.actionRaw", expected.string("actionRaw"), actual.actionRaw)
            assertText(expected, "textKey", actual.textKey, label)
            assertFlag(expected, "showTrace6", actual.showTrace6, label)
        }
    }

    @Test
    fun lateResponse() {
        each("lateResponse") { row, expected, label ->
            val actual = LateResponsePolicy.decide(row.string("interrupt"))
            assertEquals("$label.jump", expected.bool("jump"), actual.jump)
            assertEquals("$label.updateCard", expected.bool("updateCard"), actual.updateCard)
        }
    }

    @Test
    fun jumpPlan() {
        each("jumpPlan") { row, expected, label ->
            val plan = row.getValue("plan").jsonObject
            val results = row.strings("stepResults")
            assertEquals(
                "$label.fixture",
                plan.getValue("fallbacks").jsonArray.size + 1,
                results.size,
            )
            val actual =
                JumpPlanRunner.simulate(
                    plan,
                    row.getValue("installed").jsonObject,
                    results,
                )
            assertEquals("$label.executed", expected.strings("executed"), actual.executed)
            assertEquals("$label.final", expected.string("final"), actual.final)
        }
    }

    @Test
    fun linkJump() {
        each("linkJump") { row, expected, label ->
            val actual =
                LinkJumpEvents.build(
                    row.nullableString("attemptId"),
                    row.nullableString("linkId"),
                    row.getValue("steps").jsonArray.map { it.jsonObject },
                    row.bool("isAuthJump"),
                )
            // JsonObject 比较完整字段与 JSON 类型；JsonArray 保留顺序和重复项。
            assertEquals(
                "$label.events",
                expected.getValue("events").jsonArray,
                JsonArray(actual),
            )
        }
    }

    @Test
    fun authMethods() {
        each("authMethods") { row, expected, label ->
            val actual =
                AuthMethodPicker.pick(
                    row.string("platform"),
                    row.strings("authMethods"),
                    row.strings("supported"),
                )
            assertEquals("$label.chosen", expected.nullableString("chosen"), actual)
        }
    }

    private fun each(
        group: String,
        body: (JsonObject, JsonObject, String) -> Unit,
    ) {
        val rows = vectors.getValue(group).jsonArray
        assertTrue("$group: 不得为空", rows.isNotEmpty())
        val names = mutableSetOf<String>()

        rows.forEach { element ->
            val row = element.jsonObject
            val name = row.string("name")
            assertTrue("$group: 缺少名称", name.isNotEmpty())
            assertTrue("$group.$name: 名称重复", names.add(name))
            body(row, row.getValue("expect").jsonObject, "$group.$name")
        }
    }

    private fun assertText(
        expected: JsonObject,
        key: String,
        actual: String?,
        label: String,
    ) {
        if (key in expected) {
            assertEquals("$label.$key", expected.nullableString(key), actual)
        }
    }

    private fun assertFlag(
        expected: JsonObject,
        key: String,
        actual: Boolean,
        label: String,
    ) {
        if (key in expected) {
            assertEquals("$label.$key", expected.bool(key), actual)
        }
    }

    private fun JsonObject.string(key: String): String {
        val primitive = getValue(key).jsonPrimitive
        require(primitive.isString) { "$key: 应为字符串" }
        return primitive.content
    }

    private fun JsonObject.nullableString(key: String): String? =
        if (getValue(key) == JsonNull) null else string(key)

    private fun JsonObject.bool(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean

    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map {
            val primitive = it.jsonPrimitive
            require(primitive.isString) { "$key: 数组成员应为字符串" }
            primitive.content
        }
}
