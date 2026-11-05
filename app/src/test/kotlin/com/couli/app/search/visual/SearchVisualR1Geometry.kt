package com.couli.app.search.visual

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertTrue

/** 不读已经裁切的 boundsInRoot；位置与尺寸都来自实际布局。 */
internal fun LayoutCoordinates.searchR1Rect(): Rect {
    val origin = positionInRoot()
    return Rect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
}

internal fun ComposeContentTestRule.searchR1ScrollRow(tag: String): SemanticsNodeInteraction =
    onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(hasTestTag(tag)),
        useUnmergedTree = true,
    )

internal fun SemanticsNode.searchR1ScrollParent(): SemanticsNode =
    checkNotNull(
        generateSequence(parent) { it.parent }.firstOrNull {
            it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
        },
    ) { "控件必须处在横向滚动容器内" }

internal fun SemanticsNode.assertSearchR1Touch48() {
    val d = layoutInfo.density.density
    assertTrue("语义布局宽至少48dp", size.width / d >= 47.5f)
    assertTrue("语义布局高至少48dp", size.height / d >= 47.5f)
    assertTrue("实际命中宽至少48dp", touchBoundsInRoot.width / d >= 47.5f)
    assertTrue("实际命中高至少48dp", touchBoundsInRoot.height / d >= 47.5f)
}
