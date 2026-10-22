package com.couli.app.purchase

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import com.couli.app.product.ProductVisualFixtures
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.PendingTrackCards
import com.couli.core.orders.PendingTrackTags
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** S2-6：复现 AppSearch.PendingCards 的完整外层；不把裸卡当正式页面截图。 */
@Composable
internal fun PurchaseVisualPendingSlot(pending: PurchaseVisualPending) {
    Box(Modifier.fillMaxWidth().testTag(PENDING_SLOT)) {
        PendingTrackCards(
            pending.service,
            OrderDraftTexts,
            { pending.claims += 1 },
            modifier = Modifier.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        )
    }
}

internal fun ComposeContentTestRule.assertPurchasePendingSlot() {
    val app = File(ProductVisualFixtures.root, "app/src/main/kotlin/com/couli/app/search/AppSearch.kt").readText()
    val slot = app.substringAfter("private fun PendingCards(").substringBefore("/**")
    assertTrue(
        "正式插槽边距发生变化时须重新核对截图宿主",
        Regex("padding\\(horizontal\\s*=\\s*CouliSpacing.space4,\\s*vertical\\s*=\\s*CouliSpacing.space2\\)").containsMatchIn(slot),
    )
    val container = onNodeWithTag(PENDING_SLOT).fetchSemanticsNode()
    val card = onNodeWithTag(PendingTrackTags.CARD).fetchSemanticsNode().boundsInRoot
    val outer = container.boundsInRoot
    val density = container.layoutInfo.density.density
    // 仅容忍 1 物理像素取整；同时验宽度和四边位置。
    assertEquals("正式入口左右各16dp", outer.width - 32 * density, card.width, 1f)
    assertEquals("左16dp", outer.left + 16 * density, card.left, 1f)
    assertEquals("右16dp", outer.right - 16 * density, card.right, 1f)
    assertEquals("上8dp", outer.top + 8 * density, card.top, 1f)
    assertEquals("下8dp", outer.bottom - 8 * density, card.bottom, 1f)
}

private const val PENDING_SLOT = "purchase_pending_slot"
