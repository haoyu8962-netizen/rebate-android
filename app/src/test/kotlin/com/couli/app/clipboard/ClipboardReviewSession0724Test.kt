package com.couli.app.clipboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.router.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [ClipboardReviewLogin0724Wire::class, ClipboardReviewCipher0724::class])
class ClipboardReviewSession0724Test {
    @Test
    fun productionLoginSamePhoneBoundAccountSwitchClearsProductsBeforeRestart() = scenario(restart = true)

    @Test
    fun productionLoginSamePhoneBoundAccountSwitchDoesNotBlockNewAccountInProcess() = scenario(restart = false)

    private fun scenario(restart: Boolean) {
        ClipboardReviewLogin0724Graph().use { h ->
            assertTrue(h.prefs().salt.write(SAMPLE_SALT))
            assertTrue(h.prefs().importPreferences { ClipboardReview0724Harness.NOW }.enable("1"))
            h.loginAs(ClipboardReviewLogin0724Wire.PHONE_A)
            assertEquals("account-A", h.client.activeUserId())
            assertEquals(SessionState.PHONE_BOUND, h.routing.session.value)
            h.recognize()
            assertEquals(1, ClipboardReviewLogin0724Wire.parseCount())
            assertTrue(h.board.result.value is ClipboardResult.CardList)
            assertNotNull(h.prefs().hashes.read(ClipboardRecords.KEY_PRODUCTS))
            h.board.onBackground()
            h.settle()
            val epochA = h.tokens.loginEpoch
            h.loginAs(ClipboardReviewLogin0724Wire.PHONE_B)
            assertEquals("account-B", h.client.activeUserId())
            assertTrue(h.tokens.loginEpoch > epochA)
            assertEquals("A/B 身份状态相同，不能靠枚举变化触发清理", SessionState.PHONE_BOUND, h.routing.session.value)
            val persistedAfterSwitch = h.prefs().hashes.read(ClipboardRecords.KEY_PRODUCTS)
            if (restart) h.restartClipboardBeforeNextRead()
            h.recognize()
            assertEquals("B 不被 A 的摘要拦截（包含下一次提取前重建）", 2, ClipboardReviewLogin0724Wire.parseCount())
            assertTrue(h.board.result.value is ClipboardResult.CardList)
            assertNull("生产登录通知必须在下一次自动提取之前清掉 A 摘要", persistedAfterSwitch)
        }
    }
}
