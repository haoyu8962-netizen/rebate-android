package com.couli.feature.login

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInviteInFlight17cTest {
    @Test
    fun busyAndSettlementKeepNewCodeForSuccessFailureAndDisconnection() =
        runTest {
            listOf(false, true).forEach { initiallyPresent ->
                listOf("success", "20002", "disconnect").forEach { ending ->
                    PendingInvite17cFixture(backgroundScope).use { f ->
                        if (initiallyPresent) f.pending.set("K7M2QX")
                        val (arrived, release) = f.hold(response(ending))
                        val first = async { f.submit() }
                        arrived.await()
                        assertEquals(if (initiallyPresent) "K7M2QX" else null, f.code(0))
                        assertNull("已进入假服务，必须已消费", f.pending.peek())
                        pendingLink(f.pending, "P8N3RY")
                        assertEquals(LoginOutcome.BUSY, f.submit().stateRaw)
                        assertEquals("BUSY 已结算且没进请求入口", 1, f.entries.size)
                        assertEquals("P8N3RY", f.pending.peek())
                        release.countDown()
                        val outcome = first.await()
                        assertEquals(expectedState(ending), outcome.stateRaw)
                        assertEquals("旧请求结算不得 clear 新码", "P8N3RY", f.pending.peek())
                        f.answer = { PendingInvite17cFixture.envelope(20002) }
                        f.submit()
                        assertEquals("P8N3RY", f.code(1))
                        assertNull(f.pending.peek())
                    }
                }
            }
        }

    @Test
    fun disconnectionAfterReceiptDoesNotRestoreOldCode() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.pending.set("K7M2QX")
                val (arrived, release) = f.hold(response("disconnect"))
                val first = async { f.submit() }
                arrived.await()
                assertEquals("K7M2QX", f.code(0))
                release.countDown()
                assertEquals("network_error", first.await().stateRaw)
                assertNull(f.pending.peek())
                f.answer = { PendingInvite17cFixture.envelope(20002) }
                f.submit()
                assertNull(f.code(1))
            }
        }

    @Test
    fun sameCodeWrittenAgainInFlightIsStillANewPendingValue() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.pending.set("K7M2QX")
                val (arrived, release) = f.hold(PendingInvite17cFixture.envelope(20002))
                val first = async { f.submit() }
                arrived.await()
                pendingLink(f.pending, "K7M2QX")
                release.countDown()
                first.await()
                assertEquals("不能在结算时按字符串相等误删新一次写入", "K7M2QX", f.pending.peek())
            }
        }

    private fun response(ending: String): MockResponse =
        when (ending) {
            "success" -> PendingInvite17cFixture.success()
            "disconnect" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else -> PendingInvite17cFixture.envelope(20002)
        }

    private fun expectedState(ending: String): String =
        when (ending) {
            "success" -> LoginOutcome.LOGGED_IN
            "disconnect" -> "network_error"
            else -> "code_error"
        }
}

internal suspend fun pendingLink(
    pending: PendingInviteCode,
    code: String,
) {
    InviteLinkDispatcher(
        pending = pending,
        entryAllowed = { true },
        consented = { true },
        guest = { true },
        getMe = { error("游客不读 getMe") },
        open = { _, _ -> true },
    ).dispatch(code)
}
