@file:Suppress("MagicNumber")

package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.wallet.AppWallet
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.SessionIdentity
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.PayoutAccountViewModel
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawSnapshot
import com.couli.feature.wallet.WithdrawViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0718 / F-AND-41（资金，首项；FU-AND-0717 复核三 RC-04，跨端 F-X-22）：写请求成功之后，页面离开依赖导航（提现页 replaceTop 换成提现记录、
 * 收款账号页出栈回提现页）。导航没有实际完成时，发起页不得保留「可再提交」的状态——同一笔成功之后，用户在本页再怎么点，资金写请求总数仍为 1。
 *
 * 全部经生产入口：真实 AppWallet（它的 env.replaceTop / payoutSaved 就是生产实现；本文件不接 NavController——生产规则下「没有导航就不动」，即导航
 * 一直不被受理，对应配置变更重建窗口里 NavController 已解绑的情形）、真实 WithdrawViewModel / PayoutAccountViewModel（构造即按生产接线自己加载，
 * 测试不手动 load）。页面可见性按生产转发目标 controller.onVisibility（WithdrawScreen 的 OnVisibility 把 ON_RESUME / ON_PAUSE 转给它）。
 * 「首次 ON_RESUME 之前提交并被覆盖」的生命周期路径与导航被拒后再受理，见 AppWalletIntegrationTest 的 fand41_* 用例（真实 NavHost 与 Activity 生命周期）。
 *
 * 写请求计数在端口入口同步记录（[Api.writes]，不看传输层）；结算以端口返回后投递到主线程队列尾部的记录为准（与 FlowLedger 同一做法），负向断言都在结算
 * 之后。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0718WithdrawTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val identity = SessionIdentity(known = true, account = USER, loginEpoch = 1L, partition = E1)

    private fun routing(): AppRouting {
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        return AppRouting(context, environment, initialUpdate = UpdateCheck.OK).also {
            it.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
    }

    /**
     * 钱包端口：读请求即答；资金写（提现提交、收款保存、确认上一笔、放弃上一笔）在入口同步记一次（[writes]），挂在 [held] 上，放行后回成功；
     * 每次写请求返回调用方之后往主线程队列尾部投一条结算记录（[settled]）。[pending] 是本机未决记录（确认 / 放弃成功后清掉）。
     */
    private class Api : WalletApi {
        private val main = Handler(Looper.getMainLooper())
        val writes = mutableListOf<String>()
        val keys = mutableListOf<String?>()

        @Volatile var settled = 0

        @Volatile var held: CompletableDeferred<Unit>? = null

        @Volatile var pending: PendingKeyRecord? = null

        /**
         * 第 5 版：按网络层口径模拟本机未决记录——提现提交发出前登记 in_flight 记录，成功后删除（ApiClient 的 PendingKeyStore 行为）。只在打开时生效。
         */
        @Volatile var trackSubmitPending = false

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending?.takeIf { it.operationId == operationId }

        override fun restorePending() = true

        private suspend fun write(
            label: String,
            key: String?,
            answer: () -> ApiResult,
        ): ApiResult {
            writes += label
            keys += key
            if (trackSubmitPending && label == WithdrawController.OPERATION && key != null) {
                pending = PendingKeyRecord(key, WithdrawController.OPERATION, """{"amount_fen":1000}""", USER, """{"amount_fen":1000}""")
            }
            try {
                held?.await()
                val result = answer()
                if (trackSubmitPending && label == WithdrawController.OPERATION && pending?.key == key) pending = null
                return result
            } finally {
                main.post { settled += 1 }
            }
        }

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult =
            write("confirm:$operationId", null) {
                pending = null
                ok(WITHDRAWN)
            }

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult =
            write("abandon:$operationId", null) {
                pending = null
                // 作废接口回 completed、原结果成功（网络层按原操作给出 Success）
                ok(WITHDRAWN)
            }

        override suspend fun send(call: ApiCall): ApiResult =
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                PayoutAccountController.GET_OPERATION -> ok("{}")
                WithdrawController.OPERATION -> write(call.operationId, call.idempotencyKey) { ok(WITHDRAWN) }
                PayoutAccountController.OPERATION -> write(call.operationId, call.idempotencyKey) { ok(PAYOUT_SAVED) }
                else -> CompletableDeferred<ApiResult>().await()
            }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private fun appWallet(
        api: Api,
        id: () -> SessionIdentity = { identity },
    ) = AppWallet(
        api = api,
        store = null,
        routing = routing(),
        sessionEpoch = { id().loginEpoch },
        account = { id().account },
        writer = { true },
        forceUpdate = {},
        cache = null,
        mask = null,
        realname = null,
        labor = null,
        stepUpPhone = { "13800005678" },
        partitionTracked = true,
        identity = id,
    ).also { it.onSessionChanged() }

    private fun AppWallet.page(
        route: String,
        entry: String,
    ) = config(RouteDestination(route, "{}", false), entry, onBack = {})

    /** 等此刻已发出的写请求（除仍被有意挂住的 [outstanding] 个）全部连同调用方的回调处理都已结算。 */
    private fun writesSettled(
        api: Api,
        outstanding: Int = 0,
    ) {
        val target = api.writes.size - outstanding
        FlowWait.until(::idle, { "写请求结算 ${api.settled}/$target（挂住 $outstanding）：${api.writes}" }) { api.settled >= target }
        idle()
    }

    private fun withdraw(wallet: AppWallet): WithdrawViewModel {
        val model = WithdrawViewModel(wallet.page("Withdraw", ENTRY), SavedStateHandle())
        idle()
        assertEquals("前提：rules 已加载", WithdrawSnapshot.CONTENT, model.controller.state.value.phase)
        return model
    }

    /** 用户在本页可做的全部「再提交」尝试：直接再点、改金额再点、清空重输同一金额再点（每次都用此刻渲染的版本与表单身份）。 */
    private fun tryAgain(
        model: WithdrawViewModel,
        api: Api,
        outstanding: Int = 0,
    ) {
        val controller = model.controller
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        model.onAmountChange("")
        model.onAmountChange(AMOUNT)
        idle()
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        model.onAmountChange("11")
        idle()
        controller.submit(controller.state.value.version, controller.flow.owner)
        writesSettled(api, outstanding)
    }

    /**
     * F-AND-41 ①：提交成功时页面可见（ON_RESUME 之后），但导航一直不被受理（AppWallet 没有可替换的发起页）——成功结局不显示成可重试错误；本页不得回到
     * 可提交：直接再点、清空重输同一金额再点、改金额再点、离开再回来（ON_PAUSE → ON_RESUME，消费重试仍被拒）再点、身份确认下发之后再点，
     * createWithdrawal 总数都仍为 1。
     */
    @Test
    fun fand41_aSuccessWhoseNavigationIsNeverAcceptedNeverLetsTheSameFormSubmitAgain() {
        val api = Api()
        val wallet = appWallet(api)
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        model.onAmountChange(AMOUNT)
        idle()
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        assertEquals("前提：第一次提交已发出", listOf(WithdrawController.OPERATION), api.writes)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        assertNull("成功不显示成可重试错误", controller.flow.state.value.error)
        tryAgain(model, api)
        assertEquals("成功、导航未受理：本页再点不发", listOf(WithdrawController.OPERATION), api.writes)
        controller.onVisibility(false)
        controller.onVisibility(true)
        idle()
        tryAgain(model, api)
        assertEquals("离开再回来、导航仍未受理：再点不发", listOf(WithdrawController.OPERATION), api.writes)
        wallet.onSessionChanged()
        idle()
        tryAgain(model, api)
        assertEquals("同一笔成功之后不再发出任何提现写请求", listOf(WithdrawController.OPERATION), api.writes)
    }

    /**
     * F-AND-41 ①对照：成功到达时页面已被覆盖（ON_PAUSE），回到可见（ON_RESUME）时才去导航、仍不被受理——同样不得回到可提交，总数为 1。
     */
    @Test
    fun fand41_aSuccessArrivingWhileCoveredWhoseLaterNavigationIsRejectedStillBlocksResubmit() {
        val api = Api()
        val wallet = appWallet(api)
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        model.onAmountChange(AMOUNT)
        idle()
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        controller.onVisibility(false)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        tryAgain(model, api)
        controller.onVisibility(true)
        idle()
        assertNull(controller.flow.state.value.error)
        tryAgain(model, api)
        assertEquals(listOf(WithdrawController.OPERATION), api.writes)
    }

    private fun pendingRecord() =
        PendingKeyRecord(
            key = "key-earlier",
            operationId = WithdrawController.OPERATION,
            body = """{"amount_fen":1000}""",
            account = USER,
            summary = """{"amount_fen":1000}""",
            stateRaw = PendingKeyStore.UNKNOWN,
            everUnknown = true,
        )

    /**
     * F-AND-41 ②（同类路径「确认上一笔」）：本页有未决的上一笔，【确认上一笔】得到成功、导航不被受理——页面不得回到可提交：用户重输同一金额再点，
     * 不发新的提现；资金写请求只有那一次确认。
     */
    @Test
    fun fand41_aConfirmedEarlierWithdrawalWhoseNavigationIsRejectedNeverLetsANewOneGoOut() {
        val api = Api()
        api.pending = pendingRecord()
        val wallet = appWallet(api)
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        val flow = controller.flow
        val key =
            flow.state.value.pending
                ?.key
        assertEquals("前提：页面显示待确认的上一笔", "key-earlier", key)
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        flow.confirm(key!!, flow.owner)
        idle()
        assertEquals("前提：确认已发出", listOf("confirm:${WithdrawController.OPERATION}"), api.writes)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        assertNull("成功不显示成可重试错误", flow.state.value.error)
        model.onAmountChange(AMOUNT)
        idle()
        tryAgain(model, api)
        controller.onVisibility(false)
        controller.onVisibility(true)
        idle()
        tryAgain(model, api)
        assertEquals(listOf("confirm:${WithdrawController.OPERATION}"), api.writes)
    }

    /**
     * F-AND-41 ②（同类路径「放弃上一笔」得到 completed、原结果成功 = 上一笔其实已成功）：导航不被受理时同样不得回到可提交。
     */
    @Test
    fun fand41_anAbandonThatTurnsOutAlreadyDoneWithRejectedNavigationNeverLetsANewOneGoOut() {
        val api = Api()
        api.pending = pendingRecord()
        val wallet = appWallet(api)
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        val flow = controller.flow
        val key =
            flow.state.value.pending
                ?.key!!
        flow.askAbandon(key, flow.owner)
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        flow.abandon(key, flow.owner)
        idle()
        assertEquals("前提：作废已发出", listOf("abandon:${WithdrawController.OPERATION}"), api.writes)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        model.onAmountChange(AMOUNT)
        idle()
        tryAgain(model, api)
        controller.onVisibility(false)
        controller.onVisibility(true)
        idle()
        tryAgain(model, api)
        assertEquals(listOf("abandon:${WithdrawController.OPERATION}"), api.writes)
    }

    /**
     * F-AND-41 ③（同类路径「收款账号保存」，只核对）：保存成功、出栈回提现页不被受理（生产规则：不是从提现页进来 / 没有导航就停在本页）——已填内容
     * 清空，用户再点【保存】不再发；savePayoutAccount 总数为 1。
     */
    @Test
    fun fand41_aSavedPayoutAccountWhosePopIsRejectedCannotBeSavedAgainWithTheSameInput() {
        val api = Api()
        val wallet = appWallet(api)
        val model = PayoutAccountViewModel(wallet.page("PayoutAccount", "entry-payout"))
        idle()
        val controller = model.controller
        controller.onVisibility(true)
        controller.chooseMethod("alipay")
        controller.onLogonChange("fixture@example.com")
        controller.onPayeeChange("测试")
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        controller.save(controller.flow.owner)
        idle()
        assertEquals("前提：保存已发出", listOf(PayoutAccountController.OPERATION), api.writes)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        assertNull(controller.flow.state.value.error)
        controller.save(controller.flow.owner)
        idle()
        controller.onVisibility(false)
        controller.onVisibility(true)
        idle()
        controller.save(controller.flow.owner)
        writesSettled(api)
        assertEquals(listOf(PayoutAccountController.OPERATION), api.writes)
    }

    // ---------------------------------------------------------------- 终版补测（第 2 轮变异 M24–M27、M33–M38 暴露的缺口）

    /** SavedStateHandle 里的键（与产品 WithdrawViewModel 同名；终版补测按编排要求直接核对保存状态）。 */
    private object Keys {
        const val AMOUNT = "withdraw.amount"
        const val OWNER = "withdraw.owner"
        const val WITHDRAWAL_ID = "withdraw.withdrawal_id"
    }

    /** 可见时提交成功、导航一直不受理（AppWallet 未接 NavController）；返回提现页的 Holder。 */
    private fun succeeded(
        api: Api,
        wallet: AppWallet,
        saved: SavedStateHandle,
    ): Holder {
        val holder = Holder(wallet, saved, ENTRY)
        idle()
        val controller = holder.model.controller
        assertEquals("前提：rules 已加载", WithdrawSnapshot.CONTENT, controller.state.value.phase)
        controller.onVisibility(true)
        holder.model.onAmountChange(AMOUNT)
        idle()
        assertEquals("前提：草稿已保存", AMOUNT, saved.get<String>(Keys.AMOUNT))
        controller.submit(controller.state.value.version, controller.flow.owner)
        writesSettled(api)
        assertEquals("前提：提交 1 笔", listOf(WithdrawController.OPERATION), api.writes)
        return holder
    }

    /** M24–M26：成功后保存状态里记的是原 withdrawal_id；金额草稿两个键已删除；成功后再编辑金额也不会把它们写回。 */
    @Test
    fun fand0718_final_successRecordsTheWithdrawalIdAndDropsTheDraftForGood() {
        val api = Api()
        val wallet = appWallet(api)
        val saved = SavedStateHandle()
        val holder = succeeded(api, wallet, saved)
        assertEquals("记的是原 withdrawal_id", "W1", saved.get<String>(Keys.WITHDRAWAL_ID))
        assertFalse("金额草稿已删", saved.contains(Keys.AMOUNT))
        assertFalse("草稿归属已删", saved.contains(Keys.OWNER))
        val owner = holder.model.controller.flow.owner
        holder.model.onAmountChange("12", owner)
        holder.model.onAmountChange("", owner)
        holder.model.onAmountChange(AMOUNT, owner)
        idle()
        assertFalse("成功后再编辑：金额草稿不写回", saved.contains(Keys.AMOUNT))
        assertFalse("成功后再编辑：草稿归属不写回", saved.contains(Keys.OWNER))
        holder.clear()
    }

    /** M27：提交在途时重建（同一 SavedStateHandle 里有在途锁与原草稿），新实例锁住提交且不回填原金额。 */
    @Test
    fun fand0718_final_aLockRestoredByARecreationShowsAnEmptyAmount() {
        val api = Api()
        api.trackSubmitPending = true
        val wallet = appWallet(api)
        val saved = SavedStateHandle()
        val first = Holder(wallet, saved, ENTRY)
        idle()
        val controller = first.model.controller
        controller.onVisibility(true)
        first.model.onAmountChange(AMOUNT)
        idle()
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        first.clear()
        idle()
        val second = Holder(wallet, saved, ENTRY)
        idle()
        assertEquals("恢复锁：不回填原金额", "", second.model.controller.state.value.amountText)
        assertTrue("恢复锁：提交锁住", second.model.controller.state.value.successPending)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        assertEquals("", second.model.controller.state.value.amountText)
        second.clear()
    }

    /**
     * M37 / M38：「确认上一笔」「放弃上一笔」在途时活动重建（ViewModel 被清除），用同一个 SavedStateHandle、或 ON_STOP 时的 Bundle 快照重建；之后结算（记录
     * 删除、结算通知到新实例）——写请求恰好是那 1 次确认 / 放弃，新实例再点不发。
     */
    @Test
    fun fand0718_final_aRecreationWhileConfirmOrAbandonIsInFlightNeverLetsANewWithdrawalOut() {
        val misses = mutableListOf<String>()
        for (abandon in listOf(false, true)) {
            for (bundleSnapshot in listOf(false, true)) {
                val label = (if (abandon) "放弃" else "确认") + (if (bundleSnapshot) "·Bundle 快照" else "·同一对象")
                val api = Api()
                api.pending = pendingRecord()
                val wallet = appWallet(api)
                val saved = SavedStateHandle()
                val first = Holder(wallet, saved, ENTRY)
                idle()
                val flow = first.model.controller.flow
                first.model.controller.onVisibility(true)
                val key =
                    flow.state.value.pending
                        ?.key
                assertEquals("$label 前提：待确认卡", "key-earlier", key)
                val gate = CompletableDeferred<Unit>()
                api.held = gate
                if (abandon) {
                    flow.askAbandon(key!!, flow.owner)
                    flow.abandon(key, flow.owner)
                } else {
                    flow.confirm(key!!, flow.owner)
                }
                idle()
                val write = (if (abandon) "abandon:" else "confirm:") + WithdrawController.OPERATION
                assertEquals("$label 前提：已发出", listOf(write), api.writes)
                api.held = null
                first.model.controller.onVisibility(false)
                val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                first.clear()
                idle()
                val second = Holder(wallet, if (bundleSnapshot) snapshot else saved, ENTRY)
                idle()
                second.model.controller.onVisibility(true)
                idle()
                gate.complete(Unit)
                writesSettled(api)
                assertNull("$label 前提：记录已删除", api.pending)
                second.model.onAmountChange(AMOUNT)
                idle()
                tryAgain(second.model, api)
                if (api.writes != listOf(write)) misses += "$label：${api.writes}"
                second.clear()
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * M33（FU-AND-0719 编排裁定口径，F-AND-43 ①）：确认上一笔在途时同一进程身份换代（同账号、本进程登录世代不变、持久化世代 E1→E2；App 判 CHANGED，表单按新快照重建）；之后旧表单的
     * 确认成功迟到——成功只落在旧表单，不给新表单上提交锁（新表单 successPending 为 false）；写请求只有那 1 次确认。
     */
    @Test
    fun fand0718_final_aLateConfirmSuccessForTheReplacedFormDoesNotLockTheNewForm() {
        val api = Api()
        api.pending = pendingRecord()
        var current = identity
        val wallet = appWallet(api) { current }
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        val flow = controller.flow
        val key =
            flow.state.value.pending
                ?.key!!
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        flow.confirm(key, flow.owner)
        idle()
        val before = flow.owner
        current = identity.copy(partition = "login-2")
        wallet.onSessionChanged()
        idle()
        assertTrue("前提：表单按换代快照重建（新的表单身份对象）", flow.owner !== before)
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        assertFalse("旧表单的迟到成功不锁新表单", controller.state.value.successPending)
        // FU-AND-0719 编排裁定（F-AND-43 ①）同步：不锁新表单，但新表单上不得显示可重试错误，该笔成功可见（待确认卡已消失）。三条写路径的完整用例见
        // FollowupAnd0719WithdrawTest.fand43_*
        assertNull("新表单不显示可重试错误", flow.state.value.error)
        assertNull("该笔成功可见：待确认卡已消失", flow.state.value.pending)
        assertEquals(listOf("confirm:${WithdrawController.OPERATION}"), api.writes)
    }

    /** M34：已成功锁住的表单收到同一进程的换代身份（表单按新快照重建）后，锁不解除：再点不发，successPending 仍为 true。 */
    @Test
    fun fand0718_final_aLockedFormStaysLockedAcrossAnIdentityChange() {
        val api = Api()
        var current = identity
        val wallet = appWallet(api) { current }
        val holder = succeeded(api, wallet, SavedStateHandle())
        val controller = holder.model.controller
        assertTrue("前提：成功锁", controller.state.value.successPending)
        val before = controller.flow.owner
        current = identity.copy(partition = "login-2")
        wallet.onSessionChanged()
        idle()
        assertTrue("前提：表单按换代快照重建", controller.flow.owner !== before)
        tryAgain(holder.model, api)
        assertEquals(listOf(WithdrawController.OPERATION), api.writes)
        assertTrue("换代后仍锁住", controller.state.value.successPending)
        holder.clear()
    }

    // ---------------------------------------------------------------- 第 5 版（按 Opus 对抗评审 r1）

    /**
     * 第 5 版（变异 M04「导航受理后解开提交锁」存活的补测）：替身让 replaceTopAccepted 返回 true，但页面没有被移出、也不调用 leave()（控制器自身契约：
     * 导航实际受理后才消费结果，本表单的提交锁不随消费解除，不依赖外部 leave 恰好先发生）。受理后用同一版本号与表单身份再提交，再清空、重输同一金额
     * 提交——createWithdrawal 仍 1 笔、successPending 仍为 true。
     */
    @Test
    fun fand41_m04_anAcceptedNavigationWithoutLeaveKeepsTheSubmitLock() {
        val api = Api()
        val wallet = appWallet(api)
        val base = wallet.env
        var accepted = 0
        val env =
            object : com.couli.feature.wallet.WalletEnv by base {
                override fun replaceTopAccepted(
                    route: String,
                    params: kotlinx.serialization.json.JsonObject,
                    entryId: String?,
                ): Boolean {
                    accepted += 1
                    return true
                }
            }
        val d = wallet.deps
        val deps =
            com.couli.feature.wallet.WalletDeps(
                api = d.api,
                env = env,
                gates = d.gates,
                tokens = d.tokens,
                cache = d.cache,
                mask = d.mask,
                realname = d.realname,
                labor = d.labor,
                texts = d.texts,
                clock = d.clock,
                newKey = d.newKey,
                pending = d.pending,
                sms = d.sms,
                walletDirty = d.walletDirty,
                service = d.service,
            )
        // 页面钩子缺省（bindLeave 不登记）：导航受理后没有任何外部 leave()
        val model =
            WithdrawViewModel(
                com.couli.feature.wallet
                    .WalletPageConfig(deps, onBack = {}, entryId = ENTRY),
                SavedStateHandle(),
            )
        idle()
        val controller = model.controller
        assertEquals("前提：rules 已加载", WithdrawSnapshot.CONTENT, controller.state.value.phase)
        controller.onVisibility(true)
        model.onAmountChange(AMOUNT)
        idle()
        val version = controller.state.value.version
        val owner = controller.flow.owner
        controller.submit(version, owner)
        writesSettled(api)
        assertEquals("前提：导航已受理一次", 1, accepted)
        assertTrue("前提：控制器没有离页", controller.alive)
        controller.submit(version, owner)
        idle()
        model.onAmountChange("", owner)
        model.onAmountChange(AMOUNT, owner)
        idle()
        controller.submit(controller.state.value.version, owner)
        writesSettled(api)
        assertEquals("受理后再提交不发", listOf(WithdrawController.OPERATION), api.writes)
        assertTrue("successPending 仍为 true", controller.state.value.successPending)
    }

    /** 页面 ViewModel 放在一个 ViewModelStore 里（与回退栈条目同一种持有方式），[clear] = 活动销毁且不保留 / 进程回收时 ViewModel 被清除。 */
    private class Holder(
        wallet: AppWallet,
        saved: SavedStateHandle,
        entry: String,
    ) {
        val store = androidx.lifecycle.ViewModelStore()
        val model: WithdrawViewModel =
            androidx.lifecycle
                .ViewModelProvider
                .create(
                    store,
                    object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(
                            modelClass: kotlin.reflect.KClass<T>,
                            extras: androidx.lifecycle.viewmodel.CreationExtras,
                        ): T = WithdrawViewModel(wallet.config(RouteDestination("Withdraw", "{}", false), entry, onBack = {}), saved) as T
                    },
                )[WithdrawViewModel::class]

        fun clear() = store.clear()
    }

    /**
     * R1-01（常规可达，资金）：提交后宿主到后台（ON_PAUSE / ON_STOP），后台收到成功（不可见、不导航）；随后活动销毁且 ViewModel 被清除（不保留活动 /
     * 进程回收），用**同一个 SavedStateHandle** 重建提现页并回到可见——本页不得回到可提交：直接再点、清空重输同一金额再点、改金额再点，createWithdrawal
     * 总数仍为 1。分两种：App 级对象保留（不保留活动）与 App 级对象也重建（进程回收，本机未决记录已在成功时删除）。
     */
    @Test
    fun fand41_r101_aSuccessReceivedInTheBackgroundSurvivesARecreationWithTheSameSavedState() {
        for ((processDeath, bundleSnapshot) in listOf(false to false, true to false, false to true, true to true)) {
            val mode = (if (processDeath) "进程回收" else "不保留活动") + (if (bundleSnapshot) "·ON_STOP 时的 Bundle 快照" else "·同一对象")
            val api = Api()
            api.trackSubmitPending = true
            val wallet = appWallet(api)
            val saved = SavedStateHandle()
            val first = Holder(wallet, saved, ENTRY)
            idle()
            val controller = first.model.controller
            assertEquals("前提：rules 已加载", WithdrawSnapshot.CONTENT, controller.state.value.phase)
            controller.onVisibility(true)
            first.model.onAmountChange(AMOUNT)
            idle()
            val gate = CompletableDeferred<Unit>()
            api.held = gate
            controller.submit(controller.state.value.version, controller.flow.owner)
            idle()
            assertEquals("前提：提交已发出", listOf(WithdrawController.OPERATION), api.writes)
            // 宿主到后台：ON_PAUSE（OnVisibility 的转发）；ON_STOP 时系统保存实例状态（onSaveInstanceState）——第 6 版 R2-03：此刻复制一份内容，
            // 之后在后台写进 SavedStateHandle 的东西进不了恢复用的 Bundle
            controller.onVisibility(false)
            val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            api.held = null
            gate.complete(Unit)
            writesSettled(api)
            assertNull("前提：本机未决记录已在成功时删除", api.pending)
            first.clear()
            idle()
            val againWallet = if (processDeath) appWallet(api) else wallet
            val second = Holder(againWallet, if (bundleSnapshot) snapshot else saved, ENTRY)
            idle()
            second.model.controller.onVisibility(true)
            idle()
            tryAgain(second.model, api)
            assertEquals(
                "$mode：重建后再点不发",
                listOf(WithdrawController.OPERATION),
                api.writes,
            )
            second.clear()
        }
    }

    /**
     * R1-01 变体：提交在途时活动重建（旧 ViewModel 被清除，从未收到成功），用同一个 SavedStateHandle 建的新实例之后才收到结算通知（未决记录被删）——
     * 新实例同样不得回到可提交，createWithdrawal 总数为 1。
     */
    @Test
    fun fand41_r101_aRecreationWhileTheSubmitIsInFlightStillBlocksResubmitAfterTheSettlement() {
        val api = Api()
        api.trackSubmitPending = true
        val wallet = appWallet(api)
        val saved = SavedStateHandle()
        val first = Holder(wallet, saved, ENTRY)
        idle()
        val controller = first.model.controller
        controller.onVisibility(true)
        first.model.onAmountChange(AMOUNT)
        idle()
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        assertEquals("前提：提交已发出", listOf(WithdrawController.OPERATION), api.writes)
        controller.onVisibility(false)
        first.clear()
        idle()
        val second = Holder(wallet, saved, ENTRY)
        idle()
        second.model.controller.onVisibility(true)
        idle()
        api.held = null
        gate.complete(Unit)
        writesSettled(api)
        assertNull("前提：本机未决记录已在成功时删除", api.pending)
        tryAgain(second.model, api)
        second.model.controller.onVisibility(false)
        second.model.controller.onVisibility(true)
        idle()
        tryAgain(second.model, api)
        assertEquals(listOf(WithdrawController.OPERATION), api.writes)
        second.clear()
    }

    /**
     * R1-02（刻意时序）：【确认上一笔】在途时，未决记录先被同步删除、页面回到前台（OnReturn → onResume → 重读未决记录；当前产品会借此推进待确认版本），
     * 之后成功结果才回到主线程——在途窗口里与放行之后，重输同一金额再点都不发新的提现。
     */
    @Test
    fun fand41_r102_aConfirmSuccessAfterThePendingRecordWasAlreadyClearedStillLocksTheForm() {
        val api = Api()
        api.pending = pendingRecord()
        val wallet = appWallet(api)
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        val flow = controller.flow
        val key =
            flow.state.value.pending
                ?.key!!
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        flow.confirm(key, flow.owner)
        idle()
        assertEquals("前提：确认已发出", listOf("confirm:${WithdrawController.OPERATION}"), api.writes)
        api.pending = null
        controller.onResume()
        idle()
        // 第 6 版：不再要求「待确认版本已推进」——正确的修法可以在本页确认 / 放弃在途时不推进版本；窗口只由外部事件定义（记录已消失、页面已回前台重读）
        // 第 6 版 R2-01 / R2-02：确认还挂着（只这 1 笔被挂住，之后的写请求不挂）、记录已消失、版本已推进——这个窗口里重输同一金额经回调入口再提交
        api.held = null
        model.onAmountChange(AMOUNT)
        idle()
        tryAgain(model, api, outstanding = 1)
        assertEquals("在途窗口：只有那 1 次确认", listOf("confirm:${WithdrawController.OPERATION}"), api.writes)
        gate.complete(Unit)
        writesSettled(api)
        model.onAmountChange(AMOUNT)
        idle()
        tryAgain(model, api)
        assertEquals("放行之后：仍只有那 1 次", listOf("confirm:${WithdrawController.OPERATION}"), api.writes)
    }

    /** R1-02（放弃上一笔得到 completed、原结果成功）：同样的时序，本页不得回到可提交。 */
    @Test
    fun fand41_r102_anAlreadyDoneAbandonAfterThePendingRecordWasAlreadyClearedStillLocksTheForm() {
        val api = Api()
        api.pending = pendingRecord()
        val wallet = appWallet(api)
        val model = withdraw(wallet)
        val controller = model.controller
        controller.onVisibility(true)
        val flow = controller.flow
        val key =
            flow.state.value.pending
                ?.key!!
        flow.askAbandon(key, flow.owner)
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        flow.abandon(key, flow.owner)
        idle()
        assertEquals("前提：作废已发出", listOf("abandon:${WithdrawController.OPERATION}"), api.writes)
        api.pending = null
        controller.onResume()
        idle()
        // 第 6 版：不再要求「待确认版本已推进」——正确的修法可以在本页确认 / 放弃在途时不推进版本；窗口只由外部事件定义（记录已消失、页面已回前台重读）
        // 第 6 版 R2-01 / R2-02：放弃还挂着（只这 1 笔被挂住，之后的写请求不挂）、记录已消失、版本已推进——这个窗口里重输同一金额经回调入口再提交
        api.held = null
        model.onAmountChange(AMOUNT)
        idle()
        tryAgain(model, api, outstanding = 1)
        assertEquals("在途窗口：只有那 1 次放弃", listOf("abandon:${WithdrawController.OPERATION}"), api.writes)
        gate.complete(Unit)
        writesSettled(api)
        model.onAmountChange(AMOUNT)
        idle()
        tryAgain(model, api)
        assertEquals("放行之后：仍只有那 1 次", listOf("abandon:${WithdrawController.OPERATION}"), api.writes)
    }

    private companion object {
        const val USER = "user-a"
        const val E1 = "login-1"
        const val ENTRY = "entry-withdraw"
        const val AMOUNT = "10"
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
        const val WITHDRAWN = """{"withdrawal_id":"W1"}"""
        const val PAYOUT_SAVED =
            """{"payout_method":"alipay","masked_account":"fix***@example.com","masked_payee_name":"*试","change_remaining_this_month":1}"""

        fun ok(json: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(json), "trace-000123", null)
    }
}
