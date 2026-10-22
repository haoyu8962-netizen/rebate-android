package com.couli.feature.orders

import com.couli.core.designsystem.AmountCell
import com.couli.core.orders.OrderActionButton
import com.couli.core.orders.OrderActionsView
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 新接口尚未实现也必须能编译：反射只查约定签名；缺接口明确报红，不提供测试替身。 */
class OrderDetailVisualRulesTest {
    @Test fun screenIsSeparatedFromViewModelWithoutChangingPublicEntry() {
        val root =
            generateSequence(java.io.File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .first { java.io.File(it, "contract.lock").isFile }
        val model = java.io.File(root, "feature/orders/src/main/kotlin/com/couli/feature/orders/OrderDetailViewModel.kt").readText()
        org.junit.Assert.assertFalse("O20：屏幕应拆出 ViewModel 文件", model.contains("fun OrderDetailScreen("))
        val sources =
            java.io
                .File(root, "feature/orders/src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .toList()
        assertEquals("保持唯一公开页面入口", 1, sources.count { it.readText().contains("fun OrderDetailScreen(") })
    }

    @Test fun splitZeroSingleTwoAndMixedKeepsOriginalButtonsAndOrder() {
        val contact = OrderActionButton("contact_cs", "Help", true)
        val appeal = OrderActionButton("appeal", "Appeal", false)
        val withdraw = OrderActionButton("withdraw", "Withdraw", false)
        val ledger = OrderActionButton("ledger", "Ledger", false)
        val cases =
            listOf(
                emptyList(),
                listOf(appeal),
                listOf(withdraw),
                listOf(contact, ledger),
                listOf(contact, withdraw),
                listOf(contact, appeal),
            )
        val method = OrderUi.javaClass.methods.singleOrNull { it.name == "splitActions" && it.parameterCount == 1 }
        assertNotNull("O27 接口缺失：OrderUi.splitActions(OrderActionsView): Pair<List<OrderActionButton>, List<OrderActionButton>>", method)
        cases.forEach { buttons ->
            val result = method!!.invoke(OrderUi, OrderActionsView(buttons, true)) as Pair<*, *>
            assertEquals(buttons.filter { it.actionRaw != "withdraw" }, result.first)
            assertEquals(buttons.filter { it.actionRaw == "withdraw" }, result.second)
        }
    }

    @Test fun detailCellOrderReversesOnlyTwoColumnsAndKeepsListUnchanged() {
        val method = OrderUi.javaClass.methods.singleOrNull { it.name == "detailCells" && it.parameterCount == 2 }
        assertNotNull("O22 接口缺失：OrderUi.detailCells(OrderTexts, AmountCellsView)", method)
        listOf(
            "PAID",
            "WAITING",
            "CREDITED",
            "CREDITED_PART_CLAWED",
            "CLAWED_BACK",
            "INVALID",
            "DEPOSIT_PAID",
            "NO_REBATE",
            "FUTURE",
        ).forEach { status ->
            val summary =
                OrderJson.lenientSummary(
                    Json
                        .parseToJsonElement(
                            """{"display_status":"$status","pay_amount_fen":12345,"est_rebate_fen":678,""" +
                                """"actual_fen":345,"clawback_fen":111}""",
                        ).jsonObject,
                )
            val source = OrderAmountCells.of(summary)
            val list = OrderUi.listCells(OrderDraftTexts, source)
            val detail = method!!.invoke(OrderUi, OrderDraftTexts, source) as Pair<*, *>
            val expected = if (list.first.size == 2) list.first.reversed() else list.first
            assertEquals(status, expected, detail.first)
            val cells = (detail.first as List<*>).filterIsInstance<AmountCell>()
            val stack = (detail.second as? List<*>)?.filterIsInstance<Int>() ?: cells.indices.toList()
            assertEquals("纵排仍返利在上：$status", cells, stack.map(cells::get))
            assertEquals("详情不能改列表默认顺序", OrderUi.cell(OrderDraftTexts, source.left), list.first.first())
        }
    }
}
