package com.couli.core.network

import com.couli.core.clientcore.BundledConfig
import com.couli.core.clientcore.ConfigSourceRaw
import com.couli.core.clientcore.ConfigStore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ConfigStore 经网络核心拉 /v1/config（ApiConfigFetcher）对本地假服务的集成测试（APP-03b 任务书 §5 第 2 条「配置拉取失败时用 LKG」，
 * 03 §4.3）：失败继续用 LKG；没有 LKG 用包内配置；成功写 LKG；回前台带 If-None-Match，304 不变；基本模式也能拉。
 */
class ConfigStoreIntegrationTest {
    private lateinit var server: MockWebServer
    private var lkgFile: String? = null
    private var context = FULL

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private val api by lazy {
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { context },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )
    }

    /** 同步执行的拉取器：测试里每次 onColdStart / onForeground 返回时拉取已结束。 */
    private fun store(): ConfigStore {
        val fetcher = ApiConfigFetcher({ api }, executor = { it.run() })
        return ConfigStore(
            readFile = { lkgFile },
            writeFile = { lkgFile = it },
            fetcher = fetcher::fetch,
            clock = { 0L },
            bundled = BundledConfig.dataJson,
        )
    }

    private fun configResponse(
        dict: String,
        etag: String? = null,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .apply { etag?.let { setHeader("ETag", it) } }
        .setBody(
            """{"code":0,"msg":"","data":{"dict_version":"$dict","features":{"search_status":{"android":"off"}},""" +
                """"link_patterns":{"version":"lp-$dict","rules":[]}},"trace_id":"trace-0123456789"}""",
        )

    private fun dictVersion(store: ConfigStore): String =
        Json
            .parseToJsonElement(store.snapshotJson)
            .jsonObject
            .getValue("dict_version")
            .jsonPrimitive.content

    @Test
    fun fetchFailureKeepsUsingLkg() {
        server.enqueue(configResponse("v1", etag = "\"e1\""))
        val first = store()
        first.onColdStart()
        assertEquals(ConfigSourceRaw.CURRENT, first.sourceRaw)
        assertTrue("成功响应写进 LKG", lkgFile != null)

        // 下次冷启动：拉取失败（网关错误页）→ 继续用 LKG
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        val restarted = store()
        restarted.onColdStart()

        assertEquals(ConfigSourceRaw.LKG, restarted.sourceRaw)
        assertEquals("v1", dictVersion(restarted))
        assertEquals("off", restarted.searchStatus("android"))
        assertTrue(restarted.linkPatterns()!!.contains("lp-v1"))
    }

    @Test
    fun noLkgAndFailureUsesBundledDefault() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x"))
        val store = store()
        store.onColdStart()
        assertEquals(ConfigSourceRaw.BUNDLED, store.sourceRaw)
        assertNull(lkgFile)
    }

    @Test
    fun foregroundSendsIfNoneMatchAnd304KeepsTheConfig() {
        server.enqueue(configResponse("v1", etag = "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"e1\""))
        val store = store()
        store.onColdStart()
        val saved = lkgFile

        store.onForeground()

        val cold = server.takeRequest(1, TimeUnit.SECONDS)!!
        val foreground = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull("冷启动不带 If-None-Match", cold.getHeader(HeaderNames.IF_NONE_MATCH))
        assertEquals("\"e1\"", foreground.getHeader(HeaderNames.IF_NONE_MATCH))
        assertEquals(ConfigSourceRaw.CURRENT, store.sourceRaw)
        assertEquals("v1", dictVersion(store))
        assertEquals("304 不改 LKG", saved, lkgFile)
    }

    @Test
    fun basicModeCanFetchConfigWithoutCredentials() {
        context = FULL.copy(privacy = PrivacyRaw.BASIC)
        server.enqueue(configResponse("basic"))
        val store = store()
        store.onColdStart()
        assertEquals("basic", dictVersion(store))
        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/config", sent.path)
        assertNull(sent.getHeader(HeaderNames.AUTHORIZATION))
        assertNull(sent.getHeader(HeaderNames.DEVICE_ID))
    }

    @Test
    fun notModifiedWithoutIfNoneMatchIsAFailure() {
        server.enqueue(MockResponse().setResponseCode(304))
        val reply = ApiConfigFetcher({ api }, executor = { it.run() }).fetchNow(null)
        assertEquals("failure", reply.kindRaw)
    }

    private companion object {
        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}
