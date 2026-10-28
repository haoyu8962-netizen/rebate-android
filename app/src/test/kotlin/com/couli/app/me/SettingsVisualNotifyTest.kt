package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S20–S23 / G-S08 ③ / G-S13 / G-S14：通知设置子页。系统通知未开启（含 Android not_determined）→ 警告色提示条（status.warning 底 / 字、警告三角 20、14 字）
 * + 条内文字链接「去设置」（600 警告字色，视觉行高 = 文字行高，命中 48 向外扩不撑高条）；只跳系统里本 App 的通知设置、不申请权限（BR-ID-13）。
 * 服务类推送卡：内边距 12/16，标题 + 开关一行，说明句通栏在下；读屏「服务类推送，开关，已打开」+ 说明；Release 只留说明句。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualNotifyTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }
    private val barText = "通知权限已关闭，可以在系统设置中开启"
    private val desc = "跟单成功、返利结算、提现结果等消息。关闭后不再推送，消息中心里照常可以看到"

    @After fun tearDown() = host.close()

    @Test fun s20DeniedShowsWarningStripWithAlertIconAndText() = assertWarningStrip(SettingsVisualFixtures.notify("denied"))

    @Test fun g14NotDeterminedUsesTheSameWarningStrip() = assertWarningStrip(SettingsVisualFixtures.notify("notDetermined"))

    @Test fun s20GrantedHasNoStrip() {
        open(SettingsVisualFixtures.notify("granted"))
        assertFalse("系统通知已开启：无提示条（NotifySettingsOn）", host.has(MeTags.NOTIFY_BAR))
        assertEquals(0, host.count(barText))
    }

    @Test fun s20LinkIsTextHeightWith48TargetAndDoesNotInflateStrip() {
        open(SettingsVisualFixtures.notify("denied"))
        val link = rule.textUnder(MeTags.NOTIFY_GO_SETTINGS, "去设置")
        val d = link.density()
        val style = link.textStyle()
        assertEquals("链接 600", FontWeight.W600, style.fontWeight)
        assertEquals("链接警告字色", host.palette.warningText, style.color)
        val text = rule.textUnder(MeTags.NOTIFY_BAR, barText).textBox()
        val linkBox = link.textBox()
        val strip = host.node(MeTags.NOTIFY_BAR).box()
        assertDp("提示条下内边距 12（链接命中区不撑高条）", 12f, strip.bottom - linkBox.bottom, d, 1.5f)
        assertTrue("链接在提示句之下", linkBox.top >= text.bottom)
        assertTrue("链接与提示句间距不超过 8（不是 48 按钮）", linkBox.top - text.bottom <= 8.5f * d)
        val hit = host.node(MeTags.NOTIFY_GO_SETTINGS).touch()
        assertAtLeastDp("去设置命中高", 48f, hit.height, d)
        assertAtLeastDp("去设置命中宽", 48f, hit.width, d)
        // v2（评审 r1 S2-4 / M07）：读屏焦点框 = 带标记节点的语义盒，也须 ≥48（touchBounds 有系统最小触控扩展，量不出）
        val focus = host.node(MeTags.NOTIFY_GO_SETTINGS).box()
        assertAtLeastDp("去设置读屏边界高", 48f, focus.height, d)
        assertAtLeastDp("去设置读屏边界宽", 48f, focus.width, d)
        assertSeparate("去设置 / 服务类推送开关", hit, host.node(MeTags.NOTIFY_SWITCH).touch())
        val px = rule.pixels()
        assertEquals("不是描边次按钮：链接周围没有控件边", 0, px.countNear(Rect(hit.left, linkBox.top, hit.right, linkBox.bottom), host.palette.control, 4))
    }

    @Test fun s20GoSettingsOnlyOpensSystemNotificationSettingsOncePerEdgeTap() {
        open(SettingsVisualFixtures.notify("denied"))
        val node = host.node(MeTags.NOTIFY_GO_SETTINGS)
        val hit = node.touch()
        val d = node.density()
        val points =
            listOf(
                Offset(hit.center.x, hit.top + d),
                Offset(hit.center.x, hit.bottom - d),
                Offset(hit.left + d, hit.center.y),
                Offset(hit.right - d, hit.center.y),
            )
        points.forEachIndexed { index, point ->
            rule.meTouch(point)
            host.idle()
            assertEquals("边缘 $point 恰一次系统通知设置", index + 1, host.env.notifySettings)
        }
        assertEquals("不跳应用设置、不申请权限（BR-ID-13）", 0, host.env.appSettings)
        assertTrue("不经 Router", host.env.opened.isEmpty())
        assertEquals("不改开关", 0, host.notify.puts.size)
    }

    @Test fun s21ServiceCardHasTitleAndSwitchInOneRowAndDescriptionBelowFullWidth() {
        open(SettingsVisualFixtures.notify("granted"))
        val row = host.node(MeTags.NOTIFY_SWITCH)
        val d = row.density()
        val title = rule.textNode("服务类推送").textBox()
        val note = rule.textNode(desc).textBox()
        val switch =
            row
                .fetchSemanticsNode()
                .layoutInfo
                .subtree()
                .map { it.rootBounds() }
                .filter { it.width / d in 48f..56f && it.height / d in 28f..36f }
                .minBy { it.width * it.height }
        assertTrue("标题与开关同一行", kotlin.math.abs(title.center.y - switch.center.y) <= 2 * d)
        assertTrue("说明在开关行下方（不在标题下与开关并排）", note.top >= switch.bottom - 1)
        assertDp("说明与标题左齐", 0f, note.left - title.left, d)
        assertTrue("说明通栏：右缘越过开关左缘", note.right > switch.left)
        val px = rule.pixels()
        val cardLeft = px.firstFromLeft(title.center.y, host.palette.surface)
        assertDp("卡片左右内边距 16", 16f, title.left - cardLeft, d)
        val cardTop = px.edgeUp(px.width / 2f, title.top - 1, host.palette.surface)
        assertTrue("卡片上内边距 12 + 行内居中（≤ 24）", (title.top - cardTop) / d in 12f..24f)
        val cardBottom = px.edgeDown(px.width / 2f, note.bottom, host.palette.surface)
        assertDp("卡片下内边距 12", 12f, cardBottom - note.bottom, d)
        assertAtLeastDp("开关行命中 48", 48f, row.touch().height, d)
    }

    @Test fun s21ServiceSwitchReadsNameRoleStateAndDescription() {
        open(SettingsVisualFixtures.notify("granted"))
        val node = rule.onNodeWithTag(MeTags.NOTIFY_SWITCH).fetchSemanticsNode()
        assertEquals(Role.Switch, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.On, node.config.getOrNull(SemanticsProperties.ToggleableState))
        val parts = node.spokenParts()
        assertTrue("名称：$parts", parts.any { it == "服务类推送" })
        assertTrue("状态「已打开」：$parts", parts.any { it == "已打开" })
        assertEquals("说明读一次（合并进同一开关节点）：$parts", 1, parts.count { it == desc })
    }

    @Test fun s21ToggleWritesOnceAndFailureRevertsWithIconlessToast() {
        host.notify.putAnswer = SettingsVisualFixtures.failure()
        open(SettingsVisualFixtures.notify("granted"))
        host.tap(MeTags.NOTIFY_SWITCH)
        host.settle("切换失败提示") { host.has(MeTags.TOAST) }
        assertEquals("切换只写一次", listOf(false), host.notify.puts)
        assertEquals(
            "失败回到切换前的值",
            ToggleableState.On,
            rule
                .onNodeWithTag(MeTags.NOTIFY_SWITCH)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ToggleableState),
        )
        assertEquals(1, host.count("设置没有保存，请稍后再试"))
        assertEquals("G-S06：失败提示不带图标", 0, rule.pixels().countNear(host.node(MeTags.TOAST).box(), host.palette.success, 8))
        assertEquals("进入通知子页读开关一次", 1, host.notify.gets.size)
    }

    @Test fun g13ReleaseShowsOnlyDescriptionWithoutSwitch() {
        host.env.status = SettingsVisualFixtures.notify("granted")
        host.launch(debugNotify = false)
        host.openNotify()
        assertFalse("Release 不显示开关行（不画成置灰）", host.has(MeTags.NOTIFY_SWITCH))
        assertEquals("只留说明句", 1, host.count(desc))
        assertEquals(0, host.notify.gets.size)
    }

    @Test fun s22LoadErrorKeepsCurrentBlockAndRetryReadsOnce() {
        host.notify.getAnswer = SettingsVisualFixtures.failure(SettingsVisualFixtures.notify("loadErrorCode").toInt())
        open(SettingsVisualFixtures.notify("granted"))
        host.settle("读取失败块") { host.has(MeTags.NOTIFY_ERROR) }
        assertTrue(
            "统一错误文案带 trace6",
            rule
                .textNode(SettingsVisualFixtures.trace6, substring = true)
                .fetchSemanticsNode()
                .boundsInRoot.height > 0,
        )
        host.notify.getAnswer = null
        host.tap(MeTags.NOTIFY_RETRY)
        host.settle("重试后开关") { host.has(MeTags.NOTIFY_SWITCH) }
        assertEquals("重试恰一次", 2, host.notify.gets.size)
    }

    private fun open(status: String) {
        host.env.status = status
        host.launch()
        host.openNotify()
        if (host.notify.getAnswer != null) host.settle("开关读取结束") { host.notify.gets.isNotEmpty() }
    }

    private fun assertWarningStrip(status: String) {
        open(status)
        assertTrue("非 granted 一律画提示条（G-S14）", host.has(MeTags.NOTIFY_BAR))
        val text = rule.textUnder(MeTags.NOTIFY_BAR, barText)
        val style = text.textStyle()
        assertEquals("提示 14", 14f, style.fontSize.value, 0.01f)
        assertEquals("提示警告字色", host.palette.warningText, style.color)
        val box = text.textBox()
        val d = text.density()
        val strip = host.node(MeTags.NOTIFY_BAR).box()
        val px = rule.pixels()
        assertTrue("警告底 status.warning.background", sameColor(px.at(strip.left + 4 * d, strip.top + 4 * d), host.palette.warningBackground))
        assertEquals("不是 muted 底", false, sameColor(px.at(strip.left + 4 * d, strip.top + 4 * d), host.palette.muted))
        assertDp("圆角 12 的条内边距 12", 12f, box.top - strip.top, d, 2f)
        val icon = Rect(strip.left + 12 * d, strip.top + 12 * d, strip.left + 32 * d, strip.top + 32 * d)
        assertTrue("警告三角 20、警告色（不是铃铛次文字色）", px.countNear(icon, host.palette.warningText, 24) >= 6)
        assertEquals("图标不是次文字色", 0, px.countNear(icon, host.palette.secondary, 2))
        assertTrue("提示条与服务卡分开（服务卡仍在）", host.has(MeTags.NOTIFY_SWITCH))
    }
}
