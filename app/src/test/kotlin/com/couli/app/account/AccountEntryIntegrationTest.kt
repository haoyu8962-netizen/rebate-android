@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.shell.DELETION_PROMPT_TAG
import com.couli.app.update.FORCE_UPDATE_DELETE_TAG
import com.couli.app.update.FORCE_UPDATE_PRIVACY_TAG
import com.couli.core.network.PendingKeyStore
import com.couli.core.router.GuardKind
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.feature.login.LoginTags
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 入口与强更（brief §0 第 3、4 条，§2 第 8、9 项；AC-S1-83 ⑨–⑬、⑭、⑯）：强更页游客 → 注销账号 → 受限登录 → 注销 → 回强更「撤销注销」→ 撤销 → 回强更；
 * 受限会话只发受限表内接口、不上报推送令牌；access / refresh 过期两组；入口裁剪；DeleteAccount 丢参不记事件、深链被拦；since=null（无覆盖）三处入口隐藏、
 * 强更页无注销链接、10007 cooling 只提示不导航。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountEntryIntegrationTest : AccountHarness() {
    /** BR-ID-01 细则「受限会话」可调接口（加上登录、发码与设备注册这类登录前接口）。 */
    private val restrictedTable =
        setOf(
            "GET /v1/me/deletion",
            "POST /v1/me/deletion",
            "POST /v1/me/deletion/cancel",
            "GET /v1/me",
            "GET /v1/wallet/summary",
            "GET /v1/config",
            "POST /v1/auth/sms-codes",
            "POST /v1/auth/step-up",
            "POST /v1/idempotency-keys/abandon",
            "POST /v1/auth/login/sms",
            "POST /v1/devices",
        )

    @Test
    fun acS1_83_guestForceUpdateDeletesThroughRestrictedLoginAndReturnsToForceUpdate() {
        launch(user = null, forced = true)
        idleUntil("强更页") { top() == FORCE_UPDATE && has(FORCE_UPDATE_DELETE_TAG) }
        assertTrue(hasText(t("app_update.delete_account")))
        click(FORCE_UPDATE_DELETE_TAG)
        // 受限登录：无邀请码、无【设置】（⑯）
        idleUntil("受限登录页") { top() == "Login" && has(LoginTags.PHONE) }
        assertTrue(has(LoginTags.RESTRICTED))
        assertFalse(has(LoginTags.INVITE_ENTRY))
        assertFalse(has(LoginTags.SETTINGS))
        loginThroughPage()
        idleUntil("登录后打开 DeleteAccount 一次") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        assertEquals("deletion_only", tokens.scope)
        assertFalse("受限会话无【去提现】（G12）", has(AccountTags.DELETE_GO_WITHDRAW))
        checkWaiverAndSubmit()
        passStepUpSheet()
        idleUntil("申请成功回强更页") { top() == FORCE_UPDATE }
        idleUntil("标签变「撤销注销」") { hasText(t("app_update.cancel_deletion")) }
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_CANCEL) }
        tap(AccountTags.DELETE_CANCEL)
        idleUntil("撤销成功回强更页") { top() == FORCE_UPDATE }
        idleUntil { hasText(t("app_update.delete_account")) }
        // ⑭：受限会话下只发受限表内接口；登录后不上报推送令牌
        val outside = all().map { "${it.method} ${it.path}" }.toSet() - restrictedTable
        assertTrue("表外接口：$outside", outside.isEmpty())
        assertTrue(all("POST", "/v1/devices/push-token").isEmpty())
    }

    @Test
    fun acS1_83_anExpiredAccessTokenRefreshesIntoDeletionOnly() {
        refreshScope = "deletion_only"
        launch(forced = true)
        idleUntil { top() == FORCE_UPDATE }
        accessExpired = true
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil("刷新得到受限会话后打开注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        assertTrue("先刷新 ${all()}", all("POST", "/v1/auth/refresh").isNotEmpty())
        assertEquals("deletion_only", tokens.scope)
    }

    @Test
    fun acS1_83_anExpiredRefreshTokenStaysOnForceUpdateAndLogsInOnClick() {
        launch(forced = true)
        idleUntil { top() == FORCE_UPDATE }
        accessExpired = true
        enqueueCode("POST", "/v1/auth/refresh", 10001)
        composeRule.runOnUiThread { account.refreshForceUpdateLabel() }
        idleUntil("刷新失败只清会话") { tokens.accessToken == null }
        assertEquals("停在强更页", FORCE_UPDATE, top())
        click(FORCE_UPDATE_DELETE_TAG)
        idleUntil("点击后再登录") { top() == "Login" }
    }

    @Test
    fun forceUpdateLabelIsCancelDeletionWhileCoolingAndCancellable() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch(forced = true)
        idleUntil { top() == FORCE_UPDATE && hasText(t("app_update.cancel_deletion")) }
        serverState.deletion = deletionJson("cooling", canCancel = false)
        composeRule.runOnUiThread { account.refreshForceUpdateLabel() }
        idleUntil { hasText(t("app_update.delete_account")) }
    }

    @Test
    fun underForceUpdateThePhoneChangeEntryIsUnreachable() {
        launch(forced = true)
        idleUntil { top() == FORCE_UPDATE }
        open(SETTINGS)
        assertEquals("强更下 Router 不放行 Settings", FORCE_UPDATE, top())
        assertFalse(has(AccountTags.PHONE_PAGE))
    }

    @Test
    fun guestAndBasicModeSettingsHaveNoPhoneOrDeletionRows() {
        launch(user = null)
        open(SETTINGS)
        idleUntil { has(MeTags.SETTINGS_PAGE) }
        assertFalse(has(PHONE_ROW))
        assertFalse(has(DELETE_ROW))
    }

    @Test
    fun basicModeSettingsHaveNoAccountRows() {
        launch(decision = "basic_mode")
        open(SETTINGS)
        idleUntil { has(MeTags.SETTINGS_PAGE) }
        assertFalse(has(PHONE_ROW))
        assertFalse(has(DELETE_ROW))
    }

    @Test
    fun deleteAccountWithParamsDropsThemWithoutPrefillAndWithoutRouteParamsDropped() {
        launch()
        open(DELETE_ACCOUNT, JsonObject(mapOf("status" to JsonPrimitive("cooling"), "phone" to JsonPrimitive(PHONE_NEW))))
        idleUntil { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        val outcome = routing.router.lastOutcome.value
        assertEquals(GuardKind.OPEN, outcome?.decision?.kind)
        assertTrue(outcome?.decision?.params?.isEmpty() == true)
        assertEquals(2, outcome?.decision?.paramsDropped)
        assertFalse("没有预填（不是冷静期）", has(AccountTags.DELETE_COOLING))
        assertFalse(hasText(PHONE_NEW))
    }

    @Test
    fun aDeleteAccountDeepLinkIsBlocked() {
        launch()
        val decision =
            composeRule.runOnIdle {
                routing.router.open(RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), RouteSource.DEEPLINK))
            }
        settle()
        assertEquals(GuardKind.REJECT, decision.kind)
        assertFalse(top() == DELETE_ACCOUNT)
    }

    @Test
    fun g01_sinceNullHidesTheThreeEntriesAndTheForceUpdateLinkAnd10007OnlyPrompts() {
        override = emptySet()
        launch()
        open(SETTINGS)
        idleUntil { has(MeTags.SETTINGS_PAGE) && has(PHONE_ROW) }
        assertFalse("设置注销行隐藏", has(DELETE_ROW))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // 10007 cooling（写接口得到）只提示、不导航、键不结束
        serverState.risk = "banned"
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_PAGE) && has(MeTags.RISK_CS) }
        assertFalse("封禁说明页底部注销入口隐藏", has(AccountTags.RISK_DELETE))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val before = stack()
        val prompts = routing.deletionPrompt.value
        composeRule.runOnUiThread { routing.router.open(RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
        assertEquals("不导航（不进升级页）", before, stack())
        assertEquals(prompts + 1, routing.deletionPrompt.value)
        assertTrue(has(DELETION_PROMPT_TAG) || announcedText(t("error.10007")))
    }

    @Test
    fun g01_sinceNullForceUpdateShowsOnlyPrivacyPolicy() {
        override = emptySet()
        launch(forced = true)
        idleUntil { top() == FORCE_UPDATE && has(FORCE_UPDATE_PRIVACY_TAG) }
        assertFalse(has(FORCE_UPDATE_DELETE_TAG))
    }

    @Test
    fun g01_a10007CoolingOnPhoneChangeSubmitOnlyPromptsAndKeepsTheKey() {
        override = emptySet()
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 10007, """{"stage":"cooling"}""")
        val jobs = accountJobs()
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil { all("POST", BIND).size == 1 }
        settle()
        // FU-AND-0716 RC-03：请求记下时 10007 还没回来（记录仍是发送前写的 in_flight）；等这次提交连同页面续体结束（onResponse 写 held）再核对
        accountWorkSince(jobs, "换号提交结算")
        assertFalse("不导航到注销页", top() == DELETE_ACCOUNT)
        assertEquals("10007 不结束键（held）", PendingKeyStore.HELD, records().single().stateRaw)
    }

    /**
     * FU-AND-0716 RC-03：换号提交的 10007 被服务端挂住（请求已记下、应答未出）。先跑一遍原写法（等请求记下 → 固定轮次），断言此时记录仍是 in_flight
     * （原写法在这里就断言 held 了）；再在 [accountWorkSince] 第一次核对时放行（显式握手），返回后断言不导航、记录为 held。
     */
    @Test
    fun fuand0716_rc03_aHeld10007IsAwaitedBeforeAssertingTheHeldRecord() {
        override = emptySet()
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrived = CountDownLatch(1)
        val held = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            held.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            envelope("""{"stage":"cooling"}""", 10007)
        }
        try {
            val jobs = accountJobs()
            tap(AccountTags.PHONE_SUBMIT)
            assertTrue("提交已到服务端、应答挂住", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
            // 原写法
            idleUntil { all("POST", BIND).size == 1 }
            settle()
            assertEquals("挂住期间固定等待之后记录仍是 in_flight", PendingKeyStore.IN_FLIGHT, records().single().stateRaw)
            var released = false
            accountWorkSince(jobs, "换号提交结算") {
                if (!released) {
                    released = true
                    held.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertFalse("不导航到注销页", top() == DELETE_ACCOUNT)
            assertEquals("10007 不结束键（held）", PendingKeyStore.HELD, records().single().stateRaw)
        } finally {
            held.countDown()
        }
    }

    @Test
    fun aCooling10007ElsewhereOpensTheDeletionPageWhenAvailable() {
        launch()
        open(SETTINGS)
        idleUntil { has(MeTags.SETTINGS_PAGE) }
        composeRule.runOnUiThread { routing.router.open(RouteRequest(DELETE_ACCOUNT, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        idleUntil { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
        assertNotNull(routing.router.lastOutcome.value)
    }

    @Test
    fun riskNoticeBannedOpensDeleteAccountThroughTheRouterWhenAvailable() {
        serverState.risk = "banned"
        launch()
        open("RiskNotice")
        idleUntil("封禁态底部「注销账号」") { has(AccountTags.RISK_DELETE) }
        click(AccountTags.RISK_DELETE)
        idleUntil { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
    }
}
