package com.couli.feature.trade

import java.security.MessageDigest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** sha256（UTF-8 字节，小写十六进制）：测试自己算，不复用被测代码。 */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * FU-AND-TB-WEBCODE-A 测试：回跳收件箱（设计稿 §3.1、§5.5 第 1 条、§6.3）。
 * 两种先后顺序都能交付；只交给 sha256(state) 相同的等待；交付一次后清空，同一 state 再来的回跳丢弃；槽位最多 10 分钟；clear 清空。
 * 等待用 backgroundScope 起，没交付的在用例结束时自动取消。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebCodeInboxTest {
    private var now = 1_000_000L
    private val inbox = WebCodeInbox(clock = { now })

    private fun TestScope.waitFor(state: String): Deferred<WebCodeCallback> {
        val waiting = backgroundScope.async { inbox.await(sha256Hex(state)) }
        runCurrent()
        return waiting
    }

    private fun Deferred<WebCodeCallback>.code(): String {
        assertTrue("等待应已拿到回跳", isCompleted)
        val got = getCompleted()
        assertTrue("应是 Code：$got", got is WebCodeCallback.Code)
        return (got as WebCodeCallback.Code).code
    }

    @Test
    fun i01_waiterFirstThenCallbackIsDelivered() =
        runTest {
            val waiting = waitFor("S1")
            assertFalse("回跳到之前一直等", waiting.isCompleted)
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            runCurrent()
            assertEquals("C1", waiting.code())
        }

    @Test
    fun i02_callbackFirstThenWaiterIsDelivered() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            val waiting = waitFor("S1")
            assertEquals("回跳先到、等待后到也能交付", "C1", waiting.code())
        }

    @Test
    fun i03_onlyTheMatchingStateIsDelivered() =
        runTest {
            val waiting = waitFor("S1")
            inbox.deliver(WebCodeCallback.Code("FORGED", "OTHER"))
            runCurrent()
            assertFalse("state 不符的回跳不交给这次等待", waiting.isCompleted)
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            runCurrent()
            assertEquals("不符的回跳不打断等待，之后匹配的照常交付", "C1", waiting.code())
        }

    @Test
    fun i04_callbackIsDeliveredOnlyOnce() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            assertEquals("C1", waitFor("S1").code())
            val second = waitFor("S1")
            assertFalse("交付一次后清空：第二次等待拿不到同一条", second.isCompleted)
        }

    @Test
    fun i05_repeatedCallbackForAConsumedStateIsDropped() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            assertEquals("C1", waitFor("S1").code())
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            val again = waitFor("S1")
            assertFalse("同一 state 的第二次回跳丢弃", again.isCompleted)
        }

    @Test
    fun i06_incompleteIsDeliveredToo() =
        runTest {
            val waiting = waitFor("S1")
            inbox.deliver(WebCodeCallback.Incomplete("S1"))
            runCurrent()
            assertTrue("error=1 的回跳也交给等待", waiting.isCompleted)
            val got = waiting.getCompleted()
            assertTrue("应是 Incomplete：$got", got is WebCodeCallback.Incomplete)
        }

    @Test
    fun i07_slotExpiresAfterTenMinutes() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            now += WebCodeInbox.SLOT_TTL_MILLIS + 1
            assertFalse("超过 10 分钟的槽位作废", waitFor("S1").isCompleted)
        }

    @Test
    fun i08_slotWithinTenMinutesIsStillDelivered() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            now += WebCodeInbox.SLOT_TTL_MILLIS - 1
            assertEquals("10 分钟内仍可认领", "C1", waitFor("S1").code())
        }

    @Test
    fun i09_clearEmptiesTheSlot() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            inbox.clear()
            assertFalse("clear 后槽位为空", waitFor("S1").isCompleted)
        }

    @Test
    fun i10_waiterForAnotherStateDoesNotConsumeTheSlot() =
        runTest {
            inbox.deliver(WebCodeCallback.Code("C1", "S1"))
            val other = waitFor("S2")
            assertFalse(other.isCompleted)
            assertEquals("别的等待不吞掉这条回跳", "C1", waitFor("S1").code())
        }
}
