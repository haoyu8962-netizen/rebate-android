package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.textLayout
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m1ContentStartsEightDpBelowTitleAndKeepsSixteenDpSides() {
        host.launch()
        val nickname = rule.meTag(MeTags.ME_NICKNAME).fetchSemanticsNode()
        val invite = rule.meTag(MeTags.ME_INVITER).fetchSemanticsNode()
        val card = commonLayout(nickname.layoutInfo, invite.layoutInfo).parentInfo!!
        val bounds = outerBounds(card)
        val bell = rule.meTag(MeTags.ME_BELL)
        val title = commonLayout(rule.onNodeWithText("我的").fetchSemanticsNode().layoutInfo, bell.fetchSemanticsNode().layoutInfo)
        assertMeDp(8f, bounds.top - outerBounds(title).bottom, bell.meDensity())
        assertMeDp(16f, bounds.left - rule.meTag(MeTags.ME_SCROLL).meBounds().left, bell.meDensity())
        assertMeDp(12f, rule.meTag(MeTags.ME_BALANCE).meBounds().top - bounds.bottom, bell.meDensity())
    }

    @Test fun m5m6IdentityPaddingAvatarAndHorizontalGapMatchDrawing() {
        host.launch()
        val nickname = rule.meTag(MeTags.ME_NICKNAME).fetchSemanticsNode()
        val invite = rule.meTag(MeTags.ME_INVITER).fetchSemanticsNode()
        val column = commonLayout(nickname.layoutInfo, invite.layoutInfo)
        val card = column.parentInfo!!
        val avatar = card.meChildren().first { it !== column }
        val outer = outerBounds(card)
        val circle = outerBounds(avatar)
        val density = nickname.layoutInfo.density.density
        assertMeDp(16f, circle.left - outer.left, density)
        assertMeDp(56f, circle.width, density)
        assertMeDp(56f, circle.height, density)
        assertMeDp(12f, outerBounds(column).left - circle.right, density)
        assertMeDp(32f, outerBounds(avatar.meChildren().single()).width, density)
    }

    @Test fun m7NicknameIsSeventeenSemiboldAndNotEllipsized() {
        host.launch()
        val result = rule.meTag(MeTags.ME_NICKNAME).textLayout()
        assertEquals(17f, result.layoutInput.style.fontSize.value, 0f)
        assertEquals(
            600,
            result.layoutInput.style.fontWeight!!
                .weight,
        )
        assertTrue((0 until result.lineCount).none(result::isLineEllipsized))
    }

    @Test fun m8InviteAndInviterUseTwelvePointFlowAndCopyFollowsActualText() {
        host.launch()
        val code = rule.meTag(MeTags.ME_INVITE_CODE)
        val inviter = rule.meTag(MeTags.ME_INVITER)
        assertEquals(
            12f,
            code
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertEquals(
            12f,
            inviter
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertMeDp(0f, code.meBounds().center.y - inviter.meBounds().center.y, code.meDensity())
        code.assertMeCopyFollows(rule.meTag(MeTags.ME_COPY))
        assertTrue("48dp命中不能撑高邀请码文字行", code.meBounds().height / code.meDensity() <= 20f)
        val text = code.textLayout().layoutInput.text
        assertTrue(
            "码段600字重",
            text.spanStyles.any { it.item.fontWeight?.weight == 600 && text.substring(it.start, it.end).contains("K7M3QX") },
        )
    }

    @Test fun m9HeroHeaderIsCompactWithTwentyDpEye() {
        host.launch()
        val label = rule.onNodeWithText("可提现余额", useUnmergedTree = true)
        val hero = rule.meTag(MeTags.ME_BALANCE)
        val amount = rule.onNodeWithText("¥12.34", useUnmergedTree = true)
        val eye = rule.onNodeWithContentDescription("隐藏金额").fetchSemanticsNode().layoutInfo
        assertMeDp(20f, outerBounds(eye.meChildren().single()).width, label.meDensity())
        assertMeDp(20f, label.meBounds().top - hero.meBounds().top, label.meDensity())
        assertTrue("首行不能仍被48dp按钮撑高", amount.meBounds().top - label.meBounds().top < 48 * label.meDensity())
    }

    @Test fun m10HeroAmountAndButtonTypography() {
        host.launch()
        assertEquals(
            34f,
            rule
                .onNodeWithText("¥12.34", useUnmergedTree = true)
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        val withdraw = rule.meTag(MeTags.ME_WITHDRAW)
        assertTrue(
            "提现视觉宽度应至少96dp，实际=${withdraw.meBounds().width / withdraw.meDensity()}",
            withdraw.meBounds().width / withdraw.meDensity() >= 96f,
        )
        assertTrue(withdraw.meBounds().height / withdraw.meDensity() >= 48f)
    }

    @Test fun m11EstimatedUsesFourteenRegularAndTwoDpGap() {
        host.launch()
        val label = rule.onNodeWithText("预估收益", useUnmergedTree = true)
        val value = rule.onNodeWithText("¥56.78", useUnmergedTree = true)
        assertEquals(
            14f,
            label
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertEquals(
            400,
            label
                .textLayout()
                .layoutInput.style.fontWeight
                ?.weight ?: 400,
        )
        assertEquals(
            22f,
            value
                .textLayout()
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertMeDp(2f, value.meBounds().top - label.meBounds().bottom, label.meDensity())
        val row = label.fetchSemanticsNode().layoutInfo.parentInfo!!
        val clock = row.meChildren().first { it !== label.fetchSemanticsNode().layoutInfo }
        assertMeDp(14f, outerBounds(clock).width, label.meDensity())
    }
}
