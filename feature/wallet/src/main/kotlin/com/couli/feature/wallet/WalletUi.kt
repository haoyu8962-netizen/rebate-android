package com.couli.feature.wallet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSheetBody
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletTexts
import kotlinx.coroutines.delay

/** 钱包类页面共用的组件。文案一律经 [WalletTexts] 取键（BR-TEXT-12）。 */
internal object WalletUi {
    /** 内容最大宽度（大屏限宽居中，与订单、搜索同为 600dp，03 §10.4）。 */
    const val MAX_WIDTH = 600
    const val TOAST_MILLIS = 2_000L
    const val MASK_HIDE_KEY = "amount_mask.hide"
    const val MASK_SHOW_KEY = "amount_mask.show"
    const val MASK_KEY = "amount_mask.mask"
    const val HIDDEN_SPOKEN_KEY = "amount_mask.hidden_spoken"
}

fun Notice.message(texts: WalletTexts): String = WalletErrors.message(texts, key, traceId, showTrace6)

/** 页面骨架：背景、限宽居中、状态栏留白、顶栏（返回）。 */
@Composable
internal fun WalletPage(
    tag: String,
    title: String,
    texts: WalletTexts,
    onBack: () -> Unit,
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(tag),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.widthIn(max = WalletUi.MAX_WIDTH.dp).fillMaxSize().statusBarsPadding()) {
            NavBar(title = title, backLabel = texts.text("wallet.back"), onBack = onBack)
            content()
        }
        overlay()
    }
}

/**
 * 发起页上的「打开前查询失败」提示（APP-09 共性第 21 条，R1-10）：实名状态查询失败时不当成未实名继续导航，在发起页底部显示统一错误文案（5xxxx 带 trace6，
 * assertive 播报一次）与【重试】。点【重试】重新发起同一次打开；点空白处不关闭，只能经【关闭】收起。
 */
@Composable
fun OpenErrorBar(
    notice: Notice,
    texts: WalletTexts,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    /** 共享 read 禁用期内置灰（APP-09 共性第 56 条）。 */
    retryEnabled: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = WalletUi.MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .padding(CouliSpacing.space4)
                    .couliCard()
                    .padding(CouliSpacing.space3)
                    .testTag(WalletTags.OPEN_ERROR),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            ErrorBar(notice, texts, WalletTags.OPEN_ERROR_TEXT)
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2), modifier = Modifier.fillMaxWidth()) {
                CouliButton(
                    text = texts.text("wallet.help.close"),
                    onClick = onDismiss,
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f).testTag(WalletTags.OPEN_ERROR_CLOSE),
                )
                CouliButton(
                    text = texts.text("wallet.retry"),
                    onClick = onRetry,
                    enabled = retryEnabled,
                    modifier = Modifier.weight(1f).testTag(WalletTags.OPEN_ERROR_RETRY),
                )
            }
        }
    }
}

/**
 * 已有旧数据时的读取失败（APP-09 共性第 30 条）：错误条（5xxxx 带 trace6，assertive 播报一次）+【重试】独立显示在内容上方，旧数据保留但不再当作「当前」。
 */
@Composable
internal fun RetryErrorBar(
    notice: Notice,
    texts: WalletTexts,
    tag: String,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        ErrorBar(notice, texts, tag)
        CouliButton(
            text = texts.text("wallet.retry"),
            onClick = onRetry,
            enabled = retryEnabled,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.testTag(tag + RETRY_SUFFIX),
        )
    }
}

/** 被拦下的前置引导的手动入口（APP-09 共性第 51 条）：按目标路由给【去实名认证】等文字按钮。 */
@Composable
internal fun GuideEntry(
    route: String,
    texts: WalletTexts,
    tag: String,
    onClick: () -> Unit,
) {
    CouliButton(
        text = texts.text(guideLabelKey(route)),
        onClick = onClick,
        variant = CouliButtonVariant.TEXT,
        size = CouliButtonSize.COMPACT,
        modifier = Modifier.testTag(tag),
    )
}

internal fun guideLabelKey(route: String): String =
    when (route) {
        com.couli.core.wallet.WalletRoutes.REAL_NAME -> "withdraw.guide.realname"
        com.couli.core.wallet.WalletRoutes.PAYOUT_ACCOUNT -> "withdraw.guide.payout"
        com.couli.core.wallet.WalletRoutes.LABOR_AGREEMENT -> "withdraw.guide.labor"
        com.couli.core.wallet.WalletRoutes.BIND_PHONE -> "wallet.guide.bind_phone"
        else -> "wallet.guide.login"
    }

/** [RetryErrorBar] 的【重试】标记 = 错误条标记 + 这个后缀。 */
const val RETRY_SUFFIX = "_retry"

/** 错误条：图标 + 文字，出现时 assertive 播报一次（[Notice.id] 变化重新播报）。 */
@Composable
internal fun ErrorBar(
    notice: Notice,
    texts: WalletTexts,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val text = notice.message(texts)
    if (text.isEmpty()) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusErrorBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .semantics { liveRegion = if (notice.assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.size(CouliSizes.iconSmall),
        )
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** 提示条（role=status，礼貌播报）：断网缓存、待确认卡里的提示。 */
@Composable
internal fun StatusBar(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    secondary: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = CouliSizes.touchTarget)
                .padding(CouliSpacing.space3)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconSmall),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2)) {
            Text(text = text, style = CouliTextStyles.footnoteMedium, color = CouliColors.textPrimary)
            if (secondary != null) Text(text = secondary, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        if (onClick != null) {
            Icon(
                CouliIcons.chevron,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
        }
    }
}

/** 轻提示（页顶，2 秒）：同一句再提示时 [id] 不同，重新播报。 */
@Composable
internal fun WalletToast(
    message: String,
    id: Long,
    error: Boolean,
    onDone: () -> Unit,
) {
    LaunchedEffect(id) {
        delay(WalletUi.TOAST_MILLIS)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = CouliSpacing.space12),
        contentAlignment = Alignment.TopCenter,
    ) {
        CouliToast(
            message = message,
            icon = if (error) CouliIcons.alert else CouliIcons.check,
            announcementId = id,
            modifier = Modifier.testTag(WalletTags.TOAST),
        )
    }
}

/** 一次性播报（提交成功、档位估算结果）。 */
@Composable
internal fun Announce(
    text: String?,
    id: Any?,
) {
    val injected = LocalCouliAnnouncer.current
    val view = LocalView.current
    LaunchedEffect(id) {
        if (!text.isNullOrEmpty()) {
            @Suppress("DEPRECATION") // 一次性播报（与 DesignSystem 的 ViewAnnouncer 同一做法）
            injected?.announce(text) ?: view.announceForAccessibility(text)
        }
    }
}

/** 白卡片（圆角 16、内边距 16）。 */
@Composable
internal fun WalletCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        content = content,
    )
}

/**
 * 回调登记探针（APP-09 Android 第二次定向复核 RC-01、共性第 63 条）：页面交给输入框、勾选行、按钮的回调按 testTag 原样登记，页面接线测试据此保留「渲染 A 时
 * 的真实回调」，换号、重组之后再执行，验证回调只捕获渲染时取出的普通值。默认 null：生产不登记，不改变行为。
 */
fun interface WalletCallbackTap {
    fun record(
        tag: String,
        callback: Function<*>,
    )
}

val LocalWalletCallbackTap = staticCompositionLocalOf<WalletCallbackTap?> { null }

/** 把交给控件的回调登记到 [LocalWalletCallbackTap]（有探针时），原样返回。 */
@Composable
internal fun <F : Function<*>> F.tapped(tag: String): F {
    LocalWalletCallbackTap.current?.record(tag, this)
    return this
}

/**
 * 输入框（标签 + 框 + 字段错误）：字段错误即时播报一次，错误说明关联到输入框（semantics.error），保留输入（APP-05b 第 6 条）。
 * 可变长文案不固定宽度或单行（APP-06a 第 24 条）。
 */
@Suppress("LongParameterList")
@Composable
internal fun WalletField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    focus: FocusRequester? = null,
    prefix: String? = null,
    /** 这次错误事件的编号（只给本次事件里的第一个错误字段）：变化时滚到错误提示、移焦点并播报一次（APP-09 共性第 24 条）。 */
    errorEvent: Any? = null,
    errorTag: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        Text(
            text = label,
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier =
                Modifier.semantics {
                    contentDescription =
                        ""
                },
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CouliSizes.touchTarget)
                    .background(
                        if (enabled) CouliColors.backgroundSurface else CouliColors.backgroundMuted,
                        RoundedCornerShape(CouliRadius.button),
                    ).border(
                        CouliSizes.hairline,
                        if (error != null) CouliColors.statusErrorText else CouliColors.borderControl,
                        RoundedCornerShape(CouliRadius.button),
                    ).padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            if (prefix != null) Text(text = prefix, style = CouliTextStyles.amountTitle, color = CouliColors.textPrimary)
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(text = placeholder, style = CouliTypography.body, color = CouliColors.textSecondary)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange.tapped(tag),
                    enabled = enabled,
                    singleLine = true,
                    textStyle =
                        if (prefix !=
                            null
                        ) {
                            CouliTextStyles.amountTitle.copy(color = CouliColors.textPrimary)
                        } else {
                            CouliTypography.body.copy(color = CouliColors.textPrimary)
                        },
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                            .testTag(tag)
                            .semantics {
                                contentDescription = label
                                if (error != null) error(error)
                            },
                )
            }
        }
        if (error != null) FieldErrorText(error, errorTag, errorEvent)
    }
}

/**
 * 字段错误提示（APP-09 共性第 24 条，brief §0「字段错误即时播报一次，焦点移到错误提示」）：[event] 每次错误事件换新（只给第一个错误字段）；变化时先把提示
 * 滚到可见区域，再把焦点（读屏随之）移到提示上，并播报一次。没有 [event] 的其余错误字段只显示，不重复播报。输入框保留原输入与 error 语义。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FieldErrorText(
    text: String,
    tag: String?,
    event: Any?,
) {
    val bring = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val injected = LocalCouliAnnouncer.current
    val view = LocalView.current
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        // 等错误提示挂上布局与无障碍树
        delay(FIELD_ERROR_FOCUS_DELAY_MILLIS)
        bring.bringIntoView()
        runCatching { focus.requestFocus() }
        @Suppress("DEPRECATION") // 一次性播报（与 DesignSystem 的 ViewAnnouncer 同一做法）
        injected?.announce(text) ?: view.announceForAccessibility(text)
    }
    Text(
        text = text,
        style = CouliTypography.caption,
        color = CouliColors.statusErrorText,
        modifier =
            Modifier
                .bringIntoViewRequester(bring)
                .couliFocusTarget(focus)
                .focusable()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

private const val FIELD_ERROR_FOCUS_DELAY_MILLIS = 100L

/** 问号说明半屏（WalletNote* / WithdrawNoteNet；做法沿用收益看板的说明弹层）：标题 + 说明 +【知道了】。 */
@Composable
internal fun HelpSheet(
    texts: WalletTexts,
    help: String,
    restore: FocusRequester?,
    onDismiss: () -> Unit,
) {
    CouliSheet(
        title = texts.text("wallet.help.${help}_title"),
        onDismissRequest = onDismiss,
        restoreFocusTo = restore,
        closeLabel = texts.text("wallet.help.close"),
        actions = {
            CouliButton(
                text = texts.text("wallet.help.ok"),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.HELP_OK),
            )
        },
    ) {
        Text(
            text = texts.text("wallet.help.$help"),
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
            modifier = Modifier.testTag(WalletTags.HELP_SHEET),
        )
    }
}

/**
 * 资金与验证类半屏（二次验证、实名同意；AdaptSheetRules、AdaptShortStepUp、A11yFocusStepUp，APP-09 共性第 48 条）：点遮罩（不响应点外部）、下滑
 * （关掉半屏手势）都不关闭；系统返回关闭最上层半屏，走与面板里关闭按钮相同的出口 [onClose]（控制器结算验证码请求归属、恢复发起页状态）。
 * 打开时焦点进标题，关闭后焦点回到触发控件（[restore]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LockedSheet(
    title: String,
    tag: String,
    closeLabel: String?,
    onClose: () -> Unit,
    restore: FocusRequester?,
    actions: (@Composable () -> Unit)? = null,
    /** 中段至少要放得下的高度（二次验证：一行验证码格）；给了就用 [FitSheetBody]（APP-09 共性第 11、33 条）。 */
    minBody: Dp? = null,
    closeTag: String? = null,
    content: @Composable () -> Unit,
) {
    // 收起只可能来自系统返回（手势与点外部都已关掉）：返回键收起后由 onDismissRequest 走关闭按钮的同一出口
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DisposableEffect(Unit) { onDispose { restore?.let { runCatching { it.requestFocus() } } } }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true, shouldDismissOnClickOutside = false),
        shape = RoundedCornerShape(topStart = CouliRadius.panel, topEnd = CouliRadius.panel),
        containerColor = CouliColors.backgroundSurface,
        dragHandle = null,
    ) {
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
        if (minBody != null && actions != null) {
            FitSheetBody(
                title = title,
                modifier = Modifier.padding(top = CouliSpacing.space4).semantics { paneTitle = title }.testTag(tag),
                titleFocus = titleFocus,
                onClose = onClose,
                closeLabel = closeLabel,
                minBody = minBody,
                closeTag = closeTag,
                actions = actions,
                content = content,
            )
        } else {
            CouliSheetBody(
                title = title,
                modifier = Modifier.padding(top = CouliSpacing.space4).semantics { paneTitle = title }.testTag(tag),
                actions = actions,
                titleFocus = titleFocus,
                onClose = closeLabel?.let { { onClose() } },
                closeLabel = closeLabel,
                content = content,
            )
        }
    }
}

/**
 * 三段半屏（APP-09 共性第 11、33 条）：放得下时标题区与底部操作区固定、只有中段滚动；标题 + 操作区 + 中段最小高 [minBody] 超过可用高度（短窗口、200% 字号、
 * 键盘弹起）时，整块切到可滚动的降级布局，完整标题、关闭与【验证并提交】都可滚到。可用高度取半屏得到的约束（键盘上方）。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // 三段测量与降级判定在同一个 Layout 里
@Composable
internal fun FitSheetBody(
    title: String,
    modifier: Modifier,
    titleFocus: FocusRequester,
    onClose: () -> Unit,
    closeLabel: String?,
    minBody: Dp,
    closeTag: String?,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 半屏窗口随键盘缩小（约束已是键盘上方的高度）
        val budget = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val outer = rememberScrollState()
        val middle = rememberScrollState()
        var fallback by remember { mutableStateOf(false) }
        val parts: List<@Composable () -> Unit> =
            listOf(
                {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = CouliSpacing.space4)) {
                        Text(
                            text = title,
                            style = CouliTextStyles.bodySemibold,
                            color = CouliColors.textPrimary,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(top = CouliSpacing.space3)
                                    .semantics { heading() }
                                    .focusRequester(titleFocus)
                                    .focusable(),
                        )
                        if (closeLabel != null) {
                            CouliIconButton(
                                icon = CouliIcons.close,
                                label = closeLabel,
                                onClick = onClose,
                                tint = CouliColors.textSecondary,
                                modifier = if (closeTag != null) Modifier.testTag(closeTag) else Modifier,
                            )
                        }
                    }
                },
                {
                    Column(
                        modifier =
                            Modifier
                                .then(if (fallback) Modifier else Modifier.verticalScroll(middle))
                                .padding(horizontal = CouliSpacing.space4)
                                .testTag(WalletTags.SHEET_MIDDLE),
                        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
                    ) { content() }
                },
                {
                    Box(modifier = Modifier.padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space6)) {
                        actions()
                    }
                },
            )
        val density = LocalDensity.current
        val visible = if (budget == Constraints.Infinity) Modifier else Modifier.heightIn(max = with(density) { budget.toDp() })
        // 降级：整块在「键盘上方的可见高度」里滚动，最后一段（【验证并提交】）也能滚到键盘之上
        Box(modifier = if (fallback) visible.verticalScroll(outer).testTag(WalletTags.SHEET_FALLBACK) else Modifier) {
            Layout(contents = parts) { (headerMs, bodyMs, footerMs), c ->
                val gap = CouliSpacing.space4.roundToPx()
                val loose = Constraints(maxWidth = c.maxWidth)
                val header = headerMs.map { it.measure(loose) }
                val footer = footerMs.map { it.measure(loose) }
                val fixed = header.sumOf { it.height } + footer.sumOf { it.height } + 2 * gap
                val fits = budget == Constraints.Infinity || fixed + minBody.roundToPx() <= budget
                if (fits == fallback) {
                    // 放得下 ↔ 放不下切换：下一帧按新布局重排（不在本次测量里改滚动容器）
                    fallback = !fits
                }
                val bodyMax =
                    when {
                        fallback -> bodyMs.maxOfOrNull { it.maxIntrinsicHeight(c.maxWidth) } ?: 0
                        budget == Constraints.Infinity -> bodyMs.maxOfOrNull { it.maxIntrinsicHeight(c.maxWidth) } ?: 0
                        else -> (budget - fixed).coerceAtLeast(0)
                    }
                val body = bodyMs.map { it.measure(Constraints(maxWidth = c.maxWidth, maxHeight = bodyMax)) }
                val height = fixed + body.sumOf { it.height }
                val shown = if (fallback || budget == Constraints.Infinity) height else height.coerceAtMost(budget)
                layout(c.maxWidth, shown) {
                    var y = 0
                    listOf(header, body, footer).forEach { group ->
                        group.forEach {
                            it.place(0, y)
                            y += it.height
                        }
                        y += gap
                    }
                }
            }
        }
    }
}

/**
 * 回到前台的判定（放在 ViewModel 里）：Navigation 把被盖住的页面移出组合，返回时重新组合；第一次之后的每次 ON_RESUME 才算「回到本页」。
 */
class ResumeTracker {
    internal var seen = false
}

/** 页面生命周期：第一次之后每次回到前台（被前置页盖住后返回、App 回前台）调 [onReturn]。 */
@Composable
internal fun OnReturn(
    tracker: ResumeTracker,
    onReturn: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, tracker) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (tracker.seen) onReturn() else tracker.seen = true
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 页面可见性（ON_RESUME 可见、ON_PAUSE 被覆盖或退到后台；APP-09 共性第 37 条）。 */
@Composable
internal fun OnVisibility(onChange: (Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> onChange(true)
                    Lifecycle.Event.ON_PAUSE -> onChange(false)
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 勾选行（单独同意、劳务协议）：整行可点，默认不勾，读屏读勾选状态。 */
@Composable
internal fun CheckRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val toggle = onToggle.tapped(tag)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle() })
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Box(
            modifier =
                Modifier
                    .size(CouliSizes.iconMedium)
                    .background(
                        if (checked) CouliColors.brandPrimary else CouliColors.backgroundSurface,
                        RoundedCornerShape(CouliRadius.small),
                    ).border(
                        CouliSizes.hairline,
                        if (checked) CouliColors.brandPrimary else CouliColors.borderControl,
                        RoundedCornerShape(CouliRadius.small),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    CouliIcons.check,
                    contentDescription = null,
                    tint = CouliColors.backgroundSurface,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** 44001 弹窗（WithdrawBlocked / PayoutAccountBlocked）：标题、说明句、【知道了】【联系客服申诉】。 */
@Composable
internal fun RiskBlockedDialog(
    texts: WalletTexts,
    dialog: RiskDialog,
    descKey: String,
    restore: FocusRequester?,
    onOk: () -> Unit,
    onAppeal: () -> Unit,
) {
    CouliDialog(
        title = texts.text(dialog.titleKey),
        message = texts.text(descKey),
        confirmText = texts.text("withdraw.risk_blocked.ok"),
        onConfirm = onOk,
        dismissText = texts.text("cs.appeal_entry"),
        onDismiss = onAppeal,
        onDismissRequest = onOk,
        restoreFocusTo = restore,
    )
}

/** 待确认卡片（WithdrawPendingConfirm / PayoutAccountPendingConfirm 一族）：alert 角色，【放弃】【确认】，在途时两个都置灰（PendingBusy）。 */
@Suppress("LongParameterList")
@Composable
internal fun PendingCard(
    texts: WalletTexts,
    desc: String,
    abandonLabel: String,
    confirmLabel: String,
    flow: FlowState,
    blocked: Boolean,
    onAbandon: () -> Unit,
    onConfirm: () -> Unit,
    note: String,
    details: List<Pair<String, String>> = emptyList(),
    link: Pair<String, () -> Unit>? = null,
) {
    val pending = flow.pending ?: return
    val busy = pending.inFlight || blocked
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.card))
                .padding(CouliSpacing.space4)
                .testTag(WalletTags.PENDING_CARD),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            Text(text = texts.text("pending_confirm.title"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
            Text(text = desc, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
        details.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                Text(text = value, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            CouliButton(
                text = abandonLabel,
                onClick = onAbandon.tapped(WalletTags.PENDING_ABANDON),
                enabled = !busy,
                variant = CouliButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f).testTag(WalletTags.PENDING_ABANDON),
            )
            CouliButton(
                text = confirmLabel,
                onClick = onConfirm.tapped(WalletTags.PENDING_CONFIRM),
                enabled = !busy,
                modifier = Modifier.weight(1f).testTag(WalletTags.PENDING_CONFIRM),
            )
        }
        flow.pendingNotice?.let { notice ->
            Text(
                text = notice.message(texts),
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WalletTags.PENDING_NOTICE),
            )
        }
        Text(text = note, style = CouliTypography.caption, color = CouliColors.textSecondary)
        if (link != null) {
            CouliButton(
                text = link.first,
                onClick = link.second,
                variant = CouliButtonVariant.TEXT,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.testTag(WalletTags.PENDING_RECORDS),
            )
        }
    }
}

/** 放弃上一笔的二次确认（WithdrawAbandonConfirm / PayoutAccountPendingAbandonConfirm）。 */
@Composable
internal fun AbandonDialog(
    texts: WalletTexts,
    title: String,
    details: List<Pair<String, String>>,
    extra: String?,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    CouliDialog(
        title = title,
        confirmText = texts.text("pending_confirm.abandon.ok"),
        onConfirm = onOk.tapped(WalletTags.ABANDON_DIALOG),
        dismissText = texts.text("pending_confirm.abandon.cancel"),
        onDismiss = onCancel,
        onDismissRequest = onCancel,
        body = {
            Column(
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                modifier = Modifier.testTag(WalletTags.ABANDON_DIALOG),
            ) {
                Text(
                    text = texts.text("pending_confirm.abandon.confirm"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                )
                details.forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                        Text(
                            text = value,
                            style = CouliTypography.footnote,
                            color = CouliColors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (extra != null) Text(text = extra, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        },
    )
}
