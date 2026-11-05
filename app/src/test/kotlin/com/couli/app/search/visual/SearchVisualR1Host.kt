package com.couli.app.search.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.feature.search.SearchHistory
import com.couli.feature.search.SearchPageConfig
import com.couli.feature.search.SearchScreen
import com.couli.feature.search.SearchViewModel

/** r1 专用宿主：真实保存状态 / ViewModel / 页面；字号变化保留同一 ViewModel，网络仍只用内存假接口。 */
internal class SearchVisualR1Host(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    val api = SearchVisualApi()
    val fontScale = mutableFloatStateOf(1f)
    val saved = SavedStateHandle()
    private val store = ViewModelStore()
    private val owner =
        object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    private var leave: () -> Unit = {}
    lateinit var model: SearchViewModel
    var secondary = 0
    var muted = 0
    var subtleBorder = 0

    fun launch(
        platform: String = "taobao",
        scale: Float = 2f,
        limited: Boolean = false,
    ) {
        fontScale.floatValue = scale
        if (limited) api.answer = { SearchVisualApi.failure("limited") }
        val config =
            SearchPageConfig(
                api = api,
                config = SearchVisualPort(),
                texts = SearchDraftTexts,
                history = SearchHistory(),
                query = SearchVisualFixtures.query("normal"),
                platform = platform,
                clock = { SearchVisualFixtures.now },
                bindLeave = { leave = it },
            )
        rule.runOnUiThread {
            model =
                ViewModelProvider.create(store, viewModelFactory { initializer { SearchViewModel(config, saved) } })[SearchViewModel::class]
        }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalDensity provides Density(density.density, fontScale.floatValue),
                LocalProductImageLoader provides null,
                LocalCouliAnnouncer provides CouliAnnouncer {},
            ) {
                CouliTheme(darkTheme = false) {
                    secondary = CouliColors.textSecondary.toArgb()
                    muted = CouliColors.backgroundMuted.toArgb()
                    subtleBorder = CouliColors.borderSubtle.toArgb()
                    Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) { SearchScreen(config) }
                }
            }
        }
        rule.waitForIdle()
    }

    fun restore(sort: String = "relevance") {
        saved["search.keyword"] = SearchVisualFixtures.query("normal")
        saved["search.input"] = SearchVisualFixtures.query("normal")
        saved["search.platform"] = "taobao"
        saved["search.sort"] = sort
    }

    override fun close() {
        rule.runOnUiThread {
            leave()
            store.clear()
        }
    }
}
