package com.couli.core.router

/** 强更检查的进度：比 [UpdateState] 多一个「检查中」（03 §4.4「统一入口守卫」表）。 */
enum class UpdateCheck {
    CHECKING,
    OK,
    BELOW_MIN,
    BELOW_RECOMMENDED,
    UNKNOWN,
    ;

    /** 排队器交出意图时用的状态：「检查中」只会在等满超时后交出，按 unknown。 */
    fun toUpdateState(): UpdateState =
        when (this) {
            OK -> UpdateState.OK
            BELOW_MIN -> UpdateState.BELOW_MIN
            BELOW_RECOMMENDED -> UpdateState.BELOW_RECOMMENDED
            CHECKING, UNKNOWN -> UpdateState.UNKNOWN
        }

    /**
     * 实时交给守卫的状态（App 内操作、桥、前置完成后的恢复）：「检查中」原样交出，守卫对外部与自动来源返回
     * defer（update_checking），App 内操作不等（F-ALL-03）。
     */
    fun toLiveUpdateState(): UpdateState = if (this == CHECKING) UpdateState.CHECKING else toUpdateState()
}

/** 启动意图：深链（已解析的请求）或推送（只记消息编号）。只放内存，不落盘（03 §4.4「冷启动时的深链与推送」）。 */
sealed interface StartupIntent {
    data class Link(
        val request: RouteRequest,
    ) : StartupIntent

    data class Push(
        val messageId: String,
    ) : StartupIntent

    /**
     * 恢复本地持久化的 pending_action（BR-ID-10「冷启动时读取」，R2-02）：冷启动、以及前置条件（会话、隐私、强更、环境）
     * 变化后排一条，与深链、推送走同一套就绪判定与强更等待；队列里同时最多一条。
     */
    data object ResumePending : StartupIntent
}

/**
 * 交出意图前要看的几件事（状态源可注入：隐私闸门 02b、强更检查 02c 接入前由调用方给占位值）。
 * @property shellReady Tab 等外壳就绪（02b 接入；之前恒为 true）。
 * @property consentPromptShowing 首启弹窗 / 二次说明正在显示（02b；含基本模式下补同意期间，此时 [privacy] 仍是 BASIC_MODE）：
 *   运行中收到的深链与推送丢弃，已排队的意图等弹窗有结论再交出（03 §4.4，R1-05）。
 */
data class StartupReadiness(
    val environmentSettled: Boolean,
    val privacy: PrivacyState,
    val update: UpdateCheck,
    val shellReady: Boolean = true,
    val consentPromptShowing: Boolean = false,
)

/** 放行的意图与这次应当使用的强更状态（等待超时的「检查中」已改为 unknown）。 */
data class ReleasedIntent(
    val intent: StartupIntent,
    val update: UpdateState,
)

/**
 * 启动意图排队器（03 §4.4「冷启动时的深链与推送」）：按「环境闸门 settled → 隐私闸门有结论 → 强更检查有结果（或等满
 * [updateTimeoutMillis] 按 unknown）→ 外壳就绪」的顺序，全部满足才把意图交出去（之后由调用方推送回查、走 Router）。
 *
 * - 冷启动带来的意图排队等待；
 * - App 已在运行时收到的深链或推送，若此时正停在首启弹窗（隐私未有结论，或基本模式下补同意的弹窗正显示）
 *   或 ForceUpdate（低于最低版本），丢弃；恢复 pending_action 例外：低于最低版本时照样排队并交出，由守卫给出
 *   ForceUpdate 并删除持久化的待办（followups F-AND-07），不能被热启动丢弃规则吞掉而留下过期记录；
 * - 强更等待的 5 秒从「环境 settled、隐私有结论」且强更仍在检查时**第一次**被 [offer] / [drain] 观察到的时刻起算
 *   （R1-06、R2-03）：环境与隐私的等待不计入；外壳是否就绪**不影响**计时，只决定能否交出——外壳晚于 5 秒就绪时，
 *   就绪那一刻直接按 unknown 交出。整个队列共用一个起点；强更检查有了结果即清零（之后再次进入「检查中」重新起算）；
 * - 闸门未通过（或外壳未就绪）时 [nextDeadline] 为 null：调用方只在状态变化时再 [drain]，不按过期时刻空转（R1-07）。
 *
 * 纯逻辑，时间由调用方传入；调用方在状态变化与定时器到点时调 [drain]。非线程安全，在主线程使用。
 * TODO(N-14): GuardState 没有 checking 与冷热启动（rule-gaps G11），这些只在本排队器里体现。
 */
class StartupIntentQueue(
    private val updateTimeoutMillis: Long = UPDATE_WAIT_MILLIS,
) {
    private val entries = ArrayDeque<StartupIntent>()

    /** 强更等待的起点：环境与隐私闸门已通过、强更仍在检查时第一次观察到的时刻；还没开始或检查已有结果为 null。 */
    private var updateWaitStartedAtMillis: Long? = null

    val size: Int get() = entries.size

    /** 收下一个意图；被丢弃时返回 false。 */
    fun offer(
        intent: StartupIntent,
        coldStart: Boolean,
        readiness: StartupReadiness,
        now: Long,
    ): Boolean {
        observe(readiness, now)
        val onBlockingScreen =
            readiness.privacy == PrivacyState.NOT_DECIDED ||
                readiness.consentPromptShowing ||
                readiness.update == UpdateCheck.BELOW_MIN
        // F-AND-07：below_min 时 ResumePending 不套热启动丢弃规则，交给 Router.resumePending 让守卫删除持久化待办
        val resumeOnForceUpdate = intent == StartupIntent.ResumePending && readiness.update == UpdateCheck.BELOW_MIN
        if (!coldStart && onBlockingScreen && !resumeOnForceUpdate) return false
        // 恢复 pending_action 只需排一条：已在队里的那条交出时读到的就是最新记录
        val duplicateResume = intent == StartupIntent.ResumePending && intent in entries
        if (!duplicateResume) entries.addLast(intent)
        return true
    }

    /** 记录或清零强更等待的起点（与外壳是否就绪无关，R2-03）。 */
    private fun observe(
        readiness: StartupReadiness,
        now: Long,
    ) {
        if (readiness.update != UpdateCheck.CHECKING) {
            updateWaitStartedAtMillis = null
        } else if (readiness.preGatesOpen && updateWaitStartedAtMillis == null) {
            updateWaitStartedAtMillis = now
        }
    }

    private fun updateWaitExpired(
        readiness: StartupReadiness,
        now: Long,
    ): Boolean {
        val started = updateWaitStartedAtMillis
        return readiness.update != UpdateCheck.CHECKING || (started != null && now - started >= updateTimeoutMillis)
    }

    /** 交出已满足条件的意图（按收到的顺序）；不满足的继续排队。 */
    fun drain(
        readiness: StartupReadiness,
        now: Long,
    ): List<ReleasedIntent> {
        observe(readiness, now)
        if (!readiness.gatesOpen || !updateWaitExpired(readiness, now)) return emptyList()
        val released = entries.map { ReleasedIntent(it, readiness.update.toUpdateState()) }
        entries.clear()
        return released
    }

    /**
     * 下一次需要因强更等待超时而 [drain] 的时刻。只有「闸门已通过、外壳已就绪、强更仍在检查、队里有意图」时才有；
     * 其余情况（闸门未通过、外壳未就绪、没有意图、检查已有结果）返回 null：等状态变化再 [drain]。
     */
    fun nextDeadline(readiness: StartupReadiness): Long? {
        if (!readiness.gatesOpen || readiness.update != UpdateCheck.CHECKING || entries.isEmpty()) return null
        return updateWaitStartedAtMillis?.let { it + updateTimeoutMillis }
    }

    fun clear() = entries.clear()

    /** 环境与隐私闸门已通过（强更等待从此起算）。 */
    private val StartupReadiness.preGatesOpen: Boolean
        get() = environmentSettled && privacy != PrivacyState.NOT_DECIDED

    /** 可以交出意图：前置闸门通过、外壳就绪、没有隐私弹窗挡在上面。 */
    private val StartupReadiness.gatesOpen: Boolean
        get() = preGatesOpen && shellReady && !consentPromptShowing

    companion object {
        /** 启动意图等强更检查最长 5 秒（03 §4.4，客户端常量）。 */
        const val UPDATE_WAIT_MILLIS = 5_000L
    }
}
