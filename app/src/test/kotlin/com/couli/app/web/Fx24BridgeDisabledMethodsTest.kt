package com.couli.app.web

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.web.Fx24BridgeFixture.Companion.CLAIM_BODY
import com.couli.app.web.Fx24BridgeFixture.Companion.CLAIM_PARAMS
import com.couli.app.web.Fx24BridgeFixture.Companion.CLIPBOARD
import com.couli.app.web.Fx24BridgeFixture.Companion.SIGNED_REQUEST
import com.couli.app.web.Fx24BridgeFixture.Companion.WAIT_S
import com.couli.core.bridge.BridgeCode
import com.couli.core.clientcore.ConfigReply
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/**
 * F-X-24 B1–B7：ConfigStore → AppWeb.newHost → BridgeHost.handle → 真实能力 / ApiClient / TrackerQueue。
 * H5 has() 用原生声明的 methods 判断；B2 / B6 在本端核对该能力来源，网页传输另由 feature:web 覆盖。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class Fx24BridgeDisabledMethodsTest {
    private val fixture = Fx24BridgeFixture()

    @Before
    fun start() = fixture.start()

    @After
    fun close() = fixture.close()

    @Test
    fun b1DisabledClipboardRejectsBeforeTheHandlerAndRecordsBridgeCall() {
        fixture.updateDisabled(CLIPBOARD)
        val reply = fixture.call(CLIPBOARD)
        assertEquals("禁用不能进入 AppWeb.Services.clipboardRead", 0, fixture.clipboardHandlerEntries)
        assertEquals("禁用不能触碰系统剪贴板", 0, fixture.system.reads)
        assertDisabled(reply)
        assertRejectedEvent(CLIPBOARD)
    }

    @Test
    fun b2HasCapabilitySourceExcludesDisabledMethod() {
        assertTrue("前提：本端原来支持 clipboard.read", CLIPBOARD in fixture.host.declaredMethods)
        fixture.updateDisabled(CLIPBOARD)
        assertFalse("B2：SDK has() 的原生 methods 不得包含禁用方法", CLIPBOARD in fixture.host.declaredMethods)
        fixture.updateDisabled()
        assertTrue("解除禁用后能力探测恢复", CLIPBOARD in fixture.host.declaredMethods)
    }

    @Test
    fun b3OtherSupportedMethodStillSucceeds() {
        fixture.updateDisabled(CLIPBOARD)
        assertTrue("app.getEnv 的能力仍保留", "app.getEnv" in fixture.host.declaredMethods)
        assertEquals(BridgeCode.OK, fixture.call("app.getEnv").code())
        assertEquals(0, fixture.clipboardHandlerEntries)
        assertEquals(0, fixture.server.requestCount)
        assertTrue(fixture.bridgeEvents().isEmpty())
    }

    @Test
    fun b4DisabledSignedRequestMakesZeroServerRequests() {
        fixture.updateDisabled(SIGNED_REQUEST)
        val reply = fixture.call(SIGNED_REQUEST, CLAIM_PARAMS)
        assertEquals("禁用不能进入真实签名请求处理器", 0, fixture.probe.signedRequestEntries)
        assertEquals("B4：MockWebServer 实际收包数必须为零", 0, fixture.server.requestCount)
        assertDisabled(reply)
        assertRejectedEvent(SIGNED_REQUEST)

        // 同一宿主解除禁用，同一合法体必须真的发送，证明零请求不是登录 / 手势 / 强更 / 白名单挡住。
        fixture.updateDisabled()
        assertEquals(BridgeCode.OK, fixture.call(SIGNED_REQUEST, CLAIM_PARAMS).code())
        assertClaimReachedServerOnce()
    }

    @Test
    fun b5RemovalTakesEffectOnTheVeryNextCallInTheSameHost() {
        val originalHost = fixture.host
        fixture.updateDisabled(CLIPBOARD)
        assertDisabled(fixture.call(CLIPBOARD))
        assertEquals(0, fixture.clipboardHandlerEntries)
        fixture.updateDisabled()
        assertTrue("不能靠重建桥刷新配置", fixture.host === originalHost)
        assertEquals(BridgeCode.OK, fixture.call(CLIPBOARD).code())
        assertEquals(1, fixture.clipboardHandlerEntries)
        assertEquals(1, fixture.system.reads)
        assertRejectedEvent(CLIPBOARD)
    }

    @Test
    fun b6UnknownDisabledNameDoesNotAffectSupportedMethods() {
        fixture.updateDisabled("unknown.method")
        assertTrue("B6：SDK has() 的原生 methods 仍包含 clipboard.read", CLIPBOARD in fixture.host.declaredMethods)
        assertFalse("不应凭配置创造新能力", "unknown.method" in fixture.host.declaredMethods)
        assertEquals(BridgeCode.OK, fixture.call(CLIPBOARD).code())
        assertEquals(1, fixture.clipboardHandlerEntries)
        assertEquals(1, fixture.system.reads)
        assertTrue(fixture.bridgeEvents().isEmpty())
    }

    @Test
    fun b7UnknownUnlistedMethodKeepsItsExistingErrorCode() {
        val baseline = fixture.call("foo.bar").code()
        assertEquals(BridgeCode.NOT_SUPPORTED, baseline)
        fixture.updateDisabled(CLIPBOARD)
        assertEquals(baseline, fixture.call("foo.bar").code())
        assertEquals(0, fixture.clipboardHandlerEntries)
        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun disabledLoginRequiredMethodRejectsBeforeLoginGuardAndNeverStartsLogin() {
        val method = "auth.getH5Token"
        fixture.clearSession()
        fixture.updateDisabled(method)

        // M1：仅保留 guarded 复核时，准入的登录守卫会先返回 90401，不能得到这个禁用响应。
        assertDisabled(fixture.call(method))
        assertEquals("禁用调用不能触发任何登录入口", 0, fixture.probe.loginEntries)
        assertEquals(0, fixture.server.requestCount)
        assertRejectedEvent(method)

        // 同一宿主、同一游客会话解除禁用，确认该方法确实需要登录，且没有隐式补登录。
        fixture.updateDisabled()
        assertEquals(90401, BridgeCode.NOT_LOGGED_IN)
        assertEquals(BridgeCode.NOT_LOGGED_IN, fixture.call(method).code())
        assertEquals("未登录响应也不能擅自启动登录流程", 0, fixture.probe.loginEntries)
        assertEquals(0, fixture.server.requestCount)
    }

    @Test
    fun disabledUnknownMethodRejectsBeforeUnsupportedMethodLookup() {
        val method = "foo.bar"
        fixture.updateDisabled(method)

        // M1：未知方法不会进入 guarded，只有真实准入层能在契约查询前给出禁用响应。
        assertDisabled(fixture.call(method))
        assertEquals(0, fixture.clipboardHandlerEntries)
        assertEquals(0, fixture.probe.loginEntries)
        assertEquals(0, fixture.server.requestCount)

        // 同一宿主移出名单后仍按未知方法处理，不能凭禁用配置创造新方法。
        fixture.updateDisabled()
        assertEquals(90001, BridgeCode.NOT_SUPPORTED)
        assertEquals(BridgeCode.NOT_SUPPORTED, fixture.call(method).code())
    }

    @Test
    fun validSignedRequestControlReallyReachesTheServer() {
        // 独立正对照：即使 B4 在旧实现的零请求断言处变红，这个用例仍可验证夹具可通。
        assertEquals(BridgeCode.OK, fixture.call(SIGNED_REQUEST, CLAIM_PARAMS).code())
        assertClaimReachedServerOnce()
    }

    @Test
    fun disablingSignedRequestWhileAwaitingUpdateRejectsBeforeHandlerAndNetwork() {
        fixture.updateDisabled()
        assertEquals(JsonArray(emptyList()), fixture.effectiveConfig["bridge_disabled_methods"])
        val originalHost = fixture.host
        fixture.holdUpdateCheck()
        val pending = fixture.beginCall(SIGNED_REQUEST, CLAIM_PARAMS)
        fixture.assertWaitingForUpdate(pending)

        // 已进入真实 awaitUpdate、尚未执行处理器时，真实 ConfigStore 发布新的生效配置。
        fixture.updateDisabled(SIGNED_REQUEST)
        fixture.assertWaitingForUpdate(pending)
        fixture.releaseUpdateCheck()

        assertDisabled(pending.awaitReply())
        assertSame("等待中的调用仍属于同一宿主", originalHost, fixture.host)
        assertEquals("等待结束后也不能进入签名请求处理器", 0, fixture.probe.signedRequestEntries)
        assertEquals("等待结束后 MockWebServer 仍未收到请求", 0, fixture.server.requestCount)
        assertRejectedEvent(SIGNED_REQUEST)
    }

    @Test
    fun awaitingUpdateWithoutDisablingStillRunsTheRealSignedRequestOnce() {
        fixture.updateDisabled()
        fixture.holdUpdateCheck()
        val pending = fixture.beginCall(SIGNED_REQUEST, CLAIM_PARAMS)
        fixture.assertWaitingForUpdate(pending)
        fixture.releaseUpdateCheck()

        // 同一路等待正对照：排除夹具不会恢复、观察器吞掉处理器或其他守卫一律拒绝的假通过。
        assertEquals(BridgeCode.OK, pending.awaitReply().code())
        assertClaimReachedServerOnce()
        assertTrue(fixture.bridgeEvents().isEmpty())
    }

    @Test
    fun appWebWarnsOncePerInvalidConfigAndNeverLogsItsValues() {
        ShadowLog.clear()
        val firstValue = "FX24-BAD-FIRST-private-value"
        val secondValue = "FX24-BAD-SECOND-private-value"
        val firstConfig = fixture.updateDisabledValue(JsonPrimitive(firstValue))
        exerciseInvalidConfigReads()
        assertSame("重复读取没有换配置对象", firstConfig, fixture.effectiveConfig)
        assertConfigWarnings(1, firstValue)

        val secondConfig = fixture.updateDisabledValue(JsonArray(listOf(JsonPrimitive(CLIPBOARD), JsonPrimitive(secondValue))))
        assertNotSame(firstConfig, secondConfig)
        assertNotEquals("第二份坏配置内容也不同", firstConfig, secondConfig)
        exerciseInvalidConfigReads()
        assertSame(secondConfig, fixture.effectiveConfig)
        assertConfigWarnings(2, firstValue, secondValue, CLIPBOARD)
    }

    @Test
    fun failedOrNotModifiedConfigRefreshKeepsTheEffectiveDisabledList() {
        fixture.updateDisabled(CLIPBOARD)
        for (kind in listOf(ConfigReply.FAILURE, ConfigReply.NOT_MODIFIED)) {
            fixture.refreshWithoutChangingConfig(kind)
            assertDisabled(fixture.call(CLIPBOARD))
            assertEquals(0, fixture.clipboardHandlerEntries)
            assertFalse(CLIPBOARD in fixture.host.declaredMethods)
        }
    }

    private fun assertClaimReachedServerOnce() {
        assertEquals("观察器必须看到一次真实处理器调用", 1, fixture.probe.signedRequestEntries)
        assertEquals(1, fixture.server.requestCount)
        val request = checkNotNull(fixture.server.takeRequest(WAIT_S, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/v1/orders/claims", request.requestUrl?.encodedPath)
        assertEquals(CLAIM_BODY, Json.parseToJsonElement(request.body.readUtf8()))
    }

    private fun exerciseInvalidConfigReads() {
        repeat(3) {
            assertTrue("坏配置整键按空处理", CLIPBOARD in fixture.host.declaredMethods)
            assertEquals(BridgeCode.OK, fixture.call("app.getEnv").code())
        }
    }

    private fun assertConfigWarnings(
        expected: Int,
        vararg forbiddenValues: String,
    ) {
        // 按 AppWeb tag 读取全部日志，不用键名过滤，以免漏过错误地只记录了配置值的日志。
        val logs = ShadowLog.getLogsForTag("AppWeb")
        assertEquals(List(expected) { "invalid config key: bridge_disabled_methods" }, logs.map { it.msg })
        for (entry in logs) {
            assertEquals(Log.WARN, entry.type)
            assertNull("异常也不能夹带配置值", entry.throwable)
            forbiddenValues.forEach { assertFalse("日志不能包含配置值", entry.msg.contains(it)) }
        }
    }

    private fun assertDisabled(reply: JsonObject) {
        assertEquals(90403, BridgeCode.NOT_ALLOWED)
        assertEquals(BridgeCode.NOT_ALLOWED, reply.code())
        assertEquals(
            "method_disabled",
            reply["data"]
                ?.jsonObject
                ?.get("reason")
                ?.jsonPrimitive
                ?.content,
        )
    }

    private fun assertRejectedEvent(method: String) {
        val events = fixture.bridgeEvents()
        assertEquals("拒绝只记一条 bridge_call", 1, events.size)
        assertEquals(
            method,
            events
                .single()
                .getValue("method")
                .jsonPrimitive.content,
        )
        assertEquals(
            BridgeCode.NOT_ALLOWED,
            events
                .single()
                .getValue("code")
                .jsonPrimitive.int,
        )
    }

    private fun JsonObject.code(): Int = getValue("code").jsonPrimitive.int
}
