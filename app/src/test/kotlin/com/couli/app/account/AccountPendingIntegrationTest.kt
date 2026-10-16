@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.PendingKeyStore
import com.couli.core.router.UpdateCheck
import com.couli.feature.me.AccountTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 未决键两套（注销 requestDeletion、换号 bindPhone；brief §0 第 1 条、§2 第 13 项「未决键」）：结果未知进待确认、0 次新键；确认与放弃的各分支；
 * 不按时间解除；换账号隔离；冷启动恢复；原请求迟到两种结局；held（共性第 7 条：非终结响应保留原记录、再点同键原体）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountPendingIntegrationTest : AccountHarness() {
    // ---------------------------------------------------------------- 注销：进入待确认

    private fun deletionPending(): String {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueTimeout("POST", DELETION)
        checkWaiverAndSubmit()
        idleUntil("待确认卡") { has(AccountTags.PENDING_CARD) }
        val record = records().single()
        assertEquals(PendingKeyStore.UNKNOWN, record.stateRaw)
        return record.key
    }

    private fun confirmDeletion() = tap(AccountTags.PENDING_CONFIRM)

    private fun abandonPending() {
        tap(AccountTags.PENDING_ABANDON)
        idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
        tap(AccountTags.ABANDON_OK)
    }

    @Test
    fun deletionTimeoutEntersPendingWithZeroNewKeyRequests() {
        val key = deletionPending()
        assertFalse("新的申请入口置灰", enabled(AccountTags.DELETE_SUBMIT))
        settle()
        assertEquals("不自动重发、不换键", 1, all("POST", DELETION).size)
        assertEquals(key, all("POST", DELETION).single().key)
        assertTrue("警告卡 alert：标题与注销描述", hasText(t("pending_confirm.title")) && hasText(t("pending_confirm.deletion.desc")))
        assertTrue("摘要固定两句（G24）", hasText(t("pending_confirm.summary.waiver")))
    }

    @Test
    fun deletionConfirmSuccessUsesTheOriginalKeyAndEntersCooling() {
        val key = deletionPending()
        confirmDeletion()
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val posts = all("POST", DELETION)
        assertEquals(2, posts.size)
        assertEquals("原键", key, posts[1].key)
        assertEquals("原体（空体）", posts[0].body, posts[1].body)
        assertTrue(records().isEmpty())
    }

    @Test
    fun deletionConfirm3xxxxEndsTheKeyAndShowsTheError() {
        deletionPending()
        enqueueCode("POST", DELETION, 30412)
        confirmDeletion()
        idleUntil { records().isEmpty() }
        idleUntil { !has(AccountTags.PENDING_CARD) }
        assertTrue(hasText(t("error.30412")))
    }

    @Test
    fun deletionConfirm20903EndsTheKey() {
        deletionPending()
        enqueueCode("POST", DELETION, 20903)
        confirmDeletion()
        idleUntil { records().isEmpty() && !has(AccountTags.PENDING_CARD) }
    }

    @Test
    fun deletionConfirm10005GuidesAndStaysPendingAfterReturning() {
        val key = deletionPending()
        enqueueCode("POST", DELETION, 10005)
        confirmDeletion()
        idleUntil("补办引导 BindPhone") { top() == "BindPhone" }
        assertEquals(PendingKeyStore.UNKNOWN, records().single().stateRaw)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回来仍是待确认") { top() == DELETE_ACCOUNT && has(AccountTags.PENDING_CARD) }
        assertEquals(key, records().single().key)
        assertEquals("补办后不自动重放", 2, all("POST", DELETION).size)
    }

    @Test
    fun deletionConfirm20003ShowsCannotConfirmAndKeepsTheKey() {
        val key = deletionPending()
        enqueueCode("POST", DELETION, 20003)
        confirmDeletion()
        idleUntil { has(AccountTags.PENDING_NOTICE) }
        assertTrue(hasText(t("pending_confirm.cannot_confirm")))
        assertEquals(key, records().single().key)
        assertEquals(PendingKeyStore.UNKNOWN, records().single().stateRaw)
    }

    @Test
    fun deletionConfirm40901And5xxxxShowRetryLater() {
        val key = deletionPending()
        enqueueCode("POST", DELETION, 40901)
        confirmDeletion()
        idleUntil { hasText(t("pending_confirm.retry_later")) }
        enqueueCode("POST", DELETION, 50001, trace = "trace-abc123")
        val jobs = accountJobs()
        confirmDeletion()
        idleUntil { all("POST", DELETION).size == 3 }
        settle()
        // FU-AND-0716 窄修三：请求记下时应答还没出；等这次确认连同页面续体结束再核对提示
        accountWorkSince(jobs, "第二次确认结算")
        assertTrue(hasText(t("pending_confirm.retry_later")))
        assertEquals(key, records().single().key)
    }

    @Test
    fun deletionConfirm42901DisablesConfirmAndAbandonUntilRetryAfter() {
        deletionPending()
        enqueueCode("POST", DELETION, 42901, headers = mapOf("Retry-After" to "30"))
        confirmDeletion()
        idleUntil { hasText(t("pending_confirm.retry_later")) }
        assertFalse("两个按钮都置灰", enabled(AccountTags.PENDING_CONFIRM))
        assertFalse(enabled(AccountTags.PENDING_ABANDON))
    }

    @Test
    fun deletionConfirm10003OpensTheSheetAndResendsTheOriginalKey() {
        val key = deletionPending()
        serverState.requireStepUp = true
        confirmDeletion()
        passStepUpSheet()
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val posts = all("POST", DELETION)
        assertEquals(3, posts.size)
        assertTrue(posts.all { it.key == key })
        assertNotNull(posts.last().stepUp)
    }

    @Test
    fun deletionConfirm10007KeepsTheKeyPendingAndRefetches() {
        val key = deletionPending()
        enqueueCode("POST", DELETION, 10007, """{"stage":"cooling"}""")
        val reads = all("GET", DELETION).size
        confirmDeletion()
        idleUntil { all("GET", DELETION).size > reads }
        settle()
        assertTrue("键不结束（G27）", has(AccountTags.PENDING_CARD))
        assertEquals(key, records().single().key)
    }

    // ---------------------------------------------------------------- 注销：放弃

    @Test
    fun deletionAbandonAbandonedReturnsToInitialAndTheNextApplyUsesANewKey() {
        val key = deletionPending()
        abandonPending()
        idleUntil { records().isEmpty() && !has(AccountTags.PENDING_CARD) }
        val abandon = all("POST", ABANDON).single()
        assertEquals("account_deletion", body(abandon)["action"].toString().trim('"'))
        assertEquals(key, body(abandon)["idempotency_key"].toString().trim('"'))
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_COOLING) }
        assertFalse(all("POST", DELETION).last().key == key)
    }

    @Test
    fun deletionAbandonCompletedOkShowsAlreadyDoneAndCooling() {
        val key = deletionPending()
        serverState.results[key] = 0 to deletionJson("cooling", canCancel = true)
        abandonPending()
        idleUntil { has(AccountTags.DELETE_COOLING) }
        assertTrue(records().isEmpty())
    }

    @Test
    fun deletionAbandonCompleted3xxxxShowsTheOriginalErrorAndInitial() {
        val key = deletionPending()
        serverState.results[key] = 30412 to "null"
        abandonPending()
        idleUntil { records().isEmpty() && !has(AccountTags.PENDING_CARD) }
        assertTrue(has(AccountTags.DELETE_SUBMIT))
    }

    @Test
    fun deletionAbandon40901ShowsBusyAndKeepsTheKey() {
        val key = deletionPending()
        enqueueCode("POST", ABANDON, 40901)
        abandonPending()
        idleUntil { hasText(t("pending_confirm.abandon_busy")) }
        assertEquals(key, records().single().key)
    }

    @Test
    fun deletionAbandon10405EntersForceUpdateAndKeepsTheKey() {
        val key = deletionPending()
        enqueueCode("POST", ABANDON, 10405)
        abandonPending()
        idleUntil("进强更") { update.routerUpdate == UpdateCheck.BELOW_MIN }
        assertEquals(key, records().single().key)
    }

    // ---------------------------------------------------------------- 不按时间解除、换账号、冷启动

    @Test
    fun pendingSurvivesMidnight24HoursAnd30Days() {
        val key = deletionPending()
        listOf(12 * 3_600_000L, DAY_MS, 31 * DAY_MS).forEach { delta ->
            now += delta
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
            openDeleteFromSettings()
            idleUntil("+$delta ms 仍待确认") { has(AccountTags.PENDING_CARD) }
            assertEquals(key, records().single().key)
        }
        assertEquals("从不自动发出", 1, all("POST", DELETION).size)
    }

    @Test
    fun anotherAccountNeitherSendsNorSeesThePendingAndTheOriginalAccountRestoresIt() {
        val key = deletionPending()
        composeRule.runOnUiThread { navController.popBackStack() }
        switchSession(USER_B)
        openDeleteFromSettings()
        assertFalse("B 看不到 A 的待确认卡", has(AccountTags.PENDING_CARD))
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_COOLING) }
        assertTrue("B 的申请不带 A 的键", all("POST", DELETION).last().key != key)
        assertTrue("A 的键不随 B 发出", all("POST", DELETION).filter { it.user == USER_B }.none { it.key == key })
        composeRule.runOnUiThread { navController.popBackStack() }
        switchSession(USER_A)
        serverState.deletion = "null"
        openDeleteFromSettings()
        idleUntil("A 回来恢复待确认") { has(AccountTags.PENDING_CARD) }
        assertEquals(key, records(USER_A).single().key)
    }

    @Test
    fun coldStartRestoresAnInFlightRecordAsPending() {
        seedInFlight("requestDeletion", "key-from-last-process", "", "", createdAt = now - DAY_MS)
        launch()
        openDeleteFromSettings()
        idleUntil("冷启动：上个进程的在途 → 结果未知 → 待确认") { has(AccountTags.PENDING_CARD) }
        assertEquals(PendingKeyStore.UNKNOWN, records().single().stateRaw)
        assertTrue("冷启动不自动发出", all("POST", DELETION).isEmpty())
    }

    // ---------------------------------------------------------------- 原请求迟到

    @Test
    fun lateOriginalAcceptedBeforeAbandonTurnsAbandonIntoCompleted() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueue("POST", DELETION) { recorded ->
            // 服务端已受理（写入幂等记录），响应在途中丢失
            serverState.results[recorded.key.orEmpty()] = 0 to deletionJson("cooling", canCancel = true)
            okhttp3.mockwebserver
                .MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.PENDING_CARD) }
        abandonPending()
        idleUntil("作废得 completed，进冷静期") { has(AccountTags.DELETE_COOLING) }
        assertTrue(records().isEmpty())
    }

    @Test
    fun lateOriginalArrivingAfterAbandonIsRejectedWith20903() {
        val key = deletionPending()
        abandonPending()
        idleUntil { records().isEmpty() }
        assertEquals("迟到的原请求在服务端得到 20903", 20903, serverState.results[key]?.first)
        assertFalse(has(AccountTags.DELETE_COOLING))
    }

    // ---------------------------------------------------------------- held（共性第 7 条）

    @Test
    fun lesson07_a50401KeepsTheRecordHeldAndTheNextClickResendsTheSameKey() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 50401, trace = "trace-654321")
        checkWaiverAndSubmit()
        idleUntil { has(AccountTags.DELETE_ERROR) }
        assertFalse("held 不进待确认卡", has(AccountTags.PENDING_CARD))
        val held = records().single()
        assertEquals(PendingKeyStore.HELD, held.stateRaw)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil { has(AccountTags.DELETE_COOLING) }
        val posts = all("POST", DELETION)
        assertEquals(2, posts.size)
        assertEquals("同一个键（存储层保留，不重新生成）", posts[0].key, posts[1].key)
        assertEquals(held.key, posts[1].key)
    }

    @Test
    fun lesson07_a10007OnApplyKeepsTheKeyHeld() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 10007, """{"stage":"cooling"}""")
        checkWaiverAndSubmit()
        idleUntil { records().isNotEmpty() && records().single().stateRaw == PendingKeyStore.HELD }
        assertFalse(has(AccountTags.PENDING_CARD))
    }

    // ---------------------------------------------------------------- 换号：待确认

    private fun phonePending(): String {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueTimeout("POST", BIND)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("换号待确认卡") { has(AccountTags.PENDING_CARD) }
        val record = records().single { it.operationId == "bindPhone" }
        assertEquals(PendingKeyStore.UNKNOWN, record.stateRaw)
        assertEquals("摘要只放脱敏新号（G24）", "139****1234", record.summary)
        return record.key
    }

    @Test
    fun phoneTimeoutPendingConfirmSuccessGoesToDone() {
        val key = phonePending()
        assertTrue(hasText("139****1234"))
        settle()
        assertEquals(1, all("POST", BIND).size)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { has(AccountTags.PHONE_DONE) }
        val posts = all("POST", BIND)
        assertEquals(key, posts.last().key)
        assertEquals("原键原体（含原验证码）", posts[0].body, posts[1].body)
        assertTrue(records().isEmpty())
    }

    @Test
    fun phoneConfirm20003IsCannotConfirmWithoutFieldError() {
        val key = phonePending()
        enqueueCode("POST", BIND, 20003)
        tap(AccountTags.PENDING_CONFIRM)
        idleUntil { hasText(t("pending_confirm.cannot_confirm")) }
        assertFalse("不在字段旁标红", has(AccountTags.PHONE_FIELD_ERROR))
        assertEquals(key, records().single().key)
    }

    @Test
    fun phoneConfirm10003OpensTheSheetAndKeepsTheOriginalKey() {
        val key = phonePending()
        serverState.validTokens.clear()
        tap(AccountTags.PENDING_CONFIRM)
        passStepUpSheet()
        idleUntil { has(AccountTags.PHONE_DONE) }
        assertTrue(all("POST", BIND).all { it.key == key })
    }

    @Test
    fun phoneAbandonAbandonedReturnsToStep1AndClears() {
        val key = phonePending()
        abandonPending()
        idleUntil("回第 1 步重新验证（G23）") { has(AccountTags.PHONE_STEP1) }
        assertTrue(records().isEmpty())
        assertEquals("phone_change", body(all("POST", ABANDON).single())["action"].toString().trim('"'))
        assertEquals(key, body(all("POST", ABANDON).single())["idempotency_key"].toString().trim('"'))
    }

    @Test
    fun phoneAbandonCompletedOkGoesToDone() {
        val key = phonePending()
        serverState.results[key] = 0 to "{}"
        abandonPending()
        idleUntil { has(AccountTags.PHONE_DONE) }
        assertTrue(hasText("139****1234"))
    }

    @Test
    fun phoneAbandon40901And10405KeepTheKey() {
        val key = phonePending()
        enqueueCode("POST", ABANDON, 40901)
        abandonPending()
        idleUntil { hasText(t("pending_confirm.abandon_busy")) }
        enqueueCode("POST", ABANDON, 10405)
        abandonPending()
        idleUntil { update.routerUpdate == UpdateCheck.BELOW_MIN }
        assertEquals(key, records().single().key)
    }
}
