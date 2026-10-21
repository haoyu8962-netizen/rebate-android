package com.couli.app.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** BR-TEXT-14 表 C 原文，TODO(N-26)：使用真正的草稿字典与格式化器。 */
class LoginDraft17aTest {
    @Test
    fun draftSentencesAndActionNamesMatchPlanningText() {
        val expected =
            mapOf(
                "login.agree.text" to "我已阅读并同意《用户协议》和《隐私政策》，未注册的手机号验证后将自动注册凑狸账号",
                "login.agree_confirm.title" to "请阅读并同意以下条款",
                "login.agree_confirm.body" to "《用户协议》《隐私政策》，未注册的号码将自动注册凑狸账号",
                "login.agree_confirm.body_oauth" to "《用户协议》《隐私政策》，首次登录将自动注册凑狸账号",
                "login.agree_confirm.btn_get_code" to "同意并获取验证码",
                "login.agree_confirm.btn_login" to "同意并登录",
                "login.agree_confirm.btn_disagree" to "不同意",
                "login.code.help_entry" to "收不到验证码？",
                "login.code.resend_needed" to "验证码需要重新获取",
                "login.last_method_tag" to "上次登录",
            )
        expected.forEach { (key, value) -> assertEquals(key, value, LoginDraftTexts.text(key)) }
        assertEquals("27 秒后可重新获取", LoginDraftTexts.format("login.code.countdown", mapOf("n" to "27")))
        assertEquals("1 秒后可重新获取", LoginDraftTexts.format("login.code.countdown", mapOf("n" to "1")))
    }

    @Test
    fun removedDraftKeysAreGoneInsteadOfSilentlyRemainingUnused() {
        val retired =
            setOf(
                "login.settings",
                "login.submit",
                "login.agree.prefix",
                "login.agree.and",
                "login.agree.suffix",
                "login.agree.hint",
                "login.agree_confirm.ok",
                "login.agree_confirm.cancel",
                "login.agree_confirm.before",
                "login.agree_confirm.after",
            )
        LoginDraftTexts.DRAFTS.keys.forEach { key ->
            assertFalse("废弃键 $key", key in retired || key.startsWith("login.invite."))
        }
    }
}
