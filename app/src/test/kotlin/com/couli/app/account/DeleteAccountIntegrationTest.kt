@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.core.network.PendingKeyStore
import com.couli.feature.me.AccountTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 注销页主链（brief §2 第 13 项「注销主链」「AC-S2-58」「AC-S2-60」「30412」「冷静期」）：真实设置页 → Router → DeleteAccount → 勾选 → 申请 → 二次验证面板 →
 * 原键重放 → 冷静期；负余额、30416、30412、撤回各去向。抓包断言键、token 与请求次数。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeleteAccountIntegrationTest : AccountHarness() {
    @Test
    fun mainChainStepUpReplaysTheSameKeyOnceAndEntersCooling() {
        launch()
        openDeleteFromSettings()
        // 初始态：资金区按服务端字段与 CouliFormat；未勾选时【申请注销】置灰
        idleUntil { has(AccountTags.DELETE_WITHDRAWABLE) }
        assertTrue(hasText(CouliFormat.money(1234)))
        assertTrue(hasText(CouliFormat.money(5678)))
        assertFalse("未勾选不可申请", enabled(AccountTags.DELETE_SUBMIT))
        tap(AccountTags.DELETE_WAIVER)
        assertTrue(enabled(AccountTags.DELETE_SUBMIT))
        tap(AccountTags.DELETE_SUBMIT)
        // 10003 → 面板（account_deletion 发码）→ 验证 → 原键重放 → 冷静期
        passStepUpSheet()
        idleUntil("冷静期") { has(AccountTags.DELETE_COOLING) }
        val applies = all("POST", DELETION)
        assertEquals("首发 10003 + 重放一次", 2, applies.size)
        assertEquals("同一个键", 1, applies.map { it.key }.toSet().size)
        assertNull(applies[0].stepUp)
        assertNotNull(applies[1].stepUp)
        assertEquals("只受理一次", 1, serverState.results.values.count { it.first == 0 })
        val sms = all("POST", SMS).single()
        assertEquals("step_up", body(sms)["purpose"].toString().trim('"'))
        assertEquals("account_deletion", body(sms)["action"].toString().trim('"'))
        assertEquals(PHONE_OLD, body(sms)["phone"].toString().trim('"'))
        assertTrue("无请求体（G14）", applies.all { it.body.isEmpty() })
        assertTrue("键结束：没有未决记录", records().isEmpty())
        assertTrue(has(AccountTags.DELETE_CANCEL))
    }

    @Test
    fun acS2_58_negativeBalanceOnEntryPromptsAndDisablesWithoutAnyRequestDeletion() {
        serverState.negativeFen = 200
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        assertTrue("账户有待扣回金额 ¥2", hasText(CouliFormat.money(200)))
        assertTrue(has(AccountTags.DELETE_CONTACT_CS))
        assertFalse("勾选置灰", enabled(AccountTags.DELETE_WAIVER))
        assertFalse("申请置灰", enabled(AccountTags.DELETE_SUBMIT))
        assertTrue(all("POST", DELETION).isEmpty())
    }

    @Test
    fun acS2_58_a30416OnApplyShowsTheSameSentenceWithThisAmountAndEndsTheKey() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 30416, """{"amount_fen":200}""")
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("30416 提示") { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        assertTrue(hasText(CouliFormat.money(200)))
        assertTrue("键结束（3xxxx 写入幂等记录）", records().isEmpty())
        // 回正后可以申请（进页重取，negative_fen=0）：新的页面实例用新键
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        openDeleteFromSettings()
        tap(AccountTags.DELETE_WAIVER)
        assertTrue("回正后可申请", enabled(AccountTags.DELETE_SUBMIT))
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val keys = all("POST", DELETION).map { it.key }
        assertEquals(2, keys.size)
        assertNotEquals("再点用新键", keys[0], keys[1])
    }

    @Test
    fun acS2_60_systemCancelledWithNegativeShowsNegativeAndRecoversToInitial() {
        serverState.deletion = deletionJson("cancelled", canCancel = false, reason = "negative_balance")
        serverState.negativeFen = 567
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        assertTrue(hasText(CouliFormat.money(567)))
        assertFalse(enabled(AccountTags.DELETE_SUBMIT))
        composeRule.runOnUiThread { navController.popBackStack() }
        serverState.negativeFen = 0
        settle()
        openDeleteFromSettings()
        assertFalse("回正：初始态", has(AccountTags.DELETE_NEGATIVE_NOTICE))
        tap(AccountTags.DELETE_WAIVER)
        assertTrue(enabled(AccountTags.DELETE_SUBMIT))
    }

    @Test
    fun a30412EndsTheKeyKeepsTheCheckboxAndTheNextClickUsesANewKey() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 30412)
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_ERROR) }
        assertTrue(hasText(t("error.30412")))
        assertTrue("勾选保留（G14），可以再点", enabled(AccountTags.DELETE_SUBMIT))
        assertTrue(records().isEmpty())
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val keys = all("POST", DELETION).map { it.key }
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun coolingShowsServerTimesAndCanCancelFalseHidesTheButton() {
        serverState.deletion = deletionJson("cooling", canCancel = false)
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_CANCEL_EXPIRED) }
        assertFalse("can_cancel=false 不显示撤回（G15）", has(AccountTags.DELETE_CANCEL))
        assertTrue(hasText(t("error.10007")))
    }

    @Test
    fun coolingDeadlineUsesBrText11AndCancelFromSettingsReturnsToSettingsWithAToast() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        idleUntil { has(AccountTags.DELETE_COOLING_DEADLINE) }
        val until =
            java.time.OffsetDateTime
                .parse("2026-10-21T10:00:00+08:00")
                .toInstant()
                .toEpochMilli()
        assertTrue(hasText(CouliFormat.timeListFuture(until, now)))
        tap(AccountTags.DELETE_CANCEL)
        idleUntil("回设置页") { top() == SETTINGS }
        assertEquals(1, all("POST", CANCEL).size)
        idleUntil("设置页轻提示「已撤回注销申请」") { announcedText(t("deletion.cancelled_toast")) }
    }

    @Test
    fun cancel10007StaysOnCoolingAndRefetches() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", CANCEL, 10007, """{"stage":"processing"}""")
        val reads = all("GET", DELETION).size
        tap(AccountTags.DELETE_CANCEL)
        idleUntil { has(AccountTags.DELETE_ERROR) }
        assertEquals(DELETE_ACCOUNT, top())
        assertTrue(hasText(t("deletion.cooling.cancel_expired")))
        idleUntil("重取进度") { all("GET", DELETION).size > reads }
    }

    @Test
    fun cancelTimeoutNeverResendsAutomaticallyAndReconcilesByReading() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        enqueueTimeout("POST", CANCEL)
        val reads = all("GET", DELETION).size
        tap(AccountTags.DELETE_CANCEL)
        idleUntil("核对读取") { all("GET", DELETION).size > reads }
        settle()
        assertEquals("不自动重发（G17）", 1, all("POST", CANCEL).size)
        assertTrue("仍冷静期：保留人工重试", has(AccountTags.DELETE_CANCEL))
        assertTrue(records().none { it.operationId == "cancelDeletion" })
    }

    @Test
    fun lesson14_cancelUnknownThenReadingCancelledSettlesAsSuccess() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        enqueueTimeout("POST", CANCEL) {
            serverState.deletion = deletionJson("cancelled", canCancel = false, reason = "user")
        }
        tap(AccountTags.DELETE_CANCEL)
        idleUntil("核对读到 cancelled → 走撤回成功结算，回设置页") { top() == SETTINGS }
        assertEquals(1, all("POST", CANCEL).size)
    }

    @Test
    fun theWaiverIsRequiredAndUncheckedByDefaultAndPhoneUnboundOnlyPromptsWithoutRequests() {
        serverState.phoneBound = false
        launch()
        openDeleteFromSettings()
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_NO_PHONE) }
        assertTrue(hasText(t("deletion.step_up_unsupported")))
        assertTrue("full 会话给【去绑定手机号】", has(AccountTags.DELETE_BIND_PHONE))
        assertTrue("G10：不发请求", all("POST", DELETION).isEmpty() && all("POST", SMS).isEmpty())
    }

    @Test
    fun applyIn_flightKeepsTheButtonDisabledAndAPendingRecordIsWrittenBeforeSending() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        enqueue("POST", DELETION) {
            arrived.countDown()
            release.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
            envelope(deletionJson("cooling", canCancel = true))
        }
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        assertTrue(arrived.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("发送前写入在途记录", PendingKeyStore.IN_FLIGHT, records().single().stateRaw)
        assertFalse("在途不可再点", enabled(AccountTags.DELETE_SUBMIT))
        release.countDown()
        idleUntil { has(AccountTags.DELETE_COOLING) }
        assertEquals(1, all("POST", DELETION).size)
    }
}
