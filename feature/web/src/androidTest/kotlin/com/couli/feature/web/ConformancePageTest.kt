package com.couli.feature.web

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 桥一致性 UI 测试（03 §5.5；Android 第 2 轮评审 R2-05、共性第 44 条）：真实系统 WebView + 可信容器（TrustedWebViewController、
 * BridgeTransport、GestureClock）+ 真实 BridgeHost / BridgeProtocol，加载 rebate-platform 9cd1e6f 构建出的一致性入口
 * （`apps/h5/dist/web/conformance/`，原样拷在 androidTest assets 的 `conformance/` 下，测试里经 https 可信源在本机给出，不连网络），
 * 读取 `window.__RESULT__` 逐项断言，任何一项不符都让测试失败：
 * 1. 缺省加载（已登录、能力成功）：自动用例跑完后，用系统派发的真实触摸逐个点按钮用例；任务外的方法（[NOT_IMPLEMENTED]）的 normal
 *    断言 90001，其余每一项 pass 都必须为 true；harness 用例此时应为待定（下两步专门跑）；
 * 2. `?cases=` 点名未登录类 harness 用例，宿主以游客身份：每一项 pass 为 true（协议先判登录，90401）；
 * 3. `?cases=` 点名超时类 harness 用例，能力一直不回：本端实现的方法 pass 为 true（90003），任务外的方法断言 90001。
 * 能力实现（ApiClient、Router、PermissionRequester 等）在这里用替身（成功或一直不回）；它们各自有单测，真实 App 接线的一致性页结果见
 * NATIVE/APP-04/conformance-android/README.md。受环境限制、本测试无法覆盖的用例登记在 [ENVIRONMENT_LIMITED]（目前为空）。
 */
@RunWith(AndroidJUnit4::class)
class ConformancePageTest {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
    }

    @After
    fun close() {
        scope.cancel()
        if (::scenario.isInitialized) scenario.close()
    }

    private fun <T> onMain(block: (WebTestHostActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun evaluate(
        webView: WebView,
        script: String,
    ): String {
        val latch = CountDownLatch(1)
        var result = "null"
        onMain {
            webView.evaluateJavascript(script) { value ->
                result = value
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", latch.await(EVAL_S, TimeUnit.SECONDS))
        return result
    }

    private fun waitUntil(
        message: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out: $message")
    }

    /** 测试代码读取页面状态（容器自己从不执行脚本）。 */
    private fun result(webView: WebView): JsonObject? {
        val raw = evaluate(webView, "JSON.stringify(window.__RESULT__ || null)")
        val text = (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content ?: return null
        return Json.parseToJsonElement(text) as? JsonObject
    }

    private fun rows(result: JsonObject): Map<String, JsonObject> =
        result.getValue("cases").jsonArray.associate {
            val row = it.jsonObject
            row.getValue("id").jsonPrimitive.content to row
        }

    /** 打开一致性页（[query] 为 `?cases=` 等），返回 WebView；宿主的调用方与能力由参数决定。 */
    private fun open(
        query: String,
        loggedIn: Boolean,
        services: BridgeServices,
    ): WebView {
        val webView = onMain { activity -> WebView(activity).also { activity.root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) } }
        val account = if (loggedIn) "conformance-account" else null
        val host =
            BridgeHost(
                protocol = { BridgeProtocol(setOf(TRUSTED), SAMPLE_LINK_PATTERNS, setOf(SHARE_DOMAIN)) },
                caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = account, update = BridgeUpdateState.SUPPORTED) },
                services = services,
                ui = NoUi,
                awaitUpdate = { BridgeUpdateState.SUPPORTED },
                scope = scope,
            )
        val controller =
            onMain { TrustedWebViewController(webView, TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"), host, NoCallbacks) }
        assumeTrue("system WebView supports the transport", controller.bridgeInstalled)
        onMain {
            webView.webViewClient = AssetServer(webView.webViewClient)
            assertTrue(controller.load("$TRUSTED/conformance/$query"))
        }
        waitUntil("conformance auto runs done", RUN_TIMEOUT_MS) { result(webView)?.get("status")?.jsonPrimitive?.content == "done" }
        return webView
    }

    /** 用系统派发的触摸点一个按钮用例（页面里按 data-case-id 找到按钮，滚到中间，取中心点）。 */
    private fun tap(
        webView: WebView,
        id: String,
    ) {
        val raw =
            evaluate(
                webView,
                "(function(){var b=document.querySelector('[data-case-id=\"$id\"]');if(!b)return null;" +
                    "b.scrollIntoView({block:'center'});var r=b.getBoundingClientRect();" +
                    "return [r.left+r.width/2, r.top+r.height/2, window.devicePixelRatio];})()",
            )
        val point = Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content.toFloat() }
        val (x, y) = point[0] * point[2] to point[1] * point[2]
        onMain {
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + TAP_MS).forEach { (action, at) ->
                val event = MotionEvent.obtain(down, at, action, x, y, 0)
                webView.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    /** 把全部按钮用例逐个点完（每点一个等它记下结果）。 */
    private fun tapAll(
        webView: WebView,
        ids: List<String>,
        timeoutMs: Long,
    ) {
        ids.forEach { id ->
            tap(webView, id)
            waitUntil("tapped $id recorded", timeoutMs) { rows(checkNotNull(result(webView)))[id]?.get("pass") !is JsonNull }
        }
    }

    private fun buttons(webView: WebView): List<String> {
        val raw =
            evaluate(
                webView,
                "JSON.stringify(Array.from(document.querySelectorAll('[data-case-id]')).map(function(b){return b.dataset.caseId;}))",
            )
        return Json.parseToJsonElement(Json.parseToJsonElement(raw).jsonPrimitive.content).jsonArray.map { it.jsonPrimitive.content }
    }

    private fun JsonObject.passValue(): Boolean? = (get("pass") as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.code(): Int? = (get("outcome") as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull

    private fun method(row: JsonObject): String = row.getValue("method").jsonPrimitive.content

    private fun trigger(row: JsonObject): String = row.getValue("trigger").jsonPrimitive.content

    /** 逐项断言；把全部不符的用例一起报出来。 */
    private fun assertRows(
        rows: Map<String, JsonObject>,
        expect: (String, JsonObject) -> String?,
    ) {
        val problems = rows.mapNotNull { (id, row) -> expect(id, row)?.let { "$id: $it (row=$row)" } }
        assertTrue("conformance rows not as expected:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun expectNotSupported(row: JsonObject): String? =
        if (row.code() ==
            NOT_SUPPORTED
        ) {
            null
        } else {
            "task-external method must answer 90001"
        }

    /**
     * 按用例自己的预期判（共性第 55 条）：pass 必须为 true，且结果与 expect 逐项一致（expect.code → outcome.code；expect.ok →
     * outcome.ok）。不只看页面算出的 pass。
     */
    @Suppress("ReturnCount") // pass、expect、outcome 逐项判
    private fun expectAsDeclared(row: JsonObject): String? {
        if (row.passValue() != true) return "must pass"
        val expect = row["expect"] as? JsonObject ?: return "row has no expect"
        val outcome = row["outcome"] as? JsonObject ?: return "row has no outcome"
        val mismatched = expect.keys.filter { key -> outcome[key]?.toString() != expect[key]?.toString() }
        return if (mismatched.isEmpty()) null else "outcome differs from expect on $mismatched"
    }

    /** 点名跑的一组用例：结果集必须正好是点名的那些（不为空、不缺、不多）。 */
    private fun assertNamed(
        named: Collection<String>,
        rows: Map<String, JsonObject>,
    ) {
        assertTrue("named cases must not be empty", named.isNotEmpty())
        assertEquals("the page ran exactly the named cases", named.toSet(), rows.keys)
    }

    @Test
    fun everyConformanceRowMatchesTheContract() {
        // 1. 缺省加载：自动用例 + 点完所有按钮用例
        val page = open(query = "", loggedIn = true, services = Capabilities(hang = false))
        val first = checkNotNull(result(page))
        assertEquals(true, first["bridge_present"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("[]", first.getValue("unknown_cases").toString())
        tapAll(page, buttons(page), TAP_TIMEOUT_MS)
        val all = rows(checkNotNull(result(page)))
        assertTrue("the page has rows", all.size > MIN_ROWS)
        // 必测用例都在（共性第 55 条）：本端实现的每个方法都有 normal 用例，任务外的方法都有用例；参数、手势、未知方法类用例都在
        val methods = all.values.map(::method).toSet()
        val missing =
            (BridgeHost.IMPLEMENTED + NOT_IMPLEMENTED).filterNot { it in methods } +
                BridgeHost.IMPLEMENTED.filterNot { "$it/$NORMAL" in all.keys }.map { "$it/$NORMAL" } +
                REQUIRED_CATEGORIES.filterNot { category -> all.values.any { it.getValue("category").jsonPrimitive.content == category } }
        assertTrue("required conformance cases missing: $missing", missing.isEmpty())
        assertRows(all) { id, row ->
            when {
                id in ENVIRONMENT_LIMITED -> null

                trigger(row) == HARNESS -> if (row.passValue() == null) null else "harness row must wait to be named"

                // 任务外的方法只有 normal 用例按规定错误码判；它们的 bad_params、no_gesture、negative 等照常按用例预期判，失败即失败
                method(row) in NOT_IMPLEMENTED && row.getValue("category").jsonPrimitive.content == NORMAL -> expectNotSupported(row)

                else -> expectAsDeclared(row)
            }
        }
        val harness = all.filterValues { trigger(it) == HARNESS }.keys - ENVIRONMENT_LIMITED.keys
        val loggedOut = harness.filter { it.endsWith(LOGGED_OUT_SUFFIX) }
        val timeouts =
            harness.filter {
                all
                    .getValue(it)
                    .getValue("category")
                    .jsonPrimitive.content == TIMEOUT
            }
        assertEquals("every harness row is a logged-out or timeout probe", harness, (loggedOut + timeouts).toSet())
        onMain { it.root.removeAllViews() }

        // 2. 未登录类 harness 用例：游客身份，协议先判登录
        val guest = open(query = "?cases=" + loggedOut.joinToString(","), loggedIn = false, services = Capabilities(hang = false))
        tapAll(guest, buttons(guest), TAP_TIMEOUT_MS)
        val guestRows = rows(checkNotNull(result(guest)))
        assertNamed(loggedOut, guestRows)
        assertRows(guestRows) { _, row -> expectAsDeclared(row)?.let { "$it (90401)" } }
        onMain { it.root.removeAllViews() }

        // 3. 超时类 harness 用例：能力一直不回，原生按契约 timeout_ms 回 90003
        val slow = open(query = "?cases=" + timeouts.joinToString(","), loggedIn = true, services = Capabilities(hang = true))
        tapAll(slow, buttons(slow), TIMEOUT_TAP_MS)
        val slowRows = rows(checkNotNull(result(slow)))
        assertNamed(timeouts, slowRows)
        assertRows(slowRows) { id, row ->
            when {
                id in PROTOCOL_FIRST -> if (row.code() == PROTOCOL_FIRST[id]) null else "must answer ${PROTOCOL_FIRST[id]} (protocol first)"
                method(row) in NOT_IMPLEMENTED -> expectNotSupported(row)
                else -> expectAsDeclared(row)?.let { "$it (90003)" }
            }
        }
    }

    /** 可信源上的本机内容：一致性页与它的静态资源来自 androidTest assets（容器先判定，放行的才由这里给内容）。 */
    private inner class AssetServer(
        private val inner: WebViewClient,
    ) : WebViewClient() {
        @Suppress("ReturnCount") // 容器拦下、没有该资源、给内容
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            inner.shouldInterceptRequest(view, request)?.let { return it }
            val path = request.url.path.orEmpty()
            val asset =
                when {
                    path == "/conformance/" || path == "/conformance/index.html" -> "conformance/index.html"
                    path.startsWith("/assets/") -> "conformance" + path
                    else -> null
                }
            val bytes =
                asset?.let { name ->
                    runCatching {
                        instrumentation.context.assets
                            .open(name)
                            .use { it.readBytes() }
                    }.getOrNull()
                }
            if (bytes == null) {
                return WebResourceResponse("text/plain", "utf-8", NOT_FOUND, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            val type =
                when {
                    path.endsWith(".js") -> "text/javascript"
                    path.endsWith(".css") -> "text/css"
                    else -> "text/html"
                }
            return WebResourceResponse(type, "utf-8", ByteArrayInputStream(bytes))
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = inner.shouldOverrideUrlLoading(view, request)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) = inner.onPageStarted(view, url, favicon)
    }

    /** 能力替身：[hang] 为 false 时全部成功；为 true 时异步能力一直不回（由宿主按契约 timeout_ms 结束）。 */
    private class Capabilities(
        private val hang: Boolean,
    ) : BridgeServices {
        private suspend fun answer(outcome: BridgeOutcome = BridgeOutcome.Ok()): BridgeOutcome = if (hang) awaitCancellation() else outcome

        override fun env(): JsonObject =
            buildJsonObject {
                put("platform", "android")
                put("app_version", "1.0.0")
                put("build", "1")
                put("os_version", "16")
                put("bridge_version", 1)
                put("safe_area", buildJsonObject { listOf("top", "bottom", "left", "right").forEach { put(it, 0) } })
                put("channel", "official")
            }

        override fun config(keys: List<String>) = BridgeOutcome.Ok(buildJsonObject { put("values", JsonObject(emptyMap())) })

        override suspend fun user(validity: BridgeCallValidity) = answer()

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ) = answer()

        override suspend fun login(update: String) = answer()

        override suspend fun signedRequest(
            method: String,
            path: String,
            body: JsonElement?,
            validity: BridgeCallValidity,
        ) = answer()

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ) = answer()

        override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

        override suspend fun clipboardRead() = answer()

        override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

        override suspend fun pushStatus() = answer()

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ) = answer()

        override fun track(
            name: String,
            props: JsonObject,
        ) = BridgeOutcome.Ok()

        override fun reportRejected(
            method: String,
            code: Int,
        ) = Unit
    }

    private object NoUi : BridgeUi {
        override fun toast(
            text: String,
            long: Boolean,
        ) = Unit

        override fun showLoading(text: String?) = Unit

        override fun hideLoading() = Unit

        override fun setNavBar(params: JsonObject) = true

        override fun close() = Unit
    }

    private object NoCallbacks : TrustedWebCallbacks {
        override fun onOpenExternal(url: String) = Unit

        override fun onLoadFailed() = Unit
    }

    private companion object {
        const val TRUSTED = "https://h5.example.test"
        const val SHARE_DOMAIN = "s.example.test"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val EVAL_S = 10L
        const val POLL_MS = 100L
        const val TAP_MS = 40L
        const val RUN_TIMEOUT_MS = 120_000L
        const val TAP_TIMEOUT_MS = 10_000L
        const val TIMEOUT_TAP_MS = 30_000L
        const val NOT_FOUND = 404
        const val NOT_SUPPORTED = 90001
        const val MIN_ROWS = 50
        const val HARNESS = "harness"
        const val NORMAL = "normal"
        const val TIMEOUT = "timeout"
        const val LOGGED_OUT_SUFFIX = "/negative/logged_out"

        /**
         * 任务外方法的超时用例里，协议层先于「本端不提供」（90001）给出结论的：ext.openApp 的超时用例用的是页面能取到的第一个目标，
         * 契约 apps.json 里的目标全是 trade_only（所以页面也没有 ext.openApp 的 normal 用例），按 03 §5.3 与契约 ext.openApp 的 note，
         * trade_only 目标不论实现与否一律 90403、什么都不打开。逐条写明，其余任务外方法的超时用例照常断言 90001。
         */
        val PROTOCOL_FIRST: Map<String, Int> = mapOf("ext.openApp/timeout" to 90403)

        /** 必须出现的用例类别（参数、手势、未知方法、负向）。 */
        val REQUIRED_CATEGORIES = listOf("bad_params", "no_gesture", "unsupported", "negative")

        /** 本任务不实现、规定返回 90001 的方法（BridgeHost.NOT_IMPLEMENTED_TASKS）。 */
        val NOT_IMPLEMENTED: Set<String> = BridgeHost.NOT_IMPLEMENTED_TASKS.keys

        /** 受环境限制、本测试无法覆盖的用例 → 原因（作为未完成登记）。目前整张表都在这里跑。 */
        val ENVIRONMENT_LIMITED: Map<String, String> = emptyMap()

        /** 与 Debug 一致性入口相同：契约 openapi GET /v1/config 的示例规则（页面不编造真实平台域名）。 */
        val SAMPLE_LINK_PATTERNS: JsonObject =
            Json
                .parseToJsonElement(
                    """{"version":"2026100401","rules":[""" +
                        """{"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]}]}""",
                ).jsonObject
    }
}
