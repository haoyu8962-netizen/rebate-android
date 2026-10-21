package com.couli.feature.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastOAuthMethod17aTest {
    @Test
    fun oauthWritesOnlyOnSavedSuccessIncludingRestrictedLogin() {
        checkOutcome(false, true)
        checkOutcome(true, true)
        checkOutcome(false, false)
    }

    private fun checkOutcome(
        restricted: Boolean,
        success: Boolean,
    ) {
        Login17aFixture(restricted).use { f ->
            f.methods.record("sms")
            f.oauthAnswer = {
                if (success) {
                    Login17aFixture.success(if (restricted) "deletion_only" else "full")
                } else {
                    Login17aFixture.envelope(20004)
                }
            }
            authorize(f)
            assertEquals("sms", f.methods.read())
            f.tokens.afterSave = { assertEquals("保存阶段不能先记方式", "sms", f.methods.read()) }
            f.adapter.succeed()
            f.settle()
            assertEquals(if (success) "wechat" else "sms", f.methods.read())
            assertEquals(if (success) listOf("sms", "wechat") else listOf("sms"), f.backend.writes)
            assertEquals(if (success) listOf(LoginOutcome.LOGGED_IN) else emptyList<String>(), f.completed)
        }
    }

    @Test
    fun oauthSessionSaveFailureDoesNotChangeLastMethod() {
        Login17aFixture().use { f ->
            f.methods.record("sms")
            f.tokens.failSave = true
            authorize(f)
            f.adapter.succeed()
            f.settle()
            assertEquals("sms", f.methods.read())
            assertEquals(listOf("sms"), f.backend.writes)
            assertTrue(f.completed.isEmpty())
        }
    }

    @Test
    fun withdrawalWhileWaitingForSdkCannotBeUndoneByLateOAuthSuccess() {
        Login17aFixture().use { f ->
            authorize(f)
            f.methods.clear()
            f.adapter.succeed()
            f.settle()
            assertEquals("确实成功结算，不能靠取消请求放绿", listOf(LoginOutcome.LOGGED_IN), f.completed)
            assertNull(f.methods.read())
            assertEquals(listOf<String?>(null), f.backend.writes)
        }
    }

    private fun authorize(f: Login17aFixture) {
        f.model.onThirdParty("wechat")
        f.model.onConfirmAgree()
        f.until("授权已启动") { f.adapter.launches.get() == 1 }
    }
}
