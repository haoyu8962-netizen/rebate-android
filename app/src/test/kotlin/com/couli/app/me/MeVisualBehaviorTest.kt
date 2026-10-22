package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.MeLevel
import com.couli.core.me.MeView
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

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m2BellIsOneOriginalRouteWithoutUnreadClaim() {
        host.launch()
        rule.meTag(MeTags.ME_BELL).performClick()
        assertEquals(listOf("Messages"), host.env.opened.map { it.first })
        assertTrue(rule.meReadouts(true).none { "未读" in it })
        host.assertReads(1)
    }

    @Test fun m10FrozenStillNavigatesToWalletOnceAndNeverFetchesRules() {
        host.launch("me_frozen")
        rule.meTag(MeTags.ME_WITHDRAW).assertIsEnabled().performClick()
        assertEquals(listOf("Wallet"), host.env.opened.map { it.first })
        assertTrue(
            host.env.opened
                .single()
                .second
                .isEmpty(),
        )
        host.assertReads(1)
    }

    @Test fun m10NegativeAvailableDoesNotDisableOrInventWarning() {
        host.launch("me_negative")
        rule.meTag(MeTags.ME_WITHDRAW).assertIsEnabled().performClick()
        rule.onNodeWithText("待抵扣", substring = true).assertDoesNotExist()
        assertEquals(
            "Wallet",
            host.env.opened
                .single()
                .first,
        )
        host.assertReads(1)
    }

    @Test fun m12EyeAnnouncesOnlyEachUserToggleOnce() {
        host.launch()
        assertTrue("首次进入不播报金额状态", host.spoken.isEmpty())
        rule.onNodeWithContentDescription("隐藏金额").performClick()
        rule.waitForIdle()
        assertEquals(listOf("金额已隐藏"), host.spoken)
        rule.waitForIdle()
        assertEquals(1, host.spoken.size)
        rule.onNodeWithContentDescription("显示金额").performClick()
        rule.waitForIdle()
        assertEquals(listOf("金额已隐藏", "金额已显示"), host.spoken)
        assertFalse(host.mask.hidden.value)
        host.assertReads(1)
    }

    @Test fun m12HiddenAmountsAreTwoIndependentReadoutsInBothTrees() {
        host.launch(hidden = true)
        for (unmerged in listOf(false, true)) {
            val descriptions = rule.meReadouts(unmerged)
            for (name in listOf("可提现余额 金额已隐藏", "预估收益 金额已隐藏")) {
                assertEquals("独立读法：$name", 1, descriptions.count { it == name })
                assertTrue("不能把两项合在一个名称里", descriptions.none { it.contains(name) && it != name })
            }
            assertTrue(descriptions.none { "12.34" in it || "56.78" in it })
        }
        rule.onNodeWithText("¥12.34", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("¥56.78", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(host.spoken.isEmpty())
    }

    @Test fun m13GuestHasOnlyTwoCellsAndLoginWithZeroReads() {
        host.env.identityFlow.value = host.env.identity().copy(level = MeLevel.GUEST, account = null)
        host.launch()
        rule.meTag(MeTags.ME_BELL).assertDoesNotExist()
        rule.meTag(MeTags.ME_BALANCE).assertDoesNotExist()
        rule.meTag(MeTags.ME_GUIDE_BAR).assertDoesNotExist()
        rule.meTag(MeTags.ME_LOGIN).performClick()
        assertEquals(listOf("Login"), host.env.opened.map { it.first })
        for (cell in MeView.GUEST_GRID) rule.onNodeWithText(cell.item).performClick()
        assertEquals(listOf("Login", "Help", "Settings"), host.env.opened.map { it.first })
        host.assertReads(0)
    }

    @Test fun m14EveryGridCellKeepsItsOriginalTargetExactlyOnce() {
        host.env.earnings = true
        host.env.routes = MeVisualFixtures.routes(public = true)
        host.launch()
        MeView.MEMBER_GRID.forEach { cell ->
            rule.onNodeWithText(cell.item).performScrollTo().performClick()
        }
        assertEquals(MeView.MEMBER_GRID.mapNotNull { it.route }, host.env.opened.map { it.first })
        assertTrue(host.env.opened.all { it.second.isEmpty() })
        assertEquals(listOf("Me"), host.env.cs)
        host.assertReads(1)
    }

    @Test fun m14NoInviteCodeOnlyHidesCodeNotPromotion() {
        host.launch("me_no_invite_code")
        rule.meTag(MeTags.ME_COPY).assertDoesNotExist()
        rule.meTag(MeTags.ME_INVITE_CODE).assertDoesNotExist()
        rule.onNodeWithText("邀请好友").assertExists()
        rule.onNodeWithText("收藏夹").assertDoesNotExist()
        rule.onNodeWithText("分享赚").assertDoesNotExist()
        rule.onNodeWithText("收益看板").assertDoesNotExist()
        rule.onNodeWithText("其中已收货", substring = true).assertDoesNotExist()
        rule.onNodeWithText("最早预计", substring = true).assertDoesNotExist()
    }

    @Test fun m14EarningsNeedsBothAvailabilityAndFlag() {
        host.env.earnings = true
        host.launch()
        rule.onNodeWithText("收益看板").assertDoesNotExist()
        rule.runOnIdle { host.env.routes = MeVisualFixtures.routes(true) }
        host.refresh(MeVisualFixtures.ok())
        rule.onNodeWithText("收益看板").assertExists()
        rule.runOnIdle { host.env.earnings = false }
        host.refresh(MeVisualFixtures.ok())
        rule.onNodeWithText("收益看板").assertDoesNotExist()
        host.assertReads(3)
    }
}
