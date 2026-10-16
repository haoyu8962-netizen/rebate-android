package com.couli.feature.me

import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeLevel
import com.couli.core.me.MeLocalStore
import com.couli.core.me.MeTexts
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 页面、表单、执行器的固定身份（APP-09 第 39、52、63、65 条，brief §0「表单绑定账号与登录世代」）：身份档 + 账号 + 登录世代 + 隐私 / 环境世代（环境票据）。
 * 每个页面执行器、表单在构造时**一次性**取得（[MeEnv.identity]），之后对当前会话只做比较（[current]），不再赋值或绑定；请求携带 [account] 到网络层
 * （ApiCall.expectedAccount）。Compose 回调捕获渲染时的普通值（执行器与它的 identity），执行时只比较。
 */
data class MeIdentity(
    val level: String,
    val account: String?,
    val epoch: Long,
    val epochs: CallEpochs,
) {
    val member: Boolean get() = level == MeLevel.MEMBER && account != null

    /** 仍是当前身份（身份档、账号、登录世代、隐私与环境世代都没变）。 */
    fun current(env: MeEnv): Boolean = env.identity() == this
}

/** 「我的」类页面要的环境（App 给；都在主线程调用）。 */
@Suppress("TooManyFunctions") // 每项能力一个入口
interface MeEnv {
    /** 此刻的身份（App 现算）。 */
    fun identity(): MeIdentity

    /** 身份的变化（会话、隐私结论、环境变化时 App 同步更新）：页面按它重建执行器（共性第 18 条）。 */
    val identityFlow: StateFlow<MeIdentity>

    /** 经 Router 以 App 内来源打开（路由名都在 routes.json）。 */
    fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    )

    /** 经 APP-07 统一写入口写剪贴板（先记自写 hash 并确认落盘，记不下就不写）；返回是否写入。 */
    fun copy(text: String): Boolean

    /** 10405：强制进入已有的强更分流（服务端已判定）。 */
    fun forceUpdate()

    /** /v1/config 的 data（生效配置：当前 → LKG → 包内）。 */
    fun configData(): JsonObject?

    /** 本端路由表里有、since 满足的路由（宫格裁剪，G31）。 */
    fun availableRoutes(): Set<String>

    /** 收益看板开关（features.flags earnings.dashboard.visible；G31，没有为 null）。 */
    fun earningsFlag(): Boolean?

    /** 统一客服出口（brief §2.9）。 */
    fun customerService(fromRoute: String)

    /** 系统通知状态（只查不申请）：granted / denied / not_determined（Android 13+ 与 areNotificationsEnabled；从未被拒过为 not_determined，H09）。 */
    fun notificationStatus(): String

    /** 本机记下的通知权限最近被拒时刻（PermissionRequester 只读；G28）。 */
    fun notificationDeniedAt(): Long?

    /** 跳系统里本 App 的通知设置（不经 PermissionRequester 申请，不出系统授权框）。 */
    fun openNotificationSettings()

    /** 隐私中心「系统权限」一节：锁定契约 system-permissions.json 的 permissions（G26）与各类型状态。 */
    fun permissionEntries(): List<JsonObject>

    fun permissionStatus(type: String): String

    /** 跳系统里本 App 的设置页（系统权限一节的条目点击，不申请、不改节流）。 */
    fun openAppSettings()

    /** 基本模式【开启完整功能】：重新展示首启弹窗。 */
    fun requestFullFunction()

    /** 强更 below_min 的受限上下文（从强更页进入隐私中心，03 §4.1「强更页的次要入口」）。 */
    fun forceUpdateContext(): Boolean

    /**
     * [identity] 在本次运行里是否因 10006 进入过 RiskNotice（G10：appealing 时选封禁版式）。依据绑定 10006 触发时固定的身份（账号、登录世代、隐私 / 环境世代），
     * 这里只比较归属；换账号、退出、撤回、切环境时 App 清除（Android R2-03）。
     */
    fun enteredBy10006(identity: MeIdentity): Boolean

    /** 设备已注册（有 device_id；撤回同意的服务端记录要它，G25）。 */
    fun deviceRegistered(): Boolean

    /** 本机已同意的隐私政策版本（撤回记录的 version，H12）；没有为 null。 */
    fun consentedPrivacyVersion(): Int?

    /** /v1/config.legal.privacy.version（个性化记录的 version）；取不到用本机已同意版本（H12）。 */
    fun legalPrivacyVersion(): Int?

    /** 「清除缓存」：只清图片与 WebView 缓存（AC-S1-137），大小按字节；读不出为 null。 */
    fun cacheBytes(): Long?

    /** 清除缓存；返回是否全部删除（删后再核对）。在 IO 线程调用。 */
    fun clearCache(): Boolean

    /**
     * 清 WebView 的内存与磁盘资源缓存（AC-S1-137，Android R1-10：WebView.clearCache(true) 对本进程所有 WebView 生效）。在主线程调用；返回是否成功。
     * 设置页先调它、再在 IO 线程清缓存目录，全部完成后才提示结果。
     */
    fun clearWebCache(): Boolean = true

    /** 关于页：版本名与构建号。 */
    fun appVersion(): String

    fun appBuild(): String

    /** 复制诊断信息的字段（G23；只取白名单内的项，Diagnostics 再过滤一次）。 */
    fun diagnosticsFields(): JsonObject

    /** 设置页【退出登录】确认之后：只调已有 logout()（APP-05a），并按发起实例与世代回到「我的」游客态（R1-09）。[stillHere] 是发起页面的导航许可。 */
    fun logout(
        identity: MeIdentity,
        stillHere: () -> Boolean,
    )

    /** 隐私中心【确认撤回】：交给应用级协调器（不随页面出栈销毁，鸿蒙 R2-04），本地撤回一定执行（G24）。 */
    fun withdrawConsent(identity: MeIdentity)

    /** 撤回同意正在进行（页面其余操作禁用）。 */
    val withdrawing: StateFlow<Boolean>

    /** 关闭本回退栈条目（只关发起的这一个实例，APP-09 第 50 条）。 */
    fun close(entryId: String?)

    /** 「重新显示下单须知」成功：下单须知的已读缓存推进世代（鸿蒙 R1-10）。 */
    fun tipsReset() = Unit

    /**
     * 当前会话作用域（full / deletion_only；没有会话为空串）。F-X-14（FU-AND-0717）：受限登录（deletion_only）之后「我的」Tab 的自动资料同步（进入 Tab、
     * 回前台、从子页返回、会话变化后重建）不请求 getMe；页面主动读取（下拉、【重试】、设置页、注销页）不受影响。缺省空串（不受限，旧单测）。
     */
    fun sessionScope(): String = ""
}

/** 写操作结果 → 向量形状的输入（kind ok / transport / error，code、data、headers.Retry-After、traceId、msg），交给 NicknameOutcome / AppealOutcome。 */
object MeResultInput {
    fun of(result: ApiResult): JsonObject =
        when (result) {
            is ApiResult.Success -> {
                JsonObject(mapOf("kind" to JsonPrimitive("ok"), "data" to (result.data ?: JsonNull)))
            }

            is ApiResult.Failure -> {
                val map =
                    linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                        "kind" to JsonPrimitive("error"),
                        "code" to JsonPrimitive(result.code),
                        "data" to result.data,
                        "msg" to JsonPrimitive(result.msg),
                    )
                result.traceId?.let { map["traceId"] = JsonPrimitive(it) }
                // 10405 而网络层没有判定强更（F-X-09）：交给结局表按统一错误文案处理，不进强更
                if (ResultSignals.nonForcedUpdate(result)) map["forced"] = JsonPrimitive(false)
                val disable = ResultSignals.disableMillis(result)
                if (disable > 0) map["headers"] = JsonObject(mapOf("Retry-After" to JsonPrimitive((disable / MILLIS).toString())))
                JsonObject(map)
            }

            else -> {
                JsonObject(mapOf("kind" to JsonPrimitive("transport")))
            }
        }

    private const val MILLIS = 1_000L
}

/**
 * 修改昵称（G01）：契约缺 PATCH /v1/me，手写的待定 operation updateNickname 只由 Debug 假服务实现；Release 为 null（昵称行不可点、不发请求）。
 * TODO(N-33) 契约补上后改为真实接口。[allowed] 是执行层的发送前闸门（42901 禁用期、身份核对），实现方在「发送」前调用，为 false 返回本地拒绝。
 */
interface NicknameBackend {
    suspend fun save(
        account: String,
        nickname: String,
        allowed: () -> Boolean,
    ): ApiResult
}

/**
 * 服务类推送开关（G02）：契约缺 GET/PUT /v1/me/notification-settings，手写的待定 operation 只由 Debug 假服务实现；Release 为 null（不显示开关行）。
 * data：{service: boolean}（字段名按 04 代拟）。TODO(N-33)。
 */
interface NotifyBackend {
    suspend fun get(
        account: String,
        allowed: () -> Boolean,
    ): ApiResult

    suspend fun put(
        account: String,
        service: Boolean,
        allowed: () -> Boolean,
    ): ApiResult
}

/**
 * 会话级共享的几份 42901 禁用期（brief §0「Retry-After」：Me 读取一个、申诉提交一个、昵称一个、通知开关一个；另：撤回同意的服务端记录、重新显示下单须知、
 * RiskNotice 读取各自共用读取闸门）。会话换代时整体 [reset]。
 * [read] 是 Me 读取的**全局唯一**截止时间（Android R2-05，共性第 32 条）：App 把登录侧（phone_bound 校验的 getMe）持有的同一个闸门交进来，两条路径共用。
 */
class MeGates(
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    val read: WalletRateGate = WalletRateGate(scope, clock),
) {
    val appeal = WalletRateGate(scope, clock)
    val nickname = WalletRateGate(scope, clock)
    val notify = WalletRateGate(scope, clock)
    val consent = WalletRateGate(scope, clock)
    val resetTip = WalletRateGate(scope, clock)

    fun reset() = listOf(read, appeal, nickname, notify, consent, resetTip).forEach(WalletRateGate::reset)
}

/**
 * 一次性的确认框 / 半屏编号（Android R1-02，共性第 20 条）：每次打开生成新编号；确认、取消只认「当前正显示的这一个」（类型与编号都相同），
 * 用过或被取消即作废，旧闭包重放一律不生效。
 */
data class MeDialog(
    val kind: String,
    val id: Long,
)

/**
 * 「我的」Tab 根的滚动位置（Android R1-11，共性第 27 条）：放在会话级依赖里（Tab 根离开组合——切到其他 Tab——不销毁），按身份分开：
 * 身份变了（换账号、退出、撤回同意）给新的滚动状态，不把上一个账号的位置带过去。
 */
class MeTabScroll {
    private var owner: MeIdentity? = null
    private var scroll: androidx.compose.foundation.ScrollState? = null

    fun of(identity: MeIdentity): androidx.compose.foundation.ScrollState {
        val current = scroll
        if (current != null && owner == identity) return current
        return androidx.compose.foundation.ScrollState(0).also {
            owner = identity
            scroll = it
        }
    }

    /** 会话清理（撤回、切换环境）：丢掉保存的位置。 */
    fun reset() {
        owner = null
        scroll = null
    }
}

/**
 * 读请求失败的统一处理（Android R1-04 / 05 / 07，共性第 22、23 条）：先处理这一个响应自己的限流与统一副作用（42901 → 共用读取闸门按 Retry-After 启动禁用期，
 * 缺省或非法按 5 秒；10405 → 强更分流），再给页面要显示的提示（统一错误文案 + trace6）。返回 null = 静默（被页面作废、非限流的本地拒绝）。
 */
object MeReadFailure {
    fun handle(
        result: ApiResult,
        probe: com.couli.core.wallet.SendProbe,
        gate: WalletRateGate,
        env: MeEnv,
        nextId: () -> Long,
    ): MeNotice? {
        val error = MeErrors.of(result)
        if (error.kind == com.couli.core.wallet.WalletError.CANCELLED) {
            return if (probe.gated(error)) MeNotice(RATE_KEY, nextId()) else null
        }
        if (error.kind == com.couli.core.wallet.WalletError.RETRY_AFTER) gate.start(error.disableMillis)
        if (error.kind == com.couli.core.wallet.WalletError.FORCE_UPDATE) env.forceUpdate()
        return MeErrors.notice(error, nextId())
    }

    const val RATE_KEY = "error.42901"
}

/** App 建一份、各页共用的依赖。 */
@Suppress("LongParameterList") // 执行器依赖逐项给
class MeDeps(
    val api: PageApi,
    val env: MeEnv,
    val gates: MeGates,
    val texts: MeTexts = MeDraftTexts,
    val local: MeLocalStore? = null,
    val nickname: NicknameBackend? = null,
    val notify: NotifyBackend? = null,
    val clock: () -> Long = System::currentTimeMillis,
    val newKey: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
    /** 会话级的执行作用域（主线程）：已发出的写请求在这里执行，离开页面不取消（brief §0「出栈清理」）；页面只按自己是否仍有效更新界面。 */
    val service: CoroutineScope,
) {
    /** 「我的」与设置共用的 Me 快照（会话级，[MeRepository]）。 */
    val repo: MeRepository = MeRepository(this)

    /** 「我的」Tab 根的滚动位置（切 Tab 往返保留，R1-11）。 */
    val tabScroll: MeTabScroll = MeTabScroll()

    /** 「我的」Tab 这一次进入的执行器（切 Tab、进入下一页的同步出口在这里撤销它的读取许可，R2-01）。 */
    val tab: MeTabEntry = MeTabEntry()
}

/**
 * 「我的」Tab 的「这一次进入」（Android R2-01，共性第 28 条）：页面作废放在**同步出口**——外壳提交切 Tab（改 selection 之前）、回退栈压入下一页的同一条消息里调
 * [leave]，立即撤销当前执行器的读取许可并取消它已交给传输层的 getMe；不等下一次重组的 onDispose（onDispose 只作兜底）。[leave] 同时推进 [entry]：若 Tab 根
 * 仍在组合里（返回得比转场动画快），页面按新的一次进入重建执行器，不会拿着已作废的执行器停在旧状态。只在主线程使用。
 */
class MeTabEntry {
    private var current: MeTabController? = null
    private val mutable = MutableStateFlow(0L)

    /** 进入的序号：每次同步离开加 1（页面按它与身份重建执行器）。 */
    val entry: StateFlow<Long> = mutable

    /** 此刻登记的执行器（只读；集成测试经它走页面主动读取的同一入口，FU-AND-0717）。 */
    val bound: MeTabController? get() = current

    /** 页面登记这一次进入的执行器（组合提交之后、读取之前）。 */
    fun bind(controller: MeTabController) {
        current = controller
    }

    /** 组合销毁（兜底）：只解绑自己。 */
    fun unbind(controller: MeTabController) {
        if (current === controller) current = null
    }

    /** 同步出口：撤销并取消这一次进入的 Me 读取。 */
    fun leave() {
        val leaving = current ?: return
        current = null
        leaving.leave()
        mutable.value += 1
    }
}

/** 页面上的一条提示：文案键 + 编号（只播报一次）；5xxxx 带 trace6。 */
data class MeNotice(
    val key: String,
    val id: Long,
    val traceId: String? = null,
    val showTrace6: Boolean = false,
    val assertive: Boolean = true,
    /** 服务端 msg（表外码兜底，G13）。 */
    val serverMsg: String? = null,
) {
    /** 统一错误消息函数（brief §0「trace6」）：文案键 → 服务端 msg →「操作未完成，请稍后再试」；5xxxx 由 [ResultSignals.withTrace6] 独立追加末 6 位。 */
    fun message(texts: MeTexts): String {
        val base =
            texts.format(key, mapOf("trace6" to ResultSignals.trace6(traceId))).takeIf { it.isNotEmpty() }
                ?: serverMsg?.takeIf { it.isNotBlank() }
                ?: texts.text(UNFINISHED_KEY)
        return if (showTrace6) ResultSignals.withTrace6(base, traceId) else base
    }

    companion object {
        /** BR-TEXT-14 正文的兜底句（代拟键，与鸿蒙 MeMessages.UNFINISHED 同文）。 */
        const val UNFINISHED_KEY = "me.unfinished"
    }
}

/** 读请求失败的分类（复用 APP-09 WalletErrors，网络错误换成本任务的键）。 */
object MeErrors {
    fun of(result: ApiResult): WalletError {
        val error = WalletErrors.of(result)
        return if (error.textKey == WalletErrors.NETWORK_KEY) error.copy(textKey = MeDraftTexts.NETWORK_KEY) else error
    }

    fun notice(
        error: WalletError,
        id: Long,
    ): MeNotice = MeNotice(error.textKey, id, error.traceId, error.showTrace6)
}

/** 测试与冒烟用的标记。 */
object MeTags {
    const val ME_PAGE = "me_page"
    const val ME_GUEST = "me_guest"
    const val ME_LOGIN = "me_login"
    const val ME_BELL = "me_bell"
    const val ME_NICKNAME = "me_nickname"
    const val ME_INVITE_CODE = "me_invite_code"
    const val ME_COPY = "me_copy"
    const val ME_INVITER = "me_inviter"
    const val ME_BALANCE = "me_balance"
    const val ME_WITHDRAWABLE = "me_withdrawable"
    const val ME_ESTIMATED = "me_estimated"
    const val ME_WITHDRAW = "me_withdraw"
    const val ME_MASK = "me_mask"
    const val ME_GUIDE_BAR = "me_guide_bar"
    const val ME_GUIDE_CLOSE = "me_guide_close"
    const val ME_GRID_PREFIX = "me_grid_"
    const val ME_ERROR = "me_error"
    const val ME_RETRY = "me_retry"
    const val ME_LOADING = "me_loading"
    const val ME_SCROLL = "me_scroll"
    const val TOAST = "me_toast"

    const val SETTINGS_PAGE = "settings_page"
    const val SETTINGS_ROW_PREFIX = "settings_row_"
    const val SETTINGS_FOOTER = "settings_footer"
    const val SETTINGS_LOGIN = "settings_login"
    const val SETTINGS_ENABLE_FULL = "settings_enable_full"
    const val SETTINGS_LOGOUT = "settings_logout"
    const val LOGOUT_DIALOG = "settings_logout_dialog"
    const val CLEAR_DIALOG = "settings_clear_dialog"
    const val AGREEMENT_SHEET = "settings_agreement_sheet"
    const val AGREEMENT_USER = "settings_agreement_user"
    const val AGREEMENT_PRIVACY = "settings_agreement_privacy"
    const val SETTINGS_ME_LOADING = "settings_me_loading"
    const val SETTINGS_ME_ERROR = "settings_me_error"
    const val SETTINGS_ME_RETRY = "settings_me_retry"

    const val NICKNAME_PAGE = "nickname_page"
    const val NICKNAME_INPUT = "nickname_input"
    const val NICKNAME_CLEAR = "nickname_clear"
    const val NICKNAME_COUNTER = "nickname_counter"
    const val NICKNAME_SAVE = "nickname_save"
    const val NICKNAME_ERROR = "nickname_error"

    const val NOTIFY_PAGE = "notify_page"
    const val NOTIFY_BAR = "notify_bar"
    const val NOTIFY_GO_SETTINGS = "notify_go_settings"
    const val NOTIFY_SWITCH = "notify_switch"
    const val NOTIFY_ERROR = "notify_error"
    const val NOTIFY_RETRY = "notify_retry"

    const val PRIVACY_PAGE = "privacy_page"
    const val PRIVACY_LIST_PREFIX = "privacy_list_"
    const val PRIVACY_POLICY = "privacy_policy"
    const val PRIVACY_AGREEMENT = "privacy_agreement"
    const val PRIVACY_SWITCH = "privacy_personalization"
    const val PRIVACY_PERMISSIONS = "privacy_permissions"
    const val PRIVACY_PERMISSION_PREFIX = "privacy_permission_"
    const val PRIVACY_WITHDRAW = "privacy_withdraw"
    const val PRIVACY_ENABLE_FULL = "privacy_enable_full"
    const val WITHDRAW_DIALOG = "privacy_withdraw_dialog"

    const val ABOUT_PAGE = "about_page"
    const val ABOUT_FILING = "about_filing"
    const val ABOUT_COPY = "about_copy"

    const val RISK_PAGE = "risk_page"
    const val RISK_TITLE = "risk_title"
    const val RISK_REASON = "risk_reason"
    const val RISK_LIMIT = "risk_limit"
    const val RISK_UNTIL = "risk_until"
    const val RISK_WITHDRAWALS = "risk_withdrawals"
    const val RISK_UPHELD = "risk_upheld"
    const val RISK_PRIMARY = "risk_primary"
    const val RISK_CS = "risk_cs"
    const val RISK_LOGOUT = "risk_logout"
    const val RISK_BACK = "risk_back"
    const val RISK_ERROR = "risk_error"
    const val RISK_RETRY = "risk_retry"
    const val RISK_APPEALS_ERROR = "risk_appeals_error"
    const val RISK_APPEALS_RETRY = "risk_appeals_retry"
    const val RISK_WITHDRAWALS_ERROR = "risk_withdrawals_error"
    const val RISK_WITHDRAWALS_RETRY = "risk_withdrawals_retry"
    const val RISK_LOGOUT_DIALOG = "risk_logout_dialog"
    const val RISK_REFRESH_ERROR = "risk_refresh_error"
    const val RISK_REFRESH_RETRY = "risk_refresh_retry"

    const val APPEAL_PAGE = "appeal_page"
    const val APPEAL_INPUT = "appeal_input"
    const val APPEAL_SUBMIT = "appeal_submit"
    const val APPEAL_ERROR = "appeal_error"
    const val APPEAL_PROGRESS = "appeal_progress"
    const val APPEAL_RESULT = "appeal_result"
    const val APPEAL_AGAIN = "appeal_again"
    const val APPEAL_CS = "appeal_cs"
    const val APPEAL_LOGOUT = "appeal_logout"
    const val APPEAL_ACTION_PREFIX = "appeal_action_"
    const val APPEAL_LOAD_ERROR = "appeal_load_error"
    const val APPEAL_RETRY = "appeal_retry"
    const val APPEAL_LOGOUT_DIALOG = "appeal_logout_dialog"

    const val CS_SHEET = "cs_sheet"
    const val CS_OPEN = "cs_open_wechat"
    const val CS_RETRY = "cs_retry"
    const val CS_COPY = "cs_copy_link"
    const val CS_HELP = "cs_go_help"
    const val CS_CLOSE = "cs_close"
}
