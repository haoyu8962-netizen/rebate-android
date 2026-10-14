package com.couli.app.followup

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.orders.OrderDetail
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.orders.OrderDetailController
import com.couli.feature.orders.OrderDetailPhase
import com.couli.feature.orders.OrderEnv
import com.couli.feature.orders.OrderListController
import com.couli.feature.orders.OrderListPhase
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletController
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0714 第 4 项（F-AND-28，APP-09 共性第 65 条）：页面构造时账号短暂读不出（已登录、activeUserId() 为 null）→ 可恢复的初始化失败：不以
 * expectedAccount = null 发请求、不读写缓存、不发布正常数据；【重试】时清空并重新建立页面身份，之后照常加载，也照常接收本会话的提现成功通知。
 * 钱包、订单列表、订单详情各一组（「我的」「Agent」的同类核对见 audit.md）。协程在 Unconfined 上同步推进。
 */
class FollowupAnd0714IdentityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    /** 账号可读与否可控的会话环境（已登录，账号 [user] 为 null = 此刻读不出）。 */
    private open class Session {
        var epoch = 1L
        var user: String? = null
        var signedIn = true

        fun loggedIn() = signedIn
    }

    private class Sends : PageApi {
        val calls = mutableListOf<ApiCall>()
        var answer: (ApiCall) -> ApiResult = { ApiResult.TransportFailure("disconnect", null, null, false) }

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return answer(call)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)

    // ---------------------------------------------------------------- 钱包

    private class Env(
        val session: Session,
    ) : WalletEnv {
        override fun loggedIn() = session.loggedIn()

        override fun sessionEpoch() = session.epoch

        override fun account() = session.user

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) = Unit

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String): String? = null
    }

    private class Wallet(
        val sends: Sends,
    ) : WalletApi,
        PageApi by sends {
        override fun pendingFor(operationId: String): PendingKeyRecord? = null

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = error("not used")

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = error("not used")
    }

    @Test
    fun fand28_wallet_identity_unreadable_at_construction_is_recoverable_failure_and_retry_rebuilds_identity() {
        val session = Session()
        val sends = Sends()
        sends.answer = { ok(SUMMARY) }
        val deps =
            WalletDeps(
                api = Wallet(sends),
                env = Env(session),
                gates = WalletGates(scope),
                tokens = StepUpTokenCache(),
                cache = null,
                mask = null,
                realname = null,
                labor = null,
                service = scope,
            )
        val wallet = WalletController(deps, scope)
        wallet.load()
        assertEquals("不发布正常钱包数据：可恢复的错误态", WalletSnapshot.ERROR, wallet.state.value.phase)
        assertNull(wallet.state.value.summary)
        assertNotNull("带【重试】的错误提示", wallet.state.value.error)
        assertTrue("不以 expectedAccount = null 发请求", sends.calls.isEmpty())

        // 账号读得出了：【重试】先清空、再重新取得页面身份，按新身份请求
        session.user = "user-a"
        assertTrue(wallet.refresh())
        assertEquals(1, sends.calls.size)
        assertEquals("user-a", sends.calls.single().expectedAccount)
        assertEquals(WalletSnapshot.CONTENT, wallet.state.value.phase)

        // 本会话的提现成功通知照常接收（不再因身份为空永久拒收）
        deps.walletDirty.notify("user-a", session.epoch)
        assertEquals("收到本会话的失效通知后重取", 2, sends.calls.size)
    }

    // ---------------------------------------------------------------- 订单

    private class Orders(
        val session: Session,
    ) : OrderEnv {
        override fun loggedIn() = session.loggedIn()

        override fun sessionEpoch() = session.epoch

        override fun account() = session.user

        override fun helpLinks(): JsonObject? = null

        override fun copy(text: String) = true

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun forceUpdate() = Unit

        override fun onDetailLoaded(detail: OrderDetail) = Unit
    }

    @Test
    fun fand28_order_list_identity_unreadable_is_recoverable_failure_and_retry_rebuilds_identity() {
        val session = Session()
        val sends = Sends()
        sends.answer = { ok("""{"items":[],"next_cursor":null,"earliest_visible_date":null}""") }
        val list = OrderListController(sends, Orders(session), null, scope)
        list.open(null)
        assertEquals(OrderListPhase.ERROR, list.state.value.phase)
        assertTrue("不发请求", sends.calls.isEmpty())
        assertNull(list.ownerAccount)

        session.user = "user-a"
        // 【重试】：入口的身份检查发现账号已读得出 → 清空并重新取得固定归属，按新归属取第一页
        list.retry()
        assertEquals("重新取得固定归属", "user-a", list.ownerAccount)
        assertEquals(1, sends.calls.size)
        assertTrue(list.state.value.phase != OrderListPhase.ERROR)
    }

    @Test
    fun fand28_order_detail_identity_unreadable_is_recoverable_failure_and_retry_rebuilds_identity() {
        val session = Session()
        val sends = Sends()
        val detail = OrderDetailController(sends, Orders(session), null, scope, "ord-1")
        detail.load()
        assertEquals(OrderDetailPhase.ERROR, detail.state.value.phase)
        assertNull(detail.state.value.detail)
        assertTrue("不发请求", sends.calls.isEmpty())

        session.user = "user-a"
        assertTrue("【重试】清空并重新取得固定身份", detail.retry(detail.state.value.version, detail.state.value.owner))
        assertEquals(1, sends.calls.size)
        assertEquals(
            "user-a",
            detail.state.value.owner
                ?.account,
        )
    }

    private companion object {
        const val SUMMARY =
            """{"available_fen":1234,"withdrawable_fen":1234,"negative_fen":0,"frozen_fen":0,"estimated_total_fen":5678,""" +
                """"estimated_fen":2222,"pending_credit_fen":3456,"pending_credit_paused_fen":456,"next_credit_period":"2026-10",""" +
                """"credit_overdue":false,"withdrawn_fen":500,"risk_paused_reason":null,"withdraw_enabled":true}"""
    }
}
