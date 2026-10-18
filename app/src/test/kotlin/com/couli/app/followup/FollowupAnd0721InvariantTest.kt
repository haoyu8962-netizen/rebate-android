@file:Suppress("MagicNumber")

package com.couli.app.followup

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import com.couli.app.FlowLedger
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
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.core.network.SessionIdentity
import com.couli.core.router.GuardDecision
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawViewModel
import java.time.Duration
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0721：笛卡尔积 + 身份读取位置的工作队列穷举。只驱动生产 AppWallet / ViewModel / 页面回调。
 * 预言只看端口入口历史与实际交回的服务端终态，不用 canSubmit / successPending / 内存锁推导是否允许新键。
 * 参数用字符串越过 Robolectric 类加载器边界；每个 JUnit 名称、每个子轨迹失败都带完整坐标。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@Suppress("LargeClass", "TooManyFunctions") // 矩阵执行器与不依赖产品状态机的端口设施放在同一测试文件
class FollowupAnd0721InvariantTest(
    entryName: String,
    identityName: String,
    answerName: String,
    cutName: String,
    witnessName: String,
    deliveryName: String,
) {
    private val entry = Entry.valueOf(entryName)
    private val identity = Identity.valueOf(identityName)
    private val answer = Answer.valueOf(answerName)
    private val cut = Cut.valueOf(cutName)
    private val witness = Witness.valueOf(witnessName)
    private val delivery = Delivery.valueOf(deliveryName)
    private val coordinate = "entry=$entry identity=$identity A=$answer kill=$cut witness=$witness delivery=$delivery"
    private val context: Context = ApplicationProvider.getApplicationContext()

    // 有记录的首次表单也覆盖“页面先打开、另一入口随后持久化 A”：页面从没显示过 A 的卡片。
    private val lateRecord =
        entry == Entry.FIRST_FORM && witness == Witness.OBSERVER &&
            (answer == Answer.PROCESSING || (delivery == Delivery.THIS_PAGE && answer != Answer.MISSING))

    private enum class Entry { FIRST_FORM, SAME_HANDLE, BUNDLE, NEW_PROCESS, ON_RESUME, BEFORE_SUBMIT, RECLICK, EDIT_RECLICK }

    private enum class Identity { KNOWN, UNKNOWN_ONCE, UNKNOWN_CONTINUOUS, READ_FAILURE }

    private enum class Answer { PROCESSING, COMPLETED, ABANDONED, MISSING }

    private enum class Cut { BEFORE_KEY_WRITE, AFTER_KEY_WRITE, AFTER_SUBMISSION }

    private enum class Witness { AUTHOR, OBSERVER }

    private enum class Delivery { THIS_PAGE, ELSEWHERE }

    private enum class Stage { SETUP, ENTRY, INPUT, SUBMIT, RECOVERY, EXIT }

    /** 0 = 本阶段不注入；正数 = 本阶段第 n 次实际身份读取。不是绑定函数名或调用栈的补丁探针。 */
    private data class FaultPlan(
        val entryAt: Int = 0,
        val submitAt: Int = 0,
    )

    private class IdentitySource(
        private val mode: Identity,
        private val plan: FaultPlan,
    ) {
        var stage = Stage.SETUP
        val reads = mutableMapOf<Stage, Int>()
        val injected = mutableListOf<String>()
        private var continuous = false

        fun read(): SessionIdentity {
            val index = (reads[stage] ?: 0) + 1
            reads[stage] = index
            val selected =
                when (stage) {
                    Stage.ENTRY -> index == plan.entryAt
                    Stage.SUBMIT -> index == plan.submitAt
                    else -> false
                }
            val armed = stage == Stage.ENTRY || stage == Stage.SUBMIT || stage == Stage.INPUT
            if (!armed || mode == Identity.KNOWN) return STABLE
            if (selected && mode != Identity.UNKNOWN_ONCE) continuous = true
            // ApiClient.sessionIdentity 在原始存储读取失败时返回 unknown(epoch)，不把异常抛给页面。
            // 暂时未知保留账号/分区，以便断言没有假造换账号、换世代；读取失败则整份身份读不出。
            return if (selected || continuous) {
                injected += "$stage#$index"
                if (mode == Identity.READ_FAILURE) SessionIdentity.unknown(STABLE.loginEpoch) else STABLE.copy(known = false)
            } else {
                STABLE
            }
        }
    }

    private data class Event(
        val kind: String,
        val key: String?,
        val body: String?,
        val stage: Stage,
    )

    /** 独立的轨迹预言：端口入口先落日志，随后才允许 Store 检查、挂起、模拟服务端或交回结果。 */
    private class History {
        val events = mutableListOf<Event>()
        val violations = mutableListOf<String>()
        var original: String? = null
        var originalBody: String? = null
        var noPaymentDelivered = false
        var completedDelivered = false
        var exit = false

        fun enter(
            kind: String,
            key: String?,
            body: String?,
            stage: Stage,
        ) {
            events += Event(kind, key, body, stage)
            if (kind != CREATE && kind != CONFIRM) return
            if (key.isNullOrBlank()) violations += "资金端口出现空键"
            if (original == null && kind == CREATE) {
                original = key
                originalBody = body
            }
            if (!exit) {
                val replacementAllowed = noPaymentDelivered && !completedDelivered
                if (key != original && !replacementAllowed) {
                    violations += "A 未交回明确不出款终态就进入第二个键：${events.last()}"
                }
                if (key == original && body != null && body != originalBody) {
                    violations += "A 被换成新金额/请求体：${events.last()} 原体=$originalBody"
                }
            }
        }

        fun delivered(
            key: String,
            result: ApiResult,
            stage: Stage,
        ) {
            events += Event("reply:${result.javaClass.simpleName}", key, null, stage)
            if (key != original) return
            if (result is ApiResult.Abandoned || (result is ApiResult.Failure && result.code == 20903)) noPaymentDelivered = true
            if (result is ApiResult.Success) completedDelivered = true
        }

        fun creates(): List<Event> = events.filter { it.kind == CREATE }

        fun withdrawalKeys(): Set<String?> = events.filter { it.kind == CREATE || it.kind == CONFIRM }.map { it.key }.toSet()
    }

    /** 真正挂在生产端口里面的杀进程窗口；不借用返回结果或写页面私有标记模拟中断。 */
    private class CrashWindow(
        val cut: Cut,
    ) {
        val reached = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()

        suspend fun checkpoint(point: Cut) {
            if (cut != point) return
            reached.complete(Unit)
            stop.await()
        }
    }

    private class Api(
        val backend: MemoryPendingKeyBackend,
        val history: History,
        val identity: IdentitySource,
    ) : WalletApi {
        val pending = PendingKeyStore(backend) { NOW }
        val ledger = FlowLedger()
        var crash: CrashWindow? = null
        var server = Answer.PROCESSING
        var seedUnknown = false
        var accepted = 0

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending.pendingFor(operationId, USER)

        override fun restorePending(): Boolean {
            pending.coldStart(USER)
            return true
        }

        override suspend fun send(call: ApiCall): ApiResult {
            if (call.operationId == WithdrawController.OPERATION) {
                history.enter(CREATE, call.idempotencyKey, call.body, identity.stage)
            }
            return ledger.around(call) {
                when (call.operationId) {
                    WithdrawController.OPERATION -> create(call)
                    WithdrawController.RULES_OPERATION -> ok(RULES)
                    WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT)
                    "getMe" -> ok("""{"user_id":"user-a","phone_bound":true,"realname_status":"verified"}""")
                    else -> error("矩阵未登记端口：${call.operationId}")
                }
            }
        }

        private suspend fun create(call: ApiCall): ApiResult {
            val key = requireNotNull(call.idempotencyKey)
            crash?.checkpoint(Cut.BEFORE_KEY_WRITE)
            if (!pending.prepareSend(call.operationId, key, call.body.orEmpty(), USER, call.summary.orEmpty())) {
                return ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
            }
            crash?.checkpoint(Cut.AFTER_KEY_WRITE)
            accepted += 1
            crash?.checkpoint(Cut.AFTER_SUBMISSION)
            val result = if (seedUnknown) unknown(key) else response(key)
            return finish(key, result)
        }

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = resolve(operationId, target, abandon = false)

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = resolve(operationId, target, abandon = true)

        private suspend fun resolve(
            operationId: String,
            target: PendingTarget,
            abandon: Boolean,
        ): ApiResult {
            val kind = if (abandon) ABANDON else CONFIRM
            history.enter(kind, target.key, null, identity.stage)
            return ledger.around(ApiCall(kind, idempotencyKey = target.key)) {
                val record = pendingFor(operationId)
                if (record?.key != target.key) {
                    ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
                } else {
                    if (!abandon && record.body != history.originalBody && target.key == history.original) {
                        history.violations += "确认 A 未使用原体：${record.body}"
                    }
                    val held = if (abandon) pending.abandon(target.key) else pending.confirm(target.key)
                    if (held) {
                        finish(
                            target.key,
                            response(target.key, abandon),
                        )
                    } else {
                        ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
                    }
                }
            }
        }

        private fun response(
            key: String,
            abandon: Boolean = false,
        ): ApiResult =
            if (history.exit || key != history.original) {
                ok("""{"withdrawal_id":"W-PROP-NEXT"}""")
            } else {
                when (server) {
                    Answer.PROCESSING -> failure(40901)
                    Answer.COMPLETED -> ok("""{"withdrawal_id":"W-PROP-A"}""")
                    Answer.ABANDONED -> if (abandon) ApiResult.Abandoned(key, TRACE) else failure(20903)
                    Answer.MISSING -> unknown(key)
                }
            }

        private fun finish(
            key: String,
            result: ApiResult,
        ): ApiResult {
            val kind =
                when (result) {
                    is ApiResult.Success -> PendingKeyStore.ENVELOPE_SUCCESS
                    is ApiResult.Abandoned -> PendingKeyStore.ABANDONED
                    is ApiResult.Failure -> PendingKeyStore.envelopeKind(result.code)
                    is ApiResult.TransportFailure -> PendingKeyStore.NO_ENVELOPE
                    else -> error("未登记结局 $result")
                }
            pending.onResponse(key, kind)
            history.delivered(key, result, identity.stage)
            return result
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private class Navigation : RouteNavigator {
        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) = Unit

        override fun showUpgradePrompt(request: RouteRequest) = error("意外升级：$request")

        override fun requestConsent(request: RouteRequest) = error("意外隐私引导：$request")

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) = error("意外路由阻断：$request $decision")
    }

    private inner class Rig(
        val api: Api,
    ) {
        var now = NOW
        private val routing =
            AppRouting(
                context,
                EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
                initialUpdate = UpdateCheck.OK,
            ).also {
                it.updateSession(SessionState.PHONE_BOUND, resumePending = false)
                it.attach(Navigation())
            }
        val wallet =
            AppWallet(
                api = api,
                store = null,
                routing = routing,
                sessionEpoch = { STABLE.loginEpoch },
                account = { USER },
                writer = { true },
                forceUpdate = {},
                cache = null,
                mask = null,
                realname = null,
                labor = null,
                stepUpPhone = { "13800005678" },
                clock = { now },
                partitionTracked = true,
                identity = api.identity::read,
            ).also { it.connectSession() }
        val pages = mutableListOf<Holder>()

        fun open(saved: SavedStateHandle = SavedStateHandle()): Holder {
            val page = Holder(wallet, saved, "prop-${pages.size}")
            pages += page
            page.model.controller.onVisibility(true)
            settle(page)
            return page
        }

        fun settle(page: Holder) {
            FlowWait.until(::idle, { "端口 ${api.ledger.settled { true }}/${api.ledger.started { true }}；${api.history.events}" }) {
                api.ledger.settled { true } == api.ledger.started { true } && !page.model.controller.flow.state.value.operationInFlight
            }
            idle()
        }

        fun advance(page: Holder) {
            now += 10_001L
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_001L))
            settle(page)
        }

        fun close() {
            pages.forEach(Holder::clear)
            api.crash?.stop?.cancel()
            idle()
        }
    }

    private class Holder(
        wallet: AppWallet,
        val saved: SavedStateHandle,
        entry: String,
    ) {
        private val store = ViewModelStore()
        val model =
            ViewModelProvider.create(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(
                        modelClass: KClass<T>,
                        extras: CreationExtras,
                    ): T = WithdrawViewModel(wallet.config(RouteDestination("Withdraw", "{}", false), entry, onBack = {}), saved) as T
                },
            )[WithdrawViewModel::class]

        fun clear() {
            model.controller.leave()
            store.clear()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @SuppressLint("RestrictedApi")
    private fun bundleSnapshot(saved: SavedStateHandle): SavedStateHandle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(saved.savedStateProvider().saveState())
            val bytes = parcel.marshall()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(requireNotNull(parcel.readBundle(javaClass.classLoader)), null)
        } finally {
            parcel.recycle()
        }
    }

    private fun amount(
        page: Holder,
        value: String = "10",
    ) = page.model.onAmountChange(value, page.model.controller.flow.owner)

    private fun click(page: Holder) =
        page.model.controller.submit(page.model.controller.state.value.version, page.model.controller.flow.owner)

    private fun clearFeedback(page: Holder) {
        val flow = page.model.controller.flow
        if (flow.state.value.riskDialog != null) flow.dismissRisk()
        if (flow.state.value.abandonConfirm) flow.cancelAbandon()
        if (flow.stepUp.showing) flow.stepUp.close()
        flow.state.value.toast
            ?.let { flow.consumeToast(it.id) }
    }

    /**
     * 无人为写 Handle：写键前在用户点击前停；写键后/提交后挂在 API 内，抓实际 Bundle，取消旧进程的悬挂调用。
     * 新进程只共享后端和观察历史，重新创建 Store/AppWallet。写键前 A 不存在，重启后实际首发才命名为 A。
     */
    private fun seed(
        source: IdentitySource,
        history: History,
        rigs: MutableList<Rig>,
    ): Pair<Rig, Holder> {
        val backend = MemoryPendingKeyBackend()
        val old = Rig(Api(backend, history, source)).also { rigs += it }
        val author = old.open()
        amount(author)
        val window = if (cut == Cut.BEFORE_KEY_WRITE) null else CrashWindow(cut)
        old.api.crash = window
        if (window != null) {
            click(author)
            FlowWait.until(::idle, { "未进入进程中断点 $cut" }) { window.reached.isCompleted }
            assertEquals("中断点持久记录", history.original, backend.list(USER).single().key)
            assertEquals("中断点服务端受理次数", if (cut == Cut.AFTER_SUBMISSION) 1 else 0, old.api.accepted)
        } else {
            assertTrue("写键前没有资金端口调用", history.creates().isEmpty())
            assertTrue("写键前没有持久记录", backend.list(USER).isEmpty())
        }
        val saved = bundleSnapshot(author.saved)
        old.close()
        val next = Rig(Api(backend, history, source)).also { rigs += it }
        val restoredAuthor = next.open(saved)
        if (window == null) {
            next.api.seedUnknown = true
            amount(restoredAuthor)
            click(restoredAuthor)
            next.settle(restoredAuthor)
            next.api.seedUnknown = false
        }
        assertEquals("种子首笔只进入一次 createWithdrawal", 1, history.creates().size)
        assertEquals("恢复后 Store 仍是 A", history.original, next.api.pendingFor(WithdrawController.OPERATION)?.key)
        val target =
            when {
                witness == Witness.AUTHOR -> {
                    restoredAuthor
                }

                lateRecord -> {
                    // 搬移真实 Store 已写出的记录，只制造另一个入口稍晚持久化的时序；不改页面或锁字段。
                    val record = backend.list(USER).single()
                    backend.delete(USER, record.key)
                    val fresh =
                        try {
                            next.open()
                        } finally {
                            backend.write(record)
                        }
                    restoredAuthor.clear()
                    fresh
                }

                else -> {
                    next.open().also { restoredAuthor.clear() }
                }
            }
        // 观察来源由真实 refreshPending 写入；不伪造 unknown_key，也不依赖运行时卡片作为判定预言。
        val expectedUnknown = if (lateRecord) null else history.original
        assertEquals("种子页面的持久关联符合观察时序", expectedUnknown, target.saved.get<String>("withdraw.unknown_key"))
        amount(target)
        next.settle(target)
        return next to target
    }

    private fun enter(
        initial: Pair<Rig, Holder>,
        rigs: MutableList<Rig>,
    ): Pair<Rig, Holder> {
        val (rig, page) = initial
        return when (entry) {
            Entry.SAME_HANDLE, Entry.BUNDLE, Entry.NEW_PROCESS -> {
                val saved = if (entry == Entry.SAME_HANDLE) page.saved else bundleSnapshot(page.saved)
                page.clear()
                val next =
                    if (entry == Entry.NEW_PROCESS) {
                        Rig(Api(rig.api.backend, rig.api.history, rig.api.identity)).also {
                            it.api.server = rig.api.server
                            rigs += it
                        }
                    } else {
                        rig
                    }
                next to next.open(saved)
            }

            Entry.ON_RESUME -> {
                page.model.controller.onVisibility(false)
                page.model.controller.onVisibility(true)
                page.model.controller.onResume()
                rig.settle(page)
                initial
            }

            Entry.EDIT_RECLICK -> {
                amount(page, "11")
                rig.settle(page)
                initial
            }

            Entry.FIRST_FORM, Entry.BEFORE_SUBMIT, Entry.RECLICK -> {
                initial
            }
        }
    }

    /** 一次明确用户意图的恢复与结局；不靠重开表单获得同笔提现的新键许可。 */
    private fun recoverAndResolve(
        rig: Rig,
        page: Holder,
    ) {
        page.model.controller.retry()
        page.model.controller.onResume()
        rig.settle(page)
        rig.advance(page)
        clearFeedback(page)
        val flow = page.model.controller.flow
        val key = requireNotNull(rig.api.history.original)
        // 不用卡片是否存在决定要不要执行：原键按钮回调可以迟到，生产自己验证它仍可受理。
        if (answer == Answer.ABANDONED) {
            flow.askAbandon(key, flow.owner)
            flow.abandon(key, flow.owner)
        } else {
            flow.confirm(key, flow.owner)
        }
        rig.settle(page)
        clearFeedback(page)
        amount(page, "11")
        click(page)
        rig.settle(page)
        clearFeedback(page)
        click(page)
        amount(page, "10")
        click(page)
        rig.advance(page)
    }

    private fun exitControl(
        rig: Rig,
        page: Holder,
    ) {
        val history = rig.api.history
        val key = requireNotNull(history.original)
        // 服务端明确结束旧笔（相当于别页确认/作废），生产 Store 负责落终态；不是拿删除记录当成功依据。
        rig.api.pending.onResponse(key, PendingKeyStore.ABANDONED)
        page.clear()
        val before = history.creates().size
        val used = history.withdrawalKeys()
        history.exit = true
        rig.api.identity.stage = Stage.EXIT
        val fresh = rig.open()
        amount(fresh, "11")
        click(fresh)
        rig.settle(fresh)
        click(fresh)
        amount(fresh, "10")
        click(fresh)
        rig.advance(fresh)
        val writes = history.creates().drop(before)
        assertEquals("离页后全新表单应恰好提交一次", 1, writes.size)
        assertTrue("离页新意图必须用全新键：$writes", writes.single().key !in used)
        assertEquals("新表单金额", """{"amount_fen":1100}""", writes.single().body)
        assertEquals("出口采用的是新笔成功", "W-PROP-NEXT", fresh.saved.get<String>("withdraw.withdrawal_id"))
    }

    private fun publishServerOutcome(
        seedRig: Rig,
        history: History,
    ) {
        seedRig.api.server = answer
        val original = requireNotNull(history.original)
        if (answer == Answer.MISSING) {
            seedRig.api.backend.delete(USER, original)
        } else if (delivery == Delivery.ELSEWHERE) {
            // 另一客户端取得终态、生产 Store 清理记录；旧表单只有 unknown_key，未收到终态回调。
            val kind =
                when (answer) {
                    Answer.COMPLETED -> PendingKeyStore.ENVELOPE_SUCCESS
                    Answer.ABANDONED -> PendingKeyStore.ABANDONED
                    else -> PendingKeyStore.envelopeKind(40901)
                }
            seedRig.api.pending.onResponse(original, kind)
            history.events += Event("external:$answer", original, null, Stage.SETUP)
        }
    }

    private fun checkTransaction(history: History) {
        val next = history.creates().filter { it.key != history.original }
        val mayReplace = answer == Answer.ABANDONED && delivery == Delivery.THIS_PAGE
        if (mayReplace) {
            if (!history.noPaymentDelivered) history.violations += "已知身份恢复后没有取得 A 的明确不出款终态"
            if (next.size != 1) history.violations += "A 已作废后应恰好多一个新键/一次 create，实际=$next"
        } else if (next.isNotEmpty()) {
            history.violations += "A=$answer 时同一表单出现其他键：$next"
        }
        val expectedKeys = if (mayReplace) 2 else 1
        if (history.withdrawalKeys().size != expectedKeys) history.violations += "资金键集合=${history.withdrawalKeys()} 应有 $expectedKeys 个"
    }

    private fun runTrace(
        plan: FaultPlan,
        failures: MutableList<String>,
    ): Map<Stage, Int> {
        val source = IdentitySource(identity, plan)
        val history = History()
        val rigs = mutableListOf<Rig>()
        val label = "$coordinate $plan"
        try {
            val initial = seed(source, history, rigs)
            val (seedRig, seedPage) = initial
            if (entry == Entry.RECLICK || entry == Entry.EDIT_RECLICK) {
                click(seedPage)
                seedRig.settle(seedPage)
            }
            publishServerOutcome(seedRig, history)
            source.stage = Stage.ENTRY
            val (rig, page) = enter(initial, rigs)
            // 输入不是被测提交，先正常填好；故障始终注入真正的提交前补读/身份复核。
            source.stage = Stage.INPUT
            amount(page, if (entry == Entry.EDIT_RECLICK) "11" else "10")
            rig.settle(page)
            source.stage = Stage.SUBMIT
            click(page)
            rig.settle(page)
            // 在显式解决 A 前，任何新的 createWithdrawal 都是错误，包括被 PendingKeyStore 拒绝的入口。
            val beforeResolve = history.creates().map { it.key }.toSet()
            if (beforeResolve != setOf(history.original)) history.violations += "解决 A 前的 create 键集合=$beforeResolve"
            source.stage = Stage.RECOVERY
            recoverAndResolve(rig, page)
            checkTransaction(history)
            // 每个组合即使资金不变式失败，也继续执行出口对照，避免把永久封死提交当成修复。
            exitControl(rig, page)
        } catch (error: AssertionError) {
            history.violations += "设施/出口断言：${error.message}"
        } catch (error: Exception) {
            history.violations += "执行异常：${error.javaClass.simpleName}: ${error.message}"
        } finally {
            source.stage = Stage.SETUP
            rigs.forEach(Rig::close)
        }
        if (history.violations.isNotEmpty()) {
            failures += "$label 注入=${source.injected} 读取=${source.reads}\n${history.violations.joinToString("\n")}\n端口历史=${history.events}"
        }
        return source.reads.toMap()
    }

    /**
     * 写键前的另一个真实边界：A 已生成、Handle 已保存、API 已进入，但 Store 尚未写。
     * 服务端此时不可能有 A，四个服务端状态均尚未生效；不能伪造“查到已作废”来解锁。
     * 这里尚无可供别页观察的记录，所以保留发起页的真实 Handle；所有读取故障位置仍独立穷举。
     */
    private fun runAllocatedTrace(
        plan: FaultPlan,
        failures: MutableList<String>,
    ): Map<Stage, Int> {
        val source = IdentitySource(identity, plan)
        val history = History()
        val rigs = mutableListOf<Rig>()
        try {
            val backend = MemoryPendingKeyBackend()
            val old = Rig(Api(backend, history, source)).also { rigs += it }
            val page = old.open()
            amount(page)
            val window = CrashWindow(Cut.BEFORE_KEY_WRITE)
            old.api.crash = window
            click(page)
            FlowWait.until(::idle) { window.reached.isCompleted }
            assertEquals("已生成但未持久化：端口恰好一次", 1, history.creates().size)
            assertTrue("已生成但未持久化：后端仍为空", backend.list(USER).isEmpty())
            assertEquals("已生成但未持久化：服务端尚未受理", 0, old.api.accepted)
            val saved = bundleSnapshot(page.saved)
            old.close()
            val next = Rig(Api(backend, history, source)).also { rigs += it }
            source.stage = Stage.ENTRY
            val recovered = next.open(saved)
            val (rig, target) = enter(next to recovered, rigs)
            source.stage = Stage.INPUT
            amount(target, "11")
            rig.settle(target)
            source.stage = Stage.SUBMIT
            click(target)
            rig.settle(target)
            source.stage = Stage.RECOVERY
            target.model.controller.retry()
            target.model.controller.onResume()
            rig.settle(target)
            amount(target, "10")
            click(target)
            rig.advance(target)
            if (history.creates().size != 1) history.violations += "未持久化的 A 尚无服务端终态，恢复旧 Handle 又进入资金端口"
            exitControl(rig, target)
        } catch (error: AssertionError) {
            history.violations += "设施/出口断言：${error.message}"
        } catch (error: Exception) {
            history.violations += "执行异常：${error.javaClass.simpleName}: ${error.message}"
        } finally {
            source.stage = Stage.SETUP
            rigs.forEach(Rig::close)
        }
        if (history.violations.isNotEmpty()) {
            failures += "$coordinate allocated-before-store $plan 注入=${source.injected}\n" +
                "${history.violations.joinToString("\n")}\n端口历史=${history.events}"
        }
        return source.reads.toMap()
    }

    @Test
    fun everyReachableIdentityFaultPreservesOneWithdrawalKey() {
        val failures = mutableListOf<String>()
        val traces = exhaust(failures, ::runTrace)
        val allocated = if (cut == Cut.BEFORE_KEY_WRITE) exhaust(failures, ::runAllocatedTrace) else 0
        println("FU-AND-0721-PROP $coordinate traces=$traces allocated=$allocated failures=${failures.size}")
        assertTrue("$coordinate\n${failures.joinToString("\n\n")}", failures.isEmpty())
    }

    private fun exhaust(
        failures: MutableList<String>,
        run: (FaultPlan, MutableList<String>) -> Map<Stage, Int>,
    ): Int {
        val todo = ArrayDeque<FaultPlan>().also { it += FaultPlan() }
        val visited = mutableSetOf<FaultPlan>()
        while (todo.isNotEmpty()) {
            val plan = todo.removeFirst()
            if (!visited.add(plan)) continue
            val reads = run(plan, failures)
            if (identity != Identity.KNOWN) enqueueFaults(todo, plan, reads)
        }
        return visited.size
    }

    /** 对实际走到的读取穷举；故障改变控制流后新增的读取继续入队，两阶段独立叉乘。 */
    private fun enqueueFaults(
        todo: ArrayDeque<FaultPlan>,
        plan: FaultPlan,
        reads: Map<Stage, Int>,
    ) {
        if (plan.entryAt == 0) {
            for (index in 1..(reads[Stage.ENTRY] ?: 0)) todo += plan.copy(entryAt = index)
        }
        if (plan.submitAt == 0) {
            for (index in 1..(reads[Stage.SUBMIT] ?: 0)) todo += plan.copy(submitAt = index)
        }
    }

    companion object {
        private const val USER = "user-a"
        private const val NOW = 1_800_000_000_000L
        private const val TRACE = "trace-prop-0721"
        private const val CREATE = "createWithdrawal"
        private const val CONFIRM = "confirm:createWithdrawal"
        private const val ABANDON = "abandon:createWithdrawal"
        private val STABLE = SessionIdentity(known = true, account = USER, loginEpoch = 1L, partition = "login-1")
        private const val RULES =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":50000,"withdrawable_fen":50000,""" +
                """"daily_remaining":10,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,""" +
                """"est_net_fen":1000,"quick_amounts_fen":[500,1000]}"""
        private const val PAYOUT =
            """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三",""" +
                """"change_remaining_this_month":2}"""

        @JvmStatic
        @Parameters(name = "{index}: entry={0}, identity={1}, A={2}, kill={3}, witness={4}, delivery={5}")
        fun cases(): List<Array<String>> =
            Entry.entries
                .flatMap { entry ->
                    Identity.entries.flatMap { identity ->
                        Answer.entries.flatMap { answer ->
                            Cut.entries.flatMap { cut ->
                                Witness.entries.flatMap { witness ->
                                    Delivery.entries.map { delivery ->
                                        arrayOf(entry.name, identity.name, answer.name, cut.name, witness.name, delivery.name)
                                    }
                                }
                            }
                        }
                    }
                }.also {
                    check(it.size == 8 * 4 * 4 * 3 * 2 * 2)
                    check(it.map { row -> row.toList() }.toSet().size == it.size)
                }

        private fun ok(json: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(json), TRACE, null)

        private fun unknown(key: String): ApiResult = ApiResult.TransportFailure("timeout", null, key, pendingKeyUnknown = true)

        private fun failure(code: Int): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                TRACE,
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )
    }
}
