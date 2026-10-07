/*
 * 实现前测试；只读输出，未编译运行。
 * feature:login 需注册模块，并复用仓库已有 JUnit4 / serialization 依赖。
 *
 * 接线约定：
 * - 七个被测类型在 com.couli.feature.login 中可见。
 * - PhoneNumber.normalize(raw: String)
 *   -> { valid: Boolean, normalized: String?, reasonRaw: String? }。
 * - AgreementState(clock: () -> Long)，默认未勾选；
 *   tapLogin()/check()/uncheck()/confirm(nowMillis: Long)/cancel()；
 *   showConfirm、proceed: Boolean，consentAtMillis: Long?。
 *   check 使用注入时钟，confirm 使用实参，时间单位为毫秒。
 * - SmsCountdown.after(code: Int, retryAfterHeader: String?, nowMillis: Long)
 *   -> { countdownSec: Int, canResend: Boolean, stateRaw: String, textKey: String? }。
 *   code 是业务码，成功为 0，不是 HTTP 状态。
 * - CodeInput.accept(raw: String) -> { complete: Boolean, digitsOnly: Boolean }；
 *   报告原字符串形状：digitsOnly 表示没有非 ASCII 数字（空串为 true），
 *   complete 表示恰为六位 ASCII 数字；不测试 UI 清洗。
 * - LoginErrorMapper.map(op: String, code: Int, data: JsonObject)
 *   -> { stateRaw: String, textKey: String?, field: String?, route: String? }；
 *   接已有 ErrorActionMapper，不另造错误码表。
 * - InviteField.decide(raw: String, context: JsonObject)
 *   -> { showInviteField: Boolean, valid: Boolean?, noticeKey: String? }；
 *   valid 只判注册输入语法，空值可省略，不决定能否登录。
 * - AfterLogin.plan(response: JsonObject, state: JsonObject): List<String>；
 *   response 为向量投影，session_scope 来自实际 tokens.session_scope。
 *   返回完整会话/路由计划：save_session、resume_pending、
 *   discard_pending、enter_force_update；按原顺序比较，不排序、不去重。
 *   resume_pending 只委托 Router 守卫；实际保存、通知和邀请提示另验。
 * - stateRaw 是测试接线名，不是契约枚举或路由：
 *   registration_blocked / deletion_processing / session_expired 分别对应
 *   LoginCodeBlocked / LoginDeletionProcessing / LoginSessionExpired。
 * - expect 缺字段表示不判定；显式 null 表示必须无值。
 * - 每行新建状态；固定时钟，无 Android API、真实网络或计时器。
 */
@file:Suppress("MagicNumber")

package com.couli.feature.login

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
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginVectorTest {
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
            File(root, "specs/login-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
    }

    @Test
    fun phoneVectors() {
        each("phone") { row, expected, label ->
            val actual = PhoneNumber.normalize(row.string("raw"))
            assertEquals("$label.valid", expected.bool("valid"), actual.valid)
            assertText(expected, "normalized", actual.normalized, label)
            assertText(expected, "reason", actual.reasonRaw, label)
        }
    }

    @Test
    fun agreementVectors() {
        each("agreement") { row, expected, label ->
            var nowMillis = 0L
            var acceptedAt: Long? = null
            val state = AgreementState(clock = { nowMillis })

            row.getValue("steps").jsonArray.forEachIndexed { index, element ->
                val op = element.jsonObject.string("op")
                val context = "$label.steps[$index].$op"
                nowMillis = 1_700_000_000_000L + index.toLong() * 1_000L

                when (op) {
                    "tapLogin" -> state.tapLogin()
                    "check" -> {
                        state.check()
                        acceptedAt = nowMillis
                        assertEquals(context, acceptedAt, state.consentAtMillis)
                    }
                    "uncheck" -> {
                        state.uncheck()
                        // 不规定取消勾选后的历史时间存储表示。
                    }
                    "confirmAgree" -> {
                        assertTrue("$context: 应先展示确认框", state.showConfirm)
                        state.confirm(nowMillis)
                        acceptedAt = nowMillis
                        assertEquals(context, acceptedAt, state.consentAtMillis)
                    }
                    "cancelConfirm" -> state.cancel()
                    else -> throw AssertionError("$context: 未知操作")
                }
            }

            assertEquals("$label.showConfirm", expected.bool("showConfirm"), state.showConfirm)
            assertEquals("$label.proceed", expected.bool("proceed"), state.proceed)
            assertEquals(
                "$label.consentRecorded",
                expected.bool("consentRecorded"),
                state.consentAtMillis != null,
            )
            if (expected.bool("consentRecorded")) {
                assertNotNull("$label: 缺少同意步骤", acceptedAt)
                assertEquals(
                    "$label: 使用勾选/确认时刻，不能改成提交时刻",
                    acceptedAt,
                    state.consentAtMillis,
                )
            }
        }
    }

    @Test
    fun smsSendVectors() {
        each("smsSend") { row, expected, label ->
            val response = row.objectAt("response")
            val retryAfter =
                response["headers"]?.jsonObject
                    ?.get("Retry-After")?.jsonPrimitive?.content
            val actual =
                SmsCountdown.after(
                    code = response.getValue("code").jsonPrimitive.int,
                    retryAfterHeader = retryAfter,
                    nowMillis = row.getValue("nowMillis").jsonPrimitive.long,
                )
            assertEquals(
                "$label.countdownSec",
                expected.getValue("countdownSec").jsonPrimitive.int,
                actual.countdownSec,
            )
            assertEquals("$label.canResend", expected.bool("canResend"), actual.canResend)
            assertEquals("$label.stateRaw", expected.string("stateRaw"), actual.stateRaw)
            assertText(expected, "textKey", actual.textKey, label)
        }
    }

    @Test
    fun codeVectors() {
        each("code") { row, expected, label ->
            val actual = CodeInput.accept(row.string("input"))
            assertEquals("$label.complete", expected.bool("complete"), actual.complete)
            assertEquals("$label.digitsOnly", expected.bool("digitsOnly"), actual.digitsOnly)
        }
    }

    @Test
    fun loginErrorVectors() {
        each("loginError") { row, expected, label ->
            val actual =
                LoginErrorMapper.map(
                    op = row.string("op"),
                    code = row.getValue("code").jsonPrimitive.int,
                    data = row["data"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            assertEquals("$label.stateRaw", expected.string("stateRaw"), actual.stateRaw)
            assertText(expected, "textKey", actual.textKey, label)
            assertText(expected, "field", actual.field, label)
            assertText(expected, "route", actual.route, label)
        }
    }

    @Test
    fun inviteVectors() {
        each("invite") { row, expected, label ->
            val actual = InviteField.decide(row.string("raw"), row.objectAt("context"))
            assertEquals(
                "$label.showInviteField",
                expected.bool("showInviteField"),
                actual.showInviteField,
            )
            if (expected.containsKey("valid")) {
                assertEquals("$label.valid", expected.bool("valid"), actual.valid)
            }
            assertText(expected, "noticeKey", actual.noticeKey, label)
        }
    }

    @Test
    fun afterLoginVectors() {
        each("afterLogin") { row, expected, label ->
            val actual = AfterLogin.plan(row.objectAt("response"), row.objectAt("state"))
            val actions = expected.getValue("actions").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$label.actions：内容、次数、顺序", actions, actual)
        }
    }

    private fun each(
        group: String,
        body: (JsonObject, JsonObject, String) -> Unit,
    ) {
        val rows = vectors.getValue(group).jsonArray
        assertFalse("$group: 向量不能为空", rows.isEmpty())
        val names = mutableSetOf<String>()
        for (element in rows) {
            val row = element.jsonObject
            val name = row.string("name")
            val label = "$group.$name"
            assertTrue("$label: 名称不能为空", name.isNotEmpty())
            assertTrue("$label: 名称重复", names.add(name))
            body(row, row.objectAt("expect"), label)
        }
    }

    private fun assertText(
        expected: JsonObject,
        key: String,
        actual: String?,
        label: String,
    ) {
        if (!expected.containsKey(key)) return
        val value = expected.getValue(key)
        val text = if (value == JsonNull) null else value.jsonPrimitive.content
        assertEquals("$label.$key", text, actual)
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject
