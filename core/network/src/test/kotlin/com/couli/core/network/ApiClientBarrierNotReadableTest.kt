package com.couli.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** FU-AND-BARRIER-NOTREADABLE-ANON 用例 1–5 与 D10-1942 ③；规则为规划 03 §4.2 及 D10-1939 / D10-1940。 */
class ApiClientBarrierNotReadableTest {
    @Test
    fun boundAccountSearchInClosedBarrierRejectsUnreadableWithoutSending() {
        val rig = BarrierNotReadableFixture()
        assertEquals("先形成真实 bound，未发网络请求", BarrierNotReadableFixture.ACCOUNT, rig.api.activeUserId())

        val outcome = rig.inClosedWindow(BarrierNotReadableFixture.search())

        assertEquals("窗口内：0 请求、无 Authorization、本地 session_unreadable", unreadable(), outcome)
        assertEquals("整个传输层没有收到请求", 0, rig.sentCount)
    }

    @Test
    fun sameSearchAfterBarrierReopensCarriesTheStoredAccountsBearer() {
        val rig = BarrierNotReadableFixture()
        val call = BarrierNotReadableFixture.search()
        assertEquals(BarrierNotReadableFixture.ACCOUNT, rig.api.activeUserId())
        val blocked = rig.inClosedWindow(call)
        rig.settleEnvironment()

        val recovered = rig.execute(call)

        assertEquals(
            "先 0 请求 / 无头 / session_unreadable，再 1 请求 / 本人 Bearer / 无本地拒绝；两次结果一起检查",
            listOf(unreadable(), BarrierNotReadableOutcome.sent(BarrierNotReadableFixture.BEARER)),
            listOf(blocked, recovered),
        )
        assertEquals("只在恢复后的新调用发送一次", 1, rig.sentCount)
    }

    @Test
    fun confirmedGuestSearchStillSendsOnceWithoutAuthorization() {
        val stored = MemorySessionTokens()
        assertEquals("确实没有本机会话", SessionSnapshot(null, null, null, null, 0L), stored.snapshot())
        val rig = BarrierNotReadableFixture(stored)

        val outcome = rig.execute(BarrierNotReadableFixture.search())

        assertEquals("确认游客：1 请求、Authorization 缺省、无本地拒绝", BarrierNotReadableOutcome.sent(), outcome)
        assertEquals(1, rig.sentCount)
    }

    // D10-1942 ③、D10-1939 / D10-1940：盘上确无会话也不能在拒读窗口确认为游客；config 仍照发。
    @Test
    fun confirmedGuestSearchRejectsInsideBarrierWhileConfigSendsAndRecoversAnonymously() {
        val stored = MemorySessionTokens()
        assertEquals("本机确实没有会话", SessionSnapshot(null, null, null, null, 0L), stored.snapshot())
        val rig = BarrierNotReadableFixture(stored)
        val search = BarrierNotReadableFixture.search()
        assertEquals("搜索不声明身份未知也照发", false, search.sendsWhenIdentityUnknown)
        val config = ApiCall("getConfig", sendsWhenIdentityUnknown = true)

        val inside = rig.inClosedWindow(listOf(search, config))
        val insideCount = rig.sentCount
        rig.settleEnvironment()
        val recovered = rig.execute(search)

        assertEquals("同一关闸窗口：搜索零发送，config 匿名恰一次", listOf(unreadable(), BarrierNotReadableOutcome.sent()), inside)
        assertEquals("窗口内只有 config 发出", 1, insideCount)
        assertEquals("开闸后同一搜索调用照常匿名发出", BarrierNotReadableOutcome.sent(), recovered)
        assertEquals("累计仅 config 和恢复后的搜索", 2, rig.sentCount)
    }

    @Test
    fun publishedBasicModeStillSendsAnonymousSearchAndConfigWithoutReadingCredentials() {
        assertPublishedAnonymous(BarrierNotReadableFixture.FULL.copy(privacy = PrivacyRaw.BASIC))
    }

    @Test
    fun publishedWithdrawalCleanupStillSendsAnonymousSearchAndConfigWithoutReadingCredentials() {
        assertPublishedAnonymous(BarrierNotReadableFixture.FULL.copy(credentialsReadable = false))
    }

    @Test
    fun coldStartStoredAccountWithoutBoundRejectsSearchInClosedBarrier() {
        val stored = MemorySessionTokens("access-cold", "refresh-cold", SessionScopeRaw.FULL, "account-cold")
        assertEquals("冷启动盘上有会话", "account-cold", stored.snapshot().userId)
        val rig = BarrierNotReadableFixture(stored)
        assertEquals("尚未调用任何会话入口，ApiClient 没有读存储、没有形成 bound", 0, rig.tokens.reads)

        val outcome = rig.inClosedWindow(BarrierNotReadableFixture.search())

        assertEquals("未绑定也应：0 请求、无 Authorization、本地 session_unreadable", unreadable(), outcome)
        assertEquals(0, rig.sentCount)
    }

    private fun assertPublishedAnonymous(state: NetworkContext) {
        val rig = BarrierNotReadableFixture()
        assertEquals("之前确实登录并绑定过", BarrierNotReadableFixture.ACCOUNT, rig.api.activeUserId())
        rig.publishAnonymous(state)
        val reads = rig.credentialReads

        // 故意不声明 sendsWhenIdentityUnknown：已发布的合法匿名态不能误判成屏障暂时拒读。
        val outcomes = listOf(rig.execute(BarrierNotReadableFixture.search()), rig.execute(ApiCall("getConfig")))

        assertEquals(
            "已发布匿名白名单：搜索、配置各 1 请求，均无 Authorization、无本地拒绝",
            listOf(BarrierNotReadableOutcome.sent(), BarrierNotReadableOutcome.sent()),
            outcomes,
        )
        assertEquals("已发布基本模式 / 撤回清理中不再读凭据", reads, rig.credentialReads)
        assertEquals("白名单请求合计两次", 2, rig.sentCount)
    }

    private fun unreadable() = BarrierNotReadableOutcome.rejected(LocalRejectReason.SESSION_UNREADABLE)
}
