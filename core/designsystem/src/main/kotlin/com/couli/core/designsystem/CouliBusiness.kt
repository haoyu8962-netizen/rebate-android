package com.couli.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** 价格走势面板的内容状态。 */
sealed interface PriceTrendContent {
    /** 展开后请求中。 */
    data class Loading(
        val description: String,
    ) : PriceTrendContent

    /** 暂无记录（BR-TEXT-13 文案由调用方给）。 */
    data class Empty(
        val text: String,
    ) : PriceTrendContent

    /** 有记录：[points] 为按日的券后价（分），[summary] 是给读屏与图下的文字摘要（最低 / 最高 / 当前，调用方经格式化函数拼好）。 */
    data class Records(
        val points: List<Long>,
        val summary: String,
    ) : PriceTrendContent
}

/**
 * 价格走势（组件清单 §4 `PriceTrendPanel`，F-PROD-14）：[DisclosureRow]「近 30 天券后价」，展开才请求；有记录画折线（brand.emphasis），
 * 图下给文字摘要（读屏读摘要，图本身跳过）；暂无记录与加载中各一行。
 */
@Composable
fun PriceTrendPanel(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: PriceTrendContent,
    modifier: Modifier = Modifier,
) {
    DisclosureRow(title = title, expanded = expanded, onToggle = onToggle, modifier = modifier.couliCard()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            when (content) {
                is PriceTrendContent.Loading -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CouliLoading(description = content.description) }
                }

                is PriceTrendContent.Empty -> {
                    Text(text = content.text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                }

                is PriceTrendContent.Records -> {
                    TrendLine(points = content.points)
                    Text(text = content.summary, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun TrendLine(points: List<Long>) {
    val line = CouliColors.brandEmphasis
    val grid = CouliColors.borderSubtle
    Canvas(modifier = Modifier.fillMaxWidth().height(TREND_HEIGHT).clearAndSetSemantics { }) {
        val stroke = CouliSizes.hairline.toPx() * 2
        drawLine(grid, Offset(0f, size.height - stroke), Offset(size.width, size.height - stroke), strokeWidth = CouliSizes.hairline.toPx())
        if (points.size < 2) return@Canvas
        val min = points.min()
        val range = (points.max() - min).coerceAtLeast(1)
        val step = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { index, value ->
            val x = index * step
            val y = stroke + (size.height - stroke * 2) * (1f - (value - min).toFloat() / range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 一张分享素材。 */
@Immutable
data class ShareMaterial(
    /** 读屏名称（「二维码海报」「商品图 2」）。 */
    val label: String,
    val isQrCode: Boolean = false,
)

/**
 * 分享素材多选（组件清单 §4 `ShareMaterialPicker`，F-SHARE-10～13）：4 列方格（二维码首图），每格右上 22 的圆形勾选（同一个
 * [CouliCheckbox]）；顶部「全选」与「将保存 n 张」提示。格子是图片占位（背景 placeholder），读屏按勾选框读。
 */
@Composable
fun ShareMaterialPicker(
    materials: List<ShareMaterial>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    selectAllText: String,
    onSelectAll: () -> Unit,
    saveCountText: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = saveCountText, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.weight(1f))
            CouliButton(text = selectAllText, onClick = onSelectAll, variant = CouliButtonVariant.TEXT)
        }
        materials.chunked(SHARE_COLUMNS).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                repeat(SHARE_COLUMNS) { column ->
                    val index = row * SHARE_COLUMNS + column
                    val material = chunk.getOrNull(column)
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                        if (material != null) {
                            ImagePlaceholder(
                                state = ImageLoadState.ERROR,
                                label = material.label,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                icon = if (material.isQrCode) CouliIcons.link else CouliIcons.image,
                            )
                            CouliCheckbox(
                                checked = index in selected,
                                onCheckedChange = { onToggle(index) },
                                contentDescription = material.label,
                                glyphSize = SHARE_CHECKBOX,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分享文案的一段（只显示接口返回的段，不拼收益金额）。 */
@Immutable
data class ShareCopySegment(
    val text: String,
    val checked: Boolean,
    /** 必选段：置灰选中，不能取消。 */
    val required: Boolean = false,
)

/** 分享文案编辑器的模式。 */
enum class ShareCopyMode {
    VIEW,
    EDIT,

    /** 编辑后又改勾选：提示会覆盖已编辑的文字。 */
    OVERWRITE_CONFIRM,
}

/**
 * 分享文案编辑（组件清单 §4 `ShareCopyEditor`）：查看态每段一行勾选（必选段置灰选中）；编辑态是多行输入（[CouliTextField]）；
 * 改勾选会覆盖已编辑文字时先给提示（[overwriteNotice] + 两个按钮）。文案全部由调用方给。
 */
@Composable
fun ShareCopyEditor(
    segments: List<ShareCopySegment>,
    onToggle: (Int) -> Unit,
    mode: ShareCopyMode,
    editedText: String,
    onEditedTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    overwriteNotice: String? = null,
    overwriteConfirm: StateAction? = null,
    overwriteCancel: StateAction? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard(background = CouliColors.backgroundMuted).padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (mode == ShareCopyMode.EDIT) {
            CouliTextField(value = editedText, onValueChange = onEditedTextChange, singleLine = false)
        } else {
            segments.forEachIndexed { index, segment ->
                Row(verticalAlignment = Alignment.Top) {
                    CouliCheckbox(
                        checked = segment.checked || segment.required,
                        onCheckedChange = { onToggle(index) },
                        contentDescription = segment.text,
                        enabled = !segment.required,
                    )
                    Text(
                        text = segment.text,
                        style = CouliTypography.footnote,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.weight(1f).padding(top = CouliSpacing.space3).clearAndSetSemantics { },
                    )
                }
            }
        }
        if (mode == ShareCopyMode.OVERWRITE_CONFIRM && overwriteNotice != null) {
            NoticeBar(text = overwriteNotice, tone = NoticeTone.WARNING)
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                overwriteCancel?.let {
                    CouliButton(
                        text = it.label,
                        onClick = it.onClick,
                        variant = CouliButtonVariant.SECONDARY,
                        size = CouliButtonSize.COMPACT,
                    )
                }
                overwriteConfirm?.let { CouliButton(text = it.label, onClick = it.onClick, size = CouliButtonSize.COMPACT) }
            }
        }
    }
}

private const val SHARE_COLUMNS = 4
private val SHARE_CHECKBOX = 22.dp
private val TREND_HEIGHT = 96.dp
