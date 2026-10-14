package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugFakeAccount
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Debug 假账号服务（brief §2 第 11 项）的场景核对：step-up 才受理、同键同结果、作废、按账号保存、清理、闸门关着不回答。只用测试号。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DebugFakeAccountTest {
    private val dir: File = Files.createTempDirectory("fake-account").toFile()
    private val stateFile = File(dir, "state.txt")
    private val fake = DebugFakeAccount(stateFile)

    private fun body(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun headers(
        key: String? = null,
        token: String? = null,
    ): Map<String, String> = listOfNotNull(key?.let { "Idempotency-Key" to it }, token?.let { "X-Step-Up-Token" to it }).toMap()

    @Suppress("LongParameterList")
    private fun answer(
        method: String,
        path: String,
        json: String = "{}",
        headers: Map<String, String> = emptyMap(),
        account: String = "fake-user-0001",
        phoneBound: Boolean = true,
    ) = fake.answer(method, path, body(json), headers, account, phoneBound)

    private fun token(action: String): String {
        val reply = answer("POST", "/v1/auth/step-up", """{"action":"$action","code":"123456"}""")!!
        return Json
            .parseToJsonElement(reply.second)
            .jsonObject["step_up_token"]!!
            .jsonPrimitive.content
    }

    @Test
    fun requestDeletionNeedsStepUpAndReplaysTheSameResultForTheSameKey() {
        assertEquals(10003, answer("POST", "/v1/me/deletion", headers = headers("k1"))!!.first)
        val token = token("account_deletion")
        val first = answer("POST", "/v1/me/deletion", headers = headers("k1", token))!!
        assertEquals(0, first.first)
        assertEquals(first, answer("POST", "/v1/me/deletion", headers = headers("k1", token)))
        assertTrue(answer("GET", "/v1/me/deletion")!!.second.contains("cooling"))
        assertEquals("别的账号看不到", "null", answer("GET", "/v1/me/deletion", account = "fake-user-0002")!!.second)
    }

    @Test
    fun abandonAbandonsAnUnacceptedKeyAndTheSameKeyThenGets20903() {
        val reply = answer("POST", "/v1/idempotency-keys/abandon", """{"action":"account_deletion","idempotency_key":"k2"}""")!!
        assertTrue(reply.second.contains("abandoned"))
        val token = token("account_deletion")
        assertEquals(20903, answer("POST", "/v1/me/deletion", headers = headers("k2", token))!!.first)
    }

    @Test
    fun scenesTimeoutAnd30416AndPhoneChangeAndReset() {
        stateFile.writeText("apply=30416\nfunds=negative567\n")
        val token = token("account_deletion")
        val negative = answer("POST", "/v1/me/deletion", headers = headers("k3", token))!!
        assertEquals(30416, negative.first)
        assertTrue(negative.second.contains("567"))
        stateFile.writeText("bind=timeout\n")
        val change = token("phone_change")
        val thrown =
            runCatching {
                answer("POST", "/v1/me/phone", """{"phone":"13900001234","code":"123456"}""", headers("k4", change))
            }.exceptionOrNull()
        assertTrue(thrown is IOException)
        stateFile.writeText("")
        assertEquals(
            0,
            answer("POST", "/v1/me/phone", """{"phone":"13900001234","code":"123456"}""", headers("k5", token("phone_change")))!!.first,
        )
        fake.reset()
        assertEquals(10003, answer("POST", "/v1/me/deletion", headers = headers("k6", token))!!.first)
    }

    @Test
    fun aClosedGateAnswersNothingAndFirstBindWithoutSceneIsLeftToTheLoginServer() {
        assertNull(answer("POST", "/v1/me/phone", """{"phone":"13900001234","code":"123456"}""", headers("k7"), phoneBound = false))
        fake.switchOn = { false }
        assertNull(answer("GET", "/v1/me/deletion"))
    }
}
