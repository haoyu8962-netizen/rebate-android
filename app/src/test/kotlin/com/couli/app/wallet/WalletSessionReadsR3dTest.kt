package com.couli.app.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletStaleCache
import com.couli.feature.wallet.LaborAgreementController
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.LaborSnapshot
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletController
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawRecordsController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * APP-09 Android 第四次窄修（全量审计，共性第 65 条）：审计表 android-fix-r3d-session-reads.md 里改掉的 C 类各一条。场景都是「B 的令牌已在 IO 线程
 * 保存（账号与登录世代都变了），App 主线程的清空通知还没执行」：页面 / 表单执行器只能用构造（或会话换代通知重建）时一次性取得的固定身份，
 * 不得在读取、写缓存、放入未决记录、读取协议时重新读取当前会话来绑定。网络换成可控的假 [WalletApi]，协程在 Unconfined 上同步推进。
 */
@Suppress("TooManyFunctions")
class WalletSessionReadsR3dTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        pageScope.cancel()
        scope.cancel()
    }

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"

        /** 下一次读取登录世代之后执行一次（模拟：这次核对刚读到旧世代，IO 线程随即保存了 B 的令牌）。 */
        var afterEpochRead: (() -> Unit)? = null

        override fun loggedIn() = user != null

        override fun sessionEpoch(): Long {
            val current = epoch
            afterEpochRead?.let {
                afterEpochRead = null
                it()
            }
            return current
        }

        override fun account() = user

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        val opened = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
        ) {
            opened += route
        }

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) = Unit

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String) = "13800005678"
    }

    private class Call(
        val call: ApiCall,
        val answer: CompletableDeferred<ApiResult> = CompletableDeferred(),
    )

    private class FakeApi : WalletApi {
        val calls = mutableListOf<Call>()
        var pending: PendingKeyRecord? = null

        /** 自动回答；返回 null 的由测试手动回答。 */
        var auto: (ApiCall) -> ApiResult? = { null }

        fun of(operation: String) = calls.filter { it.call.operationId == operation }

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending?.takeIf { it.operationId == operationId }

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = CompletableDeferred<ApiResult>().await()

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = CompletableDeferred<ApiResult>().await()

        override suspend fun send(call: ApiCall): ApiResult {
            val entry = Call(call)
            calls += entry
            auto(call)?.let { return it }
            return entry.answer.await()
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private class FakeLabor : LaborBackend {
        var reads = 0

        override suspend fun current(): ApiResult {
            reads += 1
            return ApiResult.Success(
                Json.parseToJsonElement("""{"version":"v1","text":"协议正文","text_sha256":"abc","need_sign":true}"""),
                "trace-000123",
                null,
            )
        }

        override suspend fun sign(
            version: String,
            textSha256: String,
        ): ApiResult = CompletableDeferred<ApiResult>().await()
    }

    private val env = Env()
    private val api = FakeApi()

    private fun deps(
        cache: WalletStaleCache.Store? = null,
        labor: LaborBackend? = null,
    ) = WalletDeps(
        api = api,
        env = env,
        gates = WalletGates(scope),
        tokens = StepUpTokenCache(),
        cache = cache,
        mask = null,
        realname = null,
        labor = labor,
        service = scope,
    )

    private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)

    /** B 的令牌已保存（世代、账号都变了），清空通知还没执行。 */
    private fun switchToBWithoutNotice() {
        env.epoch += 1
        env.user = "user-b"
    }

    private fun stillOwned(entry: Call): Boolean? =
        entry.call.ownership
            ?.stillOwned
            ?.invoke()

    // ---------------------------------------------------------------- 钱包页

    /** 摘要响应通过世代核对之后、写缓存之前换号：A 的摘要只按本页固定身份（A）写缓存，不写进 B 名下。 */
    @Test
    fun r3d_walletSummaryCachedUnderPageOwnerNotReReadAccount() {
        val store = WalletStaleCache.Store(folder.newFolder("wallet"))
        val wallet = WalletController(deps(cache = store), pageScope)
        wallet.load()
        val first = api.of(WalletController.OPERATION).single()
        assertEquals("摘要读取带本页身份", "user-a", first.call.expectedAccount)
        env.afterEpochRead = { switchToBWithoutNotice() }
        first.answer.complete(ok(SUMMARY))
        assertNull("A 的摘要不写进 B 的缓存", store.read("user-b"))
        assertNotNull(store.read("user-a"))
        wallet.leave()
    }

    /** 清空通知之前的刷新：不以 B 的身份读取（账号与世代都取自本页固定身份）。 */
    @Test
    fun r3d_walletRefreshInWindowStaysBoundToPageOwner() {
        val wallet = WalletController(deps(), pageScope)
        wallet.load()
        api.of(WalletController.OPERATION)[0].answer.complete(ok(SUMMARY))
        switchToBWithoutNotice()
        wallet.refresh()
        val second = api.of(WalletController.OPERATION)[1]
        assertEquals("刷新仍带本页身份 A（网络层按它核对，不读 B 的凭据）", "user-a", second.call.expectedAccount)
        assertEquals("归属按本页世代：B 的世代下不再算本页的请求", false, stillOwned(second))
        wallet.leave()
    }

    // ---------------------------------------------------------------- 提现记录

    @Test
    fun r3d_recordsReloadInWindowStaysBoundToPageOwner() {
        val records = WithdrawRecordsController(deps(), pageScope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok("""{"items":[],"next_cursor":null}"""))
        switchToBWithoutNotice()
        records.refresh()
        val second = api.of(WithdrawRecordsController.LIST_OPERATION)[1]
        assertEquals("user-a", second.call.expectedAccount)
        assertEquals(false, stillOwned(second))
        records.leave()
    }

    // ---------------------------------------------------------------- 提现页：页头与收款账号读取

    @Test
    fun r3d_withdrawHeaderAndPayoutReadsInWindowStayBoundToFormOwner() {
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                else -> null
            }
        }
        val controller = WithdrawController(deps(), pageScope).also { it.start() }
        val before = api.calls.size
        switchToBWithoutNotice()
        controller.onResume()
        val later = api.calls.drop(before)
        val rules = later.single { it.call.operationId == WithdrawController.RULES_OPERATION }
        val payout = later.single { it.call.operationId == WithdrawController.PAYOUT_OPERATION }
        assertNull("不带金额的页头读取", rules.call.query)
        assertEquals("页头读取带表单身份 A，不以 B 的身份取页头", "user-a", rules.call.expectedAccount)
        assertEquals("收款账号读取带表单身份 A", "user-a", payout.call.expectedAccount)
        assertEquals(false, stillOwned(payout))
        controller.leave()
    }

    // ---------------------------------------------------------------- 未决记录放进表单

    /** 清空通知之前回到前台：B 的未决记录不放进 A 的表单（待确认卡、【确认上一笔】都不出现）。 */
    @Test
    fun r3d_pendingRecordOfBNotLoadedIntoFormOfA() {
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                else -> null
            }
        }
        val controller = WithdrawController(deps(), pageScope).also { it.start() }
        assertNull(controller.flow.state.value.pending)
        switchToBWithoutNotice()
        api.pending =
            PendingKeyRecord(
                "key-b",
                WithdrawController.OPERATION,
                """{"amount_fen":500}""",
                "user-b",
                """{"amount_fen":500}""",
                PendingKeyStore.UNKNOWN,
                everUnknown = true,
            )
        controller.onResume()
        assertNull("B 的未决记录不进入 A 的表单", controller.flow.state.value.pending)
        // 清空通知之后按 B 的身份重建，B 自己的记录照常显示
        controller.onSessionChanged()
        assertEquals(
            "key-b",
            controller.flow.state.value.pending
                ?.key,
        )
        controller.leave()
    }

    // ---------------------------------------------------------------- 收款账号页读取

    @Test
    fun r3d_payoutPageReloadInWindowStaysBoundToFormOwner() {
        api.auto = { call -> if (call.operationId == PayoutAccountController.GET_OPERATION) ok(PAYOUT_ALIPAY) else null }
        val controller = PayoutAccountController(deps(), pageScope).also { it.start() }
        val before = api.calls.size
        switchToBWithoutNotice()
        controller.onResume()
        val reload = api.calls.drop(before).single { it.call.operationId == PayoutAccountController.GET_OPERATION }
        assertEquals("user-a", reload.call.expectedAccount)
        assertEquals(false, stillOwned(reload))
        controller.leave()
    }

    // ---------------------------------------------------------------- 劳务协议读取

    /** 清空通知之前【重试】读取协议：不读、不把内容绑到 B（之后也就不能以 B 的身份签署 A 页面上的协议）。 */
    @Test
    fun r3d_laborReloadInWindowDoesNotBindContentToB() {
        val backend = FakeLabor()
        val controller = LaborAgreementController(deps(labor = backend), pageScope) {}
        controller.load()
        assertEquals(1, backend.reads)
        assertEquals("user-a", controller.state.value.owner.account)
        switchToBWithoutNotice()
        controller.load()
        assertEquals("清空通知之前不按 B 的会话重读", 1, backend.reads)
        assertFalse("协议内容不绑到 B", controller.state.value.owner.account == "user-b")
        assertEquals(LaborSnapshot.CONTENT, controller.state.value.phase)
        // 清空通知之后按 B 的身份重读
        controller.onSessionChanged()
        assertEquals(2, backend.reads)
        assertEquals("user-b", controller.state.value.owner.account)
        controller.leave()
    }

    private companion object {
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
        const val SUMMARY =
            """{"available_fen":1000,"withdrawable_fen":1000,"negative_fen":0,"frozen_fen":200,"estimated_total_fen":1019,""" +
                """"estimated_fen":269,"pending_credit_fen":750,"pending_credit_paused_fen":450,"next_credit_period":"2026-11",""" +
                """"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""
        const val PAYOUT_ALIPAY =
            """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三","change_remaining_this_month":2}"""
    }
}
