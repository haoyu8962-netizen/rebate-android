package com.couli.feature.login

import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LoginPhoneOwnership17aTest(
    private val entry: String,
) {
    @get:Rule
    val errors = ErrorCollector()

    @Test
    fun returningToBStartsEmptyAndOneDigitCannotSubmitACodeOrConsumePendingInvite() {
        Login17aFixture().use { f ->
            // 意外提交也给真实服务返回 20002，先等页面动作结算，再断请求入口；不靠挂起/超时制造红。
            f.loginAnswer = { Login17aFixture.envelope(20002) }
            sendForBThenEnterFiveDigitsForA(f)
            f.model.onBackToPhone()
            f.model.onPhoneChange(PHONE_B)
            assertTrue("B 的码尚未过期", f.service.issuedCodeRemainingSec(PHONE_B) > 0)
            assertTrue("GET_CODE 必须走 COUNTDOWN 而非再次发码", f.service.resendRemainingSec(PHONE_B) > 0)
            assertTrue(f.service.pendingInvite.set(INVITE))
            assertEquals(INVITE, f.service.pendingInvite.peek())
            val before = f.entries.get()
            when (entry) {
                CONTINUE -> f.model.onContinueCode()
                GET_CODE -> f.model.onGetCode()
            }
            f.settle()
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            val codeOnEntry = f.model.state.value.code
            // 和页面输入框一样从实际显示值追加，不能写死 "6" 而替产品抹掉 A 的残留。
            f.model.onCodeChange(codeOnEntry + "6")
            f.settle()
            errors.checkThat("$entry：B 的格子不应沿用 A 的五位", codeOnEntry, equalTo(""))
            errors.checkThat("补一位后只应显示一位", f.model.state.value.code, equalTo("6"))
            errors.checkThat("两条继续路径均不重复发码", f.sends(), equalTo(2))
            errors.checkThat("结算后 API 逻辑请求入口零增量（强于仅无传输）", f.entries.get(), equalTo(before))
            errors.checkThat(
                "不得发出 {phone:B, code:A 的残留+6}",
                f.logins.any {
                    it.body
                        .getValue("phone")
                        .jsonPrimitive.content == PHONE_B &&
                        it.body
                            .getValue("code")
                            .jsonPrimitive.content == "123456"
                },
                equalTo(false),
            )
            errors.checkThat("不得发出任何登录请求", f.logins.size, equalTo(0))
            errors.checkThat("普通登录的待绑定码不能被提前消费", f.service.pendingInvite.peek(), equalTo(INVITE))
        }
    }

    private fun sendForBThenEnterFiveDigitsForA(f: Login17aFixture) {
        f.model.onPhoneChange(PHONE_B)
        f.enterCode()
        f.model.onBackToPhone()
        f.model.onPhoneChange(PHONE_A)
        f.model.onGetCode()
        f.settle()
        assertEquals(LoginStep.CODE, f.model.state.value.step)
        f.input("12345", false)
        f.settle()
        assertEquals("12345", f.model.state.value.code)
        assertEquals(2, f.sends())
        assertEquals(2, f.entries.get())
        assertEquals(0, f.logins.size)
    }

    companion object {
        private const val PHONE_A = "13900139000"
        private const val PHONE_B = Login17aFixture.PHONE
        private const val INVITE = "K7M2QX"
        private const val CONTINUE = "CONTINUE_BUTTON"
        private const val GET_CODE = "GET_CODE_COUNTDOWN"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun entries(): List<Array<String>> = listOf(arrayOf(CONTINUE), arrayOf(GET_CODE))
    }
}
