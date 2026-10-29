@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.assertTextFully
import com.couli.app.me.box
import com.couli.app.me.density
import com.couli.app.me.fontScale
import com.couli.app.me.touch
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-b 读屏、大字号与大屏（brief §4 第 2、3、6、7 条；adaptation-a11y-spec §4 / §9 / §10）：读屏顺序与装饰不读、倒计时不逐秒读、
 * 字段错误焦点 + 一次播报（不双播）、确认框焦点进标题；200% × w390 / w320 不截断不缩小（裁切判据同时比宽度与位置）、200% + 字段错误时错误句每行 ≥4 字
 * （APP-09v-d r1 S2-2）、格下一行命中区不压格子；≥600 限宽居中（换号 600，绑号现状 480）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountVisualPhoneAdaptationTest : AccountVisualPhoneHarness() {
    // ---------------------------------------------------------------- 读屏

    @Test fun readingOrderOnStep1FollowsTheBoard() {
        enterStep1(sent = true)
        val merged = mergedNodes()

        fun at(
            predicate: (SemanticsNode) -> Boolean,
            what: String,
        ): Int {
            val i = merged.indexOfFirst(predicate)
            assertTrue("读屏树里找不到 $what", i >= 0)
            return i
        }
        val steps = at({ it.tag() == AccountTags.PHONE_STEPS }, "步骤条")
        val intro = at({ it.texts().contains(t("phone_change.intro")) }, "说明")
        val code = at({ it.tag() == AccountTags.PHONE_STEP1_CODE }, "验证码")
        val send = at({ it.tag() == AccountTags.PHONE_STEP1_SEND || it.hasAncestorTag(AccountTags.PHONE_STEP1_SEND) }, "倒计时")
        val old = at({ it.tag() == AccountTags.PHONE_CANT_USE_OLD || it.hasAncestorTag(AccountTags.PHONE_CANT_USE_OLD) }, "原手机号不能用了")
        val next = at({ it.tag() == AccountTags.PHONE_STEP1_NEXT }, "下一步")
        assertTrue("步骤条 → 说明 → 验证码 → 倒计时 → 原手机号不能用了 → 下一步：$steps $intro $code $send $old $next", steps < intro && intro < code)
        assertTrue(code < send && send < old && old < next)
    }

    @Test fun decorationsAreNotSpoken() {
        enterStep1(sent = true)
        val described =
            composeRule
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and
                        hasAnyAncestor(hasTestTag(AccountTags.PHONE_PAGE)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.ContentDescription] }
                .filter { it.isNotEmpty() }
        val allowed =
            setOf(
                t("phone_change.step_spoken", mapOf("n" to "1")),
                codeSpoken(0),
                com.couli.app.me.AppMeTexts
                    .text("me.back"),
            )
        assertTrue("图标块、步骤圆、提示条图标、chevron 不读：$described", described.all { it in allowed })
    }

    @Test fun theCountdownIsNotALiveRegion() {
        enterStep1(sent = true)
        val text = textIn(AccountTags.PHONE_STEP1_SEND).fetchSemanticsNode()
        var n: SemanticsNode? = text
        while (n != null) {
            assertNull("倒计时不逐秒读（不挂 liveRegion，§10）", n.config.getOrNull(SemanticsProperties.LiveRegion))
            n = n.parent
        }
    }

    @Test fun fieldErrorTakesFocusAndIsAnnouncedOnce() {
        enterStep1Error()
        composeRule.mainClock.advanceTimeBy(FOCUS_DELAY_MS)
        settle()
        val message = textOf(AccountTags.PHONE_FIELD_ERROR)
        val focused = node(AccountTags.PHONE_FIELD_ERROR).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)
        assertEquals("焦点移到错误句（原样）", true, focused)
        val times = synchronized(announced) { announced.count { it.contains(message) } }
        assertEquals("播报一次、不双播（不另加轻提示 / liveRegion）", 1, times)
    }

    @Test fun riskDialogMovesFocusToTheTitle() {
        enterRisk44001()
        composeRule.mainClock.advanceTimeBy(FOCUS_DELAY_MS)
        settle()
        val focused = composeRule.onAllNodes(isFocused() and hasAnyAncestor(pane), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(
            "确认框打开焦点进标题：${focused.map { it.config }}",
            focused.any { n ->
                n.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { it.text == t("error.44001") }
            },
        )
    }

    // ---------------------------------------------------------------- 200%

    @Test fun font200W390Step1ErrorFitsAndTheErrorLinesAreNotSqueezed() {
        fontScale = 2f
        enterStep1Error()
        assertEquals("先正：200% 真的放大", 2f, node(AccountTags.PHONE_PAGE).fontScale(), 0.01f)
        val error = node(AccountTags.PHONE_FIELD_ERROR)
        fullyVisible(error)
        val layout = error.layoutResult()
        (0 until layout.lineCount).forEach { line ->
            val chars = layout.getLineEnd(line, visibleEnd = true) - layout.getLineStart(line)
            assertTrue("200% + 字段错误：第 ${line + 1} 行 ≥4 字（倒计时不挤窄错误句）：$chars", chars >= 4 || line == layout.lineCount - 1)
        }
        fullyVisible(textIn(AccountTags.PHONE_STEP1_SEND))
        fullyVisible(textUnder(AccountTags.PHONE_CANT_USE_OLD, t("phone_change.cant_use_old")))
        fullyVisible(textNode(t("phone_change.intro")))
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320CountdownWrapsRightAlignedWithoutCoveringTheCells() {
        fontScale = 2f
        enterStep1(sent = true)
        val text = textIn(AccountTags.PHONE_STEP1_SEND)
        fullyVisible(text)
        val cells = boxOf(AccountTags.PHONE_STEP1_CODE)
        assertTrue("不超出格子右缘", text.box().right <= cells.right + 1f || text.box().left >= cells.left)
        assertSeparate("命中区不压格子", node(AccountTags.PHONE_STEP1_SEND).touch(), cells)
        assertTrue("格子不缩小（≥56）", cells.height / densityOf(AccountTags.PHONE_STEP1_CODE) >= 55f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320Step2AndTheRiskDialogFit() {
        fontScale = 2f
        enterRisk44001()
        fullyVisible(textNode(t("phone_change.risk_blocked.desc")))
        listOf("phone_change.risk_blocked.ok", "cs.appeal_entry").forEach {
            val button = dialogButtonOf(t(it))
            val d = button.density()
            assertTrue("弹窗按钮命中 ≥48：$it", button.touch().height / d >= 47.5f)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320DoneFits() {
        fontScale = 2f
        enterDone()
        fullyVisible(textUnder(AccountTags.PHONE_DONE, t("phone_change.done.title")))
        fullyVisible(textNode(t("phone_change.done.body", mapOf("phone" to phone("freshMasked")))))
        assertTrue("【完成】在屏内", boxOf(AccountTags.PHONE_DONE_OK).bottom <= boxOf(AccountTags.PHONE_PAGE).bottom + 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320PendingCardFits() {
        fontScale = 2f
        enterPhonePending()
        listOf("pending_confirm.title", "pending_confirm.summary.new_phone", "phone_change.pending_hint").forEach {
            fullyVisible(textUnder(AccountTags.PENDING_CARD, t(it)))
        }
        val abandon = node(AccountTags.PENDING_ABANDON)
        val confirm = node(AccountTags.PENDING_CONFIRM)
        confirm.performScrollTo()
        settle()
        assertSeparate("两按钮命中区不重叠", abandon.touch(), confirm.touch())
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320BindFits() {
        fontScale = 2f
        enterBind(typed = true)
        fullyVisible(textNode(lt("invite.bind_phone_guide.title")))
        fullyVisible(textNode(draftB("invite.bind_phone_guide.benefit")))
        fullyVisible(textNode(draftB("invite.bind_phone_guide.merge_hint")))
        fullyVisible(textIn(BindPhoneTags.GET_CODE))
        assertSeparate("获取验证码命中区不压格子", node(BindPhoneTags.GET_CODE).touch(), boxOf(BindPhoneTags.CODE))
        assertTrue("【绑定】在屏内", boxOf(BindPhoneTags.SUBMIT).bottom <= boxOf(BindPhoneTags.PAGE).bottom + 1f)
    }

    // ---------------------------------------------------------------- 测试 v2 T3 / 评审 r1 R1-07：绑号顶部区让位
    //
    // 编排 10-29 ①「320×640、200% 关键控件不滚动在屏内为准，顶部区让位」+ 编排 v2 追加（评审 R1-01 / R1-02 / R1-03）：折叠档位只依赖窗口与字号
    // （键盘弹起、待确认卡、错误行都不触发，不闪动）；收起的副标题 / 并号提示条挪到卡片下方而不是删除（仍在语义树、滚动可达）；可见「验证码」标签
    // 恢复并作为让位的一档（该标签照换号写法 clearAndSetSemantics，不进语义树，本组不按字核它，由截图与 compare 看）。
    // 冻结 BindPhoneScreenTest 同尺寸但用键名当文字，顶部区不让位也过；以下全部用真实中文草稿。
    // 「顶部区」= 手机号框之上（下沿 ≤ 手机号框上沿）；「挪到卡片下方」= 上沿 ≥ 格下一行（获取验证码）下沿。位置一律取未裁切的布局位置。

    /**
     * 320×640 / 200%：①手机号框 / 验证码 / 获取验证码不滚动（先量、后滚）就完整在滚动可视区内（下沿 ≤【绑定】上沿、未被裁切），【绑定】在页内；
     * ②标题留在顶部区；③全量顶部区放不下（BindPhone-v2-font200-w320 基准推算格下一行下沿约 655dp，可视区下沿约 552dp），并号提示条须挪到卡片下方；
     * 副标题在顶部区或卡片下方二者之一，且「副标题挪走而提示条还在顶部」= 顺序错；④两条都恰好一处、滚动后完整可读（不删除）。
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun font200W320H640RealTextsKeepTheFourControlsOnScreenAndMoveTheIntroBelowTheCard() {
        fontScale = 2f
        enterBind()
        settle()
        val page = unclipped(BindPhoneTags.PAGE)
        val submit = unclipped(BindPhoneTags.SUBMIT)
        val d = densityOf(BindPhoneTags.PAGE)
        assertTrue("【绑定】在页内：$submit / $page", submit.top >= page.top - 1f && submit.bottom <= page.bottom + 1f)
        listOf(BindPhoneTags.PHONE, BindPhoneTags.CODE, BindPhoneTags.GET_CODE).forEach { tag ->
            val rect = unclipped(tag)
            assertTrue(
                "$tag 不滚动就在可视区内（下沿 ${rect.bottom / d}dp ≤【绑定】上沿 ${submit.top / d}dp）",
                rect.top >= page.top && rect.bottom <= submit.top + 1f,
            )
            assertEquals("$tag 未被裁切（可见高 = 布局高）", rect.height / d, boxOf(tag).height / d, 1f)
        }
        assertTrue("获取验证码命中 ≥48", node(BindPhoneTags.GET_CODE).touch().height / d >= 47.5f)
        val title = textNode(lt("invite.bind_phone_guide.title"))
        assertTrue("标题留在顶部区", title.unclippedBox().bottom <= unclipped(BindPhoneTags.PHONE).top + 1f)
        val tier = introTier()
        assertEquals("320×640×200% 放不下全部顶部区：并号提示条挪到卡片下方", BELOW, tier.hint)
        assertTrue("让位顺序：先挪并号提示条、再挪副标题（$tier）", tier.benefit == TOP || tier.hint == BELOW)
        listOf("invite.bind_phone_guide.benefit", "invite.bind_phone_guide.merge_hint").forEach {
            assertEquals("收起不删除，仍恰一处：$it", 1, textCount(draftB(it)))
            fullyVisible(textNode(draftB(it)))
        }
    }

    /** R1-01：常规 390×844 / 100% 全部顶部区放得下；键盘弹起（注入 IME 底部 inset）不改变档位、逐帧不闪动，收起键盘后仍不变。 */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun keyboardDoesNotChangeTheIntroTierOnARegularPhone() {
        enterBind(typed = true)
        settle()
        assertEquals("先正：常规机型全部在顶部区", IntroTier(TOP, TOP), introTier())
        val before = unclipped(BindPhoneTags.SUBMIT).bottom
        imeInset(IME_DP)
        val d = densityOf(BindPhoneTags.PAGE)
        assertTrue("先正：键盘注入生效（【绑定】随键盘上移）", (before - unclipped(BindPhoneTags.SUBMIT).bottom) / d >= IME_DP * 0.8f)
        repeat(FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals("键盘弹起后第 ${it + 1} 帧档位不变（不闪动）", IntroTier(TOP, TOP), introTier())
        }
        settle()
        assertEquals("键盘弹起不改变档位", IntroTier(TOP, TOP), introTier())
        imeInset(0)
        settle()
        assertEquals("收起键盘后档位不变", IntroTier(TOP, TOP), introTier())
    }

    /** R1-01：窄屏大字号下档位已定，键盘弹起不再多收（档位只依赖窗口与字号）。 */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun keyboardDoesNotFoldFurtherOnANarrowScreen() {
        fontScale = 2f
        enterBind(typed = true)
        settle()
        val tier = introTier()
        val before = unclipped(BindPhoneTags.SUBMIT).bottom
        imeInset(IME_DP)
        val d = densityOf(BindPhoneTags.PAGE)
        assertTrue("先正：键盘注入生效（【绑定】随键盘上移）", (before - unclipped(BindPhoneTags.SUBMIT).bottom) / d >= IME_DP * 0.5f)
        repeat(FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals("键盘弹起后第 ${it + 1} 帧档位不变", tier, introTier())
        }
        settle()
        assertEquals("键盘弹起不改变档位", tier, introTier())
    }

    /** R1-01：待确认卡不触发让位（常规 390×844 / 100%，顶部区照常全部显示）。 */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pendingCardDoesNotFoldTheIntro() {
        enterBindPending()
        idleUntil("绑号待确认卡") { has(BindPhonePendingTags.CARD) }
        settle()
        assertEquals("有待确认卡时顶部区不让位", IntroTier(TOP, TOP), introTier())
    }

    /** R1-01：字段错误行不触发让位（窄屏大字号下出现 20002 错误行前后档位相同）。 */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun fieldErrorDoesNotChangeTheIntroTier() {
        fontScale = 2f
        enterBind(sent = true)
        settle()
        val tier = introTier()
        type(BindPhoneTags.CODE, CODE_OK)
        enqueueCode("POST", BIND, 20002, "null")
        idleUntil("【绑定】可点") { enabled(BindPhoneTags.SUBMIT) }
        tap(BindPhoneTags.SUBMIT)
        idleUntil("验证码错误行") { has(BindPhoneTags.CODE_ERROR) }
        settle()
        assertEquals("错误行出现后档位不变", tier, introTier())
    }

    private data class IntroTier(
        val benefit: String,
        val hint: String,
    )

    /** 副标题 / 并号提示条各在哪：顶部区（手机号框之上）或卡片下方（格下一行之下）；既不在也不唯一即红。 */
    private fun introTier(): IntroTier {
        val phoneTop = unclipped(BindPhoneTags.PHONE).top
        val rowBottom = unclipped(BindPhoneTags.GET_CODE).bottom

        fun where(key: String): String {
            val nodes =
                composeRule
                    .onAllNodes(textMatcher(draftB(key)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
            assertEquals("$key 恰一处（收起不删除）", 1, nodes.size)
            val r = Rect(nodes.single().positionInRoot, nodes.single().size.toSize())
            return when {
                r.bottom <= phoneTop + 1f -> TOP
                r.top >= rowBottom - 1f -> BELOW
                else -> "夹在表单中间：$r"
            }
        }
        return IntroTier(where("invite.bind_phone_guide.benefit"), where("invite.bind_phone_guide.merge_hint"))
    }

    /** 注入键盘（IME）底部 inset（照 ProductVisualTestHost.navigationInset 的写法）。 */
    private fun imeInset(bottomDp: Int) {
        val node = composeRule.onRoot().fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val pixels = (bottomDp * node.layoutInfo.density.density).toInt()
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, pixels))
                .setVisible(WindowInsetsCompat.Type.ime(), bottomDp > 0)
                .build()
        composeRule.runOnUiThread { view.rootView.dispatchApplyWindowInsets(checkNotNull(insets.toWindowInsets())) }
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.unclippedBox(): Rect {
        val n = fetchSemanticsNode()
        return Rect(n.positionInRoot, n.size.toSize())
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200W320BindPendingFits() {
        fontScale = 2f
        enterBindPending()
        fullyVisible(textUnder(BindPhonePendingTags.CARD, t("pending_confirm.title")))
        fullyVisible(textUnder(BindPhonePendingTags.CARD, t("bind_phone.pending_hint")))
    }

    // ---------------------------------------------------------------- 大屏

    @Test
    @Config(qualifiers = "w840dp-h1109dp-xhdpi")
    fun w840Step2IsCappedAndCentered() {
        enterStep2()
        val card = boxOf(AccountTags.PHONE_STEP2)
        val page = boxOf(AccountTags.PHONE_PAGE)
        val d = densityOf(AccountTags.PHONE_PAGE)
        assertTrue("限宽 600（卡宽 ≤ 568）：${card.width / d}", card.width / d <= 568.5f)
        assertEquals("居中", page.center.x / d, card.center.x / d, 1f)
        assertEquals("【提交】同样居中", page.center.x / d, boxOf(AccountTags.PHONE_SUBMIT).center.x / d, 1f)
    }

    @Test
    @Config(qualifiers = "w840dp-h1261dp-xhdpi")
    fun w840BindIsCappedAndCentered() {
        enterBind()
        val input = boxOf(BindPhoneTags.PHONE)
        val submit = boxOf(BindPhoneTags.SUBMIT)
        val page = boxOf(BindPhoneTags.PAGE)
        val d = densityOf(BindPhoneTags.PAGE)
        assertTrue("限宽（现状 480，≤600）：${submit.width / d}", submit.width / d <= 600f)
        assertEquals("【绑定】居中", page.center.x / d, submit.center.x / d, 1f)
        assertTrue("输入在限宽内", input.left >= submit.left - 1f && input.right <= submit.right + 1f)
    }

    // ---------------------------------------------------------------- 辅助

    private fun fullyVisible(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        settle()
        node.assertTextFully()
    }

    private fun assertSeparate(
        message: String,
        first: Rect,
        second: Rect,
    ) {
        val i = first.intersect(second)
        assertTrue("$message：$first / $second", i.width <= 1f || i.height <= 1f)
    }

    /** 未裁切的布局位置（boundsInRoot 会被滚动容器裁切，滚出可视区的部分看不出来）。 */
    private fun unclipped(tag: String): Rect {
        val n = node(tag).fetchSemanticsNode()
        return Rect(n.positionInRoot, n.size.toSize())
    }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.hasAncestorTag(tag: String): Boolean {
        var p = parent
        while (p != null) {
            if (p.tag() == tag) return true
            p = p.parent
        }
        return false
    }

    private companion object {
        /** 焦点 / 弹窗断言前推进时钟（AccountFieldError 100ms、弹窗焦点；照 a 段取 200ms）。 */
        const val FOCUS_DELAY_MS = 200L
        const val TOP = "顶部区"
        const val BELOW = "卡片下方"
        const val IME_DP = 336
        const val FRAMES = 6
    }
}
