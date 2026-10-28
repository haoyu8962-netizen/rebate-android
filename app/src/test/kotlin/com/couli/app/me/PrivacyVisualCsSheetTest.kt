package com.couli.app.me

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
 * 联系客服半屏（brief-b-android P20–P26，G-P05 / G-P06 / G-P07，画板 CsOpenFailed-v2 / CsWechatMissing-v2）：图标块 56 在上、标题居中、关闭右上
 * （读屏仍 标题 → 关闭）、说明 14 次文字居中、盾牌安全提示新句、打不开时【去帮助中心】为文字链接；动作映射、一次性编号、复制结果留在半屏原样。
 * 图标块无新标记可依，按令牌色像素外接框在标题之上量；半屏是独立窗口，像素取该窗口的 Compose 根。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class PrivacyVisualCsSheetTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun title(key: String) = host.sheetText(AppMeTexts.text(key))

    // ---------------------------------------------------------------- P20 / P21 头部与图标块

    @Test fun p20OpenFailedHeaderHasWarningBadge56AboveACenteredTitle() =
        assertHeader(
            "cs_open_failed",
            "cs.open_failed.title",
            warning = true,
        )

    @Test fun p20WechatMissingHeaderHasMutedBadge56AboveACenteredTitle() = assertHeader("cs_wechat_missing", "cs.title", warning = false)

    @Test fun p26HelpOnlyUsesTheWarningBadge() = assertHeader("cs_help_only", "cs.open_failed.title", warning = true)

    @Test fun p26HandoffUsesTheMutedBadge() = assertHeader("cs_handoff", "cs.title", warning = false)

    @Test fun p20CloseStaysTopRightAfterTheTitleAndFocusEntersTheTitle() {
        host.launchCs("cs_open_failed")
        val title = title("cs.open_failed.title")
        val close = host.closeButton()
        val d = title.density()
        val width = host.pixels(title).width
        assertTrue("关闭在右上：${close.touch()}", close.touch().right >= width - 20 * d && close.touch().top <= title.textBox().top)
        assertTrue("关闭命中 ≥ 48", close.touch().width / d >= 47.5f && close.touch().height / d >= 47.5f)
        val order = host.rootOf(title).pvFlatten().map { it.spokenParts() }
        val t = order.indexOfFirst { AppMeTexts.text("cs.open_failed.title") in it }
        val c = order.indexOfFirst { AppMeTexts.text("me.close") in it }
        assertTrue("读屏 标题 → 关闭（GUIDE）：$order", t in 0 until c)
        // v2（同 T5 同类）：半屏同样经 FocusOnAppear 延时请求焦点；v1 实跑已绿，这里先推进时钟，免得依赖前面量具耗掉的时间。
        host.settleFocus()
        title.assertIsFocused()
    }

    // ---------------------------------------------------------------- P22 / P23 说明与安全提示

    @Test fun p22DescriptionIs14SecondaryCentered() {
        host.launchCs("cs_open_failed")
        val desc = host.sheetText(AppMeTexts.text("cs.open_failed.desc"))
        assertEquals(14f, desc.textStyle().fontSize.value, 0.01f)
        assertEquals(host.palette.secondary, desc.textStyle().color)
        assertEquals(TextAlign.Center, desc.textStyle().textAlign)
        val title = title("cs.open_failed.title")
        assertTrue("说明在标题之下", desc.textBox().top >= title.textBox().bottom)
    }

    @Test fun p23SafetyStripUsesTheNewDraftSentenceWithAn20Icon() {
        host.launchCs("cs_wechat_missing")
        assertEquals("G-P07 安全提示句改为画板措辞", 1, host.count(PrivacyVisualFixtures.safetyDraft))
        assertEquals("旧句不再出现", 0, host.count(PrivacyVisualFixtures.safetyOld))
        val text = host.sheetText(PrivacyVisualFixtures.safetyDraft)
        assertEquals(14f, text.textStyle().fontSize.value, 0.01f)
        assertEquals(host.palette.primary, text.textStyle().color)
        val d = text.density()
        val px = host.pixels(text)
        val strip = Rect(0f, text.textBox().top - 16 * d, px.width.toFloat(), text.textBox().bottom + 16 * d)
        val icon =
            requireNotNull(
                px.pvColorBox(Rect(0f, strip.top, text.textBox().left, strip.bottom), host.palette.secondary, 40),
            ) { "提示条左侧没有图标" }
        assertTrue("图标 ≤ 20：$icon", icon.width / d <= 20.5f && icon.height / d <= 20.5f)
        assertTrue("muted 底", sameColor(px.at(text.textBox().left - 2f, text.textBox().center.y), host.palette.muted, 4))
    }

    // ---------------------------------------------------------------- P24 按钮写法（G-P06）

    @Test fun p24OpenFailedRetryPrimaryCopySecondaryHelpIsATextLink() {
        host.launchCs("cs_open_failed")
        val retry = host.node(MeTags.CS_RETRY)
        val copy = host.node(MeTags.CS_COPY)
        val help = host.node(MeTags.CS_HELP)
        val d = retry.density()
        val px = host.pixels(retry)
        assertTrue("【重试】主", px.countNear(retry.box(), host.palette.buttonPrimary, 2) > 200)
        assertEquals("【复制客服链接】次", 0, px.countNear(copy.box(), host.palette.buttonPrimary, 2))
        assertTrue("【复制客服链接】有控件边", px.countNear(copy.box(), host.palette.control, 8) >= 20)
        assertDp("按钮纵向 12", 12f, copy.box().top - retry.box().bottom, d, 1f)
        val link = rule.textUnder(MeTags.CS_HELP, AppMeTexts.text("cs.go_help"))
        assertEquals("链接 14", 14f, link.textStyle().fontSize.value, 0.01f)
        assertEquals("链接色 text.link", host.palette.link, link.textStyle().color)
        assertTrue("链接带下划线（G-P06）", link.textStyle().textDecoration?.contains(TextDecoration.Underline) == true)
        assertEquals("链接居中", px.width / 2f / d, link.glyphBox().center.x / d, 2f)
        val box = help.box()
        assertEquals(
            "链接无控件边（不是描边按钮）",
            0,
            px.countNear(Rect(box.left, link.textBox().top - 6 * d, box.right, link.textBox().bottom + 6 * d), host.palette.control, 2),
        )
        assertEquals("链接无品牌填充", 0, px.countNear(box, host.palette.buttonPrimary, 2))
        assertTrue("视觉不撑高：复制按钮下沿到链接文字 ≤ 14（纵向 12 + 行内留白）", link.textBox().top - copy.box().bottom <= 14.5f * d)
        assertTrue("触摸盒 ≥ 48：${help.touch()}", help.touch().height / d >= 47.5f && help.touch().width / d >= 47.5f)
        assertTrue("语义盒也 ≥ 48（APP-12w-a r1 S2-4）：$box", box.height / d >= 47.5f && box.width / d >= 47.5f)
        assertSeparate("链接与复制按钮命中不重叠", help.touch(), copy.touch())
        val merged =
            rule
                .onNode(
                    androidx.compose.ui.test
                        .hasTestTag(MeTags.CS_HELP),
                ).fetchSemanticsNode()
        assertEquals("链接读作按钮", Role.Button, merged.config.getOrNull(SemanticsProperties.Role))
    }

    @Test fun p24WechatMissingCopyPrimaryHelpSecondaryButton() {
        host.launchCs("cs_wechat_missing")
        val copy = host.node(MeTags.CS_COPY)
        val help = host.node(MeTags.CS_HELP)
        val d = copy.density()
        val px = host.pixels(copy)
        assertTrue("【复制客服链接】主", px.countNear(copy.box(), host.palette.buttonPrimary, 2) > 200)
        assertEquals("【去帮助中心】次（不是链接）", 0, px.countNear(help.box(), host.palette.buttonPrimary, 2))
        assertTrue("【去帮助中心】是描边按钮", px.countNear(help.box(), host.palette.control, 8) >= 20)
        assertTrue("通栏 ≥ 48", help.box().height / d >= 47.5f && help.box().width >= copy.box().width - d)
        assertEquals(host.palette.primary, rule.textUnder(MeTags.CS_HELP, AppMeTexts.text("cs.go_help")).textStyle().color)
    }

    @Test fun p26HelpOnlyKeepsASinglePrimaryButtonAndNoCopy() {
        host.launchCs("cs_help_only")
        assertFalse("kf 缺失不显示【复制客服链接】", host.has(MeTags.CS_COPY))
        assertFalse(host.has(MeTags.CS_RETRY))
        val help = host.node(MeTags.CS_HELP)
        assertTrue("只有帮助：首个主", host.pixels(help).countNear(help.box(), host.palette.buttonPrimary, 2) > 200)
    }

    @Test fun p26HandoffKeepsFirstPrimaryOthersSecondary() {
        host.launchCs("cs_handoff")
        val open = host.node(MeTags.CS_OPEN)
        val help = host.node(MeTags.CS_HELP)
        val px = host.pixels(open)
        assertTrue(px.countNear(open.box(), host.palette.buttonPrimary, 2) > 200)
        assertEquals(0, px.countNear(help.box(), host.palette.buttonPrimary, 2))
        assertEquals("HANDOFF 帮助按钮文案不变", 1, host.count(AppMeTexts.text("cs.handoff.go_help")))
    }

    // ---------------------------------------------------------------- 动作计数（真实触摸）

    @Test fun retryOpensOnceAndAFailedRetryStaysOnOpenFailed() {
        host.launchCs("cs_open_failed")
        host.tap(MeTags.CS_RETRY)
        assertEquals(listOf(PrivacyVisualFixtures.kf), host.ports.opened)
        assertTrue("拉不起仍是打不开半屏", host.has(MeTags.CS_RETRY))
        assertEquals(0, host.ports.help)
    }

    @Test fun helpLinkClosesTheSheetThenOpensHelpOnce() {
        host.launchCs("cs_open_failed")
        host.tap(MeTags.CS_HELP)
        assertFalse("先关半屏", host.has(MeTags.CS_SHEET))
        assertEquals("再经 Router 开 Help 一次", 1, host.ports.help)
        assertTrue("不打开、不复制", host.ports.opened.isEmpty() && host.ports.copies.isEmpty())
    }

    @Test fun closeDoesNothingAndStaleCallbacksAreVoid() {
        host.launchCs("cs_open_failed")
        val help = host.clickAction(host.node(MeTags.CS_HELP))
        val retry = host.clickAction(host.node(MeTags.CS_RETRY))
        val copy = host.clickAction(host.node(MeTags.CS_COPY))
        host.tap(host.closeButton())
        assertFalse(host.has(MeTags.CS_SHEET))
        rule.runOnIdle {
            help()
            retry()
            copy()
        }
        host.idle()
        assertEquals("关闭零动作、过期编号的旧回调零动作", Triple(0, 0, 0), Triple(host.ports.help, host.ports.opened.size, host.ports.copies.size))
    }

    @Test fun p25CopyResultStaysInTheSheetPoliteOnceWithoutAPageToast() {
        host.launchCs("cs_wechat_missing")
        host.tap(MeTags.CS_COPY)
        assertEquals("统一写入口一次、原串", listOf(PrivacyVisualFixtures.kf), host.ports.copies)
        val message = AppMeTexts.text("cs.copy_link.done")
        assertEquals(1, host.count(message))
        assertTrue("提示后留在半屏", host.has(MeTags.CS_SHEET))
        assertEquals("只有一路提示（半屏内，不同时出页面轻提示）", 1, host.countTag(MeTags.TOAST))
        val node = host.node(MeTags.TOAST).fetchSemanticsNode()
        assertEquals("polite", LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("不叠加主动播报（不得双播）", 0, host.announcements.sample().count { it.channel == "active" && it.text.contains(message) })
        val text = rule.textUnder(MeTags.TOAST, message)
        assertEquals("成功色", host.palette.success, text.textStyle().color)
    }

    @Test fun copyFailureInTheSheetShowsTheFailureKey() {
        host.launchCs("cs_wechat_missing_copy_failed")
        host.tap(MeTags.CS_COPY)
        assertEquals(1, host.ports.copies.size)
        assertEquals(1, host.count(AppMeTexts.text("me.copy_failed")))
    }

    // ---------------------------------------------------------------- 工具

    private fun assertHeader(
        case: String,
        titleKey: String,
        warning: Boolean,
    ) {
        host.launchCs(case)
        val title = title(titleKey)
        val d = title.density()
        val px = host.pixels(title)
        val glyph = title.glyphBox()
        val center = px.width / 2f
        assertEquals("G-P05 标题居中", center / d, glyph.center.x / d, 2f)
        assertEquals("标题 17", 17f, title.textStyle().fontSize.value, 0.01f)
        assertEquals("标题 600", FontWeight.SemiBold, title.textStyle().fontWeight)
        val badge = badge(px, title, if (warning) host.palette.warningBackground else host.palette.muted)
        assertDp("P21 图标块 56 宽", 56f, badge.width, d, 1f)
        assertDp("P21 图标块 56 高", 56f, badge.height, d, 1f)
        assertEquals("图标块水平居中", center / d, badge.center.x / d, 1.5f)
        assertDp("图标块到标题 12", 12f, title.textBox().top - badge.bottom, d, 1.5f)
        val glyphColor = if (warning) host.palette.warningText else host.palette.secondary
        val icon = requireNotNull(px.pvColorBox(badge, glyphColor, 40)) { "图标块里没有图标" }
        assertTrue("图标 32（字形 ≤ 32、≥ 20）：$icon", icon.width / d in 20f..32.5f && icon.height / d in 18f..32.5f)
        assertEquals("图标在块内居中", badge.center.x / d, icon.center.x / d, 2f)
        val other = if (warning) host.palette.muted else host.palette.warningBackground
        assertEquals("先正后反：另一种底色不出现", null, px.pvColorBox(badge, other, 2))
        val above =
            host
                .rootOf(title)
                .pvFlatten()
                .filter { it.spokenParts().isNotEmpty() && it.boundsInRoot.bottom <= title.textBox().top }
        assertTrue("图标块装饰、读屏不读：${above.map { it.spokenParts() }}", above.isEmpty())
    }

    /** 标题之上、半屏把手之下的 [color] 外接框。 */
    private fun badge(
        px: Bitmap,
        title: SemanticsNodeInteraction,
        color: Color,
    ): Rect {
        val area = Rect(0f, title.textBox().top - 120 * title.density(), px.width.toFloat(), title.textBox().top)
        return requireNotNull(px.pvColorBox(area, color, 3)) { "标题上方没有图标块" }
    }
}
