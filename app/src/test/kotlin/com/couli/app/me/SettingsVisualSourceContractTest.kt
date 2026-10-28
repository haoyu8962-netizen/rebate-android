package com.couli.app.me

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视图层回调与可用条件「原样搬」的源码契约（brief §5「回调捕获」、§9 第 9 条执行器边界）：换控件之后回调仍只捕获渲染时的执行器、版本与编号，
 * lambda 里不读可观察状态。比对前去掉全部空白（APP-09v-e v3：trim / 去空白，Kotlin 里不改记号含义）；只查 UI 段（红线段由 SettingsVisualGuardTest 冻结）。
 * 行为本身另由真实触摸计数测试守（SettingsVisualBehaviorTest 等），这里防的是「行为碰巧对、接线换了」。
 */
class SettingsVisualSourceContractTest {
    private val root = SettingsVisualFixtures.root
    private val me = "feature/me/src/main/kotlin/com/couli/feature/me/"

    @Test fun settingsScreenCallbacksAreMovedVerbatim() {
        val ui = flat(settingsUi())
        mapOf(
            "controller.openAgreement(params,version,sheet)" to 1,
            "controller.closeSheet(sheet)" to 1,
            "controller.confirmClear(dialog,version)" to 1,
            "controller.confirmLogout(dialog,version)" to 1,
            "dismissOnClickOutside=false" to 1,
            "controller.login(version)" to 1,
            "controller.enableFull(version)" to 1,
            "controller.askLogout(version)" to 1,
            "controller.retryMe()" to 1,
            "OnResumed(controller){controller.refresh()}" to 1,
            "controller.nicknameSaved(sub)" to 1,
            "controller.closeNotify(sub)" to 1,
            "accountDeps.notices.take(accountIdentity)?.let(controller::showToast)" to 1,
            "config.bindLeave(controller::leave)" to 1,
            "rememberPageController(SettingsController.ROUTE,identitytoaccountIdentity?.scope" to 1,
        ).forEach { (code, count) -> assertEquals("Settings.kt UI 段回调原样：$code", count, occurrences(ui, code)) }
        assertTrue("行点击仍是 controller.onRow(row, version)", occurrences(ui, "controller.onRow(row,version)") >= 1)
        assertEquals("取消 / 系统返回只走 closeDialog(dialog)（两个确认框）", 2, occurrences(ui, "controller.closeDialog(dialog)"))
        assertTrue("【退出登录】可用条件原样", ui.contains("enabled=!state.busy"))
        assertTrue("剪贴板行仍由 App 给（config.clipboardRow）", ui.contains("config.clipboardRow"))
    }

    @Test fun g07DialogsUseExistingParametersAndNoPageLevelDialog() {
        val ui = flat(settingsUi())
        assertTrue("G-S07 两个确认框都标题居中（CouliDialog 已有 titleAlign）", occurrences(ui, "TextAlign.Center") >= 2)
        assertTrue("G-S07 两个确认框都传 overlay.scrim（CouliDialog 已有 scrim）", occurrences(ui, "CouliColors.overlayScrim") >= 2)
        assertEquals("不得在页面层另建 Dialog 绕开组件库字号接续", 0, Regex("""(?<![A-Za-z])Dialog\(""").findAll(ui).count())
    }

    @Test fun subPageCallbacksAndEnabledConditionsAreMovedVerbatim() {
        val source = File(root, me + "SettingsSubPages.kt").readText()
        // 只取两个页面函数（中间的 NotifyState / NotifySettingsController 是红线段，由守卫测试冻结）
        val nicknamePage = source.substring(source.indexOf("internal fun NicknamePage("), source.indexOf("/** 通知设置子页的状态。"))
        val notifyPage = source.substring(source.indexOf("internal fun NotifySettingsPage("))
        val pages = flat(nicknamePage + notifyPage)
        mapOf(
            "controller.onSaved=onSaved" to 1,
            "onValueChange=controller::onInput" to 1,
            "controller.save()" to 1,
            "enabled=check.saveEnabled&&!blocked&&controller.editable" to 1,
            "loading=state.saving" to 1,
            "onChange=controller::toggle" to 1,
            "enabled=!state.switching&&!blocked" to 1,
            "OnResumed(controller){controller.recheck()}" to 1,
            "controller::load" to 1,
            "BackHandler(onBack=onBack)" to 2,
        ).forEach { (code, count) -> assertEquals("SettingsSubPages.kt 页面回调原样：$code", count, occurrences(pages, code)) }
        assertEquals("【去设置】只调 controller.goSettings（只跳系统通知设置）", 1, occurrences(pages, "goSettings"))
        assertFalse("通知页不得申请权限 / 跳应用设置（BR-ID-13）", pages.contains("openAppSettings") || pages.contains("requestPermission"))
        assertFalse("昵称不得截断输入（字素计数由 NicknameInput，组件 maxLength 按 UTF-16 截断）", pages.contains("maxLength"))
        assertTrue("错误仍经 ErrorFocusText（焦点 / assertive 语义不变）", pages.contains("ErrorFocusText(") && pages.contains("MeTags.NICKNAME_ERROR"))
    }

    @Test fun clipboardRowStillTogglesThroughSetAutoSwitchAndStopsOverridingState() {
        val source = File(root, "app/src/main/kotlin/com/couli/app/clipboard/ClipboardScreens.kt").readText()
        val start = source.indexOf("fun ClipboardSettingsRow(")
        val end = source.indexOf("private const val PROMPT_KEY")
        val row = flat(source.substring(start, end))
        assertEquals("开关仍走 clipboard::setAutoSwitch（首次先出用途说明，BR-ID-16）", 1, occurrences(row, "onValueChange=clipboard::setAutoSwitch"))
        assertEquals("G-S12：不再把副标题设成状态描述", 0, occurrences(row, "stateDescription=desc"))
        assertTrue("开关仍是 Role.Switch", row.contains("role=Role.Switch"))
    }

    private fun settingsUi(): String {
        val source = File(root, me + "Settings.kt").readText()
        return source.substring(source.indexOf("fun SettingsScreen("))
    }

    private fun flat(text: String) = text.filterNot(Char::isWhitespace)

    private fun occurrences(
        text: String,
        code: String,
    ): Int = text.windowed(code.length).count { it == code }
}
