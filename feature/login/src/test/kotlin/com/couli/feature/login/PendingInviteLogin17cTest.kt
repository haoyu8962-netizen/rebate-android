@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import com.couli.core.network.PrivacyRaw
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInviteLogin17cTest {
    @Test
    fun allBindResultsConsumeBeforeRequestAndRetryDoesNotCarryAgain() =
        runTest {
            listOf("bound" to "null", "failed" to "30401", "ignored_existing_user" to "null").forEach { (result, code) ->
                PendingInvite17cFixture(backgroundScope).use { f ->
                    f.pending.set("K7M2QX")
                    f.answer = { PendingInvite17cFixture.success(result, code) }
                    assertEquals(LoginOutcome.LOGGED_IN, f.submit().stateRaw)
                    assertEquals("K7M2QX", f.code(0))
                    assertEquals("请求入口已经消费", listOf<String?>(null), f.entries)
                    assertNull(f.pending.peek())
                    f.submit()
                    assertNull("第二次不带", f.code(1))
                }
            }
        }

    @Test
    fun localValidationSettlesWithoutEnteringApiOrConsuming() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                val cases =
                    listOf(
                        Triple("123", "123456", PendingInvite17cFixture.NOW) to "field_error",
                        Triple("13800138000", "12345", PendingInvite17cFixture.NOW) to LoginOutcome.CODE_INCOMPLETE,
                        Triple("13800138000", "123456", null) to LoginOutcome.AGREEMENT_REQUIRED,
                        Triple("13800138000", "123456", 0L) to LoginOutcome.AGREEMENT_REQUIRED,
                    )
                cases.forEach { (input, expected) ->
                    f.pending.set("K7M2QX")
                    assertEquals(expected, f.service.login(input.first, input.second, input.third, "", false).stateRaw)
                    assertEquals("已 await 生产调用的结算", 0, f.entries.size)
                    assertEquals("K7M2QX", f.pending.peek())
                }
                assertEquals("code_error", f.submit().stateRaw)
                assertEquals("有效调用对照确实进入 ApiClient", 1, f.entries.size)
                assertEquals("K7M2QX", f.code(0))
                assertNull("20002 仍清", f.pending.peek())
            }
        }

    @Test
    fun typedValueIsIgnoredAndRemovalPreventsSending() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.submit(typed = "K7M2QX")
                assertNull(f.code(0))
                f.pending.set("K7M2QX")
                f.pending.set("P8N3RY")
                f.submit(typed = "AB23CD")
                assertEquals("P8N3RY", f.code(1))
                f.pending.set("K7M2QX")
                f.pending.remove()
                f.submit(typed = "AB23CD")
                assertNull(f.code(2))
                assertNull(InviteField.wireValue("K".repeat(33), false))
            }
        }

    @Test
    fun restrictedLoginKeepsCodeUntilAnOrdinaryLogin() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.pending.set("K7M2QX")
                f.submit(restricted = true)
                assertNull(f.code(0))
                assertEquals(listOf("K7M2QX"), f.entries)
                assertEquals("K7M2QX", f.pending.peek())
                f.submit()
                assertEquals("K7M2QX", f.code(1))
                assertNull(f.pending.peek())
            }
        }

    @Test
    fun apiLocalRejectIsObservedAndDoesNotRestoreConsumedCode() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.pending.set("K7M2QX")
                f.privacy = PrivacyRaw.UNDECIDED
                f.submit()
                assertEquals("ApiClient 本地拒绝也能在入口观察到", listOf<String?>(null), f.entries)
                assertEquals(0, f.bodies.size)
                assertNull("Q4：发送前本地拒绝不放回", f.pending.peek())
            }
        }

    @Test
    fun consumptionAndApiEntryHaveNoSuspensionBetweenThem() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.pending.set("K7M2QX")
                val watcher =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        f.pending.state.collect { if (it == null) f.pending.set("P8N3RY") }
                    }
                f.submit()
                assertEquals("若消费后 yield/delay，观察者先改码，入口就会看见 P", listOf<String?>(null), f.entries)
                assertEquals("K7M2QX", f.code(0))
                watcher.cancel()
            }
        }
}
