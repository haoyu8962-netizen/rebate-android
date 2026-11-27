package com.couli.feature.trade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-A 测试：淘宝 web_code 回跳解析（设计稿 §5.3，按 D10-2717 改定值）。
 * - 成功 `couli://auth/taobao?code=<c>&state=<s>` → [WebCodeCallback.Code]；`?state=<s>&error=1` → [WebCodeCallback.Incomplete]（按未完成）。
 * - 只认 scheme couli、host auth、path /taobao；不得有 fragment、端口、userinfo；整串 ≤ 8192。
 * - state 1..128、code 1..4096（契约 BindUnionByWebCode）；取值（百分号解码后）只认中转页同一白名单 `[A-Za-z0-9_-]`。
 * - 同一个键出现两次、百分号编码非法：整串作废；code / state / error 以外的键忽略。
 * 「state 与本机记录不符」「同一 state 第二次回跳」不归解析器判，见 WebCodeInboxTest 与 BrowserWebCodeAuthorizerTest。
 * 纯 JVM：解析器不得依赖 android.net.Uri。
 */
class WebCodeCallbackParserTest {
    private fun parse(uri: String) = WebCodeCallbackParser.parse(uri)

    private fun code(uri: String): WebCodeCallback.Code {
        val parsed = parse(uri)
        assertTrue("应解析为 Code：$uri → $parsed", parsed is WebCodeCallback.Code)
        return parsed as WebCodeCallback.Code
    }

    private fun rejected(
        reason: String,
        uri: String,
    ) = assertNull("$reason 应丢弃（null）：${uri.take(120)}", parse(uri))

    @Test
    fun p01_codeAndStateParseToCode() {
        val parsed = code("couli://auth/taobao?code=C0de_x-9&state=St4te_y-1")
        assertEquals("C0de_x-9", parsed.code)
        assertEquals("St4te_y-1", parsed.state)
    }

    @Test
    fun p02_parameterOrderDoesNotMatter() {
        val parsed = code("couli://auth/taobao?state=S1&code=C1")
        assertEquals("C1", parsed.code)
        assertEquals("S1", parsed.state)
    }

    @Test
    fun p03_errorOneIsIncomplete() {
        val parsed = parse("couli://auth/taobao?state=S1&error=1")
        assertTrue("error=1 按未完成（D10-2717）：$parsed", parsed is WebCodeCallback.Incomplete)
        assertEquals("S1", parsed!!.state)
    }

    @Test
    fun p04_errorWithCodeIsNeverACode() {
        val parsed = parse("couli://auth/taobao?code=C1&state=S1&error=1")
        assertFalse("带 error 时不得当成拿到授权码：$parsed", parsed is WebCodeCallback.Code)
    }

    @Test
    fun p05_missingOrEmptyStateIsDropped() {
        rejected("缺 state", "couli://auth/taobao?code=C1")
        rejected("state 为空", "couli://auth/taobao?code=C1&state=")
        rejected("error=1 缺 state", "couli://auth/taobao?error=1")
    }

    @Test
    fun p06_missingOrEmptyCodeWithoutErrorIsDropped() {
        rejected("缺 code 又没有 error", "couli://auth/taobao?state=S1")
        rejected("code 为空", "couli://auth/taobao?code=&state=S1")
        rejected("没有查询串", "couli://auth/taobao")
    }

    @Test
    fun p07_lengthLimitsFollowTheContract() {
        assertEquals("state 128 字符可收", 128, code("couli://auth/taobao?code=C1&state=${"s".repeat(128)}").state.length)
        rejected("state 129 字符", "couli://auth/taobao?code=C1&state=${"s".repeat(129)}")
        assertEquals("code 4096 字符可收", 4096, code("couli://auth/taobao?code=${"c".repeat(4096)}&state=S1").code.length)
        rejected("code 4097 字符", "couli://auth/taobao?code=${"c".repeat(4097)}&state=S1")
        rejected("整串超过 8192 字符", "couli://auth/taobao?code=C1&state=S1&pad=${"p".repeat(8200)}")
    }

    @Test
    fun p08_wrongHostIsDropped() {
        rejected("host 是 oauth（设计稿旧建议值）", "couli://oauth/taobao?code=C1&state=S1")
        rejected("host 是 r（路由深链）", "couli://r/taobao?code=C1&state=S1")
        rejected("没有 host", "couli:///taobao?code=C1&state=S1")
        rejected("host 前缀相同", "couli://authx/taobao?code=C1&state=S1")
    }

    @Test
    fun p09_wrongPathIsDropped() {
        rejected("path 是 /jd", "couli://auth/jd?code=C1&state=S1")
        rejected("path 多一段", "couli://auth/taobao/x?code=C1&state=S1")
        rejected("path 带尾斜杠", "couli://auth/taobao/?code=C1&state=S1")
        rejected("没有 path", "couli://auth?code=C1&state=S1")
        rejected("path 前缀相同", "couli://auth/taobaox?code=C1&state=S1")
    }

    @Test
    fun p10_wrongSchemeIsDropped() {
        rejected("scheme 是 couli-dev", "couli-dev://auth/taobao?code=C1&state=S1")
        rejected("scheme 是 https", "https://auth/taobao?code=C1&state=S1")
        rejected("scheme 是 intent", "intent://auth/taobao?code=C1&state=S1#Intent;scheme=couli;end")
        rejected("不是 URL", "auth/taobao?code=C1&state=S1")
    }

    @Test
    fun p11_fragmentPortAndUserinfoAreDropped() {
        rejected("带 fragment", "couli://auth/taobao?code=C1&state=S1#x")
        rejected("带端口", "couli://auth:80/taobao?code=C1&state=S1")
        rejected("带 userinfo", "couli://u@auth/taobao?code=C1&state=S1")
    }

    @Test
    fun p12_duplicateKeysVoidTheWholeCallback() {
        rejected("code 出现两次", "couli://auth/taobao?code=C1&code=C2&state=S1")
        rejected("state 出现两次", "couli://auth/taobao?code=C1&state=S1&state=S2")
        rejected("error 出现两次", "couli://auth/taobao?state=S1&error=1&error=1")
    }

    @Test
    fun p13_extraKeysAreIgnored() {
        val parsed = code("couli://auth/taobao?code=C1&state=S1&from=h5&v=2")
        assertEquals("C1", parsed.code)
        assertEquals("S1", parsed.state)
    }

    @Test
    fun p14_illegalPercentEncodingIsDropped() {
        rejected("非法百分号编码 %zz", "couli://auth/taobao?code=C%zz1&state=S1")
        rejected("截断的百分号编码", "couli://auth/taobao?code=C1&state=S%")
    }

    @Test
    fun p15_illegalCharactersAreDropped() {
        rejected("code 含 NUL", "couli://auth/taobao?code=C%001&state=S1")
        rejected("state 含换行", "couli://auth/taobao?code=C1&state=S%0A1")
        rejected("code 含空格（编码）", "couli://auth/taobao?code=C%201&state=S1")
        rejected("code 含空格（原样）", "couli://auth/taobao?code=C 1&state=S1")
        rejected("state 含引号", "couli://auth/taobao?code=C1&state=S%221")
        rejected("code 含尖括号", "couli://auth/taobao?code=%3Cscript%3E&state=S1")
        rejected("code 含非 ASCII", "couli://auth/taobao?code=%E4%B8%AD&state=S1")
    }

    @Test
    fun p16_callbackToStringCarriesNoSecrets() {
        val parsed = code("couli://auth/taobao?code=SECRETcode42&state=SECRETstate42")
        assertFalse("toString 不带 code 原文", parsed.toString().contains("SECRETcode42"))
        assertFalse("toString 不带 state 原文", parsed.toString().contains("SECRETstate42"))
    }
}
