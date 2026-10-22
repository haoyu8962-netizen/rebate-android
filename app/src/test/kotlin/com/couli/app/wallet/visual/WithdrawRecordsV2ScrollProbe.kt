package com.couli.app.wallet.visual

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.couli.feature.wallet.WalletTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal data class RecordAnchor(
    val id: String,
    val offsetDp: Float,
)

/** 读取真实测量坐标，不能用已裁切的 boundsInRoot.top（那会把所有负偏移都误判为 0）。 */
internal fun SemanticsNode.recordUnclippedBounds(): Rect {
    val coordinates = layoutInfo.getModifierInfo().firstOrNull()?.coordinates ?: layoutInfo.coordinates
    val origin = coordinates.positionInRoot()
    return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
}

internal class WithdrawRecordsV2ScrollProbe(
    private val rule: ComposeContentTestRule,
) {
    val viewport: Rect get() = rule.onNodeWithTag(WalletTags.RECORDS_LIST).fetchSemanticsNode().boundsInRoot
    val density: Float get() = rule.density.density

    fun scrollByDp(dp: Float) {
        rule.onNodeWithTag(WalletTags.RECORDS_LIST).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp * density) }
        rule.waitForIdle()
    }

    fun browse(id: String): RecordAnchor {
        rule.onNodeWithTag(WalletTags.RECORDS_LIST).performScrollToNode(hasTestTag(WalletTags.RECORD_PREFIX + id))
        val top =
            rule
                .record(id)
                .fetchSemanticsNode()
                .recordUnclippedBounds()
                .top
        scrollByDp((top - viewport.top) / density + 11f)
        return anchor().also {
            assertEquals("前置条件：浏览指定月中记录", id, it.id)
            assertEquals("前置条件：保留非零记录内偏移", -11f, it.offsetDp, 1f)
        }
    }

    fun anchor(): RecordAnchor {
        val view = viewport
        val row =
            rule
                .onAllNodes(
                    SemanticsMatcher("v2 记录 ID") {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(WalletTags.RECORD_PREFIX + "V2_") == true
                    },
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .filter {
                    val bounds = it.recordUnclippedBounds()
                    bounds.bottom > view.top && bounds.top < view.bottom
                }.minBy { it.recordUnclippedBounds().top }
        val id = row.config[SemanticsProperties.TestTag].removePrefix(WalletTags.RECORD_PREFIX)
        return RecordAnchor(id, (row.recordUnclippedBounds().top - view.top) / density)
    }

    fun assertRestored(before: RecordAnchor) {
        rule.waitForIdle()
        val after = anchor()
        println("RECORD_ANCHOR before=$before after=$after viewport=$viewport")
        assertEquals("恢复同一条可见记录，不能只恢复月份项", before.id, after.id)
        assertEquals("记录相对列表顶部的偏移允许 ±1dp", before.offsetDp, after.offsetDp, 1f)
    }

    fun assertFarFromEnd(lastId: String) {
        val remaining =
            rule
                .record(lastId)
                .fetchSemanticsNode()
                .recordUnclippedBounds()
                .bottom - viewport.bottom
        assertTrue("月段末尾仍在一整屏之外：remaining=$remaining viewport=$viewport", remaining > viewport.height)
    }

    fun approachEnd(lastId: String) {
        val bottom =
            rule
                .record(lastId)
                .fetchSemanticsNode()
                .recordUnclippedBounds()
                .bottom
        scrollByDp((bottom - viewport.bottom) / density + 24f)
        val remaining =
            rule
                .record(lastId)
                .fetchSemanticsNode()
                .recordUnclippedBounds()
                .bottom - viewport.bottom
        assertTrue("前置条件：已滚到月段末尾附近", remaining <= 24 * density)
    }

    /** 只读 Compose 当前 LazyListState；不创建替身，也不写滚动位置或产品状态。 */
    fun lazyState(): LazyListState {
        val node = rule.onNodeWithTag(WalletTags.RECORDS_LIST).fetchSemanticsNode()
        val modifier =
            node.layoutInfo.getModifierInfo().map { it.modifier }.single {
                it.javaClass.simpleName == "LazyLayoutSemanticsModifier"
            }
        val semantics = modifier.recordField("state")
        return semantics.recordField("\$state") as LazyListState
    }
}

/** 反射只用于锁定版本的 Compose 测试观察点；字段漂移必须失败，不能跳过断言。 */
internal fun Any.recordField(name: String): Any = javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(this)
