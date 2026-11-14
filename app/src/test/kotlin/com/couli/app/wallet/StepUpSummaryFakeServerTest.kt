package com.couli.app.wallet

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugFakeAccount
import com.couli.app.DebugFakeStepUp
import com.couli.app.DebugFakeWallet
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-STEPUP-SUMMARY（D10-1655）：debug / staging 假服务按契约比对二次验证——
 * step_up 发码不带 phone 也发、带了 phone 回 20001 [phone]（B1-02zzz）；改收款缺业务摘要回 20001 [summary]、摘要对不上回 10003；
 * 提现带了摘要回 20001、不带照常（D10-1667）。只用测试号。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class StepUpSummaryFakeServerTest {
    private val dir: File = Files.createTempDirectory("fake-step-up").toFile()
    private val wallet = DebugFakeWallet(File(dir, "wallet.txt"))
    private val account = DebugFakeAccount(File(dir, "account.txt"))

    private fun body(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun headers(
        key: String,
        token: String? = null,
    ): Map<String, String> = listOfNotNull("Idempotency-Key" to key, token?.let { "X-Step-Up-Token" to it }).toMap()

    private fun walletAnswer(
        method: String,
        path: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ) = wallet.answer(method, path, null, body(json), headers)!!

    private fun fields(data: String): List<String> =
        Json
            .parseToJsonElement(data)
            .jsonObject["fields"]!!
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun token(reply: Triple<Int, String, Map<String, String>>): String {
        assertEquals(0, reply.first)
        return Json
            .parseToJsonElement(reply.second)
            .jsonObject["step_up_token"]!!
            .jsonPrimitive.content
    }

    @Test
    fun stepUpSmsWithoutPhoneIsSentAndWithPhoneIs20001() {
        val path = DebugFakeStepUp.SMS_PATH
        assertNull(
            "不带 phone、已登录、已绑手机：照原场景发码",
            DebugFakeStepUp.smsCheck(path, body("""{"purpose":"step_up","action":"withdraw"}"""), true, true),
        )
        val withPhone =
            DebugFakeStepUp.smsCheck(
                path,
                body("""{"purpose":"step_up","action":"withdraw","phone":"13800005678"}"""),
                true,
                true,
            )!!
        assertEquals(20001, withPhone.first)
        assertEquals(listOf("phone"), fields(withPhone.second))
        assertEquals(10001, DebugFakeStepUp.smsCheck(path, body("""{"purpose":"step_up","action":"withdraw"}"""), false, true)!!.first)
        val unbound = DebugFakeStepUp.smsCheck(path, body("""{"purpose":"step_up","action":"withdraw"}"""), true, false)!!
        assertEquals(listOf("code"), fields(unbound.second))
        assertNull("login 发码不受影响", DebugFakeStepUp.smsCheck(path, body("""{"purpose":"login","phone":"13800005678"}"""), false, false))
    }

    @Test
    fun withdrawStepUpWithSummaryIs20001AndWithoutSummaryPassesForAnyAmount() {
        // D10-1667：提现收窄后 action=withdraw 带了 summary 回 20001 [summary]；不带照常签发，建单不比对摘要
        val withSummary =
            walletAnswer(
                "POST",
                "/v1/auth/step-up",
                """{"action":"withdraw","code":"123456","summary":{"payout_account_id":"pa-1","amount_fen":1000}}""",
            )
        assertEquals(20001, withSummary.first)
        assertEquals(listOf("summary"), fields(withSummary.second))

        val tok = token(walletAnswer("POST", "/v1/auth/step-up", """{"action":"withdraw","code":"123456"}"""))
        assertEquals(
            "不带摘要的 withdraw 令牌照常通过",
            0,
            walletAnswer("POST", "/v1/withdrawals", """{"amount_fen":2000}""", headers("k1", tok)).first,
        )
    }

    @Test
    fun payoutChangeSummaryMismatchIs10003AndSameAccountPasses() {
        val missing = walletAnswer("POST", "/v1/auth/step-up", """{"action":"payout_account_change","code":"123456"}""")
        assertEquals(20001, missing.first)
        val tok =
            token(
                walletAnswer(
                    "POST",
                    "/v1/auth/step-up",
                    """{"action":"payout_account_change","code":"123456",""" +
                        """"summary":{"payout_method":"alipay","alipay_logon_id":"13800138000"}}""",
                ),
            )
        val other = """{"payout_method":"alipay","alipay_logon_id":"13900139000","payee_name":"测试三"}"""
        assertEquals("换成别的账号：10003", 10003, walletAnswer("PUT", "/v1/me/payout-account", other, headers("p1", tok)).first)
        val same = """{"payout_method":"alipay","alipay_logon_id":"+86 138-0013-8000","payee_name":"测试三"}"""
        assertEquals("同一账号另一种写法：规范化后一致", 0, walletAnswer("PUT", "/v1/me/payout-account", same, headers("p2", tok)).first)
    }

    @Test
    fun unboundActionsRejectASummary() {
        val reply =
            account.answer(
                "POST",
                "/v1/auth/step-up",
                body("""{"action":"phone_change","code":"123456","summary":{"payout_account_id":"x","amount_fen":1}}"""),
                emptyMap(),
                "fake-user-0001",
                true,
            )!!
        assertEquals(20001, reply.first)
        assertTrue(reply.second.contains("summary"))
    }
}
