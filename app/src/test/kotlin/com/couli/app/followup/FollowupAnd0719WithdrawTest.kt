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
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.core.network.SessionIdentity
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawSnapshot
import com.couli.feature.wallet.WithdrawViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0719 / F-AND-43（FU-AND-0718 评审 r2 R2-04 / R2-05；跨端 F-X-22）：提现锁口径收尾。
 *
 * ① 资金口径一致（编排裁定）：同账号分区换代、表单按新快照重建之后，旧表单迟到的成功（首次提交 / 确认上一笔 / 放弃得原成功）**不锁新表单**；但新表单上
 *   不得显示可重试错误，且该笔成功在新表单上可见（待确认卡消失）。对照：同一表单内的迟到成功仍锁（不得回退）。
 * ② 在途锁释放：提现在途标记在**明确不出款**的结局（字段错误 20001、本地拒绝未发出、键已结束的业务错误 30306）后清除，进程回收 / 活动重建后同一表单
 *   不再被锁；结果未知（超时、记录仍在）时保持锁；成功时保持锁。
 *
 * 全部经生产入口：真实 AppWallet（会话通知 onSessionChanged → 页面钩子）、真实 WithdrawViewModel（构造即按生产接线加载；SavedStateHandle 与回退栈条目
 * 同一种持有方式）。按钮经回调入口（controller.submit(渲染版本, 表单身份)、model.onAmountChange、flow.confirm / askAbandon / abandon），可见性经
 * controller.onVisibility（OnVisibility 的转发目标），回前台经 controller.onResume（OnReturn 的转发目标）。测试不读取实现的保存键名。
 *
 * 写请求在端口入口同步记录（[Api.writes]；本地拒绝也是端口的一次调用，在入口记下）；结算以端口返回后投到主线程队列尾部的记录为准，负向断言都在结算之后。
 * 替身按网络层口径维护本机未决记录：首次提交发出前登记 in_flight，成功 / 键结束的错误删除，结果未知改为 unknown 保留；确认 / 放弃发出时标 in_flight，
 * 成功后删除；本地拒绝不登记。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0719WithdrawTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val identity = SessionIdentity(known = true, account = USER, loginEpoch = 1L, partition = E1)

    private fun routing(): AppRouting {
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        return AppRouting(context, environment, initialUpdate = UpdateCheck.OK).also {
            it.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
    }

    /** 钱包端口替身（见类注释）。[submitAnswers] 依次作为前几次首次提交的结局，用完后一律成功。 */
    private class Api : WalletApi {
        private val main = Handler(Looper.getMainLooper())
        val writes = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val submitAnswers = ArrayDeque<ApiResult>()

        /** 第 2 版（T-01）：依次作为前几次确认 / 放弃的结局，用完后一律成功（放弃 = completed 且原结果成功）。结局一到，原键记录删除。 */
        val confirmAnswers = ArrayDeque<ApiResult>()
        val abandonAnswers = ArrayDeque<ApiResult>()

        @Volatile var settled = 0

        @Volatile var held: CompletableDeferred<Unit>? = null

        @Volatile var pending: PendingKeyRecord? = null

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending?.takeIf { it.operationId == operationId }

        override fun restorePending() = true

        private suspend fun write(
            label: String,
            key: String?,
            block: suspend () -> ApiResult,
        ): ApiResult {
            writes += label
            keys += key
            try {
                return block()
            } finally {
                main.post { settled += 1 }
            }
        }

        private suspend fun submit(call: ApiCall): ApiResult =
            write(call.operationId, call.idempotencyKey) {
                val answer = submitAnswers.removeFirstOrNull() ?: ok(WITHDRAWN)
                if (answer is ApiResult.LocalReject) return@write answer
                val key = call.idempotencyKey!!
                pending = PendingKeyRecord(key, WithdrawController.OPERATION, call.body.orEmpty(), USER, SUMMARY)
                held?.await()
                when (answer) {
                    is ApiResult.TransportFailure -> {
                        pending = pending?.takeIf { it.key == key }?.copy(stateRaw = PendingKeyStore.UNKNOWN, everUnknown = true)
                    }

                    else -> {
                        if (pending?.key == key) pending = null
                    }
                }
                answer
            }

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult =
            write("confirm:$operationId", target.key) {
                pending = pending?.copy(stateRaw = PendingKeyStore.IN_FLIGHT)
                held?.await()
                settle(confirmAnswers.removeFirstOrNull() ?: ok(WITHDRAWN))
            }

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult =
            write("abandon:$operationId", target.key) {
                pending = pending?.copy(stateRaw = PendingKeyStore.IN_FLIGHT)
                held?.await()
                // 缺省：作废接口回 completed、原结果成功（网络层按原操作给出 Success）
                settle(abandonAnswers.removeFirstOrNull() ?: ok(WITHDRAWN))
            }

        /** 第 3 版（T-06）：确认 / 放弃的结局落到原键记录——结果未知（TransportFailure）保留原键并标为 unknown，其余结局删除。 */
        private fun settle(answer: ApiResult): ApiResult {
            pending =
                if (answer is ApiResult.TransportFailure) {
                    pending?.copy(stateRaw = PendingKeyStore.UNKNOWN, everUnknown = true)
                } else {
                    null
                }
            return answer
        }

        override suspend fun send(call: ApiCall): ApiResult =
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok("{}")
                WithdrawController.OPERATION -> submit(call)
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

    /** 页面 ViewModel 放在一个 ViewModelStore 里（与回退栈条目同一种持有方式）；[clear] = 活动销毁且不保留 / 进程回收时 ViewModel 被清除。 */
    private class Holder(
        wallet: AppWallet,
        saved: SavedStateHandle,
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
                        ): T = WithdrawViewModel(wallet.config(RouteDestination("Withdraw", "{}", false), ENTRY, onBack = {}), saved) as T
                    },
                )[WithdrawViewModel::class]

        fun clear() = store.clear()
    }

    /** 等此刻已发出的写请求（除仍被有意挂住的 [outstanding] 个）全部连同调用方的回调处理都已结算。 */
    private fun writesSettled(
        api: Api,
        outstanding: Int = 0,
    ) {
        val target = api.writes.size - outstanding
        FlowWait.until(::idle, { "写请求结算 ${api.settled}/$target（挂住 $outstanding）：${api.writes}" }) { api.settled >= target }
        idle()
    }

    private fun opened(
        wallet: AppWallet,
        saved: SavedStateHandle = SavedStateHandle(),
    ): Holder {
        val holder = Holder(wallet, saved)
        idle()
        assertEquals("前提：rules 已加载", WithdrawSnapshot.CONTENT, holder.model.controller.state.value.phase)
        holder.model.controller.onVisibility(true)
        idle()
        return holder
    }

    /** 经回调入口输入金额（先清空，与当前金额无关）并用此刻渲染的版本与表单身份点【提交】。 */
    private fun typeAndSubmit(
        model: WithdrawViewModel,
        amount: String = AMOUNT,
    ) {
        val controller = model.controller
        model.onAmountChange("", controller.flow.owner)
        model.onAmountChange(amount, controller.flow.owner)
        idle()
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
    }

    /**
     * 用户在本页可做的全部「再发」尝试：直接再点、清空重输同一金额再点、改金额再点；待确认卡上有键时也点【确认上一笔】与【确定放弃】（每次取此刻渲染的
     * 版本、表单身份与卡上的键）。
     */
    private fun tryAgain(
        model: WithdrawViewModel,
        api: Api,
        outstanding: Int = 0,
        alsoCard: Boolean = false,
    ) {
        val controller = model.controller
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        typeAndSubmit(model)
        typeAndSubmit(model, "11")
        if (alsoCard) {
            val flow = controller.flow
            flow.state.value.pending?.key?.let { key ->
                flow.confirm(key, flow.owner)
                idle()
                flow.askAbandon(key, flow.owner)
                flow.abandon(key, flow.owner)
                idle()
            }
        }
        writesSettled(api, outstanding)
    }

    private fun earlierRecord() =
        PendingKeyRecord(
            key = "key-earlier",
            operationId = WithdrawController.OPERATION,
            body = SUMMARY,
            account = USER,
            summary = SUMMARY,
            stateRaw = PendingKeyStore.UNKNOWN,
            everUnknown = true,
        )

    private enum class Path(
        val label: String,
        val write: String,
    ) {
        SUBMIT("首次提交", WithdrawController.OPERATION),
        CONFIRM("确认上一笔", "confirm:${WithdrawController.OPERATION}"),
        ABANDON("放弃得原成功", "abandon:${WithdrawController.OPERATION}"),
    }

    /** 按 [path] 发起这一笔写请求（挂在 [Api.held] 上）。 */
    private fun start(
        path: Path,
        holder: Holder,
    ) {
        val controller = holder.model.controller
        val flow = controller.flow
        when (path) {
            Path.SUBMIT -> {
                typeAndSubmit(holder.model)
            }

            Path.CONFIRM -> {
                val key =
                    flow.state.value.pending
                        ?.key
                assertEquals("前提：待确认卡", "key-earlier", key)
                flow.confirm(key!!, flow.owner)
                idle()
            }

            Path.ABANDON -> {
                val key =
                    flow.state.value.pending
                        ?.key
                assertEquals("前提：待确认卡", "key-earlier", key)
                flow.askAbandon(key!!, flow.owner)
                flow.abandon(key, flow.owner)
                idle()
            }
        }
    }

    // ---------------------------------------------------------------- ① 资金口径一致

    /**
     * F-AND-43 ①（R2-04 本端等价；编排裁定）：三种写路径各自在途时，同一进程同账号的持久化世代 E1→E2（App 判换代，表单按新快照重建，有前提断言表单身份
     * 对象已更换），之后旧表单的成功迟到：
     * - 换代窗口里（旧写请求仍挂着）新表单上任何再发尝试都不发（直接点、重输、改金额、卡上确认 / 放弃）；
     * - 结算后：新表单不被锁（successPending=false），不显示可重试错误（错误条、字段错误都没有），该笔成功可见（待确认卡已消失）；
     * - 新表单可以照常提一笔新的：用户输入后点【提交】，恰好多出 1 次 createWithdrawal；
     * - 写请求：换代前后合计恰好是「那 1 次 + 用户在新表单上主动提交的 1 次」。
     */
    @Test
    fun fand43_aLateSuccessOfTheReplacedFormNeitherLocksNorErrorsTheNewFormAndStaysVisible() {
        val misses = mutableListOf<String>()
        for (path in Path.entries) {
            val api = Api()
            if (path != Path.SUBMIT) api.pending = earlierRecord()
            var current = identity
            val wallet = appWallet(api) { current }
            val holder = opened(wallet)
            val controller = holder.model.controller
            val flow = controller.flow
            val gate = CompletableDeferred<Unit>()
            api.held = gate
            start(path, holder)
            assertEquals("${path.label} 前提：已发出 1 笔", listOf(path.write), api.writes)
            val before = flow.owner
            current = identity.copy(partition = E2)
            wallet.onSessionChanged()
            idle()
            assertTrue("${path.label} 前提：表单按换代快照重建", flow.owner !== before)
            assertEquals("${path.label} 前提：新表单 rules 已加载", WithdrawSnapshot.CONTENT, controller.state.value.phase)
            // 换代窗口：旧写请求仍挂着（只这 1 笔被挂住，之后的不挂）
            api.held = null
            tryAgain(holder.model, api, outstanding = 1, alsoCard = true)
            if (api.writes != listOf(path.write)) misses += "${path.label} 换代窗口：${api.writes}"
            gate.complete(Unit)
            writesSettled(api)
            val state = controller.state.value
            val flowState = flow.state.value
            if (state.successPending) misses += "${path.label}：旧表单的迟到成功锁住了新表单"
            if (flowState.error != null) misses += "${path.label}：新表单显示可重试错误 ${flowState.error}"
            if (flowState.fieldErrors.isNotEmpty() || flowState.fieldErrorKey != null) misses += "${path.label}：新表单显示字段错误"
            if (flowState.pending != null) misses += "${path.label}：该笔成功后待确认卡仍在 ${flowState.pending}"
            val writesBefore = api.writes.toList()
            typeAndSubmit(holder.model)
            writesSettled(api)
            val expected = writesBefore + WithdrawController.OPERATION
            if (api.writes != expected) misses += "${path.label}：新表单主动提交应恰好多 1 次，实为 ${api.writes}"
            holder.clear()
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * F-AND-43 ①对照（同一表单内迟到成功仍锁，不得回退）：同样三种路径在途时，App 下发的会话通知是同一身份（不换代、表单不重建），之后成功迟到——本表单
     * 锁住（successPending=true），成功不显示成可重试错误；直接点、重输同一金额、改金额都不发；写请求恰好是那 1 次。
     */
    @Test
    fun fand43_aLateSuccessOfTheSameFormStillLocksIt() {
        val misses = mutableListOf<String>()
        for (path in Path.entries) {
            val api = Api()
            if (path != Path.SUBMIT) api.pending = earlierRecord()
            val wallet = appWallet(api)
            val holder = opened(wallet)
            val controller = holder.model.controller
            val flow = controller.flow
            val gate = CompletableDeferred<Unit>()
            api.held = gate
            start(path, holder)
            val before = flow.owner
            wallet.onSessionChanged()
            idle()
            if (flow.owner !== before) misses += "${path.label}：同一身份的会话通知重建了表单"
            api.held = null
            gate.complete(Unit)
            writesSettled(api)
            if (!controller.state.value.successPending) misses += "${path.label}：同一表单的迟到成功没有上锁"
            if (flow.state.value.error != null) misses += "${path.label}：成功显示成可重试错误 ${flow.state.value.error}"
            tryAgain(holder.model, api)
            if (api.writes != listOf(path.write)) misses += "${path.label}：${api.writes}"
            holder.clear()
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    // ---------------------------------------------------------------- 第 1 版补：换代重置清除保存状态里的旧锁（编排裁定 ②）

    /** 换代前旧表单处在哪一步。 */
    private enum class Before(
        val label: String,
    ) {
        SUBMIT_IN_FLIGHT("首次提交在途"),
        CONFIRM_IN_FLIGHT("确认上一笔在途"),
        SUCCEEDED("成功已采用、导航未受理"),
    }

    /**
     * 编排裁定（第 1 版补，与①同一口径；第 2 版按评审 T-02 改写）：同账号分区换代、表单重置时，旧表单留在 SavedStateHandle 的在途 / 成功标记（含原
     * withdrawal_id）随重置**当即**清除——旧写请求仍挂着时也一样。换代前旧表单 [Before] 三种状态之一：
     * - 换代重置一完成（旧请求仍挂起）就核对原 SavedStateHandle：在途、成功标记都不在，withdrawal_id 为空；并在此刻取 Bundle 快照（ON_STOP 发生在这里）；
     * - 随后放回旧结果（迟到成功，到达时表单已重置），等结算；再 ON_PAUSE；
     * - 用「换代那一刻的 Bundle 快照 / 同一 SavedStateHandle」×「不保留活动 / 进程回收」重建：新表单不被锁（successPending=false），重建本身不发写请求，
     *   同一 SavedStateHandle 里也没有被迟到成功写回的 withdrawal_id；用户输入后点【提交】恰好多出 1 次 createWithdrawal。
     * 清除发生在保存之前，不属于「结局晚于 Bundle 保存」的已知限制。对照见 [fand43_r1_withoutAnIdentityChangeTheSavedLockSurvivesARecreation]。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 状态 × 重建方式逐项遍历，失败汇总后一次断言
    fun fand43_r1_anIdentityResetClearsTheOldFormsSavedLocksSoARecreatedNewFormIsNotLocked() {
        val misses = mutableListOf<String>()
        for (before in Before.entries) {
            for ((processDeath, bundle) in listOf(false to false, true to false, false to true, true to true)) {
                val label = "${before.label}·" + (if (processDeath) "进程回收" else "不保留活动") + (if (bundle) "·换代时的 Bundle 快照" else "·同一对象")
                val api = Api()
                if (before == Before.CONFIRM_IN_FLIGHT) api.pending = earlierRecord()
                var current = identity
                val id = { current }
                val wallet = appWallet(api, id)
                val saved = SavedStateHandle()
                val first = opened(wallet, saved)
                val gate = CompletableDeferred<Unit>()
                if (before != Before.SUCCEEDED) api.held = gate
                if (before == Before.CONFIRM_IN_FLIGHT) start(Path.CONFIRM, first) else typeAndSubmit(first.model)
                if (before == Before.SUCCEEDED) {
                    writesSettled(api)
                    assertTrue("$label 前提：成功已锁", first.model.controller.state.value.successPending)
                }
                val sent = api.writes.toList()
                assertEquals("$label 前提：已发出 1 笔", 1, sent.size)
                assertTrue("$label 前提：换代前保存状态里有在途或成功标记", Keys.LOCKS.any { saved.contains(it) })
                val owner = first.model.controller.flow.owner
                current = identity.copy(partition = E2)
                wallet.onSessionChanged()
                idle()
                assertTrue("$label 前提：表单按换代快照重建", first.model.controller.flow.owner !== owner)
                if (before != Before.SUCCEEDED) assertEquals("$label 前提：旧请求仍挂着", 0, api.settled)
                // 换代重置一完成就核对（旧请求仍挂着）并立即取快照
                Keys.LOCKS.filter { saved.get<Any?>(it) != null }.forEach { misses += "$label：换代重置后保存状态里仍有 $it=${saved.get<Any?>(it)}" }
                val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                if (before != Before.SUCCEEDED) {
                    api.held = null
                    gate.complete(Unit)
                    writesSettled(api)
                }
                first.model.controller.onVisibility(false)
                if (saved.get<String>(Keys.WITHDRAWAL_ID) != null) misses += "$label：迟到成功把旧的 withdrawal_id 写回了保存状态"
                first.clear()
                idle()
                val againWallet = if (processDeath) appWallet(api, id) else wallet
                val second = opened(againWallet, if (bundle) snapshot else saved)
                writesSettled(api)
                if (api.writes != sent) misses += "$label：重建本身发出了写请求 ${api.writes}"
                if (second.model.controller.state.value.successPending) misses += "$label：重建的新表单被旧表单的标记锁住"
                typeAndSubmit(second.model)
                writesSettled(api)
                if (api.writes != sent + WithdrawController.OPERATION) misses += "$label：新表单主动提交应恰好多 1 次，实为 ${api.writes}"
                second.clear()
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * 第 1 版补对照（不换代、锁不得被误清）：同样三种状态，App 下发同一身份的会话通知（表单不重建），之后 ON_PAUSE、取 Bundle 快照、进程回收重建——新表单
     * 仍锁：再发尝试都不发（在途的那一笔结算之后才重建）。
     */
    @Test
    fun fand43_r1_withoutAnIdentityChangeTheSavedLockSurvivesARecreation() {
        val misses = mutableListOf<String>()
        for (before in Before.entries) {
            val api = Api()
            if (before == Before.CONFIRM_IN_FLIGHT) api.pending = earlierRecord()
            val wallet = appWallet(api)
            val saved = SavedStateHandle()
            val first = opened(wallet, saved)
            val gate = CompletableDeferred<Unit>()
            if (before != Before.SUCCEEDED) api.held = gate
            if (before == Before.CONFIRM_IN_FLIGHT) start(Path.CONFIRM, first) else typeAndSubmit(first.model)
            val sent = api.writes.toList()
            wallet.onSessionChanged()
            idle()
            first.model.controller.onVisibility(false)
            val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            api.held = null
            gate.complete(Unit)
            writesSettled(api)
            first.clear()
            idle()
            val second = opened(appWallet(api), snapshot)
            tryAgain(second.model, api)
            if (api.writes != sent) misses += "${before.label}：不换代时重建后再发了 ${api.writes}"
            second.clear()
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /** SavedStateHandle 里的锁键（编排裁定与评审 T-02 点名 K_IN_FLIGHT / K_SUCCEEDED / K_WITHDRAWAL_ID，与产品 WithdrawViewModel 同名）。 */
    private object Keys {
        const val WITHDRAWAL_ID = "withdraw.withdrawal_id"
        const val IN_FLIGHT = "withdraw.in_flight"
        const val SUCCEEDED = "withdraw.succeeded"
        val LOCKS = listOf(IN_FLIGHT, SUCCEEDED, WITHDRAWAL_ID)
    }

    // ---------------------------------------------------------------- ② 在途锁释放

    private enum class End(
        val label: String,
        /** 明确不出款：重建后同一表单不再被锁。 */
        val released: Boolean,
    ) {
        FIELD("字段错误 20001", true),
        BUSINESS("键已结束的业务错误 30306", true),
        LOCAL("本地拒绝未发出", true),
        UNKNOWN("结果未知（超时，记录仍在）", false),
        SUCCESS("成功", false),
        ;

        fun result(): ApiResult =
            when (this) {
                FIELD -> fail(20_001, """{"fields":["amount_fen"]}""")
                BUSINESS -> fail(30_306)
                LOCAL -> ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED)
                UNKNOWN -> ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
                SUCCESS -> ok(WITHDRAWN)
            }
    }

    /**
     * F-AND-43 ②（R2-05 本端等价）：页面可见时提交得到 [End] 结局并结算，之后宿主到后台（ON_PAUSE）、活动销毁且 ViewModel 被清除，用保存状态重建提现页并
     * 回到可见。四种重建：「不保留活动 / 进程回收（App 级对象也重建）」×「同一 SavedStateHandle / 结局之后 ON_STOP 时的 Bundle 快照」。
     * - 明确不出款（字段错误、30306、本地拒绝）：重建后不锁（successPending=false），用户输入后点【提交】恰好多出 1 次 createWithdrawal；
     * - 结果未知（记录仍在）：重建后待确认卡在（前提），再发尝试都不发；之后记录被别处结算消失、页面回前台重读，仍不发（在途锁保持）；
     * - 成功：重建后再发尝试都不发。
     * 任何结局下，重建本身不发写请求。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 结局 × 重建方式逐项遍历，失败汇总后一次断言
    fun fand43_theInFlightLockIsReleasedOnlyWhenTheWithdrawalDefinitelyDidNotGoOut() {
        val misses = mutableListOf<String>()
        for (end in End.entries) {
            for ((processDeath, bundle) in listOf(false to false, true to false, false to true, true to true)) {
                val label = "${end.label}·" + (if (processDeath) "进程回收" else "不保留活动") + (if (bundle) "·Bundle 快照" else "·同一对象")
                val api = Api()
                api.submitAnswers += end.result()
                val wallet = appWallet(api)
                val saved = SavedStateHandle()
                val first = opened(wallet, saved)
                typeAndSubmit(first.model)
                writesSettled(api)
                assertEquals("$label 前提：端口收到 1 次提交", listOf(WithdrawController.OPERATION), api.writes)
                val firstKey = api.keys.single()
                if (end == End.UNKNOWN) {
                    assertEquals("$label 前提：记录仍在（unknown）", PendingKeyStore.UNKNOWN, api.pending?.stateRaw)
                } else {
                    assertEquals("$label 前提：没有未决记录", null, api.pending)
                }
                first.model.controller.onVisibility(false)
                val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                first.clear()
                idle()
                val againWallet = if (processDeath) appWallet(api) else wallet
                val second = opened(againWallet, if (bundle) snapshot else saved)
                val controller = second.model.controller
                writesSettled(api)
                if (api.writes.size != 1) misses += "$label：重建本身发出了写请求 ${api.writes}"
                when {
                    end.released -> {
                        if (controller.state.value.successPending) misses += "$label：明确不出款后重建仍被锁"
                        typeAndSubmit(second.model)
                        writesSettled(api)
                        val fresh = api.keys.drop(1)
                        if (api.writes != listOf(WithdrawController.OPERATION, WithdrawController.OPERATION)) {
                            misses += "$label：重建后主动提交应恰好多 1 次，实为 ${api.writes}"
                        } else if (fresh.singleOrNull() == null) {
                            misses += "$label：新提交没有幂等键"
                        }
                    }

                    end == End.UNKNOWN -> {
                        val card = controller.flow.state.value.pending
                        assertNotNull("$label 前提：待确认卡在", card)
                        assertEquals("$label 前提：卡上是原键", firstKey, card!!.key)
                        tryAgain(second.model, api)
                        if (api.writes.size != 1) misses += "$label：记录在时再发了 ${api.writes}"
                        api.pending = null
                        controller.onResume()
                        idle()
                        tryAgain(second.model, api)
                        if (api.writes.size != 1) misses += "$label：记录消失后在途锁没保持，再发了 ${api.writes}"
                    }

                    else -> {
                        tryAgain(second.model, api)
                        if (api.writes.size != 1) misses += "$label：成功后重建再发了 ${api.writes}"
                    }
                }
                second.clear()
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * F-AND-43 ②对照（活页面不受影响）：明确不出款的结局之后不重建，同一页面照常可再提交（恰好多 1 次）；结果未知之后同一页面进待确认，新的提交入口不发。
     */
    @Test
    fun fand43_theLivePageAfterADefiniteFailureSubmitsOnceMoreAndAfterAnUnknownStaysPending() {
        val misses = mutableListOf<String>()
        for (end in listOf(End.FIELD, End.BUSINESS, End.LOCAL, End.UNKNOWN)) {
            val api = Api()
            api.submitAnswers += end.result()
            val wallet = appWallet(api)
            val holder = opened(wallet)
            typeAndSubmit(holder.model)
            writesSettled(api)
            assertFalse("${end.label} 前提：没有成功锁", holder.model.controller.state.value.successPending)
            if (end.released) {
                // 30306 之后本页记下服务端阻断，只有之后明确允许的 rules 才解除：回前台重取 rules（OnReturn 的转发）
                holder.model.controller.onResume()
                idle()
                typeAndSubmit(holder.model)
                writesSettled(api)
                if (api.writes.size != 2) misses += "${end.label}：活页面再提交应恰好多 1 次，实为 ${api.writes}"
            } else {
                tryAgain(holder.model, api)
                if (api.writes.size != 1) misses += "${end.label}：${api.writes}"
            }
            holder.clear()
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    // ---------------------------------------------------------------- 第 2 版（评审 T-01）：确认 / 放弃入口的明确不出款同样释放在途锁

    private enum class CardEnd(
        val label: String,
        val abandon: Boolean,
        /** 明确不出款：重建后同一表单不再被锁。 */
        val released: Boolean,
    ) {
        CONFIRM_30306("确认得 30306（键已结束）", false, true),
        ABANDON_COMPLETED_30306("放弃得 completed、原结果 30306", true, true),
        ABANDON_ABANDONED("放弃得 abandoned（原操作没执行）", true, true),
        CONFIRM_SUCCESS("确认成功（对照）", false, false),
        ABANDON_COMPLETED_SUCCESS("放弃得 completed、原结果成功（对照）", true, false),
        CONFIRM_TIMEOUT("确认超时（结果未知，对照）", false, false),
        ABANDON_TIMEOUT("放弃超时（结果未知，对照）", true, false),
        ;

        val unknown: Boolean get() = this == CONFIRM_TIMEOUT || this == ABANDON_TIMEOUT

        fun result(): ApiResult? =
            when (this) {
                CONFIRM_30306, ABANDON_COMPLETED_30306 -> fail(30_306)
                ABANDON_ABANDONED -> ApiResult.Abandoned("key-earlier", "trace-000123")
                CONFIRM_SUCCESS, ABANDON_COMPLETED_SUCCESS -> null
                CONFIRM_TIMEOUT, ABANDON_TIMEOUT -> ApiResult.TransportFailure("timeout", null, "key-earlier", pendingKeyUnknown = true)
            }
    }

    /**
     * 评审 T-01：待确认卡上【确认上一笔】/【确定放弃】在页面可见时得到 [CardEnd] 结局并结算（结局已采用），之后 ON_PAUSE、取 Bundle 快照、ViewModel 被清除，
     * 按「不保留活动 / 进程回收」×「同一 SavedStateHandle / 结局之后的 Bundle 快照」重建并回到可见：
     * - 明确不出款（确认 30306、放弃 completed(30306)、放弃 abandoned）：重建本身不发写请求，新表单不锁，用户输入后点【提交】恰好多出 1 次 createWithdrawal；
     * - 对照（确认成功、放弃得原成功）：重建后仍锁，再发尝试都不发；
     * - 第 3 版（评审 T-06）对照（确认超时、放弃超时 = 结果未知，原键记录保留为 unknown）：重建后待确认卡是原键，再发尝试都不发；记录随后消失并经 onResume
     *   刷新后，直接提交、重输、改金额仍都不发（在途锁保持，裁定④）。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 结局 × 重建方式逐项遍历，失败汇总后一次断言
    fun fand43_t01_aDefiniteNoPayoutFromConfirmOrAbandonReleasesTheSavedLockToo() {
        val misses = mutableListOf<String>()
        for (end in CardEnd.entries) {
            for ((processDeath, bundle) in listOf(false to false, true to false, false to true, true to true)) {
                val label = "${end.label}·" + (if (processDeath) "进程回收" else "不保留活动") + (if (bundle) "·Bundle 快照" else "·同一对象")
                val api = Api()
                api.pending = earlierRecord()
                end.result()?.let { if (end.abandon) api.abandonAnswers += it else api.confirmAnswers += it }
                val wallet = appWallet(api)
                val saved = SavedStateHandle()
                val first = opened(wallet, saved)
                start(if (end.abandon) Path.ABANDON else Path.CONFIRM, first)
                writesSettled(api)
                val sent = api.writes.toList()
                assertEquals("$label 前提：发出 1 次${if (end.abandon) "放弃" else "确认"}", 1, sent.size)
                if (end.unknown) {
                    assertEquals("$label 前提：原键记录仍在（unknown）", PendingKeyStore.UNKNOWN, api.pending?.stateRaw)
                } else {
                    assertEquals("$label 前提：原键记录已结束", null, api.pending)
                }
                first.model.controller.onVisibility(false)
                val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                first.clear()
                idle()
                val second = opened(if (processDeath) appWallet(api) else wallet, if (bundle) snapshot else saved)
                writesSettled(api)
                if (api.writes != sent) misses += "$label：重建本身发出了写请求 ${api.writes}"
                if (end.released) {
                    if (second.model.controller.state.value.successPending) misses += "$label：明确不出款后重建仍被锁"
                    typeAndSubmit(second.model)
                    writesSettled(api)
                    if (api.writes != sent + WithdrawController.OPERATION) misses += "$label：重建后主动提交应恰好多 1 次，实为 ${api.writes}"
                } else if (end.unknown) {
                    val card = second.model.controller.flow.state.value.pending
                    if (card?.key != "key-earlier") misses += "$label：重建后待确认卡不是原键 ${card?.key}"
                    tryAgain(second.model, api)
                    if (api.writes != sent) misses += "$label：原键记录仍在时再发了 ${api.writes}"
                    api.pending = null
                    second.model.controller.onResume()
                    idle()
                    tryAgain(second.model, api)
                    if (api.writes != sent) misses += "$label：记录消失、回前台刷新后在途锁没保持，再发了 ${api.writes}"
                } else {
                    tryAgain(second.model, api)
                    if (api.writes != sent) misses += "$label：原成功后重建再发了 ${api.writes}"
                }
                second.clear()
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    private companion object {
        const val USER = "user-a"
        const val E1 = "login-1"
        const val E2 = "login-2"
        const val ENTRY = "entry-withdraw"
        const val AMOUNT = "10"
        const val SUMMARY = """{"amount_fen":1000}"""
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
        const val WITHDRAWN = """{"withdrawal_id":"W1"}"""

        fun ok(json: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(json), "trace-000123", null)

        fun fail(
            code: Int,
            data: String = "{}",
        ): ApiResult =
            ApiResult.Failure(
                code,
                "",
                Json.parseToJsonElement(data).jsonObject,
                "trace-000123",
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )
    }
}
