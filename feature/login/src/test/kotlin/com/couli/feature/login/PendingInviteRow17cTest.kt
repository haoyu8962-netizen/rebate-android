@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInviteRow17cTest {
    @Test
    fun rowTracksSharedContainerWithoutConsumingAndRemoveUsesThatContainer() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                PendingInvite17cFixture(backgroundScope).use { f ->
                    val model = LoginViewModel(LoginPageConfig(f.service, Keys, false))
                    try {
                        assertNull(model.pendingInviteRow)
                        f.pending.set("K7M2QX")
                        val row = checkNotNull(model.pendingInviteRow)
                        assertEquals("K7M2QX", row.code)
                        assertEquals("login.pending_invite", row.textKey)
                        assertEquals("login.pending_invite.remove", row.removeKey)
                        assertFalse("10-19 小字行没有告知句", row.javaClass.methods.any { it.name == "getNoticeKey" })
                        assertEquals("读行不消费", "K7M2QX", model.pendingInviteRow?.code)
                        f.pending.set("P8N3RY")
                        assertEquals("P8N3RY", model.pendingInviteRow?.code)
                        model.removePendingInvite()
                        assertNull(model.pendingInviteRow)
                        assertNull(f.pending.peek())
                        f.submit()
                        assertNull(f.code(0))
                    } finally {
                        model.viewModelScope.cancel()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun restrictedRowIsHiddenAndCodeIsNeitherPrefilledNorConsumedOnCodeStep() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                PendingInvite17cFixture(backgroundScope).use { f ->
                    f.pending.set("K7M2QX")
                    f.answer = { PendingInvite17cFixture.envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""") }
                    assertEquals(SmsCountdown.STATE_SENT, f.service.sendCode("13800138000").stateRaw)
                    listOf(false, true).forEach { restricted ->
                        val model = LoginViewModel(LoginPageConfig(f.service, Keys, restricted))
                        try {
                            model.onPhoneChange("13800138000")
                            model.onToggleAgreement()
                            model.onContinueCode()
                            assertEquals(LoginStep.CODE, model.state.value.step)
                            assertEquals(if (restricted) null else "K7M2QX", model.pendingInviteRow?.code)
                            assertEquals("K7M2QX", f.pending.peek())
                        } finally {
                            model.viewModelScope.cancel()
                        }
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    private object Keys : LoginTexts {
        override fun text(key: String) = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ) = key
    }
}
