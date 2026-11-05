package com.couli.app.search.visual

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.feature.search.SearchController
import com.couli.feature.search.SearchHistory
import com.couli.feature.search.SearchPageActions
import com.couli.feature.search.SearchPageConfig
import com.couli.feature.search.SearchScreen
import com.couli.feature.search.SearchTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 真实 SearchScreen / ViewModel / Controller / Mapper，只替换网络、配置和外部出口。 */
internal class SearchVisualHost(
    val rule: ComposeContentTestRule,
) : AutoCloseable {
    val api = SearchVisualApi()
    val port = SearchVisualPort()
    val spoken = mutableListOf<String>()
    val opened = mutableListOf<Triple<String, String, String?>>()
    val bought = mutableListOf<Pair<JsonObject, String>>()
    val similar = mutableListOf<JsonObject>()
    var backs = 0
    var pastes = 0
    var now = SearchVisualFixtures.now
    private var leave: () -> Unit = {}
    private lateinit var activity: ComponentActivity
    lateinit var controller: SearchController

    fun launch(
        scenario: String = "normal",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        prepare(scenario)
        val history = SearchHistory()
        if (scenario == "history") SearchVisualFixtures.history.reversed().forEach(history::add)
        val config =
            SearchPageConfig(
                api = api,
                config = port,
                texts = SearchDraftTexts,
                history = history,
                query =
                    if (scenario in
                        listOf("history", "idle", "disabled")
                    ) {
                        null
                    } else {
                        SearchVisualFixtures.query(
                            if (scenario ==
                                "category"
                            ) {
                                "category"
                            } else {
                                "normal"
                            },
                        )
                    },
                platform = if (scenario == "pdd") "pdd" else "taobao",
                clock = { now },
                bindLeave = { leave = it },
                bindController = { controller = it },
                actions =
                    SearchPageActions(
                        onBack = { backs += 1 },
                        onOpenProduct = { platform, key, ref -> opened += Triple(platform, key, ref) },
                        onBuyIntent = { card, spm -> bought += card to spm },
                        onSimilar = { similar += it },
                    ),
                pasteEntry = { CouliButton(text = "粘贴链接查返利", onClick = { pastes += 1 }) },
            )
        show(config, dark, fontScale)
        finishScenario(scenario)
        assertReady(scenario)
    }

    fun show(
        config: SearchPageConfig,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides null,
                LocalCouliAnnouncer provides CouliAnnouncer { spoken += it },
            ) {
                CouliTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        Spacer(Modifier.height(54.dp))
                        SearchScreen(config)
                    }
                }
            }
        }
        rule.waitForIdle()
        val view = (rule.onNodeWithTag(SearchTags.PAGE).fetchSemanticsNode().root as ViewRootForTest).view
        activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>().first()
    }

    private fun prepare(scenario: String) {
        if (scenario == "disabled") port.platforms = emptyList()
        if (scenario == "one_off") port.platforms = listOf("taobao", "pdd")
        if (scenario == "loading" || scenario == "paging" || scenario == "notice") api.hold = CompletableDeferred()
        api.holdPaging = scenario == "paging"
        api.answer = { call ->
            when {
                scenario == "error" -> SearchVisualApi.failure("unavailable")
                scenario == "limited" -> SearchVisualApi.failure("limited")
                scenario == "notice" && call.query.orEmpty().contains("platform=taobao") -> SearchVisualApi.failure("disabled")
                scenario == "paging_error" && call.query.orEmpty().contains("cursor=") -> SearchVisualApi.failure("unavailable")
                else -> SearchVisualApi.success(page(scenario))
            }
        }
    }

    private fun page(scenario: String): JsonObject =
        when (scenario) {
            "empty_fallback" -> {
                SearchVisualFixtures.page(emptyList(), SearchVisualFixtures.cards("fallback"))
            }

            "empty" -> {
                SearchVisualFixtures.page(emptyList())
            }

            "pdd" -> {
                SearchVisualFixtures.page(SearchVisualFixtures.cards("pdd"))
            }

            "paging", "paging_error" -> {
                SearchVisualFixtures.page(listOf(SearchVisualFixtures.card("tb_single")), more = true)
            }

            "stale", "off_shelf", "compare", "price_unavailable", "long_title", "long_price" -> {
                SearchVisualFixtures.page(listOf(SearchVisualFixtures.card(scenario)))
            }

            else -> {
                SearchVisualFixtures.page()
            }
        }

    private fun finishScenario(scenario: String) {
        if (scenario == "notice") rule.runOnIdle { api.hold?.complete(Unit) }
        if (scenario == "price" || scenario == "price_invalid") {
            openPrice(if (scenario == "price_invalid") "30" else "10", "20")
            if (scenario == "price_invalid") rule.onNodeWithTag(SearchTags.PRICE_CONFIRM).performClick()
        }
        if (scenario == "disclaimer") openInfo()
        if (scenario == "empty_fallback" || scenario == "empty") {
            openPrice("10", "20")
            rule.onNodeWithTag(SearchTags.PRICE_CONFIRM).performClick()
        }
        if (scenario == "paging" || scenario == "paging_error") {
            rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(2)
        }
        rule.waitForIdle()
    }

    private fun assertReady(scenario: String) {
        val expected =
            when (scenario) {
                "history", "idle" -> "idle"
                "disabled" -> "unavailable"
                "loading" -> "loading"
                "error", "limited" -> "error"
                "empty", "empty_fallback" -> "empty"
                else -> "content"
            }
        val state = controller.state.value
        assertEquals("截图 / 断言前确认真实执行器已到指定状态：$scenario", expected, state.phase)
        if (scenario == "paging") assertTrue(state.loadingMore)
        if (scenario == "paging_error") assertTrue(state.loadMoreError != null)
        if (scenario == "limited") assertTrue(state.blocked)
        if (scenario == "notice") assertTrue(state.notice != null)
        if (scenario == "stale") assertTrue(state.stale)
    }

    fun openPrice(
        min: String = "10",
        max: String = "20",
    ) {
        rule.onNodeWithTag(SearchTags.PRICE).performClick()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).performTextInput(min)
        rule.onNodeWithTag(SearchTags.PRICE_MAX).performTextInput(max)
    }

    fun openInfo() = rule.onAllNodesWithContentDescription("价格与返利说明")[0].performClick()

    fun back() {
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    override fun close() = rule.runOnIdle { leave() }
}
