package com.couli.app.me

import android.view.View
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.couli.core.designsystem.CouliAnnouncer
import org.junit.Assert.assertEquals

/**
 * 两个独立出口分别记账，绝不按文案去重：主动回调 + liveRegion 内容变化。
 * 只统计无障碍事件使用的可见树，且节点提供器确认可见；按节点 id / 内容 / 优先级检测变化。
 * 读取同一帧不会重复计数，移除后再出现仍算新通知；被裁掉但仍在完整未合并树中的节点不计为播报。
 * 这里记录系统语义通知的触发源，不声称 Robolectric 实际运行了 TalkBack。
 */
internal class MeVisualAnnouncementProbe(
    private val rule: ComposeContentTestRule,
) {
    data class Delivery(
        val text: String,
        val channel: String,
        val priority: LiveRegionMode? = null,
    )

    private val deliveries = mutableListOf<Delivery>()
    private var regions = emptyMap<Int, Delivery>()
    val announcer = CouliAnnouncer { deliveries += Delivery(it, "active") }
    val messages get() = sample().map { it.text }

    fun sample(): List<Delivery> {
        val current =
            rule
                .meAccessibilityRegions()
                .filter { it.visibleToUser && it.adjustedBounds.width > 0 && it.adjustedBounds.height > 0 }
                .associate { region ->
                    val node = region.node
                    val priority =
                        when (region.platformLiveRegion) {
                            View.ACCESSIBILITY_LIVE_REGION_POLITE -> LiveRegionMode.Polite
                            View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE -> LiveRegionMode.Assertive
                            else -> null
                        }
                    node.id to Delivery(node.meNoticeText(), "liveRegion", priority)
                }
        current.forEach { (id, delivery) ->
            if (delivery.text.isNotEmpty() && regions[id] != delivery) deliveries += delivery
        }
        regions = current
        return deliveries.toList()
    }

    fun assertSingle(
        text: String,
        priority: LiveRegionMode,
    ) {
        val actual = sample()
        assertEquals("主动回调与系统语义合计恰一次：$actual", listOf(text), actual.map { it.text })
        // 旧 CouliAnnouncer 只有 String，没有优先级参数；不能把未知优先级擅自当成 polite。
        assertEquals("必须能观测到 $priority 优先级：$actual", priority, actual.single().priority)
    }
}

internal fun SemanticsNode.meNoticeText(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        ?: children.joinToString(" ") { it.meNoticeText() }.trim()
