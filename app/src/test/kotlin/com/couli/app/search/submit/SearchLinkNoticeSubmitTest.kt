package com.couli.app.search.submit

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardResultHost
import com.couli.app.clipboard.ClipboardScreens
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import com.couli.feature.search.SearchScreen
import com.couli.feature.search.SearchTags
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D10-651 ①（v3）：真实搜索页提交 + 外壳原有结果宿主。30141 显示「商品已下架」与【搜相似商品】，点后按标题关键词搜一次；口令 30132 的
 * 【用商品名搜索】与粘贴同一个组件、同一个动作（填进本页并搜）。测试不发布结果、不直接调 AppClipboard 的按钮入口。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
class SearchLinkNoticeSubmitTest {
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

    @Test fun offShelfShowsNoticeWithSimilarSearchThatSearchesTitleOnce() {
        launch()
        submit(TITLED_LINK)
        reply(offShelfRow())
        assertConcludedWithoutSearch()
        rule
            .onNode(hasText(DefaultTexts.text("error.30141").orEmpty(), substring = true), useUnmergedTree = true)
            .assertIsDisplayed()
        val similar = DefaultTexts.text("btn.search_similar").orEmpty()
        assertTrue("前提：契约字典有 btn.search_similar", similar.isNotEmpty())
        rule.onNode(hasText(similar) and hasClickAction()).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTitleSearchedOnce()
    }

    @Test fun unrecognizedTokenSearchByNameFillsTitleAndSearchesOnceLikePaste() {
        launch()
        submit(TITLED_TOKEN)
        reply(failure(30132))
        assertConcludedWithoutSearch()
        rule.onNodeWithTag(ClipboardScreens.UNRECOGNIZED, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(ClipboardScreens.SEARCH_BY_NAME, useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTitleSearchedOnce()
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
                        // 与 AppShell 相同的结果宿主，读真实 AppClipboard 的状态（用途说明宿主随 C2 删除）。
                        ClipboardResultHost(fixture.clipboard)
                    }
                }
            }
        }
        rule.onNodeWithTag(SearchTags.PAGE).assertIsDisplayed()
    }

    private fun submit(input: String) {
        rule.onNodeWithTag(SearchTags.INPUT).performTextInput(input)
        rule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        rule.runOnIdle { assertEquals("前提：输入是候选，先只发识别", listOf("parseInput"), operations()) }
    }

    private fun reply(result: ApiResult) {
        rule.runOnUiThread { fixture.api.parseReply.complete(result) }
        rule.waitForIdle()
    }

    private fun assertConcludedWithoutSearch() {
        rule.runOnIdle {
            assertEquals("认出了但有结论：不回落关键词搜", listOf("parseInput"), operations())
            assertTrue("不得进详情或另开页面", fixture.navigations.isEmpty())
            assertEquals("按提示呈现的不写搜索历史", emptyList<String>(), fixture.pageConfig.history.items)
        }
    }

    private fun assertTitleSearchedOnce() {
        rule.runOnIdle {
            assertEquals("点按钮后按标题在本页关键词搜一次，不再识别", listOf("parseInput", "searchProducts"), operations())
            val query = Uri.parse("https://fixture.invalid/?${fixture.api.calls.last().query}")
            assertEquals("搜索词取标题，不是原始链接 / 口令", TITLE, query.getQueryParameter("q"))
            assertTrue("本页搜索，不导航", fixture.navigations.isEmpty())
        }
    }

    private fun operations(): List<String> = fixture.api.calls.map { it.operationId }

    private fun offShelfRow(): ApiResult =
        ApiResult.Success(
            SearchSubmitFixture.json("""{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"error_code":30141}]}"""),
            null,
            null,
        )

    private fun failure(code: Int): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            null,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, registerPending = false),
            null,
        )

    private companion object {
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val TOKEN = "￥Zx8Cv7Bn6Mq￥"
        const val TITLE = "示例纯牛奶整箱"
        const val TITLED_LINK = "「$TITLE」$LINK"
        const val TITLED_TOKEN = "「$TITLE」$TOKEN"
    }
}
