package com.couli.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** vectors.md 原表逐行；0 表示无会话的世代，1/2 对应 epoch-a/epoch-b。 */
@RunWith(Parameterized::class)
class Fx25AuthChangeVectorTest(
    private val id: String,
    private val previous: WebAuthIdentity,
    private val next: WebAuthIdentity,
    private val expected: WebAuthAction,
) {
    @Test
    fun identityDecisionMatchesSharedVector() {
        assertEquals(id, expected, WebAuthChange.decide(previous, next))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> {
            val a = WebAuthIdentity(true, "user-a", 1L)
            val b = WebAuthIdentity(true, "user-b", 2L)
            val guest = WebAuthIdentity(true, null, 0L)
            val unknown = WebAuthIdentity(false, null, 0L)
            return listOf(
                arrayOf("D01_token_refresh", a, a, WebAuthAction.NONE),
                arrayOf("D02_same_account_new_epoch", a, a.copy(loginEpoch = 2L), WebAuthAction.NOTIFY),
                arrayOf("D03_switch_account", a, b, WebAuthAction.NOTIFY_RELOAD),
                arrayOf("D04_logout", a, guest, WebAuthAction.NOTIFY_RELOAD),
                arrayOf("D05_login", guest, a, WebAuthAction.NOTIFY_RELOAD),
                arrayOf("D06_cold_start", unknown, a, WebAuthAction.NONE),
                arrayOf("D07_cold_guest", unknown, guest, WebAuthAction.NONE),
                arrayOf("D08_unreadable_is_not_logout", a, unknown, WebAuthAction.NONE),
                arrayOf("D09_guest_unchanged", guest, guest, WebAuthAction.NONE),
                arrayOf("D10_account_changes_same_epoch", a, b.copy(loginEpoch = 1L), WebAuthAction.NOTIFY_RELOAD),
            )
        }
    }
}
