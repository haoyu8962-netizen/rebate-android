package com.couli.app.shell

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.couli.app.R
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.privacy.PromptStage
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.delay

/** 首启弹窗、二次说明、启动底图的测试标记。 */
const val LAUNCH_PRIVACY_TAG = "launch_privacy_dialog"
const val SECOND_NOTICE_TAG = "launch_second_notice_dialog"
const val LAUNCH_BACKDROP_TAG = "launch_backdrop"
const val PRIVACY_AGREE_TAG = "privacy_agree"
const val PRIVACY_DISAGREE_TAG = "privacy_disagree"
const val PRIVACY_STILL_DISAGREE_TAG = "privacy_still_disagree"
const val PRIVACY_EXIT_APP_TAG = "privacy_exit_app"

/** 协议链接要打开的 Agreement 参数（routes.json Agreement.params.type）。 */
enum class AgreementLink(
    val type: String,
) {
    PRIVACY("privacy"),
    AGREEMENT("agreement"),

    // TODO(N-16): 「个人信息收集清单」没有独立的 Agreement type，先打开隐私政策（清单在政策里），契约补 type 后改。
    COLLECT_LIST("privacy"),
    SDK_LIST("sdk_list"),
}

/** 首启弹窗与二次说明的操作。 */
class PrivacyPromptActions(
    val onAgree: () -> Unit,
    val onDisagree: () -> Unit,
    val onStillDisagree: () -> Unit,
    val onExitApp: () -> Unit,
    val onOpenAgreement: (AgreementLink) -> Unit,
)

/**
 * 启动底图（LaunchPrivacy / LaunchSecondNotice 弹窗背后）：品牌底色 + 居中 88 品牌方块。系统启动屏用窗口底色
 * （Theme.Couli 的 windowBackground = 令牌 background.canvas），不做自定义闪屏；本底图只在隐私闸门还没有结论时垫在弹窗下面。
 */
@Composable
fun LaunchBackdrop() {
    Box(
        modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(LAUNCH_BACKDROP_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(BRAND_TILE_SIZE)
                    .background(CouliColors.brandPrimary, RoundedCornerShape(BRAND_TILE_RADIUS)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = CouliTypography.title.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                color = CouliColors.textInverse,
            )
        }
    }
}

/**
 * 首启弹窗（[PromptStage.FIRST]）或二次说明（[PromptStage.SECOND]）。BR-ID-11、adaptation-a11y-spec §4：
 * - 模态（系统 Dialog 窗口，底层不可达）；**返回键与点外部都不关闭**（DialogProperties + BackHandler 吞掉返回键）；
 * - 打开时焦点进标题（标题有 heading 语义，弹窗 paneTitle = 标题）；按钮名称即可见文字；
 * - 最大高 = 可视高度 − 2 × (安全区 + 24)：标题与按钮固定，正文与协议链接在中间滚动（200% 字号）；
 * - 二次说明另给【退出 App】（Android，privacy.btn.exit_app）。
 */
@Composable
fun PrivacyPromptDialog(
    stage: PromptStage,
    actions: PrivacyPromptActions,
) {
    if (stage == PromptStage.NONE) return
    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        ScrimDim(LocalView.current)
        BackHandler(enabled = true) {}
        Box(
            modifier = Modifier.safeDrawingPadding().padding(CouliSpacing.space6),
            contentAlignment = Alignment.Center,
        ) {
            when (stage) {
                PromptStage.FIRST -> FirstLaunchContent(actions)
                PromptStage.SECOND -> SecondNoticeContent(actions)
                PromptStage.NONE -> Unit
            }
        }
    }
}

/** 遮罩：设计稿 rgba(40,37,31,0.4)；系统 Dialog 的遮罩只能调透明度，取 0.4。 */
@Composable
private fun ScrimDim(view: View) {
    LaunchedEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(SCRIM_ALPHA)
    }
}

@Composable
private fun FirstLaunchContent(actions: PrivacyPromptActions) {
    PromptCard(
        title = ShellDraftTexts.LAUNCH_TITLE,
        tag = LAUNCH_PRIVACY_TAG,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                BodyText(ShellDraftTexts.LAUNCH_INTRO)
                ShellDraftTexts.LAUNCH_POINTS.forEach { point -> Bullet(point) }
            }
            AgreementLinks(
                links =
                    listOf(
                        ShellDraftTexts.LINK_PRIVACY_POLICY to AgreementLink.PRIVACY,
                        ShellDraftTexts.LINK_USER_AGREEMENT to AgreementLink.AGREEMENT,
                        ShellDraftTexts.LINK_COLLECT_LIST to AgreementLink.COLLECT_LIST,
                        ShellDraftTexts.LINK_SDK_LIST to AgreementLink.SDK_LIST,
                    ),
                onOpen = actions.onOpenAgreement,
            )
        },
        buttons = {
            PromptButtons(
                primaryText = DefaultTexts.text("privacy.btn.agree").orEmpty(),
                onPrimary = actions.onAgree,
                primaryTag = PRIVACY_AGREE_TAG,
                secondaryText = DefaultTexts.text("privacy.btn.disagree").orEmpty(),
                onSecondary = actions.onDisagree,
                secondaryTag = PRIVACY_DISAGREE_TAG,
            )
        },
    )
}

@Composable
private fun SecondNoticeContent(actions: PrivacyPromptActions) {
    PromptCard(
        title = ShellDraftTexts.SECOND_TITLE,
        tag = SECOND_NOTICE_TAG,
        body = {
            BodyText(ShellDraftTexts.SECOND_BODY)
            AgreementLinks(
                links =
                    listOf(
                        ShellDraftTexts.LINK_PRIVACY_POLICY to AgreementLink.PRIVACY,
                        ShellDraftTexts.LINK_USER_AGREEMENT to AgreementLink.AGREEMENT,
                    ),
                onOpen = actions.onOpenAgreement,
            )
        },
        buttons = {
            Column(
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PromptButtons(
                    primaryText = DefaultTexts.text("privacy.btn.agree").orEmpty(),
                    onPrimary = actions.onAgree,
                    primaryTag = PRIVACY_AGREE_TAG,
                    secondaryText = DefaultTexts.text("privacy.btn.still_disagree").orEmpty(),
                    onSecondary = actions.onStillDisagree,
                    secondaryTag = PRIVACY_STILL_DISAGREE_TAG,
                )
                // Android 另给【退出 App】（BR-ID-11；iOS 没有）
                // 设计稿：14 常规字、深橙链接色、命中区不小于 48（Android）
                Box(
                    modifier =
                        Modifier
                            .heightIn(min = CouliSizes.touchTarget)
                            .clickable(role = Role.Button, onClick = actions.onExitApp)
                            .padding(horizontal = CouliSpacing.space2)
                            .testTag(PRIVACY_EXIT_APP_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = DefaultTexts.text("privacy.btn.exit_app").orEmpty(),
                        style = CouliTypography.footnote,
                        color = CouliColors.textLink,
                    )
                }
            }
        },
    )
}

/** 白卡：圆角 16、内边距 20、块间距 16；标题固定在上、按钮固定在下，中间正文在有限高度内滚动。 */
@Composable
private fun PromptCard(
    title: String,
    tag: String,
    body: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(titleFocus) {
        delay(TITLE_FOCUS_DELAY_MILLIS)
        runCatching { titleFocus.requestFocus() }
    }
    Column(
        modifier =
            Modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space5)
                .semantics { paneTitle = title }
                .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) {
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .focusRequester(titleFocus)
                    .focusable(),
        )
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
        ) {
            body()
        }
        buttons()
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary)
}

@Composable
private fun Bullet(text: String) {
    // 圆点与首行文字垂直居中：随字号缩放（200% 时行高变大）
    val bulletTop = with(LocalDensity.current) { (CouliTypography.footnote.lineHeight.toDp() - BULLET_SIZE) / 2 }
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Box(
            modifier =
                Modifier
                    .padding(top = bulletTop)
                    .size(BULLET_SIZE)
                    .background(CouliColors.textSecondary, CircleShape),
        )
        BodyText(text)
    }
}

/** 书名号链接：每条不拆行，放不下时整条换到下一行（adaptation-a11y-spec §1 第 4 条）；经 Router 打开 Agreement。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgreementLinks(
    links: List<Pair<String, AgreementLink>>,
    onOpen: (AgreementLink) -> Unit,
) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        links.forEach { (label, link) ->
            Text(
                text = label,
                style = CouliTypography.footnote,
                color = CouliColors.textLink,
                softWrap = false,
                modifier = Modifier.clickable(role = Role.Button, onClickLabel = null) { onOpen(link) },
            )
        }
    }
}

/**
 * 一主一次（GUIDE §6）：并排放得下（两者都不折行）则等宽并排，次左主右；放不下（如 200% 字号）整块纵排，主按钮在上。
 */
@Composable
private fun PromptButtons(
    primaryText: String,
    onPrimary: () -> Unit,
    primaryTag: String,
    secondaryText: String,
    onSecondary: () -> Unit,
    secondaryTag: String,
) {
    val contents: List<@Composable () -> Unit> =
        listOf(
            { CouliButton(text = primaryText, onClick = onPrimary, modifier = Modifier.fillMaxWidth().testTag(primaryTag)) },
            {
                CouliButton(
                    text = secondaryText,
                    onClick = onSecondary,
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.fillMaxWidth().testTag(secondaryTag),
                )
            },
        )
    Layout(contents = contents) { (primaryMeasurables, secondaryMeasurables), constraints ->
        val gap = CouliSpacing.space3.roundToPx()
        val primary = primaryMeasurables.first()
        val secondary = secondaryMeasurables.first()
        val natural = maxOf(primary.maxIntrinsicWidth(Constraints.Infinity), secondary.maxIntrinsicWidth(Constraints.Infinity))
        if (natural * 2 + gap <= constraints.maxWidth) {
            val each = (constraints.maxWidth - gap) / 2
            val fixed = Constraints.fixedWidth(each)
            val p = primary.measure(fixed)
            val s = secondary.measure(fixed)
            layout(constraints.maxWidth, maxOf(p.height, s.height)) {
                s.place(0, 0)
                p.place(each + gap, 0)
            }
        } else {
            val full = Constraints.fixedWidth(constraints.maxWidth)
            val p = primary.measure(full)
            val s = secondary.measure(full)
            layout(constraints.maxWidth, p.height + gap + s.height) {
                p.place(0, 0)
                s.place(0, p.height + gap)
            }
        }
    }
}

private val BRAND_TILE_SIZE = 88.dp
private val BRAND_TILE_RADIUS = 20.dp
private val BULLET_SIZE = 4.dp

/** 弹窗最大宽：手机上即屏宽 − 2 × 24（设计稿 left/right 24）；大屏不再变宽。 */
private val DIALOG_MAX_WIDTH = 342.dp
private const val SCRIM_ALPHA = 0.4f
private const val TITLE_FOCUS_DELAY_MILLIS = 150L
