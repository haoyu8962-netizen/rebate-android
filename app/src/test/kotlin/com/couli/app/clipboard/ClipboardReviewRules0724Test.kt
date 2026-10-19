@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.network.ApiResult
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardReviewRules0724Test {
    @Test
    fun queuedParsePresentationMustSeeOffThenOnHistoryPublishedInBackground() = scenario(tighten = true)

    @Test
    fun queuedParsePresentationStillWorksWhenEveryPublishedSnapshotAllowsIt() = scenario(tighten = false)

    private fun scenario(tighten: Boolean) {
        val queue = ClipboardReviewQueue0724()
        Dispatchers.setMain(queue)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            ProductionImportPreferences0722Harness(context).use { disk ->
                assertTrue(disk.preferences.importPreferences { ClipboardReview0724Harness.NOW }.enable("1"))
                val nextConfig = AtomicReference(ClipboardReview0724Harness.CONFIG)
                val store =
                    ConfigStore(
                        { null },
                        {},
                        { _, done -> done(ConfigReply(ConfigReply.OK, nextConfig.get(), null)) },
                        { ClipboardReview0724Harness.NOW },
                        ClipboardReview0724Harness.CONFIG,
                    )
                ClipboardReview0724Harness(disk.preferences, queue) { store.snapshots.value.data }.use { h ->
                    verifyQueuedPresentation(h, store, nextConfig, tighten)
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun verifyQueuedPresentation(
        h: ClipboardReview0724Harness,
        store: ConfigStore,
        nextConfig: AtomicReference<String>,
        tighten: Boolean,
    ) {
        val queue = h.queue
        h.board.connectRules(store)
        queue.drain()
        val reply = CompletableDeferred<ApiResult>()
        h.reply = reply
        h.foreground()
        assertEquals(1, h.requests.size)
        assertEquals(0, queue.size)
        reply.complete(Clipboard0724Fixture.success())
        queue.runOne() // 真实 parse 返回，onParsed 的呈现处理才入队，尚未执行。
        assertEquals("前提：返回处理已经排队", 1, queue.size)
        assertNull(h.board.result.value)
        publish(store, nextConfig, tighten)
        assertEquals(Clipboard0724Fixture.json(ClipboardReview0724Harness.CONFIG), store.snapshots.value.data)
        queue.runOne() // 先运行已排队的返回处理；规则应用任务排在它后面。
        val firstResult = h.board.result.value
        queue.drain() // 全部结算后再断言，并保留首次呈现见证，不能被事后清卡掩盖。
        if (tighten) {
            assertNull("中间关平台必须作废旧卡，不能闪现", firstResult)
            assertNull(h.board.result.value)
        } else {
            assertTrue(firstResult is ClipboardResult.CardList)
            assertTrue(h.board.result.value is ClipboardResult.CardList)
        }
        assertEquals(1, h.requests.size)
    }

    private fun publish(
        store: ConfigStore,
        next: AtomicReference<String>,
        tighten: Boolean,
    ) {
        val failure = AtomicReference<Throwable?>()
        val worker =
            Thread {
                try {
                    next.set(if (tighten) OFF else ClipboardReview0724Harness.CONFIG)
                    store.onForeground()
                    next.set(ClipboardReview0724Harness.CONFIG)
                    store.onForeground()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        worker.start()
        worker.join(5_000)
        assertFalse("后台发布完成屏障", worker.isAlive)
        failure.get()?.let { throw it }
    }

    companion object {
        private const val OFF = """{"clipboard":{"consent_version":"1"},"features":{"flags":{"clipboard.enabled.taobao":false}}}"""
    }
}
