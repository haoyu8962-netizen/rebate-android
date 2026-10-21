package com.couli.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couli.core.network.ResultSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 登录页的文案来源（App 给：契约字典 → 草稿表）。本模块不写中文（BR-TEXT-12）。 */
interface LoginTexts {
    /** 键 → 文字；缺键返回空串。 */
    fun text(key: String): String

    /** 带 {变量} 的文案；变量为空或缺失时用 fallback（BR-TEXT-14）。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/** 登录页的出口（App 给：经 Router 打开别的页、关闭登录页）。 */
@Suppress("LongParameterList") // 每个出口一个回调，都有缺省
class LoginPageActions(
    /** 关闭登录页（左上角关闭）。 */
    val onClose: () -> Unit = {},
    /**
     * 求助：手机号页「登录遇到问题」（login.help_entry）与验证码页「收不到验证码？」（login.code.help_entry）。App 读 help_links.login_help，
     * 没配或不合法时打开帮助中心首页（坑 18）。
     */
    val onHelp: () -> Unit = {},
    /** 协议链接：type 为 agreement / privacy（Agreement 路由参数）。 */
    val onAgreement: (type: String) -> Unit = {},
    /** 联系客服（申诉）。 */
    val onCustomerService: () -> Unit = {},
    /** 10405（受限登录没有账号等）：关闭登录页回到强更页，[message] 由外壳在强更页上提示（登录页已关闭）。 */
    val onForceUpdate: (message: String) -> Unit = {},
    /** 登录成功（登录页已由执行器关闭）：邀请绑定结果等后续提示。 */
    val onLoggedIn: (LoginOutcome) -> Unit = {},
    /** 第三方登录成功（登录页已由执行器关闭）：第三方新号弹绑手机引导（LoginBindPhoneGuide，BR-INV-03 细则 ①）。 */
    val onOAuthLoggedIn: (OAuthLoginOutcome) -> Unit = {},
)

/**
 * 登录页的配置。[restricted]：从强更页进入的受限登录（APP-02c 已有的来源标记）——不显示帮助等业务入口、不显示待绑定码小字行
 * （BR-ID-01 细则；APP-17a 起普通登录也没有设置与邀请码入口）；第三方登录入口保留（共性第 28 条：仅第三方身份的已有账号要能登进 deletion_only 去注销），协议校验与 deletion_only
 * 分流照常。[sessionExpired]：刷新失败清会话后跳来的登录（LoginSessionExpired，进页提示一次）。[oauth]：第三方登录执行器
 * （APP-05b）；[thirdParty] 要显示的第三方按钮（provider 名，[OAuthLoginService.visibleProviders]：适配器可用且本机已装 / 未知；
 * Android 正式包的微信适配器恒为不可用 → 空，整块不显示）。
 */
@Suppress("LongParameterList") // 执行器、文案、来源标记与出口逐项给
class LoginPageConfig(
    val service: LoginService,
    val texts: LoginTexts,
    val restricted: Boolean,
    val sessionExpired: Boolean = false,
    val oauth: OAuthLoginService? = null,
    val thirdParty: List<String> = emptyList(),
    val clock: () -> Long = System::currentTimeMillis,
    val actions: LoginPageActions = LoginPageActions(),
)

/** 登录页两步。 */
enum class LoginStep {
    PHONE,
    CODE,
}

/** 登录页上的弹窗。 */
enum class LoginDialog {
    /** LoginAgreeConfirm：未勾选协议时点获取验证码、继续输入验证码、第三方登录（文案键见 [LoginUiState.confirmation]）。 */
    AGREE_CONFIRM,

    /** LoginCodeBlocked：44001 注册被拦。 */
    BLOCKED,

    /** LoginDeletionProcessing：10007 注销处理中。 */
    DELETION_PROCESSING,

    /** 第三方登录 44001（BR-TEXT-14 表 A：给申诉入口；不套用短信登录的专用说明，APP-05b 共性第 5 条）。 */
    OAUTH_BLOCKED,
}

/** 登录页的界面状态（StateFlow，03 §3.3）。时间相关的派生值（倒计时、限流）按 [nowMillis] 现算。 */
data class LoginUiState(
    val step: LoginStep = LoginStep.PHONE,
    val phone: String = "",
    val code: String = "",
    val agreed: Boolean = false,
    val dialog: LoginDialog? = null,
    /** 协议确认框按动作取的文案键（G10）：[dialog] 是 [LoginDialog.AGREE_CONFIRM] 时有值，关框后为 null。 */
    val confirmation: AgreementConfirmKeys? = null,
    val phoneError: String? = null,
    val codeError: String? = null,
    val maskedPhone: String = "",
    val sending: Boolean = false,
    val loggingIn: Boolean = false,
    /** 验证码页的重发倒计时（秒）；0 = 可以重新获取。 */
    val resendSec: Int = 0,
    /** 当前号码（按规范化号码）的发码限流（42901）还剩几秒；0 = 没有限流（共性第 21 条）。 */
    val sendLimitedSec: Int = 0,
    /** 登录限流（42901）还剩几秒。 */
    val loginLimitedSec: Int = 0,
    /**
     * 这个号码已收到（或发码结果不确定、可能已送达）、仍在有效期内的验证码还有几秒；大于 0 时手机号页显示独立的【继续输入验证码】
     * 入口（共性第 12、16 条），与能否重发无关。
     */
    val issuedCodeSec: Int = 0,
    /** 第三方登录进行中（provider 名；没有为 null）。 */
    val oauthBusy: String? = null,
    /** 第三方登录 42901 的禁用剩余秒数。 */
    val oauthLimitedSec: Int = 0,
    /** 轻提示文字与序号（同一句再次提示也换序号重新播报）。 */
    val toast: String? = null,
    val toastId: Int = 0,
    /** 读屏播报（发码成功播报一次脱敏的发送对象；倒计时结束、可重发时播报一次；APP-05b 共性第 11 条）。 */
    val announce: String? = null,
    val announceId: Int = 0,
)

/**
 * 登录页的状态机（APP-05a；APP-17a 改版闸前逻辑）：两步（手机号 → 验证码）、协议确认框、倒计时与限流、错误映射到页面状态。网络与会话都经
 * [LoginService]；页面关闭或出栈由 App 在同步出口调 [LoginService.invalidateAttempt]（共性第 9 条），这里只在用户「返回手机号步」「换号」时作废。
 *
 * - 协议闸只有一个生产入口 [requireAgreement]（G06）：获取验证码、手机号页【继续输入验证码】、第三方登录都过它；验证码页【重新获取】不过
 *   （验证码步一定已同意）。号码不合法先本地提示、不弹确认框（坑 20）。确认框只在仍有待继续的动作时生效，同意时刻取主按钮点击时刻，
 *   第二次空确认不改时刻；换号、返回、取消都丢掉待继续的动作。
 * - 进入验证码步只有一个出口 [enterCodeStep]（评审 r1）：此刻没有同意（发码等待中取消了勾选）就留在手机号页，验证码页任何时候不弹确认框；
 *   验证码绑定所属规范化号码，换号清格、进入时号码不符清格。
 * - 验证码满 6 位自动提交（[onCodeChange]，BR-ID-05）：同值事件不是新修订；在途时的输入不排队、结算后也不补交（G12、G23）；
 *   20002 / 20003 清空格子并留着错误（程序清空回来的空事件不冲掉错误，坑 23）；42901 保留格子、禁用期内不提交（G25）；验证码已过期
 *   （本地有效期到了或服务端 20003）要先重新获取。consent_at 沿用手机号步的勾选 / 确认时刻。
 */
@Suppress("TooManyFunctions") // 页面每个操作一个入口
class LoginViewModel(
    initial: LoginPageConfig,
) : ViewModel() {
    private var config: LoginPageConfig = initial
    private val service: LoginService get() = config.service
    private val texts: LoginTexts get() = config.texts
    private val agreement = AgreementState(clock = { config.clock() })
    private val limits = LoginRateLimits()
    private val oauthLimit = LoginRateLimits()

    /** 确认框同意之后要继续的动作（只在确认框显示期间有值；同意时取走一次，取消、换号、返回时丢掉）。 */
    private var afterConfirm: (() -> Unit)? = null

    /** 上一次发起尝试时的规范化号码（换号时作废在途请求）。 */
    private var attemptPhone: String? = null

    /**
     * 共性第 29 条（FU-AND-0716 第 2 轮）：验证码修订号。输入框里的验证码每变一次（输入、发码成功清空）加 1；登录提交时捕获，迟到的
     * 20002 / 20003 等验证码字段结果只在修订号未变时落到输入框（显示错误、清空），同号码换码后不显示在新码上、不清空新码。
     */
    private var codeRevision = 0L

    /**
     * 评审 r1 S1-2：输入框里的验证码属于哪个规范化号码（没有内容或已清空时可能为 null）。号码变了就清格并推进 [codeRevision]；进入验证码步时
     * 再核对一次，所属号码与要进入的号码不符就清空——跨号码的残留永远不会被补满后自动提交（也就不会提前消费待绑定码）。
     */
    private var codePhone: String? = null

    private val mutableState = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    init {
        if (initial.sessionExpired) toast(texts.text(LoginService.SESSION_EXPIRED_KEY))
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    /** 每次组合给最新的配置（出口回调随导航宿主变化）；受限标记与执行器不变。 */
    fun bind(latest: LoginPageConfig) {
        config = latest
    }

    val restricted: Boolean get() = config.restricted

    /**
     * 手机号输入（共性第 15 条，BR-ID-05）：原样保存，不先删字符、不截断——是否合法统一由 [PhoneNumber.normalize] 判定（NFKC 后只去
     * 规则允许去掉的空白与连字符、国家码前缀）；含字母等非法字符时保留原样，点获取验证码本地报 phone_invalid、不发短信。
     */
    fun onPhoneChange(raw: String) {
        val text = raw
        // 换了号码：待继续的协议确认作废（同意后不对新号码继续旧动作）
        if (text != mutableState.value.phone) dropConfirmation()
        val normalized = PhoneNumber.normalize(text).normalized
        // S1-2：规范化号码变了，验证码格里属于旧号码的内容作废（清格、推进修订号，迟到的旧结果不再落到格子上）
        if (normalized != codePhone) dropCode()
        if (normalized != attemptPhone && (service.busy || attemptPhone != null)) {
            // 换了号码：作废在途的发码与登录（共性第 1 条）
            service.invalidateAttempt()
            attemptPhone = null
        }
        mutableState.update { it.copy(phone = text, phoneError = null) }
        // 共性第 21 条：发码禁用与倒计时按新号码的截止时间立即切换（换回旧号码时旧限制仍然生效）
        tick()
    }

    /**
     * 登录页重新出现（回到前台、从协议页返回；共性第 20 条）：立即按当前时钟与截止时间重算倒计时和限流，不等下一次每秒刷新；
     * 每秒刷新的循环随 ViewModel 一直在跑（没有因页面隐藏而停），倒计时没结束的照常继续。
     */
    fun onPageVisible() = tick()

    fun onClearPhone() = onPhoneChange("")

    fun onToggleAgreement() {
        if (agreement.checked) agreement.uncheck() else agreement.check()
        mutableState.update { it.copy(agreed = agreement.checked) }
    }

    /**
     * 待绑定码小字行（APP-17c，R04、G20）：每次现读 [LoginService.pendingInvite]（读取不取走）；没有码或受限登录为 null。
     * 只有码、文案键与【移除】键，不含告知句（10-19 口径）；渲染属视觉闸后，刷新随 [pendingInviteChanges]。
     */
    val pendingInviteRow: PendingInviteRow?
        get() = if (config.restricted) null else service.pendingInvite.peek()?.let { PendingInviteRow(it) }

    /** 小字行的变化通知（容器的 [PendingInviteCode.state]）：闸后渲染订阅它、再读 [pendingInviteRow]。 */
    val pendingInviteChanges: StateFlow<String?> get() = service.pendingInvite.state

    /** 小字行【移除】：清掉共享容器里的码（之后的登录请求不带）。受限登录不显示小字行，也不动容器。 */
    fun removePendingInvite() {
        if (config.restricted) return
        service.pendingInvite.remove()
    }

    /**
     * 验证码输入（只在验证码步收）：值没变（输入法 / Compose 重复派发、程序清空回来的空串）不算新修订，不撤错误、不提交（坑 23）；真改了
     * 才加修订号、撤掉旧错误，满 6 位立即自动提交（[submitCode]）。
     */
    fun onCodeChange(raw: String) {
        if (mutableState.value.step != LoginStep.CODE) return
        val code = CodeInput.sanitize(raw)
        if (code == mutableState.value.code) return
        codeRevision += 1
        mutableState.update { it.copy(code = code, codeError = null) }
        if (CodeInput.accept(code).complete) submitCode()
    }

    /**
     * 手机号页【获取验证码】：号码不合法先本地提示（不弹确认框、不发请求，坑 20）；再过协议闸。同意后这个号码的成功发码倒计时还没到
     * （CONTINUE 分支）直接回验证码步继续输入已收到的码，不发码、不进 loading（G06、坑 21）。
     */
    fun onGetCode() {
        service.phoneCheck(mutableState.value.phone)?.let { local ->
            onSent(local, fromCodePage = false)
            return
        }
        requireAgreement(LoginAgreementAction.GET_CODE) { requestCode(fromCodePage = false) }
    }

    /**
     * 手机号页【继续输入验证码】（共性第 12、16 条）：这个号码有仍在有效期内、或可能已送达的码时显示；同样先过协议闸（G06，确认框按
     * 获取验证码取键），同意后回验证码步，不发码、不作废旧码；不以能否重发为条件（倒计时中、42901 限流中、发码结果不确定都可以）。
     */
    fun onContinueCode() {
        if (service.continueEntry(mutableState.value.phone).kind != PhoneEntry.CONTINUE) return
        requireAgreement(LoginAgreementAction.GET_CODE, ::continueToCode)
    }

    /** 验证码页【重新获取】：直接发码（验证码步一定已同意，不再过协议闸、不弹确认框）；发码期间输入框照常可输入（坑 22）。 */
    fun onResend() = requestCode(fromCodePage = true)

    /** 第三方登录按钮（与短信登录同一个协议闸，确认框正文 body_oauth，BR-ID-04）。 */
    fun onThirdParty(provider: String) {
        // F-X-12（FU-AND-0717）：Debug / Staging 入口没开本机假服务 → 不要求勾选协议、直接只提示（[OAuthLoginService.loginWith] 先于任何请求返回）
        if (config.oauth?.debugOnly(provider) == true) {
            oauthLogin(provider)
            return
        }
        requireAgreement(LoginAgreementAction.OAUTH) { oauthLogin(provider) }
    }

    val thirdParty: List<String> get() = if (config.oauth == null) emptyList() else config.thirdParty

    /**
     * 登录方式条目（C08）：实际显示的第三方按钮与要挂「上次登录」的那一个（只读，每次按当前配置与共享的上次登录方式现算；没接存储为 null）。
     */
    val entries: LoginPageEntries get() = LoginPageEntries.of(thirdParty, service.lastLoginMethod?.read())

    /** 验证码页返回手机号页：作废在途的登录（共性第 1 条）；已收到的码仍可在同号码下继续输入（共性第 12 条）。 */
    fun onBackToPhone() {
        dropConfirmation()
        service.invalidateAttempt()
        mutableState.update { it.copy(step = LoginStep.PHONE, loggingIn = false, sending = false, codeError = null) }
    }

    /**
     * 确认框主按钮（同意并获取验证码 / 同意并登录）：勾选框同步为已勾，记下这一次点击的时刻，继续原动作一次。没有待继续的动作（框已关、
     * 已同意过、换号或返回作废了）时什么都不做，不改已记下的时刻。
     */
    fun onConfirmAgree() {
        val next = afterConfirm ?: return
        afterConfirm = null
        agreement.confirm(config.clock())
        mutableState.update { it.copy(agreed = true, dialog = null, confirmation = null) }
        if (agreement.consumeProceed()) next()
    }

    /** 确认框【不同意】、点遮罩、系统返回：只关确认框，留在本页（不勾选、不发码、不换步，G10）。 */
    fun onCancelConfirm() {
        agreement.cancel()
        afterConfirm = null
        mutableState.update { it.copy(dialog = null, confirmation = null) }
    }

    /** 确认框里的协议链接：关确认框（不算同意）并打开协议页（共性第 8 条）。 */
    fun onConfirmLink(type: String) {
        onCancelConfirm()
        config.actions.onAgreement(type)
    }

    fun onDismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun onToastShown(id: Int) = mutableState.update { if (it.toastId == id) it.copy(toast = null) else it }

    /** 页面离开（返回键关闭等）：交给 App 的出口。 */
    fun onClose() = config.actions.onClose()

    /** 求助入口（手机号页 login.help_entry、验证码页 login.code.help_entry）：App 按 help_links.login_help 打开。 */
    fun help() = config.actions.onHelp()

    /** 协议行的链接：打开协议页（不改变勾选）。 */
    fun openAgreement(type: String) = config.actions.onAgreement(type)

    /** 弹窗里的【联系客服申诉】/【联系客服】。 */
    fun customerService() {
        onDismissDialog()
        config.actions.onCustomerService()
    }

    /**
     * 协议闸（唯一的生产入口，G06）：已勾选（有同意时刻）直接继续 [next]；未勾选弹确认框，文案键按 [action] 取（G10），同意时继续一次。
     * 再次触发（框还开着）只换成最新的待继续动作，不叠加。
     */
    private fun requireAgreement(
        action: String,
        next: () -> Unit,
    ) {
        agreement.tapLogin()
        if (agreement.showConfirm) {
            afterConfirm = next
            val keys = AgreementConfirmKeys.of(action)
            mutableState.update { it.copy(dialog = LoginDialog.AGREE_CONFIRM, confirmation = keys) }
            return
        }
        if (agreement.consumeProceed()) next()
    }

    /** 换号、返回手机号步：待继续的协议确认作废（关掉确认框，之后的确认不再继续旧动作）。 */
    private fun dropConfirmation() {
        if (afterConfirm == null && mutableState.value.dialog != LoginDialog.AGREE_CONFIRM) return
        agreement.cancel()
        afterConfirm = null
        mutableState.update {
            if (it.dialog == LoginDialog.AGREE_CONFIRM) it.copy(dialog = null, confirmation = null) else it.copy(confirmation = null)
        }
    }

    /**
     * 发码（获取验证码、重新获取共用）：本地就能定的结果（号码不合法、这个号码的倒计时还没到 → 直接回验证码步、42901 禁用期）同步给出，
     * 不进 loading；要发出时才置 [LoginUiState.sending]（坑 21）。
     */
    private fun requestCode(fromCodePage: Boolean) {
        if (service.busy) return
        val phone = mutableState.value.phone
        attemptPhone = PhoneNumber.normalize(phone).normalized
        service.localSendOutcome(phone)?.let { local ->
            onSent(local, fromCodePage)
            return
        }
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val outcome = service.sendCode(phone)
            mutableState.update { it.copy(sending = false) }
            onSent(outcome, fromCodePage)
        }
    }

    /** 同意后回验证码步继续输入已收到的码（不发码、不作废旧码）；同意期间码已失效则不换步。 */
    private fun continueToCode() {
        val entry = service.continueEntry(mutableState.value.phone)
        val phone = entry.phone
        if (entry.kind != PhoneEntry.CONTINUE || phone == null) return
        enterCodeStep(phone, entry.countdownSec)
    }

    /** 此刻是否已同意协议（勾选或确认框同意，且有同意时刻）。 */
    private fun consented(): Boolean = agreement.checked && agreement.consentAtMillis != null

    /**
     * 进入验证码步的唯一出口（评审 r1 S1-1 / S1-2）：获取验证码成功、倒计时内的 COUNTDOWN 分支、【继续输入验证码】都经这里。
     * - 此刻没有同意（发码等待中取消了勾选）：留在手机号页、不弹确认框，只刷新一次计时让【继续输入验证码】出现；码仍可经原闸继续，不重复发码。
     * - 格子里的内容不属于 [phone]（换号残留）：清空并推进修订号。
     */
    private fun enterCodeStep(
        phone: String,
        resendSec: Int,
    ) {
        attemptPhone = phone
        if (!consented()) {
            backToPhoneForConsent()
            return
        }
        if (codePhone != phone) dropCode()
        codePhone = phone
        mutableState.update {
            it.copy(
                step = LoginStep.CODE,
                maskedPhone = PhoneNumber.masked(phone),
                resendSec = resendSec,
                phoneError = null,
                codeError = null,
            )
        }
    }

    /** 清掉格子里属于旧号码（或已作废）的验证码：有内容才算一次换码；所属号码一并清掉。 */
    private fun dropCode() {
        if (mutableState.value.code.isNotEmpty()) codeRevision += 1
        codePhone = null
        mutableState.update { if (it.code.isEmpty() && it.codeError == null) it else it.copy(code = "", codeError = null) }
    }

    /**
     * 验证码步上发现没有同意时刻（正常不会发生：进入验证码步都已过协议闸，验证码步也没有勾选框）：验证码页任何时候都不弹确认框，
     * 回手机号页，由用户经原闸【继续输入验证码】重新同意后再进来。
     */
    private fun backToPhoneForConsent() {
        mutableState.update { it.copy(step = LoginStep.PHONE, loggingIn = false) }
        tick()
    }

    /**
     * 满 6 位的自动提交（BR-ID-05）：已有登录或发码在途时不提交、不排队（G12、G23：结算后也不补交，要再改码才算新的一次）；42901 禁用期内
     * 不提交（G25，格子保留）；这个号码的码已过期（本地有效期到了或服务端回过 20003）时不提交，提示先重新获取。
     */
    private fun submitCode() {
        val current = mutableState.value
        if (current.loggingIn || service.busy || limits.loginBlocked(config.clock())) return
        when {
            !consented() -> {
                backToPhoneForConsent()
            }

            service.issuedCodeRemainingSec(current.phone) <= 0 -> {
                mutableState.update { it.copy(codeError = texts.text(RESEND_NEEDED_KEY).ifEmpty { null }) }
            }

            else -> {
                login()
            }
        }
    }

    /** 程序清空验证码格（20002 / 20003 等）：算一次换码（之后重新输入同一个码也是新修订，可以再提交），错误提示保留。 */
    private fun clearCode(error: String?) {
        if (mutableState.value.code.isNotEmpty()) codeRevision += 1
        mutableState.update { it.copy(code = "", codeError = error ?: it.codeError) }
    }

    private fun onSent(
        outcome: SendCodeOutcome,
        fromCodePage: Boolean,
    ) {
        when (outcome.stateRaw) {
            SmsCountdown.STATE_SENT, LoginService.STATE_COUNTDOWN -> {
                val phone = outcome.phone ?: PhoneNumber.normalize(mutableState.value.phone).normalized ?: return
                // 共性第 29 条：发码成功会清空输入框，算一次换码（不论随后能否进入验证码步，旧内容都不再对应新码）
                if (outcome.stateRaw == SmsCountdown.STATE_SENT) dropCode()
                // 共性第 11 条：真正发出的那次播报一次脱敏的发送对象（倒计时内不再发出的不播报）
                if (outcome.stateRaw == SmsCountdown.STATE_SENT) {
                    announce(texts.format("login.code.sent_to", mapOf("phone" to PhoneNumber.masked(phone))))
                }
                enterCodeStep(phone, outcome.countdownSec)
            }

            SmsCountdown.STATE_RATE_LIMITED -> {
                // 限流截止时间由 LoginService 按规范化号码记下（共性第 21 条），这里只重算显示
                tick()
                toast(text(outcome.textKey))
            }

            "field_error" -> {
                val message = text(outcome.textKey)
                if (fromCodePage) toast(message)
                mutableState.update { it.copy(step = LoginStep.PHONE, phoneError = message) }
            }

            LoginErrorMapper.STATE_FORCE_UPDATE -> {
                config.actions.onForceUpdate(text(outcome.textKey))
            }

            LoginOutcome.STALE, LoginOutcome.BUSY -> {
                Unit
            }

            else -> {
                // 结果不确定（50401 等）时码可能已送达：刷新一次，手机号页随即出现【继续输入验证码】（共性第 16 条）
                tick()
                toast(formatted(outcome.textKey, outcome.trace6))
            }
        }
    }

    private fun login() {
        val current = mutableState.value
        if (service.busy || limits.loginBlocked(config.clock())) return
        attemptPhone = PhoneNumber.normalize(current.phone).normalized
        mutableState.update { it.copy(loggingIn = true, codeError = null) }
        val revision = codeRevision
        viewModelScope.launch {
            val outcome = service.login(current.phone, current.code, agreement.consentAtMillis, config.restricted)
            mutableState.update { it.copy(loggingIn = false) }
            onLoginOutcome(outcome, revision == codeRevision)
        }
    }

    private fun oauthLogin(provider: String) {
        val oauth = config.oauth ?: return
        if (service.busy || oauth.busy || oauthLimit.loginBlocked(config.clock())) return
        mutableState.update { it.copy(oauthBusy = provider) }
        viewModelScope.launch {
            // F6：授权回来后按页面此刻的同意状态复核（同 S1-1 口径），取消勾选了就不登录
            val outcome = oauth.loginWith(provider, agreement.consentAtMillis) { agreement.consentAtMillis?.takeIf { consented() } }
            mutableState.update { it.copy(oauthBusy = null) }
            onOAuthOutcome(outcome)
        }
    }

    /** 第三方登录的结果 → 页面（取消、迟到、忙不提示；BR-TEXT-14）。 */
    @Suppress("CyclomaticComplexMethod")
    private fun onOAuthOutcome(outcome: OAuthLoginOutcome) {
        val providerName = texts.text("login.provider.${outcome.provider}")
        when (outcome.stateRaw) {
            OAuthLoginOutcome.LOGGED_IN -> {
                config.actions.onOAuthLoggedIn(outcome)
            }

            OAuthLoginErrorAction.RISK_BLOCKED -> {
                mutableState.update { it.copy(dialog = LoginDialog.OAUTH_BLOCKED) }
            }

            OAuthLoginErrorAction.RETRY_AFTER -> {
                oauthLimit.limitLogin(config.clock(), outcome.disableMillis.takeIf { it > 0 } ?: DEFAULT_LIMIT_MILLIS)
                tick()
                toast(text(outcome.textKey))
            }

            OAuthLoginErrorAction.FORCE_UPDATE -> {
                config.actions.onForceUpdate(text(outcome.textKey))
            }

            LoginOutcome.AGREEMENT_REQUIRED -> {
                requireAgreement(LoginAgreementAction.OAUTH) { oauthLogin(outcome.provider) }
            }

            OAuthLoginOutcome.CANCELLED, LoginOutcome.STALE, LoginOutcome.BUSY -> {
                Unit
            }

            // F6：授权在途期间取消了同意——留在手机号页、不弹确认框，用户从第三方入口经协议闸重新同意后再发起（consent_at 取新确认时刻）
            OAuthLoginOutcome.CONSENT_WITHDRAWN -> {
                tick()
            }

            // F-X-12：Debug / Staging 的第三方入口没开本机假服务——只提示（原文来自 Debug 源码集的假适配器），不发请求
            OAuthLoginOutcome.DEBUG_ONLY -> {
                outcome.hint?.let(::toast)
            }

            else -> {
                val key = outcome.textKey ?: LoginService.GENERIC_KEY
                val message = texts.format(key, mapOf("trace6" to outcome.trace6, "provider_name" to providerName))
                toast(ResultSignals.withTrace6(message, outcome.trace6))
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // 按 BR-TEXT-14 逐个页面状态
    private fun onLoginOutcome(
        outcome: LoginOutcome,
        sameCode: Boolean = true,
    ) {
        when (outcome.stateRaw) {
            LoginOutcome.LOGGED_IN -> {
                config.actions.onLoggedIn(outcome)
            }

            // 共性第 29 条：验证码字段的结果只落到提交时的那个验证码上（[sameCode]）；换码后的迟到结果不显示、不清空。
            // APP-17a 坑 23：20002 / 20003 清空格子并留着错误（输入框不卸载，焦点与键盘不动）；过期的码要先重新获取（[submitCode]）
            "code_error", LoginErrorMapper.STATE_CODE_EXPIRED -> {
                if (sameCode) clearCode(text(outcome.textKey))
            }

            LoginErrorMapper.STATE_REGISTRATION_BLOCKED -> {
                // 账号没有创建：手机号保留、验证码清空（需要重新获取，BR-TEXT-14 login.risk_blocked.desc）
                if (sameCode) clearCode(null)
                mutableState.update { it.copy(dialog = LoginDialog.BLOCKED) }
            }

            LoginErrorMapper.STATE_DELETION_PROCESSING -> {
                if (sameCode) clearCode(null)
                mutableState.update { it.copy(dialog = LoginDialog.DELETION_PROCESSING) }
            }

            // G25：42901 不清格子，按 Retry-After 禁用提交；禁用期内的输入不提交，到期后再改码才提交
            SmsCountdown.STATE_RATE_LIMITED -> {
                limits.limitLogin(config.clock(), outcome.disableMillis)
                tick()
                toast(text(outcome.textKey))
            }

            LoginErrorMapper.STATE_FORCE_UPDATE -> {
                config.actions.onForceUpdate(text(outcome.textKey))
            }

            "field_error" -> {
                val message = text(outcome.textKey)
                if (outcome.field == LoginErrorMapper.FIELD_CODE) {
                    if (sameCode) mutableState.update { it.copy(codeError = message) }
                } else {
                    mutableState.update { it.copy(step = LoginStep.PHONE, phoneError = message) }
                }
            }

            // 验证码步没有勾选框，正常不会到这里（进入验证码步都已过协议闸）；万一没有同意时刻，验证码页也不弹确认框（G06）：回手机号页
            LoginOutcome.AGREEMENT_REQUIRED -> {
                backToPhoneForConsent()
            }

            LoginOutcome.STALE, LoginOutcome.BUSY, LoginOutcome.CODE_INCOMPLETE -> {
                Unit
            }

            else -> {
                toast(formatted(outcome.textKey, outcome.trace6))
            }
        }
    }

    /** 每秒：重发倒计时与两个限流的剩余秒数按当前时刻现算。 */
    private fun tick() {
        val now = config.clock()
        val before = mutableState.value
        updateTimers(now)
        // 共性第 11 条：验证码步上同一号码的倒计时走完、可以重新获取时播报一次（不逐秒；换号不算）
        val after = mutableState.value
        val ended = after.phone == before.phone && before.resendSec > 0 && after.resendSec == 0
        if (ended && after.step == LoginStep.CODE) announce(texts.text("login.code.can_resend"))
    }

    private fun announce(message: String) {
        if (message.isEmpty()) return
        mutableState.update { it.copy(announce = message, announceId = it.announceId + 1) }
    }

    private fun updateTimers(now: Long) {
        mutableState.update {
            it.copy(
                resendSec = service.resendRemainingSec(it.phone),
                sendLimitedSec = service.sendLimitedRemainingSec(it.phone),
                loginLimitedSec = limits.loginRemainingSec(now),
                oauthLimitedSec = oauthLimit.loginRemainingSec(now),
                issuedCodeSec = service.issuedCodeRemainingSec(it.phone),
            )
        }
    }

    private fun toast(message: String) {
        if (message.isEmpty()) return
        mutableState.update { it.copy(toast = message, toastId = it.toastId + 1) }
    }

    private fun text(key: String?): String = key?.let(texts::text).orEmpty().ifEmpty { texts.text(LoginService.GENERIC_KEY) }

    /** 错误正文与追踪编号统一生成（APP-06a 共性第 6、12 条：不依赖文案键里恰好有 {trace6}）。 */
    private fun formatted(
        key: String?,
        trace6: String,
    ): String = ResultSignals.withTrace6(texts.format(key ?: LoginService.GENERIC_KEY, mapOf("trace6" to trace6)), trace6)

    private companion object {
        const val TICK_MILLIS = 1000L

        /** 验证码已过期、要先重新获取（BR-TEXT-14 表 C；草稿键 TODO(N-26)）。 */
        const val RESEND_NEEDED_KEY = "login.code.resend_needed"
        const val DEFAULT_LIMIT_MILLIS = 5_000L
    }
}
