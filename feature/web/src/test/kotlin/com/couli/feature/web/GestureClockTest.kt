package com.couli.feature.web

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-04 三端共性第 5 条：只有原生识别出的有效点击才算用户手势；按下、滑动、长按、取消都不算；切换页面清除资格。 */
class GestureClockTest {
    private var clock = 10_000L
    private val gesture = GestureClock(now = { clock }, longPressMs = LONG_PRESS_MS)

    private fun event(
        action: Int,
        x: Float = 10f,
        y: Float = 10f,
    ) = gesture.onTouch(action, x, y)

    @Test
    fun tapCountsForOneSecond() {
        event(MotionEvent.ACTION_DOWN)
        clock += 80
        event(MotionEvent.ACTION_UP)
        assertTrue(gesture.recent())
        clock += 1_001
        assertFalse("expires after one second", gesture.recent())
    }

    @Test
    fun pressAloneDoesNotCount() {
        event(MotionEvent.ACTION_DOWN)
        assertFalse(gesture.recent())
    }

    @Test
    fun swipeDoesNotCount() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE, y = 400f)
        event(MotionEvent.ACTION_UP, y = 400f)
        assertFalse(gesture.recent())
    }

    @Test
    fun longPressDoesNotCount() {
        event(MotionEvent.ACTION_DOWN)
        clock += LONG_PRESS_MS + 1
        event(MotionEvent.ACTION_UP)
        assertFalse(gesture.recent())
    }

    @Test
    fun cancelledTouchDoesNotCount() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_CANCEL)
        event(MotionEvent.ACTION_UP)
        assertFalse(gesture.recent())
    }

    @Test
    fun pageChangeClearsTheGesture() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_UP)
        assertTrue(gesture.recent())
        gesture.clear()
        assertFalse(gesture.recent())
    }

    private companion object {
        const val LONG_PRESS_MS = 400L
    }
}
