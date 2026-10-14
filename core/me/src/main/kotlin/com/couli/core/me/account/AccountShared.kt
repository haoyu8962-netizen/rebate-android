package com.couli.core.me.account

import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一个未决键的结算通知（APP-13a 共性第 18、24 条，鸿蒙 G68）：提交、确认、放弃按固定身份结算之后发一次；打开着的同操作页面（换号页、BindPhone 页、
 * 注销页）收到后按存储重新同步，并按 [nextStateRaw] 落地（abandoned / 20903 → 换号页回第 1 步；done → 结果页，号码取 [summary]）。
 * [key] 为 null 表示只是「请按存储重新同步」（在途状态变化、超时变 unknown）。
 */
data class PendingSettlement(
    val account: String,
    val epoch: Long,
    val operationId: String,
    val key: String?,
    val nextStateRaw: String? = null,
    val textKey: String? = null,
    /** 实际发出请求的固定脱敏摘要（换号新号；注销为 null）。 */
    val summary: String? = null,
    /**
     * bindPhone 的完整终结结局（APP-13a RC-01、共性第 47 条）：恢复了同一键待确认的其他页面实例按它落地（终结业务错误 → 恢复第 2 步、错误提示、字段保留策略），
     * 不只凭 [nextStateRaw] 处理 STEP1 / DONE。注销与只要求重新同步的通知为 null。
     */
    val outcome: PhoneChangeOutcome? = null,
    /** 结局回包的 trace_id（错误提示的 trace6 用）；没有为 null。 */
    val traceId: String? = null,
)

/** 按字符串键的截止时间（bind 发码按「账号 + 新号 + purpose」，共性第 15、20 条）；只在内存，会话换代时清空。 */
class KeyedDeadlines(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val until = mutableMapOf<String, Long>()
    private val version = MutableStateFlow(0L)

    /** 截止时间变化（页面订阅后按自己的键重算剩余时间并安排到期刷新）。 */
    val changes: StateFlow<Long> = version.asStateFlow()

    @Synchronized
    fun remaining(key: String): Long = ((until[key] ?: 0L) - clock()).coerceAtLeast(0L)

    @Synchronized
    fun start(
        key: String,
        millis: Long,
    ) {
        if (millis <= 0) return
        until[key] = maxOf(until[key] ?: 0L, clock() + millis)
        version.value += 1
    }

    @Synchronized
    fun clear() {
        until.clear()
        version.value += 1
    }

    companion object {
        /**
         * bind 发码的键：账号 + 新号的规范化结果 + purpose（APP-13a R1-13、共性第 30 条）：国家码、空格、连字符、全角统一后同一号码只有一把键，
         * 与服务端规范化一致；规范化不出（不是合法号码）时退回只取数字。请求体仍按契约保留原始输入。
         */
        fun bindKey(
            account: String,
            phone: String,
            purpose: String,
        ): String = account + "|" + (PhoneMask.normalized(phone) ?: phone.filter(Char::isDigit)) + "|" + purpose
    }
}

/**
 * bindPhone 成功的共享结算出口（共性第 9、13、25 条，编排者补充 5）：App 注入。[clearAllStepUpTokens] 清全部 action 的 step_up_token；
 * [refreshMe] 经统一 getMe 结算（共享读闸门）刷新 phone_bound；[onPhoneBound] 更新会话侧的绑定状态（守卫重算、释放暂缓）。
 * 都按固定身份（账号 + 登录世代）调用，实现不得再读当前会话来取身份。
 */
interface BindPhoneSettlePorts {
    fun clearAllStepUpTokens()

    fun refreshMe(
        account: String,
        epoch: Long,
    )

    fun onPhoneBound(
        account: String,
        epoch: Long,
    ) = Unit
}

/**
 * 换号、注销、BindPhone 的会话级共享状态（App 建一份；feature/me 与 feature/login 共用，Feature 之间不互相依赖）：
 * - [phoneGate]：bindPhone 的提交、确认、放弃共用一个 42901 截止时间（BindPhone 与换号两页同一份，共性第 8 条）；
 * - [deletionGate]：注销的申请、确认、放弃共用一个；[cancelGate]：撤回注销一个。验证（stepUp）的截止时间不在这里：换号第 1 步与所有二次验证面板
 *   共用 StepUpDeps.verifyGate 那一份（R1-14、共性第 31 条；与发码分开，共性第 26 条）；
 * - [bindSms]：bind 发码按「账号 + 新号 + purpose」的截止时间（成功倒计时与 42901 同一把键，共性第 15 条）；
 * - [pendingSignals]：未决结算通知（共性第 18、24 条）。
 * [isCurrent] 是 App 给的「这个账号 + 登录世代仍是当前会话」核对；共享结算只在核对通过后执行。只在主线程使用。
 */
class AccountShared(
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCurrent: (account: String, epoch: Long) -> Boolean,
) {
    val phoneGate = WalletRateGate(scope, clock)
    val deletionGate = WalletRateGate(scope, clock)
    val cancelGate = WalletRateGate(scope, clock)
    val bindSms = KeyedDeadlines(clock)

    /** 共享结算出口（App 接线后设置；没接时成功结算只发通知）。 */
    var ports: BindPhoneSettlePorts? = null

    /**
     * step_up_token 的失效世代（APP-13a R2-05，共性第 39 条）：bindPhone 成功的共享结算（任一页面、任一实例）、会话换代、切换环境、撤回同意时加 1。
     * 流程私有保存的 token 记下取得时的世代，世代变了即失效（不只清共享缓存），保证同账号同会话的全部私有 token 同步作废。
     */
    @Volatile
    var tokenGeneration: Long = 0L
        private set

    private val mutable = MutableSharedFlow<PendingSettlement>(extraBufferCapacity = BUFFER)
    val pendingSignals: SharedFlow<PendingSettlement> = mutable

    fun notifyPending(settlement: PendingSettlement) {
        mutable.tryEmit(settlement)
    }

    /**
     * bindPhone 成功（首次绑定、换号、确认、放弃得到 completed+0）的共享结算：先核对固定身份，再清全部 token、刷新 getMe、更新绑定状态，最后通知页面。
     * 身份已不是当前会话（迟到的旧会话结果）只通知（页面按存储同步），不改共享状态。返回是否执行了结算。
     */
    @Suppress("LongParameterList") // 固定身份、键、摘要与落地状态逐项
    fun onBindPhoneSucceeded(
        account: String,
        epoch: Long,
        key: String?,
        summary: String?,
        nextStateRaw: String = PhoneChangeOutcome.DONE,
        textKey: String? = null,
    ): Boolean {
        val current = isCurrent(account, epoch)
        if (current) {
            tokenGeneration += 1
            ports?.let {
                it.clearAllStepUpTokens()
                it.onPhoneBound(account, epoch)
                it.refreshMe(account, epoch)
            }
        }
        notifyPending(PendingSettlement(account, epoch, BindPhonePendingPolicy.OPERATION, key, nextStateRaw, textKey, summary))
        return current
    }

    /** 会话换代、切换环境、撤回同意：清空所有截止时间（未决记录按账号保留，不在这里）。 */
    fun reset() {
        tokenGeneration += 1
        listOf(phoneGate, deletionGate, cancelGate).forEach(WalletRateGate::reset)
        bindSms.clear()
    }

    private companion object {
        const val BUFFER = 32
    }
}
