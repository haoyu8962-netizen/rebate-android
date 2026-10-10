package com.couli.feature.web

import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-12 Android 第 1 轮评审 R1-10（AC-S1-137）：设置页「清除缓存」经 [WebViewCaches] 清掉 WebView 的资源缓存（内存 + 磁盘）。
 * 本机回环 HTTPS 服务给一张不缓存的页面与一个可缓存（max-age=3600）的脚本：
 * - 同一个 WebView 连续加载两次页面，脚本只请求一次（命中缓存）；
 * - 调 [WebViewCaches.clear] 之后（WebView 仍存活）再加载，脚本重新请求。
 */
@RunWith(AndroidJUnit4::class)
class WebViewCachesTest {
    private lateinit var scenario: ActivityScenario<WebTestHostActivity>
    private lateinit var server: LoopbackHttpsServer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(WebTestHostActivity::class.java)
        server =
            LoopbackHttpsServer(instrumentation.context) { request ->
                when (request.path.substringBefore('?')) {
                    SCRIPT -> {
                        LoopbackHttpsServer.Reply(
                            200,
                            html = "window.cachedScriptRuns = (window.cachedScriptRuns || 0) + 1;",
                            cacheControl = "public, max-age=3600",
                            contentType = "text/javascript; charset=utf-8",
                        )
                    }

                    else -> {
                        LoopbackHttpsServer.Reply(200, html = """<html><body><script src="$SCRIPT"></script>ok</body></html>""")
                    }
                }
            }
    }

    @After
    fun close() {
        if (::server.isInitialized) server.close()
        if (::scenario.isInitialized) scenario.close()
    }

    private fun scriptRequests(): Int = synchronized(server.requests) { server.requests.count { it.path.startsWith(SCRIPT) } }

    private fun load(webView: WebView) {
        val finished = CountDownLatch(1)
        scenario.onActivity {
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        finished.countDown()
                    }
                }
            webView.loadUrl("https://${LoopbackHttpsServer.LOOPBACK}:${server.port}/page.html?t=${SystemClock.uptimeMillis()}")
        }
        assertTrue("page load timed out", finished.await(WAIT_S, TimeUnit.SECONDS))
    }

    @Test
    fun clearDropsTheWebViewResourceCacheWhileTheWebViewIsAlive() {
        lateinit var webView: WebView
        scenario.onActivity { activity ->
            webView = WebView(activity)
            webView.settings.javaScriptEnabled = true
            activity.root.addView(
                webView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        // 先清一次，免得别的用例留下的缓存影响计数
        scenario.onActivity { assertTrue(WebViewCaches.clear(it)) }
        load(webView)
        assertEquals("第一次加载请求脚本", 1, scriptRequests())
        load(webView)
        assertEquals("可缓存的脚本命中缓存，不再请求", 1, scriptRequests())
        var cleared = false
        scenario.onActivity { cleared = WebViewCaches.clear(it) }
        assertTrue("清除成功", cleared)
        load(webView)
        assertEquals("清除之后脚本重新请求", 2, scriptRequests())
        scenario.onActivity {
            it.root.removeView(webView)
            webView.destroy()
        }
    }

    private companion object {
        const val SCRIPT = "/cached.js"
        const val WAIT_S = 15L
    }
}
