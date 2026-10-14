package com.couli.feature.me

import com.couli.core.me.MeTexts
import com.couli.core.me.account.AccountRaw
import com.couli.core.me.account.AccountShared
import com.couli.core.network.ApiResult
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.WalletTexts
import com.couli.core.wallet.stepup.FormOwner
import com.couli.core.wallet.stepup.StepUpDeps
import com.couli.core.wallet.stepup.StepUpEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 注销页、换号子页的固定身份（APP-13a brief §0 第 1 条「身份固定」；APP-09 第 39、52、59、60、63、65 条）：「我的」身份（身份档 + 账号 + 登录世代 +
 * 隐私 / 环境世代）加会话作用域（full / deletion_only）。控制器构造时**一次性**取得（[AccountEnv.accountIdentity]），之后只比较（[current]），
 * 不再从当前会话赋值；请求带 [account] 到网络层（ApiCall.expectedAccount）。作用域变了（受限 → full）也算换代：页面按新身份重建控制器。
 */
data class AccountIdentity(
    val me: MeIdentity,
    val scope: String,
) {
    val account: String? get() = me.account
    val epoch: Long get() = me.epoch
    val member: Boolean get() = me.member
    val restricted: Boolean get() = scope == AccountRaw.DELETION_ONLY

    /** 二次验证面板沿用的身份（账号 + 登录世代）。 */
    val owner: FormOwner get() = FormOwner(account, epoch)

    /** 仍是当前身份（身份档、账号、登录世代、隐私 / 环境世代、作用域都没变）。 */
    fun current(env: AccountEnv): Boolean = env.accountIdentity() == this
}

/** 注销与换号两页的文案（AccountDraftTexts → 「我的」与登录的草稿表；BR-TEXT-12）。面板取 [WalletTexts]，页面取 [MeTexts]。 */
interface AccountTexts :
    MeTexts,
    WalletTexts

/**
 * 账号类页面要的网络能力：普通调用经 [PageApi]（带页面归属）；未决键的读取、确认与作废经 ApiClient 已有入口（不重写管线）。
 * 生产为 App 的 ApiClient 接线；测试用 MockWebServer 上的真实 ApiClient。
 */
interface AccountApi : PageApi {
    /** 当前会话账号某个操作的未决记录（含 held）；存储读不出抛错（不当成没有记录）。 */
    fun pendingFor(operationId: String): PendingKeyRecord?

    /** 冷启动、登录成功、回前台：按当前会话账号恢复未决记录（只读，不发）。 */
    fun restorePending(): Boolean

    /** 【确认上一次提交】：原键、原请求体重发（二次验证之后带 [stepUpToken]）；[target] 是点击时的账号、原键与归属。 */
    suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult

    /** 【放弃上一次提交】（二次确认之后）：作废接口；[target] 同 [confirmPending]。 */
    suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult
}

/**
 * 账号类页面要的环境（App 给；都在主线程调用）。继承 [StepUpEnv]：二次验证面板取号码、作用域、强更都经它。
 */
@Suppress("TooManyFunctions") // 每项能力一个入口
interface AccountEnv : StepUpEnv {
    /** 此刻的账号身份（App 现算，含作用域）。 */
    fun accountIdentity(): AccountIdentity

    /** 身份变化（会话、作用域、隐私、环境）：页面按它重建控制器（APP-12 第 18 条）。 */
    val accountIdentityFlow: StateFlow<AccountIdentity>

    /** 经 Router 以 App 内来源打开（路由名都在 routes.json）。 */
    fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    )

    /** 统一客服出口（APP-12）。 */
    fun customerService(fromRoute: String)

    /** 路由可用性（since 与 Debug 覆盖，G01）：注销入口与 10007 落点的唯一判定。 */
    fun availableRoutes(): Set<String>

    /** 经 Router 打开 DeleteAccount，并记下来源（settings / risk_notice；G54、G55：来源不走路由参数，App 交接一次）。 */
    fun openDeletion(source: String)

    /** 注销页来源交接（G54 / G55）：App 按导航栈交接一次（打开 DeleteAccount 之前记下），页面首次组合时取走；取不到为 other。 */
    fun takeDeletionSource(entryId: String?): String

    /**
     * 注销页按来源结束（G18、G19、03 §4.1）：settings → 只出栈发起的这一个注销页（它必须是栈顶，APP-09 第 50 条）并在设置页轻提示 [toastKey]；
     * force_update → 出栈回到强更页并重取标签；返回是否已出栈。
     */
    fun finishDeletion(
        entryId: String?,
        source: String,
        toastKey: String?,
    ): Boolean

    /** 强更页需要重取注销标签（申请成功、撤回成功、离开注销页）。 */
    fun deletionChanged() = Unit

    /** 页面是否仍是栈顶且可见的那一个回退栈条目（异步结果要打开浮层、导航前核对）。 */
    fun isTop(entryId: String?): Boolean = true
}

/**
 * 设置页的一次性轻提示（撤回注销成功后回到设置页，SettingsDeletionWithdrawn）。注销页出栈时设置页还不在组合里（Navigation 只组合栈顶），所以按「待显示」保存一份，
 * 设置页组合后按身份取走一次（[take]）；身份不符（会话已换代）的丢弃。
 */
class AccountNotices {
    private val mutable = MutableStateFlow<Pair<AccountIdentity, String>?>(null)
    val settings: StateFlow<Pair<AccountIdentity, String>?> = mutable

    fun toSettings(
        identity: AccountIdentity,
        key: String,
    ) {
        mutable.value = identity to key
    }

    /** 设置页取走（只取本身份的；别的身份的作废）。 */
    fun take(identity: AccountIdentity?): String? {
        val pending = mutable.value ?: return null
        mutable.value = null
        return pending.second.takeIf { pending.first == identity }
    }
}

/** App 建一份、注销页与换号子页共用的依赖。 */
@Suppress("LongParameterList") // 执行器依赖逐项给
class AccountDeps(
    val me: MeDeps,
    val api: AccountApi,
    val env: AccountEnv,
    val stepUp: StepUpDeps,
    val shared: AccountShared,
    val texts: AccountTexts,
    /** 钱包摘要读取的截止时间（与钱包页共享，brief §0「Retry-After」）。 */
    val walletRead: WalletRateGate,
    /** getDeletion 读取的截止时间（注销页与强更页共用）。 */
    val deletionRead: WalletRateGate,
    val clock: () -> Long = System::currentTimeMillis,
    val newKey: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
    /** 会话级执行作用域（主线程）：写请求与发码在这里执行，离开页面不取消。 */
    val service: CoroutineScope,
) {
    val notices: AccountNotices = AccountNotices()
}

/** 测试与冒烟用的标记。 */
object AccountTags {
    const val DELETE_PAGE = "delete_page"
    const val DELETE_LOADING = "delete_loading"
    const val DELETE_LOAD_ERROR = "delete_load_error"
    const val DELETE_RETRY = "delete_retry"
    const val DELETE_FUNDS = "delete_funds"
    const val DELETE_WITHDRAWABLE = "delete_withdrawable"
    const val DELETE_ESTIMATED = "delete_estimated"
    const val DELETE_NEGATIVE = "delete_negative"
    const val DELETE_FUNDS_ERROR = "delete_funds_error"
    const val DELETE_FUNDS_RETRY = "delete_funds_retry"
    const val DELETE_GO_WITHDRAW = "delete_go_withdraw"
    const val DELETE_NOTICE = "delete_notice"
    const val DELETE_WAIVER = "delete_waiver"
    const val DELETE_SUBMIT = "delete_submit"
    const val DELETE_ERROR = "delete_error"
    const val DELETE_NEGATIVE_NOTICE = "delete_negative_notice"
    const val DELETE_CONTACT_CS = "delete_contact_cs"
    const val DELETE_NO_PHONE = "delete_no_phone"
    const val DELETE_BIND_PHONE = "delete_bind_phone"
    const val DELETE_GUIDE = "delete_guide"
    const val DELETE_COOLING = "delete_cooling"
    const val DELETE_COOLING_BADGE = "delete_cooling_badge"
    const val DELETE_INTRO = "delete_intro"
    const val PHONE_STEPS = "phone_change_steps"

    /** 待确认态下方置灰的第 2 步表单的标签后缀。 */
    const val LOCKED_SUFFIX = "_locked"
    const val DELETE_COOLING_DEADLINE = "delete_cooling_deadline"
    const val DELETE_CANCEL = "delete_cancel"
    const val DELETE_CANCEL_EXPIRED = "delete_cancel_expired"
    const val DELETE_PROCESSING = "delete_processing"
    const val DELETE_TOAST = "delete_toast"

    const val PENDING_CARD = "account_pending_card"
    const val PENDING_CONFIRM = "account_pending_confirm"
    const val PENDING_ABANDON = "account_pending_abandon"
    const val PENDING_NOTICE = "account_pending_notice"
    const val ABANDON_DIALOG = "account_abandon_dialog"
    const val ABANDON_OK = "account_abandon_ok"
    const val ABANDON_CANCEL = "account_abandon_cancel"

    const val PHONE_PAGE = "phone_change_page"
    const val PHONE_STEP1 = "phone_change_step1"
    const val PHONE_STEP2 = "phone_change_step2"
    const val PHONE_DONE = "phone_change_done"
    const val PHONE_DONE_OK = "phone_change_done_ok"
    const val PHONE_STEP1_CODE = "phone_change_step1_code"
    const val PHONE_STEP1_SEND = "phone_change_step1_send"
    const val PHONE_STEP1_NEXT = "phone_change_step1_next"
    const val PHONE_NO_PHONE = "phone_change_no_phone"
    const val PHONE_CANT_USE_OLD = "phone_change_cant_use_old"
    const val PHONE_NEW = "phone_change_new"
    const val PHONE_STEP2_CODE = "phone_change_step2_code"
    const val PHONE_STEP2_SEND = "phone_change_step2_send"
    const val PHONE_SUBMIT = "phone_change_submit"
    const val PHONE_ERROR = "phone_change_error"
    const val PHONE_FIELD_ERROR = "phone_change_field_error"
    const val PHONE_RISK_DIALOG = "phone_change_risk_dialog"
    const val PHONE_RISK_OK = "phone_change_risk_ok"
    const val PHONE_RISK_APPEAL = "phone_change_risk_appeal"
    const val PHONE_CONTACT_CS = "phone_change_contact_cs"
    const val PHONE_TOAST = "phone_change_toast"

    const val SETTINGS_DELETE_ROW = "settings_row_delete_account"
    const val SETTINGS_PHONE_ROW = "settings_row_phone"
    const val RISK_DELETE = "risk_delete_account"
    const val APPEAL_DELETE = "appeal_delete_account"
}
