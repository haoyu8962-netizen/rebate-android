package com.couli.core.network

import com.couli.core.generated.ApiOperations
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-r1-codex：S1-1 世代读取旁路、S2-1 刷新误清会话，以及无签名必需登录接口的回归口径。 */
class ApiClientBarrierNotReadableGapsTest {
    // D10-1971：原本可照发的 config 带闸门时改为 call_cancelled；不带闸门的匿名照发另有测试。
    // 合并原 SendGateTest 的重复 config 用例，保留零读取、闸门次数、发送次数及解除后的 Bearer 全部断言。
    @Test
    fun declaredConfigWithSendGateCancelsWithoutReadsInsideBarrierAndSendsAfterRelease() {
        val rig = BarrierNotReadableFixture()
        var gateCalls = 0
        val sendAllowed: () -> Boolean = {
            gateCalls += 1
            true
        }

        // config 明确声明身份未知照发，避免在 attempts 的 sessionReject 提前结束、根本没走到发送闸门。
        fun call() =
            ApiCall(
                "getConfig",
                sendsWhenIdentityUnknown = true,
                ownership = CallOwnership(rig.inFlight.epochs(), sendAllowed = sendAllowed) { true },
            )

        // 请求与归属都在 raise 已关闸、beforePublish 尚未作废网络世代/发布状态时创建。
        val observed = rig.observeClosedWindow { rig.execute(call()) }
        val callsInside = gateCalls
        val sentInside = rig.sentCount
        rig.settleEnvironment()
        // 环境世代已前进，创建有效的新归属；同一个 lambda 原样复用，不能让旧归属先挡掉恢复请求。
        val recovered = rig.execute(call())
        val recoveryGateCalls = gateCalls - callsInside
        val recoverySends = rig.sentCount - sentInside

        // 先走完恢复闭环，再报窗口内红点；避免首个断言失败让恢复段没有执行。
        assertEquals("解除后同一闸门恰好调用一次", 1, recoveryGateCalls)
        assertEquals("解除后恰好发送一次", 1, recoverySends)
        assertEquals(BarrierNotReadableOutcome.sent(BarrierNotReadableFixture.BEARER), recovered)
        observed.assertNoReads()
        assertEquals(
            "D10-1971：带闸门 config 窗口内闸门零调用、零发送、call_cancelled",
            listOf(0, 0, BarrierNotReadableOutcome.rejected(LocalRejectReason.CALL_CANCELLED)),
            listOf(callsInside, sentInside, observed.value),
        )
    }

    @Test
    fun failedSessionClearQuarantinesWithoutReadingGenerationInsideBarrier() {
        val stored = storedAccount()
        val before = stored.snapshot()
        val rig = BarrierNotReadableFixture(stored)
        val epoch = rig.tokens.loginEpoch
        val pause = BarrierNotReadablePause()
        rig.tokens.beforeClear = {
            pause.stopHere()
            error("清会话写盘失败，旧会话仍在")
        }

        val observed = pause.finishInClosedWindow(rig) { rig.api.clearSessionIf(epoch) }

        assertEquals("写入失败仍按原口径清除并隔离", SessionEnd.CLEARED, observed.value)
        assertEquals("确已尝试清除，失败后进入隔离", 1, rig.tokens.clearAttempts)
        assertEquals("隔离入口不发网络", 0, rig.sentCount)
        assertEquals("失败的写入没有删掉原会话", before, stored.snapshot())
        rig.settleEnvironment()
        assertNull("开闸后旧会话仍被隔离，不能恢复使用", rig.api.activeUserId())
        assertEquals("开闸后真实重试隔离删除", 2, rig.tokens.clearAttempts)
        observed.assertNoReads()
    }

    @Test
    fun sessionIdentityInsideBarrierIsUnknownWithoutReadingLoginEpoch() {
        val rig = BarrierNotReadableFixture()
        assertEquals(BarrierNotReadableFixture.ACCOUNT, rig.api.activeUserId())

        val observed = rig.observeClosedWindow { rig.api.sessionIdentity() }

        assertFalse("暂时拒读只能交回未知身份", observed.value.known)
        assertNull(observed.value.account)
        assertNull(observed.value.partition)
        assertFalse(observed.value.partitionKnown)
        assertEquals("身份查询不发网络", 0, rig.sentCount)
        observed.assertNoReads()
    }

    @Test
    fun preparedPrivacyWithdrawalChecksAfterBarrierClosesWithoutReadsOrSending() {
        val pause = BarrierNotReadablePause()
        var prepared: Request? = null
        val rig =
            BarrierNotReadableFixture(beforeAdmission = { request ->
                prepared = request
                pause.stopHere()
            })
        val epoch = rig.tokens.loginEpoch
        // 对照 AppMe.withdrawConsent：固定登录世代的发送闸门，stillOwned 始终为真。
        val call = withdrawal(CallOwnership(rig.inFlight.epochs(), sendAllowed = { rig.tokens.loginEpoch == epoch }) { true })

        val observed = pause.finishInClosedWindow(rig) { rig.execute(call) }

        assertPreparedWithdrawal(checkNotNull(prepared))
        assertEquals("保持原有归属拒绝口径", BarrierNotReadableOutcome.rejected(LocalRejectReason.SESSION_CHANGED), observed.value)
        assertEquals("真实写出前核对拒绝，替身未收到请求", 0, rig.sentCount)
        observed.assertNoReads()
    }

    @Test
    fun preparedRefreshWithheldAtWriteTimeIsTransientAndKeepsStartingSession() {
        val stored = storedAccount()
        val before = stored.snapshot()
        val pause = BarrierNotReadablePause()
        var prepared: Request? = null
        val rig =
            BarrierNotReadableFixture(
                stored,
                beforeAdmission = { request ->
                    if (request.url.encodedPath == "/v1/auth/refresh") {
                        prepared = request
                        pause.stopHere()
                    }
                },
                responseCode = { 10002 },
            )

        val observed = pause.finishInClosedWindow(rig) { rig.api.execute(ApiCall("getMe")) }

        assertEquals("已组好真实刷新请求", "POST", checkNotNull(prepared).method)
        assertTrue("刷新体已带开始那一代的 refresh", body(checkNotNull(prepared)).contains("refresh-A"))
        assertEquals("只有触发 10002 的原调用发出，刷新与重放均零发送", listOf("/v1/me"), rig.sentRequests.map { it.url.encodedPath })
        rig.settleEnvironment()
        assertEquals(
            "同 Unreadable 按暂时失败结算；不能 SESSION_CHANGED 后清掉刷新开始那一代",
            listOf(ApiResult.TransportFailure(LocalRejectReason.SESSION_UNREADABLE, null, null, false), 0, before),
            listOf(observed.value, rig.tokens.clearAttempts, stored.snapshot()),
        )
        assertEquals("屏障解除后仍可取得原账号", BarrierNotReadableFixture.ACCOUNT, rig.api.activeUserId())
        observed.assertNoReads()
    }

    @Test
    fun unpinnedUnsignedLoginPhoneAndRealnameCallsRejectUnreadableWithoutSending() {
        val rig = BarrierNotReadableFixture()
        val calls = listOf(ApiCall("getMe"), ApiCall("getMyInvite"), ApiCall("getPayoutAccount"))
        assertEquals("尚未绑定账号，也未读会话", 0, rig.tokens.reads)
        assertEquals(listOf("login", "phone", "realname"), calls.map { ApiOperations.all.getValue(it.operationId).auth })
        calls.forEach { call ->
            assertNull("调用未固定账号", call.expectedAccount)
            assertFalse("不能靠签名缺设备拦截", ApiOperations.all.getValue(call.operationId).signed)
        }

        val outcomes = rig.inClosedWindow(calls)

        assertEquals(List(calls.size) { BarrierNotReadableOutcome.rejected(LocalRejectReason.SESSION_UNREADABLE) }, outcomes)
        assertEquals("三个 auth 档均本地拒绝，不能裸发", 0, rig.sentCount)
    }

    private fun storedAccount() =
        MemorySessionTokens(BarrierNotReadableFixture.ACCESS, "refresh-A", SessionScopeRaw.FULL, BarrierNotReadableFixture.ACCOUNT)

    private fun withdrawal(ownership: CallOwnership) =
        ApiCall(
            "recordConsent",
            body =
                """{"type":"privacy","version":3,"accepted":false,"channel":"privacy_center","client_at":"2026-11-17T10:00:00+08:00"}""",
            sendsWhenIdentityUnknown = true,
            ownership = ownership,
        )

    private fun assertPreparedWithdrawal(request: Request) {
        assertEquals("POST", request.method)
        assertEquals("/v1/consents", request.url.encodedPath)
        assertEquals("关闸前已有会话并组好设备头", "device-fixture", request.header(HeaderNames.DEVICE_ID))
        assertEquals(BarrierNotReadableFixture.BEARER, request.header(HeaderNames.AUTHORIZATION))
        assertTrue(body(request).contains("\"accepted\":false"))
        assertTrue(body(request).contains("\"type\":\"privacy\""))
    }

    private fun body(request: Request): String = Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8()
}
