package com.couli.app.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couli.app.R
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.generated.DefaultTexts

const val FORCE_UPDATE_PAGE_TAG = "force_update_page"
const val FORCE_UPDATE_PRIMARY_TAG = "force_update_primary"
const val FORCE_UPDATE_UNAVAILABLE_TAG = "force_update_unavailable"
const val FORCE_UPDATE_PRIVACY_TAG = "force_update_privacy_policy"
const val FORCE_UPDATE_DELETE_TAG = "force_update_delete_account"
const val UPDATE_PROMPT_TAG = "update_prompt"

/** 强更页与可关闭提示的文案键（BR-TEXT-12）。 */
internal object UpdateTexts {
    fun text(key: String): String = DefaultTexts.text(key).orEmpty()

    const val GO_STORE = "app_update.go_store"
    const val LATER = "app_update.later"
    const val RETRY = "app_update.retry"
    const val UNAVAILABLE = "app_update.unavailable"
    const val PRIVACY_POLICY = "app_update.privacy_policy"
    const val DELETE_ACCOUNT = "app_update.delete_account"
    const val CANCEL_DELETION = "app_update.cancel_deletion"

    /** 设计稿副标题「当前版本过低，请更新后继续使用」= 10405 的文案。 */
    const val TOO_OLD = "error.10405"
}

/** 强更页的次要入口与按钮回调。 */
class ForceUpdateActions(
    val onGoStore: () -> Unit,
    val onRetry: () -> Unit,
    val onPrivacyPolicy: () -> Unit,
    val onDeleteAccount: () -> Unit,
)

/**
 * ForceUpdate 全屏拦截页（设计稿 ForceUpdate / ForceUpdateUnavailable；03 §4.1）。
 * - 主按钮【去更新】→ 选店；没有可去的商店时显示 app_update.unavailable 提示条，主按钮换成【重试】（重新检查再选），不给下载地址；
 * - 次要入口「隐私政策」与「注销账号 / 撤销注销」（APP-13a G28：[deleteLabelKey] 由 App 按已登录时取的 getDeletion 决定；null = 不显示——
 *   DeleteAccount 不可用（since=null 且没有 Debug 覆盖，G01 ②）、基本模式（G48）。TODO(N-37) 契约给 since 后自动显示）；
 * - 返回键吞掉（不能离开强更页）；内容区可滚动（200% 字号），底部按钮固定。
 */
@Composable
fun ForceUpdatePage(
    ui: UpdateUi,
    actions: ForceUpdateActions,
    deleteLabelKey: String? = null,
) {
    BackHandler(enabled = true) {}
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(FORCE_UPDATE_PAGE_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space12),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
        ) {
            ForceUpdateHeader(version = ui.result?.latest ?: ui.state.targetVersion)
            UpdateNotesCard(notes = ui.result?.updateNotes)
            if (ui.forceUnavailable) UnavailableNotice()
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = CouliSpacing.space4,
                        end = CouliSpacing.space4,
                        top = CouliSpacing.space3,
                        bottom = CouliSpacing.space4,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            CouliButton(
                text = UpdateTexts.text(if (ui.forceUnavailable) UpdateTexts.RETRY else UpdateTexts.GO_STORE),
                onClick = if (ui.forceUnavailable) actions.onRetry else actions.onGoStore,
                loading = ui.working,
                modifier = Modifier.fillMaxWidth().testTag(FORCE_UPDATE_PRIMARY_TAG),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                SecondaryLink(UpdateTexts.text(UpdateTexts.PRIVACY_POLICY), FORCE_UPDATE_PRIVACY_TAG, actions.onPrivacyPolicy)
                if (deleteLabelKey != null) {
                    Text(
                        text = "|",
                        style = CouliTypography.footnote,
                        color = CouliColors.borderSubtle,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    // 冷静期且可撤回为「撤销注销」（app_update.cancel_deletion），其余「注销账号」（G28）
                    SecondaryLink(UpdateTexts.text(deleteLabelKey), FORCE_UPDATE_DELETE_TAG, actions.onDeleteAccount)
                }
            }
        }
    }
}

@Composable
private fun ForceUpdateHeader(version: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) {
        Box(
            modifier =
                Modifier
                    .size(BRAND_TILE_SIZE)
                    .background(CouliColors.brandPrimary, RoundedCornerShape(BRAND_TILE_RADIUS))
                    .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = CouliTypography.title.copy(fontWeight = FontWeight.Bold, letterSpacing = BRAND_LETTER_SPACING),
                color = CouliColors.textInverse,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Text(
                text = UpdateDraftTexts.forceTitle(version),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = UpdateTexts.text(UpdateTexts.TOO_OLD),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 更新内容卡片：update_notes 按行拆成要点；没有更新说明时不显示卡片。 */
@Composable
private fun UpdateNotesCard(notes: String?) {
    val lines =
        notes
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Text(text = UpdateDraftTexts.NOTES_TITLE, style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = BULLET_TOP)
                                .size(BULLET_SIZE)
                                .background(CouliColors.textSecondary, CircleShape),
                    )
                    Text(text = line, style = CouliTypography.footnote, color = CouliColors.textPrimary)
                }
            }
        }
    }
}

/** 「暂时无法更新…」提示条（status.warning）。 */
@Composable
private fun UnavailableNotice() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .testTag(FORCE_UPDATE_UNAVAILABLE_TAG),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            imageVector = CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Text(text = UpdateTexts.text(UpdateTexts.UNAVAILABLE), style = CouliTypography.footnote, color = CouliColors.statusWarningText)
    }
}

@Composable
private fun SecondaryLink(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = CouliSpacing.space2)
                .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textLink)
    }
}

/**
 * 可关闭更新提示（设计稿 MainUpdatePrompt）：标题取 update_title，说明取 update_notes（没有时用 error.10405 那句）；
 * 【暂不更新】（次）与【去更新】（主）。返回键与点外部不关闭：由用户选一个（「暂不更新」按目标版本记录）。
 * 来源商店打不开时在说明后面加 app_update.unavailable（可选更新不改去别家，BR-ID-01）。
 */
@Composable
fun UpdatePromptDialog(
    ui: UpdateUi,
    onGoStore: () -> Unit,
    onLater: () -> Unit,
) {
    val notes = ui.result?.updateNotes?.takeIf { it.isNotBlank() } ?: UpdateTexts.text(UpdateTexts.TOO_OLD)
    val message = if (ui.promptUnavailable) notes + "\n\n" + UpdateTexts.text(UpdateTexts.UNAVAILABLE) else notes
    Box(modifier = Modifier.testTag(UPDATE_PROMPT_TAG)) {
        CouliDialog(
            title = ui.result?.updateTitle?.takeIf { it.isNotBlank() } ?: UpdateDraftTexts.forceTitle(ui.state.targetVersion),
            message = message,
            confirmText = UpdateTexts.text(UpdateTexts.GO_STORE),
            onConfirm = onGoStore,
            dismissText = UpdateTexts.text(UpdateTexts.LATER),
            onDismiss = onLater,
            onDismissRequest = {},
            dismissible = false,
        )
    }
}

private val BRAND_TILE_SIZE = 80.dp
private val BRAND_TILE_RADIUS = 20.dp
private val BRAND_LETTER_SPACING = 2.sp
private val BULLET_SIZE = 4.dp
private val BULLET_TOP = 9.dp
