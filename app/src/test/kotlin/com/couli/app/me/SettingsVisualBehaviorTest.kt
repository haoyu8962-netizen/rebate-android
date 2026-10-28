package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.account.AccountRaw
import com.couli.feature.me.MeTags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 逻辑零改动的计数证明（brief §4 第 4、5 条，brief-android §5「计数断言」）：每个改了样式或换了控件的入口，真实触摸一次 → 动作 / 导航恰好一次、
 * 目标与改前一致；进入设置（已登录）getMe 一次、游客 / 基本模式零次；本任务新增零请求。换号子页的点击由 AccountEntryIntegrationTest 原样覆盖
 * （PhoneChangePage 每秒计时，单页宿主不进入）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }
    private val empty = JsonObject(emptyMap())

    @After fun tearDown() = host.close()

    @Test fun memberEntryReadsMeOnceAndAddsNoRequest() {
        host.launch()
        assertEquals("已登录进入 getMe 恰一次", 1, host.api.count(SettingsVisualApi.GET_ME))
        assertEquals("本任务零新增请求：${host.api.calls.map { it.operationId }}", listOf("getMe"), host.api.calls.map { it.operationId })
        assertTrue(host.api.unexpected.isEmpty())
    }

    @Test fun routeRowsOpenTheirExactRouteOnceEach() {
        host.launch(public = true)
        val expected =
            listOf(
                SettingsRows.AUTH to "AuthManage",
                SettingsRows.PRIVACY to "PrivacyCenter",
                SettingsRows.HELP to "Help",
                SettingsRows.ABOUT to "About",
            )
        expected.forEachIndexed { index, (key, route) ->
            host.tapRow(key)
            assertEquals("$key → $route {}", expected.take(index + 1).map { it.second to empty }, host.env.opened)
        }
        assertTrue("没有经账号环境另开路由", host.accountEnv.opened.isEmpty())
        assertEquals(1, host.api.count(SettingsVisualApi.GET_ME))
    }

    @Test fun publicDeleteRowOpensDeletionFromSettingsOnce() {
        host.launch(public = true)
        host.tapRow(SettingsRows.DELETE)
        assertEquals("注销入口经账号环境打开、来源 settings", listOf(AccountRaw.SETTINGS), host.accountEnv.deletions)
        assertTrue(host.env.opened.isEmpty())
    }

    @Test fun nicknameAndNotifyRowsOpenInPageSubPagesWithoutRouting() {
        host.launch()
        host.openNickname()
        assertTrue("昵称是页内子页（不注册路由）", host.env.opened.isEmpty())
        assertEquals("进子页不发写请求", 0, host.nickname.saves.size)
    }

    @Test fun notifyRowReadsServiceSwitchExactlyOnce() {
        host.launch()
        host.openNotify()
        assertEquals("getNotificationSettings（Debug）恰一次", 1, host.notify.gets.size)
        assertTrue(host.env.opened.isEmpty())
        assertEquals("不申请权限", 0, host.env.appSettings)
    }

    @Test fun clearCacheRowOnlyOpensConfirmAndLogoutOnlyAsks() {
        host.launch()
        host.tapRow(SettingsRows.CLEAR_CACHE)
        assertTrue(host.has(MeTags.CLEAR_DIALOG))
        assertEquals("先确认：点行不清", 0, host.env.diskClears + host.env.webClears)
        host.tap(rule.dialogButton("取消"))
        host.tap(MeTags.SETTINGS_LOGOUT)
        assertTrue(host.has(MeTags.LOGOUT_DIALOG))
        assertEquals("先确认：点按钮不退出", 0, host.env.logouts.size)
    }

    @Test fun resetTipRowSendsExactlyOneRequest() {
        host.launch()
        host.tapRow(SettingsRows.RESET_TIP)
        host.settle("resetTip 结束") { host.has(MeTags.TOAST) }
        assertEquals(1, host.api.count(SettingsVisualApi.RESET_TIP))
        assertEquals(
            "jump_tip",
            host.api.calls
                .single { it.operationId == SettingsVisualApi.RESET_TIP }
                .pathParams["tip_key"],
        )
    }

    @Test fun guestLinkOpensLoginOncePerEdgeTapAndGuestReadsNothing() {
        host.launch(session = "guest_consented")
        val node = host.node(MeTags.SETTINGS_LOGIN)
        val hit = node.touch()
        val d = node.density()
        listOf(
            Offset(hit.center.x, hit.top + d),
            Offset(hit.center.x, hit.bottom - d),
            Offset(hit.left + d, hit.center.y),
            Offset(hit.right - d, hit.center.y),
        ).forEachIndexed { index, point ->
            rule.meTouch(point)
            host.idle()
            assertEquals("边缘 $point → Login 恰一次（G-S08 ①）", List(index + 1) { "Login" to empty }, host.env.opened)
        }
        assertEquals("游客零 getMe", 0, host.api.count(SettingsVisualApi.GET_ME))
    }

    @Test fun basicEnableFullRequestsFirstLaunchPromptOnceAndReadsNothing() {
        host.launch(session = "basic")
        host.tap(MeTags.SETTINGS_ENABLE_FULL)
        assertEquals("【开启完整功能】→ 首启隐私弹窗恰一次（G-S08 ④ 只加静态提示条）", 1, host.env.fullFunction)
        assertTrue(host.env.opened.isEmpty())
        assertEquals("基本模式零 getMe", 0, host.api.count(SettingsVisualApi.GET_ME))
    }

    @Test fun s12MeLoadErrorRetryCarriesTagAndReadsExactlyOnceMore() {
        host.api.answers[SettingsVisualApi.GET_ME] = SettingsVisualFixtures.failure()
        host.launch()
        host.settle("账号资料加载失败块") { host.has(MeTags.SETTINGS_ME_ERROR) }
        assertTrue(
            "统一错误文案带 trace6",
            rule
                .textNode(SettingsVisualFixtures.trace6, substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.height > 0,
        )
        assertTrue("公共设置入口仍在", host.has(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.PRIVACY))
        host.api.answers[SettingsVisualApi.GET_ME] = SettingsVisualFixtures.me()
        host.tap(MeTags.SETTINGS_ME_RETRY)
        host.settle("重试后的资料") {
            host.has(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.NICKNAME) &&
                host.count(SettingsVisualFixtures.original) >= 1
        }
        assertEquals("S12 SETTINGS_ME_RETRY 挂在真实【重试】上，点一次恰多读一次", 2, host.api.count(SettingsVisualApi.GET_ME))
    }

    @Test fun s12RateLimitedMeErrorDisablesRetry() {
        host.api.answers[SettingsVisualApi.GET_ME] = SettingsVisualFixtures.failure(42901)
        host.launch()
        host.settle("42901 失败块") { host.has(MeTags.SETTINGS_ME_RETRY) }
        host.node(MeTags.SETTINGS_ME_RETRY).assertIsNotEnabled()
        assertEquals(1, host.api.count(SettingsVisualApi.GET_ME))
    }

    @Test fun meLoadingShowsLoadingTextAndPublicRows() {
        host.api.hold = CompletableDeferred()
        host.launch()
        host.settle("加载中") { host.has(MeTags.SETTINGS_ME_LOADING) }
        assertTrue(host.has(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.PRIVACY))
        assertEquals(1, host.api.count(SettingsVisualApi.GET_ME))
        host.api.hold?.complete(Unit)
    }
}
