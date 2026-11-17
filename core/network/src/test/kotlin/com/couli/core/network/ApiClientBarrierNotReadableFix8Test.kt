package com.couli.core.network

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector

/** review-r3 的失败写入、登录分类和 targetReject 缺口；不改任何旧用例。 */
class ApiClientBarrierNotReadableFix8Test {
    @get:Rule
    val failures = ErrorCollector()

    @Test
    fun refreshReplaceThrowsInClosedWindowNeverReadsEpochOrClaimsSessionCleared() {
        val rig = BarrierNotReadableFix8Fixture()
        val pause = BarrierNotReadablePause()
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        rig.answer = { request ->
            if (request.url.encodedPath.endsWith("/refresh")) 0 to BarrierNotReadableFix8Fixture.PAIR else 10002 to "{}"
        }
        rig.tokens.beforeReplace = {
            pause.stopHere()
            error("replaceIf 写盘失败，未产生成功写入")
        }
        // 结算 clearIf 返回 false：隔离 refreshTokens 的异常分支，不能靠另一条清除异常路径让本用例变红。
        rig.tokens.rejectClear = true
        val observed = finishWithoutReads(rig, pause) { rig.api.execute(ApiCall("getMe")) }

        assertEquals("原请求与刷新已在关闸前发出，窗口内无重放", 2, rig.requests.size)
        assertEquals(before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertUnknownAndNotCleared(rig, observed.value)
        failures.checkSucceeds { observed.assertNoReads() }
    }

    @Test
    fun clearOwnedSessionThrowsInClosedWindowNeverReadsEpochOrClaimsSessionCleared() {
        clearFailure(settling = false)
    }

    @Test
    fun clearSettlingSessionThrowsInClosedWindowNeverReadsEpochOrClaimsSessionCleared() {
        clearFailure(settling = true)
    }

    @Test
    fun quarantinedLoginRefusedInClosedWindowIsPromptableFailureAndSameLoginRetriesSuccessfully() {
        val rig = BarrierNotReadableFix8Fixture()
        rig.tokens.beforeClear = { error("持久删除失败，保持隔离") }
        rig.api.clearSessionIf(rig.stored.loginEpoch)
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        val saves = rig.tokens.saveAttempts.get()
        val pause = BarrierNotReadablePause()
        val armed = AtomicBoolean(true)
        var beforeReads = emptyList<Int>()
        rig.answer = {
            if (armed.compareAndSet(true, false)) {
                beforeReads = rig.tokens.readCounts()
                pause.stopHere()
            }
            0 to BarrierNotReadableFix8Fixture.LOGIN
        }
        // 不附页面世代：同一逻辑登录解除屏障后可以原样重试；响应保存成功才通知页面。
        val call = ApiCall("loginBySms", body = """{"phone":"13800000000","code":"123456"}""")
        val observed = pause.finishInClosedWindow(rig.control) { rig.api.execute(call) }
        rig.tokens.assertNoEpochReads(beforeReads)
        assertEquals("窗口内拒存不能调用 saveIf", saves, rig.tokens.saveAttempts.get())
        assertEquals(before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertEquals("登录请求已在关闸前发出，窗口不续发", 1, rig.requests.size)
        failures.checkSucceeds { observed.assertNoReads() }
        rig.control.settleEnvironment()
        val recovered = rig.api.execute(call)
        assertTrue("同一登录重试真正保存成功：$recovered", recovered is ApiResult.Success)
        assertEquals(saves + 1, rig.tokens.saveAttempts.get())
        assertEquals("access-new", rig.stored.accessToken)
        assertEquals("account-B", rig.stored.userId)
        assertEquals(epoch + 1, rig.stored.loginEpoch)
        assertEquals(before.generation + 1, rig.stored.generation)
        assertEquals(2, rig.requests.size)
        // 两种 LoginService 都把此类别映射为 SERVICE_ERROR + GENERIC_KEY；session_changed 则静默 STALE。
        assertEquals(ApiResult.SessionNotSaved(LocalRejectReason.SESSION_SAVE_FAILED, "fix8"), observed.value)
    }

    @Test
    fun confirmPendingTargetGateIsNotCalledInClosedWindowAndRecovers() {
        targetGate(abandon = false)
    }

    @Test
    fun abandonPendingTargetGateIsNotCalledInClosedWindowAndRecovers() {
        targetGate(abandon = true)
    }

    private fun clearFailure(settling: Boolean) {
        val rig = BarrierNotReadableFix8Fixture()
        val pause = BarrierNotReadablePause()
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        rig.answer = { request ->
            if (settling && !request.url.encodedPath.endsWith("/refresh")) 10002 to "{}" else 10404 to "{}"
        }
        val once = AtomicBoolean(true)
        rig.tokens.beforeClear = {
            if (once.compareAndSet(true, false)) pause.stopHere()
            error("clearIf 未成功，必须不能获取写后世代读取许可")
        }
        val observed = finishWithoutReads(rig, pause) { rig.api.execute(ApiCall("getMe")) }
        assertEquals(if (settling) 2 else 1, rig.requests.size)
        assertEquals(before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertTrue("确实到达清除写入失败分支", rig.tokens.clearAttempts.get() > 0)
        assertUnknownAndNotCleared(rig, observed.value)
        failures.checkSucceeds { observed.assertNoReads() }
    }

    private fun finishWithoutReads(
        rig: BarrierNotReadableFix8Fixture,
        pause: BarrierNotReadablePause,
        call: () -> ApiResult,
    ): BarrierNotReadableObservation<ApiResult> {
        // beforeClear/replace 在写路径内，不持有屏障 read；关闸不会等待被暂停的线程。
        var reads = emptyList<Int>()
        val previousClear = rig.tokens.beforeClear
        val previousReplace = rig.tokens.beforeReplace
        rig.tokens.beforeClear = {
            if (reads.isEmpty()) reads = rig.tokens.readCounts()
            previousClear()
        }
        rig.tokens.beforeReplace = {
            if (reads.isEmpty()) reads = rig.tokens.readCounts()
            previousReplace()
        }
        val observed =
            pause.finishInClosedWindow(rig.control) {
                call().also {
                    val identity = rig.api.sessionIdentity()
                    assertFalse("仍在发布前窗口内，身份必须为未知", identity.known)
                    assertNull(identity.account)
                }
            }
        failures.checkSucceeds { rig.tokens.assertNoEpochReads(reads) }
        return observed
    }

    private fun assertUnknownAndNotCleared(
        rig: BarrierNotReadableFix8Fixture,
        result: ApiResult,
    ) {
        assertFalse("失败不能成为登录成功", result is ApiResult.Success)
        failures.checkSucceeds { assertTrue("没有把失败删除通知为已保存/已清除", rig.events.isEmpty()) }
        failures.checkSucceeds { assertTrue("没有交付带已清登录世代的 Effect", rig.effects.all { it.sessionEpoch == null }) }
    }

    private fun targetGate(abandon: Boolean) {
        val rig = BarrierNotReadableFix8Fixture()
        val record =
            PendingKeyRecord(
                "pending-A",
                "createWithdrawal",
                """{"amount_fen":1000}""",
                BarrierNotReadableFixture.ACCOUNT,
                stateRaw = PendingKeyStore.UNKNOWN,
                everUnknown = true,
            )
        rig.backend.write(record)
        assertTrue(rig.api.restorePending())
        var gateCalls = 0

        fun target(gated: Boolean): PendingTarget =
            PendingTarget(
                record.account,
                record.key,
                CallOwnership(
                    rig.control.inFlight.epochs(),
                    stillOwned = { true },
                    sendAllowed =
                        if (gated) {
                            (
                                {
                                    gateCalls += 1
                                    true
                                }
                            )
                        } else {
                            null
                        },
                ),
            )

        fun click(target: PendingTarget): ApiResult =
            if (abandon) {
                rig.api.abandonPending(record.operationId, target)
            } else {
                rig.api.confirmPending(record.operationId, target = target)
            }
        val plain = target(false)
        val gated = target(true)
        val observed = rig.control.observeClosedWindow { click(plain) to click(gated) }
        assertEquals(0, gateCalls)
        assertEquals(0, rig.requests.size)
        assertEquals(record, rig.backend.read(record.account, record.key))
        assertTrue("未占用，仍可由用户操作", rig.pending.wouldStartUserAction(record.key, record.account))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), observed.value.first)
        assertEquals("targetReject 保留同一调用无闸门时的拒绝码", observed.value.first, observed.value.second)
        failures.checkSucceeds { observed.assertNoReads() }
        rig.control.settleEnvironment()
        rig.answer = { 0 to if (abandon) """{"outcome":"abandoned"}""" else "{}" }
        val recovered = click(target(true))
        assertTrue("解除后闸门恢复求值", gateCalls > 0)
        assertEquals(1, rig.requests.size)
        assertEquals(BarrierNotReadableFixture.BEARER, rig.requests.single().header(HeaderNames.AUTHORIZATION))
        assertTrue("恢复结果：$recovered", if (abandon) recovered is ApiResult.Abandoned else recovered is ApiResult.Success)
    }
}
