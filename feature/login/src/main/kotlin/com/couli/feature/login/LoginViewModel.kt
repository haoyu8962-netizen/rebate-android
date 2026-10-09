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

/**
 * 邀请码的剪贴板来源（APP-07，App 给；BR-INV-04、BR-ID-16）：
 * - [takePrefill]：游客在首页邀请提示条点了确认、码放在进程内的预填容器里；进入验证码页时取一次（取走即丢弃，之后以用户输入为准，G16）；
 * - [paste]：邀请码行【粘贴】（用户点击才读剪贴板）：只抽 6 位码，抽不到返回 null（页面轻提示「未识别到邀请码」，不清空已有输入）；
 * - [blocked]：parse 收到 42901 后的共享禁用期（与首页、SearchTyping 的粘贴入口同一个，A-R1-06）：【粘贴】置灰，点击也不读。
 */
interface InvitePasteSource {
    fun takePrefill(): String?

    fun paste(): String?

    val blocked: StateFlow<Boolean> get() = NOT_BLOCKED
}

/** 没有禁用期的来源（缺省）。 */
private val NOT_BLOCKED: StateFlow<Boolean> = MutableStateFlow(false)

/** 登录页的出口（App 给：经 Router 打开别的页、关闭登录页）。 */
@Suppress("LongParameterList") // 每个出口一个回调，都有缺省
class LoginPageActions(
    /** 关闭登录页（左上角关闭）。 */
    val onClose: () -> Unit = {},
    val onSettings: () -> Unit = {},
    /** 登录遇到问题（login.help_entry）。 */
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
 * 登录页的配置。[restricted]：从强更页进入的受限登录（APP-02c 已有的来源标记）——不显示设置、邀请码、第三方登录与帮助等业务入口
 * （BR-ID-01 细则）。[sessionExpired]：刷新失败清会话后跳来的登录（LoginSessionExpired，进页提示一次）。[oauth]：第三方登录执行器
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
    /** 邀请码的剪贴板来源（APP-07）；没有时不显示【粘贴】、不预填。受限登录不用。 */
    val invite: InvitePasteSource? = null,
)

/** 登录页两步。 */
enum class LoginStep {
    PHONE,
    CODE,
}

/** 登录页上的弹窗。 */
enum class LoginDialog {
    /** LoginAgreeConfirm：未勾选协议点获取验证码 / 登录。 */
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
    val invite: String = "",
    val inviteExpanded: Boolean = false,
    val agreed: Boolean = false,
    val dialog: LoginDialog? = null,
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
 * 登录页的状态机（APP-05a）：两步（手机号 → 验证码）、协议确认框、倒计时与限流、错误映射到页面状态。网络与会话都经 [LoginService]；
 * 页面关闭或出栈由 App 在同步出口调 [LoginService.invalidateAttempt]（共性第 9 条），这里只在用户「返回手机号步」「换号」时作废。
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

    /** 确认框同意之后要继续的动作。 */
    private var afterConfirm: (() -> Unit)? = null

    /** 上一次发起尝试时的规范化号码（换号时作废在途请求）。 */
    private var attemptPhone: String? = null

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
        val normalized = PhoneNumber.normalize(text).normalized
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

    fun onExpandInvite() = mutableState.update { it.copy(inviteExpanded = true) }

    fun onInviteChange(raw: String) = mutableState.update { it.copy(invite = raw.take(MAX_INVITE_INPUT)) }

    /** 有剪贴板来源（APP-07）时邀请码行显示【粘贴】。 */
    val invitePasteAvailable: Boolean get() = !config.restricted && config.invite != null

    /** 邀请码行【粘贴】的禁用状态（42901 共享禁用期，A-R1-06）；没有来源时恒为 false。 */
    val invitePasteBlocked: StateFlow<Boolean> get() = config.invite?.blocked ?: NOT_BLOCKED

    /**
     * 邀请码行【粘贴】：只抽码填入；抽不到轻提示「未识别到邀请码」，不清空已有输入（BR-INV-04）。42901 禁用期内执行层也拦：不读剪贴板，
     * 给限流提示（error.42901），不误报「未识别到邀请码」（A-R1-06）。
     */
    fun onPasteInvite() {
        val source = config.invite?.takeIf { !config.restricted } ?: return
        if (source.blocked.value) {
            toast(texts.text(RATE_LIMITED_KEY))
            return
        }
        val code = source.paste()
        if (code != null) {
            mutableState.update { it.copy(invite = code, inviteExpanded = true) }
        } else {
            toast(texts.text(INVITE_NOT_FOUND_KEY))
        }
    }

    /** 进入验证码页：取一次首页邀请提示条留下的预填码（G16）；输入框已有内容时以用户输入为准，预填仍丢弃。 */
    private fun fillInvitePrefill() {
        if (config.restricted) return
        val code = config.invite?.takePrefill() ?: return
        mutableState.update { if (it.invite.isEmpty()) it.copy(invite = code, inviteExpanded = true) else it }
    }

    fun onCodeChange(raw: String) = mutableState.update { it.copy(code = CodeInput.sanitize(raw), codeError = null) }

    /** 手机号页【获取验证码】与验证码页【重新获取】：都是发码（同号码倒计时内不再发出，直接回验证码步）。 */
    fun onGetCode() = requireAgreement { send(fromCodePage = false) }

    /**
     * 手机号页【继续输入验证码】（共性第 12、16 条）：这个号码有仍在有效期内、或可能已送达的码时直接回验证码步，不发码、不作废旧码；
     * 不以能否重发为条件（倒计时中、42901 限流中、发码结果不确定都可以）。协议在点登录时照常检查。
     */
    fun onContinueCode() {
        val entry = service.continueEntry(mutableState.value.phone)
        val phone = entry.phone
        if (entry.kind != PhoneEntry.CONTINUE || phone == null) return
        attemptPhone = phone
        mutableState.update {
            it.copy(step = LoginStep.CODE, maskedPhone = PhoneNumber.masked(phone), resendSec = entry.countdownSec, phoneError = null)
        }
        fillInvitePrefill()
    }

    fun onResend() = requireAgreement { send(fromCodePage = true) }

    fun onLogin() = requireAgreement(::login)

    /** 第三方登录按钮（与短信登录同一个协议确认框，BR-ID-04）。 */
    fun onThirdParty(provider: String) = requireAgreement { oauthLogin(provider) }

    val thirdParty: List<String> get() = if (config.restricted || config.oauth == null) emptyList() else config.thirdParty

    /** 验证码页返回手机号页：作废在途的登录（共性第 1 条）；已收到的码仍可在同号码下继续输入（共性第 12 条）。 */
    fun onBackToPhone() {
        service.invalidateAttempt()
        mutableState.update { it.copy(step = LoginStep.PHONE, loggingIn = false, sending = false, codeError = null) }
    }

    fun onConfirmAgree() {
        agreement.confirm(config.clock())
        mutableState.update { it.copy(agreed = true, dialog = null) }
        val next = afterConfirm
        afterConfirm = null
        if (agreement.consumeProceed()) next?.invoke()
    }

    fun onCancelConfirm() {
        agreement.cancel()
        afterConfirm = null
        mutableState.update { it.copy(dialog = null) }
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

    fun settings() = config.actions.onSettings()

    fun help() = config.actions.onHelp()

    /** 协议行的链接：打开协议页（不改变勾选）。 */
    fun openAgreement(type: String) = config.actions.onAgreement(type)

    /** 弹窗里的【联系客服申诉】/【联系客服】。 */
    fun customerService() {
        onDismissDialog()
        config.actions.onCustomerService()
    }

    private fun requireAgreement(next: () -> Unit) {
        agreement.tapLogin()
        if (agreement.showConfirm) {
            afterConfirm = next
            mutableState.update { it.copy(dialog = LoginDialog.AGREE_CONFIRM) }
            return
        }
        if (agreement.consumeProceed()) next()
    }

    private fun send(fromCodePage: Boolean) {
        if (service.busy) return
        val phone = mutableState.value.phone
        attemptPhone = PhoneNumber.normalize(phone).normalized
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val outcome = service.sendCode(phone)
            mutableState.update { it.copy(sending = false) }
            onSent(outcome, fromCodePage)
        }
    }

    private fun onSent(
        outcome: SendCodeOutcome,
        fromCodePage: Boolean,
    ) {
        when (outcome.stateRaw) {
            SmsCountdown.STATE_SENT, LoginService.STATE_COUNTDOWN -> {
                val masked = outcome.phone?.let(PhoneNumber::masked).orEmpty()
                // 共性第 11 条：真正发出的那次播报一次脱敏的发送对象（倒计时内不再发出的不播报）
                if (outcome.stateRaw == SmsCountdown.STATE_SENT && masked.isNotEmpty()) {
                    announce(texts.format("login.code.sent_to", mapOf("phone" to masked)))
                }
                mutableState.update {
                    it.copy(
                        step = LoginStep.CODE,
                        maskedPhone = masked,
                        resendSec = outcome.countdownSec,
                        code = if (outcome.stateRaw == SmsCountdown.STATE_SENT) "" else it.code,
                        codeError = null,
                    )
                }
                fillInvitePrefill()
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
        viewModelScope.launch {
            val outcome = service.login(current.phone, current.code, agreement.consentAtMillis, current.invite, config.restricted)
            mutableState.update { it.copy(loggingIn = false) }
            onLoginOutcome(outcome)
        }
    }

    private fun oauthLogin(provider: String) {
        val oauth = config.oauth ?: return
        if (service.busy || oauth.busy || oauthLimit.loginBlocked(config.clock())) return
        mutableState.update { it.copy(oauthBusy = provider) }
        viewModelScope.launch {
            val outcome = oauth.loginWith(provider, agreement.consentAtMillis)
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
                afterConfirm = { oauthLogin(outcome.provider) }
                mutableState.update { it.copy(dialog = LoginDialog.AGREE_CONFIRM) }
            }

            OAuthLoginOutcome.CANCELLED, LoginOutcome.STALE, LoginOutcome.BUSY -> {
                Unit
            }

            else -> {
                val key = outcome.textKey ?: LoginService.GENERIC_KEY
                val message = texts.format(key, mapOf("trace6" to outcome.trace6, "provider_name" to providerName))
                toast(ResultSignals.withTrace6(message, outcome.trace6))
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // 按 BR-TEXT-14 逐个页面状态
    private fun onLoginOutcome(outcome: LoginOutcome) {
        when (outcome.stateRaw) {
            LoginOutcome.LOGGED_IN -> {
                config.actions.onLoggedIn(outcome)
            }

            "code_error" -> {
                mutableState.update { it.copy(codeError = text(outcome.textKey)) }
            }

            LoginErrorMapper.STATE_CODE_EXPIRED -> {
                mutableState.update { it.copy(code = "", codeError = text(outcome.textKey)) }
            }

            LoginErrorMapper.STATE_REGISTRATION_BLOCKED -> {
                // 账号没有创建：手机号保留、验证码清空（需要重新获取，BR-TEXT-14 login.risk_blocked.desc）
                mutableState.update { it.copy(code = "", dialog = LoginDialog.BLOCKED) }
            }

            LoginErrorMapper.STATE_DELETION_PROCESSING -> {
                mutableState.update { it.copy(code = "", dialog = LoginDialog.DELETION_PROCESSING) }
            }

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
                    mutableState.update { it.copy(codeError = message) }
                } else {
                    mutableState.update { it.copy(step = LoginStep.PHONE, phoneError = message) }
                }
            }

            LoginOutcome.AGREEMENT_REQUIRED -> {
                afterConfirm = ::login
                mutableState.update { it.copy(dialog = LoginDialog.AGREE_CONFIRM) }
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
        const val MAX_INVITE_INPUT = 32

        /** 邀请码行【粘贴】抽不到码的轻提示（BR-INV-04；草稿键 TODO(N-30)）。 */
        const val INVITE_NOT_FOUND_KEY = "clipboard.login_invite.not_found"

        /** 邀请码行【粘贴】在 42901 禁用期内被点（A-R1-06）。 */
        const val RATE_LIMITED_KEY = "error.42901"
        const val DEFAULT_LIMIT_MILLIS = 5_000L
    }
}
