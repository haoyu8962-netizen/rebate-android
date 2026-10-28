package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S16 / G-S06：设置页轻提示——成功类（已清除、已重新显示下单须知、已撤回注销申请、昵称已修改）带绿色对勾（status.success.text），
 * 错误 / 限流类不带图标；一次只一条、播报一次。都由真实入口触发（确认框、动作行、注销来源的待显示提示、昵称保存）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualToastTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun fixtureListsEverySettingsNoticeWithItsIconClass() {
        val notices = SettingsVisualFixtures.notices().toMap()
        assertEquals(
            setOf("settings.clear_cache.done", "nickname.saved", "deletion.cancelled_toast", "settings.reset_tip.done"),
            notices.filterValues { it }.keys,
        )
        assertTrue(notices.filterValues { !it }.keys.containsAll(listOf("error.42901", "error.50001", "notify.switch_failed")))
    }

    @Test fun g06ClearedToastHasGreenCheck() {
        host.launch()
        host.tapRow(SettingsRows.CLEAR_CACHE)
        host.tap(MeTags.CLEAR_DIALOG)
        host.settle("已清除") { host.has(MeTags.TOAST) }
        assertCheck("已清除")
    }

    @Test fun g06ResetTipSuccessHasCheckAndDoesNotNavigate() {
        host.launch()
        host.tapRow(SettingsRows.RESET_TIP)
        host.settle("已重新显示下单须知") { host.has(MeTags.TOAST) }
        assertCheck("已重新显示下单须知")
        assertEquals("resetTip 恰一次", 1, host.api.count(SettingsVisualApi.RESET_TIP))
        assertEquals("已读世代推进一次", 1, host.env.tipsResets)
        assertTrue("动作行不跳页", host.env.opened.isEmpty())
    }

    @Test fun g06RateLimitedResetTipHasNoIcon() {
        host.api.answers[SettingsVisualApi.RESET_TIP] = SettingsVisualFixtures.failure(42901)
        host.launch()
        host.tapRow(SettingsRows.RESET_TIP)
        host.settle("限流提示") { host.has(MeTags.TOAST) }
        assertNoIcon("操作太频繁，请稍后再试")
        assertEquals(1, host.api.count(SettingsVisualApi.RESET_TIP))
    }

    @Test fun g06DeletionWithdrawnToastHasCheck() {
        host.launch(pendingSettingsToast = "deletion.cancelled_toast")
        host.settle("撤回注销提示") { host.has(MeTags.TOAST) }
        assertCheck("已撤回注销申请，账号恢复正常使用。")
    }

    @Test fun g06NicknameSavedToastOnSettingsHasCheck() {
        host.launch()
        host.openNickname()
        host.type(SettingsVisualFixtures.savedName)
        host.tap(MeTags.NICKNAME_SAVE)
        host.settle("回到设置页并提示") { !host.has(MeTags.NICKNAME_PAGE) && host.has(MeTags.TOAST) }
        assertCheck("昵称已修改")
        assertEquals("保存恰一次", listOf(SettingsVisualEnv.ACCOUNT to SettingsVisualFixtures.savedName), host.nickname.saves)
    }

    private fun assertCheck(message: String) {
        val toast = host.node(MeTags.TOAST)
        assertEquals(1, host.count(message))
        val px = rule.pixels()
        val green = px.countNear(toast.box(), host.palette.success, 2)
        assertTrue("G-S06 成功类带绿色对勾：$message（绿像素 $green）", green >= 10)
        assertEquals("对勾 20", 20f, toast.iconBoxes().single().width / toast.density(), 0.5f)
        assertEquals("播报一次（主动 + liveRegion 合计）：${host.spoken}", 1, host.spoken.count { it.contains(message) })
    }

    private fun assertNoIcon(message: String) {
        val toast = host.node(MeTags.TOAST)
        assertEquals(1, host.count(message))
        val px = rule.pixels()
        assertEquals("G-S06 错误 / 限流类不带图标：$message", 0, px.countNear(toast.box(), host.palette.success, 8))
        assertTrue("错误提示没有图标盒：${toast.iconBoxes()}", toast.iconBoxes().isEmpty())
        assertEquals("播报一次：${host.spoken}", 1, host.spoken.count { it.contains(message) })
    }
}
