package com.couli.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentFeedbackValue
import com.couli.core.agent.AgentReplyActions
import com.couli.core.agent.AgentReportSheet
import com.couli.core.agent.AgentSnapshot
import com.couli.core.agent.AgentTexts
import com.couli.core.designsystem.AiLabel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.CouliTypography

/**
 * APP-10b §2 第 8 条（AgentResult / AgentReported 稿）：回复底部一行——AiLabel（+「已举报」）在左，【赞】【踩】在右（44 命中区、20 图标）。点了立即实心并读屏「已赞 /
 * 已踩」；在途或动作闸门期间两个都不可点；同值再点不发（Core 判）。只对拿到可信回复 id 的消息画（Core 给 [AgentReplyActions]）。
 */
@Composable
internal fun FeedbackBar(
    key: String,
    aiLabel: String?,
    actions: AgentReplyActions,
    controller: AgentController,
    texts: AgentTexts,
) {
    val ticket = actions.ticket
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            aiLabel?.let { AiLabel(text = it, modifier = Modifier.testTag(AgentTags.AI_LABEL)) }
            if (actions.reported) {
                CouliTag(
                    text = texts.text(REPORTED_KEY),
                    tone = CouliTagTone.NEUTRAL,
                    icon = AgentGlyphs.flag,
                    modifier = Modifier.testTag(AgentTags.reported(key)),
                )
            }
        }
        Thumb(
            selected = actions.selected == AgentFeedbackValue.UP,
            enabled = actions.enabled,
            label = texts.text(UP_KEY),
            selectedLabel = texts.text(UP_SELECTED_KEY),
            icon = if (actions.selected == AgentFeedbackValue.UP) AgentGlyphs.thumbUpFilled else AgentGlyphs.thumbUp,
            onClick = { controller.feedback(ticket, AgentFeedbackValue.UP) },
            modifier = Modifier.testTag(AgentTags.feedbackUp(key)),
        )
        Thumb(
            selected = actions.selected == AgentFeedbackValue.DOWN,
            enabled = actions.enabled,
            label = texts.text(DOWN_KEY),
            selectedLabel = texts.text(DOWN_SELECTED_KEY),
            icon = if (actions.selected == AgentFeedbackValue.DOWN) AgentGlyphs.thumbDownFilled else AgentGlyphs.thumbDown,
            onClick = { controller.feedback(ticket, AgentFeedbackValue.DOWN) },
            modifier = Modifier.testTag(AgentTags.feedbackDown(key)),
        )
    }
}

/** 赞 / 踩按钮：读屏名「赞 / 踩」，选中后读「已赞 / 已踩」（flow-map 23.2.22）。 */
@Composable
private fun Thumb(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    selectedLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = if (selected) selectedLabel else label
                    if (selected) stateDescription = selectedLabel
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                if (selected) {
                    CouliColors.brandEmphasis
                } else if (enabled) {
                    CouliColors.textSecondary
                } else {
                    CouliColors.borderControl
                },
            modifier = Modifier.size(CouliSizes.iconSmall),
        )
    }
}

/**
 * 举报半屏（AgentReport 稿，系统模态：系统返回、点遮罩、下拉、【关闭】都只关本实例）：被举报回复的摘要（受限渲染、截断）、五个原因单选（默认不选，选了才可提交）、
 * 「补充说明（选填）」（按 200 − 原因长度 − 1 个码点限长）、【提交举报】。原因与补充说明只在 Core 内存里，不进日志与埋点。
 */
@Composable
internal fun ReportSheetHost(
    s: AgentSnapshot,
    controller: AgentController,
    texts: AgentTexts,
    visible: Boolean,
) {
    val sheet: AgentReportSheet = s.reportSheet ?: return
    if (!visible) return
    val ticket = sheet.ticket
    CouliSheet(
        title = texts.text(TITLE_KEY),
        onDismissRequest = { controller.closeReport(ticket) },
        closeLabel = texts.text(CLOSE_KEY),
        actions = {
            CouliButton(
                text = texts.text(SUBMIT_KEY),
                onClick = { controller.submitReport(ticket) },
                enabled = sheet.submitEnabled,
                modifier = Modifier.fillMaxWidth().testTag(AgentTags.REPORT_SUBMIT),
            )
        },
    ) {
        Column(modifier = Modifier.testTag(AgentTags.REPORT_SHEET), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) {
            Text(text = texts.text(SUBTITLE_KEY), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            Text(
                text = sheet.excerpt,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                maxLines = 2,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                        .padding(CouliSpacing.space3),
            )
            Column(modifier = Modifier.selectableGroup()) {
                sheet.reasons.forEachIndexed { i, reason ->
                    ReasonRow(
                        reason,
                        reason == sheet.selected,
                        enabled = !sheet.inFlight,
                        modifier = Modifier.testTag(AgentTags.reportReason(i)),
                    ) {
                        controller.selectReportReason(ticket, reason)
                    }
                }
            }
            if (sheet.reasonError) {
                Text(text = texts.text(REASON_ERROR_KEY), style = CouliTypography.footnote, color = CouliColors.statusWarningText)
            }
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                val label = texts.text(SUPPLEMENT_KEY)
                Text(text = label, style = CouliTypography.footnote, color = CouliColors.textPrimary)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = SUPPLEMENT_MIN_HEIGHT.dp)
                            .border(CouliSizes.hairline, CouliColors.borderControl, RoundedCornerShape(CouliRadius.button))
                            .padding(CouliSpacing.space3),
                ) {
                    BasicTextField(
                        value = sheet.supplement,
                        onValueChange = { text -> controller.editReportSupplement(ticket, limit(text, sheet.supplementLimit)) },
                        enabled = !sheet.inFlight,
                        textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                        cursorBrush = SolidColor(CouliColors.textPrimary),
                        modifier = Modifier.fillMaxWidth().testTag(AgentTags.REPORT_SUPPLEMENT).semantics { contentDescription = label },
                    )
                }
                if (sheet.tooLong) {
                    Text(text = texts.text(TOO_LONG_KEY), style = CouliTypography.footnote, color = CouliColors.statusWarningText)
                }
            }
            if (sheet.inFlight) {
                CircularProgressIndicator(
                    color = CouliColors.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
    }
}

/** 原因单选行（44 命中区；读屏是单选按钮）。 */
@Composable
private fun ReasonRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Box(
            modifier =
                Modifier
                    .size(RADIO_SIZE.dp)
                    .border(
                        if (selected) RADIO_SELECTED_BORDER.dp else CouliSizes.hairline,
                        if (selected) CouliColors.brandPrimary else CouliColors.borderControl,
                        RoundedCornerShape(CouliRadius.pill),
                    ),
        )
        Text(text = text, style = CouliTypography.body, color = CouliColors.textPrimary)
    }
}

/** 按码点截断（G29：补充说明不超过上限；不截断代理对）。 */
private fun limit(
    text: String,
    max: Int,
): String = if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

/** 「加载更早的消息」一行（G08）：可点 / 加载中 / 失败（点它重试，鸿蒙 G74）/ 没有更早的消息；读取闸门期间不可点。 */
@Composable
internal fun EarlierRow(
    s: AgentSnapshot,
    controller: AgentController,
    texts: AgentTexts,
) {
    val page = s.page
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget), contentAlignment = Alignment.Center) {
        when {
            s.earlierLoading -> {
                CircularProgressIndicator(
                    color = CouliColors.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }

            s.hasEarlier -> {
                CouliButton(
                    text = s.earlierError.ifEmpty { texts.text(EARLIER_KEY) },
                    onClick = { controller.loadEarlier(page) },
                    enabled = !s.readLimited,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.EARLIER),
                )
            }

            s.historyExhausted -> {
                Text(
                    text = texts.text(NONE_KEY),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.testTag(AgentTags.EARLIER_NONE),
                )
            }
        }
    }
}

/** 会话已结束 / 已过期（G09、历史 30504）：error.30504 +【新对话】（只读时输入框与胶囊同时禁用）。 */
@Composable
internal fun SessionEndedBanner(
    s: AgentSnapshot,
    controller: AgentController,
    texts: AgentTexts,
) {
    if (s.sessionEndedText.isEmpty()) return
    val page = s.page
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                .testTag(AgentTags.SESSION_ENDED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = s.sessionEndedText, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
        CouliButton(
            text = texts.text(NEW_CHAT_KEY),
            onClick = { controller.newChat(page) },
            variant = CouliButtonVariant.SECONDARY,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.testTag(AgentTags.SESSION_ENDED_NEW_CHAT),
        )
    }
}

/** 本页用到的几个图标（设计稿 AgentResult / AgentReported 的 SVG 路径，24 视口、1.75 描边；实心版用于已选）。 */
internal object AgentGlyphs {
    private const val VIEWPORT = 24f
    private const val STROKE = 1.75f

    private fun icon(
        name: String,
        filled: Boolean,
        vararg paths: String,
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, VIEWPORT, VIEWPORT)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = PathParser().parsePathString(d).toNodes(),
                        fill = if (filled) SolidColor(Color.Black) else null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = STROKE,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()

    private const val THUMB_STEM = "M7.5 10.5v9h-3v-9h3Z"
    private const val THUMB_UP = "M7.5 10.5 11 4c1.3 0 2.2 1 2.2 2.2v3.3h4.3a1.6 1.6 0 0 1 1.6 1.9l-1.2 6.7a1.7 1.7 0 0 1-1.7 1.4H7.5"
    private const val THUMB_DOWN_STEM = "M7.5 13.5v-9h-3v9h3Z"
    private const val THUMB_DOWN = "M7.5 13.5 11 20c1.3 0 2.2-1 2.2-2.2v-3.3h4.3a1.6 1.6 0 0 0 1.6-1.9l-1.2-6.7a1.7 1.7 0 0 0-1.7-1.4H7.5"

    val thumbUp: ImageVector by lazy { icon("ThumbUp", false, THUMB_STEM, THUMB_UP) }
    val thumbUpFilled: ImageVector by lazy { icon("ThumbUpFilled", true, THUMB_STEM, THUMB_UP) }
    val thumbDown: ImageVector by lazy { icon("ThumbDown", false, THUMB_DOWN_STEM, THUMB_DOWN) }
    val thumbDownFilled: ImageVector by lazy { icon("ThumbDownFilled", true, THUMB_DOWN_STEM, THUMB_DOWN) }
    val flag: ImageVector by lazy { icon("Flag", false, "M6 20.5V4", "M6 4.5h11l-2 4 2 4H6") }
}

/** 举报读屏等价入口的动作名（G54：「更多操作，举报」）。 */
internal fun reportActionLabel(texts: AgentTexts): String = texts.text(MORE_ENTRY_KEY)

private const val UP_KEY = "agent.draft.feedback.up"
private const val DOWN_KEY = "agent.draft.feedback.down"
private const val UP_SELECTED_KEY = "agent.draft.feedback.up_selected"
private const val DOWN_SELECTED_KEY = "agent.draft.feedback.down_selected"
private const val REPORTED_KEY = "agent.draft.report.reported"
private const val TITLE_KEY = "agent.draft.report.title"
private const val SUBTITLE_KEY = "agent.draft.report.subtitle"
private const val SUBMIT_KEY = "agent.draft.report.submit"
private const val SUPPLEMENT_KEY = "agent.draft.report.supplement"
private const val TOO_LONG_KEY = "agent.draft.report.too_long"
private const val REASON_ERROR_KEY = "agent.draft.report.reason_error"
private const val CLOSE_KEY = "agent.draft.report.close"
private const val MORE_ENTRY_KEY = "agent.draft.report.more_entry"
private const val EARLIER_KEY = "agent.draft.history.earlier"
private const val NONE_KEY = "agent.draft.history.none"
private const val NEW_CHAT_KEY = "agent.draft.new_chat"
private const val SUPPLEMENT_MIN_HEIGHT = 64
private const val RADIO_SIZE = 22
private const val RADIO_SELECTED_BORDER = 6
