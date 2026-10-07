package com.couli.feature.web

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUpdateState
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 第 2 轮评审 R2-03 / 共性第 47 条：真实 NavHost（带默认的退场过渡）里，可信容器有一个在途的桥调用：
 * - 被别的页面盖住（仍在回退栈里）：调用照常有效；
 * - 清栈回到首页（popBackStack(home)，与 Router 清栈同一个调用）：在同一条主线程消息里调用就已失效、WebView 已摘下，
 *   不等 Navigation 的退场过渡结束后才清除回退栈条目（修复前要等几百毫秒的 onCleared）。
 */
@RunWith(AndroidJUnit4::class)
class WebContainerBackStackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeHostActivity>()

    private val validity = CompletableDeferred<BridgeCallValidity>()
    private val replies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var host: BridgeHost? = null
    private lateinit var nav: NavHostController

    private fun <T> onUi(block: () -> T): T {
        var value: T? = null
        compose.runOnUiThread { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun webViews(): List<WebView> =
        onUi {
            generateSequence(listOf<View>(compose.activity.window.decorView)) { level ->
                level.flatMap { (it as? ViewGroup)?.let { g -> List(g.childCount, g::getChildAt) }.orEmpty() }
            }.takeWhile { it.isNotEmpty() }
                .flatten()
                .filterIsInstance<WebView>()
                .toList()
        }

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out: $message")
    }

    private fun show() =
        compose.setContent {
            nav = rememberNavController()
            WebContainers.FollowBackStack(nav.currentBackStack)
            CompositionLocalProvider(LocalWebViewFactory provides { context -> OfflineWebView(context) }) {
                NavHost(navController = nav, startDestination = HOME) {
                    composable(HOME) { Text("home") }
                    composable(OTHER) { Text("other") }
                    composable(WEB) {
                        TrustedWebScreen(
                            url = PAGE,
                            config = TrustedWebConfig(setOf(TRUSTED), emptySet(), "1.0.0"),
                            hostFactory = { ui, scope ->
                                BridgeHost(
                                    protocol = { BridgeProtocol(setOf(TRUSTED), JsonObject(emptyMap()), emptySet()) },
                                    caller = {
                                        BridgeCallerState(
                                            BridgePrivacy.CONSENTED,
                                            accountKey = "account-a",
                                            update = BridgeUpdateState.SUPPORTED,
                                        )
                                    },
                                    services = PendingUser(validity),
                                    ui = ui,
                                    awaitUpdate = { BridgeUpdateState.SUPPORTED },
                                    scope = scope,
                                ).also { host = it }
                            },
                            onOpenExternal = {},
                            onClose = { nav.popBackStack() },
                        )
                    }
                }
            }
        }

    @Test
    fun clearingTheStackInvalidatesTheContainersCallsAtOnceWhileCoveringKeepsThem() {
        show()
        compose.runOnUiThread { nav.navigate(WEB) }
        compose.waitForIdle()
        waitUntil("web view created") { webViews().isNotEmpty() && host != null }
        val webView = webViews().single()
        compose.runOnUiThread {
            checkNotNull(host).handle("""{"v":1,"id":"in-flight","method":"auth.getUser","params":{}}""", TRUSTED, true, false) {
                replies += it
            }
        }
        waitUntil("call in flight") { validity.isCompleted }
        val call = validity.getCompleted()

        // 被盖住：仍在回退栈里，调用照常有效
        compose.runOnUiThread { nav.navigate(OTHER) }
        compose.waitForIdle()
        assertTrue("covered is not popped", call.stillValid())

        // 清栈：同一条主线程消息里就已失效（不等退场过渡与 onCleared）
        val (validRightAfter, parentRightAfter) =
            onUi {
                nav.popBackStack(HOME, inclusive = false)
                call.stillValid() to webView.parent
            }
        assertFalse("clearing the stack invalidates the call at once", validRightAfter)
        assertNull("the WebView is detached at once", parentRightAfter)
        waitUntil("the call was answered 90500") { replies.any { "90500" in it } }
    }

    /** auth.getUser 一直挂着（交出这次调用的有效性）。 */
    private class PendingUser(
        private val validity: CompletableDeferred<BridgeCallValidity>,
    ) : BridgeServices {
        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
            this.validity.complete(validity)
            CompletableDeferred<Unit>().await()
            return BridgeOutcome.Ok()
        }

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun login(update: String) = BridgeOutcome.Ok()

        override suspend fun signedRequest(
            method: String,
            path: String,
            body: JsonElement?,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ) = BridgeOutcome.Ok()

        override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

        override suspend fun clipboardRead() = BridgeOutcome.Ok()

        override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

        override suspend fun pushStatus() = BridgeOutcome.Ok()

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override fun track(
            name: String,
            props: JsonObject,
        ) = BridgeOutcome.Ok()

        override fun reportRejected(
            method: String,
            code: Int,
        ) = Unit
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val POLL_MS = 100L
        const val HOME = "home"
        const val OTHER = "other"
        const val WEB = "web"
        const val TRUSTED = "https://h5.example.test"
        const val PAGE = "$TRUSTED/a"
    }
}
