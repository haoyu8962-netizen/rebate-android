package com.couli.core.designsystem

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/**
 * 一次性滚动恢复的「落点确认」（F-X-18 / 补充、F-X-20，FU-AND-0717；iOS FU-IOS-0712 R1-06、鸿蒙 FU-HM-0723 R1-02 / R1-03 同法）：滚动恢复任务发出
 * scrollToItem 之后**不在下一次回调无条件结束**，逐帧看列表真实布局：
 * - 已布局的项数还没覆盖目标（数据就绪、布局滞后）：等，布局追上后再发一次同样的定位（计入 [rescrolls]）；
 * - 顶部可见项就是目标且偏移对上（容差 [TOLERANCE_PX]），或列表已到真实底部、目标在视口里（被底部截住，调用方可另行翻页）：确认（[Step.CONFIRMED]）；
 * - 目标已布局、却不在预期位置、且自上次定位以来布局有变化：再发一次定位（[Step.RESCROLL]）；
 * - 到帧上限 [ceilingFrames] 仍没确认：按没确认结束（[Step.NOT_CONFIRMED]），不挂死。
 * 用户接管（拖动、惯性、读屏滚动、键盘 / 焦点移动、同卡片内的小幅滚动）由调用方的接管判定负责：接管后调用方不再调 [onFrame]，任务随之结束，之后的布局
 * 回调不再下发定位。纯逻辑（不碰 Compose），便于确定性测试；Compose 侧的逐帧驱动见 [awaitLanding]。
 */
class RestoreLanding(
    private val target: Int,
    private val offset: Int,
    private val ceilingFrames: Int = CEILING_FRAMES,
) {
    /** 一帧的结论。 */
    enum class Step {
        WAIT,
        RESCROLL,
        CONFIRMED,
        NOT_CONFIRMED,
    }

    /** 一帧观测到的列表布局。 */
    data class Layout(
        val firstIndex: Int,
        val firstOffset: Int,
        val laidOut: Int,
        val atEnd: Boolean,
        val visible: Set<Int>,
    )

    private var frames = 0
    private var lastAttempt: Layout? = null

    /** 发出过几次补发定位（不含第一次）。 */
    var rescrolls: Int = 0
        private set

    /** 发出定位时调用（记下那一刻的布局，用来判断之后布局有没有变化）。 */
    fun moved(layout: Layout) {
        lastAttempt = layout
    }

    @Suppress("ReturnCount")
    fun onFrame(layout: Layout): Step {
        frames += 1
        if (layout.firstIndex == target && kotlin.math.abs(layout.firstOffset - offset) <= TOLERANCE_PX) return Step.CONFIRMED
        if (layout.atEnd && target in layout.visible) return Step.CONFIRMED
        if (frames >= ceilingFrames) return Step.NOT_CONFIRMED
        // 布局还没覆盖目标：等布局追上
        if (layout.laidOut <= target) return Step.WAIT
        // 目标已布局、位置不对：布局自上次定位以来变过（项数、首项）才补发一次，避免每帧重发
        if (changedSinceLastMove(layout)) {
            rescrolls += 1
            return Step.RESCROLL
        }
        return Step.WAIT
    }

    /** 自上次定位以来布局变过（项数、首项、偏移）；还没定位过也算。 */
    private fun changedSinceLastMove(layout: Layout): Boolean {
        val last = lastAttempt ?: return true
        return last.laidOut != layout.laidOut || last.firstIndex != layout.firstIndex || last.firstOffset != layout.firstOffset
    }

    companion object {
        /** 落点确认的帧上限（约 4 秒 @60Hz）。 */
        const val CEILING_FRAMES = 240

        /** 位置比较的容差（像素）。 */
        const val TOLERANCE_PX = 1

        /** 此刻 [state] 的布局（[RestoreLanding.Layout]）。 */
        fun layoutOf(state: LazyListState): Layout =
            Layout(
                firstIndex = state.firstVisibleItemIndex,
                firstOffset = state.firstVisibleItemScrollOffset,
                laidOut = state.layoutInfo.totalItemsCount,
                atEnd = !state.canScrollForward,
                visible = state.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.index },
            )
    }
}

/**
 * Compose 侧逐帧驱动 [RestoreLanding]：先 [move] 一次，之后每帧观测 [layout]；[stillMine] 为 false（用户接管、页面失效、新查询）立即结束并返回 null，
 * 不再定位；补发时再调 [move]。返回确认结论（[RestoreLanding.Step.CONFIRMED] 或 [RestoreLanding.Step.NOT_CONFIRMED]）。
 */
@Suppress("ReturnCount") // 一开始就失效、中途失效、确认结论三个出口
suspend fun awaitLanding(
    landing: RestoreLanding,
    layout: () -> RestoreLanding.Layout,
    stillMine: () -> Boolean,
    move: suspend () -> Unit,
): RestoreLanding.Step? {
    if (!stillMine()) return null
    landing.moved(layout())
    move()
    while (true) {
        withFrameNanos { }
        if (!stillMine()) return null
        when (val step = landing.onFrame(layout())) {
            RestoreLanding.Step.WAIT -> {
                Unit
            }

            RestoreLanding.Step.RESCROLL -> {
                landing.moved(layout())
                move()
            }

            RestoreLanding.Step.CONFIRMED, RestoreLanding.Step.NOT_CONFIRMED -> {
                return step
            }
        }
    }
}
