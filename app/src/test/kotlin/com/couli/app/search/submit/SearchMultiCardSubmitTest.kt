package com.couli.app.search.submit

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardConsentHost
import com.couli.app.clipboard.ClipboardResult
import com.couli.app.clipboard.ClipboardResultHost
import com.couli.app.clipboard.ClipboardScreens
import com.couli.core.clipboard.CandidateExtractor
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.network.ApiResult
import com.couli.feature.search.SearchScreen
import com.couli.feature.search.SearchTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D10-640：搜索页真实提交 + 外壳原有结果宿主；不在测试侧注入 CardList 或调用 openCard。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
class SearchMultiCardSubmitTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var fixture: SearchSubmitFixture

    @After fun close() {
        try {
            rule.runOnIdle { if (::fixture.isInitialized) fixture.close() }
        } finally {
            scope.cancel()
        }
    }

    @Test fun multipleCardsUseClipboardListWithoutNavigationOrKeywordSearch() {
        launch()
        submit()
        reply()
        assertCardList()
    }

    @Test fun clickingSecondCardOpensItsDetailOnceWithItemRef() = opensCard(1, SECOND_PARAMS)

    @Test fun clickingFirstCardOpensItsDetailOnceWithoutInventingItemRef() = opensCard(0, FIRST_PARAMS)

    @Test fun mixedInputUploadsOnlyCandidatesWithoutClipboardConsentOrNotice() {
        launch()
        val originalPreferences = fixture.preferences.importPreferences().snapshot()
        assertNull(originalPreferences.enabled)
        assertNull(originalPreferences.consentVersion)
        assertNull(originalPreferences.noticeSeenVersion)
        val input = "本地备注 $LINK https://x.test $SECOND_LINK"
        val expected = "$LINK\n$SECOND_LINK"
        // 独立固定期望，同时核对当前 CandidateExtractor；不以提取器输出替代请求体的期望值。
        assertEquals(expected, CandidateExtractor.extract(input).uploadText)
        submit(input, expected)
        reply()
        assertCardList()
        assertEquals("提交不得代用户补记剪贴板同意或已读说明", originalPreferences, fixture.preferences.importPreferences().snapshot())
    }

    private fun launch() {
        rule.runOnUiThread {
            fixture = SearchSubmitFixture(scope)
            fixture.clipboard.onBackStack("Search", SearchSubmitFixture.ENTRY, setOf("home", SearchSubmitFixture.ENTRY))
        }
        rule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides null) {
                CouliTheme {
                    Box {
                        SearchScreen(fixture.pageConfig)
                        // 与 AppShell 相同的结果 / 用途说明宿主，读真实 AppClipboard 的状态。
                        ClipboardResultHost(fixture.clipboard)
                        ClipboardConsentHost(fixture.clipboard)
                    }
                }
            }
        }
        rule.onNodeWithTag(SearchTags.PAGE).assertIsDisplayed()
        assertNoConsentNotice()
    }

    private fun submit(
        input: String = "$LINK $SECOND_LINK",
        upload: String = "$LINK\n$SECOND_LINK",
    ) {
        rule.onNodeWithTag(SearchTags.INPUT).performTextInput(input)
        rule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        rule.runOnIdle {
            assertEquals("识别在途只能有 parse，不得抢先关键词搜索", listOf("parseInput"), operations())
            assertTrue("识别返回前不得导航", fixture.navigations.isEmpty())
            val call = fixture.api.calls.single()
            assertEquals(
                "只上传最小候选，不上传原文包装或非白名单链接",
                JsonObject(mapOf("text" to JsonPrimitive(upload), "scene" to JsonPrimitive("clipboard"))),
                SearchSubmitFixture.json(checkNotNull(call.body)),
            )
            assertTrue("识别必须携带有效页面归属", call.ownership?.stillOwned() == true)
        }
        assertNoConsentNotice()
    }

    private fun reply() {
        rule.runOnUiThread { fixture.api.parseReply.complete(parsedCards()) }
        rule.waitForIdle()
    }

    private fun assertCardList() {
        rule.runOnIdle {
            assertEquals("多卡不得回落关键词搜索", listOf("parseInput"), operations())
            assertTrue("多卡不得擅自挑一张直进详情", fixture.navigations.isEmpty())
            val result = fixture.clipboard.result.value
            assertTrue("必须发布给粘贴共用的 CardList，不能新造列表状态", result is ClipboardResult.CardList)
            result as ClipboardResult.CardList
            assertEquals("结果归属仍是搜索页", SearchSubmitFixture.ENTRY, result.origin)
            assertEquals(listOf(FIRST_CARD, SECOND_CARD), result.rows.map { it.card })
        }
        rule.onNodeWithTag(SearchTags.PAGE).assertExists()
        rule.onAllNodes(hasTestTag(ClipboardScreens.CARD_LIST)).assertCountEquals(1)
        rule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertIsDisplayed()
        listOf("第一件示例商品", "第二件示例商品").forEachIndexed { index, title ->
            val tag = ClipboardScreens.CARD_PREFIX + index
            rule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(ClipboardScreens.CARD_LIST))).assertIsDisplayed()
            rule.onNode(hasText(title, substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
        }
        rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 2).assertDoesNotExist()
        assertNoConsentNotice()
    }

    private fun opensCard(
        index: Int,
        params: JsonObject,
    ) {
        launch()
        submit()
        reply()
        assertCardList()
        rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + index).performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("点选哪张就完整透传哪张的详情参数，且只导航一次", listOf("ProductDetail" to params), fixture.navigations)
            assertEquals("点卡也不得发关键词或再次识别", listOf("parseInput"), operations())
            assertNull("openCard 消费已点击的列表，避免重复导航", fixture.clipboard.result.value)
            val selected = if (index == 0) FIRST_CARD else SECOND_CARD
            assertEquals(
                "沿用 openDetail 的商品种子，保留 parse 卡的 link_id",
                selected,
                fixture.clipboard.seeds.claim("selected-detail", (params.getValue("product_key") as JsonPrimitive).content),
            )
        }
        rule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("ProductDetail" to params), fixture.navigations) }
        assertNoConsentNotice()
    }

    private fun assertNoConsentNotice() {
        rule.runOnIdle { assertNull("输入框主动提交不套剪贴板同意或首次用途说明", fixture.clipboard.consentNotice.value) }
        rule.onNodeWithTag("clipboard_consent_notice").assertDoesNotExist()
    }

    private fun operations(): List<String> = fixture.api.calls.map { it.operationId }

    private fun parsedCards(): ApiResult =
        ApiResult.Success(
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(FIRST_CARD to LINK, SECOND_CARD to SECOND_LINK).map { (card, raw) ->
                                JsonObject(
                                    mapOf(
                                        "hit" to
                                            JsonObject(
                                                mapOf(
                                                    "platform" to card.getValue("platform"),
                                                    "kind" to JsonPrimitive("url"),
                                                    "raw" to JsonPrimitive(raw),
                                                ),
                                            ),
                                        "card" to card,
                                    ),
                                )
                            },
                        ),
                ),
            ),
            "parse-submit-000123",
            null,
        )

    private companion object {
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val SECOND_LINK = "https://item.jd.com/100200.html"
        val FIRST_PARAMS = SearchSubmitFixture.json("""{"platform":"taobao","product_key":"tb:7Kq9LmN3pQ"}""")
        val SECOND_PARAMS = SearchSubmitFixture.json("""{"platform":"jd","product_key":"jd:100200","item_ref":"v1.ref.sig"}""")
        val FIRST_CARD = card(FIRST_PARAMS, "第一件示例商品", "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70")
        val SECOND_CARD = card(SECOND_PARAMS, "第二件示例商品", "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a71")

        fun card(
            params: JsonObject,
            title: String,
            linkId: String,
        ): JsonObject =
            JsonObject(
                params +
                    SearchSubmitFixture.json(
                        """
                        {
                          "title":"$title","availability":"ok","rebate_basis":"normal","link_id":"$linkId",
                          "price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,
                          "rebate_min_fen":269,"rebate_max_fen":269,"est_net_price_fen":2721,
                          "disclaimer_keys":["price_basis"],"cta":{"text_key":"btn.buy.coupon"}
                        }
                        """.trimIndent(),
                    ),
            )
    }
}
