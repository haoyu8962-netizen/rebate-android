package com.couli.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clientcore.AppConfig
import com.couli.app.clientcore.AppTracker
import com.couli.app.clientcore.clientPrivacyRaw
import com.couli.app.privacy.AppPrivacy
import com.couli.app.update.AppUpdate
import com.couli.app.update.StoreAccess
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PeriodicScheduler
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.RetryScheduler
import com.couli.core.privacy.SharedPreferencesPrivacyStore
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-03b 第 2 轮评审 R2-04：真实启动流程（[AppStartup]，CouliApplication.onCreate 与 MainActivity 共用）在环境与隐私就绪后显式
 * 初始化 Tracker 并启动每 10 秒的定时上报。同一环境冷启动、磁盘留有上次未上报的队列：不触发任何清理、不产生新事件，旧队列也按
 * 定时器提交。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppStartupTrackerWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_000L

    private class ManualScheduler : PeriodicScheduler {
        val intervals = mutableListOf<Long>()
        private val actions = mutableListOf<() -> Unit>()

        override fun every(
            intervalMillis: Long,
            action: () -> Unit,
        ): AutoCloseable {
            intervals += intervalMillis
            actions += action
            return AutoCloseable { actions -= action }
        }

        fun tick() = actions.toList().forEach { it() }
    }

    private object NoStores : StoreAccess {
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = false

        override fun open(storeKey: String) = false
    }

    private var queueFile: String? = null
    private val uploads = mutableListOf<List<String>>()

    private fun queue(
        privacy: String,
        sink: (List<String>) -> Boolean,
    ) = TrackerQueue(
        readFile = { queueFile },
        writeFile = { queueFile = it },
        sink = sink,
        privacy = privacy,
        userId = { null },
        deviceId = "device-fixture",
        platform = ClientPlatformRaw.ANDROID,
        appVersion = "1.0.0",
        sessionId = "session-test",
        clock = { now },
        canUpload = { true },
    )

    /** 已同意当前版本的隐私闸门、不发检查的强更、本地配置（接线同 AppModule，替身只换掉网络与存储）。 */
    private fun consentedComponents(environmentGate: EnvironmentGate): Triple<AppPrivacy, AppUpdate, AppConfig> {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.PRIVACY_DECISION, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { now }, bundledPolicyVersion = "1").agree()
        val privacyGate =
            PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(privacyGate, environmentSettled = { environmentGate.isSettled }, clock = { now })
        val updateStore = MemoryUpdateStore()
        val update =
            AppUpdate(
                gate =
                    UpdateGate(
                        updateStore,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = null,
                    ),
                store = updateStore,
                checker = { CompletableDeferred<Nothing>().await() },
                stores = NoStores,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = { false },
                clock = { now },
            )
        val config =
            AppConfig(
                ConfigStore(
                    readFile = { null },
                    writeFile = {},
                    fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, "{}", null)) },
                    clock = { now },
                    bundled = "{}",
                ),
                environmentSettled = { environmentGate.isSettled },
            )
        return Triple(privacy, update, config)
    }

    @Test
    fun r2_04_coldStartRestoresTheQueueLeftOnDiskAndUploadsItOnTheTimer() {
        // 上一个进程：已同意，采集了 3 条事件，没来得及上报（不满 10 条）
        val previous = queue(PrivacyStateRaw.CONSENTED) { false }
        repeat(3) { assertTrue(previous.track(ClientEvents.appLaunch(page = "Home"))) }
        assertTrue("队列留在磁盘上", queueFile != null)

        // 本进程：同一环境冷启动（对账无变化，不触发清理），隐私已同意
        var reconciles = 0
        val environmentGate =
            EnvironmentGate {
                reconciles += 1
                EnvironmentStartupReconciler.Outcome.UNCHANGED
            }
        val (privacy, update, config) = consentedComponents(environmentGate)
        var created: TrackerQueue? = null
        val scheduler = ManualScheduler()
        val tracker =
            AppTracker(
                queue = {
                    // 创建时的隐私状态来自已完成冷启动的隐私闸门
                    queue(clientPrivacyRaw(privacy.conclusion, cleanupPending = false)) {
                        uploads += it
                        true
                    }.also { created = it }
                },
                scheduler = scheduler,
                environmentSettled = { environmentGate.isSettled },
            )
        assertNull("启动前没有实例", created)

        AppStartup(environmentGate, config, privacy, update, tracker).onApplicationCreate()

        assertEquals("对账一次", 1, reconciles)
        val queue = checkNotNull(created) { "冷启动显式初始化 Tracker" }
        assertEquals("读回上次留下的队列", 3, queue.queueSize)
        assertEquals("启动每 10 秒的定时上报", listOf(TrackerQueue.FLUSH_INTERVAL_MILLIS), scheduler.intervals)
        assertTrue("定时器触发前不上报", uploads.isEmpty())

        scheduler.tick()

        assertEquals("旧队列按定时器提交", 3, uploads.single().size)
        assertEquals(0, queue.queueSize)
        assertNull(queueFile)

        // 闸门变化（MainActivity）再调一次不重复初始化
        AppStartup(environmentGate, config, privacy, update, tracker).onEnvironmentGateChanged()
        assertEquals(1, scheduler.intervals.size)
    }

    @Test
    fun r2_04_blockedEnvironmentDefersTrackerUntilTheGateSettles() {
        var fail = true
        val environmentGate =
            EnvironmentGate {
                if (fail) error("cleanup failed")
                EnvironmentStartupReconciler.Outcome.UNCHANGED
            }
        var creations = 0
        val scheduler = ManualScheduler()
        val tracker =
            AppTracker(
                queue = {
                    creations += 1
                    queue(PrivacyStateRaw.CONSENTED) { true }
                },
                scheduler = scheduler,
                environmentSettled = { environmentGate.isSettled },
            )
        environmentGate.settleAtStartup()
        tracker.startIfReady()
        assertEquals("闸门阻断时不读旧环境的队列", 0, creations)

        fail = false
        environmentGate.retry()
        tracker.startIfReady()

        assertEquals(1, creations)
        assertEquals(listOf(TrackerQueue.FLUSH_INTERVAL_MILLIS), scheduler.intervals)
    }
}
