// 「我的」Tab 视图层的辅助件（APP-12v-a）：邀请码流式行、引导条文字切分、轻提示队列与一次性播报。只排版与决定显示哪条，不碰执行器
@file:Suppress("TooManyFunctions")

package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.me.MeTexts
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** 视觉 16、48 命中区以图标为中心外溢（不撑高所在行；照 WalletHeroHeader 紧凑眼睛）。 */
internal fun Modifier.meCompactHit(): Modifier = size(CouliSizes.iconSmall).wrapContentSize(unbounded = true)

/**
 * 引导条文字切分（G-M10）：按最后一个「。」切，尾段 1–[GUIDE_LINK_MAX] 字才画链接样式（600、下划线、链接色）；没有句号、尾段为空或过长都整句普通。
 * 读屏仍是整句原文（只加样式，不增删字）。
 */
internal fun guideSpans(
    sentence: String,
    link: Color,
): AnnotatedString {
    val cut = sentence.lastIndexOf(SENTENCE_END)
    val tail = if (cut < 0) "" else sentence.substring(cut + 1)
    if (tail.isEmpty() || tail.length > GUIDE_LINK_MAX) return AnnotatedString(sentence)
    return buildAnnotatedString {
        append(sentence.substring(0, cut + 1))
        withStyle(SpanStyle(color = link, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) { append(tail) }
    }
}

/** 「我的邀请码 」+ 码（12/600 主文字、字距 1、等宽数字）；读屏仍是「我的邀请码 K7M3QX」。 */
internal fun inviteCodeText(
    label: String,
    code: String,
    codeColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        append("$label ")
        withStyle(
            CouliTypography.caption
                .tabular()
                .copy(color = codeColor, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                .toSpanStyle(),
        ) { append(code) }
    }

/**
 * 邀请码流式行（M8 / M20）：码段 → 复制（紧随码末字后 4；码段折行时跟在末行末字后，末行放不下才换到下一行行首，不停在多行文字中部）→ 邀请人
 * （与复制同一行放得下就同行、间距 16，使复制 48 命中区不压邀请人；另起一行时若与命中区横向相交，下移到命中区以下）。行间 4。
 * [content] 依次放码段 Text（把 onCodeLayout 接到它的 onTextLayout）、复制按钮、邀请人；没有码时只放邀请人。
 */
@Composable
internal fun InviteFlow(
    hasCode: Boolean,
    content: @Composable (onCodeLayout: (TextLayoutResult) -> Unit) -> Unit,
) {
    // 码段的排版结果只在同一次测量里同步读取（Text 测量时回调），不进状态、不触发重组
    val holder = remember { CodeLayoutHolder() }
    Layout(content = { content { holder.result = it } }) { measurables, constraints ->
        if (hasCode) inviteFlow(measurables, constraints, holder) else inviterOnly(measurables, constraints)
    }
}

private class CodeLayoutHolder {
    var result: TextLayoutResult? = null
}

private fun MeasureScope.inviterOnly(
    measurables: List<Measurable>,
    constraints: Constraints,
): MeasureResult {
    val only = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
    return layout(constraints.maxWidth.coerceAtLeast(only.width), only.height) { only.place(0, 0) }
}

private fun MeasureScope.inviteFlow(
    measurables: List<Measurable>,
    constraints: Constraints,
    holder: CodeLayoutHolder,
): MeasureResult {
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val maxWidth = constraints.maxWidth
    val gap = CouliSpacing.space1.roundToPx()
    val code = measurables[0].measure(loose)
    val copy = measurables[1].measure(Constraints())
    val inviter = measurables.getOrNull(2)?.measure(loose)
    val line = lastLine(holder.result, code)
    val inline = line.right + gap + copy.width <= maxWidth
    val copyX = if (inline) line.right + gap else 0
    val lineCenter = if (inline) (line.top + line.bottom) / 2 else code.height + gap + copy.height / 2
    val copyY = lineCenter - copy.height / 2
    val inviterAt =
        inviter?.let {
            inviterPosition(
                InviteRow(copyX, copy.width, lineCenter, maxOf(code.height, copyY + copy.height) + gap),
                it.width,
                it.height,
                maxWidth,
            )
        }
    // 同行的邀请人若比码段末行高，整体下移，不画到本行之上
    val shift = maxOf(0, -(inviterAt?.y ?: 0), -copyY)
    val inviterBottom = if (inviter != null && inviterAt != null) inviterAt.y + inviter.height else 0
    val height = shift + maxOf(code.height, copyY + copy.height, inviterBottom)
    return layout(maxWidth, height) {
        code.place(0, shift)
        copy.place(copyX, copyY + shift)
        if (inviter != null && inviterAt != null) inviter.place(inviterAt.x, inviterAt.y + shift)
    }
}

/** 码段末行的右端与上下（像素）；没有排版结果时按整块单行。 */
private data class CodeLine(
    val right: Int,
    val top: Int,
    val bottom: Int,
)

private fun lastLine(
    text: TextLayoutResult?,
    code: androidx.compose.ui.layout.Placeable,
): CodeLine {
    if (text == null) return CodeLine(code.width, 0, code.height)
    val last = text.lineCount - 1
    return CodeLine(ceil(text.getLineRight(last)).toInt(), text.getLineTop(last).toInt(), ceil(text.getLineBottom(last)).toInt())
}

/** 复制所在行：复制左端、宽、行中线，以及另起一行时的最低起点。 */
private data class InviteRow(
    val copyX: Int,
    val copyWidth: Int,
    val center: Int,
    val below: Int,
)

private fun MeasureScope.inviterPosition(
    row: InviteRow,
    width: Int,
    height: Int,
    maxWidth: Int,
): IntOffset {
    val sameLineX = row.copyX + row.copyWidth + CouliSpacing.space4.roundToPx()
    if (sameLineX + width <= maxWidth) return IntOffset(sameLineX, row.center - height / 2)
    val half = (CouliSizes.touchTarget / 2).roundToPx()
    // 另起一行：与复制命中区横向相交时下移到命中区之下（命中区不重叠）
    val crosses = row.copyX + row.copyWidth / 2 - half < width
    return IntOffset(0, if (crosses) maxOf(row.below, row.center + half) else row.below)
}

/**
 * 「我的」页轻提示入口（评审 r1 S1）：复制结果 Flow 的收集与整个提示宿主（显示状态、播报节点、2 秒消费计时）都挂在 [controller] 的 key 下。
 * 换身份 / 撤回同意 / 退出 / 离开重建执行器的同一帧，旧执行器的 State、排队中的复制结果与计时一并丢弃，不会出现上一账号的提示。
 */
@Composable
internal fun MeNotices(
    texts: MeTexts,
    controller: MeTabController,
    error: MeNotice?,
    consumeError: (Long) -> Unit,
) {
    key(controller) {
        val copy by controller.toast.collectAsStateWithLifecycle()
        MeNoticeQueue(texts, controller, copy, error, consumeError)
    }
}

/**
 * 轻提示队列（G-M14，规格 §6「轻提示播报顺序」）：一次只显示一条；同时有时刷新失败优先，复制结果排在后面（不提前消费）。正在显示的一条显示满
 * 2 秒再换下一条，不被后到的打断。队列只是视图状态，随执行器（[controller]，换身份 / 撤回同意 / 退出 / 离开后重建）作废；
 * 刷新失败只取当前身份的（调用方过滤）。不改 consumeToast / consumeError 的消费时机与 id 规则。
 */
@Composable
private fun MeNoticeQueue(
    texts: MeTexts,
    controller: MeTabController,
    copy: MeNotice?,
    error: MeNotice?,
    consumeError: (Long) -> Unit,
) {
    var showing by remember(controller) { mutableStateOf<NoticeSlot?>(null) }
    val errorSlot = error?.let { NoticeSlot(error = true, id = it.id) }
    val copySlot = copy?.let { NoticeSlot(error = false, id = it.id) }
    val current = showing?.takeIf { it == errorSlot || it == copySlot } ?: errorSlot ?: copySlot
    SideEffect { showing = current }
    val notice = (if (current?.error == true) error else copy) ?: return
    key(current) {
        MeNoticeHost(
            message = notice.message(texts),
            id = notice.id,
            icon = if (current?.error == false && notice.key == MeTabController.COPIED) CouliIcons.check else null,
            assertive = current?.error == true,
        ) { if (current?.error == true) consumeError(notice.id) else controller.consumeToast(notice.id) }
    }
}

private data class NoticeSlot(
    val error: Boolean,
    val id: Long,
)

/**
 * 「我的」页私有轻提示宿主（M15）：顶部、2 秒后消费；复制成功带对勾（G-M12），失败与刷新失败不带。
 * 单一播报出口（评审 r1 S2）：外观与共用 CouliToast 相同，但不主动播报，只靠 liveRegion——刷新失败 assertive、复制结果 polite。
 * 共用 CouliToast 的默认行为不变；设置一族仍用 [MeToastHost]。
 */
@Composable
private fun MeNoticeHost(
    message: String,
    id: Long,
    icon: ImageVector?,
    assertive: Boolean,
    onDone: () -> Unit,
) {
    LaunchedEffect(id) {
        delay(NOTICE_MILLIS)
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val shape = RoundedCornerShape(CouliRadius.button)
        Row(
            modifier =
                Modifier
                    .padding(top = CouliSpacing.space12)
                    .semantics { liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                    .testTag(MeTags.TOAST)
                    .shadow(CouliSpacing.space2, shape, ambientColor = CouliColors.textPrimary, spotColor = CouliColors.textPrimary)
                    .clip(shape)
                    .background(CouliColors.backgroundSurface)
                    .padding(CouliSpacing.space3),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = CouliColors.statusSuccessText, modifier = Modifier.size(CouliSizes.iconMedium))
            }
            Text(text = message, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    }
}

/**
 * 眼睛切换结果的单一播报出口（G-M13，评审 r1 / r2 S2）：只记文案，节点由 [MeEyeLiveRegion] 画在滚动容器外的页面宿主上——
 * 余额卡滚到卡顶出视口、眼睛仍可点时也能播。[rememberMeEyeSpeaker] 按 [MeTabController]（身份）隔离，进入 / 回前台 / 重建不播。
 */
@Stable
internal class MeEyeSpeaker {
    var spoken by mutableStateOf(EyeSpoken("", 0))
        private set

    fun announce(text: String) {
        spoken = EyeSpoken(text, spoken.seq + 1)
    }

    fun clear(seq: Int) {
        if (spoken.seq == seq) spoken = spoken.copy(text = "")
    }
}

@Composable
internal fun rememberMeEyeSpeaker(controller: MeTabController): MeEyeSpeaker = remember(controller) { MeEyeSpeaker() }

/** 常驻、不绘制的 polite liveRegion 节点：文案变化即系统播报一次，2 秒后清空（清空不读，读屏划过也不会停在旧文案上）。放在页面宿主最上层、滚动容器外。 */
@Composable
internal fun MeEyeLiveRegion(speaker: MeEyeSpeaker) {
    val spoken = speaker.spoken
    LaunchedEffect(speaker, spoken.seq) {
        if (spoken.text.isNotEmpty()) {
            delay(NOTICE_MILLIS)
            speaker.clear(spoken.seq)
        }
    }
    Box(
        modifier =
            Modifier.size(CouliSpacing.space1).clearAndSetSemantics {
                if (spoken.text.isNotEmpty()) contentDescription = spoken.text
                liveRegion = LiveRegionMode.Polite
            },
    )
}

internal data class EyeSpoken(
    val text: String,
    val seq: Int,
)

/** 中文句号「。」（U+3002）；切分规则用的分隔符，不是文案（feature 不写中文字面量）。 */
private val SENTENCE_END = Char(IDEOGRAPHIC_FULL_STOP)
private const val IDEOGRAPHIC_FULL_STOP = 0x3002
private const val GUIDE_LINK_MAX = 8
private const val NOTICE_MILLIS = 2_000L
