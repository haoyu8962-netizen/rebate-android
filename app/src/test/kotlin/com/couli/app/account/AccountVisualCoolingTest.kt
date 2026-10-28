@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText as textMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.me.sameColor
import com.couli.app.me.textBox
import com.couli.app.me.textStyle
import com.couli.core.designsystem.CouliFormat
import com.couli.feature.me.AccountTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13v-a 冷静期（brief-android D16–D18；DeleteAccountCooling-v2 993 高、DeleteAccountStates ③）。状态卡（胶囊 status.pending + 时钟 14、高 22 →
 * 22/600 标题 → 17 截止句 → 14 次文字）→ 时间线卡（图标 20 + 2px 连线；当前 600、未到次文字；说明 14）→ muted 提示条（info 20）→ 白底栏【撤回注销】。
 * 时间只格式化 cooling_until / apply_at（CouliFormat，不读本机时钟推算）；步态只由 showCancelDeletion 映射。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h993dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountVisualCoolingTest : AccountVisualDeletionHarness() {
    private val until get() =
        AccountVisualFixtures.millis(
            AccountVisualFixtures.text("cooling_until", AccountVisualFixtures.obj("cooling", AccountVisualFixtures.obj("deletion"))),
        )
    private val applyAt get() =
        AccountVisualFixtures.millis(
            AccountVisualFixtures.text("apply_at", AccountVisualFixtures.obj("cooling", AccountVisualFixtures.obj("deletion"))),
        )

    // ---------------------------------------------------------------- D16 状态卡与胶囊

    @Test fun d16PillIsPendingToneWithClockAnd22High() {
        enterCooling()
        val pill = textUnder(AccountTags.DELETE_COOLING, t("deletion.cooling.badge"))
        assertFont("胶囊字 12", 12f, pill)
        assertColor("D16 胶囊字 status.pending（现为 warning）", palette.pendingText, pill.textStyle().color)
        val d = pageDensity()
        val around = pill.textBox().let { Rect(it.left - 40f * d, it.top - 10f * d, it.right + 16f * d, it.bottom + 10f * d) }
        val bg = assertBox("胶囊底 status.pending", pixels().colorBox(around, palette.pendingBackground, 2))
        assertDpNear("胶囊高 22", 22f, bg.height, d, 1.5f)
        assertEquals("胶囊内一个 14 时钟", 1, iconsIn(bg, 12f, 16f).size)
    }

    @Test fun d16TitleDeadlineAndRestore() {
        enterCooling()
        val title = textNode(t("deletion.cooling.title"))
        assertFont("标题 22", 22f, title)
        assertWeight("标题 600", 600, title)
        assertTrue("标题 heading（原样）", title.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        val deadline = node(AccountTags.DELETE_COOLING_DEADLINE)
        assertEquals(
            "截止句只格式化 cooling_until（原样）",
            t("deletion.cooling.deadline", mapOf("time" to CouliFormat.timeListFuture(until, now))),
            textOf(AccountTags.DELETE_COOLING_DEADLINE),
        )
        assertFont("截止句 17", 17f, deadline)
        assertColor("截止句主文字", palette.primary, deadline.textStyle().color)
        val restore = textNode(t("deletion.cooling.restore"))
        assertFont("说明 14", 14f, restore)
        assertColor("说明次文字", palette.secondary, restore.textStyle().color)
        val card = assertBox("状态卡白卡", pixels().blockOf(title, palette.surface, 2f))
        assertTrue("状态卡包住胶囊、标题、截止、说明", card.contains(restore.textBox().center) && card.contains(deadline.textBox().center))
    }

    @Test fun d16ExpiredReplacesThePillWithTheProcessingTitle() {
        enterCooling(expired = true)
        assertTrue("先正：can_cancel=false 句（原样）", has(AccountTags.DELETE_CANCEL_EXPIRED))
        val title = textNode(t("error.10007"))
        assertFont("States ③ 标题 22", 22f, title)
        assertWeight("标题 600", 600, title)
        assertFalse("胶囊位换成标题（不再显示「冷静期中」）", hasText(t("deletion.cooling.badge")))
        assertFalse("无【撤回注销】（showCancelDeletion 原样）", has(AccountTags.DELETE_CANCEL))
    }

    // ---------------------------------------------------------------- D17 时间线

    @Test fun d17TimelineIsItsOwnCardBelowTheStatusCard() {
        enterCooling()
        val status = assertBox("状态卡", pixels().blockOf(textNode(t("deletion.cooling.title")), palette.surface, 2f))
        val step = textNode(t("deletion.cooling.timeline.apply"))
        val timeline = assertBox("时间线卡", pixels().blockOf(step, palette.surface, 2f))
        assertNotEquals("时间线另起一张卡", status.top, timeline.top)
        assertTrue("时间线卡在状态卡之下", timeline.top >= status.bottom)
        assertDpNear("卡间 12", 12f, timeline.top - status.bottom, pageDensity(), 1.5f)
    }

    @Test fun d17StepTitlesAndDescriptions() {
        enterCooling()
        val apply1 = textNode(t("deletion.cooling.timeline.apply"))
        val cooling = textNode(t("deletion.cooling.timeline.cooling"))
        val process = textNode(t("deletion.cooling.timeline.process"))
        listOf(apply1, cooling, process).forEach { assertFont("步骤标题 17", 17f, it) }
        assertWeight("当前步（冷静期）600", 600, cooling)
        assertColor("当前步主文字", palette.primary, cooling.textStyle().color)
        assertColor("未到步次文字", palette.secondary, process.textStyle().color)
        assertNotEquals("完成步不是 600", 600, apply1.textStyle().fontWeight?.weight ?: 400)
        val applyDesc = textNode(CouliFormat.timeListFuture(applyAt, now))
        assertFont("说明 14", 14f, applyDesc)
        assertColor("说明次文字", palette.secondary, applyDesc.textStyle().color)
        assertTrue(
            "冷静期说明只格式化 cooling_until",
            hasText(t("deletion.cooling.timeline.cooling_desc", mapOf("time" to CouliFormat.timeListFuture(until, now)))),
        )
        assertTrue("处理说明原样", hasText(t("deletion.cooling.timeline.process_desc")))
        assertTrue("标题在说明之上（纵向排列，不再「标签 值」同行）", apply1.textBox().bottom <= applyDesc.textBox().top + pageDensity())
    }

    @Test fun d17IconsAndConnector() {
        enterCooling()
        val icons = timelineIcons()
        assertEquals("三步各一个 20 图标", 3, icons.size)
        val px = pixels()
        assertTrue("完成步：status.success", px.colorBox(icons[0], palette.successText, 16) != null)
        assertTrue("当前步：主文字色时钟", px.colorBox(icons[1], palette.primary, 16) != null)
        assertTrue("未到步：border.control 圆圈", px.colorBox(icons[2], palette.control, 16) != null)
        val x = icons[0].center.x
        val y = (icons[0].bottom + icons[1].top) / 2f
        assertTrue("步 1 → 步 2 之间 2px border.subtle 连线", sameColor(px.getPixel(x.toInt(), y.toInt()), palette.subtle, 6))
        val d = pageDensity()
        val line = px.colorBox(Rect(x - 3f * d, icons[0].bottom, x + 3f * d, icons[1].top), palette.subtle, 6)
        assertDpNear("连线宽 2", 2f, assertBox("连线", line).width, d, 0.75f)
        val title = textNode(t("deletion.cooling.timeline.apply"))
        assertDpNear("图标列 → 标题 12", 12f, title.textBox().left - icons[0].right, d, 1.5f)
    }

    @Test fun d17ExpiredMapsDoneDoneFuture() {
        enterCooling(expired = true)
        val icons = timelineIcons()
        assertEquals(3, icons.size)
        val px = pixels()
        assertTrue("完成", px.colorBox(icons[0], palette.successText, 16) != null)
        assertTrue("冷静期步也完成（showCancelDeletion = false）", px.colorBox(icons[1], palette.successText, 16) != null)
        assertTrue("未到", px.colorBox(icons[2], palette.control, 16) != null)
    }

    @Test fun d17EachStepReadsAsOneNode() {
        enterCooling()
        val merged =
            composeRule
                .onAllNodes(textMatcher(t("deletion.cooling.timeline.process"), substring = true), useUnmergedTree = false)
                .fetchSemanticsNodes()
        assertEquals("合并树里「注销处理」只在一个节点", 1, merged.size)
        val texts = merged.single().config[SemanticsProperties.Text].map { it.text }
        assertTrue("一步一个节点「标题，说明」：$texts", texts.contains(t("deletion.cooling.timeline.process_desc")))
    }

    // ---------------------------------------------------------------- D18 提示条与底栏

    @Test fun d18ReadonlyIsAMutedStripWithInfoIcon() {
        enterCooling()
        val text = textNode(t("deletion.cooling.readonly"))
        assertFont("14", 14f, text)
        assertColor("D18 主文字（现为 caption 次文字）", palette.primary, text.textStyle().color)
        val strip = assertBox("muted 提示条", pixels().blockOf(text, palette.muted))
        assertEquals("条内一个 20 info", 1, iconsIn(strip, 18f, 22f).size)
    }

    @Test fun d18CancelButtonSitsInTheWhiteBottomBar() {
        enterCooling()
        val cancel = boxOf(AccountTags.DELETE_CANCEL)
        val d = pageDensity()
        val px = pixels()
        assertTrue("白底栏", sameColor(px.getPixel((4f * d).toInt(), cancel.center.y.toInt()), palette.surface, 2))
        assertDpNear("通栏 16 / 16", 358f, cancel.width, d, 1.5f)
        assertTrue("【撤回注销】可点（enabled 原样）", enabled(AccountTags.DELETE_CANCEL))
    }

    /** 时间线三步的图标：每步标题左侧、同一竖列、18–22dp 的正方形叶子，按纵向排序。 */
    private fun timelineIcons(): List<Rect> {
        val titles =
            listOf("deletion.cooling.timeline.apply", "deletion.cooling.timeline.cooling", "deletion.cooling.timeline.process")
                .map { textNode(t(it)).textBox() }
        val d = pageDensity()
        val area = Rect(0f, titles.first().top - 8f * d, titles.first().left, titles.last().bottom + 8f * d)
        return iconsIn(area, 8f, 22f).filter { it.width / d >= 9f }.sortedBy { it.top }
    }
}
