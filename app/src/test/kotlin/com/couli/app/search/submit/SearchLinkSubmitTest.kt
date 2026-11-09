@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardDraftTexts
import com.couli.app.clipboard.ClipboardResult
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import com.couli.core.router.PrivacyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FU-AND-SEARCH-LINK-SUBMIT：从输入框的生产提交入口到网络与 Router，不在测试侧实现识别分支。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 行为与错误结局逐条命名，便于先红和变异复核。
class SearchLinkSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun linkOpensDetailWithoutKeywordSearch() = opensDetail(LINK, "url")

    @Test fun tokenOpensDetailWithoutKeywordSearch() = opensDetail(TOKEN, "tpwd")

    @Test fun linkStillOpensDetailWhenKeywordSearchIsDisabled() = opensDetail(LINK, "url", searchEnabled = false)

    @Test
    fun ordinaryWordsOnlySearchOnceWithoutParse() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.submit(WORD)
                advanceUntilIdle()
                assertKeywordOnly(f, WORD)
            }
        }

    @Test
    fun nonCandidateLinkFallsBackLocallyWithoutUploading() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val text = "https://example.test/item/123"
                f.submit(text)
                advanceUntilIdle()
                assertKeywordOnly(f, text)
            }
        }

    // D10-656 ①（v4 改期望）：上传了候选、接口回 30132，不分口令 / 链接一律照粘贴处理，不回落关键词
    @Test
    fun unrecognizedResponseShowsPasteSearchByNameWithoutKeywordSearch() =
        showsPasteUnrecognized(failure(30132)) { prefill ->
            assertNull("整体 30132、原文没有「」标题：预填留空，不拿原始链接当商品名", prefill)
        }

    @Test
    fun unrecognizedResultRowShowsPasteSearchByNameWithoutKeywordSearch() =
        showsPasteUnrecognized(
            ApiResult.Success(
                SearchSubmitFixture.json(
                    """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"error_code":30132}]}""",
                ),
                "parse-submit-000123",
                null,
            ),
        ) { prefill ->
            // D10-666 ①（v4.1 补断言）：hit.raw 是链接本身，首行不是商品名，属「提取不到」，预填留空（与粘贴同一个 searchPrefill）
            assertNull("行内 30132 + 纯链接：预填留空，不拿原始链接当商品名", prefill)
        }

    @Test fun serverErrorFallsBackToOneKeywordSearch() = fallsBack(failure(50001))

    @Test
    fun transportErrorFallsBackToOneKeywordSearch() =
        fallsBack(ApiResult.TransportFailure("disconnect", null, null, pendingKeyUnknown = false))

    @Test
    fun basicModeLinkOnlySearchesOnceWithoutParse() =
        runTest {
            SearchSubmitFixture(backgroundScope, basic = true).use { f ->
                assertEquals(PrivacyState.BASIC_MODE, f.privacy.conclusion)
                // 即使识别会成功也不能调用；替身不替产品拦基本模式的请求。
                f.api.parseReply.complete(parsed(LINK, "url"))
                f.submit(LINK)
                advanceUntilIdle()
                assertKeywordOnly(f, LINK)
            }
        }

    private fun opensDetail(
        input: String,
        kind: String,
        searchEnabled: Boolean = true,
    ) = runTest {
        SearchSubmitFixture(backgroundScope, searchEnabled = searchEnabled).use { f ->
            assertEquals(PrivacyState.CONSENTED, f.privacy.conclusion)
            f.submit(input)
            runCurrent()
            assertParseOnly(f, input)
            f.api.parseReply.complete(parsed(input, kind))
            advanceUntilIdle()
            assertEquals("成功后不得再发关键词请求", listOf("parseInput"), operations(f))
            assertEquals(
                "直达详情一次，平台、商品键、item_ref 原样透传",
                listOf("ProductDetail" to DETAIL_PARAMS),
                f.navigations,
            )
        }
    }

    private fun fallsBack(reply: ApiResult) =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.submit(LINK)
                runCurrent()
                assertParseOnly(f, LINK)
                f.api.parseReply.complete(reply)
                advanceUntilIdle()
                assertEquals("先识别，失败后只搜索一次，不重试识别", listOf("parseInput", "searchProducts"), operations(f))
                assertKeyword(f, LINK)
            }
        }

    /** 链接 + 30132：只发一次 parse，显示粘贴同一个 Unrecognized（error.30132 +【用商品名搜索】），不导航、不搜；[prefill] 校验预填。 */
    private fun showsPasteUnrecognized(
        reply: ApiResult,
        prefill: (String?) -> Unit,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            f.submit(LINK)
            runCurrent()
            assertParseOnly(f, LINK)
            f.api.parseReply.complete(reply)
            advanceUntilIdle()
            assertEquals("上传了候选、回 30132：照粘贴处理，不回落关键词搜", listOf("parseInput"), operations(f))
            assertTrue("30132 不得进详情", f.navigations.isEmpty())
            val result = f.clipboard.result.value
            assertTrue("用粘贴同一个 30132 结果（文案 +【用商品名搜索】），实际 $result", result is ClipboardResult.Unrecognized)
            result as ClipboardResult.Unrecognized
            assertEquals("结果归属搜索页", SearchSubmitFixture.ENTRY, result.origin)
            assertEquals("粘贴同文案 error.30132", ClipboardDraftTexts.text("error.30132"), result.text)
            prefill(result.prefill)
        }
    }

    private fun assertParseOnly(
        f: SearchSubmitFixture,
        input: String,
    ) {
        assertEquals("识别未返回时不得抢先搜索", listOf("parseInput"), operations(f))
        assertTrue("未成功不得提前进详情", f.navigations.isEmpty())
        val call = f.api.calls.single()
        assertEquals(
            "沿用手动粘贴的识别请求形状",
            JsonObject(mapOf("text" to JsonPrimitive(input), "scene" to JsonPrimitive("clipboard"))),
            SearchSubmitFixture.json(checkNotNull(call.body)),
        )
        assertTrue("识别请求须绑定归属", call.ownership?.stillOwned() == true)
    }

    private fun assertKeywordOnly(
        f: SearchSubmitFixture,
        input: String,
    ) {
        assertEquals(listOf("searchProducts"), operations(f))
        assertKeyword(f, input)
    }

    private fun assertKeyword(
        f: SearchSubmitFixture,
        input: String,
    ) {
        val calls = f.api.calls.filter { it.operationId == "searchProducts" }
        assertEquals("关键词请求恰好一次", 1, calls.size)
        val query = Uri.parse("https://fixture.invalid/?${calls.single().query}")
        assertEquals("回落保留提交内容", input, query.getQueryParameter("q"))
        assertEquals("taobao", query.getQueryParameter("platform"))
        assertTrue("关键词路径不得打开详情", f.navigations.isEmpty())
    }

    private fun operations(f: SearchSubmitFixture): List<String> = f.api.calls.map { it.operationId }

    private fun failure(code: Int): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            "parse-submit-000123",
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, registerPending = false),
            null,
        )

    private fun parsed(
        input: String,
        kind: String,
    ): ApiResult =
        ApiResult.Success(
            SearchSubmitFixture.json(
                """
                {
                  "results":[{
                    "hit":{"platform":"taobao","kind":"$kind","raw":"$input"},
                    "card":{
                      "platform":"taobao","product_key":"tb:7Kq9LmN3pQ","item_ref":"v1.ref.sig",
                      "title":"示例商品","availability":"ok","rebate_basis":"normal",
                      "link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",
                      "price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,
                      "rebate_min_fen":269,"rebate_max_fen":269,"est_net_price_fen":2721,
                      "disclaimer_keys":["price_basis"],"cta":{"text_key":"btn.buy.coupon"}
                    }
                  }]
                }
                """.trimIndent(),
            ),
            "parse-submit-000123",
            null,
        )

    private companion object {
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val TOKEN = "￥Zx8Cv7Bn6Mq￥"
        const val WORD = "全脂纯牛奶"
        val DETAIL_PARAMS =
            SearchSubmitFixture.json("""{"platform":"taobao","product_key":"tb:7Kq9LmN3pQ","item_ref":"v1.ref.sig"}""")
    }
}
