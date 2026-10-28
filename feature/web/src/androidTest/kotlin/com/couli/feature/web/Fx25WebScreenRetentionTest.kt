package com.couli.feature.web

import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.bridge.TrustedAuthSync
import com.couli.core.bridge.WebAuthIdentity
import com.couli.core.bridge.WebDataOwner
import com.couli.generated.DefaultTexts
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I4/P02 与错误层（v3）：真实 TrustedWebScreen + 产品工厂 / 存储 / 协调器。
 * - D10-310 (7)：被盖住（退出组合、holder 仍在）期间切号，holder 在后台用新 WebView（新配置文件）建好并加载新文档；回前台挂回的是新实例。
 * - I3：冷启动补清失败显示可重试错误层，【重试】先补清再只加载一次。
 * - D10-310 (4)：冷启动快照久未知，隔离等待到上限后显示同一错误层；身份确定后【重试】加载。
 */
@RunWith(AndroidJUnit4::class)
class Fx25WebScreenRetentionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeHostActivity>()

    @Test
    @Suppress("LongMethod") // 单个跨 Compose 生命周期的场景；setup/盖住/切号/恢复保持同一份 holder
    fun i4CoveredScreenGetsNewWebViewBuiltInBackgroundAndReattachedOnReturn() {
        Scene(compose, PAGE).use { scene ->
            onUi { scene.sync.observe(A) }
            val shown = mutableStateOf(true)
            scene.show(shown)
            compose.waitUntil(WebWait.CEILING_MS) { onUi { scene.views.size == 1 } }
            val first = onUi { scene.views.single() }
            val probe = PageProbe(first) { action -> onUi(action) }
            val old = probe.await("首个真实网页已订阅事件", "window.fx25Document") { it != "null" }
            onUi { shown.value = false }
            compose.waitForIdle()
            assertFalse(onUi { first.isAttachedToWindow })
            assertTrue("被盖住的页仍收 A（专用投递）", scene.settleAfter { scene.sync.observe(B) })
            assertEquals("被盖住期间已在后台建好新 WebView", 2, onUi { scene.views.size })
            val second = onUi { scene.views[1] }
            assertTrue("旧 WebView 已销毁", onUi { first.destroyedByProduct })
            assertFalse("新 WebView 尚未挂回窗口", onUi { second.isAttachedToWindow })
            assertEquals(onUi { scene.store.current() }, onUi { second.profileName })
            PageProbe(second) { action -> onUi(action) }.await("未重新可见时就已加载新文档", "!!window.fx25Document && window.fx25Document !== $old") {
                it == "true"
            }
            assertEquals("旧文档恰好执行了一次 {logged_in:true}", 1, onUi { first.loggedInTrueExecutions })
            onUi { shown.value = true }
            compose.waitForIdle()
            assertEquals("回前台不再新建", 2, onUi { scene.views.size })
            assertTrue("回前台挂回的是新实例", onUi { second.isAttachedToWindow })
            assertFalse(onUi { first.isAttachedToWindow })
        }
    }

    @Test
    fun i3ColdScreenShowsRetryWithoutLoadingUntilCleanupSucceeds() {
        val cold: Scene.() -> Unit = {
            // 上一个进程留下的待清事务（归属 A）；本进程第一次补清失败
            assertTrue(onUi { journal.markPending(A) })
            onUi { store.failRotate = true }
        }
        Scene(compose, "<!doctype html><script>window.ready = true;</script>", cold).use { scene ->
            onUi { scene.sync.observe(B) }
            scene.show(mutableStateOf(true))
            assertFalse(scene.settleAfter {})
            compose.waitForIdle()
            assertEquals(0, onUi { scene.views.sumOf { it.loads } })
            compose.onNodeWithText(checkNotNull(DefaultTexts.text("h5.load_failed"))).assertIsDisplayed()
            val retry = compose.onNodeWithText(checkNotNull(DefaultTexts.text("external_page.retry")))
            retry.assertIsDisplayed()
            onUi { scene.store.failRotate = false }
            retry.performClick()
            compose.waitUntil(Fx25WebFixture.SETTLE_CEILING_MS) { onUi { scene.views.sumOf { it.loads } > 0 } }
            assertEquals("重试必须先补清再只加载一次", 1, onUi { scene.views.sumOf { it.loads } })
            assertEquals(null, onUi { scene.journal.readPending() })
            PageProbe(onUi { scene.views.last() }) { action -> onUi(action) }
                .await("冷启动补清后页面真正加载", "window.ready") { it == "true" }
        }
    }

    @Test
    fun i3UnknownIdentityWaitIsCappedWithRetryableErrorLayer() {
        Scene(compose, "<!doctype html><script>window.ready = true;</script>").use { scene ->
            scene.show(mutableStateOf(true))
            assertFalse("快照一直未知：到上限按失败结算", scene.settleAfter {})
            compose.waitForIdle()
            assertEquals("未知期间不加载", 0, onUi { scene.views.sumOf { it.loads } })
            compose.onNodeWithText(checkNotNull(DefaultTexts.text("h5.load_failed"))).assertIsDisplayed()
            onUi { scene.sync.observe(A) }
            compose.onNodeWithText(checkNotNull(DefaultTexts.text("external_page.retry"))).performClick()
            compose.waitUntil(Fx25WebFixture.SETTLE_CEILING_MS) { onUi { scene.views.sumOf { it.loads } > 0 } }
            assertEquals(1, onUi { scene.views.sumOf { it.loads } })
            assertEquals("身份与归属一致，不换存储", 0, onUi { scene.store.rotations })
        }
    }

    /** 一份真实 Screen 场景：回环服务、P 路径产品存储、归属记录（A）、待清标记、协调器、经产品工厂建的可观察 WebView。 */
    private inner class Scene(
        private val rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, ComposeHostActivity>,
        html: String,
        beforeCoordinator: Scene.() -> Unit = {},
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val owner = ViewModelStoreOwnerImpl()
        val views = mutableListOf<ScreenWebView>()
        val server =
            LoopbackHttpsServer(InstrumentationRegistry.getInstrumentation().context) { LoopbackHttpsServer.Reply(200, html = html) }
        val origin = "https://127.0.0.1:${server.port}"
        private val name = "fx25-screen-${UUID.randomUUID()}"
        val journal = AndroidWebDataJournal(rule.activity, name)
        val ownerRecord = AndroidWebDataOwnerRecord(rule.activity, name)
        val store = onUi { Fx25RecordingStore(AndroidTrustedWebStore(rule.activity, name, Fx25WebFixture.TIMEOUT_MS) { true }) }
        val sync: TrustedAuthSync
        var identity: WebAuthIdentity = A
        val factory = TrustedWebViewFactory(store, true) { context -> ScreenWebView(context).also { views += it } }

        init {
            assertTrue(onUi { ownerRecord.write(WebDataOwner(A.account)) })
            beforeCoordinator()
            sync = onUi { TrustedAuthSync(scope, journal, ownerRecord, store, Fx25WebFixture.TIMEOUT_MS) }
        }

        fun show(shown: androidx.compose.runtime.MutableState<Boolean>) {
            val services =
                Proxy.newProxyInstance(BridgeServices::class.java.classLoader, arrayOf(BridgeServices::class.java)) { _, method, _ ->
                    error("该场景不需要业务处理器：${method.name}")
                } as BridgeServices
            rule.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    if (shown.value) {
                        TrustedWebScreen(
                            url = "$origin/retained",
                            config = TrustedWebConfig(setOf(origin), emptySet(), "1.0.0"),
                            hostFactory = { ui, pageScope ->
                                BridgeHost(
                                    { BridgeProtocol(setOf(origin), JsonObject(emptyMap()), emptySet()) },
                                    {
                                        BridgeCallerState(
                                            BridgePrivacy.CONSENTED,
                                            identity.account,
                                            BridgeUpdateState.SUPPORTED,
                                            identity.loginEpoch,
                                        )
                                    },
                                    services,
                                    ui,
                                    { BridgeUpdateState.SUPPORTED },
                                    pageScope,
                                )
                            },
                            onOpenExternal = {},
                            onClose = {},
                            authSync = sync,
                            webViews = factory,
                        )
                    }
                }
            }
        }

        /** 在主线程执行 [action] 后等当前事务真正结算，返回结论。 */
        fun settleAfter(action: () -> Unit): Boolean {
            val settled = CountDownLatch(1)
            var success = false
            onUi {
                action()
                sync.whenSettled {
                    success = it
                    settled.countDown()
                }
            }
            WebWait.latch("事务结算", settled, Fx25WebFixture.SETTLE_CEILING_MS)
            return success
        }

        override fun close() {
            onUi {
                owner.viewModelStore.clear()
                sync.close()
            }
            scope.cancel()
            server.close()
        }
    }

    private class ViewModelStoreOwnerImpl : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    /** 经产品工厂基底建出的 WebView：记加载（不含拆除用的 about:blank）、销毁、所在配置文件与旧文档执行的 A。 */
    private class ScreenWebView(
        context: Context,
    ) : WebView(context) {
        var loads = 0
        var destroyedByProduct = false
        var loggedInTrueExecutions = 0
        val profileName: String? get() = if (destroyedByProduct) null else runCatching { WebViewCompat.getProfile(this).name }.getOrNull()

        override fun loadUrl(url: String) {
            if (url != "about:blank") loads += 1
            super.loadUrl(url)
        }

        override fun reload() {
            loads += 1
            super.reload()
        }

        override fun destroy() {
            destroyedByProduct = true
            super.destroy()
        }

        override fun evaluateJavascript(
            script: String,
            resultCallback: ValueCallback<String>?,
        ) {
            if (script.contains(BridgeTransport.AUTH_EVENT) && LOGGED_IN_TRUE.containsMatchIn(script)) {
                super.evaluateJavascript(script) { result ->
                    if (result == "true") loggedInTrueExecutions += 1
                    resultCallback?.onReceiveValue(result)
                }
            } else {
                super.evaluateJavascript(script, resultCallback)
            }
        }
    }

    private fun <T> onUi(action: () -> T): T {
        var result: Result<T>? = null
        compose.runOnUiThread { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        val A = Fx25WebFixture.A
        val B = Fx25WebFixture.B
        val LOGGED_IN_TRUE = Regex("""logged_in\\*"\s*:\s*true""")
        val PAGE =
            """
            <!doctype html><script>
            window.__REBATE_BRIDGE__.subscribe(function (event) { window.fx25LastEvent = event; });
            window.fx25Document = crypto.randomUUID();
            </script>
            """.trimIndent()
    }
}
