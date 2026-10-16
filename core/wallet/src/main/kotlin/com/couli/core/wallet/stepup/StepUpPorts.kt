package com.couli.core.wallet.stepup

import com.couli.core.network.PageApi
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.WalletTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 只含会话身份的读取（[FormOwner.current] 比较用）：当前登录世代与账号。 */
interface SessionIdentitySource {
    fun sessionEpoch(): Long

    /** 当前有效会话的账号（会话读不出为 null）。 */
    fun account(): String?

    /**
     * 一份固定的身份快照（F-X-17 / F-X-19 补充二，FU-AND-0717）：已知与否、账号、会话世代与持久化登录世代（缓存 / 草稿分区）同一次取得。缺省（旧单测）
     * 按 [account] 与 [sessionEpoch] 拼出、没有分区世代。
     */
    fun identitySnapshot(): com.couli.core.network.SessionIdentity {
        // 先取登录世代、再读账号（与 ApiClient.sessionIdentity 的「读取前世代」同一顺序）
        val epoch = sessionEpoch()
        return com.couli.core.network
            .SessionIdentity(known = true, account = account(), loginEpoch = epoch, partition = null)
    }

    /**
     * 此刻的会话就是 [account] 在登录世代 [epoch] 的那一次登录（FU-AND-0717 第 3 轮整体重理）：只读**一份**快照（[identitySnapshot]），不分别读世代与账号；
     * 身份暂时未知按不一致（只拦这次写入 / 展示，不清、不重绑）。
     */
    fun ownedBy(
        account: String?,
        epoch: Long,
    ): Boolean = identitySnapshot().isOwnedBy(account, epoch)

    /**
     * 快照里的持久化登录世代是否接了来源（末轮窄修 R3-02）：true 时分区为 null 即「分区未知」——按分区绑定的本机草稿延后读写、首次确认后再决策；
     * false（没接来源的旧接线、旧单测）按账号绑定（旧口径，与 OrderRestoreStore 的 Unbound 相同）。
     */
    fun partitionTracked(): Boolean = false
}

/**
 * 二次验证面板要的环境（APP-13a §2 第 7 项、brief §3 Android：从钱包模块挪到 core 共用；钱包的 WalletEnv、「我的」的账号流程各自实现）。都在主线程调用。
 */
interface StepUpEnv : SessionIdentitySource {
    /** 二次验证发码用的手机号（APP-09 G31、APP-13a G02）：本机有当前账号、并且绑定了账号归属的号码才给；没有为 null（不编造，不让用户另填）。 */
    fun stepUpPhone(account: String): String?

    /** 10405：强制进入已有的强更分流（服务端已判定）。 */
    fun forceUpdate()

    /** 二次验证等敏感浮层出现 / 关闭（剪贴板等迟到结果只丢弃，APP-07 第 4 条）。 */
    fun sensitiveOverlay(showing: Boolean) = Unit

    /** 当前会话作用域（full / deletion_only，取不到按 full）：受限会话下只允许 account_deletion（BR-ID-01 细则「受限会话」）。 */
    fun sessionScope(): String = SCOPE_FULL

    companion object {
        const val SCOPE_FULL = "full"
        const val SCOPE_DELETION_ONLY = "deletion_only"
    }
}

/**
 * 二次验证面板的共用依赖（钱包 WalletDeps.stepUpDeps 与账号流程各给一份；sms、tokens 在 App 里同一份）：
 * [smsGate] 是发码 42901 的会话级汇总观察值（APP-09 既有钱包 action 仍用它做发送前闸门，见 [StepUpController]）；[verifyGate] 是验证的禁用期
 * （与发码分开，APP-13a 共性第 26 条）；发码按「账号 + 号码 + action」的截止时间与限流在 [sms]。
 */
@Suppress("LongParameterList") // 依赖逐项给
class StepUpDeps(
    val api: PageApi,
    val env: StepUpEnv,
    val smsGate: WalletRateGate,
    val verifyGate: WalletRateGate,
    val sms: SmsDeadlines,
    val tokens: StepUpTokenCache,
    val clock: () -> Long,
    /** 会话级执行作用域（发码不随面板 / 页面关闭取消，APP-09 共性第 40 条）。 */
    val service: CoroutineScope,
    val texts: WalletTexts = WalletDraftTexts,
    /**
     * token 失效世代的读取（APP-13a R3-01、共性第 43 条）：验证请求发起时记下，回包写缓存、交付 onToken 之前复核，变了就丢弃。缺省只看 [tokens] 的整体
     * 清空；「我的」账号流程另并入 bindPhone 成功的共享失效世代（AccountShared.tokenGeneration）。
     */
    val tokenGeneration: () -> Long = { tokens.generation },
)

/** 面板里的一条提示（文案键 + trace；[id] 每次换新，读屏重新播报）。 */
data class Notice(
    val key: String,
    val id: Long,
    val traceId: String? = null,
    val showTrace6: Boolean = false,
    val assertive: Boolean = true,
)

/**
 * 表单与渲染快照所属的账号与登录世代（APP-09 共性第 52 条，Android R3-01）：页面建立表单（进入、会话换代后重建）时绑定，渲染时随快照交给按钮回调；
 * 提交、保存、确认、放弃在生成键、读取 step-up token 之前核对它仍是当前会话。不取「点击执行时的当前会话」。
 */
data class FormOwner(
    val account: String?,
    val epoch: Long,
) {
    /** 表单所属的会话仍是当前会话（账号可读且一致、世代相同；同一账号重新登录也算换代）。 */
    fun current(env: SessionIdentitySource): Boolean = env.ownedBy(account, epoch)

    /**
     * 与一份新快照比较（FU-AND-0717 第 3 轮整体重理，[com.couli.core.network.SessionIdentity.verdictFor]，不看分区）：未知 / 同一 / 未知期取得的表单第一次确认
     * （接管）/ 已确认换代。
     */
    fun verdict(identity: com.couli.core.network.SessionIdentity): com.couli.core.network.IdentityVerdict =
        identity.verdictFor(account, epoch, null, trackPartition = false)

    companion object {
        /**
         * 构造（或会话换代通知里重建）表单、页面执行器时**一次性**取得固定身份（APP-09 共性第 65 条，Android RC-06）：只允许在这两个时刻调用。
         */
        fun of(env: SessionIdentitySource): FormOwner = of(env.identitySnapshot())

        /** 从一份身份快照取（账号与世代同一次读取，F-X-19 补充二）。 */
        fun of(identity: com.couli.core.network.SessionIdentity): FormOwner = FormOwner(identity.account, identity.loginEpoch)
    }
}

/**
 * 二次验证发码的截止时间、42901 限流与在途占用（APP-09 共性第 6、17 条，APP-13a 共性第 15、20 条）：按账号、规范化号码与 action 隔离（同一把键）；
 * 重开面板恢复倒计时与限流，每次实际发送前检查；同一槽位同时只允许一个发码请求在途。只在内存（进程内），会话换代时清空。
 */
open class SmsDeadlines {
    /** 一个发码槽位：账号、号码（只取数字）、action。 */
    data class Slot(
        override val account: String,
        override val phone: String,
        override val action: String,
    ) : SmsSlotKey

    private val until = mutableMapOf<String, Long>()
    private val limited = mutableMapOf<String, Long>()

    /** 在途占用：槽位 → 占用它的那次请求（[acquire] 的票据）；释放只认同一张票据（共性第 40 条）。 */
    private val sending = mutableMapOf<String, Long>()
    private var tickets = 0L
    private val version = MutableStateFlow(0L)

    /** 截止时间、限流或在途占用变化（等别的面板发完、限流到期刷新）。 */
    val changes: StateFlow<Long> = version

    @Synchronized
    fun remaining(
        slot: SmsSlotKey,
        now: Long,
    ): Long = ((until[slot.id] ?: 0L) - now).coerceAtLeast(0L)

    @Synchronized
    fun start(
        slot: SmsSlotKey,
        deadline: Long,
    ) {
        until[slot.id] = deadline
        version.value += 1
    }

    /** 这个槽位收到 42901：禁用到 [deadline]（同一槽位的成功倒计时与限流同一把键，APP-13a 共性第 15 条）。 */
    @Synchronized
    fun rateLimit(
        slot: SmsSlotKey,
        deadline: Long,
    ) {
        limited[slot.id] = maxOf(limited[slot.id] ?: 0L, deadline)
        version.value += 1
    }

    /** 这个槽位的 42901 剩余禁用毫秒（0 = 不在限流）。 */
    @Synchronized
    fun limitedLeft(
        slot: SmsSlotKey,
        now: Long,
    ): Long = ((limited[slot.id] ?: 0L) - now).coerceAtLeast(0L)

    /** 占用槽位：返回这次请求的票据；已有在途发码返回 null。 */
    @Synchronized
    fun acquire(slot: SmsSlotKey): Long? {
        if (slot.id in sending) return null
        val ticket = ++tickets
        sending[slot.id] = ticket
        version.value += 1
        return ticket
    }

    /** 释放本次请求的占用：槽位已被清空（会话换代）或换成别的请求占用的，不动它。 */
    @Synchronized
    fun release(
        slot: SmsSlotKey,
        ticket: Long,
    ) {
        if (sending[slot.id] == ticket) {
            sending.remove(slot.id)
            version.value += 1
        }
    }

    @Synchronized
    fun busy(slot: SmsSlotKey): Boolean = slot.id in sending

    @Synchronized
    fun clear() {
        until.clear()
        limited.clear()
        sending.clear()
        version.value += 1
    }
}

/** 发码槽位的键（账号 + 号码 + action）：同值的槽位（不论哪一层建的）共用一份截止时间、限流与占用。 */
interface SmsSlotKey {
    val account: String
    val phone: String
    val action: String
}

/** 槽位键：号码只取数字。 */
internal val SmsSlotKey.id: String get() = "$account|${phone.filter(Char::isDigit)}|$action"

/** 面板与半屏的测试标记（与钱包 WalletTags 的同名常量同值）。 */
object StepUpTags {
    const val STEP_UP_SHEET = "step_up_sheet"
    const val STEP_UP_CODE = "step_up_code"
    const val STEP_UP_SUBMIT = "step_up_submit"
    const val STEP_UP_CLOSE = "step_up_close"
    const val STEP_UP_RESEND = "step_up_resend"
    const val STEP_UP_ERROR = "step_up_error"
    const val STEP_UP_NO_PHONE = "step_up_no_phone"
    const val STEP_UP_SCOPE_DENIED = "step_up_scope_denied"
    const val SHEET_MIDDLE = "sheet_middle"
    const val SHEET_FALLBACK = "sheet_fallback"
}
