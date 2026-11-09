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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D10-651 ① ③（v3）：识别「认出了但有结论」的 30131 / 30141 / 口令 30132 不回落关键词，按粘贴同一结果组件与文案呈现；识别成功与按提示呈现的
 * 都不写搜索历史，识别不到照关键词搜的照常写。走 v1 同一条生产提交入口（真实 AppSearch / SearchViewModel / AppClipboard），网络只换 PageApi。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每个结局一条，便于先红与变异复核
class SearchLinkOutcomeSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- ① 30131

    @Test
    fun unsupportedResponseShowsPasteNoticeAndPrefillsTitleWithoutSearch() =
        concluded(TITLED_LINK, failure(UNSUPPORTED)) { f ->
            assertPasteNotice(f, UNSUPPORTED)
            assertEquals("30131 把标题预填进搜索框，由用户再点搜索", TITLE, f.model.input.value)
        }

    @Test
    fun unsupportedResultRowShowsPasteNoticeAndPrefillsTitleWithoutSearch() =
        concluded(TITLED_LINK, row("url", LINK, UNSUPPORTED)) { f ->
            assertPasteNotice(f, UNSUPPORTED)
            assertEquals("行内 30131 同样预填标题、不自动搜", TITLE, f.model.input.value)
        }

    @Test
    fun unsupportedWithoutTitleShowsNoticeAndNeverSearchesRawLink() =
        concluded(LINK, failure(UNSUPPORTED)) { f ->
            assertPasteNotice(f, UNSUPPORTED)
        }

    // ---------------------------------------------------------------- ① 30141（呈现与【搜相似商品】的点击见 SearchLinkNoticeSubmitTest）

    @Test
    fun offShelfResultRowDoesNotFallBackToKeywordSearch() = concluded(TITLED_LINK, row("url", LINK, OFF_SHELF)) { }

    // ---------------------------------------------------------------- ① 口令 30132

    @Test
    fun unrecognizedTokenShowsPasteSearchByNameWithoutKeywordSearch() =
        concluded(TITLED_TOKEN, failure(UNRECOGNIZED)) { f ->
            val result = f.clipboard.result.value
            assertTrue("口令识别不了用粘贴同一个 30132 结果（文案 +【用商品名搜索】），实际 $result", result is ClipboardResult.Unrecognized)
            result as ClipboardResult.Unrecognized
            assertEquals("结果归属搜索页", SearchSubmitFixture.ENTRY, result.origin)
            assertEquals("粘贴同文案 error.30132", ClipboardDraftTexts.text("error.30132"), result.text)
            assertEquals("【用商品名搜索】的预填与粘贴同规则：请求前抽出的「」标题", TITLE, result.prefill)
        }

    // ---------------------------------------------------------------- D10-656 ① 链接 30132（v4）：不分口令 / 链接，一律照粘贴

    @Test
    fun unrecognizedLinkShowsPasteSearchByNameWithoutKeywordSearch() =
        concluded(TITLED_LINK, failure(UNRECOGNIZED)) { f ->
            val result = f.clipboard.result.value
            assertTrue("链接识别不了也用粘贴同一个 30132 结果（文案 +【用商品名搜索】），实际 $result", result is ClipboardResult.Unrecognized)
            result as ClipboardResult.Unrecognized
            assertEquals("结果归属搜索页", SearchSubmitFixture.ENTRY, result.origin)
            assertEquals("粘贴同文案 error.30132", ClipboardDraftTexts.text("error.30132"), result.text)
            assertEquals("【用商品名搜索】预填请求前抽出的「」标题，不是原始链接", TITLE, result.prefill)
        }

    // ---------------------------------------------------------------- ③ 历史：识别成功不写

    @Test
    fun singleCardSuccessDoesNotWriteSearchHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                submitAndReply(f, LINK, cards(card("tb:7Kq9LmN3pQ", "taobao", "示例商品")))
                assertEquals("前提：单卡直进详情", listOf("ProductDetail"), f.navigations.map { it.first })
                assertEquals(listOf("parseInput"), operations(f))
                assertNoHistory(f)
            }
        }

    @Test
    fun multipleCardsSuccessDoesNotWriteSearchHistory() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                submitAndReply(
                    f,
                    "$LINK $SECOND_LINK",
                    cards(card("tb:7Kq9LmN3pQ", "taobao", "第一件示例商品"), card("jd:100200", "jd", "第二件示例商品")),
                )
                assertTrue("前提：多卡出 CardList", f.clipboard.result.value is ClipboardResult.CardList)
                assertEquals(listOf("parseInput"), operations(f))
                assertNoHistory(f)
            }
        }

    // ---------------------------------------------------------------- ③ 历史：没有候选（不上传）、照关键词搜的照常写（D10-656 ①）

    @Test
    fun ordinaryWordStillWritesKeywordHistory() = keywordHistory(WORD, expectParse = false)

    @Test
    fun nonCandidateLinkStillWritesKeywordHistory() = keywordHistory("https://example.test/item/123", expectParse = false)

    // v4：原 unrecognizedLinkFallbackStillWritesKeywordHistory（链接 + 30132 回落）按 D10-656 ① 换成无候选输入：只有「」标题、没有链接或口令
    @Test
    fun titledTextWithoutCandidateStillWritesKeywordHistory() = keywordHistory("「$TITLE」", expectParse = false)

    // ---------------------------------------------------------------- 公共

    /** 识别「认出了但有结论」：响应后仍只有一次 parse、不导航、不写历史；[check] 校验呈现。 */
    private fun concluded(
        input: String,
        reply: ApiResult,
        check: (SearchSubmitFixture) -> Unit,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            submitAndReply(f, input, reply)
            assertEquals("认出了但有结论：不回落关键词搜、不拿原始链接当关键词", listOf("parseInput"), operations(f))
            assertTrue("不得进详情或另开页面", f.navigations.isEmpty())
            check(f)
            // 再排空一次：预填后也不得自动搜
            advanceUntilIdle()
            assertEquals("预填 / 提示后不得自动搜索", listOf("parseInput"), operations(f))
            assertNoHistory(f)
        }
    }

    private fun keywordHistory(
        input: String,
        expectParse: Boolean,
        reply: ApiResult? = null,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            reply?.let(f.api.parseReply::complete)
            f.submit(input)
            advanceUntilIdle()
            val expected = if (expectParse) listOf("parseInput", "searchProducts") else listOf("searchProducts")
            assertEquals("前提：识别不到照关键词搜一次", expected, operations(f))
            val query = Uri.parse("https://fixture.invalid/?${f.api.calls.last().query}")
            assertEquals(input, query.getQueryParameter("q"))
            assertEquals("识别不到、照关键词搜的按普通关键词写入历史", listOf(input), f.pageConfig.history.items)
            assertEquals(listOf(input), f.model.history.value)
        }
    }

    private fun TestScope.submitAndReply(
        f: SearchSubmitFixture,
        input: String,
        reply: ApiResult,
    ) {
        assertEquals(PrivacyState.CONSENTED, f.privacy.conclusion)
        f.submit(input)
        runCurrent()
        assertEquals("前提：输入是候选，先只发识别", listOf("parseInput"), operations(f))
        f.api.parseReply.complete(reply)
        advanceUntilIdle()
    }

    private fun assertPasteNotice(
        f: SearchSubmitFixture,
        code: Int,
    ) {
        val result = f.clipboard.result.value
        assertTrue("用粘贴同一个结果组件（ClipboardResult.Notice 轻提示），实际 $result", result is ClipboardResult.Notice)
        result as ClipboardResult.Notice
        assertEquals("结果归属搜索页", SearchSubmitFixture.ENTRY, result.origin)
        // 与粘贴同文案键（texts.default.json error.<码>），不新增文案键；本夹具不带 trace_id，粘贴也不附编号
        assertEquals("粘贴同文案 error.$code", ClipboardDraftTexts.text("error.$code"), result.text)
    }

    private fun assertNoHistory(f: SearchSubmitFixture) {
        assertEquals("识别成功 / 按提示呈现的都不写搜索历史", emptyList<String>(), f.pageConfig.history.items)
        assertEquals(emptyList<String>(), f.model.history.value)
    }

    private fun operations(f: SearchSubmitFixture): List<String> = f.api.calls.map { it.operationId }

    private fun failure(code: Int): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            null,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, registerPending = false),
            null,
        )

    private fun row(
        kind: String,
        raw: String,
        code: Int,
    ): ApiResult =
        ApiResult.Success(
            SearchSubmitFixture.json("""{"results":[{"hit":{"platform":"taobao","kind":"$kind","raw":"$raw"},"error_code":$code}]}"""),
            null,
            null,
        )

    private fun cards(vararg card: String): ApiResult =
        ApiResult.Success(SearchSubmitFixture.json("""{"results":[${card.joinToString(",")}]}"""), null, null)

    private fun card(
        key: String,
        platform: String,
        title: String,
    ): String =
        """
        {
          "hit":{"platform":"$platform","kind":"url","raw":"$LINK"},
          "card":{
            "platform":"$platform","product_key":"$key","title":"$title","availability":"ok","rebate_basis":"normal",
            "link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",
            "price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,
            "rebate_min_fen":269,"rebate_max_fen":269,"est_net_price_fen":2721,
            "disclaimer_keys":["price_basis"],"cta":{"text_key":"btn.buy.coupon"}
          }
        }
        """.trimIndent()

    private companion object {
        const val UNSUPPORTED = 30131
        const val UNRECOGNIZED = 30132
        const val OFF_SHELF = 30141
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val SECOND_LINK = "https://item.jd.com/100200.html"
        const val TOKEN = "￥Zx8Cv7Bn6Mq￥"
        const val TITLE = "示例纯牛奶整箱"
        const val TITLED_LINK = "「$TITLE」$LINK"
        const val TITLED_TOKEN = "「$TITLE」$TOKEN"
        const val WORD = "全脂纯牛奶"
    }
}
