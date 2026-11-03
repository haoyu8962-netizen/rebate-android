package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
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
 * APP-12w-a S1–S7、S9–S11（brief-android §2）：设置主页的分组、卡片、行、底部，全部在真实页面上量（文字盒 / 像素 / 布局子树），先正后反。
 * 画板 Settings-v2：内容上 8、左右 16；分组名 12 次文字左缩进 4、名到卡 8、组间 20；卡内上下 4；行 52（节距 53）、chevron 20、值与箭头 4、
 * 标题与右侧 12；动作行无 ›；【退出登录】距上卡 20、高 48、通栏次按钮。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun s1s2s3GroupsCardsAndPaddingsFollowSettingsV2() {
        host.launch()
        val d = host.row(SettingsRows.NICKNAME).density()
        val px = rule.pixels()
        val cx = px.width / 2f
        val top = rule.scrollUnder(MeTags.SETTINGS_PAGE).box().top
        val groups =
            listOf(
                "账号与安全" to SettingsRows.account,
                "隐私" to SettingsRows.privacy,
                "通用" to SettingsRows.general,
            )
        val labels = groups.map { rule.textNode(it.first).textBox() }
        assertDp("S1 内容上边距 8（标题栏下沿 → 第一个分组名）", 8f, labels.first().top - top, d)
        var previousCardBottom: Float? = null
        groups.forEachIndexed { index, (name, rows) ->
            val label = labels[index]
            val first = host.node(SettingsRows.tag(rows.first())).box()
            val last = host.node(SettingsRows.tag(rows.last())).box()
            val cardTop = px.edgeUp(cx, first.top - 1, host.palette.surface)
            val cardBottom = px.edgeDown(cx, last.bottom, host.palette.surface)
            assertDp("S2 $name：分组名到卡 8", 8f, cardTop - label.bottom, d)
            assertDp("S3 $name：卡内上内边距 4", 4f, first.top - cardTop, d)
            assertDp("S3 $name：卡内下内边距 4", 4f, cardBottom - last.bottom, d)
            previousCardBottom?.let { assertDp("S2 组间 20（上一卡下沿 → $name 分组名）", 20f, label.top - it, d) }
            previousCardBottom = cardBottom
            val cardLeft = px.firstFromLeft(first.center.y, host.palette.surface)
            assertDp("卡片左缘 16", 16f, cardLeft, d)
            assertDp("分组名左缩进 4（相对卡片）", 4f, label.left - cardLeft, d)
            val style = rule.textNode(name).textStyle()
            assertEquals("分组名 12", 12f, style.fontSize.value, 0.01f)
            assertEquals("分组名次文字色", host.palette.secondary, style.color)
            val heading =
                rule
                    .textNode(name)
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Heading)
            assertTrue("分组名读屏为标题", heading)
        }
    }

    @Test fun s4NavRowsAre52WithChevron20Value4AndTitle12() {
        host.launch()
        val nickname = host.row(SettingsRows.NICKNAME)
        val d = nickname.density()
        val px = rule.pixels()
        (SettingsRows.account + SettingsRows.privacy + SettingsRows.general - SettingsRows.CLIPBOARD).forEach { key ->
            val row = host.row(key).box()
            assertDp("S4 单行导航行高 = 52（画板 min-height 44 + 上下 4，节距 53；11-02 14:45）：$key", 52f, row.height, d, 0.5f)
        }
        val row = nickname.box()
        val icons = nickname.iconBoxes()
        assertEquals("昵称行恰有一个 chevron 图标盒：$icons", 1, icons.size)
        val chevron = icons.single()
        assertDp("S4 chevron 20", 20f, chevron.width, d, 0.5f)
        val cardRight = px.firstFromRight(row.center.y, host.palette.surface)
        val cardLeft = px.firstFromLeft(row.center.y, host.palette.surface)
        assertDp("chevron 右缘距卡片右缘 16", 16f, cardRight - chevron.right, d)
        val title = rule.textUnder(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.NICKNAME, "昵称")
        val value = rule.textUnder(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.NICKNAME, SettingsVisualFixtures.original)
        assertDp("标题左缘距卡片左缘 16", 16f, title.textBox().left - cardLeft, d)
        assertDp("S4 值与箭头 4", 4f, chevron.left - value.textBox().right, d)
        assertDp("S4 标题与右侧 12", 12f, value.textBox().left - title.textBox().right, d)
        assertEquals("标题 17", 17f, title.textStyle().fontSize.value, 0.01f)
        assertEquals("右侧值 14", 14f, value.textStyle().fontSize.value, 0.01f)
        assertEquals("右侧值次文字色", host.palette.secondary, value.textStyle().color)
        assertTrue("chevron 为次文字色墨迹", px.countNear(chevron, host.palette.secondary, 24) >= 6)
        listOf(SettingsRows.PHONE, SettingsRows.AUTH, SettingsRows.HELP).forEach { key ->
            val boxes = host.row(key).iconBoxes()
            assertEquals("$key 行 chevron 20", 20f, boxes.single().width / d, 0.5f)
        }
    }

    @Test fun s5ResetTipIsAnActionRowWithoutChevronButStillAButton() {
        host.launch()
        val reset = host.row(SettingsRows.RESET_TIP)
        val help = host.row(SettingsRows.HELP)
        val d = reset.density()
        val px = rule.pixels()
        // 先正：帮助中心行标题之后有 chevron 墨迹
        val helpTitle = rule.textUnder(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.HELP, "帮助中心").glyphBox()
        val helpRow = help.box()
        val helpTail = Rect(helpTitle.right + 2 * d, helpRow.top + 4 * d, helpRow.right - 8 * d, helpRow.bottom - 4 * d)
        assertNotNull("对照：导航行有 ›", px.ink(helpTail, host.palette.surface))
        // 再反：动作行同一区域没有任何墨迹、也没有图标盒（BR-ATTR-21：点了不跳页）
        val title = rule.textUnder(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.RESET_TIP, "重新显示下单须知").glyphBox()
        val row = reset.box()
        val tail = Rect(title.right + 2 * d, row.top + 4 * d, row.right - 8 * d, row.bottom - 4 * d)
        assertNull("S5 动作行不画 chevron", px.ink(tail, host.palette.surface))
        assertTrue("S5 动作行没有图标盒：${reset.iconBoxes()}", reset.iconBoxes().isEmpty())
        val config = reset.fetchSemanticsNode().config
        assertEquals("动作行读屏仍是按钮", Role.Button, config.getOrNull(SemanticsProperties.Role))
        assertTrue("动作行仍可点", config.contains(SemanticsActions.OnClick))
    }

    @Test fun s6s7PhoneShowsBoundStatusAndPublicBuildHasDeleteRow() {
        host.launch(public = true)
        val phone = host.row(SettingsRows.PHONE)
        val spoken = rule.onNodeWithTag(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.PHONE).fetchSemanticsNode().spokenParts()
        assertTrue("S6 手机号值仍是「已绑定」（G-S04 无脱敏字段）：$spoken", spoken.any { it == "已绑定" })
        assertFalse("不得拼本机号码（G-S04）", spoken.any { it.contains(SettingsVisualFixtures.localPhone.takeLast(4)) })
        assertEquals("本机知道号码：换号入口可点、画 ›", 1, phone.iconBoxes().size)
        assertTrue("S7 公开版有注销账号行（G-S05 注入路由）", host.has(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.DELETE))
    }

    @Test fun s7BetaBuildHasNoDeleteRow() {
        host.launch(public = false)
        assertTrue("先正：账号组在", host.has(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.AUTH))
        assertFalse("内测版无注销账号行（不画成置灰）", host.has(MeTags.SETTINGS_ROW_PREFIX + SettingsRows.DELETE))
    }

    @Test fun s9LogoutIsFullWidthSecondaryButton20BelowLastCard() {
        host.launch()
        val logout = host.node(MeTags.SETTINGS_LOGOUT)
        val d = logout.density()
        val px = rule.pixels()
        val about = host.row(SettingsRows.ABOUT).box()
        val cardBottom = px.edgeDown(px.width / 2f, about.bottom, host.palette.surface)
        val box = logout.box()
        assertDp("S9 【退出登录】距上一张卡 20", 20f, box.top - cardBottom, d)
        assertDp("S9 高 48", 48f, box.height, d, 0.5f)
        assertDp("通栏：左 16", 16f, box.left, d)
        assertDp("通栏：右 374", 374f, box.right, d)
        val text = rule.textUnder(MeTags.SETTINGS_LOGOUT, "退出登录")
        assertEquals("17", 17f, text.textStyle().fontSize.value, 0.01f)
        assertEquals("500", FontWeight.W500, text.textStyle().fontWeight)
        assertTrue("次按钮白底", sameColor(px.at(box.left + 8 * d, box.center.y), host.palette.surface))
        assertTrue(
            "控件边 1",
            px.countNear(Rect(box.left, box.top + 12 * d, box.left + 2 * d, box.bottom - 12 * d), host.palette.control, 24) > 0,
        )
    }

    @Test fun s10GuestFooterIsCenteredNoteAndTextLink() {
        host.launch(session = "guest_consented")
        assertEquals("游客零 getMe", 0, host.api.count(SettingsVisualApi.GET_ME))
        val note = rule.textNode("登录后可以管理账号、授权和通知")
        val link = rule.textUnder(MeTags.SETTINGS_LOGIN, "去登录")
        val d = note.density()
        val center =
            rule
                .scrollUnder(MeTags.SETTINGS_PAGE)
                .box()
                .center.x
        assertEquals("说明 14", 14f, note.textStyle().fontSize.value, 0.01f)
        assertEquals("说明次文字色", host.palette.secondary, note.textStyle().color)
        assertEquals("说明居中", center / d, note.glyphBox().center.x / d, 2f)
        assertEquals("链接 600", FontWeight.W600, link.textStyle().fontWeight)
        assertEquals("链接色", host.palette.link, link.textStyle().color)
        assertEquals("链接居中", center / d, link.glyphBox().center.x / d, 2f)
        assertDp("说明与链接间 8（命中区不撑开）", 8f, link.textBox().top - note.textBox().bottom, d, 1.5f)
        val hit = host.node(MeTags.SETTINGS_LOGIN).touch()
        assertAtLeastDp("链接命中高", 48f, hit.height, d)
        assertAtLeastDp("链接命中宽", 48f, hit.width, d)
        // v2（评审 r1 S2-4 / M07）：读屏焦点框 = 带标记节点的语义盒，也须 ≥48（touchBounds 有系统最小触控扩展，量不出）
        val focus = host.node(MeTags.SETTINGS_LOGIN).box()
        assertAtLeastDp("链接读屏边界高", 48f, focus.height, d)
        assertAtLeastDp("链接读屏边界宽", 48f, focus.width, d)
        val px = rule.pixels()
        assertEquals("不是填充按钮：链接文字周围是页面底色", 0, px.countNear(hit, host.palette.brand, 2))
    }

    @Test fun g03GuestAndBasicKeepTheirCurrentOrderAndSingleUnnamedGroup() {
        host.launch(session = "guest_consented")
        val tops = SettingsRows.guest.map { host.node(SettingsRows.tag(it)).box().top }
        assertEquals("G-S03：游客条目顺序不改", tops.sorted(), tops)
        listOf("账号与安全", "隐私", "通用").forEach { assertEquals("游客不出分组名（G-S03 另立 FU）：$it", 0, host.count(it)) }
    }

    @Test fun s11BasicModeHasNoticeStripAboveEnableButton() {
        host.launch(session = "basic")
        assertEquals("基本模式零 getMe", 0, host.api.count(SettingsVisualApi.GET_ME))
        val tops = SettingsRows.basic.map { host.row(it).box().top }
        assertEquals("G-S03：基本模式顺序不改（关于在帮助之前）", tops.sorted(), tops)
        assertFalse("基本模式不显示剪贴板行", host.has(SettingsRows.tag(SettingsRows.CLIPBOARD)))
        val notice = rule.textNode("当前为基本模式", substring = true)
        val d = notice.density()
        val text = notice.textBox()
        val style = notice.textStyle()
        assertEquals("提示 14", 14f, style.fontSize.value, 0.01f)
        assertEquals("提示 500", FontWeight.W500, style.fontWeight)
        assertEquals("提示主文字色", host.palette.primary, style.color)
        val px = rule.pixels()
        assertTrue("提示条 muted 底", sameColor(px.at(text.left - 4 * d, text.top + 2 * d), host.palette.muted))
        val icon = Rect(text.left - 28 * d, text.top, text.left - 8 * d, text.top + 20 * d)
        assertTrue("info 图标 20 次文字色", px.countNear(icon, host.palette.secondary, 24) >= 6)
        val button = host.node(MeTags.SETTINGS_ENABLE_FULL).box()
        assertTrue("提示条在【开启完整功能】之上", button.top > text.bottom)
        assertTrue("通栏主按钮", sameColor(px.at(button.left + 8 * d, button.center.y), host.palette.brand))
    }

    @Test fun s13AgentRowIsAbsentInReleaseShape() {
        host.launch()
        assertEquals("撤回 AI 行不出现（Release 口径，无稿）", 0, host.count("撤回 AI 服务同意"))
    }
}
