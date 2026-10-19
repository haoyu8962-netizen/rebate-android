package com.couli.core.designsystem

import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.test.SemanticsNodeInteraction

/** tag 可挂在外层：同时查看直接子布局，避免把合法的 M3 Button 外壳误判。 */
internal fun SemanticsNodeInteraction.ds02HasIndication(): Boolean {
    val layout = fetchSemanticsNode().layoutInfo
    // Compose 1.11 的 LayoutInfo 不公开 children；底层 LayoutNode 的公开 JVM 方法可供测试检查。
    val children =
        (layout.javaClass.getMethod("getChildrenInfo").invoke(layout) as List<*>)
            .filterIsInstance<androidx.compose.ui.layout.LayoutInfo>()
    return (listOf(layout) + children).any { candidate ->
        candidate.getModifierInfo().any { info ->
            val element = info.modifier
            val properties = (element as? InspectableValue)?.inspectableElements.orEmpty()
            properties.any { it.name in setOf("indication", "indicationNodeFactory") && it.value != null } ||
                element.javaClass.name.contains("IndicationModifier")
        }
    }
}
