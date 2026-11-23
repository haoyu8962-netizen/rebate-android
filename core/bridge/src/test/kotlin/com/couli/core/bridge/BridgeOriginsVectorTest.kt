package com.couli.core.bridge

import com.couli.core.bridge.N22Vectors.KNOWN_REASONS
import com.couli.core.bridge.N22Vectors.describe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * N-22（D10-2539）三端共用向量逐条：公钥表装 staging 钥（env staging）+ 测试钥（env test）；判定只看 expect.accept 与 expect.origins
 * （完全相等，含顺序与写法）；拒绝时 reason 须是约定的那套字符串之一（expect.reason 只供定位，不逐字比）。
 */
@RunWith(Parameterized::class)
class BridgeOriginsVectorTest(
    private val name: String,
    private val case: JsonObject,
) {
    @Test
    fun verdictMatchesTheVector() {
        val client = case.getValue("client").jsonObject
        val expect = case.getValue("expect").jsonObject
        val jws = case.getValue("jws").jsonPrimitive.content
        val verdict =
            BridgeOriginsVerifier.verify(
                jws = jws,
                appId = client.getValue("app_id").jsonPrimitive.content,
                env = client.getValue("env").jsonPrimitive.content,
                lastGoodVersion = (client["last_good_version"] as? JsonPrimitive)?.longOrNull,
                keys = N22Vectors.keys,
            )
        val wantReason = expect["reason"]?.jsonPrimitive?.content
        val got = describe(verdict)
        if (expect.getValue("accept").jsonPrimitive.boolean) {
            val origins = expect.getValue("origins").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("$name：应通过（向量 reason=$wantReason），实得 $got", verdict is BridgeOriginsVerdict.Accepted)
            val allowlist = (verdict as BridgeOriginsVerdict.Accepted).allowlist
            assertEquals("$name：origins 与向量完全相等，实得 $got", origins, allowlist.origins)
            val version =
                N22Vectors
                    .payloadOf(jws)
                    .getValue("version")
                    .jsonPrimitive.long
            assertEquals("$name：version = payload.version，实得 $got", version, allowlist.version)
        } else {
            assertTrue("$name：应拒绝（向量 reason=$wantReason），实得 $got", verdict is BridgeOriginsVerdict.Rejected)
            val reason = (verdict as BridgeOriginsVerdict.Rejected).reason
            assertTrue("$name：reason 须是约定字符串之一（向量 reason=$wantReason），实得 $reason", reason in KNOWN_REASONS)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> = N22Vectors.cases.map { arrayOf(it.getValue("name").jsonPrimitive.content, it) }
    }
}

/** 向量文件本身：防止被截断或换掉而全绿（条数、应通过条数、sha256、公钥与向量 keys 一致）。 */
class BridgeOriginsVectorFileTest {
    @Test
    fun vectorFileIsTheCommittedOne() {
        assertEquals("向量文件 sha256 前 16 位", N22Vectors.VECTORS_SHA16, N22Vectors.sha256Hex(N22Vectors.vectorBytes).take(16))
        val cases = N22Vectors.cases
        assertEquals("向量总数", 33, cases.size)
        assertEquals("向量名不重复", 33, cases.map { it.getValue("name").jsonPrimitive.content }.toSet().size)
        val accepted =
            cases.filter {
                it
                    .getValue("expect")
                    .jsonObject
                    .getValue("accept")
                    .jsonPrimitive.boolean
            }
        assertEquals(
            "应通过的 4 条",
            listOf("stg_live_ok", "stg_live_ok_last_good_equal", "t_ok", "t_ok_two_origins_port"),
            accepted.map { it.getValue("name").jsonPrimitive.content },
        )
    }

    @Test
    fun publicKeysMatchTheVectorKeys() {
        assertEquals("staging 钥 SPKI DER sha256-16", N22Vectors.STAGING_SHA16, N22Vectors.sha256Hex(N22Vectors.stagingDer).take(16))
        assertEquals("测试钥 SPKI DER sha256-16", N22Vectors.TEST_SHA16, N22Vectors.sha256Hex(N22Vectors.testDer).take(16))
        val keys =
            N22Vectors.vectors
                .getValue("keys")
                .jsonArray
                .map { it.jsonObject }
        val described =
            keys.map {
                Triple(
                    it.getValue("env").jsonPrimitive.content,
                    it.getValue("kid").jsonPrimitive.content,
                    it.getValue("spki_der_sha256_16").jsonPrimitive.content,
                )
            }
        assertEquals(
            "向量 keys 与拷入的两个 pem 一致",
            setOf(
                Triple(N22Vectors.STAGING_ENV, N22Vectors.STAGING_KID, N22Vectors.STAGING_SHA16),
                Triple(N22Vectors.TEST_ENV, N22Vectors.TEST_KID, N22Vectors.TEST_SHA16),
            ),
            described.toSet(),
        )
        keys.forEach {
            val der = N22Vectors.pemToDer(it.getValue("spki_pem").jsonPrimitive.content)
            val sha16 = it.getValue("spki_der_sha256_16").jsonPrimitive.content
            assertEquals("向量 keys 的 spki_pem 与 sha256-16 自洽", sha16, N22Vectors.sha256Hex(der).take(16))
        }
    }
}
