package com.couli.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10-1999：窗口外（屏障始终放行），刷新回包成功、replaceIf 抛错、隔离删除（clearIf）也持续抛错。
 * 钉的是完整结算后的实际行为：r4 在 refreshTokens 的 catch 里把已清世代记为未知（null），但随后 RefreshCoordinator 的
 * clearSettlingSession 再清一次、同样转隔离，并在窗口外经 guardedEpoch 读到登录世代，把结论改写为带世代的已清——
 * 原调用最终仍拿到带世代的 CLEAR_SESSION_LOGIN，界面收到 onSessionStored 并转为游客。
 */
class ApiClientBarrierNotReadableFix11Test {
    @Test
    fun doubleFailureOutsideWindowStillSettlesWithSessionStoredAndEpochBoundLoginEffect() {
        val rig = failingStorage()
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        val result = failRefresh(rig)

        assertEquals("失败写入没有改盘上的旧会话", before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertEquals("双重失败不会另存任何会话", 0, rig.tokens.saveAttempts.get())
        assertTrue("隔离重试以外，协调器结算还会再次清理", rig.tokens.clearAttempts.get() >= 2)
        assertEquals("结算清理再次抛错也会通知当前会话世代", listOf(before.generation), rig.events.toList())
        assertEquals("唯一 Effect 与原调用拿到的一致", listOf(result.effect), rig.effects.toList())
        assertEquals(
            "窗口外结算清理转隔离后读到登录世代，原调用拿到带世代的跳登录，不是 GENERIC_ERROR",
            ErrorEffect(
                code = 10002,
                kind = ErrorEffectKind.CLEAR_SESSION_LOGIN,
                replay = ReplayMode.NONE,
                registerPending = false,
                route = "Login",
                sessionEpoch = epoch,
            ),
            result.effect,
        )
        assertGuest(rig, epoch)
        assertEquals("读取隔离身份没有重复通知", listOf(before.generation), rig.events.toList())
        assertEquals("读取不会重发旧令牌", 2, rig.requests.size)
    }

    @Test
    fun quarantinedOriginalAccountIsLocallyRejectedAndUnpinnedLoginRequestSendsNoBearer() {
        val rig = failingStorage()
        failRefresh(rig)
        val before = rig.stored.snapshot()
        val events = rig.events.toList()
        val effects = rig.effects.toList()
        val clears = rig.tokens.clearAttempts.get()

        val rejected = rig.api.execute(originalAccountCall())
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), rejected)
        assertEquals("固定原账号的登录接口零新增发送", 2, rig.requests.size)
        assertEquals("本地拒绝不新增界面 Effect", effects, rig.effects.toList())
        assertTrue("后续读取确实重试了删除，仍然失败", rig.tokens.clearAttempts.get() > clears)
        assertEquals(before, rig.stored.snapshot())
        assertEquals(events, rig.events.toList())

        // 不固定账号时隔离按游客，不是 Unreadable；服务端以 10001 明确拒绝这条匿名 getMe。
        rig.answer = { 10001 to "{}" }
        val anonymous = rig.api.execute(ApiCall("getMe"))
        assertTrue("不是本地 session_unreadable：$anonymous", anonymous is ApiResult.Failure)
        assertEquals(10001, (anonymous as ApiResult.Failure).code)
        assertEquals(ErrorEffectKind.LOGIN, anonymous.effect.kind)
        assertEquals("Login", anonymous.effect.route)
        assertNull(anonymous.effect.sessionEpoch)
        assertEquals("匿名 getMe 只发一条，不再触发刷新", 3, rig.requests.size)
        val sent = rig.requests.last()
        assertEquals("/v1/me", sent.url.encodedPath)
        assertNull("隔离期间不能带出盘上旧令牌", sent.header(HeaderNames.AUTHORIZATION))
        assertEquals(effects + anonymous.effect, rig.effects.toList())
        assertEquals(events, rig.events.toList())
        assertEquals(before, rig.stored.snapshot())
    }

    @Test
    fun nextReadAfterStorageRecoversDeletesOldSessionWithoutRestoringItOrAddingNotification() {
        val rig = failingStorage()
        failRefresh(rig)
        val generation = rig.stored.generation
        val epoch = rig.stored.loginEpoch
        val clears = rig.tokens.clearAttempts.get()
        val events = rig.events.toList()
        val effects = rig.effects.toList()
        rig.tokens.beforeClear = {}

        assertGuest(rig, epoch + 1)
        assertEquals("下一次读取只需一次成功删除", clears + 1, rig.tokens.clearAttempts.get())
        assertEmptyStorage(rig, generation + 1, epoch + 1)
        assertEquals("retryQuarantineClear 成功自身不发 onSessionStored", events, rig.events.toList())
        assertEquals("读取恢复自身不补发跳登录或通用错误", effects, rig.effects.toList())
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), rig.api.execute(originalAccountCall()))
        assertEquals("旧账号没有被恢复或重放", 2, rig.requests.size)
        assertEquals("隔离已经解除，后续读取不再重试删除", clears + 1, rig.tokens.clearAttempts.get())
    }

    @Test
    fun explicitClearAfterStorageRecoversNotifiesNewGenerationAndLeavesGuest() {
        val rig = failingStorage()
        failRefresh(rig)
        val generation = rig.stored.generation
        val epoch = rig.stored.loginEpoch
        val clears = rig.tokens.clearAttempts.get()
        val effects = rig.effects.toList()
        var ended = 0
        rig.tokens.beforeClear = {}

        assertEquals(SessionEnd.CLEARED, rig.api.clearSessionIf(epoch) { ended += 1 })
        assertEquals(1, ended)
        assertEquals(clears + 1, rig.tokens.clearAttempts.get())
        assertEmptyStorage(rig, generation + 1, epoch + 1)
        assertEquals("显式清理会通知新世代", listOf(generation, generation + 1), rig.events.toList())
        assertGuest(rig, epoch + 1)
        assertEquals("确认盘上已空后解除隔离，不再删除", clears + 1, rig.tokens.clearAttempts.get())
        assertEquals("显式清理不另造导航 Effect", effects, rig.effects.toList())
        assertEquals(2, rig.requests.size)
    }

    private fun failingStorage(): BarrierNotReadableFix8Fixture {
        val rig = BarrierNotReadableFix8Fixture()
        rig.answer = { request ->
            if (request.url.encodedPath.endsWith("/refresh")) 0 to BarrierNotReadableFix8Fixture.PAIR else 10002 to "{}"
        }
        rig.tokens.beforeReplace = { error("replaceIf 写盘失败，未写入新令牌") }
        rig.tokens.beforeClear = { error("clearIf 隔离删除持续失败，包括刷新协调器结算") }
        return rig
    }

    private fun failRefresh(rig: BarrierNotReadableFix8Fixture): ApiResult.Failure {
        assertOpen(rig)
        var replaces = 0
        val failure = rig.tokens.beforeReplace
        rig.tokens.beforeReplace = {
            replaces += 1
            assertOpen(rig)
            failure()
        }
        val result = rig.api.execute(ApiCall("getMe"))
        assertTrue("原调用应交回业务失败：$result", result is ApiResult.Failure)
        result as ApiResult.Failure
        assertEquals(10002, result.code)
        assertEquals("fix8", result.traceId)
        assertEquals("成功刷新回包确实到达替换存储的失败点", 1, replaces)
        assertEquals(listOf("/v1/me", "/v1/auth/refresh"), rig.requests.map { it.url.encodedPath })
        assertEquals(listOf(BarrierNotReadableFixture.BEARER, null), rig.requests.map { it.header(HeaderNames.AUTHORIZATION) })
        assertOpen(rig)
        return result
    }

    private fun assertOpen(rig: BarrierNotReadableFix8Fixture) {
        assertTrue("整个用例不关屏障，不属于发布窗口", rig.control.barrier.read({ true }, { false }) { true })
        assertEquals(BarrierNotReadableFixture.FULL, rig.control.state)
        assertEquals(CallEpochs(0, 0), rig.control.inFlight.epochs())
    }

    private fun assertGuest(
        rig: BarrierNotReadableFix8Fixture,
        epoch: Long,
    ) {
        val identity = rig.api.sessionIdentity()
        assertTrue("隔离按已知游客，不按身份未知", identity.known)
        assertNull(identity.account)
        assertNull(identity.partition)
        assertEquals(epoch, identity.loginEpoch)
        assertNull("AppLogin.sessionState 据此判断为 GUEST", rig.api.activeSessionScope())
    }

    private fun assertEmptyStorage(
        rig: BarrierNotReadableFix8Fixture,
        generation: Long,
        epoch: Long,
    ) {
        assertEquals(SessionSnapshot(null, null, null, null, generation), rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertFalse("删除恢复没有保存任何新登录", rig.tokens.saveAttempts.get() > 0)
    }

    private fun originalAccountCall() = ApiCall("getMe", expectedAccount = BarrierNotReadableFixture.ACCOUNT)
}
