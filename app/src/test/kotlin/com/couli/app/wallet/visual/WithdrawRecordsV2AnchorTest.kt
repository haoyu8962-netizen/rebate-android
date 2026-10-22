package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.id
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.inSeptember
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.page
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.rows
import com.couli.app.wallet.visual.WithdrawRecordsVisualFixtures.ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w840dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsV2AnchorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }
    private val scroll by lazy { WithdrawRecordsV2ScrollProbe(rule) }

    @After fun close() = host.close()

    @Test fun sameMonthRefreshPrependRestoresRecordAndOffset() {
        val initial = rows()
        launch(initial)
        val before = scroll.browse(id(10))
        host.refresh(ok(page(rows(-2, 2) + initial.take(18))))
        host.assertCalls("listWithdrawals", "listWithdrawals")
        scroll.assertRestored(before)
    }

    @Test fun monthFirstRecordRemovedRestoresRecordAndOffset() {
        val initial = rows()
        launch(initial)
        val before = scroll.browse(id(10))
        host.refresh(ok(page(initial.drop(1) + rows(20, 1))))
        host.assertCalls("listWithdrawals", "listWithdrawals")
        scroll.assertRestored(before)
    }

    @Test fun monthFirstRecordReplacedWithoutHeightChangeKeepsAnchor() {
        val initial = rows()
        launch(initial)
        val before = scroll.browse(id(10))
        host.refresh(ok(page(listOf(JsonObject(initial.first() + ("withdrawal_id" to JsonPrimitive(id(-1))))) + initial.drop(1))))
        host.assertCalls("listWithdrawals", "listWithdrawals")
        scroll.assertRestored(before)
    }

    @Test fun monthBoundaryMovesAcrossCurrentSectionAndRestoresRecord() {
        val initial = rows(0, 4) + rows(4, 16).map(::inSeptember)
        launch(initial)
        val before = scroll.browse(id(12))
        // 同一个 ID 从九月段首移到十月段，十月又前插两张；当前九月记录仍在新第一页。
        val changed = rows(-2, 2) + rows(0, 5) + rows(5, 13).map(::inSeptember)
        host.refresh(ok(page(changed)))
        host.assertCalls("listWithdrawals", "listWithdrawals")
        scroll.assertRestored(before)
    }

    @Test fun lateDetailAboveViewportGrowsWithoutMovingCurrentRecord() {
        val initial = rows(source = "paid")
        host.api.detailHold = CompletableDeferred()
        host.api.detailAnswers[id(2)] =
            ok(JsonObject(WithdrawRecordsVisualFixtures.detail("stress") + ("withdrawal_id" to JsonPrimitive(id(2)))))
        launch(initial)
        host.toggle(id(2))
        val oldHeight =
            rule
                .record(id(2))
                .fetchSemanticsNode()
                .recordUnclippedBounds()
                .height
        val before = scroll.browse(id(10))
        rule.runOnIdle { checkNotNull(host.api.detailHold).complete(Unit) }
        rule.waitForIdle()
        assertTrue(
            "前置条件：上方迟到详情确实增加高度",
            rule
                .record(id(2))
                .fetchSemanticsNode()
                .recordUnclippedBounds()
                .height > oldHeight,
        )
        host.assertCalls("listWithdrawals", "getWithdrawal")
        scroll.assertRestored(before)
    }

    @Test fun narrowToWideRestoresRecordAndOffset() = resize(320, 840)

    @Test fun wideToNarrowRestoresRecordAndOffset() = resize(840, 320)

    @Test fun unchangedRefreshControlKeepsNonzeroRecordOffset() {
        val initial = rows()
        launch(initial)
        val before = scroll.browse(id(10))
        host.refresh(ok(page(initial)))
        host.assertCalls("listWithdrawals", "listWithdrawals")
        scroll.assertRestored(before)
    }

    private fun resize(
        from: Int,
        to: Int,
    ) {
        launch(rows(), from)
        val controller = host.controller
        val before = scroll.browse(id(10))
        host.resizeWindow(to)
        assertWindow(to)
        assertSame("改变窗口不能重建控制器掩盖丢锚", controller, host.controller)
        host.assertCalls("listWithdrawals")
        scroll.assertRestored(before)
        host.resizeWindow(from)
        scroll.assertRestored(before)
    }

    private fun launch(
        items: List<JsonObject>,
        width: Int = 320,
    ) {
        host.api.answer = ok(page(items))
        host.launch()
        host.resizeWindow(width)
        assertWindow(width)
    }

    private fun assertWindow(width: Int) {
        val window = rule.onNodeWithTag("records_test_window").fetchSemanticsNode().boundsInRoot
        assertEquals("窗口约束确实改变", width.toFloat(), window.width / scroll.density, 1f)
        assertEquals("列表遵守既有宽屏600dp内容上限", minOf(width, 600).toFloat(), scroll.viewport.width / scroll.density, 1f)
    }
}
