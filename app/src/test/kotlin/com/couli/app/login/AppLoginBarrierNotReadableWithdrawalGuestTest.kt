package com.couli.app.login

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
import com.couli.app.clipboard.ClipboardReviewCipher0724
import com.couli.app.clipboard.ClipboardReviewLogin0724Graph
import com.couli.app.clipboard.ClipboardReviewLogin0724Wire
import com.couli.app.web.BridgeHostRegistry
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.network.CallEpochs
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryDeviceCredentialStore
import com.couli.core.network.StoredDevice
import com.couli.core.router.SessionState
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D10-2008 ②（BARRIER fix12 第 ③ 点的界面层落点）：撤回同意这条生产路径——AppModule.credentialCleaners 注册的会话令牌清理项
 * （AppModule.kt:425 `tokens.clear()`，不经 ApiClient、网络层不发会话通知）清掉令牌，隐私结论变化后 AppModule.appLogin 接的
 * `privacy.onConclusionChanged { login.syncSession() }`（AppModule.kt:778）把 Router 的会话状态同步为游客。
 * 装配：ClipboardReviewLogin0724Graph（真实 AppModule.apiClient / appLogin、AppPrivacy、AppRouting）；撤回清理的注册表接上真实的
 * AppModule.credentialCleaners，测试不手调 syncSession、不手设 Router 会话状态。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [ClipboardReviewLogin0724Wire::class, ClipboardReviewCipher0724::class])
class AppLoginBarrierNotReadableWithdrawalGuestTest {
    @Test
    fun withdrawalCleanerClearsTokensAndConclusionSyncMovesRouterSessionToGuest() {
        ClipboardReviewLogin0724Graph().use { graph ->
            val production =
                AppModule.credentialCleaners(
                    graph.context,
                    graph.tokens,
                    InFlightCalls(),
                    Provider { device() },
                    Provider { tracker() },
                    BridgeHostRegistry(),
                )
            graph.credentialCleaners.register(PRODUCTION_CLEANERS) { production.cleanAll() }

            graph.loginAs(ClipboardReviewLogin0724Wire.PHONE_A)
            assertNotNull("前提：已登录、有令牌", graph.tokens.accessToken)
            assertNotNull("前提：网络层有有效会话", graph.client.activeSessionScope())
            assertNotEquals("前提：Router 不是游客", SessionState.GUEST, graph.routing.session.value)
            val generation = graph.tokens.generation

            graph.privacy.withdraw()
            graph.settle()

            assertNull("撤回清理器清掉了 access_token", graph.tokens.accessToken)
            assertNull("撤回清理器清掉了 refresh_token", graph.tokens.refreshToken)
            assertNull("撤回清理器清掉了账号", graph.tokens.userId)
            assertEquals("清除推进了一次会话世代", generation + 1, graph.tokens.generation)
            assertNull("网络层不再有有效会话", graph.client.activeSessionScope())
            assertEquals("隐私结论变化后 login.syncSession() 把 Router 会话状态同步为游客", SessionState.GUEST, graph.routing.session.value)
        }
    }

    private fun device(): DeviceRegistry =
        DeviceRegistry(
            platform = ClientPlatformRaw.ANDROID,
            idReader = { null },
            store = MemoryDeviceCredentialStore().apply { stored = StoredDevice.Present("device-fixture", "test-only.install.device-a") },
            privacy = { PrivacyStateRaw.CONSENTED },
            client = { error("no network in this test") },
            epochs = { CallEpochs(0, 0) },
        )

    private fun tracker(): TrackerQueue =
        TrackerQueue(
            readFile = { null },
            writeFile = {},
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

    private companion object {
        const val PRODUCTION_CLEANERS = "appmodule_credential_cleaners"
    }
}
