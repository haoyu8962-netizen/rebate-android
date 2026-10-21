package com.couli.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
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
import kotlinx.coroutines.delay

/** UI 测试与冒烟用的标记。 */
object LoginTags {
    const val PAGE = "login_page"
    const val CONTENT = "login_content"
    const val RESTRICTED = "login_restricted"
    const val PHONE = "login_phone"
    const val PHONE_CLEAR = "login_phone_clear"
    const val AGREE = "login_agree"
    const val LINK_AGREEMENT = "login_link_agreement"
    const val LINK_PRIVACY = "login_link_privacy"
    const val GET_CODE = "login_get_code"
    const val CONTINUE_CODE = "login_continue_code"
    const val CODE = "login_code"
    const val CODE_CELL = "login_code_cell_"
    const val RESEND = "login_resend"

    /** 求助入口（手机号页 login.help_entry、验证码页 login.code.help_entry；一页只有一个）。 */
    const val HELP = "login_help"
    const val CLOSE = "login_close"
    const val BACK = "login_back"

    /** 协议确认框主按钮（同意并获取验证码 / 同意并登录，按动作取键）。 */
    const val CONFIRM_PRIMARY = "login_confirm_primary"
    const val CONFIRM_LINK_AGREEMENT = "login_confirm_link_agreement"
    const val CONFIRM_LINK_PRIVACY = "login_confirm_link_privacy"
    const val THIRD_PARTY = "login_third_party"

    /** 第三方登录按钮：`login_oauth_<provider>`。 */
    const val OAUTH_PREFIX = "login_oauth_"
    const val OAUTH_BLOCKED_APPEAL = "login_oauth_blocked_appeal"
}

/** 协议类型（Agreement 路由参数 type）。 */
object LoginAgreementType {
    const val AGREEMENT = "agreement"
    const val PRIVACY = "privacy"
}

/**
 * 登录页（Login / LoginCode / LoginAgreeConfirm / LoginCodeBlocked / LoginDeletionProcessing / LoginSessionExpired；APP-05a）。
 * 读屏顺序按 A11yFocusLogin：关闭 → 手机号 → 协议勾选 →《用户协议》→《隐私政策》→ 获取验证码 → 其他登录方式 → 登录遇到问题；品牌方块
 * 对读屏隐藏。命中区不小于 48dp，两个协议链接与勾选单元各自独立（共性第 6 条）。
 *
 * APP-17a（登录改版闸前）：只删入口（设置、邀请码入口与手填框、【粘贴】、告知句、验证码页勾选行与【登录】）、转发事件、改读屏名与文案键；
 * 协议整句、确认框按动作取键、验证码满 6 位自动提交、验证码页求助入口都来自 [LoginViewModel]。DS-02 组件与排版在视觉闸后。
 */
@Composable
fun LoginScreen(
    config: LoginPageConfig,
    modifier: Modifier = Modifier,
) {
    val model: LoginViewModel = viewModel { LoginViewModel(config) }
    model.bind(config)
    val state by model.state.collectAsStateWithLifecycle()
    val texts = config.texts
    BackHandler {
        if (state.step == LoginStep.CODE) model.onBackToPhone() else model.onClose()
    }
    // 共性第 20 条：页面重新出现（回前台、从协议页等返回）时立即按当前时钟重算倒计时与限流
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
                .testTag(LoginTags.PAGE),
    ) {
        // 大屏（最小宽度 ≥ 600dp 的平板、折叠屏内屏）：内容限定最大宽度并水平居中，照常可滚动（共性第 17 条，03 §10.4）；
        // 手机宽度下等同铺满。输入状态在 ViewModel 里，折叠 / 展开、窗口大小变化不丢
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .testTag(LoginTags.CONTENT),
        ) {
            TopBar(model, state, texts)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = CouliSpacing.space4)
                        .padding(top = CouliSpacing.space4, bottom = CouliSpacing.space4)
                        .then(if (config.restricted) Modifier.testTag(LoginTags.RESTRICTED) else Modifier),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
            ) {
                if (state.step == LoginStep.PHONE) PhoneStep(model, state, config) else CodeStep(model, state, config)
            }
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
    LoginDialogs(model, state, texts)
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(state.announceId) {
        @Suppress("DEPRECATION") // 与 CouliToast 同：需要立即读出
        state.announce?.let(view::announceForAccessibility)
    }
}

@Composable
private fun TopBar(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .padding(horizontal = CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.step == LoginStep.CODE) {
            CouliIconButton(
                icon = CouliIcons.back,
                label = texts.text("login.back"),
                onClick = model::onBackToPhone,
                modifier = Modifier.testTag(LoginTags.BACK),
            )
        } else {
            CouliIconButton(
                icon = CouliIcons.close,
                label = texts.text("login.close"),
                onClick = model::onClose,
                modifier = Modifier.testTag(LoginTags.CLOSE),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PhoneStep(
    model: LoginViewModel,
    state: LoginUiState,
    config: LoginPageConfig,
) {
    val texts = config.texts
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        BrandMark(texts.text("login.brand"))
        Text(
            text = texts.text("login.title"),
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        PhoneField(model, state, texts)
        AgreementRow(model, state, texts)
    }
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        // 发码限流只禁用「获取验证码」（重新发码）；继续输入已收到的码是独立入口，不受限流与倒计时影响（共性第 11、12、16 条）
        CouliButton(
            text = texts.text("login.get_code"),
            onClick = model::onGetCode,
            enabled = state.sendLimitedSec == 0 && state.phone.isNotBlank(),
            loading = state.sending,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(LoginTags.GET_CODE),
        )
        if (state.issuedCodeSec > 0) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextLink(
                    text = texts.text("login.code.continue"),
                    onClick = model::onContinueCode,
                    modifier = Modifier.testTag(LoginTags.CONTINUE_CODE),
                )
            }
        }
    }
    // 共性第 28 条：受限登录保留第三方入口（只隐藏帮助等业务入口）
    if (model.thirdParty.isNotEmpty()) ThirdPartySlot(model, state, texts)
    if (!config.restricted) HelpEntry(model, texts.text("login.help_entry"))
}

@Composable
private fun CodeStep(
    model: LoginViewModel,
    state: LoginUiState,
    config: LoginPageConfig,
) {
    val texts = config.texts
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        Text(
            text = texts.text("login.code.title"),
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = texts.format("login.code.sent_to", mapOf("phone" to state.maskedPhone)),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
        )
    }
    // 满 6 位自动提交（没有【登录】按钮、没有勾选行）；失败清格时输入框不卸载，焦点与键盘留在格子上（坑 23）
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        CodeCells(model, state, texts)
        state.codeError?.let { message ->
            Text(text = message, style = CouliTypography.footnote, color = CouliColors.statusErrorText)
        }
        ResendRow(model, state, texts)
    }
    // 验证码页唯一的求助入口「收不到验证码？」（坑 18、25）；受限登录不显示业务入口
    if (!config.restricted) HelpEntry(model, texts.text("login.code.help_entry"))
}

/** 品牌方块 72 × 72（设计稿固定尺寸，不随字号放大；对读屏隐藏，标题已说明页面）。 */
@Composable
private fun BrandMark(text: String) {
    val density = LocalDensity.current
    val fixed = with(density) { BRAND_FONT_DP.dp.toSp() }
    Box(
        modifier =
            Modifier
                .size(BRAND_SIZE.dp)
                .background(CouliColors.brandPrimary, RoundedCornerShape(BRAND_RADIUS.dp))
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = CouliColors.textInverse,
            style = TextStyle(fontSize = fixed, lineHeight = fixed * BRAND_LINE, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun PhoneField(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    val label = texts.text("login.phone.label")
    val prefix = texts.text("login.phone.prefix")
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
        InputBox(error = state.phoneError != null) {
            Text(
                text = prefix,
                style = CouliTypography.body,
                color = CouliColors.textPrimary,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = CouliSpacing.space3)
                        .width(CouliSizes.hairline)
                        .height(CouliSpacing.space6)
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
                            .testTag(LoginTags.PHONE)
                            .semantics {
                                contentDescription = "$label $prefix"
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
                    modifier = Modifier.testTag(LoginTags.PHONE_CLEAR),
                )
            }
        }
        state.phoneError?.let { message ->
            Text(text = message, style = CouliTypography.footnote, color = CouliColors.statusErrorText)
        }
    }
}

/** 白底描边输入框：最小高 52，圆角 12。 */
@Composable
private fun InputBox(
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

/**
 * 协议行（共性第 6 条）：勾选单元（复选框 + 整句开头到第一个链接）、《用户协议》、《隐私政策》三个命中区各自独立、各不小于 48dp；窄屏或大字号时
 * 按节换行。文字来自整句 login.agree.text（[AgreementSentence]，G16），勾选单元的读屏名是整句。
 */
@Composable
private fun AgreementRow(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    val sentence = agreementSentence(texts, AGREE_TEXT_KEY)
    val checkedText = texts.text(if (state.agreed) "login.agree.checked" else "login.agree.unchecked")
    val style = CouliTypography.caption
    val lead = sentence.spans.firstOrNull()?.let { sentence.text.substring(0, it.start) } ?: sentence.text
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = CouliSizes.touchTarget)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Checkbox,
                        onClick = model::onToggleAgreement,
                    ).semantics(mergeDescendants = true) {
                        contentDescription = sentence.accessibilityText
                        toggleableState = ToggleableState(state.agreed)
                        stateDescription = checkedText
                    }.testTag(LoginTags.AGREE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            CheckMark(state.agreed)
            Text(text = lead, style = style, color = CouliColors.textSecondary, modifier = Modifier.clearAndSetSemantics {})
        }
        Spacer(Modifier.width(CouliSpacing.space1))
        AgreementLinks(sentence, texts, style, CouliColors.textSecondary, AGREEMENT_LINK_TAGS, model::openAgreement)
    }
}

/** 协议整句：链接按《…》原文在整句里定位（[AgreementSentence.of]）。 */
private fun agreementSentence(
    texts: LoginTexts,
    key: String,
): AgreementSentence =
    AgreementSentence.of(
        texts.text(key),
        linkedMapOf(
            LoginAgreementType.AGREEMENT to texts.text("login.agree.agreement"),
            LoginAgreementType.PRIVACY to texts.text("login.agree.privacy"),
        ),
    )

/**
 * 整句里第一个链接起的部分：链接（独立命中区）与链接之间、之后的普通文字依次排开；字典里没能定位到的协议仍补一个独立链接（不让协议入口
 * 因文案调整而消失）。[tags] 协议类型 → 测试标记。
 */
@Composable
@Suppress("LongParameterList") // 整句、文案、样式与出口逐项给（登录页协议行与确认框正文共用）
private fun AgreementLinks(
    sentence: AgreementSentence,
    texts: LoginTexts,
    style: TextStyle,
    color: Color,
    tags: Map<String, String>,
    onOpen: (type: String) -> Unit,
) {
    sentence.spans.forEachIndexed { index, span ->
        val tail = sentence.text.substring(span.end, sentence.spans.getOrNull(index + 1)?.start ?: sentence.text.length)
        TextLink(
            text = sentence.text.substring(span.start, span.end),
            onClick = { onOpen(span.type) },
            style = style,
            padding = 0.dp,
            modifier = Modifier.testTag(tags.getValue(span.type)),
        )
        if (tail.isNotEmpty()) Text(text = tail, style = style, color = color)
    }
    tags.keys.filter { type -> sentence.spans.none { it.type == type } }.forEach { type ->
        TextLink(
            text = texts.text("login.agree.$type"),
            onClick = { onOpen(type) },
            style = style,
            padding = 0.dp,
            modifier = Modifier.testTag(tags.getValue(type)),
        )
    }
}

@Composable
private fun CheckMark(checked: Boolean) {
    val shape = RoundedCornerShape(CouliRadius.small)
    Box(
        modifier =
            Modifier
                .size(CouliSizes.iconMedium)
                .background(if (checked) CouliColors.brandPrimary else CouliColors.backgroundSurface, shape)
                .then(if (checked) Modifier else Modifier.border(CouliSizes.hairline, CouliColors.borderControl, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(CouliIcons.check, contentDescription = null, tint = CouliColors.textInverse, modifier = Modifier.size(CouliSizes.iconTiny))
        }
    }
}

/** 文字链接：命中区高不小于 48dp。 */
@Composable
private fun TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = CouliTypography.footnote,
    color: Color = CouliColors.textLink,
    padding: Dp = CouliSpacing.space2,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = CouliSizes.touchTarget)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = color)
    }
}

/**
 * 6 个验证码格（共性第 7 条）：格宽按可用宽度均分（格间 8dp），格高随字号缩放（数字行高 + 上下留白，不小于 56dp）；320dp 宽与 200% 字号下
 * 六位都完整可见。透明输入框盖在格子上接收输入（数字键盘），读屏把整组当作一个「验证码」输入框。
 */
@Composable
private fun CodeCells(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = CouliSpacing.space2
        val cellWidth = (maxWidth - gap * (CodeInput.LENGTH - 1)) / CodeInput.LENGTH
        val style =
            CouliTextStyles.amountTitle.copy(
                fontSize = CouliTypography.heading.fontSize,
                lineHeight = CouliTypography.heading.lineHeight,
            )
        val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
        val cellHeight = maxOf(CODE_CELL_MIN_HEIGHT.dp, lineHeight + CouliSpacing.space5)
        Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.clearAndSetSemantics {}) {
            repeat(CodeInput.LENGTH) { index ->
                val digit =
                    state.code
                        .getOrNull(index)
                        ?.toString()
                        .orEmpty()
                val current = index == state.code.length.coerceAtMost(CodeInput.LENGTH - 1) && state.code.length < CodeInput.LENGTH
                val borderColor =
                    when {
                        state.codeError != null -> CouliColors.statusErrorText
                        current -> CouliColors.focusRing
                        else -> CouliColors.borderControl
                    }
                Box(
                    modifier =
                        Modifier
                            .width(cellWidth)
                            .height(cellHeight)
                            .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                            .border(
                                if (current) CouliSizes.hairline * 2 else CouliSizes.hairline,
                                borderColor,
                                RoundedCornerShape(CouliRadius.button),
                            ).testTag(LoginTags.CODE_CELL + index),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = digit, style = style, color = CouliColors.textPrimary, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
        BasicTextField(
            value = state.code,
            onValueChange = model::onCodeChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier =
                Modifier
                    .matchParentSize()
                    .focusRequester(focus)
                    .testTag(LoginTags.CODE)
                    .semantics {
                        contentDescription = texts.text("login.code.label")
                        state.codeError?.let { error(it) }
                    },
        )
    }
}

@Composable
private fun ResendRow(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val waiting = state.resendSec > 0
        val limited = state.sendLimitedSec > 0
        val label =
            if (waiting) {
                texts.format("login.code.countdown", mapOf("n" to state.resendSec.toString()))
            } else {
                texts.text("login.code.resend")
            }
        TextLink(
            text = label,
            onClick = model::onResend,
            enabled = !waiting && !limited && !state.sending,
            color = if (waiting || limited) CouliColors.textSecondary else CouliColors.textLink,
            style = CouliTypography.footnote.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.testTag(LoginTags.RESEND),
        )
    }
}

/**
 * 第三方登录（APP-05b，设计稿 Login「其他登录方式」）：分隔线 + 圆形描边图标按钮（48dp，图形不随字号放大）与下方名称。Android 只有微信
 * 候选；没有可显示的按钮时整块不出现（正式包微信适配器不可用，BR-ID-04 / 任务书 §2.2）。读屏名称「微信登录」，按钮角色；42901 期间与
 * 登录进行中禁用。
 */
@Composable
private fun ThirdPartySlot(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(LoginTags.THIRD_PARTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(CouliSizes.hairline)
                    .background(CouliColors.borderSubtle),
            )
            Text(text = texts.text("login.other_methods"), style = CouliTypography.caption, color = CouliColors.textSecondary)
            Box(
                Modifier
                    .weight(1f)
                    .height(CouliSizes.hairline)
                    .background(CouliColors.borderSubtle),
            )
        }
        // 「上次登录」只挂在实际显示的那个按钮上（[LoginPageEntries]，C08）：闸前只进读屏说明，可视标签（DS-02 LastLoginTag）在视觉闸后
        val entries = model.entries
        FlowRow(horizontalArrangement = Arrangement.spacedBy(OAUTH_GAP.dp, Alignment.CenterHorizontally)) {
            entries.providers.forEach { provider ->
                val enabled = state.oauthBusy == null && state.oauthLimitedSec == 0 && !state.sending && !state.loggingIn
                val lastTag = if (entries.lastMethod == provider) texts.text("login.last_method_tag") else null
                Column(
                    modifier =
                        Modifier
                            .widthIn(min = OAUTH_MIN_WIDTH.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled,
                                role = Role.Button,
                                onClick = { model.onThirdParty(provider) },
                            ).semantics(mergeDescendants = true) {
                                // 读屏名称「微信登录」（下方的「微信」标签不再重复朗读）；点击动作由 clickable 提供
                                contentDescription = texts.text("login.$provider")
                                lastTag?.let { stateDescription = it }
                            }.testTag(LoginTags.OAUTH_PREFIX + provider),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(CouliSizes.touchTarget)
                                .background(CouliColors.backgroundSurface, CircleShape)
                                .border(CouliSizes.hairline, CouliColors.borderSubtle, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = CouliIcons.chat,
                            contentDescription = null,
                            tint = if (enabled) CouliColors.textSecondary else CouliColors.borderControl,
                            modifier = Modifier.size(CouliSizes.iconMedium),
                        )
                    }
                    Text(
                        text = texts.text("login.$provider.short"),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/** 求助入口（一页一个）：[label] 是可见文字，也是读屏名；点击由 App 按 help_links.login_help 打开（坑 18）。 */
@Composable
private fun HelpEntry(
    model: LoginViewModel,
    label: String,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextLink(text = label, onClick = { model.help() }, modifier = Modifier.testTag(LoginTags.HELP))
    }
}

@Composable
private fun LoginDialogs(
    model: LoginViewModel,
    state: LoginUiState,
    texts: LoginTexts,
) {
    when (state.dialog) {
        // G10：四个键按动作取（获取验证码 / 第三方 / 其余）；次按钮【不同意】、点遮罩、返回都只关弹窗、留在本页
        LoginDialog.AGREE_CONFIRM -> {
            val keys = state.confirmation ?: AgreementConfirmKeys.of(LoginAgreementAction.LOGIN)
            CouliDialog(
                title = texts.text(keys.titleKey),
                confirmText = texts.text(keys.primaryKey),
                onConfirm = model::onConfirmAgree,
                onDismissRequest = model::onCancelConfirm,
                dismissText = texts.text(keys.secondaryKey),
                body = { ConfirmBody(model, texts, keys.bodyKey) },
                confirmTag = LoginTags.CONFIRM_PRIMARY,
            )
        }

        LoginDialog.BLOCKED -> {
            CouliDialog(
                title = texts.text("error.44001"),
                message = texts.text("login.risk_blocked.desc"),
                confirmText = texts.text("login.risk_blocked.ok"),
                onConfirm = model::onDismissDialog,
                onDismissRequest = model::onDismissDialog,
                dismissText = texts.text("cs.appeal_entry"),
                onDismiss = model::customerService,
            )
        }

        // 第三方登录 44001：标题 error.44001（risk_msg 未下发时的默认句），【知道了】与【联系客服申诉】（统一客服出口）；第三方登录没有
        // 短信登录那样的专用说明句，不配说明（APP-05b 共性第 5 条，rule-gaps RISK-APPEAL）
        LoginDialog.OAUTH_BLOCKED -> {
            CouliDialog(
                title = texts.text("error.44001"),
                confirmText = texts.text("login.risk_blocked.ok"),
                onConfirm = model::onDismissDialog,
                onDismissRequest = model::onDismissDialog,
                dismissText = texts.text("cs.appeal_entry"),
                onDismiss = model::customerService,
            )
        }

        LoginDialog.DELETION_PROCESSING -> {
            CouliDialog(
                title = texts.text("error.10007"),
                message = texts.text("login.deletion.body"),
                confirmText = texts.text("login.deletion.ok"),
                onConfirm = model::onDismissDialog,
                onDismissRequest = model::onDismissDialog,
                dismissText = texts.text("login.deletion.cs"),
                onDismiss = model::customerService,
            )
        }

        null -> {
            Unit
        }
    }
}

/** 确认框正文（整句 [bodyKey]）：《用户协议》《隐私政策》是两个可点的链接，读屏单独聚焦（共性第 8 条），各自命中区 48dp；点了只关框、打开协议页。 */
@Composable
private fun ConfirmBody(
    model: LoginViewModel,
    texts: LoginTexts,
    bodyKey: String,
) {
    val sentence = agreementSentence(texts, bodyKey)
    val style = CouliTypography.footnote
    val lead = sentence.spans.firstOrNull()?.let { sentence.text.substring(0, it.start) } ?: sentence.text
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        if (lead.isNotEmpty()) Text(text = lead, style = style, color = CouliColors.textPrimary)
        AgreementLinks(sentence, texts, style, CouliColors.textPrimary, CONFIRM_LINK_TAGS, model::onConfirmLink)
    }
}

private const val TOAST_MILLIS = 2_000L
private const val BRAND_SIZE = 72
private const val BRAND_RADIUS = 20
private const val BRAND_FONT_DP = 20
private const val BRAND_LINE = 1.43f
private const val INPUT_MIN_HEIGHT = 52
private const val CODE_CELL_MIN_HEIGHT = 56

/** 第三方按钮：两个按钮间距 40、每个至少 64 宽（设计稿 Login）。 */
private const val OAUTH_GAP = 40
private const val OAUTH_MIN_WIDTH = 64

/** 大屏上登录内容的最大宽度（03 §10.4「内容区限制最大宽度并水平居中」；共性第 17 条示例值 480dp）。 */
private const val CONTENT_MAX_WIDTH = 480

/** 协议整句（BR-TEXT-14 表 C）。 */
private const val AGREE_TEXT_KEY = "login.agree.text"

private val AGREEMENT_LINK_TAGS =
    linkedMapOf(LoginAgreementType.AGREEMENT to LoginTags.LINK_AGREEMENT, LoginAgreementType.PRIVACY to LoginTags.LINK_PRIVACY)
private val CONFIRM_LINK_TAGS =
    linkedMapOf(
        LoginAgreementType.AGREEMENT to LoginTags.CONFIRM_LINK_AGREEMENT,
        LoginAgreementType.PRIVACY to LoginTags.CONFIRM_LINK_PRIVACY,
    )
