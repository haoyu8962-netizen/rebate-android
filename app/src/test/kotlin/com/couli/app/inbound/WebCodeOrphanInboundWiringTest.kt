package com.couli.app.inbound

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.ActivityAuthReturnSignal
import com.couli.app.trade.BrowserWebCodeAuthorizer
import com.couli.app.trade.MemoryWebCodeAttempts
import com.couli.app.trade.RecordingLauncher
import com.couli.app.trade.WebCodeInboundDispatcher
import com.couli.app.trade.webCodeSha256
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeCallbackSink
import com.couli.feature.trade.WebCodeInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 测试用 Application：入站中转拿到的是真实的 [WebCodeInboundDispatcher]（真实收件箱），交给 orphan 的回跳记下来。 */
class OrphanInboundTestApp :
    Application(),
    WebCodeCallbackSinkProvider {
    val inbox = WebCodeInbox()
    val orphans = mutableListOf<WebCodeCallback>()
    private val dispatcher by lazy { WebCodeInboundDispatcher(inbox, { orphans += it }) }

    override fun webCodeCallbackSink(): WebCodeCallbackSink = dispatcher
}

/**
 * FU-AND-TB-WEBCODE-B 测试：入站中转 → 收件箱 → 下一轮主线程循环交 orphan 的接线（设计稿 §6.4「什么时候触发」、§9 第 1 行）。
 * - InboundSchemeActivity 把 auth 回跳交给收件箱后**当场**不交 orphan；下一轮主线程循环检查：没有等待者认领 → 交给 orphan 处理器；有等待者认领 → 不交；
 * - MainActivity 的前后台信号（甲的取消判定，[ActivityAuthReturnSignal]）与 orphan 不冲突：冷启动时 STOPPED / STARTED 不让 orphan 回跳丢失或被当成取消；
 *   热路径回跳先到时授权器拿到凭证、orphan 不重复处理；热路径已按取消结束后的晚到回跳交给 orphan。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = OrphanInboundTestApp::class, sdk = [36])
class WebCodeOrphanInboundWiringTest {
    private val app: OrphanInboundTestApp get() = ApplicationProvider.getApplicationContext()

    private fun view(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setClass(app, InboundSchemeActivity::class.java)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    private fun arrive(uri: String = CALLBACK) {
        Robolectric.buildActivity(InboundSchemeActivity::class.java, view(uri)).create()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun d01_anUnclaimedCallbackGoesToTheOrphanHandlerOnTheNextMainLoopTurn() {
        arrive()
        assertTrue("交付当场不交 orphan（等下一轮，给热等待者认领的机会）：${app.orphans}", app.orphans.isEmpty())
        idle()
        val got = app.orphans.singleOrNull()
        assertTrue("下一轮仍没人认领：交给 orphan 一次：${app.orphans}", got is WebCodeCallback.Code)
        assertEquals("c0de_1", (got as WebCodeCallback.Code).code)
        assertEquals("st_1", got.state)
        idle()
        assertEquals("只交一次", 1, app.orphans.size)
    }

    @Test
    fun d02_aCallbackClaimedByAWaitingAuthorizerIsNotHandedToTheOrphanHandler() =
        runTest {
            val waiting = backgroundScope.async { app.inbox.await(webCodeSha256("st_1")) }
            runCurrent()
            arrive()
            runCurrent()
            idle()
            assertTrue("热等待者拿到", waiting.isCompleted)
            assertTrue("已认领：不交 orphan：${app.orphans}", app.orphans.isEmpty())
        }

    @Test
    fun d03_aWaiterForAnotherStateDoesNotHideTheCallbackFromTheOrphanHandler() =
        runTest {
            val other = backgroundScope.async { app.inbox.await(webCodeSha256("st_other")) }
            runCurrent()
            arrive()
            runCurrent()
            idle()
            assertTrue("别的 state 的等待者拿不到", !other.isCompleted)
            assertEquals("交给 orphan：${app.orphans}", 1, app.orphans.size)
        }

    @Test
    fun d04_anUnclaimedErrorOneCallbackAlsoGoesToTheOrphanHandler() {
        arrive("couli://auth/taobao?state=st_1&error=1")
        idle()
        val got = app.orphans.singleOrNull()
        assertTrue("error=1 也交 orphan（冷启动删待办、提示 error.20004 由处理器判）：${app.orphans}", got is WebCodeCallback.Incomplete)
    }

    @Test
    fun d05_invalidCallbacksAndRouteLinksNeverReachTheOrphanHandler() {
        listOf("couli://auth/taobao?code=c0de_1", "couli://oauth/taobao?code=c0de_1&state=st_1", "couli://r/Search?q=a").forEach {
            arrive(it)
            idle()
            assertTrue("不交 orphan：$it → ${app.orphans}", app.orphans.isEmpty())
        }
    }

    @Test
    fun d06_coldStartVisibilityEventsDoNotTurnTheOrphanCallbackIntoACancel() {
        // 冷启动：没有热等待者；中转拉起 MainActivity，MainActivity 先后发出 STOPPED / STARTED（甲的取消判定只对热等待者有效）
        val signal = ActivityAuthReturnSignal()
        signal.stopped()
        arrive()
        signal.started()
        idle()
        assertEquals("冷启动的回跳照样交 orphan 一次（没被当成取消丢掉）：${app.orphans}", 1, app.orphans.size)
        assertTrue(app.orphans.single() is WebCodeCallback.Code)
    }

    @Test
    fun d07_hotPathCallbackBeforeStartedIsTheAuthorizersAndNotTheOrphans() =
        runTest {
            val signal = ActivityAuthReturnSignal()
            val attempts = MemoryWebCodeAttempts()
            val authorizer = BrowserWebCodeAuthorizer(RecordingLauncher(), app.inbox, attempts, signal)
            val running = backgroundScope.async { authorizer.authorize(AUTH_URL, "st_1") }
            runCurrent()
            signal.stopped()
            runCurrent()
            arrive()
            signal.started()
            runCurrent()
            idle()
            runCurrent()
            assertTrue("授权器拿到凭证（回跳先于 STARTED）：${running.isCompleted}", running.isCompleted)
            assertTrue(running.getCompleted() is AuthStepResult.Credential)
            assertTrue("不交 orphan，不会重复提交：${app.orphans}", app.orphans.isEmpty())
        }

    @Test
    fun d08_aLateCallbackAfterTheHotAttemptWasCancelledGoesToTheOrphanHandler() =
        runTest {
            val signal = ActivityAuthReturnSignal()
            val attempts = MemoryWebCodeAttempts()
            val authorizer = BrowserWebCodeAuthorizer(RecordingLauncher(), app.inbox, attempts, signal)
            val running = backgroundScope.async { authorizer.authorize(AUTH_URL, "st_1") }
            runCurrent()
            signal.stopped()
            runCurrent()
            signal.started()
            runCurrent()
            assertEquals("用户中途返回：取消", AuthStepResult.Cancelled, running.getCompleted())
            assertNotNull("尝试记录保留给晚到回跳", attempts.current())
            arrive()
            idle()
            assertEquals("晚到回跳交给 orphan：${app.orphans}", 1, app.orphans.size)
        }

    private companion object {
        const val CALLBACK = "couli://auth/taobao?code=c0de_1&state=st_1"
        const val AUTH_URL = "https://oauth.taobao.com/authorize?state=st_1"
    }
}
