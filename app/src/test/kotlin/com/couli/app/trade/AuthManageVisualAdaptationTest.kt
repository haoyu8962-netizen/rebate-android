@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.assertTextFully
import com.couli.app.me.at
import com.couli.app.me.box
import com.couli.app.me.firstFromLeft
import com.couli.app.me.firstFromRight
import com.couli.app.me.lines
import com.couli.app.me.sameColor
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.app.me.touch
import com.couli.app.trade.AuthManageVisualHost.Companion.AUTHORIZED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.BLOCKED_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.CONTACT_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.GRANT
import com.couli.app.trade.AuthManageVisualHost.Companion.REBIND_KEY
import com.couli.app.trade.AuthManageVisualHost.Companion.UNAUTHORIZED_KEY
import com.couli.core.designsystem.ErrorStateFocus
import com.couli.feature.trade.AuthManageTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-06w 适配与读屏（brief §4 验收 2 / 3 / 6 / 7 / 8、gaps G-A08 / G-A11）：读屏合并与装饰不读、命中区与语义盒 ≥48 且不重叠、
 * 列表失败焦点（推进时钟后断言）、200% × w390 / w320（不截断、不缩小；放不下时「状态 + 按钮」整组换到下一行右对齐，平台名不逐字折行）、
 * 840 限宽 600 居中、深色令牌。底版应红：G-A08、G-A11（w320 平台名逐字折行）、200% 轻提示左右 16、深色圆勾 / 主按钮。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AuthManageVisualAdaptationTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { AuthManageVisualHost(rule) }

    @After fun tearDown() = host.close()

    private val d get() = host.density()

    // ---------------------------------------------------------------- 读屏（G-A08）

    /*
     * v2 T2 / T3：按名字「包含」找会撞上底部说明（rebind_note 也含「淘宝」），停用说明 error.30153 句子本身也含「联系客服」字样。
     * 改为按节点归属（行块 ROW_PREFIX / CONTACT 标记之下）与整句相等定位；断言意图不变。
     */

    /** 合并树里匹配 [m] 的节点 id。 */
    private fun mergedIds(m: SemanticsMatcher): Set<Int> =
        rule
            .onAllNodes(m, useUnmergedTree = false)
            .fetchSemanticsNodes()
            .map { it.id }
            .toSet()

    /** 自身或祖先挂 [tag]。 */
    private fun within(tag: String): SemanticsMatcher = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))

    /** 读出平台名（文字或整组描述含名）。 */
    private fun naming(platform: String): SemanticsMatcher =
        textMatcher(host.name(platform), substring = true) or hasContentDescription(host.name(platform), substring = true)

    @Test fun g08NameAndStatusAreOneReadNodeAndButtonsStaySeparate() {
        host.launch("normal")
        listOf("taobao" to AUTHORIZED_KEY, "pdd" to UNAUTHORIZED_KEY).forEach { (p, key) ->
            val merged =
                rule
                    .onAllNodes(naming(p) and within(host.row(p)), useUnmergedTree = false)
                    .fetchSemanticsNodes()
            assertEquals("$p 名在行块的合并树里恰一项", 1, merged.size)
            val leading = host.readOrder().count { n -> host.spokenOf(n).firstOrNull()?.startsWith(host.name(p)) == true }
            assertEquals("$p 名在整页读屏里只作为一项的开头出现一次（底部说明句中含名不算）", 1, leading)
            val spoken = host.spokenOf(merged.single()).joinToString("")
            assertTrue("G-A08：$p 那一项同时读出状态（底版名、状态各一项，红）：$spoken", spoken.contains(host.text(key)))
            assertTrue("名在前、状态在后", spoken.indexOf(host.name(p)) < spoken.indexOf(host.text(key)))
            assertNull("这一项不可点", merged.single().config.getOrNull(SemanticsActions.OnClick))
        }
        val grant = rule.onNode(hasTestTag(host.grantTag("pdd")), useUnmergedTree = false).fetchSemanticsNode()
        assertEquals("【去授权】单独一项、只读自己的字", listOf(host.text("auth_manage.action.authorize")), host.spokenOf(grant))
    }

    @Test fun g08BlockedOrderIsRowThenNoticeThenContact() {
        host.launch("blocked")
        val nodes = host.readOrder()
        val order = nodes.map { host.spokenOf(it).joinToString("") }

        fun at(
            what: String,
            m: SemanticsMatcher,
        ): Int {
            val ids = mergedIds(m)
            val i = nodes.indexOfFirst { it.id in ids }
            assertTrue("读屏顺序里有$what：$order", i >= 0)
            return i
        }
        val row = at("淘宝行", naming("taobao") and within(host.row("taobao")))
        assertTrue("淘宝那一项含「未授权」：${order[row]}", order[row].contains(host.text(UNAUTHORIZED_KEY)))
        assertTrue("淘宝那一项名在前", order[row].indexOf(host.name("taobao")) < order[row].indexOf(host.text(UNAUTHORIZED_KEY)))
        val notice = at("停用说明（整句相等）", textMatcher(host.text(BLOCKED_KEY)))
        assertEquals("停用说明单独一项、只读说明本句", host.text(BLOCKED_KEY), order[notice])
        val contact = at("【联系客服】（CONTACT 标记）", within(AuthManageTags.CONTACT))
        assertEquals("【联系客服】单独一项、只读自己的字", host.text(CONTACT_KEY), order[contact])
        val pdd = at("拼多多行", naming("pdd") and within(host.row("pdd")))
        val note = at("底部说明（整句相等）", textMatcher(host.text(REBIND_KEY)))
        assertTrue("顺序：淘宝行 → 停用说明 → 【联系客服】 → 拼多多行 → 底部说明：$order", row < notice)
        assertTrue("停用说明在【联系客服】之前：$order", notice < contact)
        assertTrue("【联系客服】在拼多多行之前：$order", contact < pdd)
        assertTrue("拼多多行在底部说明之前：$order", pdd < note)
    }

    @Test fun decorationsAreNotRead() {
        host.launch("blocked")
        // G-A08 若以整组描述实现（「淘宝，已授权」），那一项的描述含平台名，不算装饰
        val names = listOf(host.name("taobao"), host.name("pdd"))
        val described =
            host
                .readOrder()
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                .filterNot { desc -> names.any { desc.contains(it) } }
        assertEquals("只有返回带描述：平台图标、圆勾、警告三角、分隔线都不读", listOf(host.text(AuthManageVisualHost.BACK_KEY)), described)
        host.pop()
        host.launch("normal")
        val again =
            host
                .readOrder()
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                .filterNot { desc -> names.any { desc.contains(it) } }
        assertEquals(listOf(host.text(AuthManageVisualHost.BACK_KEY)), again)
    }

    @Test fun listErrorMovesFocusToRetryAndAnnouncesOnce() {
        host.launchListError(AuthManageVisualFixtures.failure("c50001"))
        // v2 T4：ErrorAnnouncement 先播报，按字数估读完再移焦（ErrorStateFocus.focusDelayMillis；50001 + trace6 约 3.4 秒，
        // v1 只推 1.5 秒不够）。推进过上限 MAX_MILLIS 再断言；焦点可落在挂 RETRY 标记的节点或其内层可聚焦节点。
        host.advance(ErrorStateFocus.MAX_MILLIS + 500)
        val focused =
            rule
                .onAllNodes(isFocused() and within(AuthManageTags.RETRY), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertTrue("焦点移到【重试】（现状 couliFocusTarget）：${focused.map { it.config }}", focused.isNotEmpty())
        val message = host.spoken.filter { it.contains(AuthManageVisualFixtures.trace6) }
        assertEquals("错误句播报恰一次、不与轻提示双播：${host.spoken}", 1, message.size)
    }

    // ---------------------------------------------------------------- 命中区

    @Test fun hitTargetsAreFortyEightAndDoNotOverlap() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30153")
        host.launch("both_unbound")
        val tbOld = host.node(host.grantTag("taobao"))
        val pdd = host.node(host.grantTag("pdd"))
        assertSeparate("两行【去授权】", tbOld.touch(), pdd.touch())
        host.tap(host.grantTag("taobao"))
        host.settle("淘宝停用") { host.has(AuthManageTags.CONTACT) }
        val contact = host.node(AuthManageTags.CONTACT)
        listOf(host.back(), contact, host.node(host.grantTag("pdd"))).forEach { n ->
            assertTrue("命中区 ≥48：${n.touch()}", n.touch().width / d >= 47.5f && n.touch().height / d >= 47.5f)
            assertTrue("语义盒 ≥48：${n.box()}", n.box().width / d >= 47.5f && n.box().height / d >= 47.5f)
        }
        assertSeparate("停用块按钮与下一行【去授权】", contact.touch(), host.node(host.grantTag("pdd")).touch())
    }

    // ---------------------------------------------------------------- 200%（G-A11）

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun font200W390NothingTruncatedAndGroupRule() {
        host.launch("normal", fontScale = 2f)
        assertAllTextFully()
        listOf("taobao", "pdd").forEach(::assertRowRule)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320PddWrapsTheStatusAndButtonGroupBelowTheName() {
        host.launch("normal", fontScale = 2f)
        assertAllTextFully()
        val name = host.inRow("pdd", host.name("pdd"))
        assertEquals("G-A11：平台名不逐字折行（底版 weight 1 被挤成竖排，红）", 1, name.lines())
        val status = host.inRow("pdd", host.text(UNAUTHORIZED_KEY))
        val button = host.node(host.grantTag("pdd")).box()
        assertTrue("放不下：「未授权 + 去授权」整组换到名下", status.textBox().top >= name.textBox().bottom - d)
        assertTrue("按钮也在名下", button.top >= name.textBox().bottom - d)
        assertNear("整组同一行（竖向中线）", status.textBox().center.y / d, button.center.y, d, 3f)
        val card = host.cardOf(name)
        assertNear("整组右对齐（按钮贴卡内右 16）", 16f, card.right - button.right, d, 1.5f)
        assertTrue("状态在按钮左侧、间距 ≥ 12", button.left - status.textBox().right >= 11f * d)
        assertRowRule("taobao")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320BlockedNoticeAndContactStayWhole() {
        host.launch("blocked", fontScale = 2f)
        assertAllTextFully()
        val contact = host.node(AuthManageTags.CONTACT)
        contact.assertExistsOnScreenWidth()
        val message = host.inRow("taobao", host.text(BLOCKED_KEY))
        assertTrue("【联系客服】在停用说明之下", contact.box().top >= message.textBox().bottom)
        assertEquals(
            "【联系客服】字完整",
            1,
            rule
                .onAllNodes(
                    textMatcher(host.text(CONTACT_KEY)) and hasAnyAncestor(hasTestTag(AuthManageTags.CONTACT)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320ToastKeepsSixteenOnBothSides() {
        host.api.sticky[GRANT] = AuthManageVisualFixtures.failure("c30101")
        host.launch("taobao_unbound", fontScale = 2f)
        val message = host.text(AuthManageVisualFixtures.wording("c30101"))
        host.toastAfter(message) { host.tap(host.grantTag("taobao")) }
        val text = host.textNode(message)
        text.assertTextFully()
        val px = host.pixels()
        val y = text.textBox().top - 6 * d
        val left = px.firstFromLeft(y, host.palette.surface)
        val right = px.firstFromRight(y, host.palette.surface)
        assertTrue("先正：轻提示白底量到", right - left > 100 * d)
        assertTrue("A12：长句不贴边，左右各留 16（底版无左右边距，红）：$left / $right", left / d >= 15f && (host.rootWidth() - right) / d >= 15f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320ListErrorStaysReadable() {
        host.launchListError(AuthManageVisualFixtures.failure("c50001"), fontScale = 2f)
        assertAllTextFully()
        assertTrue(host.node(AuthManageTags.RETRY).touch().height / d >= 47.5f)
    }

    // ---------------------------------------------------------------- 840 / 深色

    @Test
    @Config(qualifiers = "w840dp-h844dp-xhdpi")
    fun w840LimitsTheContentTo600Centred() {
        host.launch("blocked")
        val card = host.cardOf(host.inRow("taobao", host.name("taobao")))
        assertNear("卡宽 600 − 32", 568f, card.width, d, 1.5f)
        assertNear("居中", (host.rootWidth() / 2) / d, card.center.x, d, 1.5f)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun darkUsesTheDarkTokens() {
        host.launch("normal", dark = true)
        val px = host.pixels()
        val card = host.cardOf(host.inRow("taobao", host.name("taobao")), px)
        assertTrue("页底 canvas（深 #171513）", sameColor(px.at(4 * d, card.center.y), host.palette.canvas, 3))
        assertTrue("卡片 surface（深 #24211E）", sameColor(px.at(card.left + 8 * d, card.center.y), host.palette.surface, 3))
        val status = host.inRow("taobao", host.text(AUTHORIZED_KEY))
        assertTrue("「已授权」主文字（深 #F2EEE6）", sameColor(status.textStyle().color.toArgb(), host.palette.primary))
        val checks = host.node(host.row("taobao")).squareLeaves(14f, 18f).filter { it.right <= status.textBox().left + d }
        assertEquals("深色仍有 16 圆勾", 1, checks.size)
        assertTrue("圆勾次文字色（深 #B5ACA2）", px.inkOf(checks.single(), host.palette.secondary) > 0)
        val grant = host.node(host.grantTag("pdd")).box()
        assertTrue("主按钮 #C44820（深同）", sameColor(px.at(grant.left + 6 * d, grant.top + 4 * d), host.palette.buttonPrimary, 4))
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    fun darkBlockedNoticeUsesTheDarkErrorTokens() {
        host.launch("blocked", dark = true)
        val px = host.pixels()
        val message = host.inRow("taobao", host.text(BLOCKED_KEY))
        assertTrue("停用说明字色（深 #F28DA4）", sameColor(message.textStyle().color.toArgb(), host.palette.errorText))
        assertTrue("停用块底（深 #3B1A22，推导）", px.blockAround(message, host.palette.errorBackground, d) != null)
    }

    // ---------------------------------------------------------------- 量具

    private fun assertAllTextFully() {
        rule
            .onAllNodes(
                hasAnyAncestor(hasTestTag(AuthManageTags.PAGE)) and
                    androidx.compose.ui.test.SemanticsMatcher
                        .keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .forEach { n ->
                val node =
                    rule.onNode(
                        androidx.compose.ui.test
                            .SemanticsMatcher("节点 ${n.id}") { it.id == n.id },
                        useUnmergedTree = true,
                    )
                if (n.config.contains(SemanticsActions.GetTextLayoutResult)) node.assertTextFully()
            }
    }

    /** G-A11：同一行放得下就并排（中线对齐、按钮贴右）；放不下整组换到名下右对齐。名始终一行。 */
    private fun assertRowRule(p: String) {
        val name = host.inRow(p, host.name(p))
        assertEquals("$p 平台名一行", 1, name.lines())
        val trailing =
            if (host.has(host.grantTag(p))) {
                host.node(host.grantTag(p)).box()
            } else {
                host.inRow(p, host.text(AUTHORIZED_KEY)).textBox()
            }
        val card = host.cardOf(name)
        assertNear("$p 尾组贴卡内右 16", 16f, card.right - trailing.right, d, 1.5f)
        val wrapped = trailing.top >= name.textBox().bottom - d
        if (!wrapped) assertNear("$p 并排时竖向居中", name.textBox().center.y / d, trailing.center.y, d, 3f)
        assertTrue("$p 尾组不压名", wrapped || trailing.left >= name.textBox().right)
        assertEquals("$p 名的字号随 200% 放大（不缩小）", 17f, name.textStyle().fontSize.value, 0.01f)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsOnScreenWidth() {
        val b = box()
        assertTrue("整块在屏宽内：$b", b.left >= 0f && b.right <= host.rootWidth() + 1f && b.width / d >= 47.5f)
    }

    private fun assertSeparate(
        message: String,
        a: androidx.compose.ui.geometry.Rect,
        b: androidx.compose.ui.geometry.Rect,
    ) {
        val i = a.intersect(b)
        assertTrue("$message：命中区重叠 $a / $b", i.width <= 0f || i.height <= 0f)
    }
}
