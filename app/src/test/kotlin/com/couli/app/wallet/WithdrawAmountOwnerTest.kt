package com.couli.app.wallet

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletStaleCache
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-09 Android 定向复核 RC-01（S0，共性第 59 条）的金额本机保存：WithdrawViewModel 的金额输入与 SavedStateHandle 保存按渲染时固定的表单身份核对，
 * 不按执行时读到的账号保存。ViewModel 要主线程调度器，所以单独用 Robolectric 跑；网络换成立即回答的假 [WalletApi]。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class WithdrawAmountOwnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val env = Env()
    private var session: (() -> Unit)? = null

    @After
    fun tearDown() = scope.cancel()

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"

        override fun loggedIn() = user != null

        override fun sessionEpoch() = epoch

        /** 下一次读取账号之后执行一次（模拟：这次读取刚取得 A，IO 线程随即保存了 B 的令牌，清空通知还没执行）。 */
        var afterAccountRead: (() -> Unit)? = null

        override fun account(): String? {
            val current = user
            afterAccountRead?.let {
                afterAccountRead = null
                it()
            }
            return current
        }

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

        override fun stepUpPhone(account: String) = "13800005678"
    }

    private class FakeApi(
        private val env: Env,
    ) : WalletApi {
        val sent = mutableListOf<ApiCall>()

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

        override suspend fun send(call: ApiCall): ApiResult {
            sent += call
            // 同网络层：expectedAccount 不是当前会话账号的，读取凭据之前本地拒绝（account_mismatch）
            if (call.expectedAccount != null && call.expectedAccount != env.user) return ApiResult.LocalReject("account_mismatch")
            return when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.OPERATION -> ok("""{"withdrawal_id":"w-1"}""")
                else -> ok("""{"payout_method":null}""")
            }
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)

        private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)
    }

    private val api = FakeApi(env)
    private var keys = 0

    private fun model(saved: SavedStateHandle): WithdrawViewModel {
        val deps =
            WalletDeps(
                api = api,
                env = env,
                gates = WalletGates(scope),
                tokens = StepUpTokenCache(),
                cache = null,
                mask = null,
                realname = null,
                labor = null,
                newKey = { "key-" + (++keys) },
                service = scope,
            )
        return WithdrawViewModel(WalletPageConfig(deps, onBack = {}, hooks = PageHooks(bindSession = { session = it })), saved)
    }

    /** 会话已换成 B、清空通知还没执行：A 表单的旧输入不受理，按 A 的身份保存也被拦下，A 的金额不会绑到 B 的账号哈希下。 */
    @Test
    fun rc01_amountOfAIsNeverPersistedUnderBBeforeNotice() {
        val saved = SavedStateHandle()
        val model = model(saved)
        val ownerA = model.controller.flow.owner
        model.onAmountChange("10", ownerA)
        assertEquals("10", saved.get<String>(K_AMOUNT))
        assertEquals(WalletStaleCache.Store.hash("user-a"), saved.get<String>(K_OWNER))
        env.epoch += 1
        env.user = "user-b"
        model.onAmountChange("12", ownerA)
        model.persist(ownerA)
        model.persist()
        assertEquals("A 的金额不按执行时的账号（B）保存", WalletStaleCache.Store.hash("user-a"), saved.get<String>(K_OWNER))
        assertEquals("10", saved.get<String>(K_AMOUNT))
        model.controller.leave()
    }

    /** 清空通知执行之后，A 表单的旧输入回调才到达：不写进 B 的表单，也不保存。 */
    @Test
    fun rc01_oldAmountCallbackAfterNoticeIsNotWrittenOrPersisted() {
        val saved = SavedStateHandle()
        val model = model(saved)
        val ownerA = model.controller.flow.owner
        env.epoch += 1
        env.user = "user-b"
        session?.invoke()
        model.onAmountChange("10", ownerA)
        assertEquals("", model.controller.state.value.amountText)
        assertNull(saved.get<String>(K_AMOUNT))
        assertNull(saved.get<String>(K_OWNER))
        // B 自己的输入照常保存到 B 名下
        model.onAmountChange("8", model.controller.flow.owner)
        assertEquals("8", saved.get<String>(K_AMOUNT))
        assertEquals(WalletStaleCache.Store.hash("user-b"), saved.get<String>(K_OWNER))
        model.controller.leave()
    }

    /**
     * RC-06（S0，共性第 65 条）：重建 WithdrawViewModel 时，恢复金额的账号校验刚读到 A，IO 线程随即保存了 B 的令牌（清空通知还没执行）。
     * 表单身份与恢复金额必须来自同一次读取：A 的金额不进入 B 的表单，不以 B 的身份估算，不生成键、不提交；清空通知之后 B 的表单是空的。
     */
    @Test
    fun rc06_switchBetweenRestoreCheckAndFlowBuild_oldAmountNeverEntersBForm() {
        val saved = SavedStateHandle()
        saved[K_AMOUNT] = "10"
        saved[K_OWNER] = WalletStaleCache.Store.hash("user-a")
        env.afterAccountRead = {
            env.epoch += 1
            env.user = "user-b"
        }
        val model = model(saved)
        val controller = model.controller
        val owner = controller.flow.owner
        assertFalse("表单身份不能是 B（不得在恢复校验之后重新读取当前会话）", owner.account == "user-b")
        assertFalse("身份读取期间换了号：这份身份已不是当前会话", owner.current(env))
        assertEquals("A 的金额不进入表单", "", controller.state.value.amountText)
        assertTrue(
            "不以任何身份带 A 的金额估算",
            api.sent.none { it.operationId == WithdrawController.RULES_OPERATION && it.query != null },
        )
        assertTrue("读取不以 B 的身份发出", api.sent.none { it.expectedAccount == "user-b" })
        // 清空通知之前点【提交】（渲染时的身份就是这份）：不生成键、不提交
        controller.submit(controller.state.value.version, owner)
        assertEquals("不生成键", 0, keys)
        assertTrue("不提交", api.sent.none { it.operationId == WithdrawController.OPERATION })
        // 清空通知之后：B 的表单为空，A 的金额与账号哈希都已清掉
        session?.invoke()
        assertEquals("", controller.state.value.amountText)
        assertNull(saved.get<String>(K_AMOUNT))
        assertNull(saved.get<String>(K_OWNER))
        assertTrue(
            "B 的表单不带 A 的金额估算",
            api.sent.none { it.operationId == WithdrawController.RULES_OPERATION && it.query == "amount_fen=1000" },
        )
        controller.leave()
    }

    /** RC-06 对照：会话一直不变时，A 的金额照常恢复并按 A 估算（修复不能误伤正常恢复）。 */
    @Test
    fun rc06_sameSessionStillRestoresAmount() {
        val saved = SavedStateHandle()
        saved[K_AMOUNT] = "10"
        saved[K_OWNER] = WalletStaleCache.Store.hash("user-a")
        val model = model(saved)
        assertEquals("10", model.controller.state.value.amountText)
        assertEquals("user-a", model.controller.flow.owner.account)
        assertTrue(api.sent.any { it.operationId == WithdrawController.RULES_OPERATION && it.query == "amount_fen=1000" })
        model.controller.leave()
    }

    private companion object {
        const val K_AMOUNT = "withdraw.amount"
        const val K_OWNER = "withdraw.owner"
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
    }
}
