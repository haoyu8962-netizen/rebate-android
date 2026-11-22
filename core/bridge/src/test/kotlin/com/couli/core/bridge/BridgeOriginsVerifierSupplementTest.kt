package com.couli.core.bridge

import com.couli.core.bridge.N22Vectors.APP_ID
import com.couli.core.bridge.N22Vectors.KNOWN_REASONS
import com.couli.core.bridge.N22Vectors.STAGING_ENV
import com.couli.core.bridge.N22Vectors.STAGING_KID
import com.couli.core.bridge.N22Vectors.STAGING_SHA16
import com.couli.core.bridge.N22Vectors.STG_ORIGIN
import com.couli.core.bridge.N22Vectors.TEST_ENV
import com.couli.core.bridge.N22Vectors.TEST_KID
import com.couli.core.bridge.N22Vectors.TEST_SHA16
import com.couli.core.bridge.N22Vectors.describe
import com.couli.core.clientcore.BundledConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-22 向量之外的补充（照 iOS 任务书 §3.1 第 1 条）：空 key 表、ConfigSignKeys.parse 坏项跳过 / 好项、verify(config = …) 的配置形状、
 * 包内占位 jws、测试钥现签的类型边界（v 为布尔、version 为小数 / 字符串 / 超过 Int）。
 */
class BridgeOriginsVerifierSupplementTest {
    private val stgLive = N22Vectors.jws("stg_live_ok")

    private fun config(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun assertAccepted(
        what: String,
        verdict: BridgeOriginsVerdict,
        version: Long,
        origins: List<String>,
    ) {
        assertEquals("$what：实得 ${describe(verdict)}", BridgeOriginsVerdict.Accepted(BridgeOriginsAllowlist(version, origins)), verdict)
    }

    private fun assertRejected(
        what: String,
        verdict: BridgeOriginsVerdict,
        reason: String? = null,
    ) {
        assertTrue("$what：应拒绝，实得 ${describe(verdict)}", verdict is BridgeOriginsVerdict.Rejected)
        val got = (verdict as BridgeOriginsVerdict.Rejected).reason
        if (reason != null) {
            assertEquals("$what：reason", reason, got)
        } else {
            assertTrue("$what：reason 须是约定字符串之一，实得 $got", got in KNOWN_REASONS)
        }
    }

    // ---------------------------------------------------------------- 公钥表

    @Test
    fun emptyKeyTableRejectsTheStagingLiveJws() {
        val verify = { keys: ConfigSignKeys -> BridgeOriginsVerifier.verify(stgLive, APP_ID, STAGING_ENV, null, keys) }
        assertRejected("EMPTY 表", verify(ConfigSignKeys.EMPTY))
        assertRejected("空列表构造", verify(ConfigSignKeys(emptyList())))
        assertRejected("只有测试钥（env test）", verify(N22TestSigner().keys))
        assertTrue("EMPTY 没有任何一把", ConfigSignKeys.EMPTY.entries.isEmpty())
        assertFalse(ConfigSignKeys.EMPTY.hasKey(STAGING_ENV, STAGING_KID))
    }

    @Test
    fun parseGoodItemGivesTheStagingEntryAndVerifiesTheLiveJws() {
        val keys = ConfigSignKeys.parse("$STAGING_ENV:$STAGING_KID:${N22Vectors.hex(N22Vectors.stagingDer)}")
        assertEquals(listOf(ConfigSignKeys.Entry(STAGING_ENV, STAGING_KID, STAGING_SHA16)), keys.entries)
        assertTrue(keys.hasKey(STAGING_ENV, STAGING_KID))
        assertFalse("kid 只在它的 env 下有效", keys.hasKey("prod", STAGING_KID))
        assertFalse(keys.hasKey(STAGING_ENV, TEST_KID))
        assertAccepted(
            "parse 出的表验 staging 实测 jws",
            BridgeOriginsVerifier.verify(jws = stgLive, appId = APP_ID, env = STAGING_ENV, lastGoodVersion = null, keys = keys),
            0L,
            listOf(STG_ORIGIN),
        )
    }

    @Test
    fun parseSkipsBadItemsAndKeepsGoodOnesSortedByEnvThenKid() {
        val stagingHex = N22Vectors.hex(N22Vectors.stagingDer)
        val testHex = N22Vectors.hex(N22Vectors.testDer)
        val p384Hex = N22Vectors.hex(N22TestSigner(curve = "secp384r1").spkiDer)
        val spec =
            listOf(
                "staging:only-two-segments",
                "staging:not-hex:zz${stagingHex.drop(2)}",
                "staging:odd-hex:${stagingHex.dropLast(1)}",
                "staging:truncated:${stagingHex.dropLast(8)}",
                "staging:p384:$p384Hex",
                "staging:empty-hex:",
                "$TEST_ENV:$TEST_KID:$testHex",
                "$STAGING_ENV:$STAGING_KID:$stagingHex",
            ).joinToString(separator = " ,\n\t")
        val keys = ConfigSignKeys.parse(" , $spec , ")
        assertEquals(
            "只留两项好的，按 env、kid 排序",
            listOf(ConfigSignKeys.Entry(STAGING_ENV, STAGING_KID, STAGING_SHA16), ConfigSignKeys.Entry(TEST_ENV, TEST_KID, TEST_SHA16)),
            keys.entries,
        )
        listOf("only-two-segments", "not-hex", "odd-hex", "truncated", "p384", "empty-hex").forEach {
            assertFalse("坏项 $it 不进表", keys.hasKey(STAGING_ENV, it))
        }
        assertTrue(keys.hasKey(TEST_ENV, TEST_KID))
        assertTrue("空串", ConfigSignKeys.parse("").entries.isEmpty())
        assertTrue("只有分隔符", ConfigSignKeys.parse(" ,\n, \t").entries.isEmpty())
    }

    @Test
    fun derKeysConstructorSkipsKeysThatAreNotP256() {
        val good = N22TestSigner(kid = "good")
        val keys =
            ConfigSignKeys(
                listOf(
                    Triple(TEST_ENV, "p384", N22TestSigner(curve = "secp384r1").spkiDer),
                    Triple(TEST_ENV, "junk", byteArrayOf(1, 2, 3)),
                    Triple(TEST_ENV, "good", good.spkiDer),
                ),
            )
        assertEquals(listOf("good"), keys.entries.map { it.kid })
        assertEquals(N22Vectors.sha256Hex(good.spkiDer).take(16), keys.entries.single().spkiSha256Prefix16)
    }

    // ---------------------------------------------------------------- verify(config = …) 的形状

    @Test
    fun configShapesOtherThanAJwsStringAreMalformed() {
        listOf(
            """{"bridge_origins":["https://h5-stg.couliapp.cn"]}""" to "旧的字符串数组形状",
            """{"h5_base_url":"https://h5-stg.couliapp.cn/"}""" to "缺 bridge_origins（h5_base_url 不当白名单）",
            """{"bridge_origins":{}}""" to "缺 jws",
            """{"bridge_origins":{"h5":"https://h5-stg.couliapp.cn"}}""" to "对象里没有 jws",
            """{"bridge_origins":{"jws":123}}""" to "jws 是数字",
            """{"bridge_origins":{"jws":null}}""" to "jws 是 null",
            """{"bridge_origins":{"jws":true}}""" to "jws 是布尔",
            """{"bridge_origins":{"jws":["$stgLive"]}}""" to "jws 是数组",
            """{"bridge_origins":{"jws":{"v":"$stgLive"}}}""" to "jws 是对象",
            """{"bridge_origins":"$stgLive"}""" to "bridge_origins 直接是字符串",
            """{"bridge_origins":null}""" to "bridge_origins 是 null",
        ).forEach { (json, what) ->
            val verdict =
                BridgeOriginsVerifier.verify(
                    config = config(json),
                    appId = APP_ID,
                    env = STAGING_ENV,
                    lastGoodVersion = null,
                    keys = N22Vectors.keys,
                )
            assertRejected(what, verdict, reason = "malformed")
        }
    }

    @Test
    fun configWithTheLiveJwsIsAccepted() {
        val data = config("""{"h5_base_url":"https://h5-stg.couliapp.cn","bridge_origins":{"jws":"$stgLive"}}""")
        assertAccepted(
            "staging 实测形状",
            BridgeOriginsVerifier.verify(config = data, appId = APP_ID, env = STAGING_ENV, lastGoodVersion = null, keys = N22Vectors.keys),
            0L,
            listOf(STG_ORIGIN),
        )
        assertRejected(
            "同一份配置、上次可用 version 1 → 回退",
            BridgeOriginsVerifier.verify(config = data, appId = APP_ID, env = STAGING_ENV, lastGoodVersion = 1L, keys = N22Vectors.keys),
        )
    }

    @Test
    fun bundledPlaceholderJwsIsRejected() {
        val bundled = config(BundledConfig.dataJson)
        val jws = (bundled.getValue("bridge_origins").jsonObject.getValue("jws")).jsonPrimitive.content
        assertEquals("包内是占位 jws（生成物，保持不动）", "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl", jws)
        listOf(STAGING_ENV, TEST_ENV, "prod").forEach { env ->
            assertRejected(
                "包内占位 @ $env",
                BridgeOriginsVerifier.verify(config = bundled, appId = APP_ID, env = env, lastGoodVersion = null, keys = N22Vectors.keys),
            )
        }
    }

    // ---------------------------------------------------------------- 测试钥现签：验签之后的类型规则

    @Test
    fun freshlySignedGoodJwsIsAcceptedIncludingVersionsBeyondInt() {
        val signer = N22TestSigner()
        val verify = { jws: String, lastGood: Long? -> BridgeOriginsVerifier.verify(jws, APP_ID, TEST_ENV, lastGood, signer.keys) }
        assertAccepted("现签正例", verify(signer.sign(), null), 1L, listOf("https://h5.example.test"))
        assertAccepted("version 0", verify(signer.sign(signer.payload(version = "0")), null), 0L, listOf("https://h5.example.test"))
        val big = 4_000_000_000L
        assertAccepted("version 超过 Int", verify(signer.sign(signer.payload(version = "$big")), big), big, listOf("https://h5.example.test"))
        assertRejected("version 超过 Int 的回退", verify(signer.sign(signer.payload(version = "$big")), big + 1))
    }

    @Test
    fun freshlySignedJwsWithWrongValueTypesIsRejected() {
        val signer = N22TestSigner()
        val verify = { jws: String -> BridgeOriginsVerifier.verify(jws, APP_ID, TEST_ENV, null, signer.keys) }
        mapOf(
            "v 为 true" to signer.payload(v = "true"),
            "v 为字符串 \"1\"" to signer.payload(v = "\"1\""),
            "version 为 1.5" to signer.payload(version = "1.5"),
            "version 为字符串 \"3\"" to signer.payload(version = "\"3\""),
            "version 为 true" to signer.payload(version = "true"),
            "version 为 null" to signer.payload(version = "null"),
            "origins 为字符串" to signer.payload(origins = "\"https://h5.example.test\""),
            "origins 含非字符串" to signer.payload(origins = """["https://h5.example.test",1]"""),
            "app_id 为数字" to signer.payload(appId = "1"),
            "env 不符" to signer.payload(env = "\"staging\""),
            "payload 不是对象" to """["https://h5.example.test"]""",
        ).forEach { (what, payload) -> assertRejected(what, verify(signer.sign(payload))) }
        assertRejected("签名坏", verify(N22TestSigner.corruptSignature(signer.sign())))
        assertRejected("签名是 DER 形式", verify(signer.signDer()))
        assertRejected("header 不是对象", verify(signer.sign(headerJson = "[\"ES256\"]")))
        assertRejected("kid 不在表里", verify(signer.sign(headerJson = signer.header(kid = "other-kid"))))
        assertRejected("别的钥签的", verify(N22TestSigner(kid = signer.kid).sign()))
    }
}
