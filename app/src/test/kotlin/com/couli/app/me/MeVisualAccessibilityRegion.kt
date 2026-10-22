package com.couli.app.me

import androidx.collection.IntObjectMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.IntRect

internal data class MeVisualAccessibilityRegion(
    val node: SemanticsNode,
    val adjustedBounds: IntRect,
    val visibleToUser: Boolean,
    val platformLiveRegion: Int,
)

/**
 * 读取 Compose 1.11.4 无障碍事件实际使用的 currentSemanticsNodes；不能用测试的完整树或自行按 bounds 猜可见性。
 * 该 getter 调用 getAllUncoveredSemanticsNodesToIntObjectMap，包含滚动裁切、遮挡和合并规则。
 * 反射只读实际代理；升级后接口若变了立即失败，不回退到完整树。另经真实节点提供器核对 isVisibleToUser / liveRegion。
 */
internal fun ComposeContentTestRule.meAccessibilityRegions(): List<MeVisualAccessibilityRegion> {
    val views = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().map { (it.root as ViewRootForTest).view }
    return runOnUiThread {
        buildList {
            for (view in views) {
                val field = view.javaClass.getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }
                val delegate = checkNotNull(field.get(view))
                val getter = delegate.javaClass.getDeclaredMethod("getCurrentSemanticsNodes").apply { isAccessible = true }
                val nodes = getter.invoke(delegate) as IntObjectMap<*>
                val provider = checkNotNull(view.accessibilityNodeProvider)
                nodes.forEach { id, value ->
                    val adjusted = checkNotNull(value)
                    val node = adjusted.javaClass.getMethod("getSemanticsNode").invoke(adjusted) as SemanticsNode
                    if (node.config.contains(SemanticsProperties.LiveRegion)) {
                        val bounds = adjusted.javaClass.getMethod("getAdjustedBounds").invoke(adjusted) as IntRect
                        val info = checkNotNull(provider.createAccessibilityNodeInfo(id))
                        add(MeVisualAccessibilityRegion(node, bounds, info.isVisibleToUser, info.liveRegion))
                    }
                }
            }
        }
    }
}
