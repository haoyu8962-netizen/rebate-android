@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.at
import com.couli.app.me.box
import com.couli.app.me.firstFromLeft
import com.couli.app.me.firstFromRight
import com.couli.app.me.glyphBox
import com.couli.app.me.sameColor
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.app.me.touch
import com.couli.app.trade.AuthManageVisualHost.Companion.AUTHORIZED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.BLOCKED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.CONTACT_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.LOADING_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.REBIND_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.UNAUTHORIZED_KEY
import com.couli.feature.trade.AuthManageTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-06w 结构 / 视觉单测（brief-android §2 差距 A1–A18、gaps G-A04 / 05 / 06 / 14；裁定 D10-414 ③）：宽 390 / xhdpi、真实页面（宿主见
 * [AuthManageVisualHost]）。每条视觉断言先正后反；量外框不量内容盒；平台图标只量外框不比像素。底版（main 1dbb163）上 A1 / A3 / A6 / A7 /
 * A9 / A10 / A11 应红，A2 / A4 / A13 / A14 / A18 与回调类断言应绿（回归）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AuthManageVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { AuthManageVisualHost(rule) }

    @After fun tearDown() = host.close()

    private val d get() = host.density()

    // ---------------------------------------------------------------- A1 内容边距 / A2 顶栏

    @Test fun a01ContentStartsEightBelowTheBarAndSixteenFromTheEdges() {
        host.launch("normal")
        val card = host.cardOf(host.inRow("taobao", host.name("taobao")))
        val bar = host.back().box()
        assertNear("先正：卡左缘距页左 16", 16f, card.left, d)
        assertNear("卡右缘距页右 16", 16f, host.rootWidth() - card.right, d)
        assertNear("A1：卡上沿距顶栏下沿 8（底版四周 16 → 16，红）", 8f, card.top - bar.bottom, d, 1f)
    }

    @Test fun a01LoadingFollowsTheSamePadding() {
        host.launch(bindings = null, wait = false)
        val loading = host.textNode(host.text(LOADING_KEY))
        assertNear("加载中仍贴左 16（G-A10 不改，只随边距）", 16f, loading.textBox().left, d)
        assertNear("A1：加载中上沿距顶栏 8", 8f, loading.textBox().top - host.back().box().bottom, d, 1.5f)
        assertEquals("加载中 14 次文字（不改）", 14f, loading.textStyle().fontSize.value, 0.01f)
        assertTrue(sameColor(loading.textStyle().color.toArgb(), host.palette.secondary))
    }

    @Test fun a02BarKeepsBackAndCentredHeading() {
        host.launch("normal")
        val back = host.back()
        assertTrue("返回命中 ≥48", back.touch().width / d >= 47.5f && back.touch().height / d >= 47.5f)
        val title = host.textNode(host.text(AuthManageVisualHost.TITLE_KEY))
        assertNotNull("标题仍是 heading（APP-13v-b R1-05）", title.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Heading))
        assertEquals("标题 17", 17f, title.textStyle().fontSize.value, 0.01f)
        assertEquals("标题 600", FontWeight.SemiBold, title.textStyle().fontWeight)
        val glyph = title.glyphBox()
        assertNear("标题字形居中（量字形）", host.rootWidth() / 2 / d, glyph.center.x, d, 2f)
    }

    // ---------------------------------------------------------------- A3 / A4 / A5 平台卡、分隔、行

    @Test fun a03CardIsCouliCardWithRadiusSixteen() {
        host.launch("normal")
        val px = host.pixels()
        val card = host.cardOf(host.inRow("taobao", host.name("taobao")), px)
        // 先正：同一量具在卡片中段（离上沿 30dp）量到直边，内缩为 0
        val straight = px.cornerSample(card, host.palette.surface, 30f, d)
        assertEquals("先正：卡片中段左缘是直边：${straight.upperLeft}", 0f, straight.upperLeft, 0.75f)
        // v2 T5：取 4dp / 8dp 深处（16 圆角约 5.4 / 2.1；24 圆角约 10.7 / 6.1，差 ≥4dp，远大于 1 像素抗锯齿）
        listOf(4f, 8f).forEach { depth ->
            val sample = px.cornerSample(card, host.palette.surface, depth, d)
            val expected = cornerInsetFor(16f, depth)
            val old = cornerInsetFor(24f, depth)
            assertTrue("先正：$depth dp 深处有圆角内缩：${sample.upperLeft}", sample.upperLeft > 1f)
            assertEquals("A3：左上圆角 16（$depth dp 深处内缩约 $expected；底版 panel 24 约 $old，红）", expected, sample.upperLeft, 1.5f)
            assertEquals("A3：左下圆角 16（$depth dp 深处）", expected, sample.lowerLeft, 1.5f)
            assertEquals("A3：左上圆角竖向对称（左缘内 $depth dp 那一列）", expected, sample.upperColumn, 1.5f)
        }
    }

    @Test fun a03CardHasFourVerticalPaddingAndRowsTwelve() {
        host.launch("normal")
        val tb = host.inRow("taobao", host.name("taobao"))
        val pdd = host.inRow("pdd", host.name("pdd"))
        val card = host.cardOf(tb)
        assertNear("A3 + A5：首行文字中线距卡上沿 4 + 12 + 24（底版无卡内上下 4 → 36，红）", 40f, tb.textBox().center.y - card.top, d)
        assertNear("末行文字中线距卡下沿 24 + 12 + 4", 40f, card.bottom - pdd.textBox().center.y, d)
    }

    @Test fun a04DividerRunsTheCardInnerWidth() {
        host.launch("normal")
        val px = host.pixels()
        val tb = host.inRow("taobao", host.name("taobao"))
        val pdd = host.inRow("pdd", host.name("pdd"))
        val card = host.cardOf(tb, px)
        val x = card.center.x
        val y =
            (
                tb
                    .textBox()
                    .center.y
                    .toInt() until
                    pdd
                        .textBox()
                        .center.y
                        .toInt()
            ).firstOrNull { sameColor(px.at(x, it.toFloat()), host.palette.subtle) }
        assertNotNull("先正：两行之间有 border.subtle 分隔线", y)
        val left = px.firstFromLeft(y!!.toFloat(), host.palette.subtle, card.left)
        val right = px.firstFromRight(y.toFloat(), host.palette.subtle, card.right)
        assertNear("A4：分隔线左端在卡左缘内 16", 16f, left - card.left, d, 1f)
        assertNear("A4：分隔线右端在卡右缘内 16", 16f, card.right - right, d, 1f)
        assertNear("分隔线在两行正中（行上下 12）", 36f, y - tb.textBox().center.y, d, 2f)
    }

    // ---------------------------------------------------------------- A6 平台名 / A7 状态

    @Test fun a06PlatformRowHasTwentyIconThenEightThenNameSeventeenMedium() {
        host.launch("normal")
        listOf("taobao", "pdd").forEach { p ->
            val name = host.inRow(p, host.name(p))
            assertEquals("$p 平台名 17", 17f, name.textStyle().fontSize.value, 0.01f)
            assertEquals("A6：$p 平台名 17/500（底版 600，红）", FontWeight.Medium, name.textStyle().fontWeight)
            val icons = host.node(host.row(p)).squareLeaves(18f, 22f).filter { it.right <= name.textBox().left + d }
            assertEquals("A6 / G-A04：$p 名左侧恰一个 20 平台图标（底版无图标，红）：$icons", 1, icons.size)
            val icon = icons.single()
            assertNear("图标 20", 20f, icon.width, d, 0.6f)
            assertNear("图标与名间距 8", 8f, name.textBox().left - icon.right, d, 1f)
            assertNear("图标与名竖向居中", icon.center.y / d, name.textBox().center.y, d, 2f)
        }
        val card = host.cardOf(host.inRow("taobao", host.name("taobao")))
        val icon = host.node(host.row("taobao")).squareLeaves(18f, 22f).first()
        assertNear("图标贴卡内左 16", 16f, icon.left - card.left, d, 1f)
    }

    @Test fun a07AuthorizedHasSixteenCheckCirclePrimaryText() {
        host.launch("normal")
        val px = host.pixels()
        val status = host.inRow("taobao", host.text(AUTHORIZED_KEY))
        assertEquals("状态 14", 14f, status.textStyle().fontSize.value, 0.01f)
        assertTrue(
            "A7：「已授权」主文字色（底版次文字，红）",
            sameColor(status.textStyle().color.toArgb(), host.palette.primary),
        )
        val checks = host.node(host.row("taobao")).squareLeaves(14f, 18f).filter { it.right <= status.textBox().left + d }
        assertEquals("A7：「已授权」左侧恰一个 16 圆勾（底版无，红）：$checks", 1, checks.size)
        val check = checks.single()
        assertNear("圆勾与字间距 4", 4f, status.textBox().left - check.right, d, 1f)
        assertTrue("圆勾是次文字色（深色 #B5ACA2 同令牌）", px.inkOf(check, host.palette.secondary) > 0)
        assertEquals("圆勾不是成功绿（反）", 0, px.inkOf(check, host.palette.successText, 6))
        val unauthorized = host.inRow("pdd", host.text(UNAUTHORIZED_KEY))
        assertTrue("「未授权」次文字色", sameColor(unauthorized.textStyle().color.toArgb(), host.palette.secondary))
        val pddName = host.inRow("pdd", host.name("pdd")).textBox()
        val between =
            host.node(host.row("pdd")).squareLeaves(14f, 18f).filter {
                it.left >= pddName.right &&
                    it.right <= unauthorized.textBox().left + d
            }
        assertEquals("「未授权」前没有圆勾（反）：$between", 0, between.size)
    }

    // ---------------------------------------------------------------- A9 【去授权】（G-A05）

    @Test fun a09GrantIsSolidPrimaryTwelveAfterTheStatus() {
        host.launch("normal")
        val px = host.pixels()
        val button = host.node(host.grantTag("pdd"))
        val box = button.box()
        val sample = px.at(box.left + 6 * d, box.top + 4 * d)
        assertTrue("A9 / G-A05：【去授权】实心主按钮底色（底版次按钮白底，红）", sameColor(sample, host.palette.buttonPrimary, 4))
        assertFalse("不是白底描边次按钮（反）", sameColor(sample, host.palette.surface, 2))
        val label = host.grantText("pdd")
        assertEquals("按钮字 14", 14f, label.textStyle().fontSize.value, 0.01f)
        assertEquals("按钮字 600", FontWeight.SemiBold, label.textStyle().fontWeight)
        val status = host.inRow("pdd", host.text(UNAUTHORIZED_KEY))
        assertNear("A9：「未授权」与按钮间距 12（底版 8，红）", 12f, box.left - status.textBox().right, d, 1.5f)
        val card = host.cardOf(host.inRow("pdd", host.name("pdd")))
        assertNear("按钮右缘贴卡内右 16", 16f, card.right - box.right, d, 1f)
        assertTrue("命中区 ≥48", button.touch().height / d >= 47.5f && button.touch().width / d >= 47.5f)
        assertTrue("语义盒 ≥48（APP-12w-a r1 S2-4）", box.height / d >= 47.5f && box.width / d >= 47.5f)
        assertFalse("已授权行没有【去授权】", host.has(host.grantTag("taobao")))
    }

    @Test fun a16GrantInFlightSpinsInTheButtonWithoutNewWords() {
        host.launch("both_unbound")
        host.tap(host.grantTag("taobao"))
        assertEquals("取地址在途（挂起）", 1, host.api.count(GRANT))
        val button = host.node(host.grantTag("taobao"))
        val spinners =
            rule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher
                        .keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
                        hasAnyAncestor(hasTestTag(host.grantTag("taobao"))),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertEquals("G-A14：授权中按钮里转圈（组件库现状）", 1, spinners.size)
        assertNotNull("授权中按钮不可再点", button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled))
        val texts =
            rule
                .onAllNodes(hasAnyAncestor(hasTestTag(host.row("taobao"))), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { n ->
                    n.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                }.toSet()
        assertEquals(
            "G-A14：行内只有平台名 / 未授权 / 去授权，不新增「授权中…」文字",
            setOf(host.name("taobao"), host.text(UNAUTHORIZED_KEY), host.text(GRANT_KEY)),
            texts,
        )
        val sample = host.pixels().at(button.box().left + 6 * d, button.box().top + 4 * d)
        assertTrue("授权中仍是主按钮底色（loading 不是 disabled 外观）", sameColor(sample, host.palette.buttonPrimary, 4))
    }

    @Test fun a16RateLimitedGrantsUseTheDisabledToken() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c42901")
        host.launch("both_unbound")
        host.tap(host.grantTag("taobao"))
        listOf("taobao", "pdd").forEach { p ->
            val button = host.node(host.grantTag(p))
            assertNotNull("42901：$p【去授权】置灰", button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled))
        }
        // 只在拼多多行取样：页顶 error.42901 轻提示可能盖住第一行右侧
        val pdd = host.node(host.grantTag("pdd")).box()
        val sample = host.pixels().at(pdd.left + 6 * d, pdd.top + 4 * d)
        assertTrue("置灰用组件库禁用令牌", sameColor(sample, host.palette.buttonDisabled, 4))
        assertFalse("置灰不是可点主色（反）", sameColor(sample, host.palette.buttonPrimary, 2))
    }

    // ---------------------------------------------------------------- A10 被停用行（G-A06）

    @Test fun a10BlockedRowKeepsOnlyUnauthorizedOnTheRight() {
        host.launch("blocked")
        val name = host.inRow("taobao", host.name("taobao"))
        val status = host.inRow("taobao", host.text(UNAUTHORIZED_KEY))
        val card = host.cardOf(name)
        assertFalse("停用行没有【去授权】", host.has(host.grantTag("taobao")))
        assertNear("先正：行右只「未授权」、贴卡内右 16", 16f, card.right - status.textBox().right, d, 1f)
        assertNear("「未授权」与平台名同一行", name.textBox().center.y / d, status.textBox().center.y, d, 2f)
    }

    @Test fun a10BlockedNoticeIsAnErrorBlockWithTriangleAndOutlinedContact() {
        host.launch("blocked")
        val px = host.pixels()
        val name = host.inRow("taobao", host.name("taobao"))
        val card = host.cardOf(name, px)
        val message = host.inRow("taobao", host.text(BLOCKED_KEY))
        assertEquals("停用说明 14", 14f, message.textStyle().fontSize.value, 0.01f)
        assertTrue("停用说明 status.error 字色", sameColor(message.textStyle().color.toArgb(), host.palette.errorText))
        val block = px.blockAround(message, host.palette.errorBackground, d)
        assertNotNull("A10：说明在 status.error 底色块里（底版无底色块，红）", block)
        block!!
        assertNear("块左缘贴卡内左 16", 16f, block.left - card.left, d)
        assertNear("块右缘贴卡内右 16", 16f, card.right - block.right, d)
        assertNear("块内边距 12：说明上沿", 12f, message.textBox().top - block.top, d)
        assertNear("块内 12 + 三角 20 + 8：说明左缘", 40f, message.textBox().left - block.left, d)
        assertNear("块在行下（平台行 48 之下 12）", 12f, block.top - (name.textBox().center.y + 24 * d), d, 2f)
        assertEquals(
            "块圆角 12（2dp 处内缩约 ${cornerInsetFor(12f, 2f)}）",
            cornerInsetFor(12f, 2f),
            px.cornerInsetDp(block, host.palette.errorBackground, 2f, d),
            1.5f,
        )
        val triangles =
            host
                .node(host.row("taobao"))
                .squareLeaves(18f, 22f)
                .filter { it.left >= block.left && it.right <= message.textBox().left }
        assertEquals("块内恰一个 20 警告三角（不是平台图标）：$triangles", 1, triangles.size)
        assertNear("三角距块左 12", 12f, triangles.single().left - block.left, d, 1f)
        assertTrue("三角是 status.error 字色", px.inkOf(triangles.single(), host.palette.errorText) > 0)
        val contact = host.node(AuthManageTags.CONTACT)
        val c = contact.box()
        assertNear("【联系客服】在说明下 12", 12f, c.top - message.textBox().bottom, d, 1.5f)
        assertNear("【联系客服】左对齐说明", 0f, c.left - message.textBox().left, d, 1f)
        assertTrue("【联系客服】在块内", c.bottom <= block.bottom + d && c.right <= block.right + d)
        assertTrue("【联系客服】白底（描边次按钮）", sameColor(px.at(c.center.x, c.top + 3 * d), host.palette.surface, 3))
        assertTrue(
            "A10：【联系客服】1px border.control 描边（底版文字按钮无描边，红）",
            px.inkOf(Rect(c.left, c.center.y - 2 * d, c.left + 1.5f * d, c.center.y + 2 * d), host.palette.control, 8) > 0,
        )
        val label = host.textNode(host.text(CONTACT_KEY))
        assertEquals("【联系客服】字 14", 14f, label.textStyle().fontSize.value, 0.01f)
        assertTrue("【联系客服】主文字色（不是链接色）", sameColor(label.textStyle().color.toArgb(), host.palette.primary))
        assertTrue("命中区 ≥48", contact.touch().height / d >= 47.5f && contact.touch().width / d >= 47.5f)
        assertTrue("语义盒 ≥48", c.height / d >= 47.5f && c.width / d >= 47.5f)
    }

    // ---------------------------------------------------------------- A11 底部说明

    @Test fun a11RebindNoteIsCaptionSecondaryInsetFour() {
        host.launch("normal")
        val note = host.textNode(host.text(REBIND_KEY))
        val card = host.cardOf(host.inRow("pdd", host.name("pdd")))
        assertEquals("说明 12", 12f, note.textStyle().fontSize.value, 0.01f)
        assertTrue("说明次文字色", sameColor(note.textStyle().color.toArgb(), host.palette.secondary))
        assertNear("说明在卡下 12", 12f, note.textBox().top - card.bottom, d)
        assertNear("A11：说明左右内边距 4（底版 0，红）", 4f, note.textBox().left - card.left, d, 1f)
        assertTrue("说明不进卡片（反）", note.textBox().top > card.bottom)
    }

    @Test fun a11RebindNoteOnlyInTheListState() {
        host.launchListError(AuthManageVisualFixtures.failure("c50001"))
        assertEquals("错误态不显示底部说明（现状）", 0, host.countText(host.text(REBIND_KEY)))
        assertNull("错误态没有平台卡行", rule.onAllNodes(hasTestTag(host.row("taobao"))).fetchSemanticsNodes().firstOrNull())
    }

    // ---------------------------------------------------------------- A14 列表失败（G-A09 不改结构）

    @Test fun a14ListErrorKeepsTheTextAndSecondaryRetry() {
        host.launchListError(AuthManageVisualFixtures.failure("c50001"))
        val retry = host.node(AuthManageTags.RETRY)
        val px = host.pixels()
        assertTrue("【重试】仍是次按钮（白底，G-A09 不改）", sameColor(px.at(retry.box().left + 6 * d, retry.box().top + 4 * d), host.palette.surface, 3))
        assertEquals("错误句带 trace6（只有 5xxxx）", 1, host.countText(AuthManageVisualFixtures.trace6, substring = true))
        assertTrue("命中区 ≥48", retry.touch().height / d >= 47.5f)
    }

    // ---------------------------------------------------------------- A18 标记（不改不增）

    @Test fun a18ExistingTagsStayOnTheSameNodes() {
        host.launch("both_unbound")
        assertTrue(host.has(AuthManageTags.PAGE))
        listOf("taobao", "pdd").forEach {
            assertTrue("行标记 $it", host.has(host.row(it)))
            assertTrue(
                "授权标记 $it 在可点按钮上",
                host
                    .node(host.grantTag(it))
                    .fetchSemanticsNode()
                    .config
                    .contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
            )
        }
        assertEquals("名在行块之下", 1, host.countInRow("taobao", host.name("taobao")))
    }
}
