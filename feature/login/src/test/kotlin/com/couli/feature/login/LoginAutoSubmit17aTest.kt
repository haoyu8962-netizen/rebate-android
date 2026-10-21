package com.couli.feature.login

import java.io.File
import java.time.Instant
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** C02/C03：逐步解释共享向量；从生产 VM 发起，绝不手串 AgreementState、sendCode、login。 */
@RunWith(Parameterized::class)
class LoginAutoSubmit17aTest(
    private val name: String,
    private val row: JsonObject,
) {
    @Test
    fun sharedSequence() {
        Login17aFixture().use { f ->
            val times = mutableListOf<Long>()
            row.getValue("steps").jsonArray.forEach { element ->
                val step = element.jsonObject
                f.advance(1_000)
                times += f.now
                act(f, step)
                assertStep(f, step)
            }
            f.settle()
            val expected = row.getValue("expect").jsonObject
            val requests = expected.getValue("requests").jsonArray
            assertEquals("$name：全部应答结算后总数（禁止排队补交）", requests.size, f.logins.size)
            assertEquals("$name：请求入口总数，含传输前拒绝", requests.size + expected.getValue("sends").jsonPrimitive.int, f.entries.get())
            assertEquals(expected.getValue("sends").jsonPrimitive.int, f.sends())
            requests.forEachIndexed { index, value ->
                val want = value.jsonObject
                val actual = f.logins[index].body
                assertEquals(name, want.getValue("code"), actual.getValue("code"))
                val consentStep = want.getValue("consentAtStep").jsonPrimitive.int
                assertEquals(name, times[consentStep], Instant.parse(actual.getValue("consent_at").jsonPrimitive.content).toEpochMilli())
                assertEquals(setOf("privacy", "agreement"), actual.getValue("legal_versions").jsonObject.keys)
            }
        }
    }

    private fun act(
        f: Login17aFixture,
        step: JsonObject,
    ) {
        when (step.getValue("op").jsonPrimitive.content) {
            "input" -> f.input(step.getValue("code").jsonPrimitive.content, step.getValue("submits").jsonPrimitive.boolean)
            "respond" -> respond(f, step)
            "advance" -> f.advance(step.getValue("ms").jsonPrimitive.long)
            "field" -> assertEquals(name, step.getValue("value").jsonPrimitive.content, f.model.state.value.code)
            else -> pageAction(f, step.getValue("op").jsonPrimitive.content)
        }
    }

    private fun respond(
        f: Login17aFixture,
        step: JsonObject,
    ) {
        val before = f.model.state.value.code
        val code = step.getValue("code").jsonPrimitive.int
        f.respond(code, step["retryAfter"]?.jsonPrimitive?.content)
        if (step["current"]?.jsonPrimitive?.boolean == false) {
            assertEquals("$name：迟到不清新码", before, f.model.state.value.code)
            assertNull("$name：迟到不在新码显示旧错误", f.model.state.value.codeError)
        }
    }

    private fun pageAction(
        f: Login17aFixture,
        op: String,
    ) {
        when (op) {
            "check", "uncheck" -> f.model.onToggleAgreement()
            "getCode" -> f.model.onGetCode()
            "continueCode" -> f.model.onContinueCode()
            "resend" -> f.model.onResend()
            "back" -> f.model.onBackToPhone()
            "confirmAgree" -> f.model.onConfirmAgree()
            "cancelConfirm" -> f.model.onCancelConfirm()
            else -> error("未识别共享操作：$op")
        }
        f.settle()
    }

    private fun assertStep(
        f: Login17aFixture,
        step: JsonObject,
    ) {
        step["step"]?.let {
            assertEquals(
                name,
                it.jsonPrimitive.content,
                f.model.state.value.step.name
                    .lowercase(),
            )
        }
        if ("confirm" in step) {
            val actual = f.model.state.value.confirmation
            if (step.getValue("confirm") == JsonNull) {
                assertNull(name, actual)
            } else {
                assertEquals(name, "login.agree_confirm.btn_get_code", actual?.primaryKey)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> =
            login17aVectors().getValue("codeAutoSubmit").jsonArray.map {
                arrayOf(
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content,
                    it.jsonObject,
                )
            }
    }
}

internal fun login17aRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "contract.lock").isFile }

internal fun login17aVectors(): JsonObject = Json.parseToJsonElement(File(login17aRoot(), "specs/login-vectors.json").readText()).jsonObject
