package com.couli.app.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugMockGate
import com.couli.app.RawFakeServerSwitch
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.home.ApiClientHomeContractPort
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.UpdateCheck
import com.couli.feature.home.HomeTimers
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** FU-X-HOME-PAGE-API 测试 v3，D10-618 T2：草稿商品卡不借用正式首页的真实端口（D7 / MX2）。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class HomePageApiPreviewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** previewPage 正常交出含商品池卡的草稿；所有契约调用只记录，不经过网络。 */
    private class RecordingPageApi : PageApi {
        val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            val body = if (call.operationId == "previewPage") DRAFT else """{"items":[],"next_cursor":null}"""
            return ApiResult.Success(Json.parseToJsonElement(body), null, null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    /** 委托给生产预览端口，记录已完成的取卡，避免没有真正加载草稿也让负向断言通过。 */
    private class RecordingPreviewPort(
        private val delegate: HomeContractPort,
    ) : HomeContractPort by delegate {
        val completed = mutableListOf<Pair<String, String>>()

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult {
            val result = delegate.listSectionItems(pageKey, sectionId, cursor, call)
            completed += pageKey to sectionId
            return result
        }
    }

    @OptIn(RawFakeServerSwitch::class)
    @Test
    fun t2DraftPreviewWithFakeSwitchOffNeverSendsListPageSectionItems() {
        val wasOn = DebugMockGate.switchOn(context)
        val ready = DebugMockGate.environmentReady
        var session: HomePreviewSession? = null
        try {
            DebugMockGate.environmentReady = { true }
            DebugMockGate.setSwitch(context, false)
            assertFalse("前提：假服务开关关闭", DebugMockGate.of(context)())
            val api = RecordingPageApi()
            val previewPort = RecordingPreviewPort(requireNotNull(variantPreviewPort(context)))
            val (home, routing) = home(api, previewPort)
            val preview =
                HomePreviewSession(
                    home = home,
                    api = api,
                    routing = routing,
                    pageKey = "home",
                    token = "preview-fixture",
                    entryId = "preview-entry",
                    envName = "test",
                    clock = { 0L },
                )
            session = preview
            preview.load()
            shadowOf(Looper.getMainLooper()).idle()
            val draft = preview.screen.value as? PreviewScreen.Draft
            assertTrue("草稿已加载，不能用未加载状态证明不发请求", draft != null)
            assertEquals(14, draft?.version)
            assertEquals("只调 previewPage，不发 listPageSectionItems", listOf("previewPage"), api.calls.map { it.operationId })
            assertEquals("草稿商品卡已由预览端口完成取数", listOf("home" to "draft-grid"), previewPort.completed)
        } finally {
            session?.leave()
            DebugMockGate.setSwitch(context, wasOn)
            DebugMockGate.environmentReady = ready
        }
    }

    private fun home(
        api: PageApi,
        previewPort: HomeContractPort,
    ): Pair<AppHome, AppRouting> {
        val gate =
            PrivacyGate(Store(), clock = { 0L }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { 0L }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(
                context,
                environment,
                clock = { 0L },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
                initialUpdate = UpdateCheck.OK,
            )
        val tokens = MemorySessionTokens()
        val home =
            AppHome(
                client = client(tokens),
                tokens = tokens,
                routing = routing,
                privacy = privacy,
                port = variantHomePort(context, ApiClientHomeContractPort(api)),
                previewPort = previewPort,
                lkg = null,
                prefs = null,
                images = null,
                envKey = { "test" },
                configData = { null },
                track = {},
                serverForced = {},
                cardTexts = ProductCardTexts { "" },
                appVersion = "1.0.0",
                clock = { 0L },
                timers = HomeTimers { _, _ -> {} },
                bundled = { null },
                api = api,
                io = Dispatchers.Main.immediate,
            )
        return home to routing
    }

    private fun client(tokens: MemorySessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { error("本测试的请求只能交给 PageApi 替身") },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = UnregisteredDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )

    private class Store : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private companion object {
        const val DRAFT =
            """{"page_key":"home","version":14,"sections":[""" +
                """{"id":"draft-grid","type":"product_grid","version":1,"props":{"columns":2,"title":"draft"},""" +
                """"data_source":{"kind":"product_pool","pool_id":"draft-pool","limit":20}}]}"""
    }
}
