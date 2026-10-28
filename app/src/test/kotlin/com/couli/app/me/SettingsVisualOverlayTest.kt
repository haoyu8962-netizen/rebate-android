package com.couli.app.me

import android.app.Application
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.WithdrawScrimSample
import com.couli.app.wallet.visual.WithdrawVisualWindowProbe
import com.couli.feature.me.MeTags
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * S14 协议选择半屏（两条列表行替换两个次按钮，G-S08 ②）、S15 确认框（标题居中 + overlay.scrim，G-S07）：版式 + 一次性编号的计数（关闭 / 取消零动作、
 * 确认恰一次）+ 弹窗接续页面字号（APP-11v-b r1）。遮罩按 APP-09v-c 的窗口合成量具，浅 / 深都量，白卡采样区分令牌与系统 dim。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsVisualOverlayTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    // ---------------------------------------------------------------- S14 协议半屏

    @Test fun s14AgreementSheetHasTwoListRowsWithChevronAndDivider() {
        host.launch()
        host.tapRow(SettingsRows.AGREEMENT)
        assertTrue(host.has(MeTags.AGREEMENT_SHEET))
        val user = host.node(MeTags.AGREEMENT_USER)
        val privacy = host.node(MeTags.AGREEMENT_PRIVACY)
        val d = user.density()
        val a = user.box()
        val b = privacy.box()
        listOf(MeTags.AGREEMENT_USER to "用户协议", MeTags.AGREEMENT_PRIVACY to "隐私政策").forEach { (tag, label) ->
            val row = host.node(tag)
            val box = row.box()
            assertDp("S14 列表行高 48（min 44 + 上下 8，Android 命中区）：$label", 48f, box.height, d, 0.5f)
            val text = rule.textUnder(tag, label)
            assertEquals("行标题 17", 17f, text.textStyle().fontSize.value, 0.01f)
            assertEquals("行标题主文字色", host.palette.primary, text.textStyle().color)
            val glyph = text.glyphBox()
            assertTrue("列表行文字靠左（不是居中按钮）：$glyph / $box", glyph.left - box.left <= 16.5f * d && box.center.x - glyph.center.x > 40 * d)
            val icons = row.iconBoxes()
            assertEquals("行尾 chevron 20：$icons", 20f, icons.single().width / d, 0.5f)
            assertEquals("读屏为按钮", Role.Button, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        assertTrue("两行相邻、只隔一条分隔线", b.top - a.bottom <= 1.5f * d && b.top >= a.bottom)
        // 分隔线在半屏窗口里：用行的未合并文字节点所在窗口取像素不可行，改查两行之间没有按钮间距（现状 spacedBy 12）
        assertTrue("不是两个次按钮（现状两按钮间 12）", b.top - a.bottom < 12 * d)
    }

    @Test fun s14EachRowOpensAgreementOnceWithItsTypeAndCloseOpensNothing() {
        host.launch()
        host.tapRow(SettingsRows.AGREEMENT)
        host.tap(MeTags.AGREEMENT_USER)
        assertEquals(listOf("Agreement" to type("agreement")), host.env.opened)
        assertFalse("选中一行即作废半屏", host.has(MeTags.AGREEMENT_SHEET))
        host.tapRow(SettingsRows.AGREEMENT)
        host.tap(MeTags.AGREEMENT_PRIVACY)
        assertEquals(listOf("Agreement" to type("agreement"), "Agreement" to type("privacy")), host.env.opened)
        host.tapRow(SettingsRows.AGREEMENT)
        val close = rule.onNode(hasContentDescription("关闭") and hasClickAction(), useUnmergedTree = true)
        host.tap(close)
        assertFalse(host.has(MeTags.AGREEMENT_SHEET))
        assertEquals("关闭半屏零导航", 2, host.env.opened.size)
    }

    @Test fun s14SheetRowsAndCloseHave48TargetsThatDoNotOverlap() {
        host.launch()
        host.tapRow(SettingsRows.AGREEMENT)
        val user = host.node(MeTags.AGREEMENT_USER).touch()
        val privacy = host.node(MeTags.AGREEMENT_PRIVACY).touch()
        val close = rule.onNode(hasContentDescription("关闭") and hasClickAction(), useUnmergedTree = true).touch()
        val d = host.node(MeTags.AGREEMENT_USER).density()
        listOf(user, privacy, close).forEach {
            assertAtLeastDp("命中宽", 48f, it.width, d)
            assertAtLeastDp("命中高", 48f, it.height, d)
        }
        assertSeparate("用户协议 / 隐私政策", user, privacy)
        assertSeparate("关闭 / 用户协议", close, user)
    }

    // ---------------------------------------------------------------- S15 确认框

    @Test fun s15ClearCacheDialogTitleIsCentered() = assertCentered(clear = true)

    @Test fun s15LogoutDialogTitleIsCentered() = assertCentered(clear = false)

    @Test fun s15ClearCacheDialogUsesOverlayScrimLight() = assertScrim(clear = true, dark = false)

    @Test fun s15LogoutDialogUsesOverlayScrimLight() = assertScrim(clear = false, dark = false)

    @Test fun s15LogoutDialogUsesOverlayScrimDark() = assertScrim(clear = false, dark = true)

    @Test fun s15ClearCacheDialogUsesOverlayScrimDark() = assertScrim(clear = true, dark = true)

    @Test fun s15ClearConfirmClearsImageAndWebCacheOnceThenToastAndZeroSize() {
        host.launch()
        host.tapRow(SettingsRows.CLEAR_CACHE)
        host.tap(MeTags.CLEAR_DIALOG)
        host.settle("已清除") { host.count("已清除") == 1 }
        assertEquals("WebView 内存与磁盘缓存清一次", 1, host.env.webClears)
        assertEquals("图片缓存目录清一次", 1, host.env.diskClears)
        assertTrue("清除后缓存行显示 0 KB", host.count("0 KB") >= 1)
        assertTrue("零导航", host.env.opened.isEmpty())
    }

    @Test fun s15ClearCancelDoesNothingAndOldConfirmIsVoid() {
        host.launch()
        host.tapRow(SettingsRows.CLEAR_CACHE)
        val stale = confirmAction(MeTags.CLEAR_DIALOG)
        host.tap(rule.dialogButton("取消"))
        assertFalse(host.has(MeTags.CLEAR_DIALOG))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("取消后旧确认回调零动作（R1-02）", 0, host.env.webClears + host.env.diskClears)
    }

    @Test fun s15LogoutConfirmCallsLogoutOnceForTheRenderIdentity() {
        host.launch()
        host.tap(MeTags.SETTINGS_LOGOUT)
        host.tap(MeTags.LOGOUT_DIALOG)
        assertEquals(listOf(SettingsVisualEnv.member()), host.env.logouts)
        assertTrue(host.env.opened.isEmpty())
    }

    @Test fun s15LogoutOutsideTapKeepsDialogAndBackIsCancel() {
        host.launch()
        host.tap(MeTags.SETTINGS_LOGOUT)
        val stale = confirmAction(MeTags.LOGOUT_DIALOG)
        rule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0, 16, action, 4f, 4f, 0)
                try {
                    dialog.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        host.idle()
        assertTrue("点遮罩不关闭退出确认", host.has(MeTags.LOGOUT_DIALOG))
        assertEquals("点遮罩不执行", 0, host.env.logouts.size)
        // 系统返回键：Compose Dialog 经 OnBackPressedDispatcher 交给 onDismissRequest（= 取消）
        @Suppress("DEPRECATION")
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        host.idle()
        assertFalse("系统返回 = 取消", host.has(MeTags.LOGOUT_DIALOG))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("取消后旧确认零动作", 0, host.env.logouts.size)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun s15DialogAndSheetCarryThePageFontScale() {
        host.launch(fontScale = 2f)
        host.tap(MeTags.SETTINGS_LOGOUT)
        val title = dialogText("确定退出登录？")
        assertEquals("退出确认接续 200%", 2f, title.fontScale(), 0.01f)
        title.assertTextFully()
        host.tap(rule.dialogButton("取消"))
        host.tapRow(SettingsRows.AGREEMENT)
        val row = rule.textUnder(MeTags.AGREEMENT_USER, "用户协议")
        assertEquals("协议半屏接续 200%", 2f, row.fontScale(), 0.01f)
        row.assertTextFully()
    }

    private fun assertCentered(clear: Boolean) {
        host.launch()
        val title = open(clear)
        val pane =
            rule
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        val node = pane.first { it.config[SemanticsProperties.PaneTitle] == titleText(clear) }
        // v2（T1）：组件库把 paneTitle 挂在 .couliCard().padding(20) 之后，语义盒是内容盒；卡片外框取同一布局节点上最外层修饰符的盒
        val content = node.boundsInRoot
        val box = node.cardFrame()
        val d = title.density()
        assertTrue("外框须真包住内容盒且更宽（取到的不是内容盒）：card=$box content=$content", box.width > content.width && box.left < content.left)
        val glyph = title.glyphBox()
        assertEquals("G-S07 标题居中（画板左右各留 28）：glyph=$glyph card=$box", box.center.x / d, glyph.center.x / d, 2f)
        if (clear) {
            // 先正：正文仍靠左（证明量具能区分对齐）
            val body = dialogText("只清除图片和网页缓存，不会退出登录，也不会清除你的设置")
            assertDp("正文左缘 = 卡片左 + 20", 20f, body.glyphBox().left - box.left, d, 1.5f)
        }
    }

    private fun assertScrim(
        clear: Boolean,
        dark: Boolean,
    ) {
        host.scrimSamples = true
        host.launch(dark = dark)
        open(clear)
        val samples =
            listOf(SettingsVisualTestHost.SCRIM_CANVAS, SettingsVisualTestHost.SCRIM_WHITE).map {
                WithdrawVisualWindowProbe(rule).scrim(host.node(it).fetchSemanticsNode())
            }
        assertEquals("白卡采样必须落在白色上", Color.White.toArgb(), samples.last().before)
        samples.forEach { assertScrimToken(it, host.palette.scrim) }
    }

    private fun assertScrimToken(
        sample: WithdrawScrimSample,
        overlay: Color,
    ) {
        val expected = overlay.compositeOver(Color(sample.before)).toArgb()
        val ok = listOf(24, 16, 8, 0).all { shift -> abs((sample.after ushr shift and 255) - (expected ushr shift and 255)) <= 2 }
        assertTrue(
            "G-S07 遮罩 = overlay.scrim（不得留系统 dim）：before=${sample.before.toUInt().toString(16)} " +
                "after=${sample.after.toUInt().toString(16)} expected=${expected.toUInt().toString(16)} dim=${sample.dim}",
            ok,
        )
    }

    private fun open(clear: Boolean): SemanticsNodeInteraction {
        if (clear) host.tapRow(SettingsRows.CLEAR_CACHE) else host.tap(MeTags.SETTINGS_LOGOUT)
        assertTrue(host.has(if (clear) MeTags.CLEAR_DIALOG else MeTags.LOGOUT_DIALOG))
        return dialogText(titleText(clear))
    }

    private fun titleText(clear: Boolean) = if (clear) "清除缓存" else "确定退出登录？"

    /** 弹窗窗口里的文字（祖先带 paneTitle，区别于页面上同名的「清除缓存」行）。 */
    private fun dialogText(text: String): SemanticsNodeInteraction =
        rule.onNode(textIs(text) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)), useUnmergedTree = true)

    /**
     * 卡片外框（v2，T1）：该布局节点上各修饰符坐标里最大的盒（widthIn / couliCard 那一层），即内边距 20 之外的白卡；
     * 语义盒 boundsInRoot 取的是 padding 之后的内容盒。
     */
    private fun SemanticsNode.cardFrame(): Rect =
        layoutInfo
            .getModifierInfo()
            .map { it.coordinates.boundsInRoot() }
            .maxBy { it.width * it.height }

    private fun confirmAction(tag: String): () -> Unit {
        val action =
            host
                .node(tag)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        return { action() }
    }

    private fun type(value: String) = JsonObject(mapOf("type" to JsonPrimitive(value)))
}
