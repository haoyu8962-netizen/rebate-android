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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.couli.core.designsystem.CouliLoading
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSheetBody
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.LoadingSize
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.wallet.SensitiveOutcome
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

/** [ErrorBar] 的语气（APP-09v-e W79 / W81 / W92）：只换展示，不改错误分类、键与播报。 */
internal enum class WalletBarTone {
    /** 错误（红色浅底、红字 500、20 三角）：默认，读取失败、提交失败。 */
    ERROR,

    /** 警告（警告浅底、警告字色 500、20 三角）：收款账号 30303 次数类。 */
    WARNING,

    /** 中性（浅灰底、主文字 14、20 次文字色信息图标）：40901 核验中（G-W22，不加常转圈）。 */
    NEUTRAL,
}

/**
 * 错误条：图标 + 文字，出现时 assertive 播报一次（[Notice.id] 变化重新播报）。W92：默认 ERROR 语气（红底、红字 500、20 三角，提现页、记录页、钱包页同变）；
 * [tone] 只换配色与图标，liveRegion、tag 不变。
 */
@Composable
internal fun ErrorBar(
    notice: Notice,
    texts: WalletTexts,
    tag: String,
    modifier: Modifier = Modifier,
    tone: WalletBarTone = WalletBarTone.ERROR,
) {
    val text = notice.message(texts)
    if (text.isEmpty()) return
    val background =
        when (tone) {
            WalletBarTone.ERROR -> CouliColors.statusErrorBackground
            WalletBarTone.WARNING -> CouliColors.statusWarningBackground
            WalletBarTone.NEUTRAL -> CouliColors.backgroundMuted
        }
    val content =
        when (tone) {
            WalletBarTone.ERROR -> CouliColors.statusErrorText
            WalletBarTone.WARNING -> CouliColors.statusWarningText
            WalletBarTone.NEUTRAL -> CouliColors.textPrimary
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .semantics { liveRegion = if (notice.assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (tone == WalletBarTone.NEUTRAL) CouliIcons.info else CouliIcons.alert,
            contentDescription = null,
            tint = if (tone == WalletBarTone.NEUTRAL) CouliColors.textSecondary else content,
            modifier = Modifier.padding(top = BAR_ICON_TOP).size(CouliSizes.iconMedium),
        )
        Text(
            text = text,
            style = if (tone == WalletBarTone.NEUTRAL) CouliTypography.footnote else CouliTextStyles.footnoteMedium,
            color = content,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 说明提示条（INFO 语气：浅灰底、圆角 12、内边距 12；20 次文字色图标 + 14 主文字；APP-09v-e W74 / W77 / W91）。页面常驻说明，不播报。
 * [icon] 默认信息图标（实名第二条用人像图标，以画板为准）。
 */
@Composable
internal fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = CouliIcons.info,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.padding(top = BAR_ICON_TOP).size(CouliSizes.iconMedium),
        )
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** 20 图标与 14/21 文字首行居中：(21 − 20) / 2 ≈ 0.5。 */
private val BAR_ICON_TOP = CouliSizes.hairline / 2

/**
 * 警告提示条（钱包 Wallet* 画板：警告浅底、圆角 12、内边距 12；20 图标 + 两行文字都是警告字色，首行 [textStyle]、次行 14 常规）：
 * 风控暂停（可点，右侧 20 的 ›）、断网缓存（[icon] 给断网图标）、待抵扣、提现关闭。[onClick] 为 null 时是不可点形态（无 ›、无点击动作）。
 * [spoken] 给了就作为首行的读屏（金额隐藏时首行可见文字是遮罩）。[live] 为 true 时 role=status 礼貌播报（断网、风控）；
 * 待抵扣、提现关闭是页面常驻说明，不播报。
 */
@Suppress("LongParameterList")
@Composable
internal fun StatusBar(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    secondary: String? = null,
    icon: ImageVector = CouliIcons.alert,
    textStyle: TextStyle = CouliTextStyles.footnoteSemibold,
    spoken: String? = null,
    live: Boolean = true,
) {
    val tint = CouliColors.statusWarningText
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CouliRadius.button))
                .background(CouliColors.statusWarningBackground)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick).heightIn(min = CouliSizes.touchTarget)
                    } else {
                        Modifier
                    },
                ).padding(CouliSpacing.space3)
                .semantics(mergeDescendants = true) { if (live) liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(CouliSizes.iconMedium))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2)) {
            Text(
                text = text,
                style = textStyle,
                color = tint,
                modifier = if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier,
            )
            if (secondary != null) Text(text = secondary, style = CouliTypography.footnote, color = tint)
        }
        if (onClick != null) {
            Icon(CouliIcons.chevron, contentDescription = null, tint = tint, modifier = Modifier.size(CouliSizes.iconMedium))
        }
    }
}

/**
 * 轻提示（页顶，2 秒）：同一句再提示时 [id] 不同，重新播报。[info] 为 true（「上一次提交已经处理完成」，W62）时用次文字色信息图标，其余仍是成功色对勾；
 * 位置不变（G-W18）。
 */
@Composable
internal fun WalletToast(
    message: String,
    id: Long,
    error: Boolean,
    info: Boolean = false,
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
            icon =
                when {
                    error -> CouliIcons.alert
                    info -> CouliIcons.info
                    else -> CouliIcons.check
                },
            announcementId = id,
            modifier = Modifier.testTag(WalletTags.TOAST),
            iconTint = if (info && !error) CouliColors.textSecondary else CouliColors.statusSuccessText,
        )
    }
}

/** 这条一次性提示是「上一次提交已经处理完成」（W62 用信息图标）。 */
internal fun Notice.isCompletedNotice(): Boolean = key == SensitiveOutcome.ALREADY_DONE_KEY

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

/** 白卡片（圆角 16、内边距 16）；[spacing] 卡内块间距，默认 12（APP-09v-e 表单卡字段间 16）。 */
@Composable
internal fun WalletCard(
    modifier: Modifier = Modifier,
    spacing: Dp = CouliSpacing.space3,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(spacing),
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
    /** 标签与框（及框与字段错误）的间距（APP-09v-e W72 / W90：收款账号、实名传 8）；默认 4 不变。金额框固定 8。 */
    labelGap: Dp = CouliSpacing.space1,
    /**
     * 提现金额框样式（APP-09v-c W38 / W54）：标签与框间 8、框视觉高 56、「¥」17 次文字、输入 28/600 等宽、禁用浅边 + 灰底。默认 false = 原样式
     * （实名、收款账号不变）。
     */
    amount: Boolean = false,
    /** 与框同一行、在框右侧的控件（提现页【全部提现】）；null = 框占满整行（原样）。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (amount) CouliSpacing.space2 else labelGap),
    ) {
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
        val box =
            @Composable { boxModifier: Modifier ->
                Row(
                    modifier =
                        boxModifier
                            .fillMaxWidth()
                            .heightIn(min = if (amount) AMOUNT_FIELD_HEIGHT else CouliSizes.touchTarget)
                            .background(
                                if (enabled) CouliColors.backgroundSurface else CouliColors.backgroundMuted,
                                RoundedCornerShape(CouliRadius.button),
                            ).border(
                                CouliSizes.hairline,
                                fieldBorder(error != null, enabled, amount),
                                RoundedCornerShape(CouliRadius.button),
                            ).padding(
                                horizontal = if (amount) CouliSpacing.space4 else CouliSpacing.space3,
                                vertical = CouliSpacing.space2,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                ) {
                    if (prefix != null) {
                        Text(
                            text = prefix,
                            style = if (amount) CouliTypography.body else CouliTextStyles.amountTitle,
                            color = if (amount) CouliColors.textSecondary else CouliColors.textPrimary,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(text = placeholder, style = CouliTypography.body, color = CouliColors.textSecondary)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange.tapped(tag),
                            enabled = enabled,
                            singleLine = true,
                            textStyle = fieldTextStyle(prefix != null, amount).copy(color = CouliColors.textPrimary),
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
            }
        if (trailing == null) {
            box(Modifier)
        } else {
            FieldWithTrailing(box = { box(Modifier) }, trailing = trailing)
        }
        if (error != null) FieldErrorText(error, errorTag, errorEvent)
    }
}

/**
 * 框与右侧控件同一行（间距 12、垂直居中）；大字号 / 窄屏下框若会窄于右侧控件的 2 倍，右侧控件整块换到框下靠右（W55：金额框不被挤成几个字宽）。
 */
@Composable
private fun FieldWithTrailing(
    box: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(contents = listOf(box, trailing)) { (boxMs, trailingMs), c ->
        val gap = CouliSpacing.space3.roundToPx()
        val trailingP = trailingMs.map { it.measure(Constraints(maxWidth = c.maxWidth)) }
        val trailingW = trailingP.maxOfOrNull { it.width } ?: 0
        val stacked = c.maxWidth - trailingW - gap < trailingW * 2
        val boxW = if (stacked) c.maxWidth else (c.maxWidth - trailingW - gap).coerceAtLeast(0)
        val boxP = boxMs.map { it.measure(Constraints(minWidth = boxW, maxWidth = boxW)) }
        val boxH = boxP.maxOfOrNull { it.height } ?: 0
        val trailingH = trailingP.maxOfOrNull { it.height } ?: 0
        val height = if (stacked) boxH + trailingH else maxOf(boxH, trailingH)
        layout(c.maxWidth, height) {
            boxP.forEach { it.place(0, if (stacked) 0 else (height - it.height) / 2) }
            trailingP.forEach {
                if (stacked) it.place(c.maxWidth - it.width, boxH) else it.place(c.maxWidth - it.width, (height - it.height) / 2)
            }
        }
    }
}

/** 框描边：有错误红；金额框禁用时浅边（W54）；其余控件边（原样）。 */
@Composable
private fun fieldBorder(
    hasError: Boolean,
    enabled: Boolean,
    amount: Boolean,
) = when {
    hasError -> CouliColors.statusErrorText
    amount && !enabled -> CouliColors.borderSubtle
    else -> CouliColors.borderControl
}

/** 输入文字样式：金额框 28/600 等宽（W38）；带前缀 22/600 等宽、其余 17 正文（原样）。 */
private fun fieldTextStyle(
    hasPrefix: Boolean,
    amount: Boolean,
): TextStyle =
    when {
        amount -> AMOUNT_INPUT_STYLE
        hasPrefix -> CouliTextStyles.amountTitle
        else -> CouliTypography.body
    }

private val AMOUNT_INPUT_STYLE: TextStyle = with(CouliTextStyles) { CouliTypography.heading.tabular() }

/** 金额框视觉高 56（W38）= 48 命中高 + 8。 */
private val AMOUNT_FIELD_HEIGHT = CouliSizes.touchTarget + CouliSpacing.space2

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
    // W50 / W82：14 红字 + 左侧 16 三角图标（间距 4）；焦点、tag、文字仍在同一个文字节点上
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1), verticalAlignment = Alignment.Top) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.padding(top = FIELD_ERROR_ICON_TOP).size(CouliSizes.iconSmall),
        )
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = CouliColors.statusErrorText,
            modifier =
                Modifier
                    .bringIntoViewRequester(bring)
                    .couliFocusTarget(focus)
                    .focusable()
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        )
    }
}

/** 16 图标与 14/21 文字首行垂直居中：(21 − 16) / 2 ≈ 2。 */
private val FIELD_ERROR_ICON_TOP = CouliSpacing.space1 / 2

private const val FIELD_ERROR_FOCUS_DELAY_MILLIS = 100L

/**
 * 问号说明半屏（WalletNote* / WithdrawNoteNet；做法沿用收益看板的说明弹层）：标题 + 说明 +【知道了】。[bodyStyle] / [okVariant] 默认 17 正文与实心
 * 主按钮（提现页沿用）；钱包页按 WalletNote* 画板给 14/21 与描边按钮（APP-09v W15）。
 */
@Composable
internal fun HelpSheet(
    texts: WalletTexts,
    help: String,
    restore: FocusRequester?,
    bodyStyle: TextStyle = CouliTypography.body,
    okVariant: CouliButtonVariant = CouliButtonVariant.PRIMARY,
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
                variant = okVariant,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.HELP_OK),
            )
        },
    ) {
        Text(
            text = texts.text("wallet.help.$help"),
            style = bodyStyle,
            color = CouliColors.textPrimary,
            modifier = Modifier.testTag(WalletTags.HELP_SHEET),
        )
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
        // 首次组合时可能尚未 RESUMED，也可能直接被覆盖而没有 ON_PAUSE；先同步当前状态。
        onChange(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
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

/**
 * 44001 弹窗（WithdrawBlocked / PayoutAccountBlocked）：标题居中、说明句；按钮槽位见 [riskDialogSlots]（G-W16）：主（右）【联系客服申诉】→ [onAppeal]，
 * 次（左）【知道了】→ [onOk]；返回键 / 点遮罩仍 = 【知道了】。
 */
@Composable
internal fun RiskBlockedDialog(
    texts: WalletTexts,
    dialog: RiskDialog,
    descKey: String,
    restore: FocusRequester?,
    onOk: () -> Unit,
    onAppeal: () -> Unit,
) {
    val (primaryKey, secondaryKey) = riskDialogSlots()
    CouliDialog(
        title = texts.text(dialog.titleKey),
        message = texts.text(descKey),
        confirmText = texts.text(primaryKey),
        onConfirm = onAppeal,
        dismissText = texts.text(secondaryKey),
        onDismiss = onOk,
        onDismissRequest = onOk,
        restoreFocusTo = restore,
        titleAlign = TextAlign.Center,
        // r1 S2-1：画板 WithdrawBlocked 遮罩 = 令牌 overlayScrim（关系统 dim）
        scrim = CouliColors.overlayScrim,
    )
}

/**
 * 待确认卡片（WithdrawPendingConfirm / Busy / Still 与 PayoutAccountPending* 一族，W56–W59，G-W17）：白卡 → 警告提示块（20 图标、标题 14/600、说明 14，
 * 警告字色，整块一个 assertive 元素）→ 灰底明细（标签左、值右，有才显示）→ 两等宽按钮（【放弃】描边、【确认】主）→ 在途时转圈状态行「正在处理，请稍候」
 * （role=status，两按钮置灰）→ 40901 / retry_later / cannot_confirm 等提示为警告提示块（polite）→ 卡底一行：左注释 12、右「查看提现记录 ›」（有 [link] 才有）。
 *
 * 只改结构：[onAbandon] / [onConfirm] 由调用方在渲染时捕获未决键与表单身份后传入，签名与 `.tapped(PENDING_*)` 不变；在途判定只读 `pending.inFlight`。
 */
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
                .couliCard()
                .padding(CouliSpacing.space4)
                .testTag(WalletTags.PENDING_CARD),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        PendingWarningBlock(
            title = texts.text("pending_confirm.title"),
            text = desc,
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        )
        if (details.isNotEmpty()) DetailBlock(details)
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            CouliButton(
                text = abandonLabel,
                onClick = onAbandon.tapped(WalletTags.PENDING_ABANDON),
                enabled = !busy,
                variant = CouliButtonVariant.SECONDARY,
                // 两等宽按钮 17/600（画板）：用紧凑款的 16 横向内边距，七个字的按钮名在 390 宽下一行放得下；命中高仍 ≥48
                size = CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.weight(1f).testTag(WalletTags.PENDING_ABANDON),
            )
            CouliButton(
                text = confirmLabel,
                onClick = onConfirm.tapped(WalletTags.PENDING_CONFIRM),
                enabled = !busy,
                size = CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.weight(1f).testTag(WalletTags.PENDING_CONFIRM),
            )
        }
        if (pending.inFlight) PendingBusyRow(texts.text(PENDING_BUSY_KEY))
        // 在途时控制器给的「正在处理」由上面的转圈状态行表达，不再重复一块同文的提示
        flow.pendingNotice?.takeUnless { pending.inFlight && it.key == PENDING_BUSY_KEY }?.let { notice ->
            PendingWarningBlock(
                title = null,
                text = notice.message(texts),
                modifier =
                    Modifier
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                        .testTag(WalletTags.PENDING_NOTICE),
            )
        }
        PendingFooter(note, link)
    }
}

/** 在途状态与 40901 共用的文案键（契约 error.40901「正在处理，请稍候」，与 SensitiveFlow 在途提示同键）。 */
private const val PENDING_BUSY_KEY = "error.40901"

/** 警告提示块：警告浅底、圆角 12、内边距 12；20 三角 + 文字列（[title] 14/600、[text] 14，均警告字色）。 */
@Composable
private fun PendingWarningBlock(
    title: String?,
    text: String,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2)) {
            if (title != null) Text(text = title, style = CouliTextStyles.footnoteSemibold, color = CouliColors.statusWarningText)
            Text(text = text, style = CouliTypography.footnote, color = CouliColors.statusWarningText)
        }
    }
}

/** 在途状态行（W58）：16 转圈 +「正在处理，请稍候」次文字，role=status（polite），整行一个读屏元素。 */
@Composable
private fun PendingBusyRow(text: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouliLoading(description = "", size = LoadingSize.SMALL)
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/**
 * 卡底一行（W57）：左注释 12 次文字、右「查看提现记录 ›」14/500 链接色；放不下（大字号 / 窄屏）时链接整块换到下一行。链接视觉行高 = 文字 + 上下 4，
 * 可点区由最小触控尺寸扩到 48（上下各扩约 8.5，不越过与按钮行之间的 12 间距）。
 */
@Composable
private fun PendingFooter(
    note: String,
    link: Pair<String, () -> Unit>?,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = note, style = CouliTypography.caption, color = CouliColors.textSecondary)
        if (link != null) {
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(CouliRadius.small))
                        .clickable(role = Role.Button, onClick = link.second)
                        .padding(vertical = CouliSpacing.space1)
                        .testTag(WalletTags.PENDING_RECORDS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = link.first, style = CouliTextStyles.footnoteMedium, color = CouliColors.textLink)
                Icon(
                    CouliIcons.chevron,
                    contentDescription = null,
                    tint = CouliColors.textLink,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
    }
}

/** 灰底明细块（待确认卡、放弃确认弹窗）：圆角 12、内边距 12，每行标签左（次文字）值右（主文字、可折行），行距 4。 */
@Composable
private fun DetailBlock(details: List<Pair<String, String>>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        details.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                Text(
                    text = value,
                    style = CouliTypography.footnote.copy(textAlign = TextAlign.End),
                    color = CouliColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 放弃上一笔的二次确认（WithdrawAbandonConfirm / PayoutAccountPendingAbandonConfirm，W61）：标题居中、说明句、灰底明细块；按钮槽位见
 * [abandonDialogSlots]（G-W16）：主（右）【再想想】→ [onCancel]；次（左）【确定放弃】→ [onOk]（`.tapped(ABANDON_DIALOG)` 跟着放弃回调）；
 * 返回键 / 点遮罩 = 【再想想】，不放弃。
 */
@Composable
internal fun AbandonDialog(
    texts: WalletTexts,
    title: String,
    details: List<Pair<String, String>>,
    extra: String?,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    val (primaryKey, secondaryKey) = abandonDialogSlots()
    CouliDialog(
        title = title,
        confirmText = texts.text(primaryKey),
        onConfirm = onCancel,
        dismissText = texts.text(secondaryKey),
        onDismiss = onOk.tapped(WalletTags.ABANDON_DIALOG),
        onDismissRequest = onCancel,
        titleAlign = TextAlign.Center,
        // r1 S2-1：画板 WithdrawAbandonConfirm 遮罩 = 令牌 overlayScrim（关系统 dim）
        scrim = CouliColors.overlayScrim,
        body = {
            Column(
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                modifier = Modifier.testTag(WalletTags.ABANDON_DIALOG),
            ) {
                Text(
                    text = texts.text("pending_confirm.abandon.confirm"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                )
                if (details.isNotEmpty()) DetailBlock(details)
                if (extra != null) Text(text = extra, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        },
    )
}
