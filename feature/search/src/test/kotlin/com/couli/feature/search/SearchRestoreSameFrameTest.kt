package com.couli.feature.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-AND-18（APP-06b R3-05）：恢复任务的让位判定在「项数变化与瞬时语义滚动（ScrollToIndex）合并到同一观测帧、观测时滚动已结束」时
 * 不把用户的新位置当基准抢回；只放行能由布局解释的位置变化。循环式恢复不变（判定是同一个 [RestoreExpectation]）。
 * 撤修复记录见 NATIVE/FU-AND-0607/revert-checks.txt。
 */
class SearchRestoreSameFrameTest {
    @Test
    fun aSemanticJumpMergedWithANewPageInOneFrameIsAForeignMove() {
        // 恢复在等第 2 页：列表还在顶部（提示行 + 20 张卡 + 底部）
        val waiting = ListPosition(index = 0, offset = 0, count = 22, scrolling = false)
        val e = RestoreExpectation(waiting)
        // 读屏 ScrollToIndex 到第 15 项，与第 2 页布局同一帧：项数变了、位置也变了、观测时滚动已结束
        assertTrue("项数变化不能掩盖用户的瞬时滚动", e.drifted(ListPosition(index = 15, offset = 0, count = 42, scrolling = false)))
    }

    @Test
    fun aSemanticJumpMergedWithAShrinkingListIsAForeignMove() {
        val e = RestoreExpectation(ListPosition(index = 3, offset = 40, count = 42, scrolling = false))
        assertTrue("向后跳不是截到末尾", e.drifted(ListPosition(index = 9, offset = 0, count = 30, scrolling = false)))
    }

    /** F1-06：尾部追加量恰等于瞬时滚动距离（序号差 = 项数差、偏移不变），但顶部已是另一项：不是前插，让位。 */
    @Test
    fun f1_06_aTailAppendEqualToTheJumpDistanceIsNotTakenAsAFrontInsert() {
        val e = RestoreExpectation(ListPosition(index = 0, offset = 0, count = 22, scrolling = false, key = "notices"))
        assertTrue(e.drifted(ListPosition(index = 20, offset = 0, count = 42, scrolling = false, key = "tb:19")))
        val mid = RestoreExpectation(ListPosition(index = 3, offset = 12, count = 22, scrolling = false, key = "tb:2"))
        assertTrue("同理：中段也不按项数差放行", mid.drifted(ListPosition(index = 23, offset = 12, count = 42, scrolling = false, key = "tb:22")))
    }

    /** F1-06：列表变短但没到底部（未越界）时向前移动：不是底部截断，让位。 */
    @Test
    fun f1_06_aShrinkWithoutReachingTheEndAndAForwardMoveIsNotAClamp() {
        val e = RestoreExpectation(ListPosition(index = 12, offset = 30, count = 42, scrolling = false, key = "tb:11"))
        assertTrue(e.drifted(ListPosition(index = 4, offset = 0, count = 30, scrolling = false, key = "tb:3", atEnd = false)))
    }

    @Test
    fun explainableLayoutShiftsStillRebase() {
        // 追加一页：位置不变
        val append = RestoreExpectation(ListPosition(index = 5, offset = 30, count = 22, scrolling = false))
        assertFalse(append.drifted(ListPosition(index = 5, offset = 30, count = 42, scrolling = false)))
        assertFalse("基准已换", append.drifted(ListPosition(index = 5, offset = 30, count = 42, scrolling = false)))
        // 前面多了一行（按 key 保持同一项在原处）：顶部可见项还是同一个 key、偏移不变，只是序号位移
        val insert = RestoreExpectation(ListPosition(index = 5, offset = 30, count = 22, scrolling = false, key = "tb:4"))
        assertFalse(insert.drifted(ListPosition(index = 6, offset = 30, count = 23, scrolling = false, key = "tb:4")))
        // 列表变短：越界的位置被截到末尾（此刻已在真实底部）
        val clamp = RestoreExpectation(ListPosition(index = 30, offset = 10, count = 42, scrolling = false))
        assertFalse(clamp.drifted(ListPosition(index = 18, offset = 0, count = 22, scrolling = false, atEnd = true)))
        // 在顶部、能证明为零高度的前置项（没有内容的提示行）增减：仍在顶部
        val top = RestoreExpectation(ListPosition(index = 0, offset = 0, count = 2, scrolling = false, leading = 1, zeroLeading = 1))
        assertFalse(top.drifted(ListPosition(index = 1, offset = 0, count = 22, scrolling = false, leading = 1, zeroLeading = 1)))
        // 顶部可见项 key 没变（零高度证据之外的另一种证明）
        val sameKey = RestoreExpectation(ListPosition(index = 0, offset = 0, count = 2, scrolling = false, key = "notices"))
        assertFalse(sameKey.drifted(ListPosition(index = 0, offset = 0, count = 8, scrolling = false, leading = 3, key = "notices")))
        // 空态与推荐标题总有高度：跳到它们之后不算「仍在顶部」（F2-03）
        val empty = RestoreExpectation(ListPosition(index = 0, offset = 0, count = 2, scrolling = false, leading = 1, zeroLeading = 1))
        assertTrue(empty.drifted(ListPosition(index = 3, offset = 0, count = 8, scrolling = false, leading = 3, zeroLeading = 1)))
        // 但从顶部跳到前置项之后的卡片不算「仍在顶部」
        val jump = RestoreExpectation(ListPosition(index = 0, offset = 0, count = 2, scrolling = false, leading = 1))
        assertTrue(jump.drifted(ListPosition(index = 4, offset = 0, count = 22, scrolling = false, leading = 1)))
    }

    /**
     * F2-03：首屏有实际高度的缓存降级提示条（index=0、offset=0、key=notices），用户 ScrollToIndex(1) 到第一张卡与第二页到达同一帧
     * （count 22 → 42、观测时滚动已结束）：顶部 key 变了、提示条仍有高度（不能证明前置项零高度），不算「仍在顶部」，让位。
     */
    @Test
    fun f2_03_aJumpPastANoticeBarWithHeightMergedWithATailAppendIsAForeignMove() {
        val e =
            RestoreExpectation(
                ListPosition(index = 0, offset = 0, count = 22, scrolling = false, leading = 1, key = "notices", zeroLeading = 0),
            )
        assertTrue(
            "有高度的提示条之后的第一张卡不是顶部",
            e.drifted(ListPosition(index = 1, offset = 0, count = 42, scrolling = false, leading = 1, key = "tb:0", zeroLeading = 0)),
        )
        // 同一跳动，前一观测提示条为零高度、此刻提示条有了内容：此刻也要证明，同样让位
        val appeared =
            RestoreExpectation(
                ListPosition(index = 0, offset = 0, count = 22, scrolling = false, leading = 1, key = "notices", zeroLeading = 1),
            )
        assertTrue(
            appeared.drifted(
                ListPosition(index = 1, offset = 0, count = 42, scrolling = false, leading = 1, key = "tb:0", zeroLeading = 0),
            ),
        )
    }
}
