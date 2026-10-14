package com.couli.app.followup

import com.couli.app.beginEnvironmentSwitchBehindBarrier
import com.couli.app.withdrawalBarrier
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.CallEpochs
import com.couli.core.network.CredentialReadBarrier
import com.couli.core.network.DeviceCredentialStore
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.StoredDevice
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0714 第 1 轮评审 R1-01（S0）：凭据读取屏障的顺序——先用屏障内部状态禁止新读取 → 等已准入的读取结束 → 网络作废、在途取消 →
 * 最后才发布 switching / 撤回标记。真实并发探针：设备凭据读取停在「检查通过与实际读取之间」，另一线程按 AppModule 的接线
 * （[beginEnvironmentSwitchBehindBarrier] / [withdrawalBarrier]）关闸门或撤回；从状态发布的那一刻起计数，发布之后的存储读取必须为 0。
 */
class FollowupAnd0714FixR1BarrierTest {
    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 1L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    /** 存储读取：状态已发布（[published] 现判为 true）之后的每一次读都记一笔。 */
    private class ProbeStore(
        val published: () -> Boolean,
    ) : DeviceCredentialStore {
        val reads = AtomicInteger()
        val afterPublish = AtomicInteger()

        override fun read(): StoredDevice {
            reads.incrementAndGet()
            if (published()) afterPublish.incrementAndGet()
            return StoredDevice.Present("device-fixture", "test-only.install.device-a")
        }

        override fun save(
            deviceId: String,
            installSecret: String,
        ) = Unit

        override fun clear() = Unit
    }

    /** 读取停在「检查通过与实际读取之间」：环境就绪判定返回 true 之前先挂起一次，等测试放行。 */
    private class Pause {
        val armed = AtomicBoolean(true)
        val checked = CountDownLatch(1)
        val resume = CountDownLatch(1)

        fun <T> after(value: T): T {
            if (armed.compareAndSet(true, false)) {
                checked.countDown()
                resume.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return value
        }
    }

    private fun registry(
        store: DeviceCredentialStore,
        barrier: CredentialReadBarrier,
        privacy: () -> String,
        settled: () -> Boolean,
    ) = DeviceRegistry(
        platform = "android",
        idReader = { "0123456789abcdef" },
        store = store,
        privacy = privacy,
        client = { error("registration must not reach the network") },
        epochs = { CallEpochs(0, 0) },
        executor = Executor { it.run() },
        environmentSettled = settled,
        readBarrier = barrier,
    )

    /**
     * 探针：读取线程停在检查与读取之间 → 另一线程发布（[publish]）→ 最多等 [PUBLISH_WAIT_MS] 看状态是否已发布 → 期间主线程再读一次（应被内部闸挡住、
     * 不读存储）→ 放行读取线程 → 等发布完成。返回（发布前看到的状态是否已发布、主线程那次读的结果）。
     */
    private fun probe(
        pause: Pause,
        read: () -> String?,
        published: () -> Boolean,
        publish: () -> Unit,
    ): Pair<Boolean, String?> {
        val reader = Thread { read() }.apply { start() }
        assertTrue("读取应停在检查之后", pause.checked.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val failure = AtomicReference<Throwable?>()
        val publisher = Thread { runCatching(publish).exceptionOrNull()?.let(failure::set) }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PUBLISH_WAIT_MS)
        while (!published() && System.nanoTime() < deadline) Thread.sleep(1)
        val publishedEarly = published()
        // 屏障竖起中（内部闸已关）的新读取：不读
        val during = if (publishedEarly) null else read()
        pause.resume.countDown()
        reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        publisher.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(reader.isAlive || publisher.isAlive)
        failure.get()?.let { throw it }
        return publishedEarly to during
    }

    @Test
    fun r1_01_switch_publishes_switching_only_after_admitted_reads_finish_and_network_is_invalidated() {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.apply { settleAtStartup() }
        val barrier = CredentialReadBarrier()
        val inFlight = InFlightCalls()
        val pause = Pause()
        val store = ProbeStore(published = { !gate.isSettled })
        val device = registry(store, barrier, { PrivacyStateRaw.CONSENTED }) { pause.after(gate.isSettled) }
        val epochsAtPublish = AtomicReference<CallEpochs>()

        val (publishedEarly, during) =
            probe(pause, { device.deviceId }, { !gate.isSettled }) {
                beginEnvironmentSwitchBehindBarrier(barrier, inFlight::onEnvironmentSwitching) {
                    epochsAtPublish.set(inFlight.epochs())
                    gate.closeForSwitch()
                }
            }

        assertEquals("从 switching 发布的那一刻起：存储零读取", 0, store.afterPublish.get())
        assertFalse("已准入的读取还没结束时不得发布 switching", publishedEarly)
        assertNull("屏障竖起中的新读取被内部闸挡住", during)
        assertEquals("发布之前已作废网络世代、取消在途", CallEpochs(0, 1), epochsAtPublish.get())
        assertFalse(gate.isSettled)
        val before = store.reads.get()
        assertNull(device.deviceId)
        assertNull(device.installSecret)
        assertEquals("发布之后的读取一律不读存储", before, store.reads.get())
        assertEquals(0, store.afterPublish.get())
    }

    @Test
    fun r1_01_withdraw_publishes_pending_marker_only_after_admitted_reads_finish_and_network_is_invalidated() {
        val barrier = CredentialReadBarrier()
        val inFlight = InFlightCalls()
        val privacy = PrivacyGate(MemoryStore(), { 0L }, "1")
        val epochsAtPublish = AtomicReference<CallEpochs>()
        val wired = withdrawalBarrier(barrier, inFlight::onPrivacyRevoked)
        privacy.credentialBarrier = { publish ->
            wired {
                epochsAtPublish.set(inFlight.epochs())
                publish()
            }
        }
        privacy.withdrawalCleanup = { error("cleaners not run yet") }
        val pause = Pause()
        val pending = { privacy.credentialAccess.cleanupPending }
        val store = ProbeStore(published = pending)
        val device =
            registry(store, barrier, { if (pending()) PrivacyStateRaw.BASIC_MODE else PrivacyStateRaw.CONSENTED }) { pause.after(true) }

        val (publishedEarly, during) = probe(pause, { device.deviceId }, pending) { runCatching { privacy.withdraw() } }

        assertEquals("从撤回标记发布的那一刻起：存储零读取", 0, store.afterPublish.get())
        assertFalse("已准入的读取还没结束时不得记下待清理标记", publishedEarly)
        assertNull(during)
        assertEquals("发布之前已作废隐私世代、取消在途", CallEpochs(1, 0), epochsAtPublish.get())
        assertTrue(pending())
        val before = store.reads.get()
        assertNull(device.deviceId)
        assertEquals(before, store.reads.get())
    }

    @Test
    fun r1_01_barrier_internal_state_denies_new_reads_while_waiting_and_reopens_after_publish() {
        val barrier = CredentialReadBarrier()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val holder =
            Thread {
                barrier.read({ true }, {}) {
                    inside.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }.apply { start() }
        assertTrue(inside.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val raiser = Thread { barrier.raise({ order += "invalidate" }) { order += "publish" } }.apply { start() }
        Thread.sleep(PUBLISH_WAIT_MS)
        assertTrue("已准入的读取没结束：既不作废也不发布", order.isEmpty())
        assertEquals("内部闸关着：新读取不读", "denied", barrier.read({ true }, { "denied" }) { "read".also { reads.incrementAndGet() } })
        release.countDown()
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        raiser.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertEquals(listOf("invalidate", "publish"), order.toList())
        assertEquals("发布之后内部闸打开（由已发布的状态自己判定）", "read", barrier.read({ true }, { "denied" }) { "read" })
        assertEquals(0, reads.get())
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val PUBLISH_WAIT_MS = 300L
    }
}
