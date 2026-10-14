@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.update.FORCE_UPDATE_DELETE_TAG
import com.couli.core.network.UpdateRaw
import com.couli.feature.me.AccountTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13a Android 第 3 轮定向复核（android-recheck-r3fix.md，RC-01、RC-02 S1）的回归测试：走真实 AppShell、Router 与网络层管线，只把传输层换成本地假服务。
 * 用例名带复核编号。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountReviewR3bTest : AccountHarness() {
    /**
     * 实例 A：第 2 步提交超时 → 待确认 → 点【确认】或【确定放弃】，请求已到服务端、回包挂住（[answer] 在放行后给出）→ 返回设置进实例 B，B 恢复同一键的
     * 待确认卡 → 放行 A 的回包。返回原键。
     */
    private fun settleElsewhere(
        abandon: Boolean,
        answer: () -> MockResponse,
    ): String {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("A 的待确认卡") { has(AccountTags.PENDING_CARD) && enabled(AccountTags.PENDING_CONFIRM) }
        val key = records().single().key
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", if (abandon) ABANDON else BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            answer()
        }
        try {
            if (abandon) {
                tap(AccountTags.PENDING_ABANDON)
                idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
                tap(AccountTags.ABANDON_OK)
            } else {
                tap(AccountTags.PENDING_CONFIRM)
            }
            assertTrue("A 的请求已到服务端", arrived.await(WAIT_S, TimeUnit.SECONDS))
            androidx.test.espresso.Espresso
                .pressBack()
            settle()
            idleUntil("A 退出到设置") { !has(AccountTags.PHONE_PAGE) && has(PHONE_ROW) }
            click(PHONE_ROW)
            idleUntil("实例 B 恢复同键待确认") { has(AccountTags.PHONE_PAGE) && has(AccountTags.PENDING_CARD) }
            assertEquals(key, records().single().key)
        } finally {
            release.countDown()
        }
        idleUntil("A 的回包已结算（键结束）") { records().isEmpty() }
        idleUntil("B 收到共享通知") { !has(AccountTags.PENDING_CARD) }
        settle()
        return key
    }

    /** B 的页面：恢复第 2 步提交入口（不回第 1 步），G95：B 从未填过第 2 步，新号与验证码为空，填上后【提交】可用。 */
    private fun assertBRestoredStep2() {
        assertTrue("B 恢复第 2 步", has(AccountTags.PHONE_STEP2))
        assertFalse("B 不回第 1 步", has(AccountTags.PHONE_STEP1))
        assertTrue("新号输入可用", has(AccountTags.PHONE_NEW))
        fillStep2()
        idleUntil("填好后【提交】可用") { enabled(AccountTags.PHONE_SUBMIT) }
    }

    // ---------------------------------------------------------------- RC-01

    /** RC-01：A 的【确认】回包延迟 → B 恢复同键 → A 收到 30411 → B 恢复第 2 步并提示 30411（不回第 1 步）。 */
    @Test
    fun rc01_aConfirm30411ElsewhereRestoresStep2OnTheInstanceThatRestoredTheSameKey() {
        settleElsewhere(abandon = false) { envelope("""{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""", 30411, "trace-304110") }
        assertTrue("B 显示 30411", hasText(t("error.30411")))
        assertFalse(has(AccountTags.PHONE_CONTACT_CS))
        assertBRestoredStep2()
    }

    /** RC-01：A 的【确认】回包延迟 → B 恢复同键 → A 收到 30414 → B 恢复第 2 步、提示 30414 并给【联系客服】。 */
    @Test
    fun rc01_aConfirm30414ElsewhereRestoresStep2WithTheContactEntryOnTheOtherInstance() {
        settleElsewhere(abandon = false) { envelope("null", 30414, "trace-304140") }
        assertTrue("B 显示 30414", hasText(t("error.30414")))
        assertTrue("B 给【联系客服】", has(AccountTags.PHONE_CONTACT_CS))
        assertBRestoredStep2()
    }

    /** RC-01：A 的【确定放弃】回包延迟 → B 恢复同键 → A 收到 completed 且原结果 30414 → B 恢复第 2 步、提示 30414 与【联系客服】，不回第 1 步。 */
    @Test
    fun rc01_anAbandonCompletedWithAnOriginal3xxxxElsewhereRestoresStep2OnTheOtherInstance() {
        settleElsewhere(abandon = true) {
            envelope("""{"outcome":"completed","original":{"code":30414,"msg":"","data":null}}""")
        }
        assertTrue("B 显示原结果 30414", hasText(t("error.30414")))
        assertTrue("B 给【联系客服】", has(AccountTags.PHONE_CONTACT_CS))
        assertBRestoredStep2()
    }

    // ---------------------------------------------------------------- RC-02

    /**
     * RC-02：deletion_only 注销页有未决记录 → 点【确认】，请求已到服务端、成功回包挂住 → 版本限制解除，刷新得到同账号、同登录世代的 full（旧控制器随作用域
     * 换代作废）→ 放行：在途确认不被取消，迟到的成功照常共享结算（原键结束、不落结果未知，新实例的待确认卡随共享通知消失）；旧页面不更新界面（不出错误提示）。
     */
    @Test
    fun rc02_aDeletionOnlyConfirmAlreadyAtTheServerSettlesAfterASameEpochFullRefresh() {
        seedInFlight("requestDeletion", KEY_RC02, "", "", createdAt = now - DAY_MS)
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil { top() == FORCE_UPDATE && has(FORCE_UPDATE_DELETE_TAG) }
        idleUntil("冷启动的屏障刷新已结束") { all("POST", REFRESH).isNotEmpty() }
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil("受限注销页的待确认卡") { top() == DELETE_ACCOUNT && has(AccountTags.PENDING_CARD) && enabled(AccountTags.PENDING_CONFIRM) }
        assertEquals("deletion_only", account.identity.value.scope)
        val epoch = tokens.loginEpoch
        val cooling = deletionJson("cooling", canCancel = true)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", DELETION) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.deletion = cooling
            envelope(cooling)
        }
        try {
            tap(AccountTags.PENDING_CONFIRM)
            assertTrue("确认已到服务端", arrived.await(WAIT_S, TimeUnit.SECONDS))
            // 版本限制解除：网络层发起受限会话的屏障刷新 → 同账号、同登录世代的 full
            refreshScope = "full"
            networkUpdate = UpdateRaw.SUPPORTED
            Thread { api.onUpdateStateChanged(UpdateRaw.SUPPORTED) }.start()
            idleUntil("刷新为同世代的 full") { account.identity.value.scope == "full" }
            assertEquals("同一登录世代", epoch, tokens.loginEpoch)
            // 给在途请求的作废监视足够时间（它每 50ms 核对一次）
            repeat(WATCH_ROUNDS) { settle() }
            assertEquals("确认仍在途（键未结束）", KEY_RC02, records().single().key)
        } finally {
            release.countDown()
        }
        idleUntil("迟到的成功照常结算：原键结束 ${records()}") { records().isEmpty() }
        settle()
        assertEquals("确认只发出 1 次", 1, all("POST", DELETION).size)
        assertEquals("原键", KEY_RC02, all("POST", DELETION).single().key)
        idleUntil("共享通知：新实例的待确认卡消失") { !has(AccountTags.PENDING_CARD) }
        assertFalse("旧页面不出网络错误提示", hasText(t(com.couli.core.me.MeDraftTexts.NETWORK_KEY)))
    }

    private companion object {
        const val KEY_RC02 = "key-rc02-from-last-process"
        const val REFRESH = "/v1/auth/refresh"
        const val WATCH_ROUNDS = 5
    }
}
