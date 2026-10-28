package com.couli.app.me

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.WithdrawVisualWindowProbe
import com.couli.feature.me.MeTags
import kotlin.math.abs
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
 * 隐私中心视觉结构（brief-b-android P1–P11，画板 PrivacyCenter / PrivacyCenterBasic-v2 / PrivacyCenterWithdraw / PrivacyCenterForceUpdate）。
 * 卡片边界按 surface 像素扫描（量外框，不量内容盒）；文字用真实 TextLayoutResult；先正后反。底版（main fe60a02）上视觉断言应红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h864dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class PrivacyVisualPrivacyCenterTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private val rows =
        listOf(
            MeTags.PRIVACY_LIST_PREFIX + "collect" to "privacy_center.collect_list",
            MeTags.PRIVACY_LIST_PREFIX + "share" to "privacy_center.share_list",
            MeTags.PRIVACY_POLICY to "privacy_center.privacy_policy",
            MeTags.PRIVACY_AGREEMENT to "privacy_center.agreement",
        )

    // ---------------------------------------------------------------- P1 / P2 清单与协议卡

    @Test fun p1ContentStartsEightBelowTheTitleBarAndSixteenFromTheEdges() {
        host.launchPrivacy()
        val first = host.node(rows.first().first)
        val d = first.density()
        val px = host.pixels(first)
        val card = cardAround(px, first.box())
        val top = rule.scrollUnder(MeTags.PRIVACY_PAGE).box().top
        assertDp("P1 内容上 8（标题栏下沿到清单卡外框）", 8f, card.top - top, d, 1f)
        assertDp("P1 左 16", 16f, card.left, d, 1f)
        assertDp("P1 右 16", 16f, px.width - card.right, d, 1f)
    }

    @Test fun p2ListCardHasInnerFourAndFour48RowsWithChevron20() {
        host.launchPrivacy()
        val first = host.node(rows.first().first)
        val d = first.density()
        val px = host.pixels(first)
        val card = cardAround(px, first.box())
        assertDp("P2 卡内上 4", 4f, first.box().top - card.top, d, 1f)
        assertDp("P2 卡内下 4", 4f, card.bottom - host.node(rows.last().first).box().bottom, d, 1f)
        var previous: Rect? = null
        rows.forEach { (tag, key) ->
            val row = host.node(tag)
            val box = row.box()
            assertDp("P2 行高 48（min 44 + 上下 8，Android 命中区）：$key", 48f, box.height, d, 0.5f)
            assertDp("整行通栏于卡（左）", 0f, box.left - card.left, d, 1f)
            assertDp("整行通栏于卡（右）", 0f, card.right - box.right, d, 1f)
            val title = rule.textUnder(tag, AppMeTexts.text(key))
            assertEquals("行标题 17", 17f, title.textStyle().fontSize.value, 0.01f)
            assertEquals("行标题主文字色", host.palette.primary, title.textStyle().color)
            assertDp("标题左距卡 16", 16f, title.textBox().left - card.left, d, 1f)
            val icons = row.iconBoxes()
            assertEquals("chevron 20：$icons", 20f, icons.single().width / d, 0.5f)
            assertDp("chevron 右距卡 16", 16f, card.right - icons.single().right, d, 1f)
            previous?.let { assertTrue("相邻两行只隔 1px 分隔线：$it / $box", box.top >= it.bottom && box.top - it.bottom <= 1.5f * d) }
            previous = box
        }
        val y = (host.node(rows[0].first).box().bottom + host.node(rows[1].first).box().top) / 2
        assertTrue("行间 1px border.subtle 分隔（与卡内边距 16 齐）", sameColor(px.at(card.left + 24 * d, y), host.palette.subtle, 6))
        assertFalse("分隔线不通到卡边", sameColor(px.at(card.left + 8 * d, y), host.palette.subtle, 6))
    }

    @Test fun p2EachRowIsOneButtonReadingItsTitle() {
        host.launchPrivacy()
        rows.forEach { (tag, key) ->
            val node = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNode()
            assertEquals("读屏「标题，按钮」：$key", Role.Button, node.config.getOrNull(SemanticsProperties.Role))
            assertEquals(listOf(AppMeTexts.text(key)), node.spokenParts())
        }
    }

    // ---------------------------------------------------------------- P3 个性化卡

    @Test fun p3PersonalizationCardPaddingSixteenTitleDescGapFourSwitchCentered() {
        host.launchPrivacy()
        val title = rule.textUnder(MeTags.PRIVACY_SWITCH, AppMeTexts.text("privacy_center.personalization"))
        val desc = rule.textUnder(MeTags.PRIVACY_SWITCH, AppMeTexts.text("privacy_center.personalization_desc"))
        val d = title.density()
        val px = host.pixels(title)
        val card = cardAround(px, title.textBox())
        assertDp("P3 卡内上 16", 16f, title.textBox().top - card.top, d, 1f)
        assertDp("P3 卡内下 16", 16f, card.bottom - desc.textBox().bottom, d, 1f)
        assertDp("P3 卡内左 16", 16f, title.textBox().left - card.left, d, 1f)
        assertDp("标题与说明纵向 4", 4f, desc.textBox().top - title.textBox().bottom, d, 1f)
        assertEquals("标题 17", 17f, title.textStyle().fontSize.value, 0.01f)
        assertEquals("说明 12", 12f, desc.textStyle().fontSize.value, 0.01f)
        assertEquals("说明次文字色", host.palette.secondary, desc.textStyle().color)
        val track = requireNotNull(px.pvColorBox(card, host.palette.brand, 4)) { "开关（已打开）轨道为品牌色" }
        assertEquals("开关在卡内垂直居中", card.center.y / d, track.center.y / d, 2f)
        assertTrue("说明不被开关挤压：右缘在开关左 12 以外", desc.glyphBox().right <= track.left - 12 * d + d)
        assertDp("开关右距卡 16（含 M3 开关自身留白）", 16f, card.right - track.right, d, 4f)
    }

    @Test fun p3SwitchReadsNameSwitchStateAndDescriptionOnce() {
        host.launchPrivacy()
        val node = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MeTags.PRIVACY_SWITCH)).fetchSemanticsNode()
        assertEquals(Role.Switch, node.config.getOrNull(SemanticsProperties.Role))
        val parts = node.spokenParts()
        val desc = AppMeTexts.text("privacy_center.personalization_desc")
        assertTrue("名称：$parts", parts.contains(AppMeTexts.text("privacy_center.personalization")))
        assertEquals("说明读一次：$parts", 1, parts.count { it == desc })
        val state = node.config.getOrNull(SemanticsProperties.StateDescription)
        assertTrue("状态描述不得被说明覆盖（G-S12 同类）：$state", state == null || state == AppMeTexts.text("me.switch.on"))
    }

    @Test fun p3PersonalizationOffShowsTheSwitchOffWithoutBrandTrack() {
        host.launchPrivacy(personalization = false)
        val title = rule.textUnder(MeTags.PRIVACY_SWITCH, AppMeTexts.text("privacy_center.personalization"))
        val px = host.pixels(title)
        val card = cardAround(px, title.textBox())
        assertEquals("关闭态无品牌色轨道（先正见 p3 主用例）", null, px.pvColorBox(card, host.palette.brand, 4))
        val node = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MeTags.PRIVACY_SWITCH)).fetchSemanticsNode()
        assertEquals(ToggleableState.Off, node.config.getOrNull(SemanticsProperties.ToggleableState))
    }

    // ---------------------------------------------------------------- P4 系统权限分节头

    @Test fun p4PermissionHeaderIndentsFourWithTitle14SemiboldAndHint12() {
        host.launchPrivacy()
        val title = rule.textUnder(MeTags.PRIVACY_PERMISSIONS, AppMeTexts.text("privacy.system_permissions.title"))
        val hint = rule.textUnder(MeTags.PRIVACY_PERMISSIONS, AppMeTexts.text("privacy.system_permissions.hint"))
        val d = title.density()
        assertDp("P4 标题左缩进 4（16 + 4）", 20f, title.textBox().left, d, 0.75f)
        assertDp("P4 说明左缩进 4", 20f, hint.textBox().left, d, 0.75f)
        assertTrue("P4 右缩进 4：说明右缘 ≤ 390 − 20", hint.textBox().right <= (390f - 20f + 0.75f) * d)
        assertEquals(14f, title.textStyle().fontSize.value, 0.01f)
        assertEquals(FontWeight.SemiBold, title.textStyle().fontWeight)
        assertEquals("标题次文字色", host.palette.secondary, title.textStyle().color)
        assertTrue("分节标题读屏为标题", title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertEquals(12f, hint.textStyle().fontSize.value, 0.01f)
        assertEquals(host.palette.secondary, hint.textStyle().color)
        assertDp("标题与说明纵向 4", 4f, hint.textBox().top - title.textBox().bottom, d, 1f)
        val px = host.pixels(title)
        val switchCard = cardAround(px, rule.textUnder(MeTags.PRIVACY_SWITCH, AppMeTexts.text("privacy_center.personalization")).textBox())
        assertDp("分节头上 8（列间 12 + 8）", 20f, title.textBox().top - switchCard.bottom, d, 1.5f)
    }

    // ---------------------------------------------------------------- P6 撤回同意卡

    @Test fun p6WithdrawCardWrapsTitleDescAndFullWidthSecondaryButton() {
        host.launchPrivacy()
        val title = host.pageHeading(AppMeTexts.text("privacy_center.withdraw_title"))
        val desc = host.pageText(AppMeTexts.text("privacy_center.withdraw_desc"))
        val button = host.node(MeTags.PRIVACY_WITHDRAW)
        val d = title.density()
        val px = host.pixels(title)
        val card = cardAround(px, title.textBox())
        assertDp("P6 卡左 16", 16f, card.left, d, 1f)
        assertDp("P6 卡内上 16", 16f, title.textBox().top - card.top, d, 1f)
        assertDp("P6 卡内左 16", 16f, title.textBox().left - card.left, d, 1f)
        assertEquals("标题 17", 17f, title.textStyle().fontSize.value, 0.01f)
        assertEquals("标题 600", FontWeight.SemiBold, title.textStyle().fontWeight)
        assertEquals("说明 14", 14f, desc.textStyle().fontSize.value, 0.01f)
        assertEquals("说明次文字色", host.palette.secondary, desc.textStyle().color)
        assertDp("标题与说明纵向 4", 4f, desc.textBox().top - title.textBox().bottom, d, 1f)
        val b = button.box()
        assertDp("说明到按钮 12", 12f, b.top - desc.textBox().bottom, d, 1f)
        assertDp("按钮通栏于卡内（左 16）", 16f, b.left - card.left, d, 1f)
        assertDp("按钮通栏于卡内（右 16）", 16f, card.right - b.right, d, 1f)
        assertDp("卡内下 16", 16f, card.bottom - b.bottom, d, 1f)
        assertTrue("按钮 ≥ 48", b.height / d >= 47.5f)
    }

    @Test fun g17WithdrawButtonIsAPlainSecondaryNotDanger() {
        host.launchPrivacy()
        val button = host.node(MeTags.PRIVACY_WITHDRAW)
        val px = host.pixels(button)
        val b = button.box()
        assertEquals("不是主按钮（无品牌填充）", 0, px.countNear(b, host.palette.buttonPrimary, 2))
        assertEquals("不是危险样式（无错误色）", 0, px.countNear(b, host.palette.error, 2))
        assertTrue("白底 + border.control 描边（先正）", px.countNear(b, host.palette.control, 8) >= 20)
        assertEquals(
            host.palette.primary,
            rule.textUnder(MeTags.PRIVACY_WITHDRAW, AppMeTexts.text("privacy_center.withdraw")).textStyle().color,
        )
    }

    // ---------------------------------------------------------------- P7 / P8 基本模式

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun p7BasicEnableCardHasTitleAndPrimaryButtonWithoutTheOldSubline() {
        host.launchPrivacy(session = "privacy_basic")
        assertEquals("G-P08 不再显示副句", 0, host.count(AppMeTexts.text("privacy_center.basic_desc")))
        val title = host.pageHeading(AppMeTexts.text("privacy_center.basic_title"))
        val button = host.node(MeTags.PRIVACY_ENABLE_FULL)
        val d = title.density()
        val px = host.pixels(title)
        val card = cardAround(px, title.textBox())
        assertDp("卡内上 16", 16f, title.textBox().top - card.top, d, 1f)
        assertDp("卡内左 16", 16f, title.textBox().left - card.left, d, 1f)
        assertEquals(17f, title.textStyle().fontSize.value, 0.01f)
        assertEquals(FontWeight.SemiBold, title.textStyle().fontWeight)
        val b = button.box()
        assertDp("标题到按钮 12", 12f, b.top - title.textBox().bottom, d, 1f)
        assertDp("按钮左 = 卡左 + 16", 16f, b.left - card.left, d, 1f)
        assertDp("按钮右 = 卡右 − 16", 16f, card.right - b.right, d, 1f)
        assertDp("卡内下 16", 16f, card.bottom - b.bottom, d, 1f)
        assertTrue("主按钮（品牌填充）", px.countNear(b, host.palette.buttonPrimary, 2) > 200)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun p8BasicNoticeStripIsUnchangedAndOnTop() {
        host.launchPrivacy(session = "privacy_basic")
        val notice = host.pageText(AppMeTexts.text("privacy.basic_mode.notice"))
        val d = notice.density()
        assertEquals("提示 14", 14f, notice.textStyle().fontSize.value, 0.01f)
        assertEquals("主文字色", host.palette.primary, notice.textStyle().color)
        val px = host.pixels(notice)
        assertTrue("muted 底", sameColor(px.at(notice.textBox().left - 4 * d, notice.textBox().center.y), host.palette.muted, 4))
        val top = rule.scrollUnder(MeTags.PRIVACY_PAGE).box().top
        val stripTop = px.edgeUp(notice.textBox().left - 4 * d, notice.textBox().center.y, host.palette.muted)
        assertDp("提示条在页顶（内容上 8）", 8f, stripTop - top, d, 1f)
        assertTrue("提示条在清单卡之上", stripTop < host.node(rows.first().first).box().top)
    }

    // ---------------------------------------------------------------- P9 撤回确认框

    @Test fun p9WithdrawDialogTitleIsCenteredAndBodyStaysLeft() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val title = host.dialogText(AppMeTexts.text("privacy.withdraw.confirm.title"))
        val pane =
            rule
                .onAllNodes(PrivacyVisualTestHost.PANE, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first { it.config[SemanticsProperties.PaneTitle] == AppMeTexts.text("privacy.withdraw.confirm.title") }
        val content = pane.boundsInRoot
        val card = pane.cardFrame()
        val d = title.density()
        assertTrue("外框须包住内容盒（T1）：$card / $content", card.width > content.width && card.left < content.left)
        assertEquals("G-P09 标题居中：${title.glyphBox()} / $card", card.center.x / d, title.glyphBox().center.x / d, 2f)
        val body = host.dialogText(AppMeTexts.text("privacy.withdraw.confirm.body"))
        assertDp("先正：正文仍靠左 20", 20f, body.glyphBox().left - card.left, d, 1.5f)
        assertEquals("正文 14", 14f, body.textStyle().fontSize.value, 0.01f)
        assertEquals("正文主文字色", host.palette.primary, body.textStyle().color)
    }

    @Test fun p9ConfirmIsSecondaryOnTheLeftAndCancelIsPrimaryOnTheRight() {
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val cancel = rule.dialogButton(AppMeTexts.text("privacy.withdraw.confirm.cancel"))
        val confirm = rule.dialogButton(AppMeTexts.text("privacy.withdraw.confirm.ok"))
        val px = host.pixels(cancel)
        assertTrue("【取消】为主（品牌填充）", px.countNear(cancel.box(), host.palette.buttonPrimary, 2) > 200)
        assertEquals("【确认撤回】为次（无品牌填充）", 0, px.countNear(confirm.box(), host.palette.buttonPrimary, 2))
        assertTrue("次左主右（w390 一行放得下）", confirm.box().right <= cancel.box().left && abs(confirm.box().top - cancel.box().top) < 1f)
    }

    @Test fun p9WithdrawDialogUsesOverlayScrimLight() = assertScrim(dark = false)

    @Test fun p9WithdrawDialogUsesOverlayScrimDark() = assertScrim(dark = true)

    // ---------------------------------------------------------------- P10 / P11

    @Test fun p10PersonalizationFailureToastHasNoIconAndIsSpokenOnce() {
        host.api.consent = PrivacyVisualFixtures.failure()
        host.launchPrivacy()
        host.tap(MeTags.PRIVACY_SWITCH)
        val message = AppMeTexts.text("privacy_center.personalization_failed")
        host.settle("个性化失败提示") { host.count(message) == 1 }
        val toast = host.node(MeTags.TOAST)
        val px = host.pixels(toast)
        assertEquals("错误类不带对勾（G-S06）", 0, px.countNear(toast.box(), host.palette.success, 8))
        assertTrue("无图标盒：${toast.iconBoxes()}", toast.iconBoxes().isEmpty())
        assertEquals("播报一次：${host.spoken}", 1, host.spoken.count { it.contains(message) })
        assertFalse("不回滚开关（用户意愿优先）", host.local.personalizationOn.value)
    }

    @Test fun p11ForceUpdateShowsOnlyListsAndWithdrawAndBackReturns() {
        host.launchPrivacy(session = "privacy_force_update")
        rows.forEach { assertTrue(it.first, host.has(it.first)) }
        assertTrue(host.has(MeTags.PRIVACY_WITHDRAW))
        assertFalse("强更上下文无个性化", host.has(MeTags.PRIVACY_SWITCH))
        assertFalse("强更上下文无系统权限一节", host.has(MeTags.PRIVACY_PERMISSIONS))
        assertFalse("强更上下文无开启完整功能", host.has(MeTags.PRIVACY_ENABLE_FULL))
        val title = host.pageHeading(AppMeTexts.text("privacy_center.withdraw_title"))
        val card = cardAround(host.pixels(title), title.textBox())
        assertDp("撤回卡与清单卡同写法（卡内上 16）", 16f, title.textBox().top - card.top, title.density(), 1f)
        // v2（T4）：组件库 NavBar 是 IconButton { Icon(contentDescription = 返回) }——描述在子图标、点击在父按钮。取「可点击且其下有该描述」的唯一节点。
        val back = hasClickAction() and hasAnyDescendant(hasContentDescription(AppMeTexts.text("me.back")))
        host.tap(rule.onNode(back, useUnmergedTree = true))
        assertEquals("返回回强更页（onBack 一次）", 1, host.backs)
    }

    @Test fun pageSectionsFollowTheModelOneToOne() {
        mapOf(
            "privacy_member" to listOf(true, true, true, false),
            "privacy_guest" to listOf(true, true, true, false),
            "privacy_basic" to listOf(false, true, false, true),
            "privacy_force_update" to listOf(false, false, true, false),
        ).forEach { (session, expected) ->
            val model =
                com.couli.core.me.PrivacyCenterView.model(
                    PrivacyVisualFixtures.level(session),
                    com.couli.core.me.MePlatform.ANDROID,
                    emptyList(),
                    emptyMap(),
                    PrivacyVisualFixtures.forceUpdate(session),
                )
            assertEquals(
                "分节显隐只来自 PrivacyCenterModel：$session",
                expected,
                listOf(model.personalizationSwitch, model.permissionSection, model.withdrawButton, model.enableFullButton),
            )
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun assertScrim(dark: Boolean) {
        host.scrimSamples = true
        host.launchPrivacy(dark = dark)
        host.tap(MeTags.PRIVACY_WITHDRAW)
        val samples =
            listOf(PrivacyVisualTestHost.SCRIM_CANVAS, PrivacyVisualTestHost.SCRIM_WHITE).map {
                WithdrawVisualWindowProbe(rule).scrim(host.node(it).fetchSemanticsNode())
            }
        assertEquals("白卡采样必须落在白色上", Color.White.toArgb(), samples.last().before)
        samples.forEach { sample ->
            val expected =
                host.palette.scrim
                    .compositeOver(Color(sample.before))
                    .toArgb()
            val ok = listOf(24, 16, 8, 0).all { shift -> abs((sample.after ushr shift and 255) - (expected ushr shift and 255)) <= 2 }
            assertTrue(
                "G-P09 遮罩 = overlay.scrim（不留系统 dim）：after=${sample.after.toUInt().toString(
                    16,
                )} expected=${expected.toUInt().toString(16)} dim=${sample.dim}",
                ok,
            )
        }
    }

    /**
     * 包住 [inner] 的白卡外框（surface 像素向四边扫描，量外框不量内容盒）。取样列先试 [inner] 左缘外 2px（文字在卡内边距之内），再试左缘内
     * [ROW_SAMPLE_DP]（整行通栏的列表行）；都不在 surface 上时返回 [inner] 自身（底版无卡 → 距离 0，断言变红）。
     *
     * v2（T1 / T2）：通栏行的取样列原为左缘内 4px（2dp），落在卡片 16 圆角的弧内——2dp 处圆角深约 8dp，大于卡内上 4，
     * 取到的是画布色，于是退回「行盒 = 卡」。改到左缘内 14dp：圆角在此深约 0.1dp（< 1px），又在行间分隔线起点（左 16）之前，
     * 向上 / 向下扫都不会被圆角或分隔线截断。断言与容差不变。
     */
    private fun cardAround(
        px: Bitmap,
        inner: Rect,
    ): Rect {
        val surface = host.palette.surface
        val y = inner.top + 1f
        val rowColumn = inner.left + ROW_SAMPLE_DP * rule.density.density
        val x = listOf(inner.left - 2f, rowColumn).firstOrNull { sameColor(px.at(it, y), surface) } ?: return inner
        val top = px.edgeUp(x, y, surface)
        val bottom = px.edgeDown(x, inner.bottom - 1f, surface)
        val left = px.firstFromLeft(inner.center.y, surface)
        val right = px.firstFromRight(inner.center.y, surface)
        return Rect(left, top, right, bottom)
    }

    /** 卡片外框（T1）：该布局节点上各修饰符坐标里最大的盒。 */
    private fun SemanticsNode.cardFrame(): Rect =
        layoutInfo
            .getModifierInfo()
            .map { it.coordinates.boundsInRoot() }
            .maxBy { it.width * it.height }

    private companion object {
        /** 通栏行的取样列：左缘内 14dp（16 圆角在此深 < 1px，且在分隔线起点 16 之前）。 */
        const val ROW_SAMPLE_DP = 14f
    }
}
