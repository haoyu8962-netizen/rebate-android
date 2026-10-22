package com.couli.feature.wallet

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.abs

/*
 * 提现记录列表的记录级位置（评审 r1 S1 / S2-1）。按月合段后一个 Lazy 项装多条记录，Lazy 自己的「首个可见项 + 偏移」和预取都只到月段一级，
 * 这里补上记录一级：
 * - 分页：只有列表最后一条记录真实摆进视口（裁切后仍有高度）才算到了末尾；屏外预组合的页脚不再触发翻页。
 * - 锚点：内容变化（刷新增删、同 ID 记录内容变化、详情迟到、展开收起、宽度变化）前记下「视口内最上面的记录 ID + 相对列表顶边的偏移」，
 *   变化后把同一记录挪回原偏移。用户正在拖动 / 甩动列表时让出：不记锚点，恢复中途被用户接手也立即放弃，不抢位置。
 * 只读布局坐标、只调列表滚动位置；不碰控制器数据、请求与分页游标。
 */
internal class RecordListKeeper {
    private val placed = HashMap<String, LayoutCoordinates>()
    private var list: LayoutCoordinates? = null
    private var signature: Any? = null
    private var anchor: RecordScrollAnchor? = null
    private var sectionKeyOf: Map<String, String> = emptyMap()
    private var lazyIndexOf: Map<String, Int> = emptyMap()

    /** 列表当前最后一条记录（组合时更新）。 */
    private var lastId: String? = null

    /** 最后一条记录在视口内时为它的 ID，否则 null（快照状态，翻页据此触发）。 */
    var visibleLast by mutableStateOf<String?>(null)
        private set

    fun list(coordinates: LayoutCoordinates) {
        list = coordinates
    }

    fun placed(
        id: String,
        coordinates: LayoutCoordinates,
    ) {
        placed[id] = coordinates
        if (id == lastId) {
            val visible = coordinates.isAttached && coordinates.boundsInRoot().height > 0f
            if (visible) {
                visibleLast = id
            } else if (visibleLast == id) {
                visibleLast = null
            }
        }
    }

    fun removed(id: String) {
        placed.remove(id)
        if (visibleLast == id) visibleLast = null
    }

    /**
     * 组合后、布局前调用：内容签名变化且上次恢复已完成时，按变化前的布局记下锚点；再换成新的分段映射。
     * [sections] 为新的分段，[lazyOffset] 为分段之前的 Lazy 项数（刷新错误条）。
     */
    fun content(
        signature: Any,
        sections: List<RecordSection>,
        lazyOffset: Int,
        state: LazyListState,
    ): Boolean {
        lastId =
            sections
                .lastOrNull()
                ?.items
                ?.lastOrNull()
                ?.withdrawalId
        if (signature == this.signature) return false
        if (this.signature != null && anchor == null && !state.isScrollInProgress) anchor = capture(state)
        this.signature = signature
        val keys = HashMap<String, String>()
        val indices = HashMap<String, Int>()
        sections.forEachIndexed { index, section ->
            section.items.forEach {
                keys[it.withdrawalId] = section.key
                indices[it.withdrawalId] = index + lazyOffset
            }
        }
        sectionKeyOf = keys
        lazyIndexOf = indices
        return anchor != null
    }

    /** 变化后的下一帧起把锚点记录挪回原偏移；锚点不在已摆的段里时先滚到它所在的段再校正。记录已不存在则交给 Lazy 默认。 */
    suspend fun restore(state: LazyListState) {
        var frames = 0
        while (anchor != null && frames++ < RESTORE_FRAMES) {
            withFrameNanos { }
            // 用户已接手滚动：让出，不再校正
            if (state.isScrollInProgress) break
            anchor?.let { target -> if (settle(target, state)) anchor = null }
        }
        anchor = null
    }

    /** 锚点记录已摆好则按差值校正并返回 true；不在已摆的段里则滚到它所在段（下一帧再校正）；记录已不存在返回 true（交给 Lazy 默认）。 */
    private fun settle(
        target: RecordScrollAnchor,
        state: LazyListState,
    ): Boolean {
        val now = edges(target.id, visibleKeys(state))
        val index = lazyIndexOf[target.id]
        if (now != null) {
            val delta = now.first - target.offsetPx
            if (abs(delta) >= HALF_PIXEL) state.dispatchRawDelta(delta)
        } else if (index != null) {
            state.requestScrollToItem(index)
        }
        return now != null || index == null
    }

    private fun capture(state: LazyListState): RecordScrollAnchor? {
        val height = list?.takeIf { it.isAttached }?.size?.height ?: return null
        val keys = visibleKeys(state)
        return placed.keys
            .mapNotNull { id -> edges(id, keys)?.let { id to it } }
            .filter { (_, edge) -> edge.second > 0f && edge.first < height }
            .minByOrNull { (_, edge) -> edge.first }
            ?.let { (id, edge) -> RecordScrollAnchor(id, edge.first) }
    }

    private fun visibleKeys(state: LazyListState): Set<Any> =
        state.layoutInfo.visibleItemsInfo
            .map { it.key }
            .toSet()

    /** 记录上下边相对列表顶边（px）；只认摆在可见 Lazy 项里的记录（避免已移出视口的旧坐标）。 */
    private fun edges(
        id: String,
        visibleKeys: Set<Any>,
    ): Pair<Float, Float>? {
        val visible = sectionKeyOf[id]?.let { it in visibleKeys } == true
        val parent = list?.takeIf { it.isAttached && visible }
        val child = placed[id]?.takeIf { it.isAttached }
        if (parent == null || child == null) return null
        val top = parent.localPositionOf(child, Offset.Zero).y
        return top to top + child.size.height
    }
}

private data class RecordScrollAnchor(
    val id: String,
    val offsetPx: Float,
)

/** 记录的外框位置登记给 [RecordListKeeper]（锚点与翻页用）；离开组合时由 [ForgetRecordOnDispose] 注销。 */
internal fun Modifier.trackRecord(
    keeper: RecordListKeeper,
    id: String,
): Modifier = onGloballyPositioned { keeper.placed(id, it) }

@Composable
internal fun ForgetRecordOnDispose(
    keeper: RecordListKeeper,
    id: String,
) {
    DisposableEffect(keeper, id) { onDispose { keeper.removed(id) } }
}

private const val RESTORE_FRAMES = 3
private const val HALF_PIXEL = 0.5f
