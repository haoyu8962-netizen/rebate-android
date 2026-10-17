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
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.core.network.SessionIdentity
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.WalletTexts
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawSnapshot
import com.couli.feature.wallet.WithdrawViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0720 / F-AND-45（FU-AND-0719 对抗评审 r1 的 R1-01、R1-03）：提现体验与在途锁边界。设施沿用 [FollowupAnd0719WithdrawTest]（真实 AppWallet、真实
 * WithdrawViewModel 放在 ViewModelStore 里、按钮经回调入口、可见性经 controller.onVisibility、回前台经 controller.onResume），替身端口另加：
 * - rules / 收款账号读取在端口入口同步记录（[Api.reads]），端口返回后往主线程队列尾部投一条结算记录（[Api.readsSettled]）；
 * - 任何一笔写请求以成功返回之后（[Api.paid]），rules 与收款账号改回「成功之后」的内容（余额、账号行都不同），用来断言重取的结果确实被采用。
 *
 * R1-01：同账号分区换代、表单按新快照重建之后，旧表单的成功迟到——新表单补一次 rules / 收款账号重取（等同 onSettled）并采用重取结果，给中性提示
 *   「上一笔提现已提交，可在提现记录查看」；不锁、不显示错误（维持 FU-AND-0719 口径）。对照：旧表单迟到的是明确不出款 / 结果未知时，不给这句提示。
 * R1-03：提现在途标记只由当初设锁的那个键的明确不出款释放；别的键（记录换成的另一条、记录消失后新发的一笔）的明确不出款不得清掉它。对照：同一个键的明确
 *   不出款照常释放。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0720WithdrawTest {
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
     * 钱包端口替身（未决记录口径同 [FollowupAnd0719WithdrawTest]）：首次提交发出前登记 in_flight，结果未知改为 unknown 保留，其余结局删除；确认 / 放弃发出时
     * 标 in_flight，结果未知保留为 unknown，其余结局删除；本地拒绝不登记。
     */
    private class Api : WalletApi {
        private val main = Handler(Looper.getMainLooper())
        val writes = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val submitAnswers = ArrayDeque<ApiResult>()
        val confirmAnswers = ArrayDeque<ApiResult>()
        val abandonAnswers = ArrayDeque<ApiResult>()

        /** rules / 收款账号读取（端口入口同步记录 operationId）。 */
        val reads = mutableListOf<String>()

        @Volatile var readsSettled = 0

        @Volatile var settled = 0

        @Volatile var held: CompletableDeferred<Unit>? = null

        @Volatile var pending: PendingKeyRecord? = null

        /** 已有一笔写请求以成功返回（之后的 rules / 收款账号读取给「成功之后」的内容）。 */
        @Volatile var paid = false

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
                return block().also { if (it is ApiResult.Success) paid = true }
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

        private fun settle(answer: ApiResult): ApiResult {
            pending =
                if (answer is ApiResult.TransportFailure) {
                    pending?.copy(stateRaw = PendingKeyStore.UNKNOWN, everUnknown = true)
                } else {
                    null
                }
            return answer
        }

        private fun read(operationId: String): ApiResult {
            reads += operationId
            try {
                return when (operationId) {
                    WithdrawController.RULES_OPERATION -> ok(if (paid) RULES_AFTER else RULES_BEFORE)
                    else -> ok(if (paid) PAYOUT_AFTER else PAYOUT_BEFORE)
                }
            } finally {
                main.post { readsSettled += 1 }
            }
        }

        override suspend fun send(call: ApiCall): ApiResult =
            when (call.operationId) {
                WithdrawController.RULES_OPERATION, WithdrawController.PAYOUT_OPERATION -> read(call.operationId)
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

    /** 页面实际使用的文案字典（与 WithdrawScreen 渲染时同一个 deps.texts；entryId = null，不登记任何页面钩子）。 */
    private fun texts(wallet: AppWallet): WalletTexts =
        wallet.config(RouteDestination("Withdraw", "{}", false), null, onBack = {}).deps.texts

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

    /** 等此刻已发起的 rules / 收款账号读取全部连同调用方的回调处理都已结算。 */
    private fun readsSettled(api: Api) {
        val target = api.reads.size
        FlowWait.until(::idle, { "读取结算 ${api.readsSettled}/$target：${api.reads}" }) { api.readsSettled >= target }
        idle()
    }

    /** 有限等待某个条件成立（到上限不抛），返回是否成立；调用方再用带说明的断言判定，使红灯落在预期断言上。 */
    private fun within(condition: () -> Boolean): Boolean =
        runCatching { FlowWait.until(::idle, ceilingMs = WITHIN_MS, condition = condition) }.isSuccess

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

    /** 用户在本页可做的全部「再发」尝试：直接再点、清空重输同一金额再点、改金额再点（每次取此刻渲染的版本与表单身份）。 */
    private fun tryAgain(
        model: WithdrawViewModel,
        api: Api,
    ) {
        val controller = model.controller
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
        typeAndSubmit(model)
        typeAndSubmit(model, "11")
        writesSettled(api)
    }

    private fun record(key: String) =
        PendingKeyRecord(
            key = key,
            operationId = WithdrawController.OPERATION,
            body = SUMMARY,
            account = USER,
            summary = SUMMARY,
            stateRaw = PendingKeyStore.UNKNOWN,
            everUnknown = true,
        )

    private fun snapshotOf(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun shownRules(holder: Holder): Long? =
        holder.model.controller.state.value.rules
            ?.withdrawableFen

    private fun shownPayout(holder: Holder): String? =
        holder.model.controller.state.value.payout
            ?.maskedAccount

    // ---------------------------------------------------------------- R1-01：换代后旧表单迟到成功的可见性

    private enum class Path(
        val label: String,
        val write: String,
    ) {
        SUBMIT("首次提交", WithdrawController.OPERATION),
        CONFIRM("确认上一笔", "confirm:${WithdrawController.OPERATION}"),
        ABANDON("放弃得原成功", "abandon:${WithdrawController.OPERATION}"),
    }

    /** 按 [path] 发起这一笔写请求（挂在 [Api.held] 上）。确认 / 放弃作用于页面上待确认卡的那条记录（key-earlier）。 */
    private fun start(
        path: Path,
        holder: Holder,
    ) {
        val flow = holder.model.controller.flow
        when (path) {
            Path.SUBMIT -> {
                typeAndSubmit(holder.model)
            }

            Path.CONFIRM -> {
                val key =
                    flow.state.value.pending
                        ?.key
                assertEquals("前提：待确认卡", EARLIER, key)
                flow.confirm(key!!, flow.owner)
                idle()
            }

            Path.ABANDON -> {
                val key =
                    flow.state.value.pending
                        ?.key
                assertEquals("前提：待确认卡", EARLIER, key)
                flow.askAbandon(key!!, flow.owner)
                flow.abandon(key, flow.owner)
                idle()
            }
        }
    }

    /**
     * 让旧表单这一笔写请求挂着、同账号分区换代（E1→E2，App 判换代、表单按新快照重建，前提断言表单身份对象已换），等新表单的首轮读取结算后放回旧结局。
     * 返回放回之前已发起的读取个数。
     */
    @Suppress("LongParameterList") // 换代窗口的各个参与方逐项给
    private fun rotateThenRelease(
        api: Api,
        wallet: AppWallet,
        holder: Holder,
        rotate: () -> Unit,
        gate: CompletableDeferred<Unit>,
        label: String,
    ): Int {
        val flow = holder.model.controller.flow
        val before = flow.owner
        rotate()
        wallet.onSessionChanged()
        idle()
        assertTrue("$label 前提：表单按换代快照重建", flow.owner !== before)
        readsSettled(api)
        assertEquals("$label 前提：新表单 rules 已加载", WithdrawSnapshot.CONTENT, holder.model.controller.state.value.phase)
        assertEquals("$label 前提：旧请求仍挂着", 0, api.settled)
        assertEquals("$label 前提：新表单此刻显示的是成功之前的余额", BEFORE_FEN, shownRules(holder))
        api.held = null
        val readsBefore = api.reads.size
        gate.complete(Unit)
        writesSettled(api)
        return readsBefore
    }

    /**
     * F-AND-45 R1-01：三种写路径（首次提交、确认上一笔、放弃得原成功）各自在途时，同账号分区换代、表单重建；之后旧表单的成功迟到。期望：
     * - 新表单补一次 rules 与收款账号重取（等同 onSettled）：成功落地之后端口收到了 rules 与收款账号读取，且新表单显示的是重取到的「成功之后」的余额与账号行；
     * - 新表单给中性提示「上一笔提现已提交，可在提现记录查看」：页面的一次性轻提示（FlowState.toast，WalletToast 渲染、不是错误样式），按页面同一个
     *   deps.texts 取出的文字完全相等；
     * - 维持 FU-AND-0719 口径：新表单不锁（successPending=false），没有错误条和字段错误。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // 三条路径逐项，失败汇总后一次断言
    fun fand45_r101_aLateSuccessOfTheReplacedFormRefetchesRulesAndPayoutAndShowsTheNeutralHint() {
        val misses = mutableListOf<String>()
        for (path in Path.entries) {
            val api = Api()
            if (path != Path.SUBMIT) api.pending = record(EARLIER)
            var current = identity
            val wallet = appWallet(api) { current }
            val texts = texts(wallet)
            val holder = opened(wallet)
            val controller = holder.model.controller
            val flow = controller.flow
            val gate = CompletableDeferred<Unit>()
            api.held = gate
            start(path, holder)
            assertEquals("${path.label} 前提：已发出 1 笔", listOf(path.write), api.writes)
            val readsBefore = rotateThenRelease(api, wallet, holder, { current = identity.copy(partition = E2) }, gate, path.label)
            assertTrue("${path.label} 前提：旧表单这一笔以成功返回", api.paid)
            within { api.readsSettled >= api.reads.size && shownRules(holder) == AFTER_FEN && shownPayout(holder) == AFTER_ACCOUNT }
            readsSettled(api)
            val later = api.reads.drop(readsBefore)
            if (WithdrawController.RULES_OPERATION !in later) misses += "${path.label}：旧表单迟到成功后新表单没有重取 rules，之后的读取 $later"
            if (WithdrawController.PAYOUT_OPERATION !in later) misses += "${path.label}：旧表单迟到成功后新表单没有重取收款账号，之后的读取 $later"
            if (shownRules(holder) != AFTER_FEN) misses += "${path.label}：新表单没有显示重取到的余额，实为 ${shownRules(holder)}"
            if (shownPayout(holder) != AFTER_ACCOUNT) misses += "${path.label}：新表单没有显示重取到的收款账号，实为 ${shownPayout(holder)}"
            val flowState = flow.state.value
            val hint = flowState.toast?.let { texts.text(it.key) }
            if (hint != HINT) misses += "${path.label}：没有中性提示「$HINT」，轻提示为 ${flowState.toast?.key}=「$hint」"
            if (flowState.error != null) misses += "${path.label}：新表单显示错误条 ${flowState.error}"
            if (flowState.fieldErrors.isNotEmpty() || flowState.fieldErrorKey != null) misses += "${path.label}：新表单显示字段错误"
            if (controller.state.value.successPending) misses += "${path.label}：旧表单的迟到成功锁住了新表单"
            // 第 2 版（评审 T-01）：迟到结果、补重取及相关回调全部结算之后，写请求仍恰好是旧表单那 1 笔（不得为了确认结果再发确认 / 放弃 / 提交）
            writesSettled(api)
            readsSettled(api)
            val writesAfter = api.writes.toList()
            if (writesAfter != listOf(path.write)) misses += "${path.label}：迟到成功落地后多出写请求，应恰好 [${path.write}]，实为 $writesAfter"
            holder.clear()
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    private enum class LateEnd(
        val label: String,
        val path: Path,
    ) {
        SUBMIT_FIELD("首次提交得 20001", Path.SUBMIT),
        SUBMIT_30306("首次提交得 30306", Path.SUBMIT),
        SUBMIT_TIMEOUT("首次提交超时（结果未知）", Path.SUBMIT),
        CONFIRM_30306("确认得 30306", Path.CONFIRM),
        ABANDON_ABANDONED("放弃得 abandoned", Path.ABANDON),

        /** 第 2 版（评审 T-02）：放弃得 completed、原结果为业务失败（同样映射为 ALREADY_DONE，但不是成功）。 */
        ABANDON_COMPLETED_30306("放弃得 completed、原结果 30306", Path.ABANDON),
        ;

        fun result(): ApiResult =
            when (this) {
                SUBMIT_FIELD -> fail(20_001, """{"fields":["amount_fen"]}""")
                SUBMIT_30306, CONFIRM_30306, ABANDON_COMPLETED_30306 -> fail(30_306)
                SUBMIT_TIMEOUT -> ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
                ABANDON_ABANDONED -> ApiResult.Abandoned(EARLIER, "trace-000123")
            }
    }

    /**
     * R1-01 对照：同样在换代窗口里，旧表单迟到的**不是成功**（明确不出款 20001 / 30306 / abandoned，或结果未知）——新表单不给「上一笔提现已提交」的提示
     * （轻提示没有这句），不显示错误条、不锁（FU-AND-0719 口径：旧结局只同步记录）。
     */
    @Test
    fun fand45_r101_aLateNonSuccessOfTheReplacedFormShowsNoSubmittedHint() {
        val misses = mutableListOf<String>()
        for (end in LateEnd.entries) {
            val api = Api()
            when (end.path) {
                Path.SUBMIT -> api.submitAnswers += end.result()
                Path.CONFIRM -> api.confirmAnswers += end.result()
                Path.ABANDON -> api.abandonAnswers += end.result()
            }
            if (end.path != Path.SUBMIT) api.pending = record(EARLIER)
            var current = identity
            val wallet = appWallet(api) { current }
            val texts = texts(wallet)
            val holder = opened(wallet)
            val gate = CompletableDeferred<Unit>()
            api.held = gate
            start(end.path, holder)
            assertEquals("${end.label} 前提：已发出 1 笔", listOf(end.path.write), api.writes)
            rotateThenRelease(api, wallet, holder, { current = identity.copy(partition = E2) }, gate, end.label)
            assertTrue("${end.label} 前提：旧表单这一笔没有成功", !api.paid)
            readsSettled(api)
            val flowState = holder.model.controller.flow.state.value
            val hint = flowState.toast?.let { texts.text(it.key) }
            if (hint == HINT) misses += "${end.label}：旧表单没有成功，新表单却提示「$HINT」"
            if (flowState.error != null) misses += "${end.label}：新表单显示错误条 ${flowState.error}"
            if (holder.model.controller.state.value.successPending) misses += "${end.label}：新表单被锁"
            holder.clear()
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    // ---------------------------------------------------------------- R1-03：在途标记只由设锁的那个键释放

    private enum class Release(
        val label: String,
        /** 释放动作作用的是当初设锁（结果未知）的那个键。 */
        val sameKey: Boolean,
    ) {
        OTHER_CONFIRM_30306("别的键：确认得 30306", false),
        OTHER_ABANDON_ABANDONED("别的键：放弃得 abandoned", false),
        OTHER_ABANDON_COMPLETED_30306("别的键：放弃得 completed、原结果 30306", false),
        SAME_CONFIRM_30306("同一个键：确认得 30306（对照）", true),
        SAME_ABANDON_ABANDONED("同一个键：放弃得 abandoned（对照）", true),
        ;

        val abandon: Boolean get() =
            this == OTHER_ABANDON_ABANDONED || this == OTHER_ABANDON_COMPLETED_30306 ||
                this == SAME_ABANDON_ABANDONED

        fun answer(key: String): ApiResult =
            when (this) {
                OTHER_CONFIRM_30306, OTHER_ABANDON_COMPLETED_30306, SAME_CONFIRM_30306 -> fail(30_306)
                OTHER_ABANDON_ABANDONED, SAME_ABANDON_ABANDONED -> ApiResult.Abandoned(key, "trace-000123")
            }
    }

    /**
     * F-AND-45 R1-03：本表单首次提交键 A 结果未知（超时，记录保留为 unknown；在途标记由 A 设下）。之后在**活页面**上，另一个键得到明确不出款并被采用：
     * 本机记录换成另一条（key-other，例如别处留下的），回前台重读后卡上是它；对它【确认上一笔】得 30306、【确定放弃】得 abandoned 或 completed(30306)。
     * （活页面上 A 结果未知之后，用户再提交沿用的仍是键 A——产品不给新键——所以「新发的另一笔」构造不出来，不测。）
     * 然后 ON_PAUSE、ViewModel 被清除，按「不保留活动·同一 SavedStateHandle」「进程回收·结局之后的 Bundle 快照」重建：A 设下的在途标记不得被别的键的结局
     * 清掉——重建本身不发写请求，直接提交、重输、改金额都不发（与 FU-AND-0719 裁定④一致：结果未知后记录消失仍锁到离页）。
     * 对照（同一个键）：对 A 本身【确认上一笔】得 30306 / 【确定放弃】得 abandoned——照常释放，重建后用户输入后点【提交】恰好多出 1 次 createWithdrawal。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 结局 × 重建方式逐项遍历，失败汇总后一次断言
    fun fand45_r103_aDefiniteNoPayoutOfAnotherKeyDoesNotReleaseTheLockSetByTheUnknownWrite() {
        val misses = mutableListOf<String>()
        for (release in Release.entries) {
            for ((processDeath, bundle) in listOf(false to false, true to true)) {
                val label = "${release.label}·" + (if (processDeath) "进程回收·Bundle 快照" else "不保留活动·同一对象")
                val api = Api()
                api.submitAnswers += ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
                val wallet = appWallet(api)
                val saved = SavedStateHandle()
                val first = opened(wallet, saved)
                val controller = first.model.controller
                val flow = controller.flow
                typeAndSubmit(first.model)
                writesSettled(api)
                val keyA = api.keys.single()!!
                assertEquals("$label 前提：A 的记录仍在（unknown）", PendingKeyStore.UNKNOWN, api.pending?.stateRaw)
                assertEquals(
                    "$label 前提：卡上是 A",
                    keyA,
                    flow.state.value.pending
                        ?.key,
                )
                val target =
                    if (release.sameKey) {
                        keyA
                    } else {
                        // 本机记录换成另一条（别处留下的 key-other），回前台重读
                        api.pending = record(OTHER)
                        controller.onResume()
                        idle()
                        OTHER
                    }
                assertEquals(
                    "$label 前提：卡上是 $target",
                    target,
                    flow.state.value.pending
                        ?.key,
                )
                if (release.abandon) api.abandonAnswers += release.answer(target) else api.confirmAnswers += release.answer(target)
                if (release.abandon) {
                    flow.askAbandon(target, flow.owner)
                    flow.abandon(target, flow.owner)
                } else {
                    flow.confirm(target, flow.owner)
                }
                idle()
                writesSettled(api)
                assertEquals("$label 前提：发出 1 次${if (release.abandon) "放弃" else "确认"}", 2, api.writes.size)
                assertEquals("$label 前提：发给的是 $target", target, api.keys[1])
                assertEquals("$label 前提：结局之后没有未决记录", null, api.pending)
                controller.onVisibility(false)
                val snapshot = snapshotOf(saved)
                first.clear()
                idle()
                val sent = api.writes.toList()
                val second = opened(if (processDeath) appWallet(api) else wallet, if (bundle) snapshot else saved)
                writesSettled(api)
                if (api.writes != sent) misses += "$label：重建本身发出了写请求 ${api.writes}"
                if (release.sameKey) {
                    if (second.model.controller.state.value.successPending) misses += "$label：设锁的那个键明确不出款之后重建仍被锁"
                    typeAndSubmit(second.model)
                    writesSettled(api)
                    if (api.writes != sent + WithdrawController.OPERATION) misses += "$label：重建后主动提交应恰好多 1 次，实为 ${api.writes}"
                } else {
                    tryAgain(second.model, api)
                    if (api.writes != sent) misses += "$label：别的键的明确不出款清掉了 A 设下的在途标记，重建后再发了 ${api.writes.drop(sent.size)}"
                }
                second.clear()
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * 第 2 版（评审 T-03）：设锁的键必须随保存状态恢复。首次提交键 A 超时（记录 unknown，在途标记由 A 设下）后**先**保存并重建（ViewModel 被清除，按
     * 「不保留活动·同一 SavedStateHandle」「进程回收·Bundle 快照」两种）；在重建后的页面上：
     * - 别的键：本机记录换成 key-other，回前台重读后卡上是它；对它确认得 30306 / 放弃得 abandoned / 放弃得 completed(30306)；
     * - 对照（同一个键）：对恢复出来的卡上的 A 确认得 30306 / 放弃得 abandoned。
     * 结局采用后再 ON_PAUSE、保存并第二次重建：别的键的结局之后仍锁（重建本身不发，直接提交、重输、改金额都不发）；同一个键的对照照常释放，主动提交恰好多 1 次。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 结局 × 重建方式逐项遍历，失败汇总后一次断言
    fun fand45_r103_t03_theLockingKeySurvivesARecreationBeforeAnotherKeyIsSettled() {
        val misses = mutableListOf<String>()
        for (release in Release.entries) {
            for ((processDeath, bundle) in listOf(false to false, true to true)) {
                val label = "先重建·${release.label}·" + (if (processDeath) "进程回收·Bundle 快照" else "不保留活动·同一对象")
                val api = Api()
                api.submitAnswers += ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
                val wallet = appWallet(api)
                val saved = SavedStateHandle()
                val first = opened(wallet, saved)
                typeAndSubmit(first.model)
                writesSettled(api)
                val keyA = api.keys.single()!!
                assertEquals("$label 前提：A 的记录仍在（unknown）", PendingKeyStore.UNKNOWN, api.pending?.stateRaw)
                // A 结果未知之后先保存、重建
                first.model.controller.onVisibility(false)
                val firstSnapshot = snapshotOf(saved)
                first.clear()
                idle()
                val secondSaved = if (bundle) firstSnapshot else saved
                val second = opened(if (processDeath) appWallet(api) else wallet, secondSaved)
                writesSettled(api)
                assertEquals("$label 前提：重建本身不发写请求", 1, api.writes.size)
                val controller = second.model.controller
                val flow = controller.flow
                assertEquals(
                    "$label 前提：重建后卡上是 A",
                    keyA,
                    flow.state.value.pending
                        ?.key,
                )
                val target =
                    if (release.sameKey) {
                        keyA
                    } else {
                        api.pending = record(OTHER)
                        controller.onResume()
                        idle()
                        OTHER
                    }
                assertEquals(
                    "$label 前提：卡上是 $target",
                    target,
                    flow.state.value.pending
                        ?.key,
                )
                if (release.abandon) api.abandonAnswers += release.answer(target) else api.confirmAnswers += release.answer(target)
                if (release.abandon) {
                    flow.askAbandon(target, flow.owner)
                    flow.abandon(target, flow.owner)
                } else {
                    flow.confirm(target, flow.owner)
                }
                idle()
                writesSettled(api)
                assertEquals("$label 前提：发出 1 次${if (release.abandon) "放弃" else "确认"}", 2, api.writes.size)
                assertEquals("$label 前提：发给的是 $target", target, api.keys[1])
                assertEquals("$label 前提：结局之后没有未决记录", null, api.pending)
                controller.onVisibility(false)
                val secondSnapshot = snapshotOf(secondSaved)
                second.clear()
                idle()
                val sent = api.writes.toList()
                val third = opened(if (processDeath) appWallet(api) else wallet, if (bundle) secondSnapshot else secondSaved)
                writesSettled(api)
                if (api.writes != sent) misses += "$label：第二次重建本身发出了写请求 ${api.writes}"
                if (release.sameKey) {
                    if (third.model.controller.state.value.successPending) misses += "$label：恢复后 A 本身明确不出款，第二次重建仍被锁"
                    typeAndSubmit(third.model)
                    writesSettled(api)
                    if (api.writes != sent + WithdrawController.OPERATION) misses += "$label：第二次重建后主动提交应恰好多 1 次，实为 ${api.writes}"
                } else {
                    tryAgain(third.model, api)
                    if (api.writes != sent) misses += "$label：恢复后别的键的明确不出款清掉了 A 设下的在途标记，再发了 ${api.writes.drop(sent.size)}"
                }
                third.clear()
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * 第 3 版（评审 T-05）：「重建前已换键」。首次提交键 A 超时（记录 unknown，在途标记由 A 设下）→ 保存状态、销毁旧实例 → 在旧实例不在的这段时间里本机记录
     * 换成 key-other（别处留下的）→ 按「不保留活动·同一 SavedStateHandle」「进程回收·Bundle 快照」恢复，恢复出的卡上就是 key-other → 对它确认得 30306 /
     * 放弃得 abandoned / 放弃得 completed(30306) → 结局采用后 ON_PAUSE、第二次重建：仍锁——重建本身不发，结算后直接提交、重输、改金额都不增加写请求
     * （设锁的键是 A，不能按恢复那一刻的当前记录反推）。对照（同一个键）：记录不换，恢复后对卡上的 A 确认得 30306 / 放弃得 abandoned，照常释放，第二次重建
     * 后主动提交恰好多 1 次。
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 结局 × 重建方式逐项遍历，失败汇总后一次断言
    fun fand45_r103_t05_aKeySwappedBeforeTheRecreationIsNotTakenAsTheLockingKey() {
        val misses = mutableListOf<String>()
        for (release in Release.entries) {
            for ((processDeath, bundle) in listOf(false to false, true to true)) {
                val label = "重建前已换键·${release.label}·" + (if (processDeath) "进程回收·Bundle 快照" else "不保留活动·同一对象")
                val api = Api()
                api.submitAnswers += ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
                val wallet = appWallet(api)
                val saved = SavedStateHandle()
                val first = opened(wallet, saved)
                typeAndSubmit(first.model)
                writesSettled(api)
                val keyA = api.keys.single()!!
                assertEquals("$label 前提：A 的记录仍在（unknown）", PendingKeyStore.UNKNOWN, api.pending?.stateRaw)
                // 保存状态、销毁旧实例
                first.model.controller.onVisibility(false)
                val firstSnapshot = snapshotOf(saved)
                first.clear()
                idle()
                // 旧实例不在时，本机记录换成另一条（同一个键的对照不换）
                val target = if (release.sameKey) keyA else OTHER
                if (!release.sameKey) api.pending = record(OTHER)
                val secondSaved = if (bundle) firstSnapshot else saved
                val second = opened(if (processDeath) appWallet(api) else wallet, secondSaved)
                writesSettled(api)
                assertEquals("$label 前提：恢复本身不发写请求", 1, api.writes.size)
                val controller = second.model.controller
                val flow = controller.flow
                assertEquals(
                    "$label 前提：恢复后卡上是 $target",
                    target,
                    flow.state.value.pending
                        ?.key,
                )
                if (release.abandon) api.abandonAnswers += release.answer(target) else api.confirmAnswers += release.answer(target)
                if (release.abandon) {
                    flow.askAbandon(target, flow.owner)
                    flow.abandon(target, flow.owner)
                } else {
                    flow.confirm(target, flow.owner)
                }
                idle()
                writesSettled(api)
                assertEquals("$label 前提：发出 1 次${if (release.abandon) "放弃" else "确认"}", 2, api.writes.size)
                assertEquals("$label 前提：发给的是 $target", target, api.keys[1])
                assertEquals("$label 前提：结局之后没有未决记录", null, api.pending)
                controller.onVisibility(false)
                val secondSnapshot = snapshotOf(secondSaved)
                second.clear()
                idle()
                val sent = api.writes.toList()
                val third = opened(if (processDeath) appWallet(api) else wallet, if (bundle) secondSnapshot else secondSaved)
                writesSettled(api)
                if (api.writes != sent) misses += "$label：第二次重建本身发出了写请求 ${api.writes}"
                if (release.sameKey) {
                    if (third.model.controller.state.value.successPending) misses += "$label：恢复后 A 本身明确不出款，第二次重建仍被锁"
                    typeAndSubmit(third.model)
                    writesSettled(api)
                    if (api.writes != sent + WithdrawController.OPERATION) misses += "$label：第二次重建后主动提交应恰好多 1 次，实为 ${api.writes}"
                } else {
                    tryAgain(third.model, api)
                    if (api.writes != sent) misses += "$label：恢复时把当前记录当成设锁键，别的键的明确不出款清掉了 A 的在途标记，再发了 ${api.writes.drop(sent.size)}"
                }
                third.clear()
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
        const val EARLIER = "key-earlier"
        const val OTHER = "key-other"
        const val SUMMARY = """{"amount_fen":1000}"""
        const val HINT = "上一笔提现已提交，可在提现记录查看"
        const val WITHIN_MS = 3_000L
        const val BEFORE_FEN = 1234L
        const val AFTER_FEN = 234L
        const val AFTER_ACCOUNT = "138****0002"
        const val RULES_BEFORE =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
        const val RULES_AFTER =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":234,"withdrawable_fen":234,""" +
                """"daily_remaining":0,"monthly_remaining":9,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":null,""" +
                """"quick_amounts_fen":[]}"""
        const val PAYOUT_BEFORE =
            """{"payout_method":"alipay","masked_account":"138****0001","masked_payee_name":"**甲","change_remaining_this_month":1}"""
        const val PAYOUT_AFTER =
            """{"payout_method":"alipay","masked_account":"138****0002","masked_payee_name":"**甲","change_remaining_this_month":1}"""
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
