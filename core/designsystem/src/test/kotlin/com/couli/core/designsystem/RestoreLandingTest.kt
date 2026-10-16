package com.couli.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FU-AND-0717 F-X-18 / F-X-20：一次性滚动恢复的落点确认（[RestoreLanding]）——确定性地模拟逐帧布局：数据就绪而布局滞后、从没布局、被底部截住、
 * 布局追上后补发一次定位。旧写法「scrollToItem 返回即结束」相当于第一帧就结束：布局滞后时会在目标还没布局时提前结束（下面第一例）。
 */
class RestoreLandingTest {
    private fun layout(
        first: Int,
        offset: Int = 0,
        laidOut: Int,
        atEnd: Boolean = false,
        visible: IntRange = first..first + 3,
    ) = RestoreLanding.Layout(first, offset, laidOut, atEnd, visible.toSet())

    @Test
    fun fuand0717_x18_layoutLagAfterTheModelIsReadyWaitsThenRescrollsAndConfirmsOnlyOnTheRealLayout() {
        val landing = RestoreLanding(target = 25, offset = 12)
        landing.moved(layout(first = 0, laidOut = 20))
        // 数据已到（40 项），布局还停在 20 项：定位被截住，不算完成
        val lagging = layout(first = 16, laidOut = 20, atEnd = true, visible = 16..19)
        repeat(3) { assertEquals(RestoreLanding.Step.WAIT, landing.onFrame(lagging)) }
        // 布局追上：目标已布局但不在预期位置 → 补发一次定位（不是每帧都发）
        assertEquals(RestoreLanding.Step.RESCROLL, landing.onFrame(layout(first = 16, laidOut = 42, visible = 16..19)))
        landing.moved(layout(first = 16, laidOut = 42, visible = 16..19))
        assertEquals(RestoreLanding.Step.WAIT, landing.onFrame(layout(first = 16, laidOut = 42, visible = 16..19)))
        assertEquals(1, landing.rescrolls)
        // 真实布局里顶部就是目标、偏移对上（容差 1px）：确认
        assertEquals(RestoreLanding.Step.CONFIRMED, landing.onFrame(layout(first = 25, offset = 13, laidOut = 42)))
    }

    @Test
    fun fuand0717_x18_neverLaidOutEndsAsNotConfirmedWithinTheFrameCeiling() {
        val landing = RestoreLanding(target = 25, offset = 0, ceilingFrames = 10)
        landing.moved(layout(first = 0, laidOut = 0, visible = IntRange.EMPTY))
        repeat(9) { assertEquals(RestoreLanding.Step.WAIT, landing.onFrame(layout(first = 0, laidOut = 0, visible = IntRange.EMPTY))) }
        assertEquals(RestoreLanding.Step.NOT_CONFIRMED, landing.onFrame(layout(first = 0, laidOut = 0, visible = IntRange.EMPTY)))
    }

    @Test
    fun fuand0717_x20_aTargetClampedByTheListEndIsConfirmedSoTheCallerCanPageThenAlign() {
        val landing = RestoreLanding(target = 19, offset = 0)
        landing.moved(layout(first = 0, laidOut = 21))
        // 目标在已加载内容末尾：顶不上去、已到底、目标可见 → 落点确认（调用方据「被截住」决定先翻页）
        val clamped = layout(first = 15, offset = 40, laidOut = 21, atEnd = true, visible = 15..20)
        assertEquals(RestoreLanding.Step.CONFIRMED, landing.onFrame(clamped))
    }
}
