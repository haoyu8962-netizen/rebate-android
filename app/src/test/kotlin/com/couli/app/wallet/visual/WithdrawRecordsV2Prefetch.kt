@file:Suppress("DEPRECATION") // Compose 1.11.4 的预取接口；只在测试接管 Robolectric 的空调度器。

package com.couli.app.wallet.visual

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.PrefetchRequest
import androidx.compose.foundation.lazy.layout.PrefetchRequestScope
import androidx.compose.foundation.lazy.layout.PrefetchScheduler
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertTrue

/**
 * RobolectricImpl.schedulePrefetch 是空函数。仅替换真实 Lazy 项的调度器，执行滚动策略实际排入的请求；
 * 不直接调用 schedulePrecomposition / loadMore，不模拟页脚或列表。预组合仍由 Compose 自己完成。
 */
@OptIn(ExperimentalFoundationApi::class)
internal class WithdrawRecordsV2Prefetch(
    private val rule: ComposeContentTestRule,
    probe: WithdrawRecordsV2ScrollProbe,
) : PrefetchScheduler,
    AutoCloseable {
    private val queue = ArrayDeque<PrefetchRequest>()
    val completedIndices = mutableListOf<Int>()
    private val state = probe.lazyState()
    private val prefetch = state.javaClass.getMethod("getPrefetchState\$foundation").invoke(state)
    private val provider = prefetch.javaClass.getMethod("getPrefetchHandleProvider\$foundation").invoke(prefetch)
    private val executor = provider.javaClass.getDeclaredField("executor").also { it.isAccessible = true }
    private val original = executor.get(provider)

    init {
        rule.runOnIdle { executor.set(provider, this) }
    }

    override fun schedulePrefetch(prefetchRequest: PrefetchRequest) {
        queue.addLast(prefetchRequest)
    }

    fun drain() {
        rule.runOnIdle {
            var attempts = 0
            val budget =
                object : PrefetchRequestScope {
                    override fun availableTimeNanos() = Long.MAX_VALUE
                }
            while (queue.isNotEmpty()) {
                check(attempts++ < 100) { "预取必须在有限帧完成" }
                val request = queue.removeFirst()
                if (with(request) { budget.execute() }) {
                    queue.addLast(request)
                } else {
                    val composed =
                        request.javaClass
                            .getDeclaredMethod(
                                "isComposed",
                            ).also { it.isAccessible = true }
                            .invoke(request) as Boolean
                    if (composed) completedIndices += request.recordField("index") as Int
                }
            }
        }
        rule.waitForIdle()
    }

    fun assertFooterWasPrefetchedOffscreen() {
        assertTrue("必须实际完成页脚预组合，不能因 Robolectric 默认禁用预取而假绿：$completedIndices", 1 in completedIndices)
        assertTrue("预取页脚仍不在可见 Lazy 项中", state.layoutInfo.visibleItemsInfo.none { it.index == 1 })
        println("PREFETCH completed=$completedIndices visible=${state.layoutInfo.visibleItemsInfo.map { it.index }}")
    }

    override fun close() {
        rule.runOnIdle { executor.set(provider, original) }
    }
}
