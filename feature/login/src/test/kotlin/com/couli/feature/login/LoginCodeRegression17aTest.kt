@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCodeRegression17aTest {
    @Test
    fun inFlightInputRepeatedAfterSettlementIsNotQueuedButANewRevisionSubmits() {
        Login17aFixture().use { f ->
            f.enterCode()
            f.input("123456", true)
            f.input("654321", false)
            f.respond(20002)
            f.input("654321", false)
            f.settle()
            assertEquals("654321", f.model.state.value.code)
            assertNull(f.model.state.value.codeError)
            assertEquals(1, f.logins.size)
            f.input("654320", true)
            f.respond(20002)
            assertEquals(2, f.logins.size)
            assertEquals(3, f.entries.get())
        }
    }

    @Test
    fun errorClearCreatesANewRevisionSoRetypingTheSameCodeCanSubmit() {
        Login17aFixture().use { f ->
            f.enterCode()
            f.input("123456", true)
            f.respond(20002)
            assertEquals("", f.model.state.value.code)
            f.input("123456", true)
            f.respond(20002)
            assertEquals(2, f.logins.size)
            assertEquals(3, f.entries.get())
        }
    }

    @Test
    fun getCodeContinueBranchNeverEmitsSending() {
        Login17aFixture().use { f ->
            f.enterCode()
            f.model.onBackToPhone()
            val sending = mutableListOf<Boolean>()
            val observer =
                f.scope.launch(UnconfinedTestDispatcher(f.scheduler), start = CoroutineStart.UNDISPATCHED) {
                    f.model.state.collect { sending += it.sending }
                }
            try {
                f.model.onGetCode()
                f.settle()
                assertEquals(LoginStep.CODE, f.model.state.value.step)
                assertEquals(1, f.entries.get())
                assertFalse("CONTINUE 分支不应短暂发出 loading=true", sending.any { it })
            } finally {
                observer.cancel()
            }
        }
    }

    @Test
    fun clearingFromWrongOrExpiredResponseKeepsTheErrorThroughDuplicateEmptyEvent() {
        listOf(20002, 20003).forEach { code ->
            Login17aFixture().use { f ->
                f.enterCode()
                f.input("123456", true)
                f.respond(code)
                assertEquals("", f.model.state.value.code)
                assertEquals("error.$code", f.model.state.value.codeError)
                // Compose/输入法重复派发程序清空后的值，不是用户的新修订。
                f.model.onCodeChange("")
                f.settle()
                assertEquals("程序清格不能冲掉错误", "error.$code", f.model.state.value.codeError)
                assertEquals(2, f.entries.get())
                f.model.onCodeChange("1")
                f.settle()
                assertNull("真正编辑后才撤掉旧错误", f.model.state.value.codeError)
            }
        }
    }

    @Test
    fun resendOnCodeStepDoesNotAskAgainAndLoadingStopsWithSend() {
        Login17aFixture().use { f ->
            f.enterCode()
            f.advance(61_000)
            val held = CountDownLatch(1)
            try {
                f.sendAnswer = {
                    check(held.await(5, TimeUnit.SECONDS))
                    Login17aFixture.sent()
                }
                f.model.onResend()
                f.until("重发请求到达") { f.sends() == 2 }
                assertNull(f.model.state.value.dialog)
                assertTrue(f.model.state.value.sending)
                f.model.onCodeChange("123")
                assertEquals("重发期间仍接收输入", "123", f.model.state.value.code)
            } finally {
                held.countDown()
            }
            f.settle()
            assertFalse(f.model.state.value.sending)
            assertEquals(LoginStep.CODE, f.model.state.value.step)
        }
    }

    @Test
    fun firstSendAndResendKeepErrorFeedbackAndClearItOnSuccess() {
        Login17aFixture().use { f ->
            f.sendAnswer = { Login17aFixture.envelope(50001) }
            f.model.onGetCode()
            f.model.onConfirmAgree()
            f.settle()
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            assertTrue(checkNotNull(f.model.state.value.toast).contains("error.50001"))
            assertTrue(checkNotNull(f.model.state.value.toast).contains("123456"))
            f.sendAnswer = { Login17aFixture.sent() }
            f.model.onGetCode()
            f.settle()
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            f.advance(61_000)
            f.sendAnswer = { Login17aFixture.envelope(42901).setResponseCode(429).setHeader("Retry-After", "45") }
            f.model.onResend()
            f.settle()
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            assertEquals(45, f.model.state.value.sendLimitedSec)
            assertNotNull(f.model.state.value.toast)
            val count = f.entries.get()
            f.model.onResend()
            f.settle()
            assertEquals(count, f.entries.get())
            f.advance(46_000)
            f.sendAnswer = { Login17aFixture.sent() }
            f.model.onResend()
            f.settle()
            assertEquals(count + 1, f.entries.get())
            assertEquals(0, f.model.state.value.sendLimitedSec)
            assertNull(f.model.state.value.phoneError)
        }
    }

    @Test
    fun first42901DoesNotEnterCodeAndResend50001KeepsCodeStepAndTrace() {
        Login17aFixture().use { f ->
            f.sendAnswer = { Login17aFixture.envelope(42901).setResponseCode(429).setHeader("Retry-After", "30") }
            f.model.onGetCode()
            f.model.onConfirmAgree()
            f.settle()
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            assertEquals(30, f.model.state.value.sendLimitedSec)
            assertTrue(checkNotNull(f.model.state.value.toast).contains("error.42901"))
            f.model.onGetCode()
            f.settle()
            assertEquals(1, f.entries.get())
            f.advance(31_000)
            f.sendAnswer = { Login17aFixture.sent() }
            f.model.onGetCode()
            f.settle()
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            f.advance(61_000)
            f.sendAnswer = { Login17aFixture.envelope(50001) }
            f.model.onResend()
            f.settle()
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            assertTrue(checkNotNull(f.model.state.value.toast).contains("error.50001"))
            assertTrue(checkNotNull(f.model.state.value.toast).contains("123456"))
            assertFalse(f.model.state.value.sending)
        }
    }

    @Test
    fun leavingWhileSmsIsInFlightCannotReenterCodeStep() {
        Login17aFixture().use { f ->
            val held = CountDownLatch(1)
            try {
                f.sendAnswer = {
                    check(held.await(5, TimeUnit.SECONDS))
                    Login17aFixture.sent()
                }
                f.model.onGetCode()
                f.model.onConfirmAgree()
                f.until("服务已收到发码") { f.sends() == 1 }
                f.model.onBackToPhone()
            } finally {
                held.countDown()
            }
            f.settle()
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            f.input("123456", false)
            f.settle()
            assertEquals(1, f.entries.get())
        }
    }
}
