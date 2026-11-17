package com.couli.app.me

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.couli.app.CallSiteIdentityPin
import com.couli.app.CallSiteTokens
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.me.MeLocalStore
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.push.PushTokenSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import java.util.Collections
import okhttp3.mockwebserver.MockWebServer

/** IdentityGaps 第 1、3 项共用装配；沿用 ConsentCallSiteIdentityPinTest，只替换系统端口与设备凭据。 */
internal class IdentityGapsConsentSupport(
    server: MockWebServer,
    val tokens: CallSiteTokens = CallSiteIdentityPin.memberA(),
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val device = Device()
    private val gate =
        PrivacyGate(MemoryStore(), clock = { NOW }, bundledPolicyVersion = "3").apply {
            retryScheduler = RetryScheduler { _, _ -> }
            withdrawalCleanup = {
                events += "clear_tokens"
                tokens.inner.clear()
                events += "clear_device_id"
                device.deviceId = null
                events += "clear_install_secret"
                device.installSecret = null
            }
        }
    val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { NOW }).also { it.coldStart() }
    val routing =
        AppRouting(
            context,
            EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
            clock = { NOW },
            initialPrivacy = privacy.conclusion,
            initialUpdate = UpdateCheck.OK,
        ).also {
            privacy.onConclusionChanged { state -> it.privacy.value = state }
            it.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
    val client =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = device,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )

    fun me(): AppMe =
        AppMe(
            client = client,
            tokens = tokens,
            routing = routing,
            privacy = privacy,
            store = null,
            login = { null },
            updateCheck = { UpdateCheck.OK },
            serverForced = {},
            writer = { false },
            local = MeLocalStore(context).also { it.clearAll() },
            mask = null,
            system = NoSystem(),
            pushSource = NoPush,
            nickname = null,
            notify = null,
            clock = { NOW },
        )

    class Device : DeviceCredentials {
        @Volatile override var deviceId: String? = CallSiteIdentityPin.DEVICE_ID

        @Volatile override var installSecret: String? = "install-fixture"
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private inner class NoSystem : MeSystemPorts {
        override fun pushStatus(): String = "not_asked"

        override fun pushDeniedAt(): Long? = null

        override fun openNotificationSettings() = Unit

        override fun openAppSettings() = Unit

        override fun openExternal(url: String): Boolean = false

        override fun wechatInstalled(): String = "unknown"

        override fun cacheBytes(): Long? = null

        override fun clearCache(): Boolean = true

        override fun clearWebCache(): Boolean = true

        override fun deviceId(): String? = device.deviceId

        override fun osVersion(): String = "TEST_OS"

        override fun model(): String = "TEST_MODEL"
    }

    private object NoPush : PushTokenSource {
        override fun start() = Unit

        override fun stop() = Unit

        override fun current(): String? = null

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    companion object {
        const val NOW = 1_800_000_000_000L
    }
}
