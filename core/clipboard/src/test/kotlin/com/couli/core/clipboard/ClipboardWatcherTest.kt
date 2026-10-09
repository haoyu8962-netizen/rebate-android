package com.couli.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 读取时机（brief §2.1、§3 Android；G13）：只有冷启动进入前台、或经过后台再回前台才算一次；ON_START 之后第一次获得焦点读 1 次；未获焦
 * 不读、等获焦；已获焦读到 null 也算读过（A-R1-01）；失焦再得焦不读；敏感页 / 开关关时本次前台不补做；进后台换代。
 */
class ClipboardWatcherTest {
    private var state =
        ClipboardReadState(consent = ClipboardReadPolicy.CONSENT_AGREED, event = ClipboardReadPolicy.EVENT_FOREGROUND, route = "Home")
    private val reads = mutableListOf<Long>()
    private var outcome = WatcherReadOutcome.DONE

    private val watcher =
        ClipboardWatcher(state = { state }) { round ->
            reads += round
            outcome
        }

    @Test
    fun coldStartReadsOnceOnFirstFocus() {
        watcher.onForeground(cold = true, hasFocus = false)
        assertEquals("没有焦点不读", 0, reads.size)
        assertEquals(ClipboardReadPolicy.REASON_NO_FOCUS, watcher.lastDecision?.reasonRaw)
        watcher.onWindowFocus(true)
        assertEquals(1, reads.size)
        // 下拉通知栏、系统弹窗后重新获焦：不算回前台
        watcher.onWindowFocus(false)
        watcher.onWindowFocus(true)
        assertEquals("同一次前台最多读 1 次", 1, reads.size)
    }

    @Test
    fun foregroundWithFocusAlreadyReadsImmediately() {
        watcher.onForeground(cold = false, hasFocus = true)
        assertEquals(1, reads.size)
    }

    @Test
    fun noFocusWaitsButAFocusedNullReadUsesUpThisForeground() {
        outcome = WatcherReadOutcome.UNAVAILABLE
        watcher.onForeground(cold = false, hasFocus = false)
        assertEquals("真正未获焦：不读，等本轮第一次获焦", 0, reads.size)
        watcher.onWindowFocus(true)
        assertEquals(1, reads.size)
        // A-R1-01：已获焦读到 null（空剪贴板）也算本次前台读过；下拉再收起通知栏不再读
        outcome = WatcherReadOutcome.DONE
        repeat(3) {
            watcher.onWindowFocus(false)
            watcher.onWindowFocus(true)
        }
        assertEquals("一次前台最多一次自动读取", 1, reads.size)
        assertEquals(ClipboardReadPolicy.REASON_NOT_FOREGROUND, watcher.lastDecision?.reasonRaw)
        // 真正经过后台再回来：重新计
        watcher.onBackground()
        watcher.onForeground(cold = false, hasFocus = true)
        assertEquals(2, reads.size)
    }

    @Test
    fun backgroundThenForegroundIsANewRound() {
        watcher.onForeground(cold = true, hasFocus = true)
        val first = watcher.round
        watcher.onBackground()
        assertEquals(first + 1, watcher.round)
        watcher.onWindowFocus(true)
        assertEquals("后台里获焦不读", 1, reads.size)
        watcher.onForeground(cold = false, hasFocus = false)
        watcher.onWindowFocus(true)
        assertEquals(2, reads.size)
        assertEquals(listOf(first, first + 2), reads)
    }

    @Test
    fun sensitivePageSkipsWithoutCatchUp() {
        state = state.copy(route = "Withdraw")
        watcher.onForeground(cold = false, hasFocus = false)
        watcher.onWindowFocus(true)
        assertEquals(ClipboardReadPolicy.REASON_SENSITIVE, watcher.lastDecision?.reasonRaw)
        // 离开敏感页回到首页：本次前台不补做
        state = state.copy(route = "Home")
        watcher.onWindowFocus(false)
        watcher.onWindowFocus(true)
        assertEquals(0, reads.size)
    }

    @Test
    fun switchOffAndBasicModeNeverRead() {
        state = state.copy(androidAutoSwitch = false)
        watcher.onForeground(cold = true, hasFocus = true)
        assertEquals(ClipboardReadPolicy.REASON_SWITCH_OFF, watcher.lastDecision?.reasonRaw)
        state = state.copy(androidAutoSwitch = true, consent = ClipboardReadPolicy.CONSENT_BASIC)
        watcher.onBackground()
        watcher.onForeground(cold = false, hasFocus = true)
        assertEquals(ClipboardReadPolicy.REASON_BASIC_MODE, watcher.lastDecision?.reasonRaw)
        assertEquals(0, reads.size)
    }

    @Test
    fun webSuppressionAndConfigOffSkip() {
        state = state.copy(webSuppressed = true)
        watcher.onForeground(cold = false, hasFocus = true)
        assertEquals(ClipboardReadPolicy.REASON_WEB_SUPPRESSED, watcher.lastDecision?.reasonRaw)
        state = state.copy(webSuppressed = false, configEnabled = false)
        watcher.onBackground()
        watcher.onForeground(cold = false, hasFocus = true)
        assertEquals(ClipboardReadPolicy.REASON_CONFIG_OFF, watcher.lastDecision?.reasonRaw)
        assertEquals(0, reads.size)
    }
}
