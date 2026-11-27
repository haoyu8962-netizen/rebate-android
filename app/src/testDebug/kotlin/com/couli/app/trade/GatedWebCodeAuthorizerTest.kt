package com.couli.app.trade

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugMockGate
import com.couli.app.RawFakeServerSwitch
import com.couli.app.buildDefaultEnvironment
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import com.couli.feature.trade.AuthCredential
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeAuthorizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 记下调用的授权器替身。 */
private class CountingAuthorizer(
    override val available: Boolean,
    private val result: AuthStepResult,
) : WebCodeAuthorizer {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult {
        calls += authUrl to state
        return result
    }
}

/**
 * FU-AND-TB-WEBCODE-A 测试：debug / staging 的闸门委派（设计稿 §3.2、§7；D10-3561）。假服务闸门开 → DebugWebCodeAuthorizer；
 * 关 → 真实授权器；每次 authorize 现判闸门；available = gate() || real.available。另用真实 [DebugMockGate] 核对
 * variantWebCodeAuthorizer(context, real) 的装配。DebugWebCodeAuthorizer 本身不改（FollowupAnd0714DebugGateTest 照旧）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GatedWebCodeAuthorizerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val switcher = EnvironmentSwitcher(context, buildDefaultEnvironment(), EnvironmentSwitchCleanerRegistry())
    private val ready = DebugMockGate.environmentReady
    private val debugResult = AuthStepResult.Credential(AuthCredential.WebCode("from-debug"))
    private val realResult = AuthStepResult.Credential(AuthCredential.WebCode("from-real"))

    @Before
    fun setUp() {
        DebugMockGate.environmentReady = { true }
        switcher.switchTo(AppEnvironment.TEST)
    }

    @After
    fun tearDown() {
        setSwitch(false)
        switcher.switchTo(AppEnvironment.TEST)
        DebugMockGate.environmentReady = ready
    }

    @OptIn(RawFakeServerSwitch::class)
    private fun setSwitch(on: Boolean) {
        DebugMockGate.setSwitch(context, on)
    }

    @Test
    fun g01_gateOpenDelegatesToDebug() =
        runTest {
            val debug = CountingAuthorizer(available = true, result = debugResult)
            val real = CountingAuthorizer(available = true, result = realResult)
            val result = GatedWebCodeAuthorizer({ true }, debug, real).authorize(URL, STATE)
            assertEquals("闸门开：交给假实现", listOf(URL to STATE), debug.calls)
            assertTrue("闸门开：不碰真实实现", real.calls.isEmpty())
            assertEquals(debugResult, result)
        }

    @Test
    fun g02_gateClosedDelegatesToReal() =
        runTest {
            val debug = CountingAuthorizer(available = false, result = debugResult)
            val real = CountingAuthorizer(available = true, result = realResult)
            val result = GatedWebCodeAuthorizer({ false }, debug, real).authorize(URL, STATE)
            assertEquals("闸门关：交给真实授权器", listOf(URL to STATE), real.calls)
            assertTrue("闸门关：不碰假实现", debug.calls.isEmpty())
            assertEquals(realResult, result)
        }

    @Test
    fun g03_gateIsReadOnEveryCall() =
        runTest {
            var open = true
            val debug = CountingAuthorizer(available = true, result = debugResult)
            val real = CountingAuthorizer(available = true, result = realResult)
            val gated = GatedWebCodeAuthorizer({ open }, debug, real)
            assertEquals(debugResult, gated.authorize(URL, "s1"))
            open = false
            assertEquals("同一个实例：闸门关了改走真实实现", realResult, gated.authorize(URL, "s2"))
            open = true
            assertEquals("再开回到假实现", debugResult, gated.authorize(URL, "s3"))
            assertEquals(listOf(URL to "s1", URL to "s3"), debug.calls)
            assertEquals(listOf(URL to "s2"), real.calls)
        }

    @Test
    fun g04_availableIsGateOrReal() {
        val debug = CountingAuthorizer(available = true, result = debugResult)
        val realOn = CountingAuthorizer(available = true, result = realResult)
        val realOff = CountingAuthorizer(available = false, result = realResult)
        assertTrue("闸门关、真实可用 → 可用", GatedWebCodeAuthorizer({ false }, debug, realOn).available)
        assertTrue("闸门开 → 可用", GatedWebCodeAuthorizer({ true }, debug, realOff).available)
        assertFalse("两者都不行 → 不可用", GatedWebCodeAuthorizer({ false }, debug, realOff).available)
    }

    @Test
    fun g05_variantWithFakeServerOnUsesTheDebugAuthorizer() =
        runTest {
            setSwitch(true)
            val real = CountingAuthorizer(available = true, result = realResult)
            val result = variantWebCodeAuthorizer(context, real).authorize(URL, STATE)
            assertTrue("假服务开：DebugWebCodeAuthorizer 给假授权码：$result", result is AuthStepResult.Credential)
            assertEquals("debug-web-code", ((result as AuthStepResult.Credential).value as AuthCredential.WebCode).code)
            assertTrue("假服务开：不打开真实授权页", real.calls.isEmpty())
        }

    @Test
    fun g06_variantWithFakeServerOffUsesTheRealAuthorizer() =
        runTest {
            setSwitch(false)
            val real = CountingAuthorizer(available = true, result = realResult)
            val authorizer = variantWebCodeAuthorizer(context, real)
            assertTrue("假服务关、真实可用：本端可执行（不再等同 release 的不可用）", authorizer.available)
            assertEquals("假服务关：走真实授权器", realResult, authorizer.authorize(URL, STATE))
            assertEquals(listOf(URL to STATE), real.calls)
        }

    @Test
    fun g07_variantSwitchingToProdUsesTheRealAuthorizer() =
        runTest {
            setSwitch(true)
            val real = CountingAuthorizer(available = true, result = realResult)
            val authorizer = variantWebCodeAuthorizer(context, real)
            switcher.switchTo(AppEnvironment.PROD)
            assertEquals("同一个实例切到 prod：闸门关，走真实授权器", realResult, authorizer.authorize(URL, STATE))
            assertEquals(listOf(URL to STATE), real.calls)
        }

    private companion object {
        const val URL = "https://oauth.taobao.com/authorize?state=st1&view=wap"
        const val STATE = "st1"
    }
}
