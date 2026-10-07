package com.couli.feature.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-04 共性第 62 条（Android 第 1 轮评审 R1-05）：会导航的桥调用，回复脚本交给 evaluateJavascript 之后——
 * - 渲染线程繁忙、回调一直不来：等待超时按「没有执行」结束（settled(false)），不当作执行成功；之后迟到的回调不再算数；
 * - 回调来了、接收函数确实执行完：settled(true)；接收函数不在：settled(false)。
 */
class ReplyConfirmationTest {
    private val settled = mutableListOf<Boolean>()
    private var callback: ((String?) -> Unit)? = null
    private var timeout: (() -> Unit)? = null
    private var scheduledDelay = 0L

    private fun deliver() =
        ReplyConfirmation.deliver(
            script = BridgeTransport.receiveScript("""{"id":"1","code":0}"""),
            evaluate = { _, done -> callback = done },
            schedule = { delay, task ->
                scheduledDelay = delay
                timeout = task
            },
            settled = { settled += it },
        )

    @Test
    fun aReplyThatHasNotRunWhenTheWaitTimesOutIsNotTreatedAsExecuted() {
        deliver()
        assertTrue("还在等：不结束", settled.isEmpty())
        assertEquals(BridgeTransport.CONFIRM_TIMEOUT_MS, scheduledDelay)
        checkNotNull(timeout).invoke()
        assertEquals("超时 = 没有执行，放弃导航", listOf(false), settled)
        checkNotNull(callback).invoke(ReplyConfirmation.EXECUTED)
        assertEquals("迟到的执行回调不再算数", listOf(false), settled)
    }

    @Test
    fun theReplyCountsAsExecutedOnlyWhenTheReceiverRan() {
        deliver()
        checkNotNull(callback).invoke(ReplyConfirmation.EXECUTED)
        checkNotNull(timeout).invoke()
        assertEquals(listOf(true), settled)

        settled.clear()
        deliver()
        checkNotNull(callback).invoke("false")
        assertEquals("接收函数不在：没有执行", listOf(false), settled)
    }

    @Test
    fun theReceiveScriptReportsWhetherTheReceiverRan() {
        val script = BridgeTransport.receiveScript("a\"b")
        assertTrue(script.contains("return true"))
        assertTrue("回复按 JSON 字符串字面量嵌入", script.contains("\"a\\\"b\""))
    }
}
