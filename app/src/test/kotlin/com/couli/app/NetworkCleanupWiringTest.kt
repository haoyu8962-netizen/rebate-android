package com.couli.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clientcore.AppConfig
import com.couli.app.web.BridgeHostRegistry
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.AppEnvironment
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchListener
import com.couli.core.network.CallEpochs
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryDeviceCredentialStore
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.SecretCipher
import com.couli.core.network.StoredDevice
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.RetryScheduler
import com.couli.core.privacy.SharedPreferencesPrivacyStore
import java.io.File
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * lessons 35：网络失效清理（取消在途请求）在 App 的真实组合处（AppModule）排在撤回同意与环境切换两个注册表的第一位。
 * APP-03b：设备注册、埋点队列登记进撤回清理；设备注册、LKG、埋点队列登记进环境切换清理（03 §3.6、BR-ID-13）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NetworkCleanupWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    private class Box {
        var value: String? = null
    }

    private val deviceStore =
        MemoryDeviceCredentialStore().apply { stored = StoredDevice.Present("device-fixture", "test-only.install.device-a") }
    private val device =
        DeviceRegistry(
            platform = ClientPlatformRaw.ANDROID,
            idReader = { null },
            store = deviceStore,
            privacy = { PrivacyStateRaw.CONSENTED },
            client = { error("no network in this test") },
            epochs = { CallEpochs(0, 0) },
        )
    private val queueFile = Box()
    private val tracker =
        TrackerQueue(
            readFile = { queueFile.value },
            writeFile = { queueFile.value = it },
            sink = { false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "device-fixture",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "session-test",
            clock = { 0L },
            canUpload = { false },
        )
    private val lkgFile = Box()
    private var fetches = 0
    private val environmentGate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.apply { settleAtStartup() }
    private var registrations = 0
    private val gateDuringRegistration = mutableListOf<Boolean>()
    private val privacyGate: PrivacyGate by lazy {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.PRIVACY_DECISION, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { 1_000L }, bundledPolicyVersion = "1").apply {
            withdrawalCleanup = {}
            retryScheduler = RetryScheduler { _, _ -> }
            registerDeviceStep { onResult ->
                registrations += 1
                gateDuringRegistration += environmentGate.isSettled
                onResult(true)
            }
        }
    }
    private val config =
        AppConfig(
            ConfigStore(
                readFile = { lkgFile.value },
                writeFile = { lkgFile.value = it },
                fetcher = { _, done ->
                    fetches += 1
                    done(ConfigReply(ConfigReply.OK, """{"dict_version":"env-a"}""", "\"v1\""))
                },
                clock = { 0L },
                bundled = """{"dict_version":"bundled"}""",
            ),
            environmentSettled = { true },
        )

    private fun <T> provider(value: T) = Provider { value }

    @Test
    fun withdrawalCleanupCancelsInFlightCallsFirst() {
        val inFlight = InFlightCalls()
        val registry = withdrawalCleaners(inFlight)
        assertEquals("in_flight_calls", registry.registeredNames().first())
        val before = inFlight.epochs()
        registry.cleanAll()
        assertEquals(before.privacy + 1, inFlight.epochs().privacy)
    }

    @Test
    fun withdrawalCleanupClearsDeviceCredentialsAndTrackerQueue() {
        val registry = withdrawalCleaners(InFlightCalls())
        assertEquals("device-fixture", device.deviceId)
        assertTrue(tracker.track(ClientEvents.appLaunch(page = "Home")))
        assertEquals(1, tracker.queueSize)

        registry.cleanAll()

        assertNull(device.deviceId)
        assertNull(device.installSecret)
        assertEquals(StoredDevice.None, deviceStore.stored)
        assertEquals(0, tracker.queueSize)
        assertNull("队列文件也删掉", queueFile.value)
    }

    @Test
    fun environmentSwitchCleanupCancelsInFlightCallsFirst() {
        val inFlight = InFlightCalls()
        val tokens = MemorySessionTokens("access", "refresh", "full", "account")
        val registry = environmentCleaners(tokens, inFlight)
        val first = registry.registered().first()
        val before = inFlight.epochs()
        first.clean(AppEnvironment.TEST, AppEnvironment.STAGING)
        assertEquals("the first cleaner only invalidates in-flight calls", before.environment + 1, inFlight.epochs().environment)
        assertEquals("tokens are cleared by a later cleaner", "access", tokens.accessToken)
    }

    @Test
    fun environmentSwitchCleanupClearsLkgDeviceAndTrackerQueue() {
        config.startIfReady()
        assertEquals("current", config.store.sourceRaw)
        assertTrue("拉取成功写了 LKG", lkgFile.value != null)
        assertTrue(tracker.track(ClientEvents.appLaunch(page = "Home")))

        // 前两项只作废在途请求与桥调用（APP-04）；其后是 APP-03b 登记的设备、LKG、队列文件、内存队列
        environmentCleaners(MemorySessionTokens(), InFlightCalls()).registered().take(6).forEach {
            it.clean(AppEnvironment.TEST, AppEnvironment.STAGING)
        }

        assertNull("LKG 文件已删", lkgFile.value)
        assertEquals("回到包内配置", "bundled", config.store.sourceRaw)
        assertNull(device.deviceId)
        assertEquals(StoredDevice.None, deviceStore.stored)
        assertEquals(0, tracker.queueSize)
    }

    /**
     * R1-05、lessons 10：运行中切换环境——开始时同步关环境闸门，清理与提交期间不注册设备、不拉配置；新环境提交之后开闸门，
     * 重新开始设备注册（PrivacyGate 已初始化过，initializeIfReady 不会再注册）并冷启动拉配置。
     */
    @Test
    fun runtimeEnvironmentSwitchRestartsRegistrationAndConfigOnlyAfterCommit() {
        privacyGate.agree()
        privacyGate.onConsented()
        config.startIfReady()
        assertEquals(1, registrations)
        assertEquals(1, fetches)
        val registry = environmentCleaners(MemorySessionTokens(), InFlightCalls())
        val duringSwitch = mutableListOf<String>()
        registry.register { _, _ -> duringSwitch += "gate=${environmentGate.isSettled} registrations=$registrations fetches=$fetches" }

        registry.switching(AppEnvironment.TEST, AppEnvironment.STAGING) {
            duringSwitch += "commit gate=${environmentGate.isSettled}"
        }

        assertEquals(listOf("gate=false registrations=1 fetches=1", "commit gate=false"), duringSwitch)
        assertTrue("切换后闸门重新打开", environmentGate.isSettled)
        assertEquals("新环境重新注册设备", 2, registrations)
        assertEquals("注册时闸门已开（属于新环境）", listOf(true, true), gateDuringRegistration)
        assertEquals("新环境拉配置", 2, fetches)
        assertEquals("current", config.store.sourceRaw)
    }

    /**
     * R2-03：设备已注册 → 切换环境时 device.clear 成功、之后的清理失败（环境保持旧环境）→ 重新开闸，并在旧环境重新开始设备注册
     * 与配置拉取（已被清除的初始化状态要恢复，不能停在 device_not_ready）。
     */
    @Test
    fun r2_03_failedEnvironmentSwitchRestoresRegistrationAndConfigAfterReopening() {
        privacyGate.agree()
        privacyGate.onConsented()
        config.startIfReady()
        assertEquals(1, registrations)
        assertEquals(1, fetches)
        val registry = environmentCleaners(MemorySessionTokens(), InFlightCalls())
        registry.register { _, _ -> error("cleanup failed") }

        assertTrue(runCatching { registry.switching(AppEnvironment.TEST, AppEnvironment.STAGING) {} }.isFailure)

        assertNull("device.clear 已执行", device.deviceId)
        assertTrue(environmentGate.isSettled)
        assertEquals("失败后也重新开始设备注册", 2, registrations)
        assertEquals("注册时闸门已开", listOf(true, true), gateDuringRegistration)
        assertEquals("失败后也重新拉配置（LKG 已被清掉）", 2, fetches)
        assertEquals("current", config.store.sourceRaw)
    }

    /**
     * F-ALL-08（APP-03b 共性第 23 条）：埋点队列的清理在环境清理注册表里（冷启动的环境对账与运行中切换共用），直接删磁盘上的
     * 队列文件与残留的 .tmp，不依赖队列对象能否建出来（覆盖安装后冷启动对账时队列对象还没建，也要清掉旧环境的队列）。
     */
    @Test
    fun fAll08_environmentCleanupDeletesQueueFileAndTempEvenWhenQueueCannotBeBuilt() {
        val file = trackerQueueFile(context)
        file.write("[\"old-environment-event\"]")
        val dir = File(context.filesDir, "couli_tracker")
        File(dir, "queue.json.tmp").writeText("half-written")
        val registry =
            AppModule.environmentSwitchCleaners(
                context,
                MemorySessionTokens(),
                NoCipher,
                InFlightCalls(),
                provider(device),
                provider(config),
                Provider { error("queue not constructed during startup reconcile") },
                provider(environmentGate),
                provider(privacyGate),
                BridgeHostRegistry(),
            )

        assertTrue(runCatching { registry.cleanAll(AppEnvironment.TEST, AppEnvironment.STAGING) }.isFailure)

        assertNull("队列文件已删", file.read())
        assertTrue("没有残留的 .tmp", !File(dir, "queue.json.tmp").exists() && !File(dir, "queue.json").exists())
    }

    /** lessons 17：关环境闸门的同时（任何清理、等待之前）同步作废网络的环境世代。 */
    @Test
    fun lessons17_switchStartInvalidatesInFlightCallsBeforeAnyCleaner() {
        val inFlight = InFlightCalls()
        val registry = environmentCleaners(MemorySessionTokens(), inFlight)
        val before = inFlight.epochs().environment
        val seen = mutableListOf<String>()
        registry.addSwitchListener(
            object : EnvironmentSwitchListener {
                override fun onSwitchStarting() {
                    seen += "start gate=${environmentGate.isSettled} epoch=${inFlight.epochs().environment - before}"
                }

                override fun onSwitchFinished(committed: Boolean) = Unit
            },
        )

        registry.switching(AppEnvironment.TEST, AppEnvironment.STAGING) {}

        assertEquals(listOf("start gate=false epoch=1"), seen)
    }

    private fun withdrawalCleaners(inFlight: InFlightCalls) =
        AppModule.credentialCleaners(
            context,
            MemorySessionTokens(),
            inFlight,
            provider(device),
            provider(tracker),
            BridgeHostRegistry(),
        )

    private fun environmentCleaners(
        tokens: MemorySessionTokens,
        inFlight: InFlightCalls,
    ) = AppModule.environmentSwitchCleaners(
        context,
        tokens,
        NoCipher,
        inFlight,
        provider(device),
        provider(config),
        provider(tracker),
        provider(environmentGate),
        provider(privacyGate),
        BridgeHostRegistry(),
    )
}
