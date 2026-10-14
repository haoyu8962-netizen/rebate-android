package com.couli.core.agent

import com.couli.core.network.CallEpochs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0714 第 1 轮评审 R1-04（F-AND-28）：「会员但账号未知」（level=member、account=null）是可恢复的初始化失败——控制器的全部正常入口
 * （取会话、建会话、新对话、发消息、快捷追问、加载【重试】）对待定 operation 端口零调用、canSend=false；【重试】只在同一页面实例、同一登录世代、
 * 只差账号时交给 App 重建（[AgentDeps.rebuildIdentity]）。
 */
class FollowupAnd0714AgentIdentityTest {
    private val kit = AgentTestKit()
    private var rebuilds = 0

    private fun unknownMember(
        loginEpoch: Long = 1,
        page: Long = 1,
    ) = AgentIdentity("test", AgentIdentity.MEMBER, null, loginEpoch, CallEpochs(0, 0), 0, page)

    private fun controller(fixed: AgentIdentity): AgentController =
        AgentController(
            AgentDeps(
                identity = fixed,
                currentIdentity = { kit.identity },
                contract = kit.port,
                gate = kit.gate,
                scope = kit.scope,
                timer = kit.timer,
                clock = { kit.now },
                recordConsent = { accepted ->
                    kit.consentCalls += accepted
                    AgentTestKit.ok()
                },
                rebuildIdentity = { rebuilds += 1 },
            ),
        ).also { c ->
            c.setVisible(true)
            c.start()
        }

    private fun portCalls() = kit.port.currents + kit.port.creates + kit.port.streams.size + kit.port.cancels.size + kit.port.notSent.size

    @Test
    fun r1_04_member_with_unknown_account_is_recoverable_failure_and_normal_entries_never_reach_the_port() {
        val fixed = unknownMember()
        kit.identity = fixed
        val c = controller(fixed)
        assertEquals("可恢复的加载失败", AgentPhase.LOAD_FAILED, c.currentPhase)
        assertFalse(c.canSend)
        val page = c.snapshot.value.page
        assertFalse("发消息不受理", c.send(page, "帮我找纯牛奶"))
        c.newChat(page)
        c.chip(AgentChipTap(page.controller, page.conversation, "换一批", "换一批"))
        c.setVisible(false)
        c.setVisible(true)
        c.start()
        // 账号仍读不出：【重试】核对通过但身份不变，交给 App（App 侧按此刻身份重建，仍为失败）
        c.retryLoad(page)
        kit.advance(1)
        assertEquals("取会话、建会话、消息流、cancel 一律零调用（也不进端口再被拦）", 0, portCalls())
        assertTrue(kit.consentCalls.isEmpty())
        assertEquals(1, rebuilds)
    }

    @Test
    fun r1_04_retry_rebuilds_only_for_the_same_page_and_login_epoch() {
        val fixed = unknownMember()
        kit.identity = fixed
        val c = controller(fixed)
        val page = c.snapshot.value.page
        // 别的对话 / 控制器的票据：不重建
        c.retryLoad(AgentPageTicket(page.controller + 1, page.conversation))
        assertEquals(0, rebuilds)
        // 重新登录（登录世代变了）：交给会话同步，不经【重试】重建
        kit.identity = AgentTestKit.member("user-a").copy(loginEpoch = 2)
        c.retryLoad(page)
        assertEquals(0, rebuilds)
        // 同一登录世代、账号恢复：重建
        kit.identity = AgentTestKit.member("user-a")
        c.retryLoad(page)
        assertEquals(1, rebuilds)
        assertEquals(0, portCalls())
        // 已作废的控制器不再重建
        c.dispose()
        c.retryLoad(page)
        assertEquals(1, rebuilds)
    }

    @Test
    fun r1_04_known_member_still_loads_normally() {
        val c = kit.controller(AgentTestKit.member("user-a"))
        kit.advance(1)
        assertEquals(1, kit.port.currents)
        assertEquals(AgentPhase.READY, c.currentPhase)
    }
}
