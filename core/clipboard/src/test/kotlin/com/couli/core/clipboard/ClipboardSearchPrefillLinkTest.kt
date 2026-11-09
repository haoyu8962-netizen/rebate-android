package com.couli.core.clipboard

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FU-AND-SEARCH-LINK-SUBMIT 测试 v4.1（D10-666 ①）：30132【用商品名搜索】的预填照 BR-TEXT-14 表 A「预填「」内或首行商品名，提取不到则留空」。
 * 首行是链接（http / https 或平台短链）不是商品名，属于「提取不到」，[ClipboardText.searchPrefill] 返回空；粘贴与搜索框共用这一处。
 */
class ClipboardSearchPrefillLinkTest {
    // ---------------------------------------------------------------- searchPrefill 纯函数

    @Test
    fun firstLineHttpLinkPrefillsNothing() {
        assertNull("首行是 http 链接：留空", ClipboardText.searchPrefill(HTTP_LINK))
        assertNull("首行是 http 链接：留空，也不退到第二行", ClipboardText.searchPrefill("$HTTP_LINK\n$TITLE"))
    }

    @Test
    fun firstLineHttpsLinkPrefillsNothing() {
        assertNull("首行是 https 链接：留空", ClipboardText.searchPrefill(LINK))
        assertNull("首个非空行是 https 链接（前后有空白）：留空", ClipboardText.searchPrefill("\n  $SECOND_LINK  \n$TITLE"))
    }

    @Test
    fun firstLinePlatformShortLinkPrefillsNothing() {
        assertNull("首行是淘宝短链（无 scheme）：留空", ClipboardText.searchPrefill(TB_SHORT))
        assertNull("首行是京东短链（无 scheme）：留空，也不退到第二行", ClipboardText.searchPrefill("$JD_SHORT\n$TITLE"))
    }

    @Test
    fun quotedTitleIsUsed() {
        assertEquals("有「」标题取标题，即使首行以链接开头", TITLE, ClipboardText.searchPrefill("$LINK「$TITLE」"))
        assertEquals("「」标题去首尾空白", TITLE, ClipboardText.searchPrefill("$TB_SHORT\n「 $TITLE 」"))
    }

    @Test
    fun firstLineProductNameIsUsed() {
        assertEquals("首行是商品名取首行", TITLE, ClipboardText.searchPrefill("$TITLE\n$LINK"))
        assertEquals("以 HTTP 字母开头的商品名不是链接，照取首行", "HTTP转接头 USB3.0", ClipboardText.searchPrefill("HTTP转接头 USB3.0"))
    }

    @Test
    fun emptyTextPrefillsNothing() {
        assertNull(ClipboardText.searchPrefill(null))
        assertNull(ClipboardText.searchPrefill(""))
        assertNull(ClipboardText.searchPrefill("  \n\t "))
    }

    // ---------------------------------------------------------------- 粘贴：行内 30132 + 纯链接（D10-666 ①：原「把链接预填进去」按缺陷修）

    @Test
    fun pasteInline30132WithBareLinkPrefillsNothing() {
        val action = ParseResultAction.map(envelope(inlineUnrecognized(LINK)), errorCode = null, retryAfter = null, traceId = null)
        assertEquals("前提：行内 30132 → search_prefill", ParseResultAction.SEARCH_PREFILL, action.actionRaw)
        assertEquals("前提：文案 error.30132", "error.30132", action.textKey)
        assertNull("hit.raw 是链接本身：不拿链接当商品名，预填留空", action.prefill)
    }

    @Test
    fun pasteServiceInline30132WithBareLinkPrefillsNothing() =
        runTest {
            val api = OneReplyApi(ApiResult.Success(data(inlineUnrecognized(LINK)), "parse-000123", null))
            val outcome = ParseInputService(api).parse(LINK, CallOwnership(CallEpochs(0, 0)) { true })
            assertEquals("前提：只发一次 parse", listOf(ParseInputService.OPERATION), api.calls.map { it.operationId })
            val action = checkNotNull(outcome.action) { "前提：结果未被丢弃" }
            assertEquals("前提：行内 30132 → search_prefill", ParseResultAction.SEARCH_PREFILL, action.actionRaw)
            assertNull("粘贴的 parse 服务：行内 30132 + 纯链接，预填留空", action.prefill)
        }

    private fun inlineUnrecognized(raw: String): String =
        """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$raw"},"error_code":30132}]}"""

    private fun data(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun envelope(dataText: String): JsonObject = data("""{"code":0,"msg":"","data":$dataText,"trace_id":"parse-000123"}""")

    private class OneReplyApi(
        private val reply: ApiResult,
    ) : PageApi {
        val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return reply
        }

        override fun epochs(): CallEpochs = CallEpochs(0, 0)

        override fun cancelOwned(owner: CallOwnership) = Unit
    }

    private companion object {
        const val TITLE = "示例纯牛奶整箱"
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val HTTP_LINK = "http://item.taobao.com/item.htm?id=123456"
        const val SECOND_LINK = "https://item.jd.com/100200.html"
        const val TB_SHORT = "m.tb.cn/h.UxQ3kZ9"
        const val JD_SHORT = "u.jd.com/AbCdEf"
    }
}
