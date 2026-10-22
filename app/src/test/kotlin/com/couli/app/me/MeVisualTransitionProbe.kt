package com.couli.app.me

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.couli.feature.me.MeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/** 不调用 settle / idleUntil 或大步 advanceTimeBy；操作前、同帧、每个后续帧都留证。 */
internal class MeVisualTransitionProbe(
    private val rule: ComposeContentTestRule,
    private val active: List<String>,
    private val identity: () -> String,
) {
    private data class Frame(
        val time: Long,
        val phase: String,
        val identity: String,
        val visible: List<String>,
        val newActive: List<String>,
    )

    fun check(
        copied: String,
        finished: () -> Boolean,
        action: () -> Unit,
    ) {
        assertFalse("逐帧检查不能由等待自动推进时钟", rule.mainClock.autoAdvance)
        val beforeTime = rule.mainClock.currentTime
        val beforeActive = active.toList()
        val beforeVisible = visible()
        assertEquals("操作前正在显示刷新失败", 1, beforeVisible.size)
        assertTrue(beforeVisible.single().contains("a1b2c3"))
        assertFalse("排队复制尚未展示", beforeVisible.any { copied in it })
        assertFalse("排队复制尚未播报", copied in beforeActive)
        val frames = mutableListOf<Frame>()

        fun sample(phase: String) {
            frames += Frame(rule.mainClock.currentTime, phase, identity(), visible(), active.drop(beforeActive.size))
        }
        sample("before-action")
        rule.runOnUiThread(action)
        sample("same-frame-after-action")
        shadowOf(Looper.getMainLooper()).idle()
        sample("same-frame-after-main-loop")
        assertEquals("首帧检查前禁止偷跑时钟", beforeTime, rule.mainClock.currentTime)
        val deadline = System.nanoTime() + 30_000_000_000L
        var boundary: Long? = null
        while (System.nanoTime() < deadline && (boundary == null || rule.mainClock.currentTime - boundary < 4_500)) {
            if (finished() && boundary == null) boundary = rule.mainClock.currentTime
            rule.mainClock.advanceTimeByFrame()
            sample("after-frame")
            shadowOf(Looper.getMainLooper()).idle()
            sample("after-main-loop")
            Thread.sleep(5) // 让真实 OkHttp 回调有机会完成，不推进 Compose 时钟。
        }
        assertTrue("真实入口必须完成身份切换", finished())
        assertTrue("边界后逐帧覆盖两条提示的总寿命", boundary != null && rule.mainClock.currentTime - boundary >= 4_500)
        val leaks = frames.filter { it.visible.any { text -> copied in text } || it.newActive.isNotEmpty() }
        assertTrue("操作前基线=$beforeActive / $beforeVisible；违例采样=${leaks.size}（newActive 为操作后的累计回调），前六项=${leaks.take(6)}", leaks.isEmpty())
        assertEquals("保留原有最终播报次数断言，基线在操作前", beforeActive, active)
        assertEquals("最终没有残留轻提示", emptyList<String>(), visible())
        println("S1 frames=${frames.size}; first=${frames.first()}; sameFrame=${frames[1]}; last=${frames.last()}")
    }

    private fun visible(): List<String> =
        rule
            .onAllNodesWithTag(MeTags.TOAST, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.meNoticeText() }
}
