package com.couli.feature.login

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAgreement17aTest {
    @Test
    fun confirmKeysComeFromEverySharedActionVector() {
        val rows = login17aVectors().getValue("agreementConfirm").jsonArray
        assertEquals(4, rows.size)
        rows.forEach {
            val row = it.jsonObject
            val actual = AgreementConfirmKeys.of(row.getValue("action").jsonPrimitive.content)
            val fields =
                mapOf(
                    "titleKey" to actual.titleKey,
                    "bodyKey" to actual.bodyKey,
                    "primaryKey" to actual.primaryKey,
                    "secondaryKey" to actual.secondaryKey,
                )
            row.getValue("expect").jsonObject.forEach { (key, value) ->
                assertEquals("${row["name"]}.$key", value.jsonPrimitive.content, fields[key])
            }
        }
    }

    @Test
    fun sentencePreservesWholeTextAndUsesUtf16PositionsInTextOrder() {
        val text = "😀我已阅读并同意《用户协议》和《隐私政策》，未注册的手机号验证后将自动注册凑狸账号"
        val sentence = AgreementSentence.of(text, linkedMapOf("privacy" to "《隐私政策》", "agreement" to "《用户协议》"))
        assertEquals(text, sentence.text)
        assertEquals(text, sentence.accessibilityText)
        assertEquals(listOf("agreement", "privacy"), sentence.spans.map { it.type })
        assertEquals(listOf("《用户协议》", "《隐私政策》"), sentence.spans.map { text.substring(it.start, it.end) })
        assertEquals(text.indexOf("《用户协议》"), sentence.spans.first().start)
    }

    @Test
    fun missingAndOverlappingLinksAreDroppedAndFirstOccurrenceWins() {
        val text = "《用户协议》《隐私政策》《用户协议》"
        val sentence =
            AgreementSentence.of(
                text,
                linkedMapOf("first" to "《用户协议》", "overlap" to "用户协议", "missing" to "《不存在》", "privacy" to "《隐私政策》"),
            )
        assertEquals(listOf("first", "privacy"), sentence.spans.map { it.type })
        assertEquals(0, sentence.spans.first().start)
        assertEquals(text.indexOf("《隐私政策》"), sentence.spans.last().start)
    }

    @Test
    fun invalidPhoneIsRejectedBeforeConsentOrNetwork() {
        Login17aFixture().use { f ->
            f.model.onPhoneChange("138a00138000")
            f.model.onGetCode()
            f.settle()
            assertNull(f.model.state.value.dialog)
            assertFalse(f.model.state.value.agreed)
            assertEquals("error.20001.phone_invalid", f.model.state.value.phoneError)
            assertEquals(0, f.entries.get())
        }
    }

    @Test
    fun thirdPartyUsesTheSameGateCancelDoesNothingAndLoadingBelongsOnlyToSms() {
        Login17aFixture().use { f ->
            f.model.onThirdParty("wechat")
            f.settle()
            val confirmation = checkNotNull(f.model.state.value.confirmation)
            assertEquals("login.agree_confirm.btn_login", confirmation.primaryKey)
            assertEquals("login.agree_confirm.body_oauth", confirmation.bodyKey)
            f.model.onCancelConfirm()
            f.settle()
            assertEquals(0, f.entries.get())
            assertEquals(0, f.adapter.launches.get())
            assertEquals(LoginStep.PHONE, f.model.state.value.step)
            f.model.onThirdParty("wechat")
            f.advance(9_000)
            f.model.onConfirmAgree()
            f.until("授权已启动") { f.adapter.launches.get() == 1 }
            assertTrue(f.model.state.value.agreed)
            assertFalse("第三方授权期间获取验证码不转圈", f.model.state.value.sending)
            f.adapter.cancel()
            f.settle()
        }
    }

    @Test
    fun bothExplicitContinuePathsAskAgainAndCancelKeepsPhoneWithoutSending() {
        listOf(false, true).forEach { uncertain ->
            Login17aFixture().use { f ->
                if (uncertain) f.sendAnswer = { Login17aFixture.envelope(50401) }
                f.model.onToggleAgreement()
                f.model.onGetCode()
                f.settle()
                if (!uncertain) {
                    f.model.onBackToPhone()
                    f.advance(61_000) // 已可重发、旧码仍有效的另一条继续路径。
                }
                f.model.onToggleAgreement()
                val before = f.entries.get()
                f.model.onContinueCode()
                f.settle()
                assertEquals(LoginDialog.AGREE_CONFIRM, f.model.state.value.dialog)
                assertFalse(f.model.state.value.sending)
                f.model.onCancelConfirm()
                f.settle()
                assertEquals(LoginStep.PHONE, f.model.state.value.step)
                assertEquals(before, f.entries.get())
                f.model.onContinueCode()
                f.model.onConfirmAgree()
                f.settle()
                assertEquals(LoginStep.CODE, f.model.state.value.step)
                assertEquals(before, f.entries.get())
            }
        }
    }

    @Test
    fun changingPhoneOrGoingBackInvalidatesPendingConfirmation() {
        listOf(false, true).forEach { back ->
            Login17aFixture().use { f ->
                f.model.onGetCode()
                if (back) f.model.onBackToPhone() else f.model.onPhoneChange("13900139000")
                f.model.onConfirmAgree()
                f.settle()
                assertEquals(0, f.entries.get())
                assertEquals(LoginStep.PHONE, f.model.state.value.step)
                assertFalse(f.model.state.value.agreed)
                f.model.onGetCode()
                f.model.onConfirmAgree()
                f.settle()
                assertEquals(1, f.sends())
                assertEquals(LoginStep.CODE, f.model.state.value.step)
            }
        }
    }
}
