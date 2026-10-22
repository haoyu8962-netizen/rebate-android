package com.couli.app.me

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 只改变 fixture；完整未合并树一直有节点，实际无障碍可见树随裁切变化。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class MeVisualAccessibilityTreeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun s2ClippedLiveRegionIsNotCountedUntilItActuallyEntersAccessibilityTree() {
        val probe = MeVisualAnnouncementProbe(rule)
        val offset = mutableStateOf((-8).dp)
        rule.setContent {
            Box(Modifier.size(100.dp).clipToBounds()) {
                Box(
                    Modifier.offset { IntOffset(0, offset.value.roundToPx()) }.size(4.dp).semantics {
                        contentDescription = "裁切节点对照"
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        rule.onAllNodesWithContentDescription("裁切节点对照", useUnmergedTree = true).assertCountEquals(1)
        assertTrue("完整树里的离屏节点不能算一次播报", probe.messages.isEmpty())
        assertTrue(rule.meAccessibilityRegions().none { it.node.meNoticeText() == "裁切节点对照" })
        rule.runOnIdle { offset.value = 0.dp }
        probe.assertSingle("裁切节点对照", LiveRegionMode.Polite)
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            probe.assertSingle("裁切节点对照", LiveRegionMode.Polite)
        }
        rule.runOnIdle { offset.value = (-8).dp }
        assertEquals("离开可见树不制造新播报", 1, probe.sample().size)
        assertTrue(rule.meAccessibilityRegions().none { it.node.meNoticeText() == "裁切节点对照" })
    }

    @Test fun s2PartiallyVisibleRegionStillCountsOnceWithRealAdjustedBounds() {
        val probe = MeVisualAnnouncementProbe(rule)
        rule.setContent {
            Box(Modifier.size(100.dp).clipToBounds()) {
                Box(
                    Modifier.offset { IntOffset(0, (-2).dp.roundToPx()) }.size(4.dp).semantics {
                        contentDescription = "部分可见对照"
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        probe.assertSingle("部分可见对照", LiveRegionMode.Polite)
        val region = rule.meAccessibilityRegions().single { it.node.meNoticeText() == "部分可见对照" }
        assertTrue(region.visibleToUser)
        assertTrue(region.adjustedBounds.height > 0)
        assertTrue("不把完整布局尺寸当可见范围", region.adjustedBounds.height < region.node.layoutInfo.coordinates.size.height)
    }
}
