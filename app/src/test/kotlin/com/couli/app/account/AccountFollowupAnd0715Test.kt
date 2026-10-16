@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.update.FORCE_UPDATE_DELETE_TAG
import com.couli.core.network.ApiCall
import com.couli.core.network.UpdateRaw
import com.couli.feature.me.AccountTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0715 第 3 项（F-AND-35：APP-13a RC-02「已发请求的存活条件只看同账号 + 同登录世代」补用例）。走真实 AppShell、Router 与网络层，传输层换成本地假服务。
 * - 申请注销：deletion_only 注销页点【申请注销】，请求已到服务端、成功回包挂住 → 同世代刷新为 full → 放行：键照常结算（不落 UNKNOWN）、只发 1 次。
 * - 撤回注销：撤回只在 full 会话开放（DeletionPolicies canWithdraw），「升 full」走不到；改用同账号同登录世代里作用域来回变化（full → deletion_only → full），
 *   撤回已到服务端、回包挂住期间完成（超过 watchAbandoned 的核对周期）→ 放行：迟到的成功照常结算（撤回成功提示，不出网络错误）。
 * - 换号提交：受限会话不开放换号（设置页手机号行不可用），同样用 full → deletion_only → full 的同世代作用域变化验证共用的 writeOwnership：已到服务端的提交
 *   照常结算（键结束、不落 UNKNOWN）。
 * 变异（mutations.txt）：两个控制器的 stillOwned 改回完整固定身份（ownsWrite），对应用例失败。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountFollowupAnd0715Test : AccountHarness() {
    /** 一次真实的令牌刷新（getMe 收到 10002 → 单飞刷新），刷新结果的作用域为 [scope]；同账号、同登录世代。 */
    private fun refreshTo(scope: String) {
        refreshScope = scope
        accessExpired = true
        Thread { api.execute(ApiCall("getMe")) }.start()
        idleUntil("刷新为同世代的 $scope") { account.identity.value.scope == scope }
    }

    /** 作用域在同一登录世代里来回变化（full → deletion_only → full），每段都超过 watchAbandoned 的 50ms 核对周期。 */
    private fun bounceScopeKeepingTheLoginEpoch() {
        val epoch = tokens.loginEpoch
        refreshTo("deletion_only")
        repeat(WATCH_ROUNDS) { settle() }
        refreshTo("full")
        repeat(WATCH_ROUNDS) { settle() }
        assertEquals("同一登录世代", epoch, tokens.loginEpoch)
    }

    @Test
    fun fand35_aDeletionOnlyApplyAlreadyAtTheServerSettlesAfterASameEpochFullRefresh() {
        serverState.requireStepUp = false
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil { top() == FORCE_UPDATE && has(FORCE_UPDATE_DELETE_TAG) }
        idleUntil("冷启动的屏障刷新已结束") { all("POST", REFRESH).isNotEmpty() }
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil("受限注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        checkWaiver()
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
        val key: String
        try {
            tap(AccountTags.DELETE_SUBMIT)
            assertTrue("申请已到服务端", arrived.await(WAIT_S, TimeUnit.SECONDS))
            key = records().single().key
            refreshScope = "full"
            networkUpdate = UpdateRaw.SUPPORTED
            Thread { api.onUpdateStateChanged(UpdateRaw.SUPPORTED) }.start()
            idleUntil("刷新为同世代的 full") { account.identity.value.scope == "full" }
            assertEquals("同一登录世代", epoch, tokens.loginEpoch)
            repeat(WATCH_ROUNDS) { settle() }
            assertEquals("申请仍在途（键未结束）", key, records().single().key)
        } finally {
            release.countDown()
        }
        idleUntil("迟到的成功照常结算：原键结束 ${records()}") { records().isEmpty() }
        settle()
        assertEquals("申请只发出 1 次", 1, all("POST", DELETION).size)
        assertEquals("原键", key, all("POST", DELETION).single().key)
        assertFalse("不出网络错误提示", hasText(t(com.couli.core.me.MeDraftTexts.NETWORK_KEY)))
    }

    @Test
    fun fand35_aWithdrawAlreadyAtTheServerSettlesAcrossASameEpochScopeChange() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        idleUntil("冷静期") { has(AccountTags.DELETE_CANCEL) && enabled(AccountTags.DELETE_CANCEL) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val answered = CountDownLatch(1)
        enqueue("POST", CANCEL) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            val cancelled = deletionJson("cancelled", canCancel = false, reason = "user")
            serverState.deletion = cancelled
            answered.countDown()
            envelope(cancelled)
        }
        val reads: Int
        try {
            tap(AccountTags.DELETE_CANCEL)
            assertTrue("撤回已到服务端", arrived.await(WAIT_S, TimeUnit.SECONDS))
            bounceScopeKeepingTheLoginEpoch()
            repeat(WATCH_ROUNDS) { settle() }
            reads = all("GET", DELETION).size
        } finally {
            release.countDown()
        }
        assertTrue(answered.await(WAIT_S, TimeUnit.SECONDS))
        // 撤回只在 full 开放、作用域一变旧页面实例即作废（alive() 为假，界面由新实例负责）；迟到的成功只做共享结算：注销进度变了 → 强更页注销入口标签
        // 重新读取注销进度（deletionChanged）。在途请求若被 watchAbandoned 取消（RC-02 之前的口径），这一步不会发生
        idleUntil("迟到的撤回成功照常共享结算（重新读取注销进度）") { all("GET", DELETION).size > reads }
        settle()
        assertEquals("撤回只发出 1 次", 1, all("POST", CANCEL).size)
        assertFalse("不出网络错误提示", hasText(t(com.couli.core.me.MeDraftTexts.NETWORK_KEY)))
    }

    @Test
    fun fand35_aPhoneChangeSubmitAlreadyAtTheServerSettlesAcrossASameEpochScopeChange() {
        serverState.requireStepUp = false
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        idleUntil("【提交】可用") { enabled(AccountTags.PHONE_SUBMIT) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) { request ->
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.results[request.key.orEmpty()] = 0 to "{}"
            serverState.phoneBound = true
            envelope("{}")
        }
        val key: String
        try {
            tap(AccountTags.PHONE_SUBMIT)
            assertTrue("提交已到服务端", arrived.await(WAIT_S, TimeUnit.SECONDS))
            key = records().single().key
            bounceScopeKeepingTheLoginEpoch()
            assertEquals("提交仍在途（键未结束）", key, records().single().key)
        } finally {
            release.countDown()
        }
        idleUntil("迟到的成功照常结算：原键结束 ${records()}") { records().isEmpty() }
        settle()
        assertEquals("提交只发出 1 次", 1, all("POST", BIND).size)
        assertEquals("原键", key, all("POST", BIND).single().key)
    }

    /**
     * FU-AND-0715 第 4 项（F-X-09）：受限会话的注销页读钱包摘要得到非强更的 10405（本机版本不低于 min_supported_version：网络层只刷新，REFRESH_ONLY）
     * → 资金区显示统一错误文案（error.50001 + trace 末 6 位）+【重试】，不进强更、不说成断网；【重试】成功后显示资金。
     */
    @Test
    fun fx09_aRefreshOnly10405OnTheDeletionPageFundsReadShowsTheUnifiedErrorWithRetry() {
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil { top() == FORCE_UPDATE && has(FORCE_UPDATE_DELETE_TAG) }
        idleUntil("冷启动的屏障刷新已结束") { all("POST", REFRESH).isNotEmpty() }
        // 注销页的资金读取（会话刷新之后页面会再读一次：两次都是非强更的 10405）
        repeat(2) { enqueueCode("GET", WALLET, 10405, """{"min_supported_version":"0.0.1"}""", trace = "trace-fx0905") }
        val forced = accountForcedCalls
        val refreshes = all("POST", REFRESH).size
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil("受限注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
        idleUntil("资金区读取失败") { has(AccountTags.DELETE_FUNDS_ERROR) }
        settle()
        assertTrue("网络层只刷新（REFRESH_ONLY）", all("POST", REFRESH).size > refreshes)
        assertEquals("页面不进强更", forced, accountForcedCalls)
        val shown = textOf(AccountTags.DELETE_FUNDS_ERROR)
        assertTrue("统一错误文案：$shown", shown.contains(t("error.50001").substringBefore("（")))
        assertTrue("带 trace 末 6 位：$shown", shown.contains("fx0905"))
        assertFalse("不说成断网：$shown", shown.contains(t(com.couli.core.me.MeDraftTexts.NETWORK_KEY)))
        assertTrue("【重试】可用", enabled(AccountTags.DELETE_FUNDS_RETRY))
        tap(AccountTags.DELETE_FUNDS_RETRY)
        idleUntil("重试成功显示资金") { has(AccountTags.DELETE_FUNDS) && !has(AccountTags.DELETE_FUNDS_ERROR) }
        assertEquals(forced, accountForcedCalls)
    }

    private companion object {
        const val REFRESH = "/v1/auth/refresh"
        const val WATCH_ROUNDS = 5
    }
}
