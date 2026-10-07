package com.couli.feature.web

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.ExternalNavPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose 测试宿主（只进测试 APK）。 */
class ComposeHostActivity : ComponentActivity()

/**
 * 两个容器页面（真实 WebView + Compose）的界面行为：
 * - 第 21 条：平台页面被拦（含初始地址）时提示里提供「去搜索」；
 * - 第 23 条：ui.setNavBar 的参数在标题栏上真实生效，做不到的参数返回失败；
 * - 第 24 条：ExternalPage 的 title 作为主机名下的副标题、nav_style 进入容器；
 * - 第 25、42 条：页面元素全屏时全屏内容铺在标题栏下方，标题栏不隐藏（分不出视频与任意元素）；返回退出全屏。
 */
@RunWith(AndroidJUnit4::class)
class WebScreensComposeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeHostActivity>()

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Suppress("LongParameterList")
    private fun externalScreen(
        url: String,
        subtitle: String? = null,
        navStyle: String? = null,
        onSearch: () -> Unit = {},
    ) = compose.setContent {
        ExternalWebScreen(
            url = url,
            subtitle = subtitle,
            navStyle = navStyle,
            policy = ExternalNavPolicy(PATTERNS),
            linkPatterns = { PATTERNS },
            flags = { ExternalNavFlags(consented = true, productIntercept = true) },
            track = {},
            appVersion = "1.0.0",
            onProductIntent = { _, _, _ -> },
            onOpenBrowser = {},
            onSearch = onSearch,
            onClose = {},
        )
    }

    @Test
    fun blockedPlatformPageAtTheInitialUrlOffersGoToSearch() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        val searches = AtomicInteger()
        externalScreen("https://shop.taobao.example.test/cart", onSearch = { searches.incrementAndGet() })
        compose.waitUntil(WAIT_MS) { count(EXTERNAL_SEARCH_TAG) > 0 }
        compose.onNodeWithTag(EXTERNAL_SEARCH_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, searches.get())
    }

    @Test
    fun setNavBarParametersReallyChangeTheBarAndUnsupportedOnesFail() {
        var ui: BridgeUi? = null
        compose.setContent {
            TrustedWebScreen(
                url = "$TRUSTED/page",
                config = TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"),
                hostFactory = { captured, _ ->
                    ui = captured
                    null
                },
                onOpenExternal = {},
                onClose = {},
            )
        }
        compose.waitUntil(WAIT_MS) { ui != null }
        assertEquals(1, count(CLOSE_TAG))
        var applied = false
        compose.runOnUiThread {
            applied =
                checkNotNull(ui).setNavBar(
                    params(
                        """{"title":"Bridge title","hide_close":true,"bg_gradient":["#112233","#445566"],"text_color":"#FFFFFF",""" +
                            """"status_bar_style":"light","immersive":true,"hide_bottom_safe_area":true,"bounce":false}""",
                    ),
                )
        }
        assertTrue(applied)
        compose.waitForIdle()
        compose.onNodeWithText("Bridge title").assertExists()
        assertEquals("hide_close took effect", 0, count(CLOSE_TAG))

        var unsupported = true
        compose.runOnUiThread { unsupported = checkNotNull(ui).setNavBar(params("""{"bg_image":"https://img.example.test/a.png"}""")) }
        assertFalse("bg_image is not implemented: must not report success", unsupported)

        compose.runOnUiThread { checkNotNull(ui).setNavBar(params("""{"visible":false}""")) }
        compose.waitForIdle()
        assertEquals("visible=false hides the bar", 0, count(TITLE_BAR_TAG))
    }

    @Test
    fun externalPageShowsTheRouteTitleAsSubtitleWithTheDarkStyle() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        externalScreen("about:blank", subtitle = "Partner page", navStyle = "dark")
        compose.waitForIdle()
        compose.onNodeWithText("Partner page").assertExists()
        assertEquals(1, count(TITLE_BAR_TAG))
        assertEquals("close stays visible", 1, count(CLOSE_TAG))
    }

    @Test
    fun elementFullscreenNeverHidesTheTitleBarAndBackExitsIt() {
        assumeTrue(ExternalWebViewController.cookieIsolationSupported())
        // about:blank 不加载任何东西（非 https 取消），再由测试把全屏测试页放进这个容器的 WebView
        externalScreen("about:blank")
        compose.waitForIdle()
        val webView = compose.runOnUiThreadValue { findWebView(compose.activity.window.decorView) }
        compose.runOnUiThread { webView.loadDataWithBaseURL("$THIRD_PARTY/video", FULLSCREEN_PAGE, "text/html", "utf-8", null) }
        Thread.sleep(LOAD_MS)
        // 真实触摸（系统派发的 MotionEvent）点到页面左上角的元素，onclick 里 requestFullscreen
        compose.runOnUiThread {
            val now = android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now, action, TAP_PX, TAP_PX, 0)
                webView.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        compose.waitUntil(WAIT_MS) { count(FULLSCREEN_TAG) > 0 }
        // 共性第 42、52 条：普通元素（这里是一个包着 video 的 div）请求全屏也会走 onShowCustomView，原生分不出全屏的是不是视频元素
        // 本身（视频尺寸大于 0 也说明不了）→ Android 对任何全屏都不隐藏标题栏（主机名、返回、关闭）
        assertEquals("title bar stays in fullscreen", 1, count(TITLE_BAR_TAG))
        assertEquals("close stays reachable", 1, count(CLOSE_TAG))

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(WAIT_MS) { count(FULLSCREEN_TAG) == 0 }
        assertEquals("title bar restored", 1, count(TITLE_BAR_TAG))
    }

    private fun <T> androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.runOnUiThreadValue(block: () -> T): T {
        var value: T? = null
        runOnUiThread { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun findWebView(view: View): WebView =
        checkNotNull(
            generateSequence(listOf(view)) { level ->
                level.flatMap {
                    (it as? ViewGroup)
                        ?.let { g ->
                            List(g.childCount, g::getChildAt)
                        }.orEmpty()
                }
            }.takeWhile { it.isNotEmpty() }
                .flatten()
                .filterIsInstance<WebView>()
                .firstOrNull(),
        )

    private fun params(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val WAIT_MS = 10_000L
        const val LOAD_MS = 1_500L
        const val TAP_PX = 10f
        const val TRUSTED = "https://h5.example.test"
        const val THIRD_PARTY = "https://page.example.test"

        val FULLSCREEN_PAGE: String =
            """
            <!doctype html><html><body style="margin:0">
            <div id="v" style="width:300px;height:300px;background:#333"
                 onclick="this.requestFullscreen ? this.requestFullscreen() : this.webkitRequestFullscreen()">
              <video width="300" height="150" muted playsinline style="pointer-events:none;background:#000"></video>
            </div>
            </body></html>
            """.trimIndent()

        val PATTERNS: JsonObject =
            Json
                .parseToJsonElement(
                    """{"version":"instrumented","rules":[""" +
                        """{"platform":"taobao","category":"union_host","hosts":["taobao.example.test"],"path_patterns":[]}]}""",
                ).jsonObject
    }
}
