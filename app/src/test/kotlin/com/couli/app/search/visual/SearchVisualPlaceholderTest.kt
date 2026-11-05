package com.couli.app.search.visual

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ConfigStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.feature.search.SearchTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D10-617：从真实 AppSearch.searchConfig 进入，不能由测试侧替换占位或伪造基本模式字段。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
class SearchVisualPlaceholderTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }
    private var app: AppSearch? = null

    @After fun close() {
        rule.runOnIdle { app?.followBackStackIds(setOf("home")) }
        host.close()
    }

    @Test fun fullModeUsesContractInsteadOfDraft() = placeholder(PrivacyState.CONSENTED, "搜商品名，或粘贴商品链接")

    @Test fun basicModeUsesBasicPlaceholderInVisibleTextAndSpeech() = placeholder(PrivacyState.BASIC_MODE, "搜索商品")

    private fun placeholder(
        privacy: PrivacyState,
        expected: String,
    ) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, _ -> error("输入页不得拉配置") },
                clock = { SearchVisualFixtures.now },
                bundled = """{"features":{"flags":{},"search_status":{"taobao":"on","jd":"on","pdd":"on"}}}""",
            )
        val search = AppSearch(client(), store, AppRouting(context, gate), privacy = { privacy }, api = host.api)
        app = search
        host.show(search.searchConfig(RouteDestination("Search", "{}", readonly = false), "search", {}))
        rule.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.INPUT).assertContentDescriptionEquals(expected)
        assertEquals(0, host.api.calls.size)
    }

    private fun client(): ApiClient =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { error("测试不允许真实网络") },
            context = {
                NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
            },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )
}
