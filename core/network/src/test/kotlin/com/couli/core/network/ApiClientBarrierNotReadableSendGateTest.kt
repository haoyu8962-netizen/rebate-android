package com.couli.core.network

import com.couli.core.generated.ApiOperations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * D10-1971：窗口内闸门零调用；原本已拒绝的请求保留原拒绝码，不能一律改成 call_cancelled。
 * 原 config 用例全部断言已合并到 GapsTest.declaredConfigWithSendGateCancelsWithoutReadsInsideBarrierAndSendsAfterRelease。
 */
class ApiClientBarrierNotReadableSendGateTest {
    @Test
    fun walletGetMeWithSendGateKeepsUngatedRejectionInsideBarrierAndSendsAfterRelease() {
        // AppWallet.kt:375 的 owner 只用于 :382 的 ApiCall(GET_ME)；:547 定义 GET_ME = "getMe"。
        // 同一 operation，无 expectedAccount / 身份未知照发声明；只替换发送闸门为可计数的 lambda。
        val operation = ApiOperations.all.getValue("getMe")
        assertEquals("GET", operation.method)
        assertEquals("/v1/me", operation.pathTemplate)
        assertEquals("login", operation.auth)
        assertFalse("钱包这处闸门用于无签名的必须登录请求", operation.signed)
        val rig = BarrierNotReadableFixture()
        var gateCalls = 0
        val sendAllowed: () -> Boolean = {
            gateCalls += 1
            true
        }

        fun call(gate: (() -> Boolean)?) =
            ApiCall(
                operation.operationId,
                ownership = CallOwnership(rig.inFlight.epochs(), sendAllowed = gate) { true },
            )

        // 同一真实关闸窗口、同一 operation 和有效归属；对照仅不带 sendAllowed，不猜测拒绝码。
        val observed =
            rig.observeClosedWindow {
                val baseline = rig.execute(call(null))
                val gated = rig.execute(call(sendAllowed))
                baseline to gated
            }
        val callsInside = gateCalls
        val sentInside = rig.sentCount
        rig.settleEnvironment()
        // 环境世代已前进，重建有效归属，复用同一个 sendAllowed 对象。
        val recovered = rig.execute(call(sendAllowed))

        // 恢复闭环和零读取先检查，窗口内拒绝码即使回归也不会挡住恢复段执行。
        assertEquals("解除后同一闸门恰好调用一次", 1, gateCalls - callsInside)
        assertEquals("解除后恰好发送一次", 1, rig.sentCount - sentInside)
        assertEquals(BarrierNotReadableOutcome.sent(BarrierNotReadableFixture.BEARER), recovered)
        observed.assertNoReads()
        val (baseline, gated) = observed.value
        assertNotNull("不带闸门的同一钱包调用必须在窗口内本地拒绝", baseline.localRejectReason)
        assertEquals(BarrierNotReadableOutcome.rejected(checkNotNull(baseline.localRejectReason)), baseline)
        assertEquals("窗口内发送闸门一次也不能执行", 0, callsInside)
        assertEquals("窗口内对照与带闸门调用都零发送", 0, sentInside)
        if (baseline.localRejectReason != LocalRejectReason.CALL_CANCELLED) {
            assertNotEquals("不能把钱包原拒绝码变成静默取消", LocalRejectReason.CALL_CANCELLED, gated.localRejectReason)
        }
        assertEquals("D10-1971：带闸门与不带闸门的窗口内拒绝结局完全一致", baseline, gated)
    }
}
