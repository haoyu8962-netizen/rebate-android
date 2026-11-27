package com.couli.app.trade

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.trade.AuthCredential
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeAttempt
import com.couli.feature.trade.WebCodeAttemptStore
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeInbox
import java.security.MessageDigest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** sha256（UTF-8 字节，小写十六进制）：测试自己算，不复用被测代码。 */
internal fun webCodeSha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 内存里的尝试记录（只给授权器单测用；落盘口径见 WebCodeAttemptStoreTest）。 */
internal class MemoryWebCodeAttempts : WebCodeAttemptStore {
    var attempt: WebCodeAttempt? = null
    val recordedStates = mutableListOf<String>()

    override fun record(
        state: String,
        nowMillis: Long,
    ) {
        recordedStates += state
        attempt = WebCodeAttempt(webCodeSha256(state), nowMillis)
    }

    override fun current(): WebCodeAttempt? = attempt

    override fun clear() {
        attempt = null
    }
}

/** 记下打开过的地址；[opens] 决定能不能打开（没有浏览器时 false）。 */
internal class RecordingLauncher(
    var opens: Boolean = true,
) : AuthBrowserLauncher {
    val opened = mutableListOf<String>()

    override fun open(authUrl: String): Boolean {
        opened += authUrl
        return opens
    }
}

/** 测试驱动的 MainActivity 可见性事件。 */
internal class TestReturnSignal : AuthReturnSignal {
    override val events = MutableSharedFlow<AuthReturnEvent>(extraBufferCapacity = 16)

    fun stopped() = check(events.tryEmit(AuthReturnEvent.STOPPED))

    fun started() = check(events.tryEmit(AuthReturnEvent.STARTED))
}

/**
 * FU-AND-TB-WEBCODE-A 测试：真实 web_code 授权器（设计稿 §2 ①–④、§5.5、§6.1、§6.3；D10-2717：白名单只放 https://oauth.taobao.com，
 * error=1 按未完成 → Failed）。打开浏览器、回前台信号、收件箱、尝试记录、时钟全部注入替身；不依赖 androidx.browser（D10-3568）。
 * 用 Robolectric 跑只是为了不限制实现用 android.net.Uri 校验地址。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BrowserWebCodeAuthorizerTest {
    private val launcher = RecordingLauncher()
    private var now = 1_700_000_000_000L
    private val inbox = WebCodeInbox(clock = { now })
    private val attempts = MemoryWebCodeAttempts()
    private val signal = TestReturnSignal()
    private val authorizer = BrowserWebCodeAuthorizer(launcher, inbox, attempts, signal, clock = { now })

    private fun TestScope.start(
        url: String = OK_URL,
        state: String = STATE,
    ): Deferred<AuthStepResult> {
        val running = backgroundScope.async { authorizer.authorize(url, state) }
        runCurrent()
        return running
    }

    private fun Deferred<AuthStepResult>.result(): AuthStepResult {
        assertTrue("授权器应已有结果", isCompleted)
        return getCompleted()
    }

    @Test
    fun a01_availableIsAlwaysTrue() {
        assertTrue("真实授权器恒可执行（release 与关着假服务的 debug / staging）", authorizer.available)
        launcher.opens = false
        assertTrue("没有浏览器也不提前判不可用：打开时再返回 Failed", authorizer.available)
    }

    @Test
    fun a02_urlOutsideTheWhitelistIsNotOpenedAndFails() =
        runTest {
            listOf(
                "http://oauth.taobao.com/authorize?state=$STATE",
                "https://oauth.m.taobao.com/authorize?state=$STATE",
                "https://oauth.tbsandbox.com/authorize?state=$STATE",
                "https://oauth.example.test/authorize?state=$STATE",
                "https://user:pw@oauth.taobao.com/authorize?state=$STATE",
                "https://oauth.taobao.com@evil.example/authorize?state=$STATE",
                "https://oauth.taobao.com.evil.example/authorize?state=$STATE",
                "https://evil.example/authorize?next=https://oauth.taobao.com/",
                "intent://oauth.taobao.com/authorize#Intent;scheme=https;end",
                "javascript:alert(1)",
                "oauth.taobao.com/authorize?state=$STATE",
                "",
            ).forEach { url ->
                val result = start(url = url).result()
                assertEquals("不在白名单返回 Failed：$url", AuthStepResult.Failed, result)
                assertTrue("不在白名单不打开：$url", launcher.opened.isEmpty())
                assertNull("不在白名单不记尝试：$url", attempts.current())
            }
        }

    @Test
    fun a03_whitelistedUrlIsOpenedAndTheAttemptRecorded() =
        runTest {
            val running = start()
            assertEquals("白名单内原样交给 launcher 打开一次", listOf(OK_URL), launcher.opened)
            assertFalse("打开后等回跳", running.isCompleted)
            assertEquals("只记 sha256(state) 与注入时钟的时间", WebCodeAttempt(webCodeSha256(STATE), now), attempts.current())
        }

    @Test
    fun a04_launcherFailureFails() =
        runTest {
            launcher.opens = false
            val result = start().result()
            assertEquals("没有浏览器（打不开）返回 Failed", AuthStepResult.Failed, result)
            assertEquals("确实尝试过打开", listOf(OK_URL), launcher.opened)
        }

    @Test
    fun a05_matchingCodeReturnsCredentialAndClearsTheAttempt() =
        runTest {
            val running = start()
            inbox.deliver(WebCodeCallback.Code("CODE_abc-1", STATE))
            runCurrent()
            val result = running.result()
            assertTrue("匹配的回跳 → Credential：$result", result is AuthStepResult.Credential)
            val credential = (result as AuthStepResult.Credential).value
            assertTrue("凭证是 WebCode", credential is AuthCredential.WebCode)
            assertEquals("CODE_abc-1", (credential as AuthCredential.WebCode).code)
            assertNull("拿到凭证后删除尝试记录", attempts.current())
        }

    @Test
    fun a06_mismatchedCallbackIsIgnoredAndWaitingContinues() =
        runTest {
            val running = start()
            inbox.deliver(WebCodeCallback.Code("FORGED", "someone-else"))
            runCurrent()
            assertFalse("state 不符的回跳丢弃，不打断等待", running.isCompleted)
            assertTrue("等待中的尝试记录还在", attempts.current() != null)
            inbox.deliver(WebCodeCallback.Code("CODE_ok", STATE))
            runCurrent()
            val result = running.result()
            assertTrue("之后匹配的回跳照常交付：$result", result is AuthStepResult.Credential)
            assertEquals("CODE_ok", ((result as AuthStepResult.Credential).value as AuthCredential.WebCode).code)
        }

    @Test
    fun a07_errorOneIsIncompleteAndFails() =
        runTest {
            val running = start()
            assertFalse("打开后等回跳", running.isCompleted)
            inbox.deliver(WebCodeCallback.Incomplete(STATE))
            runCurrent()
            assertEquals("error=1 按未完成 → 授权失败二选一（D10-2717）", AuthStepResult.Failed, running.result())
            assertNull("匹配的回跳到了就删除记录", attempts.current())
        }

    @Test
    fun a08_returningWithoutCallbackIsCancelledAndKeepsTheAttempt() =
        runTest {
            val running = start()
            signal.stopped()
            runCurrent()
            assertFalse("只离开前台不算取消", running.isCompleted)
            signal.started()
            runCurrent()
            assertEquals("拉起后先 stop 再 start、仍无回跳 → Cancelled", AuthStepResult.Cancelled, running.result())
            assertEquals("记录保留（晚到回跳还能核对）", webCodeSha256(STATE), attempts.current()?.stateSha256)
        }

    @Test
    fun a09_startWithoutStopIsNotACancel() =
        runTest {
            val running = start()
            signal.started()
            runCurrent()
            assertFalse("只有 start、没有先 stop：不判取消，继续等", running.isCompleted)
            assertEquals("仍在等待：已打开授权页", listOf(OK_URL), launcher.opened)
        }

    @Test
    fun a10_callbackBeforeReturningWins() =
        runTest {
            val running = start()
            signal.stopped()
            runCurrent()
            // 中转 Activity 在自己的 onCreate 里同步送出回跳，早于 MainActivity 的 onStart（设计稿 §9）
            inbox.deliver(WebCodeCallback.Code("CODE_first", STATE))
            runCurrent()
            signal.started()
            runCurrent()
            val result = running.result()
            assertTrue("回跳先到：Credential，不是 Cancelled：$result", result is AuthStepResult.Credential)
        }

    @Test
    fun a11_noLocalTimerEndsTheWait() =
        runTest {
            val running = start()
            now += 60 * 60 * 1000L
            advanceTimeByHour()
            assertFalse("不另设本地计时器打断等待（设计稿 §6.3）", running.isCompleted)
            assertEquals(listOf(OK_URL), launcher.opened)
        }

    @Test
    fun a12_cancelledWaitDoesNotSwallowALaterCallback() =
        runTest {
            val running = start()
            assertFalse(running.isCompleted)
            assertEquals("确实已打开、进入等待", listOf(OK_URL), launcher.opened)
            running.cancel()
            runCurrent()
            inbox.deliver(WebCodeCallback.Code("CODE_late", STATE))
            val later = backgroundScope.async { inbox.await(webCodeSha256(STATE)) }
            runCurrent()
            assertTrue("被取消的等待已注销，晚到回跳留在收件箱", later.isCompleted)
        }

    private fun TestScope.advanceTimeByHour() {
        testScheduler.advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
    }

    private companion object {
        const val STATE = "st_Ab-12"
        const val OK_URL =
            "https://oauth.taobao.com/authorize?response_type=code&client_id=12345678" +
                "&redirect_uri=https%3A%2F%2Fh5-stg.couliapp.cn%2Ftaobao-auth-callback&state=$STATE&view=wap"
    }
}
