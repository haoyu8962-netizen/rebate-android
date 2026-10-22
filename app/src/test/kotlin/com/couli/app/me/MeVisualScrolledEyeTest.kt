package com.couli.app.me

import android.app.Application
import android.view.View
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
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
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualScrolledEyeTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun s2ScrolledCardHideAnnouncesOnceFromVisiblePoliteNode() = toggle(hidden = false, clipped = true)

    @Test fun s2ScrolledCardShowAnnouncesOnceFromVisiblePoliteNode() = toggle(hidden = true, clipped = true)

    @Test fun s2WholeCardHideAnnouncesOnceFromVisiblePoliteNode() = toggle(hidden = false, clipped = false)

    @Test fun s2WholeCardShowAnnouncesOnceFromVisiblePoliteNode() = toggle(hidden = true, clipped = false)

    private fun toggle(
        hidden: Boolean,
        clipped: Boolean,
    ) {
        host.launch(fontScale = 2f, hidden = hidden)
        placeCard(clipped)
        assertTrue("进入和滚动不播报金额状态", host.spoken.isEmpty())
        val eye = rule.onNodeWithContentDescription(if (hidden) "显示金额" else "隐藏金额")
        eye.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val viewport = rule.meTag(MeTags.ME_SCROLL).meBounds()
        val eyeBounds = eye.meBounds()
        assertTrue("眼睛完整可见：$eyeBounds / $viewport", eyeBounds.top >= viewport.top && eyeBounds.bottom <= viewport.bottom)
        rule.mainClock.autoAdvance = false
        rule.meTouch(eyeBounds.center)
        assertEquals("真实坐标点击确实改变开关", !hidden, host.mask.hidden.value)
        rule.mainClock.advanceTimeByFrame()
        val message = if (hidden) "金额已显示" else "金额已隐藏"
        val deliveries = host.announcements.sample()
        val carriers = rule.meAccessibilityRegions().filter { it.node.meNoticeText() == message }
        println("EYE clipped=$clipped hidden=$hidden eye=$eyeBounds viewport=$viewport deliveries=$deliveries carriers=$carriers")
        assertEquals("播报承载必须在无障碍事件使用的可见树，实际播报=$deliveries", 1, carriers.size)
        val carrier = carriers.single()
        assertTrue("节点提供器必须确认可见", carrier.visibleToUser)
        assertTrue("可见裁切范围非空", carrier.adjustedBounds.width > 0 && carrier.adjustedBounds.height > 0)
        assertEquals(LiveRegionMode.Polite, carrier.node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, carrier.platformLiveRegion)
        host.announcements.assertSingle(message, LiveRegionMode.Polite)
        repeat(140) {
            rule.mainClock.advanceTimeByFrame()
            host.announcements.sample()
        }
        host.announcements.assertSingle(message, LiveRegionMode.Polite)
        host.assertReads(1)
    }

    private fun placeCard(clipped: Boolean) {
        val card = rule.meTag(MeTags.ME_BALANCE).performScrollTo()
        val scroll = rule.meTag(MeTags.ME_SCROLL)
        val viewport = scroll.meBounds()
        val density = card.meDensity()
        val origin = card.fetchSemanticsNode().positionInRoot
        val targetTop = viewport.top + (if (clipped) -8f else 12f) * density
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, origin.y - targetTop) }
        rule.waitForIdle()
        val node = card.fetchSemanticsNode()
        val actualTop = node.positionInRoot.y
        val actualBottom = actualTop + node.size.height
        println("CARD clipped=$clipped outerTop=$actualTop outerSize=${node.size} clippedBounds=${node.boundsInRoot} viewport=$viewport")
        assertEquals(2f, node.layoutInfo.density.fontScale, 0f)
        assertMeDp(320f, viewport.width, density)
        if (clipped) {
            assertTrue("卡顶 4dp 播报区域完全在视口外：top=$actualTop viewport=$viewport", actualTop + 4 * density < viewport.top)
            assertTrue("卡片确实被裁切", card.meBounds().height < node.size.height)
        } else {
            assertTrue("对照要求整卡可见：$actualTop..$actualBottom / $viewport", actualTop >= viewport.top && actualBottom <= viewport.bottom)
        }
    }
}
