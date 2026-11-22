package com.couli.core.bridge

import com.couli.core.bridge.N22Vectors.APP_ID
import com.couli.core.bridge.N22Vectors.TEST_ENV
import com.couli.core.bridge.N22Vectors.describe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N-22-android 评审 r1 的 S2-1 / S2-2 补测（测试钥现签，写法照 [BridgeOriginsVerifierSupplementTest]）：
 * - S2-1：实现比参考校验器 check-vectors.py 更严的数字类型与端口检查——v 只认 JSON 整数字面量 `1`；version 只认非负、不超过 Long 的
 *   JSON 整数字面量；端口无前导 0、在 1..65535、不是 443。
 * - S2-2：origin 规范形（BridgeOriginsVerifier.ORIGIN + matchEntire）向量 6 条之外的拒绝写法；每条跑「单项」与「好项 + 坏项」两种形式。
 * 每组先跑应通过的对照（同一签名者、同一写法只换被测字段），证明拒绝来自被测规则而不是别处。
 */
class BridgeOriginsN22R1Test {
    private val signer = N22TestSigner()
    private val good = "https://h5.example.test"

    private fun verify(payload: String): BridgeOriginsVerdict =
        BridgeOriginsVerifier.verify(signer.sign(payload), APP_ID, TEST_ENV, null, signer.keys)

    /** 字符串数组按 JSON 编码（换行、制表、反斜杠、引号都正确转义）。 */
    private fun originsJson(vararg origins: String): String = JsonArray(origins.map(::JsonPrimitive)).toString()

    private fun assertAccepted(
        what: String,
        payload: String,
        version: Long,
        origins: List<String>,
    ) {
        val verdict = verify(payload)
        assertEquals("$what：实得 ${describe(verdict)}", BridgeOriginsVerdict.Accepted(BridgeOriginsAllowlist(version, origins)), verdict)
    }

    private fun assertRejected(
        what: String,
        payload: String,
        reason: String,
    ) {
        val verdict = verify(payload)
        assertEquals("$what：实得 ${describe(verdict)}", BridgeOriginsVerdict.Rejected(reason), verdict)
    }

    /** 坏 origin 两种形式各验一次：单项、好项 + 坏项；并核对签进去的确实是这个原文（转义无误）。 */
    private fun assertOriginRejected(origin: String) {
        val label = "origin ${JsonPrimitive(origin)}"
        listOf(listOf(origin), listOf(good, origin)).forEach { items ->
            val payload = signer.payload(origins = originsJson(*items.toTypedArray()))
            val signed = N22Vectors.payloadOf(signer.sign(payload)).getValue("origins").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$label：签进 payload 的原文", items, signed)
            assertRejected("$label（${if (items.size == 1) "单项" else "好项 + 坏项"}）", payload, BridgeOriginsVerifier.REASON_BAD_ORIGIN)
        }
    }

    // ---------------------------------------------------------------- S2-1：v

    @Test
    fun n22r1_vMustBeTheJsonIntegerLiteralOne() {
        assertAccepted("对照 v 为 1", signer.payload(v = "1"), 1L, listOf(good))
        listOf("1.0", "1e0", "1E0", "1.00", "10e-1", "1e+0", "01", "+1").forEach { raw ->
            assertRejected("v 为 $raw", signer.payload(v = raw), BridgeOriginsVerifier.REASON_BAD_V)
        }
    }

    // ---------------------------------------------------------------- S2-1：version

    @Test
    fun n22r1_versionMustBeANonNegativeJsonIntegerLiteralWithinLong() {
        assertAccepted("对照 version 3", signer.payload(version = "3"), 3L, listOf(good))
        assertAccepted("对照 version 0", signer.payload(version = "0"), 0L, listOf(good))
        assertAccepted("对照 version = Long.MAX_VALUE", signer.payload(version = "${Long.MAX_VALUE}"), Long.MAX_VALUE, listOf(good))
        listOf(
            "3.0",
            "3e0",
            "3E0",
            "3e+0",
            "0.3e1",
            "-0.0",
            "0.0",
            "03",
            "00",
            "+3",
            "-9223372036854775808",
            "9223372036854775808",
            "99999999999999999999",
        ).forEach { raw ->
            assertRejected("version 为 $raw", signer.payload(version = raw), BridgeOriginsVerifier.REASON_BAD_PAYLOAD)
        }
    }

    // ---------------------------------------------------------------- S2-1：端口

    @Test
    fun n22r1_portHasNoLeadingZeroIsIn1To65535AndIsNot443() {
        val accepted = listOf("$good:1", "$good:442", "$good:444", "$good:8443", "$good:65535")
        accepted.forEach { origin -> assertAccepted("对照 $origin", signer.payload(origins = originsJson(origin)), 1L, listOf(origin)) }
        assertAccepted("对照 多项", signer.payload(origins = originsJson(*accepted.toTypedArray())), 1L, accepted)
        // :443 已由向量 t_origin_port443 覆盖，这里不重复
        listOf(":0", ":00", ":01", ":0443", ":08443", ":065535", ":65536", ":99999", ":100000", ":", ":-1", ":+8443", ": 8443")
            .forEach { port -> assertOriginRejected(good + port) }
    }

    // ---------------------------------------------------------------- S2-2：origin 规范形

    @Test
    fun n22r1_originCanonicalFormControlsAreAccepted() {
        listOf(
            "https://xn--fsqu00a.xn--0zwm56d",
            "https://xn--fsqu00a.xn--0zwm56d:8443",
            "https://a-b.example:8443",
            "https://a--b.c0.example",
            "https://0.example",
            good,
        ).forEach { origin ->
            assertAccepted("对照 $origin", signer.payload(origins = originsJson(origin)), 1L, listOf(origin))
            assertAccepted("对照 好项 + $origin", signer.payload(origins = originsJson(good, origin)), 1L, listOf(good, origin))
        }
    }

    @Test
    fun n22r1_originHostLabelsMustBeNonEmptyLdhWithoutEdgeHyphens() {
        listOf(
            "https://h5.example.test.",
            "https://h5.example.test.:8443",
            "https://a..example",
            "https://.example.test",
            "https://-a.example",
            "https://a-.example",
            "https://a.-b.example",
            "https://a.b-.example",
            "https://a.b-",
            "https://-",
            "https://a_b.example",
        ).forEach(::assertOriginRejected)
    }

    @Test
    fun n22r1_originWithTrailingOrLeadingWhitespaceIsRejected() {
        listOf(
            "$good\n",
            "$good\r\n",
            "$good\r",
            "$good\t",
            "$good ",
            " $good",
            "\n$good",
            "$good:8443\n",
            "https://h5 .example.test",
        ).forEach(::assertOriginRejected)
    }

    @Test
    fun n22r1_originWithUppercaseIsRejected() {
        listOf(
            "HTTPS://h5.example.test",
            "Https://h5.example.test",
            "https://h5.EXAMPLE.test",
            "https://h5.example.Test",
            "https://XN--fsqu00a.xn--0zwm56d",
        ).forEach(::assertOriginRejected)
    }

    @Test
    fun n22r1_originWithPathQueryFragmentOrCredentialsIsRejected() {
        listOf(
            "$good/",
            "$good:8443/",
            "$good?",
            "$good?a=1",
            "$good#",
            "$good#f",
            "https://u:p@h5.example.test",
            "https://u@h5.example.test:8443",
            "https://@h5.example.test",
            "https://h5.example.test@evil.example",
        ).forEach(::assertOriginRejected)
    }

    @Test
    fun n22r1_originWithIpv6OrIllegalCharactersIsRejected() {
        listOf(
            "https://[::1]",
            "https://[::1]:8443",
            "https://a%2eb.example",
            "https://h5.example.test\\",
            "https://h5\\.example.test",
            "https://h5。example.test",
            "https://h5．example.test",
            "https://例子.测试",
            "https://h5.example.test\u0000",
        ).forEach(::assertOriginRejected)
    }

    @Test
    fun n22r1_originWithMissingHostOrWrongSchemeShapeIsRejected() {
        listOf(
            "",
            "https://",
            "https://:8443",
            "https:/h5.example.test",
            "https:h5.example.test",
            "//h5.example.test",
            "h5.example.test",
            "wss://h5.example.test",
            "https ://h5.example.test",
        ).forEach(::assertOriginRejected)
    }
}
