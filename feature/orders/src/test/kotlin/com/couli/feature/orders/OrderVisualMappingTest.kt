package com.couli.feature.orders

import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.platformIconAssetPath
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderJson
import com.couli.generated.DisplayStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** O12/O13/O17：只测视图映射，不在测试辅助重写订单规则。 */
class OrderVisualMappingTest {
    @Test fun allElevenKnownStatusesAndUnknownUseSpecifiedIcon() {
        val expected =
            mapOf(
                DisplayStatus.DEPOSIT_PAID to CouliIcons.clock,
                DisplayStatus.PAID to CouliIcons.clock,
                DisplayStatus.WAITING to CouliIcons.clock,
                DisplayStatus.CREDITING to CouliIcons.clock,
                DisplayStatus.RIGHTS_PENDING to CouliIcons.clock,
                DisplayStatus.REVIEWING to CouliIcons.clock,
                DisplayStatus.CREDITED to CouliIcons.check,
                DisplayStatus.CREDITED_PART_CLAWED to CouliIcons.check,
                DisplayStatus.CLAWED_BACK to CouliIcons.alert,
                DisplayStatus.INVALID to CouliIcons.info,
                DisplayStatus.NO_REBATE to CouliIcons.info,
                DisplayStatus.UNKNOWN to CouliIcons.info,
            )
        assertEquals(DisplayStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, icon) -> assertEquals(status.raw, icon, OrderUi.statusIcon(status)) }
    }

    @Test fun listKeepsPaidOnLeftAndRebateFirstWhenStacked() {
        val row =
            OrderJson.lenientSummary(
                Json.parseToJsonElement("""{"display_status":"PAID","pay_amount_fen":4560,"est_rebate_fen":123}""").jsonObject,
            )
        val (cells, order) = OrderUi.listCells(OrderDraftTexts, OrderAmountCells.of(row))
        assertEquals(listOf("实付金额", "预估返"), cells.map { it.label })
        assertEquals(listOf(1, 0), order)
        val deposit = row.copy(displayStatusRaw = "DEPOSIT_PAID")
        val (one, noOrder) = OrderUi.listCells(OrderDraftTexts, OrderAmountCells.of(deposit))
        assertEquals(listOf("已付定金"), one.map { it.label })
        assertNull(noOrder)
    }

    @Test fun platformCodeSelectsOfficialAssetAndUnknownNeverGuesses() {
        assertEquals("platform/taobao.png", platformIconAssetPath("taobao"))
        assertEquals("platform/jd.png", platformIconAssetPath("jd"))
        assertEquals("platform/pinduoduo.png", platformIconAssetPath("pdd"))
        listOf("", "TAOBAO", "unknown", "淘宝").forEach { assertNull(platformIconAssetPath(it)) }
    }
}
