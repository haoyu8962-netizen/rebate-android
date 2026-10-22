package com.couli.app.me

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
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
class MeVisualNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m15CopySuccessHasGreenIconAndAnnouncesOnce() {
        host.launch()
        rule.meTag(MeTags.ME_COPY).performClick()
        rule.waitForIdle()
        assertEquals(listOf("K7M3QX"), host.env.copies)
        assertEquals(listOf("已复制邀请码"), host.spoken)
        val toast = rule.meTag(MeTags.TOAST).fetchSemanticsNode()
        val pixels = rule.productPixels()
        val bounds = toast.boundsInRoot
        val green = host.success.toArgb()
        val count =
            (bounds.left.toInt() until bounds.right.toInt()).sumOf { x ->
                (bounds.top.toInt() until bounds.bottom.toInt()).count { y -> pixels.getPixel(x, y) == green }
            }
        assertTrue("复制成功必须实际绘制绿色对勾", count >= 10)
        host.assertReads(1)
    }

    @Test fun m15CopyFailureHasNoSuccessAnnouncement() {
        host.env.copyOk = false
        host.launch()
        rule.meTag(MeTags.ME_COPY).performClick()
        rule.waitForIdle()
        assertEquals(listOf("复制失败，请重试"), host.spoken)
        host.assertReads(1)
    }

    @Test fun m16SameFrameRefreshErrorWinsThenCopyAppearsOnce() {
        host.launch()
        rule.mainClock.autoAdvance = false
        rule.meTag(MeTags.ME_COPY).performClick()
        host.refresh(MeVisualFixtures.failure())
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onAllNodesWithTag(MeTags.TOAST, useUnmergedTree = true).assertCountEquals(1)
        assertEquals("同帧错误优先，复制不能抢先播报", 1, host.spoken.size)
        assertTrue(host.spoken.single().contains("a1b2c3"))
        val live =
            rule
                .meTag(MeTags.TOAST)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Assertive, live)
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        rule.onAllNodesWithText("已复制邀请码", useUnmergedTree = true).assertCountEquals(1)
        assertEquals(listOf(host.spoken.first(), "已复制邀请码"), host.spoken)
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        rule.onAllNodesWithTag(MeTags.TOAST, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(2, host.spoken.size)
        host.assertReads(2)
    }

    @Test fun m16CopyArrivingDuringErrorWaitsWithoutEarlyConsumption() {
        host.launch()
        rule.mainClock.autoAdvance = false
        host.refresh(MeVisualFixtures.failure())
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(1, host.spoken.size)
        assertTrue(host.spoken.single().contains("a1b2c3"))
        rule.meTag(MeTags.ME_COPY).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onAllNodesWithTag(MeTags.TOAST, useUnmergedTree = true).assertCountEquals(1)
        assertEquals("复制还在排队，不能抢播", 1, host.spoken.size)
        assertTrue("未展示的复制不能被宿主提前消费", host.controller.toast.value != null)
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        assertEquals(listOf(host.spoken.first(), "已复制邀请码"), host.spoken)
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        rule.onAllNodesWithTag(MeTags.TOAST, useUnmergedTree = true).assertCountEquals(0)
        host.assertReads(2)
    }

    @Test fun m16RepeatedCopyUsesNewIdsWithoutRecompositionDuplicates() {
        host.launch()
        rule.meTag(MeTags.ME_COPY).performClick()
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals(listOf("已复制邀请码"), host.spoken)
        rule.mainClock.advanceTimeBy(2_100)
        rule.meTag(MeTags.ME_COPY).performClick()
        rule.waitForIdle()
        assertEquals(listOf("已复制邀请码", "已复制邀请码"), host.spoken)
        assertEquals(listOf("K7M3QX", "K7M3QX"), host.env.copies)
    }
}
