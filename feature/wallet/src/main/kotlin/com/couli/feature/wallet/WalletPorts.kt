package com.couli.feature.wallet

import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WalletStaleCache
import com.couli.core.wallet.WalletTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * 钱包类页面要的网络能力：普通调用经 [PageApi]（带页面归属）；未决键的恢复、确认与作废经 ApiClient 的已有入口（BR-ID-10，03 §4.2「未决键」），
 * 不重写管线。生产为 [ApiClientWalletApi]；测试用 MockWebServer 上的真实 ApiClient。
 */
interface WalletApi : PageApi {
    /** 当前会话账号某个操作的未决记录；存储读不出抛错（不当成没有记录）。 */
    fun pendingFor(operationId: String): PendingKeyRecord?

    /** 冷启动、登录成功、回前台：按当前会话的账号恢复未决记录（只读，不发）。 */
    fun restorePending(): Boolean

    /**
     * 【确认上一笔】：原键、原请求体重发（二次验证之后带 [stepUpToken]）。[target] 是点击时的账号、原键与归属（含发送前闸门），读取、占用与实际发送前
     * 都核对（APP-09 共性第 14、15 条）。
     */
    suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult

    /** 【放弃上一笔】（二次确认之后）：POST /v1/idempotency-keys/abandon；[target] 同 [confirmPending]。 */
    suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult
}

class ApiClientWalletApi(
    private val client: ApiClient,
) : WalletApi,
    PageApi by com.couli.core.network
        .ApiClientPageApi(client) {
    override fun pendingFor(operationId: String): PendingKeyRecord? = client.pendingFor(operationId)

    override fun restorePending(): Boolean = client.restorePending()

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult = withContext(Dispatchers.IO) { client.confirmPending(operationId, stepUpToken, target) }

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult = withContext(Dispatchers.IO) { client.abandonPending(operationId, target) }
}

/**
 * 实名提交（G01）：契约没有实名提交接口，手写的待定 operation submitRealname 只由 Debug 假服务实现；Release 没有实现（null），【提交认证】置灰并显示
 * 「实名认证暂未开放」，不发任何请求，也不调 /v1/consents。TODO(N-32) 契约补上后改为真实接口。
 */
interface RealNameBackend {
    /** 单独同意与实名提交（Debug 假服务里一起发生）；结果按 ApiResult（20001、30405 / 30406 / 30407 / 30410、42901、5xxxx、成功）。 */
    suspend fun submit(
        name: String,
        idNo: String,
        idempotencyKey: String,
    ): ApiResult
}

/** 劳务协议（G02）：取协议与签署走手写的待定 operation，只由 Debug 假服务实现；Release 为 null（全文区「协议正文暂未提供」，【同意并签署】置灰）。 */
interface LaborBackend {
    /** data：{version, text, text_sha256, need_sign}（形状照 04 §6.1；全文按「地址或文本」，假服务给文本）。 */
    suspend fun current(): ApiResult

    /** data：{}；sha256 取服务端返回的值原样回传。 */
    suspend fun sign(
        version: String,
        textSha256: String,
    ): ApiResult
}

/**
 * 钱包类页面要的环境（App 给；都在主线程调用）。请求归属绑定 [sessionEpoch]（登录、退出、换账号都会变）；缓存、未决键与 step-up token 按 [account]
 * 隔离。
 */
@Suppress("TooManyFunctions") // 每项能力一个入口
interface WalletEnv {
    fun loggedIn(): Boolean

    fun sessionEpoch(): Long

    /** 当前有效会话的账号（会话读不出为 null）。 */
    fun account(): String?

    /** /v1/config 的 data（生效配置：当前 → LKG → 包内）。 */
    fun configData(): JsonObject?

    /** 本端版本（与 routes.json since.android 比较）。 */
    fun appVersion(): String

    /** Earnings 路由在本端的 since（G20；现在三端都为 null）。 */
    fun earningsSince(): String?

    /** 经 Router 以 App 内来源打开（路由名都在 routes.json）。 */
    fun open(
        route: String,
        params: JsonObject,
    )

    /**
     * 用 [route] 替换发起页（提交成功进 WithdrawRecords {withdrawal_id}，返回时回到钱包，G19）。[entryId] 是发起页的回退栈条目（APP-09 共性第 50 条）：
     * 只在它仍是栈顶时替换它本身，不按路由名找同名页面；不是栈顶就不动。
     */
    fun replaceTop(
        route: String,
        params: JsonObject,
        entryId: String?,
    )

    /** 经 App 写剪贴板的唯一入口（APP-07 ClipboardWriter：先记自写 hash 并确认落盘，记不下就不写）；返回是否写入。 */
    fun copy(text: String): Boolean

    /** 10405：强制进入已有的强更分流（服务端已判定）。 */
    fun forceUpdate()

    /** 【联系客服】/【联系客服申诉】：统一客服出口（APP-12 brief §2.9；App 接 AppMe）。没接时经 Router 打开帮助中心。 */
    fun contactService(fromRoute: String) = open(WalletRoutes.HELP, JsonObject(emptyMap()))

    /** 二次验证发码用的手机号（G31）：本机有当前账号、并且绑定了账号归属的号码才给；没有为 null（不编造，不让用户另填）。 */
    fun stepUpPhone(account: String): String?

    /**
     * 钱包摘要需要刷新（提交成功、放弃得到 completed 成功，G19）。[account]、[epoch] 是共享结算已核对过的点击时会话（APP-09 共性第 61 条，Android
     * RC-03）：原样放进失效通知，实现不得重新读取当前会话（其间新会话的令牌可能已保存）。
     */
    fun markWalletDirty(
        account: String,
        epoch: Long,
    ) = Unit

    /** 实名成功：刷新 GET /v1/me 的 realname_status，守卫按已实名放行（G05）。 */
    fun realnameChanged() = Unit

    /**
     * 收款账号保存成功：从 Withdraw 进来的出栈回发起页，从别处进来的停在本页（brief §2.7）。返回是否已出栈。[entryId] 是发起的收款账号页条目
     * （共性第 50 条）：只出栈它本身，且它必须是栈顶。
     */
    fun payoutSaved(entryId: String?): Boolean = false

    /** 二次验证、单独同意等敏感浮层出现 / 关闭（剪贴板等迟到结果只丢弃，APP-07 第 4 条）。 */
    fun sensitiveOverlay(showing: Boolean) = Unit
}

/** App 建一份、各页共用的依赖。 */
@Suppress("LongParameterList") // 执行器依赖逐项给
class WalletDeps(
    val api: WalletApi,
    val env: WalletEnv,
    val gates: WalletGates,
    val tokens: StepUpTokenCache,
    val cache: WalletStaleCache.Store?,
    val mask: AmountMaskStore?,
    val realname: RealNameBackend?,
    val labor: LaborBackend?,
    val texts: WalletTexts = WalletDraftTexts,
    val clock: () -> Long = System::currentTimeMillis,
    val newKey: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
    /** 未决记录的结算通知（APP-09 共性第 1 条；App 建一份、各页共用）。 */
    val pending: PendingSignals = PendingSignals(),
    /** 二次验证发码的截止时间（APP-09 共性第 6 条；App 建一份、各面板共用）。 */
    val sms: SmsDeadlines = SmsDeadlines(),
    /** 钱包摘要的失效通知（APP-09 共性第 53 条；App 建一份，[WalletEnv.markWalletDirty] 按会话归属发出，钱包页订阅）。 */
    val walletDirty: WalletDirtySignals = WalletDirtySignals(),
    /**
     * 会话级的执行作用域（App 建一份，主线程；APP-09 共性第 40 条）：已发出的写请求（提交、保存、确认、放弃）与发码在这里执行，结果的共享结算
     * （禁用期、发码倒计时、step-up token、钱包刷新标记、未决记录通知）不随页面 / ViewModel 的作用域取消而丢失；页面只按自己是否仍有效更新界面。
     */
    val service: kotlinx.coroutines.CoroutineScope,
)

/**
 * 表单与渲染快照所属的账号与登录世代（APP-09 共性第 52 条，Android R3-01）：页面建立表单（进入、会话换代后重建）时绑定，渲染时随快照交给按钮回调；
 * 提交、保存、确认、放弃、实名提交、劳务签署在生成键、读取 step-up token 之前核对它仍是当前会话，并把同一身份交给 [SensitiveFlow] 与网络层
 * （ApiCall.expectedAccount、PendingTarget）。不取「点击执行时的当前会话」，也不依赖稍后才执行的会话清空通知。
 */
data class FormOwner(
    val account: String?,
    val epoch: Long,
) {
    /** 表单所属的会话仍是当前会话（账号可读且一致、世代相同；同一账号重新登录也算换代）。 */
    fun current(env: WalletEnv): Boolean = account != null && env.sessionEpoch() == epoch && env.account() == account

    companion object {
        /**
         * 构造（或会话换代通知里重建）表单、页面执行器时**一次性**取得固定身份（APP-09 共性第 65 条，Android RC-06）：只允许在这两个时刻调用；
         * 恢复的数据（金额、输入）要与这份身份成对使用，之后对当前会话的读取只用于和它比较（[current]），不再读取当前会话来赋值或绑定。
         */
        fun of(env: WalletEnv): FormOwner = FormOwner(env.account(), env.sessionEpoch())
    }
}

/**
 * 钱包摘要的失效通知（APP-09 共性第 53 条，Android R3-02，BR-WDR-04、G19）：提现成功（含放弃得到 completed 成功）的共享结算按点击时的会话归属发一次
 * （账号, 世代）；仍存在的钱包页收到后核对是自己的会话，作废成功之前发出的摘要请求并重取。
 */
class WalletDirtySignals {
    private val mutable = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String?, Long>>(extraBufferCapacity = BUFFER)
    val dirty: kotlinx.coroutines.flow.SharedFlow<Pair<String?, Long>> = mutable

    fun notify(
        account: String?,
        epoch: Long,
    ) {
        mutable.tryEmit(account to epoch)
    }

    private companion object {
        const val BUFFER = 16
    }
}

/**
 * 未决记录按「账号 + 操作」的结算通知（APP-09 共性第 1 条）：提交、确认、作废结算之后（含超时变 unknown、页面已出栈的迟到响应）发一次，当前页面收到后
 * 按本机未决记录重新同步；旧页面不直接改新页面。
 */
class PendingSignals {
    private val mutable = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String?, String>>(extraBufferCapacity = BUFFER)
    val settled: kotlinx.coroutines.flow.SharedFlow<Pair<String?, String>> = mutable

    fun notify(
        account: String?,
        operationId: String,
    ) {
        mutable.tryEmit(account to operationId)
    }

    private companion object {
        const val BUFFER = 16
    }
}

/**
 * 二次验证发码的截止时间与在途占用（APP-09 共性第 6、17 条，APP-05a 第 5、20、21 条）：按账号、规范化号码与 action 隔离；重开面板恢复倒计时，
 * 每次实际发送前检查同一截止时间；同一槽位同时只允许一个发码请求在途（防连点与跨面板交错）。只在内存（进程内），会话换代时清空。
 */
class SmsDeadlines {
    /** 一个发码槽位：账号、号码（只取数字）、action。 */
    data class Slot(
        val account: String,
        val phone: String,
        val action: String,
    ) {
        internal val id: String get() = "$account|${phone.filter(Char::isDigit)}|$action"
    }

    private val until = mutableMapOf<String, Long>()

    /** 在途占用：槽位 → 占用它的那次请求（[acquire] 的票据）；释放只认同一张票据（共性第 40 条）。 */
    private val sending = mutableMapOf<String, Long>()
    private var tickets = 0L
    private val version = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** 截止时间或在途占用变化（等别的面板发完）。 */
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = version

    @Synchronized
    fun remaining(
        slot: Slot,
        now: Long,
    ): Long = ((until[slot.id] ?: 0L) - now).coerceAtLeast(0L)

    @Synchronized
    fun start(
        slot: Slot,
        deadline: Long,
    ) {
        until[slot.id] = deadline
        version.value += 1
    }

    /** 占用槽位：返回这次请求的票据；已有在途发码返回 null。 */
    @Synchronized
    fun acquire(slot: Slot): Long? {
        if (slot.id in sending) return null
        val ticket = ++tickets
        sending[slot.id] = ticket
        version.value += 1
        return ticket
    }

    /** 释放本次请求的占用：槽位已被清空（会话换代）或换成别的请求占用的，不动它。 */
    @Synchronized
    fun release(
        slot: Slot,
        ticket: Long,
    ) {
        if (sending[slot.id] == ticket) {
            sending.remove(slot.id)
            version.value += 1
        }
    }

    @Synchronized
    fun busy(slot: Slot): Boolean = slot.id in sending

    @Synchronized
    fun clear() {
        until.clear()
        sending.clear()
        version.value += 1
    }
}

/** 页面与 App 之间的接线（每个页面实例一份）：出栈的同步出口、会话换代、回到前台。 */
class PageHooks(
    val bindLeave: (leave: () -> Unit) -> Unit = {},
    val bindSession: (onSessionChanged: () -> Unit) -> Unit = {},
    /** 自动恢复页面（进程被回收后重开）先等本轮版本检查结果。 */
    val restoreGate: (suspend () -> Unit)? = null,
)

/** 测试与冒烟用的标记。 */
object WalletTags {
    const val WALLET_PAGE = "wallet_page"
    const val WALLET_HERO = "wallet_hero"
    const val WALLET_MASK = "wallet_mask"
    const val WALLET_WITHDRAW = "wallet_withdraw"
    const val WALLET_RECORDS = "wallet_records"
    const val WALLET_NEGATIVE = "wallet_negative"
    const val WALLET_RISK = "wallet_risk"
    const val WALLET_DISABLED_REASON = "wallet_disabled_reason"
    const val WALLET_FROZEN = "wallet_frozen"
    const val WALLET_ESTIMATED = "wallet_estimated"
    const val WALLET_RECEIVED = "wallet_received"
    const val WALLET_EARLIEST = "wallet_earliest"
    const val WALLET_OVERDUE = "wallet_overdue"
    const val WALLET_PAUSED = "wallet_paused"
    const val WALLET_STALE = "wallet_stale"
    const val WALLET_ERROR = "wallet_error"
    const val WALLET_RETRY = "wallet_retry"
    const val WALLET_REFRESH_ERROR = "wallet_refresh_error"
    const val RECORDS_REFRESH_ERROR = "records_refresh_error"
    const val PAYOUT_REFRESH_ERROR = "payout_refresh_error"
    const val ENTRY_ORDERS = "wallet_entry_orders"
    const val ENTRY_LEDGER = "wallet_entry_ledger"
    const val ENTRY_EARNINGS = "wallet_entry_earnings"
    const val HELP_PREFIX = "wallet_help_"
    const val HELP_SHEET = "wallet_help_sheet"
    const val HELP_OK = "wallet_help_ok"

    const val WITHDRAW_PAGE = "withdraw_page"
    const val WITHDRAW_BALANCE = "withdraw_balance"
    const val WITHDRAW_REMAINING = "withdraw_remaining"
    const val WITHDRAW_INPUT = "withdraw_input"
    const val WITHDRAW_ALL = "withdraw_all"
    const val WITHDRAW_MAX = "withdraw_max"
    const val WITHDRAW_QUICK_PREFIX = "withdraw_quick_"
    const val WITHDRAW_ESTIMATE = "withdraw_estimate"
    const val WITHDRAW_CALCULATING = "withdraw_calculating"
    const val WITHDRAW_PAYOUT = "withdraw_payout"
    const val WITHDRAW_SLA = "withdraw_sla"
    const val WITHDRAW_SUBMIT = "withdraw_submit"
    const val WITHDRAW_GUIDE = "withdraw_guide"
    const val WITHDRAW_GUIDE_BUTTON = "withdraw_guide_button"
    const val WITHDRAW_ERROR = "withdraw_error"
    const val FLOW_GUIDE = "flow_guide"
    const val PAYOUT_GUIDE = "payout_guide"
    const val WITHDRAW_READ_ERROR = "withdraw_read_error"
    const val WITHDRAW_FIELD_ERROR = "withdraw_field_error"
    const val WITHDRAW_RULES_LINK = "withdraw_rules_link"
    const val WITHDRAW_LOAD_ERROR = "withdraw_load_error"
    const val WITHDRAW_RETRY = "withdraw_retry"
    const val RISK_DIALOG = "withdraw_risk_dialog"
    const val RISK_APPEAL = "withdraw_risk_appeal"
    const val RISK_OK = "withdraw_risk_ok"

    const val PENDING_CARD = "pending_card"
    const val PENDING_CONFIRM = "pending_confirm"
    const val PENDING_ABANDON = "pending_abandon"
    const val PENDING_NOTICE = "pending_notice"
    const val PENDING_RECORDS = "pending_records"
    const val ABANDON_DIALOG = "abandon_dialog"
    const val ABANDON_OK = "abandon_ok"
    const val ABANDON_CANCEL = "abandon_cancel"
    const val TOAST = "wallet_toast"
    const val OPEN_ERROR = "wallet_open_error"
    const val OPEN_ERROR_TEXT = "wallet_open_error_text"
    const val OPEN_ERROR_RETRY = "wallet_open_error_retry"
    const val OPEN_ERROR_CLOSE = "wallet_open_error_close"

    const val STEP_UP_SHEET = "step_up_sheet"
    const val STEP_UP_CODE = "step_up_code"
    const val STEP_UP_SUBMIT = "step_up_submit"
    const val STEP_UP_CLOSE = "step_up_close"
    const val STEP_UP_RESEND = "step_up_resend"
    const val STEP_UP_ERROR = "step_up_error"
    const val STEP_UP_NO_PHONE = "step_up_no_phone"
    const val SHEET_MIDDLE = "sheet_middle"
    const val SHEET_FALLBACK = "sheet_fallback"

    const val RECORDS_PAGE = "records_page"
    const val RECORDS_LIST = "records_list"
    const val RECORD_PREFIX = "record_"
    const val RECORD_DETAIL_PREFIX = "record_detail_"
    const val RECORD_COPY = "record_copy"
    const val RECORD_TRADE_NO = "record_trade_no"
    const val RECORD_CHANGE_ACCOUNT = "record_change_account"
    const val RECORD_CS = "record_cs"
    const val RECORDS_EMPTY = "records_empty"
    const val RECORDS_END = "records_end"
    const val RECORDS_ERROR = "records_error"
    const val RECORDS_RETRY = "records_retry"
    const val RECORDS_LOAD_MORE = "records_load_more"

    const val REALNAME_PAGE = "realname_page"
    const val REALNAME_NAME = "realname_name"
    const val REALNAME_ID = "realname_id"
    const val REALNAME_SUBMIT = "realname_submit"
    const val REALNAME_UNAVAILABLE = "realname_unavailable"
    const val REALNAME_ERROR = "realname_error"
    const val CONSENT_SHEET = "realname_consent"
    const val CONSENT_CHECK = "realname_consent_check"
    const val CONSENT_OK = "realname_consent_ok"
    const val CONSENT_CANCEL = "realname_consent_cancel"

    const val LABOR_PAGE = "labor_page"
    const val LABOR_TEXT = "labor_text"
    const val LABOR_CHECK = "labor_check"
    const val LABOR_SIGN = "labor_sign"
    const val LABOR_UNAVAILABLE = "labor_unavailable"
    const val LABOR_ERROR = "labor_error"

    const val PAYOUT_PAGE = "payout_page"
    const val PAYOUT_BOUND = "payout_bound"
    const val PAYOUT_CHANGE = "payout_change"
    const val PAYOUT_METHOD_ALIPAY = "payout_method_alipay"
    const val PAYOUT_METHOD_BANK = "payout_method_bank"
    const val PAYOUT_LOGON = "payout_logon"
    const val PAYOUT_CARD = "payout_card"
    const val PAYOUT_BANK_NAME = "payout_bank_name"
    const val PAYOUT_PAYEE = "payout_payee"
    const val PAYOUT_SAVE = "payout_save"
    const val PAYOUT_ERROR = "payout_error"
    const val PAYOUT_FIELD_ERROR_PREFIX = "payout_field_error_"
    const val PAYOUT_LOAD_ERROR = "payout_load_error"
    const val PAYOUT_RETRY = "payout_retry"
}
