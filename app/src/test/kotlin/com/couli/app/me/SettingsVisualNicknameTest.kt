package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
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
 * S17–S19 / G-S09 / G-S10：修改昵称子页——卡内 17/600 可见标签 → 组件库外框输入（控件边 1、圆角 12）→ 错误行（14 错误色、带图标、assertive、移焦、
 * 只出现一次）→ 说明句（12 次文字，卡内最后）；【保存】在底部固定白底操作栏（12/16），840 同样限宽。行为原样：字素计数（不截断输入）、保存判定、
 * 30415 后不置灰（G-S10）、保存恰一次。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualNicknameTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }
    private val hint = "1–16 个字，保存前会做内容检查；每月最多修改 3 次。头像暂不支持修改"
    private val limitMessage = "本月昵称修改次数已用完，下月可再修改"
    private val sensitiveMessage = "昵称含不允许使用的内容，请修改"

    @After fun tearDown() = host.close()

    @Test fun s17VisibleLabelAboveComponentFrameAndFieldNamedOnce() {
        open()
        val label = label()
        val style = label.textStyle()
        assertEquals("标签 17", 17f, style.fontSize.value, 0.01f)
        assertEquals("标签 600", FontWeight.W600, style.fontWeight)
        assertEquals("标签主文字色", host.palette.primary, style.color)
        val frame = host.editable().box()
        val d = label.density()
        assertDp("标签 → 输入框 8（卡内纵向 8；浮动标签会压在框线上）", 8f, frame.top - label.textBox().bottom, d, 1.5f)
        assertTrue("外框最小高 44（组件库 52，记组件库差异）", frame.height / d >= 44f - 0.5f)
        assertTrue("外框不被清除命中区撑高（≤ 56）", frame.height / d <= 56f)
        val px = rule.pixels()
        val edge = Rect(frame.left, frame.center.y - 4 * d, frame.left + 2 * d, frame.center.y + 4 * d)
        assertTrue("未聚焦外框 border.control 1（不是 M3 outline）", px.countNear(edge, host.palette.control, 8) > 0)
        val field = host.editable().fetchSemanticsNode()
        assertTrue("输入框读屏名称为「昵称」：${field.spokenParts()}", "昵称" in field.spokenParts())
        val named = mergedNodes().count { node -> node.spokenParts().any { it == "昵称" } }
        assertEquals("「昵称」只作输入框名称读一次（可见标签不重复读）", 1, named)
    }

    @Test fun s17CounterCountsGraphemesAndInputIsNeverTruncated() {
        open()
        host.type(SettingsVisualFixtures.tooLong)
        assertEquals("超长 17 字不得被截断（不得给组件 maxLength）", SettingsVisualFixtures.tooLong, fieldText())
        assertEquals("计数 17/16", "17/16", counter())
        host.node(MeTags.NICKNAME_SAVE).assertIsNotEnabled()
        assertEquals("超长提示在错误行位置出现一次", 1, host.count("昵称最多 16 个字"))
        val tooLong = rule.textNode("昵称最多 16 个字")
        assertTrue("超长提示在输入框下", tooLong.textBox().top >= host.editable().box().bottom - 1)
        host.type(SettingsVisualFixtures.grapheme)
        assertEquals("字素计数：ZWJ 一家人算 1（不是 UTF-16 长度）", "16/16", counter())
        assertEquals(SettingsVisualFixtures.grapheme, fieldText())
        host.node(MeTags.NICKNAME_SAVE).assertIsEnabled()
        host.type(SettingsVisualFixtures.original)
        host.node(MeTags.NICKNAME_SAVE).assertIsNotEnabled()
    }

    @Test fun s17ClearHas48TargetInsideFrameAndClearsOnce() {
        open()
        val clear = clear()
        val d = clear.density()
        val hit = clear.touch()
        assertAtLeastDp("清除命中宽", 48f, hit.width, d)
        assertAtLeastDp("清除命中高", 48f, hit.height, d)
        val frame = host.editable().box()
        assertTrue("清除在框内右侧", hit.center.x > frame.center.x && hit.center.y in frame.top..frame.bottom)
        assertSeparate("清除 / 保存", hit, host.node(MeTags.NICKNAME_SAVE).touch())
        host.tap(clear)
        assertEquals("", fieldText())
        host.node(MeTags.NICKNAME_SAVE).assertIsNotEnabled()
        assertEquals("清除不发请求", 0, host.nickname.saves.size)
    }

    /** v2（评审 r1 S2-2，brief-android S17）：NICKNAME_CLEAR 挂在清除按钮的等价节点上，点它清空输入、不发请求。 */
    @Test fun s17ClearCarriesNicknameClearTagAndClearsInput() {
        open()
        assertTrue("NICKNAME_CLEAR 存在", host.has(MeTags.NICKNAME_CLEAR))
        assertTrue("清除前输入非空", fieldText().isNotEmpty())
        host.tap(MeTags.NICKNAME_CLEAR)
        assertEquals("点 NICKNAME_CLEAR 清空输入", "", fieldText())
        assertEquals("清除不发请求", 0, host.nickname.saves.size)
    }

    @Test fun s18LimitErrorHasIconFocusAssertiveOnceAndSaveStaysEnabledGS10() {
        host.nickname.answer = SettingsVisualFixtures.failure(SettingsVisualFixtures.limitCode)
        open()
        host.type(SettingsVisualFixtures.limitInput)
        host.tap(MeTags.NICKNAME_SAVE)
        host.settle("30415 错误行") { host.has(MeTags.NICKNAME_ERROR) }
        assertErrorLine(limitMessage)
        assertEquals("保留输入", SettingsVisualFixtures.limitInput, fieldText())
        host.node(MeTags.NICKNAME_SAVE).assertIsEnabled()
        host.tap(MeTags.NICKNAME_SAVE)
        host.idle()
        assertEquals(
            "G-S10：30415 后【保存】不置灰，再点照常再发（不自动重试）",
            List(2) { SettingsVisualEnv.ACCOUNT to SettingsVisualFixtures.limitInput },
            host.nickname.saves,
        )
    }

    @Test fun s18SensitiveErrorMarksFieldRedAndKeepsInput() {
        host.nickname.answer = SettingsVisualFixtures.sensitive()
        open()
        host.type(SettingsVisualFixtures.sensitiveInput)
        host.tap(MeTags.NICKNAME_SAVE)
        host.settle("20001 错误行") { host.has(MeTags.NICKNAME_ERROR) }
        assertErrorLine(sensitiveMessage)
        val field = host.editable().fetchSemanticsNode()
        assertTrue("字段错误关联到输入框（现状语义）", field.config.contains(SemanticsProperties.Error))
        // v2（评审 r1 S2-1）：输入框的错误语义须是完整错误句（不是空串），回到输入框能听到原因；画出来的错误句仍只一份（assertErrorLine 已量）
        assertEquals("输入框错误语义 = 错误句", sensitiveMessage, field.config[SemanticsProperties.Error])
        val frame = host.editable().box()
        val d = host.editable().density()
        val px = rule.pixels()
        val edge = Rect(frame.left, frame.center.y - 4 * d, frame.left + 3 * d, frame.center.y + 4 * d)
        assertTrue("错误红框", px.countNear(edge, host.palette.error, 8) > 0)
        assertEquals(SettingsVisualFixtures.sensitiveInput, fieldText())
        host.node(MeTags.NICKNAME_SAVE).assertIsEnabled()
        assertEquals("保存恰一次", 1, host.nickname.saves.size)
    }

    @Test fun s18HintIsInsideCardAsLastItemWith16Padding() {
        open()
        val label = label().textBox()
        val note = rule.textNode(hint)
        val box = note.textBox()
        val d = note.density()
        val px = rule.pixels()
        val cx = px.width / 2f
        val cardTop = px.edgeUp(cx, label.top - 1, host.palette.surface)
        val cardBottom = px.edgeDown(cx, box.bottom, host.palette.surface)
        assertDp("卡内上 16", 16f, label.top - cardTop, d)
        assertDp("卡内下 16（说明句移进卡内、在最后）", 16f, cardBottom - box.bottom, d)
        assertTrue("说明在输入框下", box.top > host.editable().box().bottom)
        assertDp("说明与标签左齐", 0f, box.left - label.left, d)
        assertEquals("说明 12", 12f, note.textStyle().fontSize.value, 0.01f)
        assertEquals("说明次文字色", host.palette.secondary, note.textStyle().color)
    }

    @Test fun s19SaveLivesInFixedWhiteBottomBar() {
        open()
        val save = host.node(MeTags.NICKNAME_SAVE)
        val d = save.density()
        val box = save.box()
        val scroll = rule.scrollUnder(MeTags.NICKNAME_PAGE).box()
        assertTrue("【保存】不在滚动内容里：$box / $scroll", box.top >= scroll.bottom - 1)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertDp("操作栏下内边距 12（无系统导航条）", 12f, root.bottom - box.bottom, d)
        assertDp("左 16", 16f, box.left, d)
        assertDp("右 374", 374f, box.right, d)
        val px = rule.pixels()
        assertTrue("操作栏白底（background.surface）", sameColor(px.at(8 * d, box.top - 6 * d), host.palette.surface))
        assertTrue("操作栏上沿 = 按钮上 12（再往上是页面底）", !sameColor(px.at(8 * d, box.top - 14 * d), host.palette.surface))
        save.assertIsNotEnabled()
        host.type(SettingsVisualFixtures.savedName)
        save.assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun s19s26BottomBarIsWidthLimitedAt840() {
        open()
        val save = host.node(MeTags.NICKNAME_SAVE)
        val d = save.density()
        val box = save.box()
        assertDp("840：按钮左 136（限宽 600 居中 + 16）", 136f, box.left, d)
        assertDp("840：按钮宽 568", 568f, box.width, d)
        val label = label().textBox()
        assertDp("840：卡片内容同样限宽（标签左 = 120 + 16 + 16）", 152f, label.left, d)
    }

    private fun open() {
        host.launch()
        host.openNickname()
    }

    private fun label(): SemanticsNodeInteraction = rule.onNode(textIs("昵称") and hasSetTextAction().not(), useUnmergedTree = true)

    private fun clear(): SemanticsNodeInteraction = rule.onNode(hasContentDescription("清除昵称") and hasClickAction(), useUnmergedTree = true)

    private fun fieldText(): String =
        host
            .editable()
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun counter(): String =
        host
            .node(MeTags.NICKNAME_COUNTER)
            .fetchSemanticsNode()
            .spokenParts()
            .joinToString("")

    private fun mergedNodes(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()

        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return out
    }

    private fun assertErrorLine(message: String) {
        assertEquals("错误句只出现一次（不得 ErrorFocusText 与组件 error 双份）", 1, host.count(message))
        val error = host.node(MeTags.NICKNAME_ERROR)
        error.assertIsFocused()
        val node = error.fetchSemanticsNode()
        val live =
            generateSequence(node) { it.parent }
                .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.LiveRegion) }
        assertEquals("错误 assertive（共性第 25、37 条）", LiveRegionMode.Assertive, live)
        val text = rule.textNode(message)
        val style = text.textStyle()
        assertEquals("错误 14", 14f, style.fontSize.value, 0.01f)
        assertEquals("错误色", host.palette.error, style.color)
        val glyph = text.glyphBox()
        val d = text.density()
        assertTrue("错误行在输入框下", glyph.top >= host.editable().box().bottom - 1)
        val px = rule.pixels()
        val icon = Rect(glyph.left - 24 * d, glyph.top, glyph.left - 2 * d, glyph.top + 21 * d)
        assertTrue("错误行带图标（错误色墨迹在文字左侧）", px.countNear(icon, host.palette.error, 24) >= 6)
        assertEquals("错误播报一次：${host.spoken}", 1, host.spoken.count { it.contains(message) })
    }
}
