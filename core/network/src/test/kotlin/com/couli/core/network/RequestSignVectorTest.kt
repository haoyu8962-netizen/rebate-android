package com.couli.core.network

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端与三端共用的签名向量（BR-ID-09；契约快照 contracts/snapshot/specs/request-sign.vectors.json）。
 * valid_cases 逐条核对签名串与 X-Sign；invalid_cases 的 expected_sign 按字段原样计算（方法不转大写、路径不去主机），
 * 也逐条核对，证明实现不对输入做任何规范化。
 */
class RequestSignVectorTest {
    private val vectors: JsonObject by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        Json.parseToJsonElement(File(root, "contracts/snapshot/specs/request-sign.vectors.json").readText()).jsonObject
    }

    @Test
    fun validCases() {
        val cases = vectors.getValue("valid_cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val label = case.text("note")
            val signing = signingString(case)
            assertEquals(label, case.text("expected_signing_string"), signing)
            assertEquals(label, case.text("expected_sign"), RequestSigner.sign(case.text("install_secret"), signing))
        }
    }

    @Test
    fun invalidCasesAreSignedVerbatim() {
        val cases = vectors.getValue("invalid_cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            assertEquals(
                case.text("note"),
                case.text("expected_sign"),
                RequestSigner.sign(case.text("install_secret"), signingString(case)),
            )
        }
    }

    @Test
    fun generatedNonceAndTimestampHaveTheRequiredShape() {
        repeat(20) { assertTrue(Regex("^[0-9a-f]{32}$").matches(RequestSigner.nonce())) }
        assertEquals("1790661600", RequestSigner.timestamp(1_790_661_600_999L))
    }

    private fun signingString(case: JsonObject): String =
        RequestSigner.signingString(
            method = case.text("method"),
            pathWithQuery = case.text("path"),
            timestamp = case.text("timestamp"),
            nonce = case.text("nonce"),
            body = case.text("body_utf8").toByteArray(Charsets.UTF_8),
        )

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
