package com.couli.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.AppEnvironment
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * lessons 35：网络失效清理（取消在途请求）在 App 的真实组合处（AppModule）排在撤回同意与环境切换两个注册表的第一位。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NetworkCleanupWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    @Test
    fun withdrawalCleanupCancelsInFlightCallsFirst() {
        val inFlight = InFlightCalls()
        val registry = AppModule.credentialCleaners(context, MemorySessionTokens(), inFlight)
        assertEquals("in_flight_calls", registry.registeredNames().first())
        val before = inFlight.epochs()
        registry.cleanAll()
        assertEquals(before.privacy + 1, inFlight.epochs().privacy)
    }

    @Test
    fun environmentSwitchCleanupCancelsInFlightCallsFirst() {
        val inFlight = InFlightCalls()
        val tokens = MemorySessionTokens("access", "refresh", "full", "account")
        val registry = AppModule.environmentSwitchCleaners(context, tokens, NoCipher, inFlight)
        val first = registry.registered().first()
        val before = inFlight.epochs()
        first.clean(AppEnvironment.TEST, AppEnvironment.STAGING)
        assertEquals("the first cleaner only invalidates in-flight calls", before.environment + 1, inFlight.epochs().environment)
        assertEquals("tokens are cleared by a later cleaner", "access", tokens.accessToken)
    }
}
