package com.couli.app.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardMapper

/**
 * ⑥只有独立展示 fixture：锁定 ParseResult / ProductCard 均无 material 字段。
 * 不伪造接口或分类；金额只格式化成素材行，绝不当作商品价/返利。真实剪贴板接线另见交回缺口。
 */
internal object ClipboardMaterialVisualFixture {
    const val TAG = "visual_material_card"
    const val ACTION = "让 AI 帮我看看"
    val line: String get() = "素材参考价 ${CouliFormat.money(3990L)}（需 88VIP，仅供参考，以下单页为准）"

    fun data(): ProductCardData {
        val mapped = ProductCardMapper.map(ClipboardVisualFixtures.card(), SearchDraftTexts).data
        return mapped.copy(
            price = checkNotNull(mapped.price).copy(subLine = line),
            estNet = null,
            buyBase = ACTION,
        )
    }

    @Composable
    fun Sheet(
        onAskAi: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        CouliSheet(title = "识别到的商品", onDismissRequest = onClose, closeLabel = "关闭") {
            ProductCardList(data = data(), onBuy = onAskAi, modifier = Modifier.testTag(TAG))
        }
    }
}
