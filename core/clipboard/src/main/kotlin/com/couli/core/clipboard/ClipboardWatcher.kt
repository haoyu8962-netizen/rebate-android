package com.couli.core.clipboard

/** 一次自动读取的结果。 */
enum class WatcherReadOutcome {
    /** 读过了（读到文本、非文本或判定不该读而放弃）：本次前台不再读。 */
    DONE,

    /**
     * 已获焦读到 null（剪贴板空、读取失败）：不当成空、不记 hash；但读取前已判定窗口有焦点，这一次也算本次前台的自动读取（A-R1-01），
     * 通知栏下拉收起等普通重新获焦不再读，只有经过后台再回来才重新计。
     */
    UNAVAILABLE,
}

/**
 * Android 的读取时机（brief §2.1、§3；BR-ID-16；规则空白 G13）：
 * - 「一次回前台」只由生命周期事件判定：冷启动进入前台，或真正经过后台（ON_STOP，配置变更重建除外）之后的 ON_START；下拉通知栏、系统弹窗、
 *   分屏后重新获焦不算；App 内页面切换不算，离开敏感页面后不补做；
 * - 开关开启时，在 ON_START 之后第一次 onWindowFocusChanged(true) 读 1 次；之后的失焦再得焦不再读。真正未获焦（判定为 no_focus）时不读、
 *   等本轮第一次获焦；已获焦读到 null 或读取失败也算本轮读过（A-R1-01：一次前台最多一次自动读取）；
 * - 进后台时作废未完成的读取（[round] 换代，调用方据此作废未点击的提示条与在途请求），回来后按新的一次前台重新判定。
 * 只在主线程使用。读取前提由 [state] 现取（隐私、当前路由与浮层、配置开关、网页抑制、设备级开关），判定交 [ClipboardReadPolicy]。
 */
class ClipboardWatcher(
    private val state: () -> ClipboardReadState,
    private val read: (round: Long) -> WatcherReadOutcome,
) {
    /** 前台轮次：每次回前台、进后台都换代。 */
    var round: Long = 0L
        private set

    var inForeground: Boolean = false
        private set

    private var focused = false
    private var readThisForeground = false

    /** 本次前台还在等第一次获得焦点去读。 */
    private var pending = false

    /** 最近一次判定（测试与诊断用；不含剪贴板内容）。 */
    var lastDecision: ClipboardReadDecision? = null
        private set

    /** ON_START（冷启动那次 [cold] = true；配置变更重建不调）。[hasFocus]：此刻窗口是否已有焦点。 */
    fun onForeground(
        cold: Boolean,
        hasFocus: Boolean,
    ) {
        round += 1
        inForeground = true
        readThisForeground = false
        pending = true
        focused = hasFocus
        evaluate(if (cold) ClipboardReadPolicy.EVENT_COLD_START else ClipboardReadPolicy.EVENT_FOREGROUND)
    }

    /** ON_STOP（配置变更重建不调）：作废本次前台的读取。 */
    fun onBackground() {
        round += 1
        inForeground = false
        pending = false
        focused = false
    }

    /** onWindowFocusChanged：回前台后第一次获得焦点时读；之后的失焦再得焦按 refocus 判定（不读）。 */
    fun onWindowFocus(hasFocus: Boolean) {
        focused = hasFocus
        if (!hasFocus || !inForeground) return
        evaluate(if (pending) ClipboardReadPolicy.EVENT_WINDOW_FOCUS else ClipboardReadPolicy.EVENT_REFOCUS)
    }

    private fun evaluate(event: String) {
        val decision =
            ClipboardReadPolicy.decide(
                ClipboardReadPolicy.PLATFORM_ANDROID,
                state().copy(event = event, readThisForeground = readThisForeground, androidWindowFocused = focused),
            )
        lastDecision = decision
        if (decision.actionRaw != ClipboardReadPolicy.ACTION_READ) {
            // 没有焦点：等焦点；其余（敏感页、开关关、已读、不是回前台…）本次前台不再补做
            if (decision.reasonRaw != ClipboardReadPolicy.REASON_NO_FOCUS && event != ClipboardReadPolicy.EVENT_REFOCUS) pending = false
            return
        }
        // 读取前已判定窗口有焦点：不论读到文本、非文本还是 null（UNAVAILABLE 不记 hash），本次前台的自动读取资格都已用掉（A-R1-01）
        read(round)
        readThisForeground = true
        pending = false
    }
}
