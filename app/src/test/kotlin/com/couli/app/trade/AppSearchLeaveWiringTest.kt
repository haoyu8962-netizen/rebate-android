package com.couli.app.trade

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.search.ScrollAnchor
import com.couli.feature.search.SearchViewModel
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A-R2-04（APP-06a 共性第 39 条）的 App 接线：搜索页的出栈出口经 App 真实的回退栈同步出口（AppSearch.followBackStack 的主体
 * followBackStackIds，AppShell 在回退栈变化的同一条主线程消息里调）触发，不直接调 ViewModel 内部的 leave：
 * - 条目还在栈里：页面实例有效、恢复任务保留；
 * - 条目离开栈：恢复任务作废（待恢复锚点清除）、页面实例失效；之后迟到的位置记录、ON_START 解冻、输入等持久化入口都不写保存状态。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppSearchLeaveWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val store =
        ConfigStore(
            readFile = { null },
            writeFile = {},
            fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, """{"features":{"flags":{},"platform_status":{}}}""", null)) },
            clock = { 0L },
            bundled = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}""",
        )

    private val client =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { "https://api.example.test/" },
            context = {
                NetworkContext(
                    PrivacyRaw.FULL,
                    environmentSettled = true,
                    credentialsReadable = true,
                    update = UpdateRaw.SUPPORTED,
                )
            },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )

    private fun search(): AppSearch {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        val routing = AppRouting(context, gate)
        val api = ApiClientPageApi(client)
        val trade =
            AppTrade(
                api = api,
                store = store,
                routing = routing,
                tracker = Provider { error("not used") },
                baichuan = UnavailableBaichuan(),
                webCode = UnavailableWebCodeAuthorizer,
                ports = AndroidJumpPorts(context),
            )
        return AppSearch(client, store, routing, privacy = { PrivacyState.CONSENTED }, api = api, trade = trade)
    }

    /** 进程回收后恢复出来的保存状态：条件 + 第二页的滚动锚点（恢复任务等着回到那里）。 */
    private fun restoredState(): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                "search.keyword" to "milk",
                "search.input" to "milk",
                "search.round" to 1L,
                "search.scrolled_round" to 1L,
                "search.scroll_index" to SAVED_INDEX,
                "search.scroll_offset" to 12,
                "search.scroll_last_visible" to SAVED_INDEX + 2,
            ),
        )

    @Test
    fun theBackStackExitInvalidatesTheRestoreAndRejectsLateWrites() {
        val search = search()
        val config =
            search.searchConfig(RouteDestination("Search", """{"q":"milk"}""", readonly = false), entryId = ENTRY, onBack = {})
        val saved = restoredState()
        val model = SearchViewModel(config, saved)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("恢复任务在等", model.pendingScroll)

        // 回退栈变了但本页条目还在：不作废
        search.followBackStackIds(setOf(ENTRY, OTHER))
        assertTrue(model.alive)
        assertNotNull(model.pendingScroll)

        // 本页条目离开回退栈（同步出口）：组合、生命周期都还在时就已作废
        search.followBackStackIds(setOf(OTHER))
        assertFalse("页面实例已作废", model.alive)
        assertNull("待恢复锚点已清除", model.pendingScroll)

        // 退出动画期间迟到的回调：解冻、位置记录、输入都不写已出栈页面的保存状态
        model.onPageStarted()
        model.onScrolled(ScrollAnchor(3, 0, 6))
        model.onPageStopped(ScrollAnchor(4, 0, 7))
        model.onInput("tea")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("位置记录冻结", SAVED_INDEX, saved.get<Int>("search.scroll_index"))
        assertEquals("输入不写", "milk", saved.get<String>("search.input"))
        assertTrue(model.recordingFrozen)
    }

    private companion object {
        const val ENTRY = "entry-search"
        const val OTHER = "entry-home"
        const val SAVED_INDEX = 25
    }
}
