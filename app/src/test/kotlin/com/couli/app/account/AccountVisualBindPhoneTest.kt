@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText as textMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.density
import com.couli.app.me.glyphBox
import com.couli.app.me.iconBoxes
import com.couli.app.me.ink
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.app.me.touch
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-b 绑手机页（非登录入口）视觉（brief-android D35–D38；gaps G-D17 / G-D18 / G-D20 / G-D04 / G-D08）。画板 BindPhone-v2 1261 高。
 * 只改顶部区与输入写法（G-D17）：顶栏只留返回、标题移到内容区 22/600、副标题 .benefit 与并号提示条 .merge_hint 两条新草稿、.body 本页不再渲染；
 * 手机号框 +86 次文字无分隔；验证码换 6 格、【获取验证码】挪格下（结构不改、不接输满自动绑定）；三个确认框标题居中 + 遮罩令牌；待确认卡同注销写法。
 * 底版上视觉断言应红；回调 / 次数由 AccountVisualPhoneRedLineTest ⑨ 守。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1261dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountVisualBindPhoneTest : AccountVisualPhoneHarness() {
    // ---------------------------------------------------------------- D35 顶部区

    @Test fun d35TopBarKeepsOnlyBackAndTheTitleMovesIntoTheContent() {
        enterBind()
        val back = boxOf(BindPhoneTags.BACK)
        val titleText = lt("invite.bind_phone_guide.title")
        assertEquals("标题只出现一次（顶栏不再有标题）", 1, textCount(titleText))
        val title = textNode(titleText)
        val d = title.density()
        assertTrue("D35 标题在顶栏之下（内容区）", title.textBox().top >= back.bottom - 0.5f)
        assertFont("标题 22", 22f, title)
        assertWeight("600", 600, title)
        assertTrue(
            "标题仍是读屏标题（heading）",
            title.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Heading) != null,
        )
        assertEquals("左对齐到内容左 16", 16f, (title.glyphBox().left - boxOf(BindPhoneTags.PAGE).left) / d, 1.5f)
    }

    @Test fun d35BenefitSubtitleAndMergeHintStrip() {
        enterBind()
        val title = textNode(lt("invite.bind_phone_guide.title"))
        val benefit = textNode(draftB("invite.bind_phone_guide.benefit"))
        assertFont("副标题 14", 14f, benefit)
        assertColor("副标题次文字", palette.secondary, benefit.textStyle().color)
        assertTrue("副标题在标题下", benefit.textBox().top >= title.textBox().bottom - 0.5f)
        val hint = textNode(draftB("invite.bind_phone_guide.merge_hint"))
        assertColor("提示条主文字", palette.primary, hint.textStyle().color)
        val block = assertBox("G-D17 muted 并号提示条", pixels().blockOf(hint, palette.muted))
        val d = hint.density()
        assertEquals("条内一个 20 info", 1, node(BindPhoneTags.PAGE).iconBoxes(18f, 22f).count { it.overlaps(block) })
        assertEquals("标题区 → 提示条 纵向 24", 24f, (block.top - benefit.textBox().bottom) / d, 3f)
    }

    @Test fun d35TheOldBodyIsNoLongerRenderedOnThisPage() {
        enterBind()
        val body = lt("invite.bind_phone_guide.body")
        assertTrue("先正：契约 .body 有字（登录引导弹窗仍用）", body.isNotBlank())
        assertEquals("G-D17 本页不再渲染 .body", 0, textCount(body))
    }

    @Test fun gd17DraftKeysCarryTheBoardWording() {
        listOf("invite.bind_phone_guide.benefit", "invite.bind_phone_guide.merge_hint").forEach {
            assertEquals("G-D17 新草稿键（LoginDraftTexts，三端同键同字）：$it", draftB(it), lt(it))
        }
        assertEquals("G-D08 绑号倒计时措辞维持", keptB("bind_phone.countdown"), LoginDraftTextsProbe.raw("bind_phone.countdown"))
    }

    // ---------------------------------------------------------------- D36 手机号框

    @Test fun d36PrefixIsSecondaryWithoutTheDivider() {
        enterBind()
        val prefix = textUnder(BindPhoneTags.PAGE, lt("login.phone.prefix"))
        assertColor("+86 次文字色", palette.secondary, prefix.textStyle().color)
        val input = boxOf(BindPhoneTags.PHONE)
        val d = prefix.density()
        val glyph = prefix.glyphBox()
        val gap = Rect(glyph.right + 2f, input.center.y - 8f * d, input.left - 1f, input.center.y + 8f * d)
        assertTrue("先正：+86 在输入之左", gap.width > 0f)
        assertTrue("D36 去掉竖分隔线", pixels().ink(gap, palette.surface) == null)
    }

    @Test fun d36FrameIsFiftyTwoAndThePlaceholderIsSecondary() {
        enterBind()
        val frame = frameOf(BindPhoneTags.PHONE)
        val d = densityOf(BindPhoneTags.PHONE)
        assertTrue("框高 ≥52：${frame.height / d}", frame.height / d >= 51.5f)
        val placeholder = textUnder(BindPhoneTags.PAGE, keptB("login.phone.placeholder"))
        assertColor("占位次文字", palette.secondary, placeholder.textStyle().color)
    }

    @Test fun d36ClearButtonIsKeptWithAFortyEightTarget() {
        enterBind(typed = true)
        val clear = node(BindPhoneTags.PHONE_CLEAR)
        val d = clear.density()
        assertTrue("清除保留、命中 ≥48", clear.touch().height / d >= 47.5f && clear.touch().width / d >= 47.5f)
    }

    // ---------------------------------------------------------------- D37 验证码

    @Test fun d37CodeIsSixCellsFiftySixHigh() {
        enterBind(typed = true)
        val d = densityOf(BindPhoneTags.CODE)
        assertEquals("6 格", 12, cellBorderRuns(BindPhoneTags.CODE).size)
        assertEquals("格高 56", 56f, boxOf(BindPhoneTags.CODE).height / d, 1.5f)
    }

    @Test fun d37GetCodeIsALinkUnderTheCellsRightAligned() {
        enterBind(typed = true)
        val text = textIn(BindPhoneTags.GET_CODE)
        val cells = boxOf(BindPhoneTags.CODE)
        val d = densityOf(BindPhoneTags.CODE)
        assertEquals(lt("login.get_code"), textOfNode(text))
        assertColor("可点 text.link", palette.link, text.textStyle().color)
        assertTrue("D37 挪到格下一行", text.glyphBox().top >= cells.bottom - 0.5f)
        assertEquals("右对齐", cells.right / d, text.glyphBox().right / d, 4f)
        val hit = node(BindPhoneTags.GET_CODE).touch()
        assertTrue("命中 ≥48", hit.height / d >= 47.5f)
        val i = hit.intersect(cells)
        assertTrue("命中区不压格子", i.width <= 1f || i.height <= 1f)
    }

    @Test fun d37CountdownKeepsTheWordingInSecondaryText() {
        enterBind(sent = true)
        val text = textIn(BindPhoneTags.GET_CODE)
        val words = textOfNode(text)
        assertTrue("G-D08「{sec} 秒后重新获取」：$words", words.contains(STEP2_COUNTDOWN_TEXT) && !words.contains(FORBIDDEN_COUNTDOWN))
        assertColor("倒计时次文字", palette.secondary, text.textStyle().color)
    }

    @Test fun d37WrongCodeTurnsTheCellsRedAndKeepsTheErrorOnTheInput() {
        bindSubmitWith(20002)
        idleUntil("验证码字段错误") { has(BindPhoneTags.CODE_ERROR) }
        assertEquals("6 格都是 status.error 描边", 12, cellBorderRuns(BindPhoneTags.CODE, palette.error).size)
        assertEquals("error 语义在输入节点上（冻结 BindPhoneScreenTest 同口径）", textOf(BindPhoneTags.CODE_ERROR), errorOf(BindPhoneTags.CODE))
        assertTrue("错误句在格下", boxOf(BindPhoneTags.CODE_ERROR).top >= boxOf(BindPhoneTags.CODE).bottom - 1f)
    }

    // ---------------------------------------------------------------- D38 确认框与待确认卡

    @Test
    @Config(qualifiers = "w390dp-h1091dp-xhdpi")
    fun d38MergeDialogCentersTheTitleAndKeepsBodyAndTarget() {
        enterBindMerge()
        assertDialogTitleCentered("并号确认框", lt("bind_phone.merge.title"))
        assertEquals("信息块「并入到」原样", 1, textCount(lt("bind_phone.merge.into")))
        assertTrue("信息块原样（脱敏号码）", hasText(ltf("bind_phone.merge.account", mapOf("phone" to phone("bindMasked")))))
    }

    @Test fun d38RiskDialogCentersTheTitle() {
        enterBind44001()
        assertDialogTitleCentered("绑号 44001", lt("error.44001"))
    }

    @Test fun d38PendingCardIsWhiteWithAWarningBlockAndMutedDetails() {
        enterBindPending()
        val card = boxOf(BindPhonePendingTags.CARD)
        val d = densityOf(BindPhonePendingTags.CARD)
        val px = pixels()
        assertTrue("白卡（现为整卡警告底）", px.cardColorIs(card, palette.surface, d))
        val title = textUnder(BindPhonePendingTags.CARD, t("pending_confirm.title"))
        assertFont("标题 14", 14f, title)
        assertWeight("标题 600", 600, title)
        assertColor("警告字色", palette.warningText, title.textStyle().color)
        assertTrue("警告块", px.blockOf(title, palette.warningBackground) != null)
        val label = textUnder(BindPhonePendingTags.CARD, t("pending_confirm.summary.new_phone"))
        assertTrue("灰底明细", px.blockOf(label, palette.muted) != null)
        val abandon = boxOf(BindPhonePendingTags.ABANDON)
        val confirm = boxOf(BindPhonePendingTags.CONFIRM)
        assertEquals("两按钮等宽", abandon.width / d, confirm.width / d, 1f)
        assertTrue("先正：脱敏号码原样", hasText(AccountVisualFixtures.pendingSummary("bind_phone")))
    }

    @Test fun d38PendingInFlightShowsTheStatusRow() {
        enterBindPending()
        assertEquals("先反：不在途没有在途行", 0, busyRows())
        val (arrived, release) = hold("POST", BIND) { envelope("{}") }
        try {
            tap(BindPhonePendingTags.CONFIRM)
            assertTrue(arrived.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
            settle()
            assertFalse("在途两按钮置灰（原样）", enabled(BindPhonePendingTags.CONFIRM) || enabled(BindPhonePendingTags.ABANDON))
            assertEquals("G-D13 在途行恰一行（只读既有 pendingBusy）", 1, busyRows())
        } finally {
            release.countDown()
        }
    }

    @Test fun d38AbandonDialogCentersTheTitle() {
        enterBindPending()
        tap(BindPhonePendingTags.ABANDON)
        idleUntil("放弃二次确认") { has(BindPhonePendingTags.ABANDON_OK) }
        assertDialogTitleCentered("绑号放弃确认框", t("pending_confirm.abandon"))
    }

    private fun busyRows(): Int = countUnder(BindPhonePendingTags.CARD, textMatcher(t("error.40901")))
}

/** 绑号草稿原值（不经占位回退，核措辞用）。 */
internal object LoginDraftTextsProbe {
    fun raw(key: String): String? = com.couli.app.login.LoginDraftTexts.DRAFTS[key]
}
