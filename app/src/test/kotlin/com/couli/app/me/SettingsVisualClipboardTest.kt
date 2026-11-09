package com.couli.app.me

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardDraftTexts
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.clipboard.ClipboardScreens
import com.couli.app.clipboard.SampleCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S8 / G-S11 / G-S12 / S24：剪贴板开关行只改间距与读屏；开关外观不改；默认开启、打开关闭都不出弹层、不发请求（BR-ID-16 2026-10-30，C8）。
 * （BR-ID-16）。全部经真实 ClipboardSettingsRow + 真实 AppClipboard 的真实触摸。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualClipboardTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }
    private val title = ClipboardDraftTexts.text("settings.clipboard_auto")
    private val desc = ClipboardDraftTexts.text("settings.clipboard_auto.desc")

    @After fun tearDown() = host.close()

    @Test fun s8RowPaddingTitleGapAndSwitchDoNotSqueezeText() {
        host.launch()
        turnOff()
        val row = host.node(ClipboardScreens.SETTINGS_ROW)
        val d = row.density()
        val box = row.box()
        val name = rule.textUnder(ClipboardScreens.SETTINGS_ROW, title)
        val sub = rule.textUnder(ClipboardScreens.SETTINGS_ROW, desc)
        assertDp("S8 上内边距 8", 8f, name.textBox().top - box.top, d)
        assertDp("S8 下内边距 8", 8f, box.bottom - sub.textBox().bottom, d)
        assertDp("S8 标题与副标题间 2（现状 0）", 2f, sub.textBox().top - name.textBox().bottom, d, 0.5f)
        assertEquals("标题 17", 17f, name.textStyle().fontSize.value, 0.01f)
        assertEquals("副标题 12", 12f, sub.textStyle().fontSize.value, 0.01f)
        assertEquals("副标题次文字色", host.palette.secondary, sub.textStyle().color)
        val switch = switchBox()
        assertTrue("开关垂直居中", kotlin.math.abs(switch.center.y - box.center.y) <= d)
        assertTrue("开关不挤压文字：副标题右缘在开关左侧 12 以外", sub.textBox().right <= switch.left - 12 * d + 1)
        assertTrue("副标题多行完整（不截断）", sub.lines() >= 2)
        sub.assertTextFully()
    }

    @Test fun g11SwitchKeepsMaterialAppearanceAndUncheckedTrackToken() {
        host.launch()
        turnOff()
        val switch = switchBox()
        val d = host.node(ClipboardScreens.SETTINGS_ROW).density()
        assertEquals("M3 Switch 外形 52×32（不改成画板 51×31）", 52f, switch.width / d, 1f)
        assertEquals(32f, switch.height / d, 1f)
        val px = rule.pixels()
        val muted = px.countNear(switch, host.palette.muted, 2)
        val placeholder = px.countNear(switch, host.palette.placeholder, 2)
        assertTrue("未选轨道仍是 background.muted（G-S11 推荐不改）：muted=$muted placeholder=$placeholder", muted > 40 && muted > placeholder)
        assertTrue("未选轨道描边 border.control", px.countNear(switch, host.palette.control, 24) > 10)
    }

    @Test fun g12ReadsNameStateAndDescriptionWithoutDescriptionOverridingState() {
        host.launch()
        turnOff()
        assertReadout(ToggleableState.Off, "已关闭")
    }

    /** C8 / C1：默认开启（品牌色轨道），打开关闭都不出弹层、不发请求；游客与登录用户一致。 */
    @Test fun c8DefaultOnBrandTrackTogglesDirectlyWithoutNoticeMember() = defaultOnRow("settings_member")

    @Test fun c8DefaultOnBrandTrackTogglesDirectlyWithoutNoticeGuest() = defaultOnRow("guest_consented")

    private fun defaultOnRow(session: String) {
        host.launch(session = session)
        assertEquals("默认开启", true, host.clipboard.autoSwitch.value)
        assertReadout(ToggleableState.On, "已打开")
        assertTrue("开启轨道用品牌色", rule.pixels().countNear(switchBox(), host.palette.brand, 24) > 40)
        val requestsBefore = host.api.calls.map { it.operationId }
        host.tap(ClipboardScreens.SETTINGS_ROW)
        assertFalse("关闭不出弹层", host.has(NOTICE))
        assertEquals("关闭立即生效", false, host.clipboard.autoSwitch.value)
        assertReadout(ToggleableState.Off, "已关闭")
        assertEquals("关闭写本机", false, store().snapshot().enabled)
        host.tap(ClipboardScreens.SETTINGS_ROW)
        assertFalse("打开不出弹层", host.has(NOTICE))
        assertEquals("打开立即生效", true, host.clipboard.autoSwitch.value)
        assertReadout(ToggleableState.On, "已打开")
        assertEquals("打开写本机", true, store().snapshot().enabled)
        assertEquals("打开关闭都不发任何请求（含 clipboard_auto_import）", requestsBefore, host.api.calls.map { it.operationId })
        assertTrue("设置页零导航", host.env.opened.isEmpty())
    }

    /** C8：基本模式不显示这一行。 */
    @Test fun c8BasicModeHasNoRow() {
        host.launch(session = "basic")
        assertFalse("基本模式无剪贴板行", host.has(ClipboardScreens.SETTINGS_ROW))
    }

    private fun assertReadout(
        expected: ToggleableState,
        stateWord: String,
    ) {
        val node = rule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).fetchSemanticsNode()
        val config = node.config
        assertEquals("角色是开关", Role.Switch, config.getOrNull(SemanticsProperties.Role))
        assertEquals(expected, config.getOrNull(SemanticsProperties.ToggleableState))
        val state = config.getOrNull(SemanticsProperties.StateDescription)
        assertNotEquals("G-S12：状态描述不得被副标题覆盖", desc, state)
        assertTrue("G-S12：状态描述缺省（系统按开关读状态）或恰为「$stateWord」：$state", state == null || state == stateWord)
        val parts = node.spokenParts()
        assertEquals("名称读一次：$parts", 1, parts.count { it == title })
        assertEquals("副标题作说明读一次：$parts", 1, parts.count { it == desc })
        assertTrue("状态词最多一次（不双读）：$parts", parts.count { it.contains("已打开") || it.contains("已关闭") } <= 1)
    }

    /** M3 Switch 的布局盒（行子树里约 52×32 的那一个）。 */
    private fun switchBox(): Rect {
        val row = host.node(ClipboardScreens.SETTINGS_ROW)
        val d = row.density()
        val layout = row.fetchSemanticsNode().layoutInfo
        return layout
            .subtree()
            .map { it.rootBounds() }
            .filter { it.width / d in 48f..56f && it.height / d in 28f..36f }
            .minBy { it.width * it.height }
    }

    /** 视觉用例需要未选轨道时先经真实入口关闭（默认开启）。 */
    private fun turnOff() {
        rule.runOnUiThread { host.clipboard.setAutoSwitch(false) }
        host.idle()
    }

    private fun store() =
        ClipboardPreferences(
            ApplicationProvider.getApplicationContext<Context>(),
            SampleCipher,
        ).importPreferences { SettingsVisualFixtures.now }

    private companion object {
        // 已移除的用途说明弹层标记（C2），只用于断言不存在
        const val NOTICE = "clipboard_consent_notice"
    }
}
