package com.couli.app.web

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.web.TrustedWebConfig
import com.couli.feature.web.TrustedWebScreen
import com.couli.feature.web.TrustedWebTarget
import com.couli.generated.DefaultTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0710 F-AND-26（APP-08 Android RC-02）：可信 H5 页首次加载已发起（用 LKG 的旧基址）但失败，随后新配置换了基址 →
 * 原页面点【重试】要比较新读取的配置，变了就按新配置校验、重建控制器并加载新地址；没变才 reload；新配置不可信时继续失败、不加载。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class TrustedWebRetryFreshConfigTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var hostView: View? = null
    private var fresh = target(OLD)

    private fun target(
        origin: String,
        trusted: String = origin,
    ) = TrustedWebTarget("$origin/find-order", TrustedWebConfig(setOf(trusted), emptySet(), "1.0.0"))

    private fun start() {
        val first = target(OLD)
        composeRule.setContent {
            hostView = LocalView.current
            TrustedWebScreen(
                url = first.url,
                config = first.config,
                hostFactory = { _, _ -> null },
                onOpenExternal = {},
                onClose = {},
                retarget = { fresh },
            )
        }
        settle()
    }

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun webView(): WebView {
        val out = mutableListOf<WebView>()

        fun walk(view: View) {
            if (view is WebView) out += view
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        composeRule.runOnUiThread { hostView?.rootView?.let(::walk) }
        assertEquals("页面里只有一个 WebView", 1, out.size)
        return out.single()
    }

    /** 首屏失败：主框架落到不可信地址（控制器的兜底走 onLoadFailed），显示加载失败与【重试】。 */
    private fun failFirstLoad(view: WebView) {
        composeRule.runOnUiThread {
            shadowOf(view).webViewClient.onPageStarted(view, "https://unreachable.example.test/", null)
        }
        settle()
        assertEquals(1, composeRule.onAllNodes(hasText(FAILED)).fetchSemanticsNodes().size)
    }

    private fun clickRetry() {
        composeRule.onNode(hasText(RETRY) and hasClickAction()).performClick()
        settle()
    }

    @Test
    fun fAnd26_retryAfterStartedLoadFailsUsesTheNewBaseFromFreshConfig() {
        start()
        val first = webView()
        assertEquals("首次已按 LKG 的旧基址发起加载", "$OLD/find-order", shadowOf(first).lastLoadedUrl)
        failFirstLoad(first)

        fresh = target(NEW)
        clickRetry()
        val rebuilt = webView()
        assertNotSame("新配置换了基址：重建 WebView 与控制器", first, rebuilt)
        assertEquals("加载新地址", "$NEW/find-order", shadowOf(rebuilt).lastLoadedUrl)
        assertEquals("加载失败页已消失", 0, composeRule.onAllNodes(hasText(FAILED)).fetchSemanticsNodes().size)
    }

    @Test
    fun fAnd26_unchangedConfigOnlyReloadsAndUntrustedFreshConfigStaysFailed() {
        start()
        val first = webView()
        failFirstLoad(first)

        // 配置没变：同一个 WebView reload，不重建
        val reloadsBefore = shadowOf(first).reloadInvocations
        clickRetry()
        assertSame(first, webView())
        assertEquals(reloadsBefore + 1, shadowOf(first).reloadInvocations)

        // 新配置的地址不在它自己的白名单里：继续显示加载失败，新 WebView 不加载任何地址
        failFirstLoad(first)
        fresh = target(NEW, trusted = OTHER)
        clickRetry()
        assertEquals(1, composeRule.onAllNodes(hasText(FAILED)).fetchSemanticsNodes().size)
        val current = webView()
        assertTrue("不可信的新地址不加载", shadowOf(current).lastLoadedUrl.isNullOrEmpty())
    }

    private companion object {
        const val OLD = "https://old.example.test"
        const val NEW = "https://h5.example.test"
        const val OTHER = "https://other.example.test"
        const val SETTLE_ROUNDS = 5
        val FAILED = DefaultTexts.text("h5.load_failed").orEmpty()
        val RETRY = DefaultTexts.text("external_page.retry").orEmpty()
    }
}
