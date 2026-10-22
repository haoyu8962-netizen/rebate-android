package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.id
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.page
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.rows
import com.couli.app.wallet.visual.WithdrawRecordsVisualFixtures.ok
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WithdrawalText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r2 S2：列表身份完全不变，仅上方收起行状态文字改变高度，也必须保留记录内偏移。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsV4AnchorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }
    private val scroll by lazy { WithdrawRecordsV2ScrollProbe(rule) }

    @After fun close() = host.close()

    @Test fun sameIdsRefreshCollapsedPayingToFailedKeepsNonzeroRecordOffset() = refreshCollapsedRow(grows = true)

    @Test fun sameIdsRefreshCollapsedContentShortensKeepsNonzeroRecordOffset() = refreshCollapsedRow(grows = false)

    private fun refreshCollapsedRow(grows: Boolean) {
        val shortRows = rows(source = "paying")
        val stateFields = setOf("status", "reason_code", "fail_action")
        val failure = WithdrawRecordsVisualFixtures.detail("failed").filterKeys { it in stateFields }
        val longRows = shortRows.mapIndexed { index, row -> if (index == 2) JsonObject(row + failure) else row }
        // 只换一条的状态相关字段；时间、月份、ID、顺序、金额、账号和其余 19 条完全不变。
        assertEquals(
            shortRows.map { it.filterKeys { key -> key !in stateFields } },
            longRows.map {
                it.filterKeys { key ->
                    key !in
                        stateFields
                }
            },
        )
        assertEquals(shortRows.filterIndexed { index, _ -> index != 2 }, longRows.filterIndexed { index, _ -> index != 2 })
        assertEquals(
            setOf("2026-10"),
            shortRows
                .map {
                    it
                        .getValue("created_at")
                        .jsonPrimitive.content
                        .take(7)
                }.toSet(),
        )
        val initial = if (grows) shortRows else longRows
        val refreshed = if (grows) longRows else shortRows
        host.api.answer = ok(page(initial))
        host.launch()
        assertEquals("前置条件：320dp 窄屏真实换行", 320f, scroll.viewport.width / scroll.density, 1f)
        val controller = host.controller
        assertCollapsedSnapshot(initial)
        val before = scroll.browse(id(10)) // 现有探针强制当前 ID=V2_10、偏移=-11dp，不能以裁切后的 0 冒充。
        val old = upperRowMetrics()

        host.refresh(ok(page(refreshed)))

        host.assertCalls("listWithdrawals", "listWithdrawals")
        assertSame("刷新不能重建控制器掩盖丢锚", controller, host.controller)
        assertCollapsedSnapshot(refreshed)
        val updated = upperRowMetrics()
        val direction = if (grows) 1 else -1
        println("COLLAPSED_REFRESH grows=$grows upper=${id(2)} old=$old updated=$updated anchor=$before")
        assertTrue("前置条件：副文案换行数确实按方向变化", direction * (updated.lines - old.lines) > 0)
        assertTrue("前置条件：收起行实际高度确实按方向变化", direction * (updated.heightDp - old.heightDp) > 1f)
        scroll.assertRestored(before)
    }

    private fun assertCollapsedSnapshot(expected: List<JsonObject>) {
        val state = host.controller.state.value
        assertEquals("真实列表已接受本次完整返回，含原顺序及新的状态字段", expected.map { checkNotNull(WalletJson.withdrawal(it)) }, state.items)
        assertEquals("仍是原来 20 条 ID，顺序不变", (0 until 20).map(::id), state.items.map { it.withdrawalId })
        assertTrue("全程收起，没有展开变化触发恢复", state.expanded.isEmpty())
        assertTrue("全程没有详情变化触发恢复", state.details.isEmpty())
    }

    private fun upperRowMetrics(): CollapsedMetrics {
        val item =
            host.controller.state.value.items
                .single { it.withdrawalId == id(2) }
        val text = WithdrawalText.render(WithdrawalText.of(item, null, host.texts), host.texts).single()
        val bounds = rule.record(id(2)).fetchSemanticsNode().recordUnclippedBounds()
        assertTrue("前置条件：变化记录仍在当前可见记录上方、屏外", bounds.bottom < scroll.viewport.top)
        return CollapsedMetrics(bounds.height / scroll.density, rule.recordText(text, id(2)).textLayout().lineCount)
    }

    private data class CollapsedMetrics(
        val heightDp: Float,
        val lines: Int,
    )
}
