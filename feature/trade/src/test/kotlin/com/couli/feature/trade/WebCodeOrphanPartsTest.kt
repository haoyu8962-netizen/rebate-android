package com.couli.feature.trade

import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-B 测试：冷启动 / 晚到回跳用到的几个小部件（设计稿 §6.4）。
 * - [WebCodeInbox.takeUnclaimed]：中转交付后下一轮主线程循环取「仍没人认领」的回跳；
 * - [ColdAuthResume]：恢复槽只放一张票，link_id 或 product_key 对上才认领、只认领一次，到期取出一次；登记 / 认领 / 到期 / 作废时通知订阅者（D10-3690）；
 * - [BindingChanges]：通知全部订阅者，退订后不再通知；
 * - [webCodeAccountTag]：账号标签是用户 id 的 sha256（不是原文）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebCodeOrphanPartsTest {
    private var now = 1_700_000_000_000L

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- 收件箱

    @Test
    fun u01_anUnclaimedCallbackIsTakenOnceThenTheSameStateIsDropped() {
        val inbox = WebCodeInbox(clock = { now })
        val callback = WebCodeCallback.Code("c0de_1", "st_1")
        inbox.deliver(callback)
        assertEquals("没人等：下一轮取得到这条", callback, inbox.takeUnclaimed(sha("st_1")))
        assertNull("只取一次", inbox.takeUnclaimed(sha("st_1")))
        inbox.deliver(WebCodeCallback.Code("c0de_1", "st_1"))
        assertNull("取走之后同一 state 再来的回跳丢弃", inbox.takeUnclaimed(sha("st_1")))
    }

    @Test
    fun u02_aCallbackClaimedByAWaiterIsNotTaken() =
        runTest {
            val inbox = WebCodeInbox(clock = { now })
            val waiting = backgroundScope.async { inbox.await(sha("st_1")) }
            runCurrent()
            inbox.deliver(WebCodeCallback.Code("c0de_1", "st_1"))
            runCurrent()
            assertTrue("等待者拿到了", waiting.isCompleted)
            assertNull("已被认领：不交给 orphan", inbox.takeUnclaimed(sha("st_1")))
        }

    @Test
    fun u03_takeUnclaimedOnlyMatchesTheSameHashAndRespectsTheSlotLifetime() {
        val inbox = WebCodeInbox(clock = { now })
        val callback = WebCodeCallback.Incomplete("st_2")
        inbox.deliver(callback)
        assertNull("哈希不同不取", inbox.takeUnclaimed(sha("st_other")))
        assertEquals("哈希相同取得到（error=1 也交给 orphan）", callback, inbox.takeUnclaimed(sha("st_2")))
        inbox.deliver(WebCodeCallback.Code("c0de_3", "st_3"))
        now += WebCodeInbox.SLOT_TTL_MILLIS + 1
        assertNull("槽位超过 10 分钟作废", inbox.takeUnclaimed(sha("st_3")))
    }

    @Test
    fun u04_aCancelledWaiterLeavesTheCallbackForTheOrphanPath() =
        runTest {
            val inbox = WebCodeInbox(clock = { now })
            val waiting = backgroundScope.async { inbox.await(sha("st_1")) }
            runCurrent()
            waiting.cancel()
            runCurrent()
            val callback = WebCodeCallback.Code("c0de_1", "st_1")
            inbox.deliver(callback)
            assertEquals("热等待已取消（晚到回跳）：交给 orphan", callback, inbox.takeUnclaimed(sha("st_1")))
        }

    @Test
    fun u05_clearDropsTheUnclaimedCallback() {
        val inbox = WebCodeInbox(clock = { now })
        inbox.deliver(WebCodeCallback.Code("c0de_1", "st_1"))
        inbox.clear()
        assertNull("退出登录 / 换账号清空后取不到", inbox.takeUnclaimed(sha("st_1")))
    }

    // ---------------------------------------------------------------- 恢复槽

    private fun ticket(deadline: Long = now + 600_000L) = ColdAuthResume.Ticket("link-old", "tb:c1", "taobao", deadline)

    @Test
    fun r01_offeredTicketIsVisibleUntilItsDeadline() {
        val resume = ColdAuthResume(clock = { now })
        resume.offer(ticket())
        assertEquals("登记后看得到", ticket(), resume.peek())
        now += 600_001L
        assertNull("过了 deadline 看不到", resume.peek())
    }

    @Test
    fun r02_claimMatchesByLinkIdOrProductKeyAndOnlyOnce() {
        val resume = ColdAuthResume(clock = { now })
        resume.offer(ticket())
        assertNull("link_id、product_key 都不对：不认领", resume.claim("link-x", "tb:x"))
        assertEquals("product_key 对上即可（卡片 link_id 已换新）", ticket(), resume.claim("link-new", "tb:c1"))
        assertNull("同一张只能认领一次", resume.claim("link-old", "tb:c1"))

        resume.offer(ticket())
        assertEquals("link_id 对上即可（卡片没有 product_key）", ticket(), resume.claim("link-old", null))
        assertNull("认领后槽位空了", resume.peek())
    }

    @Test
    fun r03_anExpiredTicketCannotBeClaimedAndIsTakenOnceAsExpired() {
        val resume = ColdAuthResume(clock = { now })
        resume.offer(ticket())
        assertNull("没到期：takeExpired 不取", resume.takeExpired())
        now += 600_001L
        assertNull("到期后不能认领", resume.claim("link-old", "tb:c1"))
        assertEquals("到期没人认领：取出一次（调用方只提示再点一次）", ticket(deadline = now - 1), resume.takeExpired())
        assertNull("只取一次", resume.takeExpired())
    }

    @Test
    fun r04_newerTicketReplacesTheOlderOneAndClearDropsIt() {
        val resume = ColdAuthResume(clock = { now })
        resume.offer(ticket())
        val newer = ColdAuthResume.Ticket("link-2", "tb:c2", "taobao", now + 600_000L)
        resume.offer(newer)
        assertEquals("只放一张：新的覆盖旧的", newer, resume.peek())
        assertNull("旧票认领不到", resume.claim("link-old", "tb:c1"))
        resume.clear()
        assertNull("clear 后作废", resume.peek())
        assertNull(resume.claim("link-2", "tb:c2"))
    }

    @Test
    fun r05_subscribersHearOfferClaimExpiryAndClearUntilUnsubscribed() {
        val resume = ColdAuthResume(clock = { now })
        val seen = mutableListOf<ColdAuthResume.Ticket?>()
        var other = 0
        val unsubscribe = resume.subscribe { seen += resume.peek() }
        resume.subscribe { other += 1 }
        resume.offer(ticket())
        assertEquals("登记：通知一次，通知时已看得到票", listOf<ColdAuthResume.Ticket?>(ticket()), seen)
        assertNull("认领不到不通知", resume.claim("link-x", "tb:x"))
        assertEquals(1, seen.size)
        assertEquals(ticket(), resume.claim("link-old", null))
        assertEquals("认领：再通知一次，通知时槽位已空", listOf(ticket(), null), seen)

        resume.offer(ticket())
        now += 600_001L
        assertEquals(ticket(deadline = now - 1), resume.takeExpired())
        assertEquals("登记、到期取出各通知一次", 4, seen.size)
        assertNull("到期取出后槽位空", seen.last())
        assertNull("已取空：takeExpired 不通知", resume.takeExpired())
        assertEquals(4, seen.size)

        resume.offer(ColdAuthResume.Ticket("link-2", "tb:c2", "taobao", now + 600_000L))
        resume.clear()
        assertEquals("作废一张票也通知", 6, seen.size)
        resume.clear()
        assertEquals("槽位本来就空：clear 不通知", 6, seen.size)

        unsubscribe()
        resume.offer(ticket(deadline = now + 600_000L))
        assertEquals("取消订阅后不再收到", 6, seen.size)
        assertEquals("别的订阅者照常收到（登记 4 次、认领 1 次、到期 1 次、作废 1 次）", 7, other)
    }

    // ---------------------------------------------------------------- 绑定变化信号

    @Test
    fun b01_bindingChangesNotifiesEverySubscriberUntilUnsubscribed() {
        val changes = BindingChanges()
        var a = 0
        var b = 0
        val unsubscribeA = changes.subscribe { a += 1 }
        changes.subscribe { b += 1 }
        changes.notifyChanged()
        assertEquals("订阅者 A 收到一次", 1, a)
        assertEquals("订阅者 B 收到一次", 1, b)
        unsubscribeA()
        changes.notifyChanged()
        assertEquals("退订后不再通知", 1, a)
        assertEquals(2, b)
    }

    // ---------------------------------------------------------------- 账号标签

    @Test
    fun t01_accountTagIsTheSha256OfTheUserIdNotTheRawId() {
        val tag = webCodeAccountTag("user-42")
        assertEquals("sha256（UTF-8，小写十六进制）", sha("user-42"), tag)
        assertFalse("不含用户 id 原文", tag.contains("user-42"))
        assertNotEquals("不同账号标签不同", webCodeAccountTag("user-42"), webCodeAccountTag("user-43"))
    }
}
