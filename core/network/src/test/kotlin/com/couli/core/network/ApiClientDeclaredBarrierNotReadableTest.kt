package com.couli.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用例 6：D10-1939 / D10-1940 (二)，声明照发不越过设备凭据屏障；窗口内只有 config 匿名照发。
 * D10-1939 / D10-1940：均未固定账号，不能用于放宽真实绑手机流程的账号核对；开闸后另一次调用照常。
 */
class ApiClientDeclaredBarrierNotReadableTest {
    // D10-1939 / D10-1940：登录发码窗口内 device_not_ready、零发送，开闸后带设备标识和签名、不带令牌。
    @Test
    fun declaredLoginSmsRejectsUntilDeviceCredentialsAreReadable() {
        assertBlockedThenRecovered("sendSmsCode", """{"phone":"13800005678","purpose":"login"}""", signed = true)
    }

    // D10-1939 / D10-1940：未固定账号的绑手机发码同样不能绕过设备屏障和签名。
    @Test
    fun declaredBindSmsWithoutPinnedAccountRejectsUntilDeviceCredentialsAreReadable() {
        assertBlockedThenRecovered("sendSmsCode", """{"phone":"13800005678","purpose":"bind"}""", signed = true)
    }

    // D10-1939 / D10-1940：登录授权尝试窗口内零发送，开闸后仍须设备标识和签名。
    @Test
    fun declaredLoginOauthAttemptRejectsUntilDeviceCredentialsAreReadable() {
        assertBlockedThenRecovered("createOauthAttempt", """{"provider":"wechat","purpose":"login"}""", signed = true)
    }

    // D10-1939 / D10-1940：config 在窗口内匿名恰发一次，解除屏障后新调用也照常。
    @Test
    fun declaredConfigSendsAnonymouslyInsideBarrierAndAfterReopening() {
        val rig = withoutAccessToken()
        val call = ApiCall("getConfig", sendsWhenIdentityUnknown = true)

        val inside = rig.inClosedWindow(call)
        val insideCount = rig.sentCount
        rig.settleEnvironment()
        val recovered = rig.execute(call)

        assertEquals(listOf(BarrierNotReadableOutcome.sent(), BarrierNotReadableOutcome.sent()), listOf(inside, recovered))
        assertEquals("窗口内恰发一次，不延后到开闸", 1, insideCount)
        assertEquals("恢复后只新增一次", 2, rig.sentCount)
        assertEquals("device-fixture", rig.sentRequests.last().header(HeaderNames.DEVICE_ID))
    }

    // D10-1939 / D10-1940：1940 改正 1939 的 events 旧句，窗口内也必须零发送。
    @Test
    fun declaredEventsRejectUntilDeviceCredentialsAreReadable() {
        assertBlockedThenRecovered("ingestEvents", """{"events":[{"event":"app_open","client_at":"2026-11-17T10:00:00+08:00"}]}""")
    }

    // D10-1939 / D10-1940：撤回以设备为主体，没设备标识不发；交回现有未发出结果，开闸后调用方可再发。
    @Test
    fun declaredPrivacyWithdrawalReturnsLocalNotSentUntilDeviceCredentialsAreReadable() {
        assertBlockedThenRecovered(
            "recordConsent",
            """{"type":"privacy","version":3,"accepted":false,"channel":"privacy_center","client_at":"2026-11-17T10:00:00+08:00"}""",
        )
    }

    // D10-1939 / D10-1940：POST /v1/consents 全部零发送，不只拦隐私撤回；普通同意也交回本地未发出结果。
    @Test
    fun privacyAcceptanceReturnsLocalNotSentUntilDeviceCredentialsAreReadable() {
        assertBlockedThenRecovered(
            "recordConsent",
            """{"type":"privacy","version":3,"accepted":true,"channel":"privacy_center","client_at":"2026-11-17T10:00:00+08:00"}""",
            declared = false,
        )
    }

    private fun assertBlockedThenRecovered(
        operation: String,
        body: String,
        signed: Boolean = false,
        declared: Boolean = true,
    ) {
        val rig = if (declared) withoutAccessToken() else BarrierNotReadableFixture(MemorySessionTokens())
        val call = ApiCall(operation, body = body, sendsWhenIdentityUnknown = declared)
        val blocked = rig.inClosedWindow(call)
        val localResult = rig.lastResult
        val insideCount = rig.sentCount
        rig.settleEnvironment()

        val recovered = rig.execute(call)

        assertEquals(
            "D10-1939 / D10-1940：窗口内未发出；开闸后主动新调用成功，不新增自动重试",
            listOf(BarrierNotReadableOutcome.rejected(LocalRejectReason.DEVICE_NOT_READY), BarrierNotReadableOutcome.sent(signed = signed)),
            listOf(blocked, recovered),
        )
        assertEquals("调用方拿到现有本地未发出结果", ApiResult.LocalReject(LocalRejectReason.DEVICE_NOT_READY), localResult)
        assertEquals("窗口内零发送", 0, insideCount)
        assertEquals("只在恢复后的新调用发送一次", 1, rig.sentCount)
        assertEquals("恢复后设备级请求仍带设备标识", "device-fixture", rig.sentRequests.single().header(HeaderNames.DEVICE_ID))
    }

    // D10-1939 / D10-1940 (一)：恢复后设备可读但只有 refresh、没有 access，声明请求带设备凭据、不带令牌。
    private fun withoutAccessToken(): BarrierNotReadableFixture {
        val rig = BarrierNotReadableFixture(MemorySessionTokens(null, "refresh-A", SessionScopeRaw.FULL, BarrierNotReadableFixture.ACCOUNT))
        assertEquals("窗口之前仍形成真实 bound", BarrierNotReadableFixture.ACCOUNT, rig.api.activeUserId())
        return rig
    }
}
