package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.id
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.page
import com.couli.app.wallet.visual.WithdrawRecordsV2Fixtures.rows
import com.couli.app.wallet.visual.WithdrawRecordsVisualFixtures.ok
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsV2PagingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }
    private val scroll by lazy { WithdrawRecordsV2ScrollProbe(rule) }

    @After fun close() = host.close()

    @Test fun smallScrollPrefetchesFooterButMustNotReadNextPage() {
        launchPages()
        WithdrawRecordsV2Prefetch(rule, scroll).use { prefetch ->
            scroll.scrollByDp(24f)
            scroll.assertFarFromEnd(id(19))
            prefetch.drain()
            prefetch.assertFooterWasPrefetchedOffscreen()
            println("PAGING small24dp queries=${queries()}")
            host.assertCalls("listWithdrawals")
            assertEquals("只有用户接近月段末尾才分页", listOf("limit=20"), queries())
        }
    }

    @Test fun threePagesReadExactlyOnceAtEachEndAndNeverCascadeDuringSmallScrolls() {
        launchPages()
        val premature = mutableListOf<String>()
        WithdrawRecordsV2Prefetch(rule, scroll).use { prefetch ->
            repeat(2) { pageIndex ->
                val count = (pageIndex + 1) * 20
                scroll.scrollByDp(24f)
                prefetch.drain()
                scroll.assertFarFromEnd(id(count - 1))
                prefetch.assertFooterWasPrefetchedOffscreen()
                if (queries().size != pageIndex + 1) premature += "第${pageIndex + 1}页小幅滚动已读：${queries()}"
                // 不在首个错误即退出：继续真实滚到月段末尾并完成响应，覆盖 20→40→60 的连续路径。
                scroll.approachEnd(id(count - 1))
                prefetch.drain()
                assertEquals("末尾应恰好发送一次当前游标", expectedQueries().take(pageIndex + 2), queries())
                rule.runOnIdle {
                    host.api.pageHolds
                        .getValue(expectedQueries()[pageIndex + 1])
                        .complete(Unit)
                }
                rule.waitForIdle()
                assertEquals(count + 20, host.controller.state.value.items.size)
                println("PAGING nearEnd loaded=${host.controller.state.value.items.size} queries=${queries()}")
                scroll.scrollByDp(8f)
                prefetch.drain()
                if (queries().size != pageIndex + 2) premature += "第${pageIndex + 2}页追加后远离末尾仍级联：${queries()}"
            }
            scroll.approachEnd(id(59))
            prefetch.drain()
            assertEquals("无游标时不得多读、不得重复游标", expectedQueries(), queries())
            assertEquals(
                (0 until 60).map(::id),
                host.controller.state.value.items
                    .map { it.withdrawalId },
            )
            assertTrue("每页的远端预取都不得引发请求：$premature", premature.isEmpty())
            host.assertCalls("listWithdrawals", "listWithdrawals", "listWithdrawals")
        }
    }

    private fun launchPages() {
        host.api.answer = ok(page(rows(source = "pending"), "v2-page-2"))
        host.api.pageAnswers["cursor=v2-page-2&limit=20"] = ok(page(rows(20, source = "pending"), "v2-page-3"))
        host.api.pageAnswers["cursor=v2-page-3&limit=20"] = ok(page(rows(40, source = "pending")))
        expectedQueries().drop(1).forEach { host.api.pageHolds[it] = kotlinx.coroutines.CompletableDeferred() }
        host.launch()
        assertEquals("一个月段和一个页脚；不能用20个Lazy项绕过预取缺口", 2, scroll.lazyState().layoutInfo.totalItemsCount)
        host.assertCalls("listWithdrawals")
    }

    private fun queries() =
        host.api.calls
            .filter { it.operationId == "listWithdrawals" }
            .map { it.query }

    private fun expectedQueries() = listOf("limit=20", "cursor=v2-page-2&limit=20", "cursor=v2-page-3&limit=20")
}
