package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardDraftTexts
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
 * S26 / brief §4 第 6、7 条：200% × 390 / 320 文字不截断不缩小（裁切判据同时比宽度与位置，滚到可见再量）、字号真的放大、长昵称值不把标题挤成一字一行；
 * 相邻命中区不重叠；≥600 限宽 600 居中。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun font200w390MemberLongNicknameKeepsTitleAndValueReadable() = member()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200w320MemberLongNicknameKeepsTitleAndValueReadable() = member()

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200w320GuestFooterAndRowsAreComplete() {
        host.launch(session = "guest_consented", fontScale = 2f)
        listOf("隐私中心", "用户协议与隐私政策", "关于凑狸", "清除缓存", "帮助中心", "登录后可以管理账号、授权和通知", "去登录").forEach { full(rule.textNode(it)) }
        full(rule.textNode(ClipboardDraftTexts.text("settings.clipboard_auto.desc")))
        val d = host.node(MeTags.SETTINGS_LOGIN).density()
        val link = host.node(MeTags.SETTINGS_LOGIN).touch()
        val help = host.row(SettingsRows.HELP).touch()
        assertTrue("命中 ≥ 48", link.height / d >= 47.5f && link.width / d >= 47.5f)
        assertSeparate("去登录 / 帮助中心", link, help)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200w320BasicNoticeAndButtonAreComplete() {
        host.launch(session = "basic", fontScale = 2f)
        full(rule.textNode("当前为基本模式", substring = true))
        full(rule.textUnder(MeTags.SETTINGS_ENABLE_FULL, "开启完整功能"))
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200w320NicknameLimitErrorHintAndSaveAreComplete() {
        host.nickname.answer = SettingsVisualFixtures.failure(SettingsVisualFixtures.limitCode)
        host.launch(fontScale = 2f)
        host.openNickname()
        host.type(SettingsVisualFixtures.limitInput)
        host.tap(MeTags.NICKNAME_SAVE)
        host.settle("30415") { host.has(MeTags.NICKNAME_ERROR) }
        full(rule.textNode("本月昵称修改次数已用完，下月可再修改"))
        full(rule.textNode("1–16 个字，保存前会做内容检查；每月最多修改 3 次。头像暂不支持修改"))
        full(rule.textUnder(MeTags.NICKNAME_SAVE, "保存"))
        val clear =
            rule
                .onNode(
                    androidx.compose.ui.test
                        .hasContentDescription("清除昵称"),
                    useUnmergedTree = true,
                ).touch()
        assertSeparate("清除 / 保存", clear, host.node(MeTags.NICKNAME_SAVE).touch())
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200w320NotifyStripLinkAndDescriptionAreComplete() {
        host.env.status = SettingsVisualFixtures.notify("denied")
        host.launch(fontScale = 2f)
        host.openNotify()
        full(rule.textNode("通知权限已关闭，可以在系统设置中开启"))
        full(rule.textUnder(MeTags.NOTIFY_GO_SETTINGS, "去设置"))
        full(rule.textNode("跟单成功、返利结算、提现结果等消息。关闭后不再推送，消息中心里照常可以看到"))
        assertSeparate("去设置 / 开关", host.node(MeTags.NOTIFY_GO_SETTINGS).touch(), host.node(MeTags.NOTIFY_SWITCH).touch())
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun w840ContentIsLimitedTo600AndCentered() {
        host.launch()
        val row = host.row(SettingsRows.NICKNAME)
        val d = row.density()
        val px = rule.pixels()
        val box = row.box()
        assertEquals("840：卡片左 = (840 − 600) / 2 + 16", 136f, px.firstFromLeft(box.center.y, host.palette.surface) / d, 1f)
        assertEquals("840：卡片右 = 840 − 136", 704f, px.firstFromRight(box.center.y, host.palette.surface) / d, 1f)
        val logout = host.node(MeTags.SETTINGS_LOGOUT).box()
        assertEquals("【退出登录】同样限宽", 568f, logout.width / d, 1f)
    }

    private fun member() {
        host.launch(fontScale = 2f, profile = "settings_long_nickname")
        val title = rule.textUnder(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.NICKNAME, "昵称")
        val value = rule.textUnder(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.NICKNAME, SettingsVisualFixtures.stress)
        title.scrollIntoView()
        assertEquals("200% 真放大", 2f, title.fontScale(), 0.01f)
        assertEquals("标题不被挤成一字一行", 1, title.lines())
        full(title)
        full(value)
        val t = title.textBox()
        val v = value.textBox()
        assertTrue("值不压住标题（并排时在右、或换到下一行）", v.left >= t.right - 1 || v.top >= t.bottom - 1)
        listOf("账号与安全", "隐私", "通用", "重新显示下单须知", "用户协议与隐私政策", "退出登录").forEach { full(rule.textNode(it)) }
        full(rule.textNode(ClipboardDraftTexts.text("settings.clipboard_auto.desc")))
        val rows = (SettingsRows.account + SettingsRows.privacy + SettingsRows.general).map { host.node(SettingsRows.tag(it)).touch() }
        rows.zipWithNext().forEach { (a, b) -> assertSeparate("相邻行", a, b) }
    }

    private fun full(node: SemanticsNodeInteraction) {
        node.scrollIntoView()
        node.assertTextFully()
    }

    private fun SemanticsNodeInteraction.scrollIntoView() {
        if (insideScroll()) performScrollTo()
        host.idle()
    }
}
