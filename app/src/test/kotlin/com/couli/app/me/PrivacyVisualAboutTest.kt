package com.couli.app.me

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.Diagnostics
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
 * 关于（brief-b-android P12–P19，画板 About-v2 / AboutBeforeFiling / AboutDark）：头部（图标 80、名字 17/600、版本 14 等宽数字）、AI 公示分组名在卡外、
 * 复制按钮带图标 + 居中说明、轻提示成功带对勾 / 失败不带；复制内容逐字等于白名单输出、统一写入口一次。App 图标没有新标记可依，按品牌色像素外接框量。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class PrivacyVisualAboutTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PrivacyVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private val name get() = host.pageHeading(AppMeTexts.text("about.app_name"))
    private val version get() =
        host.pageText(
            AppMeTexts.format(
                "about.version",
                mapOf(
                    "version" to PrivacyVisualFixtures.version,
                    "build" to PrivacyVisualFixtures.build,
                ),
            ),
        )
    private val label get() = host.pageHeading(AppMeTexts.text("about.filing_title"))
    private val filing get() = host.pageText(PrivacyVisualFixtures.filed)

    // ---------------------------------------------------------------- P12–P15 头部

    @Test fun p13AppIconIs80SquareBrandBlockAboveTheNameWithoutText() {
        host.launchAbout()
        assertEquals("「凑狸」只作名字出现一次（图标里不再写字）", 1, host.count(AppMeTexts.text("about.app_name")))
        val d = name.density()
        val px = host.pixels(name)
        val icon = iconBox(px)
        assertDp("P13 图标宽 80", 80f, icon.width, d, 1f)
        assertDp("P13 图标高 80", 80f, icon.height, d, 1f)
        assertEquals("图标水平居中", px.width / 2f / d, icon.center.x / d, 1f)
        assertDp("图标到名字 8", 8f, name.textBox().top - icon.bottom, d, 1f)
        assertFalse("圆角（左上角不是品牌色）", sameColor(px.at(icon.left + 1f, icon.top + 1f), host.palette.brand, 6))
        assertTrue(
            "图标里有反白主图形（非纯色块，先正）",
            px.countNear(icon, host.palette.surface, 12) > 50 || px.countNear(icon, androidx.compose.ui.graphics.Color.White, 12) > 50,
        )
    }

    @Test fun p12P15HeaderStartsTwentyFourBelowContentTopAndBlocksAreSixteenApart() {
        host.launchAbout()
        val d = name.density()
        val px = host.pixels(name)
        val icon = iconBox(px)
        val top = rule.scrollUnder(MeTags.ABOUT_PAGE).box().top
        assertDp("P12 内容上 8 + P15 头部上 24", 32f, icon.top - top, d, 1f)
        assertDp("名字到版本 8", 8f, version.textBox().top - name.textBox().bottom, d, 1f)
        assertDp("P15 头部下 8 + P12 块间 16", 24f, label.textBox().top - version.textBox().bottom, d, 1.5f)
    }

    @Test fun p14NameIs17SemiboldHeadingAndVersionIs14SecondaryTabularDigits() {
        host.launchAbout()
        assertEquals(17f, name.textStyle().fontSize.value, 0.01f)
        assertEquals(FontWeight.SemiBold, name.textStyle().fontWeight)
        assertEquals(host.palette.primary, name.textStyle().color)
        assertEquals(14f, version.textStyle().fontSize.value, 0.01f)
        assertEquals(host.palette.secondary, version.textStyle().color)
        assertTrue(
            "版本等宽数字：${version.textStyle().fontFeatureSettings}",
            version
                .textStyle()
                .fontFeatureSettings
                .orEmpty()
                .contains("tnum"),
        )
        assertEquals("版本居中", 390f / 2, version.glyphBox().center.x / version.density(), 1f)
    }

    // ---------------------------------------------------------------- P16 AI 公示

    @Test fun p16FilingLabelIsOutsideTheCardAndBodyIsPrimary14WithPadding12x16() {
        host.launchAbout()
        val d = label.density()
        assertEquals("分组名 12", 12f, label.textStyle().fontSize.value, 0.01f)
        assertEquals("分组名次文字色", host.palette.secondary, label.textStyle().color)
        assertDp("分组名左缩进 4（16 + 4）", 20f, label.textBox().left, d, 0.75f)
        assertTrue(
            "分组名不在 ABOUT_FILING 卡之下",
            rule
                .onAllNodes(
                    textIs(AppMeTexts.text("about.filing_title")) and
                        androidx.compose.ui.test
                            .hasAnyAncestor(hasTestTag(MeTags.ABOUT_FILING)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isEmpty(),
        )
        assertEquals("正文 14", 14f, filing.textStyle().fontSize.value, 0.01f)
        assertEquals("正文主文字色（原样显示）", host.palette.primary, filing.textStyle().color)
        val px = host.pixels(filing)
        val card = card(px, filing.textBox())
        assertDp("卡内上 12", 12f, filing.textBox().top - card.top, d, 1f)
        assertDp("卡内左 16", 16f, filing.textBox().left - card.left, d, 1f)
        assertDp("卡内下 12", 12f, card.bottom - filing.textBox().bottom, d, 1f)
        assertDp("名到卡 8", 8f, card.top - label.textBox().bottom, d, 1f)
        assertDp("卡左 16", 16f, card.left, d, 1f)
        assertFalse("分组名在卡外（其背后不是卡面）", sameColor(px.at(label.textBox().left - 2f, label.textBox().center.y), host.palette.surface))
        assertTrue("正文原样、不拼接", host.count(PrivacyVisualFixtures.filed) == 1)
    }

    @Test fun p16UnfiledHidesTheWholeSection() {
        host.launchAbout(filed = false)
        assertFalse("filing_text 空串：整节不显示", host.has(MeTags.ABOUT_FILING))
        assertEquals(0, host.count(AppMeTexts.text("about.filing_title")))
        assertTrue("先正：复制按钮仍在", host.has(MeTags.ABOUT_COPY))
    }

    // ---------------------------------------------------------------- P17 复制按钮与说明

    @Test fun p17CopyButtonHasCopyIcon20AndCenteredNoteEightBelow() {
        host.launchAbout()
        val button = host.node(MeTags.ABOUT_COPY)
        val d = button.density()
        val b = button.box()
        assertDp("按钮通栏左 16", 16f, b.left, d, 1f)
        assertDp("按钮通栏右 16", 16f, 390f * d - b.right, d, 1f)
        assertTrue("按钮 ≥ 48", b.height / d >= 47.5f)
        val icons = button.iconBoxes()
        assertEquals("P17 复制图标 20：$icons", 20f, icons.single().width / d, 0.5f)
        val text = rule.textUnder(MeTags.ABOUT_COPY, AppMeTexts.text("about.copy_diagnostics"))
        // v2（T3）：组件库 CouliButton 的 Text 带 TextAlign.Center，读到的 TextLayoutResult 按约束宽排版，getLineLeft 相对排版宽偏右，
        // glyphBox 左缘多出约 94dp；改用 textBox().left（文字节点原点）量间距。另以「文字节点实测宽 ≈ 字形宽」确认节点紧贴文字、
        // 没有被撑宽（撑宽时居中排版会把字形推离图标，这正是原断言要拦的）。
        assertDp("文字节点紧贴字形（宽差 ≤ 1.5）：${text.box()} / ${text.glyphBox()}", text.glyphBox().width / d, text.box().width, d, 1.5f)
        assertDp("图标与文字间 8", 8f, text.textBox().left - icons.single().right, d, 1.5f)
        val note = host.pageText(AppMeTexts.text("about.copy_desc"))
        assertEquals("说明 12", 12f, note.textStyle().fontSize.value, 0.01f)
        assertEquals(host.palette.secondary, note.textStyle().color)
        assertEquals("说明居中", TextAlign.Center, note.textStyle().textAlign)
        assertDp("按钮到说明 8", 8f, note.textBox().top - b.bottom, d, 1f)
        assertDp("公示卡到按钮 16（块间）", 16f, b.top - card(host.pixels(filing), filing.textBox()).bottom, d, 1.5f)
    }

    // ---------------------------------------------------------------- P18 轻提示与复制计数

    @Test fun p18CopySuccessWritesTheWhitelistOnceAndToastHasCheck() {
        host.launchAbout()
        host.tap(MeTags.ABOUT_COPY)
        assertEquals("统一写入口一次、内容逐字等于白名单输出", listOf(PrivacyVisualFixtures.diagnosticsText), host.env.copies)
        assertEquals(
            "与产品规则 Diagnostics.lines 一致",
            Diagnostics.lines(PrivacyVisualFixtures.diagnostics).joinToString("\n"),
            host.env.copies.single(),
        )
        PrivacyVisualFixtures.diagnosticsForbidden.forEach {
            assertFalse(
                "不含 $it",
                host.env.copies
                    .single()
                    .contains(it),
            )
        }
        val message = AppMeTexts.text("about.copy_diagnostics.done")
        host.settle("复制成功提示") { host.has(MeTags.TOAST) }
        val toast = host.node(MeTags.TOAST)
        val px = host.pixels(toast)
        assertTrue("G-P12 成功带对勾（绿像素）", px.countNear(toast.box(), host.palette.success, 2) >= 10)
        assertEquals("对勾 20", 20f, toast.iconBoxes().single().width / toast.density(), 0.5f)
        assertEquals("只播一次（主动 + liveRegion 合计）：${host.spoken}", 1, host.spoken.count { it.contains(message) })
        assertTrue("零请求、零导航", host.api.calls.isEmpty() && host.env.opened.isEmpty())
    }

    @Test fun p18CopyFailureToastHasNoIconAndIsSpokenOnce() {
        host.launchAbout(copyOk = false)
        host.tap(MeTags.ABOUT_COPY)
        assertEquals("失败也只写一次", 1, host.env.copies.size)
        val message = AppMeTexts.text("me.copy_failed")
        host.settle("复制失败提示") { host.count(message) == 1 }
        val toast = host.node(MeTags.TOAST)
        assertEquals("失败不带对勾", 0, host.pixels(toast).countNear(toast.box(), host.palette.success, 8))
        assertTrue(toast.iconBoxes().isEmpty())
        assertEquals("只播一次：${host.spoken}", 1, host.spoken.count { it.contains(message) })
    }

    @Test fun p19IcpRowIsNotShown() {
        host.launchAbout()
        assertEquals("G-P23 备案号未配置不显示", 0, rule.onAllNodes(textIs("App 备案号")).fetchSemanticsNodes().size)
    }

    @Test fun aboutIconIsNotSpoken() {
        host.launchAbout()
        val spoken =
            rule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher
                        .keyIsDefined(SemanticsProperties.ContentDescription),
                ).fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.ContentDescription] }
        assertTrue("图标装饰不读（只有返回有内容描述）：$spoken", spoken.all { it == AppMeTexts.text("me.back") })
    }

    // ---------------------------------------------------------------- 工具

    /** 名字之上的品牌色外接框（App 图标）。 */
    private fun iconBox(px: Bitmap): Rect {
        val top = rule.scrollUnder(MeTags.ABOUT_PAGE).box().top
        val area = Rect(0f, top, px.width.toFloat(), name.textBox().top)
        return requireNotNull(px.pvColorBox(area, host.palette.brand, 6)) { "名字上方没有品牌色图标" }
    }

    private fun card(
        px: Bitmap,
        inner: Rect,
    ): Rect {
        val surface = host.palette.surface
        val y = inner.top + 1f
        val x = inner.left - 2f
        if (!sameColor(px.at(x, y), surface)) return inner
        return Rect(
            px.firstFromLeft(inner.center.y, surface),
            px.edgeUp(x, y, surface),
            px.firstFromRight(inner.center.y, surface),
            px.edgeDown(
                x,
                inner.bottom - 1f,
                surface,
            ),
        )
    }
}
