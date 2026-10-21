@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector

class LoginConsentRace17aTest {
    @get:Rule
    val errors = ErrorCollector()

    @Test
    fun uncheckingDuringSendKeepsPhoneAndContinueRequiresFreshConsentWithoutResending() {
        Login17aFixture().use { f ->
            val states = mutableListOf<LoginUiState>()
            val observer =
                f.scope.launch(UnconfinedTestDispatcher(f.scheduler), start = CoroutineStart.UNDISPATCHED) {
                    f.model.state.collect { states += it }
                }
            try {
                sendThenUncheckBeforeReply(f)
                errors.checkThat("S1-1 成功回包仍须留在手机号页", f.model.state.value.step, equalTo(LoginStep.PHONE))
                assertFalse(f.model.state.value.agreed)
                assertFalse(f.model.state.value.sending)
                assertNull(f.model.state.value.confirmation)
                assertTrue("原号码的码仍可继续，不能把发码成功丢掉", f.service.issuedCodeRemainingSec(Login17aFixture.PHONE) > 0)
                f.model.onContinueCode()
                f.settle()
                errors.checkThat("重新同意前不能换步", f.model.state.value.step, equalTo(LoginStep.PHONE))
                assertEquals(LoginDialog.AGREE_CONFIRM, f.model.state.value.dialog)
                assertEquals(
                    "login.agree_confirm.btn_get_code",
                    f.model.state.value.confirmation
                        ?.primaryKey,
                )
                assertEquals(1, f.entries.get())
                f.advance(9_000)
                f.model.onConfirmAgree()
                f.settle()
                assertEquals(LoginStep.CODE, f.model.state.value.step)
                assertTrue(f.model.state.value.agreed)
                assertNull(f.model.state.value.confirmation)
                assertEquals("确认继续不能再发码", 1, f.sends())
                assertEquals(1, f.entries.get())
                f.input("123456", true)
                f.respond(20002)
                assertEquals(
                    Instant.ofEpochMilli(f.now).toString(),
                    f.logins
                        .single()
                        .body
                        .getValue("consent_at")
                        .jsonPrimitive.content,
                )
                errors.checkThat(
                    "验证码页任何一次状态都不能带同意确认框（包括瞬时状态）",
                    states.any { it.step == LoginStep.CODE && (it.dialog == LoginDialog.AGREE_CONFIRM || it.confirmation != null) },
                    equalTo(false),
                )
            } finally {
                observer.cancel()
            }
        }
    }

    @Test
    fun lateSmsReplyAndSixDigitInputCannotOpenConsentOnTheCodePage() {
        Login17aFixture().use { f ->
            val states = mutableListOf<LoginUiState>()
            val observer =
                f.scope.launch(UnconfinedTestDispatcher(f.scheduler), start = CoroutineStart.UNDISPATCHED) {
                    f.model.state.collect { states += it }
                }
            try {
                sendThenUncheckBeforeReply(f)
                // 错误实现已露出验证码页，填满六位会再弹同意框；修复后留在 PHONE，旧输入回调应被忽略。
                f.model.onCodeChange("123456")
                f.settle()
                assertFalse(
                    "成功回包后的自动提交也不得在验证码页弹同意框",
                    states.any { it.step == LoginStep.CODE && (it.dialog == LoginDialog.AGREE_CONFIRM || it.confirmation != null) },
                )
                assertFalse("取消勾选后任何瞬时状态都不能进入 CODE", states.any { it.step == LoginStep.CODE && !it.agreed })
                assertEquals(LoginStep.PHONE, f.model.state.value.step)
                assertEquals("未再次同意前不应有登录请求入口", 1, f.entries.get())
                assertEquals(0, f.logins.size)
            } finally {
                observer.cancel()
            }
        }
    }

    private fun sendThenUncheckBeforeReply(f: Login17aFixture) {
        val reply = CountDownLatch(1)
        try {
            f.sendAnswer = {
                check(reply.await(5, TimeUnit.SECONDS)) { "测试没有释放发码成功回包" }
                Login17aFixture.sent()
            }
            f.model.onGetCode()
            assertEquals(LoginDialog.AGREE_CONFIRM, f.model.state.value.dialog)
            f.model.onConfirmAgree()
            f.until("生产发码请求到达，回包仍挂起") { f.sends() == 1 }
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            assertTrue(f.model.state.value.sending)
            assertTrue(f.model.state.value.agreed)
            f.model.onToggleAgreement()
            assertFalse(f.model.state.value.agreed)
        } finally {
            reply.countDown()
        }
        f.settle()
    }
}
