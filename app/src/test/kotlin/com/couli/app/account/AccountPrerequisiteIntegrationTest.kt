@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.PendingKeyStore
import com.couli.feature.me.AccountTags
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 前置补办（brief §0 第 2 条：补办后回发起页、用户再点之前 0 次 POST、再点用新键；出现过结果未知的仍待确认）、AC-S2-62 ㉓（封禁下放弃结果未知的注销）、
 * 共性第 19、22 条。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountPrerequisiteIntegrationTest : AccountHarness() {
    @Test
    fun requestDeletion10005GuidesToBindPhoneAndZeroPostsUntilTheUserClicksAgainWithANewKey() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 10005)
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("补办：BindPhone") { top() == "BindPhone" }
        assertTrue("不登记 pending_action、不重放", records().isEmpty())
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        settle()
        assertEquals(DELETE_ACCOUNT, top())
        assertEquals("用户再次点击之前 0 次 POST", 1, all("POST", DELETION).size)
        assertTrue("勾选保留（G14）", enabled(AccountTags.DELETE_SUBMIT))
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val posts = all("POST", DELETION)
        assertNotEquals("再点用新键", posts[0].key, posts[1].key)
    }

    @Test
    fun bindPhone10004GuidesAndZeroPostsUntilTheUserSubmitsAgain() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 10004, """{"consent_type":"labor_agreement"}""")
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("补办引导") { top() != SETTINGS || has(AccountTags.PHONE_ERROR) }
        if (top() != SETTINGS) {
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        settle()
        assertEquals("回发起页之后 0 次 POST", 1, all("POST", BIND).size)
        assertTrue("新号保留、码清空（G39）", hasText(PHONE_NEW))
        assertTrue(records().isEmpty())
        type(AccountTags.PHONE_STEP2_CODE, CODE_OK)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertNotEquals(posts[0].key, posts[1].key)
    }

    @Test
    fun acS2_62_23_bannedUserAbandonsAnUnknownDeletionAndTheLateOriginalGets20903() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueTimeout("POST", DELETION)
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.PENDING_CARD) }
        val key = records().single().key
        // 之后被封禁：白名单外接口 10006；注销页（白名单）不被 10006 换走（G73）
        serverState.risk = "banned"
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open(DELETE_ACCOUNT)
        idleUntil("封禁中注销页仍在，按记录显示待确认") { top() == DELETE_ACCOUNT && has(AccountTags.PENDING_CARD) }
        tap(AccountTags.PENDING_ABANDON)
        idleUntil { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
        idleUntil("作废返回 abandoned（不是 10006），页面恢复入口") { records().isEmpty() && !has(AccountTags.PENDING_CARD) }
        idleUntil { announcedText(t("pending_confirm.abandoned")) }
        assertEquals(DELETE_ACCOUNT, top())
        assertEquals("迟到的原请求得到 20903", 20903, serverState.results[key]?.first)
    }

    @Test
    fun lesson22_aNonTerminal10405OnApplyKeepsTheKeyAndTheNextApplyReusesItAndItsBody() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 10405, """{"min_supported_version":"9.0.0"}""")
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { records().singleOrNull()?.stateRaw == PendingKeyStore.HELD }
        assertFalse("10405 不进待确认卡", has(AccountTags.PENDING_CARD))
    }

    @Test
    fun lesson19_theDeletionPageRefreshesWhenItsWalletReadLimitExpires() {
        launch()
        // 进页的两次读取（创建与回到前台各一次，只收最新）都被限流
        repeat(2) { enqueueCode("GET", WALLET, 42901, headers = mapOf("Retry-After" to "2")) }
        openDeleteFromSettings()
        idleUntil("钱包读取限流：资金区读取失败") { has(AccountTags.DELETE_FUNDS_ERROR) }
        val reads = all("GET", WALLET).size
        now += 3_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        composeRule.mainClock.advanceTimeBy(3_000L)
        settle()
        idleUntil("到期后自动重取") { all("GET", WALLET).size > reads && has(AccountTags.DELETE_WITHDRAWABLE) }
    }
}
