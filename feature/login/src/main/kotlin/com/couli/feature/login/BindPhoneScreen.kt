package com.couli.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.network.ResultSignals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI 测试与冒烟用的标记。 */
object BindPhoneTags {
    const val PAGE = "bind_phone_page"
    const val BACK = "bind_phone_back"
    const val PHONE = "bind_phone_phone"
    const val PHONE_CLEAR = "bind_phone_phone_clear"
    const val PHONE_ERROR = "bind_phone_phone_error"
    const val CODE = "bind_phone_code"
    const val CODE_ERROR = "bind_phone_code_error"
    const val GET_CODE = "bind_phone_get_code"
    const val SUBMIT = "bind_phone_submit"
    const val MERGE_OK = "bind_phone_merge_ok"
    const val MERGE_CANCEL = "bind_phone_merge_cancel"
    const val GUIDE_GO = "bind_phone_guide_go"
    const val GUIDE_SKIP = "bind_phone_guide_skip"
}

/** 绑手机页的出口（App 给：返回、客服、绑定完成）。 */
class BindPhonePageActions(
    /** 返回（没有绑定就离开：App 作废在途请求、按「未绑定离开」恢复暂缓的待办）。 */
    val onBack: () -> Unit = {},
    /** 风控弹窗【联系客服申诉】（统一客服出口）。 */
    val onCustomerService: () -> Unit = {},
    /** 经 Router 打开页面（10007 stage=cooling → DeleteAccount，APP-05b 共性第 10 条）。 */
    val onOpenRoute: (route: String) -> Unit = {},
)

/**
 * 绑手机页配置。[providerName]：当前第三方登录方式的名称（并号确认正文 error.30411.mergeable 的 {provider_name}；不知道时用微信，
 * Android 只有微信）。
 */
class BindPhonePageConfig(
    val service: BindPhoneService,
    val texts: LoginTexts,
    val providerName: String,
    val clock: () -> Long = System::currentTimeMillis,
    val actions: BindPhonePageActions = BindPhonePageActions(),
)

/** 绑手机页上的弹窗。 */
enum class BindPhoneDialog {
    /** BindPhoneMerge：30411 mergeable。 */
    MERGE,

    /** 44001：风控拦截（BR-TEXT-14 表 A：给申诉入口）。 */
    RISK,
}

/** 绑手机页的界面状态。手机号与验证码写进 SavedStateHandle（进程被回收后恢复草稿，APP-06a 共性第 20 条）。 */
data class BindPhoneUiState(
    val phone: String = "",
    val code: String = "",
    val phoneError: String? = null,
    val codeError: String? = null,
    /** 字段错误的序号：每次新的字段错误加 1（读屏即时播报一次、焦点移到错误提示，APP-05b 共性第 6 条）。 */
    val errorSeq: Int = 0,
    val sending: Boolean = false,
    val binding: Boolean = false,
    val resendSec: Int = 0,
    val sendLimitedSec: Int = 0,
    val bindLimitedSec: Int = 0,
    val dialog: BindPhoneDialog? = null,
    val mergeMasked: String = "",
    val toast: String? = null,
    val toastId: Int = 0,
    /** 读屏播报（发码成功播报一次脱敏的发送对象；倒计时结束、可重发时播报一次；APP-05b 共性第 11 条）。 */
    val announce: String? = null,
    val announceId: Int = 0,
)

/** 绑手机页的状态机（BindPhone / BindPhoneMerge；APP-05b）。网络与会话都经 [BindPhoneService]。 */
@Suppress("TooManyFunctions")
class BindPhoneViewModel(
    initial: BindPhonePageConfig,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var config = initial
    private val service: BindPhoneService get() = config.service
    private val texts: LoginTexts get() = config.texts
    private val bindLimit = LoginRateLimits()

    private val mutableState =
        MutableStateFlow(BindPhoneUiState(phone = saved[KEY_PHONE] ?: "", code = saved[KEY_CODE] ?: ""))
    val state: StateFlow<BindPhoneUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(TICK_MILLIS)
            }
        }
    }

    fun bind(latest: BindPhonePageConfig) {
        config = latest
    }

    /** 手机号输入：原样保存（不先删字符，APP-05a 共性第 15 条）；换号作废在途请求与并号凭据。 */
    fun onPhoneChange(raw: String) {
        if (PhoneNumber.normalize(raw).normalized != PhoneNumber.normalize(mutableState.value.phone).normalized) service.invalidate()
        saved[KEY_PHONE] = raw
        mutableState.update { it.copy(phone = raw, phoneError = null) }
        // 共性第 11、17 条：换号、清空只按新号码重算倒计时，不触发结束播报；切回仍在倒计时的原号码时重新跟踪它真实的截止时间
        tick(announceEnd = false)
    }

    fun onClearPhone() = onPhoneChange("")

    fun onCodeChange(raw: String) {
        val code = CodeInput.sanitize(raw)
        saved[KEY_CODE] = code
        mutableState.update { it.copy(code = code, codeError = null) }
    }

    fun onPageVisible() = tick()

    fun onGetCode() {
        if (service.busy) return
        val phone = mutableState.value.phone
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val outcome = service.sendCode(phone)
            mutableState.update { it.copy(sending = false) }
            onSent(outcome)
        }
    }

    private fun onSent(outcome: SendCodeOutcome) {
        tick()
        when (outcome.stateRaw) {
            SmsCountdown.STATE_SENT -> {
                // 共性第 11 条：发码成功播报一次脱敏的发送对象（换号、离页后的旧结果是 stale，不播报）
                outcome.phone?.let { announce(texts.format("login.code.sent_to", mapOf("phone" to PhoneNumber.masked(it)))) }
            }

            LoginService.STATE_COUNTDOWN, BindOutcome.STALE, BindOutcome.BUSY -> {
                Unit
            }

            // 共性第 10、16 条：发码收到 10007 stage=cooling → 经 Router 打开注销进度页（不弹提示）
            BindOutcome.OPEN_ROUTE -> {
                outcome.route?.let(config.actions.onOpenRoute)
            }

            SmsCountdown.STATE_RATE_LIMITED -> {
                toast(text(outcome.textKey))
            }

            else -> {
                // 字段错误是输入框旁的提示（不附追踪编号）；其余轻提示正文与追踪编号统一生成
                when {
                    outcome.textKey == RISK_KEY -> mutableState.update { it.copy(dialog = BindPhoneDialog.RISK) }
                    outcome.field == LoginErrorMapper.FIELD_PHONE -> fieldError(phone = text(outcome.textKey))
                    else -> toast(formatted(outcome.textKey, outcome.trace6))
                }
            }
        }
    }

    fun onSubmit() {
        val current = mutableState.value
        if (service.busy || bindLimit.loginBlocked(config.clock())) return
        mutableState.update { it.copy(binding = true, codeError = null, phoneError = null) }
        viewModelScope.launch {
            val outcome = service.bind(current.phone, current.code)
            mutableState.update { it.copy(binding = false) }
            onBound(outcome)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun onBound(outcome: BindOutcome) {
        when (outcome.actionRaw) {
            BindPhoneAction.DONE, BindOutcome.STALE, BindOutcome.BUSY, BindOutcome.CODE_INCOMPLETE -> {
                Unit
            }

            BindOutcome.OPEN_ROUTE -> {
                outcome.route?.let(config.actions.onOpenRoute)
            }

            BindPhoneAction.MERGE_CONFIRM -> {
                mutableState.update { it.copy(dialog = BindPhoneDialog.MERGE, mergeMasked = outcome.maskedPhone) }
            }

            BindPhoneAction.RETRY_AFTER -> {
                bindLimit.limitLogin(config.clock(), outcome.disableMillis.takeIf { it > 0 } ?: DEFAULT_LIMIT_MILLIS)
                tick()
                toast(text(outcome.textKey))
            }

            else -> {
                // 字段错误不附追踪编号；轻提示正文与追踪编号统一生成
                when {
                    outcome.textKey == RISK_KEY -> {
                        mutableState.update { it.copy(dialog = BindPhoneDialog.RISK) }
                    }

                    outcome.field == LoginErrorMapper.FIELD_PHONE || outcome.actionRaw == BindOutcome.PHONE_INVALID -> {
                        fieldError(
                            phone = text(outcome.textKey),
                        )
                    }

                    outcome.field == LoginErrorMapper.FIELD_CODE -> {
                        fieldError(code = text(outcome.textKey))
                    }

                    else -> {
                        toast(formatted(outcome.textKey, outcome.trace6))
                    }
                }
            }
        }
    }

    /** 并号确认框【并入】：锁定契约没有并号接口——不发请求、不并号，给明确的「暂不可用」（TODO(N-29) MERGE-SUCCESS）。 */
    fun onMergeConfirm() {
        val result = service.decideMerge(MergeDecision.CONFIRM)
        mutableState.update { it.copy(dialog = null) }
        if (result.resultRaw == MergeDecision.UNAVAILABLE) toast(texts.text(BindPhoneService.MERGE_UNAVAILABLE_KEY))
    }

    /** 并号确认框【取消】、遮罩、返回：不并号、不保存会话，留在本页，输入保留。 */
    fun onMergeCancel() {
        service.decideMerge(MergeDecision.CANCEL)
        mutableState.update { it.copy(dialog = null) }
    }

    fun onDismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun customerService() {
        onDismissDialog()
        config.actions.onCustomerService()
    }

    fun onBack() = config.actions.onBack()

    fun onToastShown(id: Int) = mutableState.update { if (it.toastId == id) it.copy(toast = null) else it }

    val providerName: String get() = config.providerName

    private fun fieldError(
        phone: String? = null,
        code: String? = null,
    ) = mutableState.update { it.copy(phoneError = phone, codeError = code, errorSeq = it.errorSeq + 1) }

    /**
     * 正在跟踪的重发倒计时：规范化号码与它真实的截止时刻（共性第 17 条）。只在这个号码仍是输入框里的号码、且真实截止时刻已到时播报一次
     * 「可以重新获取」；换号、清空时改跟踪新号码（没有倒计时就不跟踪），旧号码的倒计时结束不播报。
     */
    private var watched: Pair<String, Long>? = null

    private fun tick(announceEnd: Boolean = true) {
        val now = config.clock()
        mutableState.update {
            it.copy(
                resendSec = service.resendRemainingSec(it.phone),
                sendLimitedSec = service.sendLimitedRemainingSec(it.phone),
                bindLimitedSec = bindLimit.loginRemainingSec(now),
            )
        }
        val current = mutableState.value
        val phone = PhoneNumber.normalize(current.phone).normalized
        val deadline = service.resendDeadline(current.phone)
        val tracked = watched
        when {
            // 这个号码正在倒计时：跟踪它真实的截止时刻（重新发码会更新）
            phone != null && deadline != null && current.resendSec > 0 -> {
                watched = phone to deadline
            }

            // 换号、清空：不播报，改跟踪新号码（没有倒计时）
            tracked == null || tracked.first != phone || !announceEnd -> {
                watched = null
            }

            // 同一号码、真实截止时刻已到：播报一次
            now >= tracked.second -> {
                watched = null
                announce(texts.text("bind_phone.can_resend"))
            }
        }
    }

    private fun announce(message: String) {
        if (message.isEmpty()) return
        mutableState.update { it.copy(announce = message, announceId = it.announceId + 1) }
    }

    private fun toast(message: String) {
        if (message.isEmpty()) return
        mutableState.update { it.copy(toast = message, toastId = it.toastId + 1) }
    }

    private fun text(key: String?): String = key?.let(texts::text).orEmpty().ifEmpty { texts.text(LoginService.GENERIC_KEY) }

    private fun formatted(
        key: String?,
        trace6: String,
    ): String = ResultSignals.withTrace6(texts.format(key ?: LoginService.GENERIC_KEY, mapOf("trace6" to trace6)), trace6)

    private companion object {
        const val KEY_PHONE = "bind_phone.phone"
        const val KEY_CODE = "bind_phone.code"
        const val TICK_MILLIS = 1000L
        const val DEFAULT_LIMIT_MILLIS = 5_000L
        const val RISK_KEY = "error.44001"
    }
}

/**
 * 绑手机页（BindPhone / BindPhoneMerge；APP-05b）：返回 + 居中标题、浅底说明条、白卡片里手机号（+86 / 清除）与验证码（右侧「获取验证码 /
 * N 秒后重新获取」）、底部【绑定】。底部主操作随键盘上移（整页按输入法让位，内容区可滚动；APP-05b 共性第 7 条）；字段错误即时播报一次、
 * 焦点移到错误提示、错误关联到输入框、输入保留（共性第 6 条）。
 */
@Composable
fun BindPhoneScreen(
    config: BindPhonePageConfig,
    modifier: Modifier = Modifier,
) {
    val model: BindPhoneViewModel = viewModel { BindPhoneViewModel(config, createSavedStateHandle()) }
    model.bind(config)
    val state by model.state.collectAsStateWithLifecycle()
    val texts = config.texts
    BackHandler { model.onBack() }
    LifecycleResumeEffect(model) {
        model.onPageVisible()
        onPauseOrDispose { }
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(BindPhoneTags.PAGE),
    ) {
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .imePadding(),
        ) {
            BindTopBar(model, texts)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = CouliSpacing.space4)
                        .padding(top = CouliSpacing.space2, bottom = CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                InfoStrip(texts.text("invite.bind_phone_guide.body"))
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .couliCard()
                            .padding(CouliSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
                ) {
                    BindPhoneField(model, state, texts)
                    BindCodeField(model, state, texts)
                }
            }
            CouliButton(
                text = texts.text("bind_phone.submit"),
                onClick = model::onSubmit,
                enabled = PhoneNumber.normalize(state.phone).valid && CodeInput.accept(state.code).complete && state.bindLimitedSec == 0,
                loading = state.binding,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                        .testTag(BindPhoneTags.SUBMIT),
            )
        }
        state.toast?.let { message ->
            val id = state.toastId
            LaunchedEffect(id) {
                delay(TOAST_MILLIS)
                model.onToastShown(id)
            }
            CouliToast(
                message = message,
                icon = null,
                announcementId = id,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = CouliSpacing.space12, start = CouliSpacing.space4, end = CouliSpacing.space4),
            )
        }
    }
    BindPhoneDialogs(model, state, texts)
    val view = LocalView.current
    LaunchedEffect(state.announceId) {
        @Suppress("DEPRECATION") // 与 CouliToast 同：需要立即读出
        state.announce?.let(view::announceForAccessibility)
    }
}

@Composable
private fun BindTopBar(
    model: BindPhoneViewModel,
    texts: LoginTexts,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .padding(horizontal = CouliSpacing.space1),
        contentAlignment = Alignment.Center,
    ) {
        CouliIconButton(
            icon = CouliIcons.back,
            label = texts.text("login.back"),
            onClick = model::onBack,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .testTag(BindPhoneTags.BACK),
        )
        Text(
            text = texts.text("invite.bind_phone_guide.title"),
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .padding(horizontal = CouliSizes.touchTarget)
                    .semantics { heading() },
        )
    }
}

@Composable
private fun InfoStrip(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(CouliIcons.info, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary)
    }
}

@Composable
private fun BindPhoneField(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    texts: LoginTexts,
) {
    val label = texts.text("login.phone.label")
    val prefix = texts.text("login.phone.prefix")
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
        BindInputBox(error = state.phoneError != null) {
            Text(text = prefix, style = CouliTypography.body, color = CouliColors.textPrimary, modifier = Modifier.clearAndSetSemantics {})
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = CouliSpacing.space3)
                        .size(width = CouliSizes.hairline, height = CouliSpacing.space6)
                        .background(CouliColors.borderSubtle),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.phone.isEmpty()) {
                    Text(
                        text = texts.text("login.phone.placeholder"),
                        style = CouliTypography.body,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                BasicTextField(
                    value = state.phone,
                    onValueChange = model::onPhoneChange,
                    singleLine = true,
                    textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = CouliSpacing.space2)
                            .testTag(BindPhoneTags.PHONE)
                            .semantics {
                                contentDescription = "$label $prefix"
                                // 错误说明关联到输入框（共性第 6 条）
                                state.phoneError?.let { error(it) }
                            },
                )
            }
            if (state.phone.isNotEmpty()) {
                CouliIconButton(
                    icon = CouliIcons.close,
                    label = texts.text("login.phone.clear"),
                    onClick = model::onClearPhone,
                    tint = CouliColors.textSecondary,
                    iconSize = CouliSizes.iconSmall,
                    modifier = Modifier.testTag(BindPhoneTags.PHONE_CLEAR),
                )
            }
        }
        FieldError(state.phoneError, state.errorSeq, BindPhoneTags.PHONE_ERROR)
    }
}

@Composable
private fun BindCodeField(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    texts: LoginTexts,
) {
    val label = texts.text("login.code.label")
    val waiting = state.resendSec > 0
    val limited = state.sendLimitedSec > 0
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
        // 200% 字号或窄屏放不下时，「获取验证码」换到输入框下方（按宽度换行，文字不截断）
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = INPUT_MIN_HEIGHT.dp)
                    .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                    .border(
                        CouliSizes.hairline,
                        if (state.codeError != null) CouliColors.statusErrorText else CouliColors.borderControl,
                        RoundedCornerShape(CouliRadius.button),
                    ).padding(start = CouliSpacing.space3, end = CouliSpacing.space1),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = CODE_MIN_WIDTH.dp)
                        .padding(vertical = CouliSpacing.space3),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.code.isEmpty()) {
                    Text(
                        text = texts.text("bind_phone.code.placeholder"),
                        style = CouliTypography.body,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                BasicTextField(
                    value = state.code,
                    onValueChange = model::onCodeChange,
                    singleLine = true,
                    textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(BindPhoneTags.CODE)
                            .semantics {
                                contentDescription = label
                                state.codeError?.let { error(it) }
                            },
                )
            }
            val getCode =
                if (waiting) {
                    texts.format("bind_phone.countdown", mapOf("sec" to state.resendSec.toString()))
                } else {
                    texts.text("login.get_code")
                }
            val enabled = !waiting && !limited && !state.sending && PhoneNumber.normalize(state.phone).valid
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = CouliSizes.hairline, height = CouliSpacing.space6).background(CouliColors.borderSubtle))
                Box(
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = CouliSizes.touchTarget)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled,
                                role = Role.Button,
                                onClick = model::onGetCode,
                            ).padding(horizontal = CouliSpacing.space3)
                            .testTag(BindPhoneTags.GET_CODE),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = getCode,
                        style = CouliTypography.footnote.copy(fontFeatureSettings = "tnum"),
                        color = if (enabled) CouliColors.textLink else CouliColors.textSecondary,
                    )
                }
            }
        }
        FieldError(state.codeError, state.errorSeq, BindPhoneTags.CODE_ERROR)
    }
}

/**
 * 字段错误提示（APP-05b 共性第 6 条，03 §10.5）：每次新的错误（[seq] 变化）读屏即时播报一次，无障碍焦点移到这条提示；提示本身是礼貌
 * 级以上的实时区域。输入框的 error 语义同时带上这句，读屏回到输入框时也能读到。
 */
@Composable
private fun FieldError(
    message: String?,
    seq: Int,
    tag: String,
) {
    if (message == null) return
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    LaunchedEffect(seq) {
        @Suppress("DEPRECATION") // 与 CouliToast 同：错误态需要立即读出
        view.announceForAccessibility(message)
        delay(ERROR_FOCUS_DELAY_MILLIS)
        runCatching { focus.requestFocus() }
    }
    Text(
        text = message,
        style = CouliTypography.footnote,
        color = CouliColors.statusErrorText,
        modifier =
            Modifier
                .focusRequester(focus)
                .focusable()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
    )
}

@Composable
private fun BindInputBox(
    error: Boolean,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = INPUT_MIN_HEIGHT.dp)
                .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                .border(
                    CouliSizes.hairline,
                    if (error) CouliColors.statusErrorText else CouliColors.borderControl,
                    RoundedCornerShape(CouliRadius.button),
                ).padding(start = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun BindPhoneDialogs(
    model: BindPhoneViewModel,
    state: BindPhoneUiState,
    texts: LoginTexts,
) {
    when (state.dialog) {
        BindPhoneDialog.MERGE -> {
            CouliDialog(
                title = texts.text("bind_phone.merge.title"),
                confirmText = texts.text("bind_phone.merge.ok"),
                onConfirm = model::onMergeConfirm,
                onDismissRequest = model::onMergeCancel,
                dismissText = texts.text("bind_phone.merge.cancel"),
                // 正文 error.30411.mergeable + 信息块（DesignSystem 弹窗给了 body 就不再画 message，正文放进 body）
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) {
                        Text(
                            text = texts.format("error.30411.mergeable", mapOf("provider_name" to model.providerName)),
                            style = CouliTypography.footnote,
                            color = CouliColors.textPrimary,
                        )
                        MergeTarget(texts, state.mergeMasked)
                    }
                },
            )
        }

        // 44001：标题 error.44001，【知道了】与【联系客服申诉】（BR-TEXT-14 表 A；没有绑手机专用说明句，APP-05b 共性第 5 条）
        BindPhoneDialog.RISK -> {
            CouliDialog(
                title = texts.text("error.44001"),
                confirmText = texts.text("login.risk_blocked.ok"),
                onConfirm = model::onDismissDialog,
                onDismissRequest = model::onDismissDialog,
                dismissText = texts.text("cs.appeal_entry"),
                onDismiss = model::customerService,
            )
        }

        null -> {
            Unit
        }
    }
}

/** 并号确认里的信息块：并入图标、「并入到」、「手机号账号 138****5678」（设计稿 BindPhoneMerge）。 */
@Composable
private fun MergeTarget(
    texts: LoginTexts,
    masked: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Icon(CouliIcons.merge, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
        Column {
            Text(text = texts.text("bind_phone.merge.into"), style = CouliTypography.caption, color = CouliColors.textSecondary)
            Text(
                text = texts.format("bind_phone.merge.account", mapOf("phone" to masked)),
                style = CouliTextStyles.bodyMedium,
                color = CouliColors.textPrimary,
            )
        }
    }
}

/**
 * 第三方新号绑手机引导（LoginBindPhoneGuide，BR-INV-03 细则 ①、F-INV-10）：标题与正文用契约 invite.bind_phone_guide.*，【暂不】/【去绑定】。
 * 登录页关闭后盖在恢复页之上（不新增路由，rule-gaps ROUTES / GUIDE-ROUTE）。点遮罩、返回按【暂不】处理。
 */
@Composable
fun BindPhoneGuideDialog(
    texts: LoginTexts,
    onSkip: () -> Unit,
    onGo: () -> Unit,
) {
    CouliDialog(
        title = texts.text("invite.bind_phone_guide.title"),
        message = texts.text("invite.bind_phone_guide.body"),
        confirmText = texts.text("invite.bind_phone_guide.go"),
        onConfirm = onGo,
        onDismissRequest = onSkip,
        dismissText = texts.text("invite.bind_phone_guide.skip"),
    )
}

private const val TOAST_MILLIS = 2_000L
private const val INPUT_MIN_HEIGHT = 52
private const val CODE_MIN_WIDTH = 96
private const val CONTENT_MAX_WIDTH = 480
private const val ERROR_FOCUS_DELAY_MILLIS = 600L
