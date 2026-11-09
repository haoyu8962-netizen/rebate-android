@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.FIRST_KEY
import com.couli.app.search.submit.SubmitReplies.LINK
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 修复 r3 / 评审 r2 S1-2（测试 v6）：发送侧的线程交错。识别上传的写出前守卫在真实 IO 线程上求值：IO 线程停在守卫处，测试线程（主线程角色）
 * 作废——覆盖搜索页（界面世代）或切号（登录世代 + 会话通知）——再放行，守卫必须读到作废后的值，识别不得写出。
 *
 * 局限：停 / 放行用 CountDownLatch，本身带 happens-before，所以这条钉住的是「守卫在 IO 线程上求值、作废先于求值时必拒」的语义，
 * 复现不了 JIT / CPU 缓存造成的旧值可见性；后者由 [SearchLinkGuardPublicationTest] 的结构断言钉住（守卫只读原子快照或 volatile 字段）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class SearchLinkIoGuardSubmitTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun coveredWhileIoGuardPausedDoesNotUpload() = interleave(SearchSubmitFixture::coverWithDetail)

    @Test fun accountSwitchWhileIoGuardPausedDoesNotUpload() = interleave(SearchSubmitFixture::switchAccount)

    @Test fun loginEpochOnlyWhileIoGuardPausedDoesNotUpload() = interleave(SearchSubmitFixture::bumpLoginEpoch)

    private fun interleave(invalidate: (SearchSubmitFixture) -> Unit) =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                val pause = f.api.pauseGuardOnIo(PARSE)
                val main = Thread.currentThread().name
                f.submit(LINK)
                runCurrent()
                assertTrue("前提：IO 线程已到达写出前守卫", pause.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS))
                assertNotEquals("前提：守卫在另一条线程上求值", main, pause.ioThread.get())
                assertEquals("前提：识别已交到网络层、尚未写出", emptyList<String>(), f.api.writtenOperations)
                // 主线程作废，然后放行 IO 线程上的守卫
                invalidate(f)
                pause.proceed.countDown()
                awaitResumed(pause)
                assertEquals("IO 线程上的写出前守卫必须读到作废后的值（拒绝）", false, pause.verdict.get())
                f.api.parseReply.complete(SubmitReplies.single())
                advanceUntilIdle()
                assertEquals("已作废的识别不得上传（实际写出 ${f.api.writtenOperations}）", emptyList<String>(), f.api.writtenOperations)
                assertEquals("不得回落关键词搜", listOf(PARSE), SubmitReplies.operations(f))
                assertTrue("不得导航（实际 ${f.navigations}）", f.navigations.isEmpty())
                assertNull("不得写 ProductSeeds", SubmitReplies.claimSeed(f, FIRST_KEY))
                assertNull("不得出结果（实际 ${f.clipboard.result.value}）", f.clipboard.result.value)
                assertFalse("旧识别票据已作废", f.api.ownerOf(0).stillOwned())
            }
        }

    /** 等 IO 线程求值并把发送协程交回测试调度器（有上限，不无限等）。 */
    private fun TestScope.awaitResumed(pause: IoGuardPause) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!pause.resumed.get() && System.nanoTime() < deadline) {
            advanceUntilIdle()
            Thread.sleep(POLL_MS)
        }
        assertTrue("IO 线程上的守卫在时限内求值完毕", pause.resumed.get())
    }

    private companion object {
        const val PARSE = "parseInput"
        const val WAIT_SECONDS = 10L
        const val POLL_MS = 5L
    }
}
