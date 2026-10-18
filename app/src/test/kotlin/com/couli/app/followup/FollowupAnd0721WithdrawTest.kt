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
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.couli.feature.wallet.WithdrawSnapshot
import com.couli.feature.wallet.WithdrawViewModel
import java.time.Duration
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0721：沿用 0719/0720 的 AppWallet → WithdrawViewModel → 按钮回调测试入口。
 * 写计数和结算沿用 AppWalletIntegrationTest 的 FlowLedger：在 WalletApi 入口记账，返回后的主线程回调结算才作负向断言。
 * 未决记录改用真实 PendingKeyStore，避免替身把 40901 写死为终态或未决态；不复制结局分类、页面恢复或可见性调度。
 * F-AND-47 观察生产页面的 riskDialog 状态及真实 Router 的导航出口，不冒充 Compose/NavHost 渲染验收。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions", "LargeClass") // 每类结局单独报告红绿，共用同一套恢复与端口设施
class FollowupAnd0721WithdrawTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val holders = mutableListOf<Holder>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() {
        holders.forEach(Holder::clear)
        idle()
    }

    private data class Write(
        val operation: String,
        val key: String?,
        val body: String?,
    )

    /** 只挂下一次待确认意图，在端口受理以后、首次读取/占用 Store 之前；其他页的请求不被它挂住。 */
    private class PendingReadWindow {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** Store 已登记在途、端口尚未交回结局；不影响 Q 的读取和其他后续请求。 */
    private class ResponseWindow {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** 只替换服务端回答；存储生命周期调用生产 Store，不自行判断 40901、5xxxx 等是否终结。 */
    private class Api(
        val backend: MemoryPendingKeyBackend = MemoryPendingKeyBackend(),
        val writes: MutableList<Write> = mutableListOf(),
    ) : WalletApi {
        val ledger = FlowLedger()
        val answers = ArrayDeque<ApiResult>()
        val replies = mutableListOf<Pair<String, ApiResult>>()
        val pendingReads = mutableListOf<Int>()
        val pending = PendingKeyStore(backend) { 0L }
        var rules = RULES
        var hold: CompletableDeferred<Unit>? = null
        var holdNextPendingRead: PendingReadWindow? = null
        var holdNextResponse: ResponseWindow? = null
        var submitAnswer: ((ApiCall) -> ApiResult)? = null
        var onWriteEntry: (Write) -> Unit = {}

        private fun recordWrite(write: Write): Int {
            val index = writes.size
            writes += write
            onWriteEntry(write)
            return index
        }

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending.pendingFor(operationId, USER)

        override fun restorePending(): Boolean {
            pending.coldStart(USER)
            return true
        }

        override suspend fun send(call: ApiCall): ApiResult {
            if (call.operationId == WithdrawController.OPERATION) {
                // 必须在挂起、Store 放行和返回结果之前记账；被网络层挡掉的误写也会被测出。
                recordWrite(Write(call.operationId, call.idempotencyKey, call.body))
            }
            return ledger.around(call) {
                when (call.operationId) {
                    WithdrawController.OPERATION -> {
                        val key = requireNotNull(call.idempotencyKey)
                        if (!pending.prepareSend(call.operationId, key, call.body.orEmpty(), USER, call.summary.orEmpty())) {
                            ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED)
                        } else {
                            answer(key, submitAnswer?.invoke(call))
                        }
                    }

                    WithdrawController.RULES_OPERATION -> {
                        ok(rules)
                    }

                    WithdrawController.PAYOUT_OPERATION -> {
                        ok(PAYOUT)
                    }

                    "sendSmsCode" -> {
                        ok("""{"resend_after_sec":60,"expires_in_sec":300}""")
                    }

                    "stepUp" -> {
                        ok("""{"step_up_token":"fixture-token","expire_at":"2099-01-01T00:00:00Z"}""")
                    }

                    "getMe" -> {
                        ok("""{"user_id":"user-a","phone_bound":true,"realname_status":"verified"}""")
                    }

                    else -> {
                        error("未登记的端口调用：${call.operationId}")
                    }
                }
            }
        }

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = pendingAction(operationId, target, abandon = false)

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = pendingAction(operationId, target, abandon = true)

        private suspend fun pendingAction(
            operationId: String,
            target: PendingTarget,
            abandon: Boolean,
        ): ApiResult {
            val operation = if (abandon) "abandon:$operationId" else "confirm:$operationId"
            // 键与入口次数同步登记；此处不能为观察 body 提前读记录，body 只在实际读取后补入观察日志。
            val index = recordWrite(Write(operation, target.key, null))
            val window = holdNextPendingRead
            holdNextPendingRead = null
            return ledger.around(ApiCall(operation, idempotencyKey = target.key)) {
                window?.let {
                    it.entered.complete(Unit)
                    it.release.await()
                }
                val record = pendingFor(operationId)
                pendingReads += index
                if (!abandon) writes[index] = writes[index].copy(body = record?.body)
                val accepted = record?.key == target.key && if (abandon) pending.abandon(target.key) else pending.confirm(target.key)
                if (!accepted) {
                    ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED).also { replies += target.key to it }
                } else {
                    answer(target.key)
                }
            }
        }

        private suspend fun answer(
            key: String,
            selected: ApiResult? = null,
        ): ApiResult {
            val result = selected ?: answers.removeFirstOrNull() ?: ok(WITHDRAWN)
            val window = holdNextResponse
            holdNextResponse = null
            window?.let {
                it.entered.complete(Unit)
                it.release.await()
            }
            hold?.await()
            val kind =
                when (result) {
                    is ApiResult.Success -> PendingKeyStore.ENVELOPE_SUCCESS
                    is ApiResult.Abandoned -> PendingKeyStore.ABANDONED
                    is ApiResult.Failure -> PendingKeyStore.envelopeKind(result.code)
                    is ApiResult.TransportFailure -> PendingKeyStore.NO_ENVELOPE
                    else -> error("本组用例未登记此结局：$result")
                }
            pending.onResponse(key, kind)
            replies += key to result
            return result
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private class Navigation : RouteNavigator {
        val routes = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            routes += route
        }

        override fun showUpgradePrompt(request: RouteRequest) = error("意外升级引导：$request")

        override fun requestConsent(request: RouteRequest) = error("意外隐私引导：$request")

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) = error("意外路由阻断：$request / $decision")
    }

    private inner class Rig(
        val api: Api = Api(),
    ) {
        val navigation = Navigation()
        var now = 1_800_000_000_000L
        var forcedUpdates = 0
        var identity = SessionIdentity(known = true, account = USER, loginEpoch = 1L, partition = "login-1")

        // 仅注入身份读取暂时未知；账号、世代、分区与会话通知不变。
        var identityRead: (SessionIdentity) -> SessionIdentity = { it }
        private val routing =
            AppRouting(
                context,
                EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
                initialUpdate = UpdateCheck.OK,
            ).also {
                it.updateSession(SessionState.PHONE_BOUND, resumePending = false)
                it.attach(navigation)
            }
        val wallet =
            AppWallet(
                api = api,
                store = null,
                routing = routing,
                sessionEpoch = { identity.loginEpoch },
                account = { identity.account },
                writer = { true },
                forceUpdate = { forcedUpdates += 1 },
                cache = null,
                mask = null,
                realname = null,
                labor = null,
                stepUpPhone = { "13800005678" },
                clock = { now },
                partitionTracked = true,
                identity = { identityRead(identity) },
            ).also { it.connectSession() }

        fun open(
            saved: SavedStateHandle = SavedStateHandle(),
            visible: Boolean = true,
            entry: String = ENTRY,
            heldKey: String? = null,
        ): Holder {
            val holder = Holder(wallet, saved, entry)
            holders += holder
            holder.model.controller.onVisibility(visible)
            FlowWait.until(::idle) { holder.model.controller.state.value.phase == WithdrawSnapshot.CONTENT }
            settle(holder, heldKey)
            return holder
        }

        /** 除端口明确挂住的 [heldKey] 外，所有调用及本页操作锁都结算，含结果采用时新发起的读取。 */
        fun settle(
            holder: Holder,
            heldKey: String? = null,
        ) {
            val included: (ApiCall) -> Boolean = { heldKey == null || it.idempotencyKey != heldKey }
            FlowWait.until(::idle, { "写=${api.writes}；端口=${api.ledger.settled { true }}/${api.ledger.started { true }}" }) {
                api.ledger.settled(included) == api.ledger.started(included) &&
                    !holder.model.controller.flow.state.value.operationInFlight
            }
            idle()
        }

        fun recreate(
            holder: Holder,
            mode: Restore,
        ): Holder {
            holder.model.controller.onVisibility(false)
            val saved = if (mode == Restore.BUNDLE) bundleSnapshot(holder.saved) else holder.saved
            holder.clear()
            idle()
            return open(saved, entry = holder.entry)
        }

        /** 同时推进业务截止时间与计时协程，不能只推进 Looper。 */
        fun advance(millis: Long) {
            now += millis
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
            idle()
        }
    }

    private class Holder(
        wallet: AppWallet,
        val saved: SavedStateHandle,
        val entry: String,
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

        fun clear() = store.clear()
    }

    private enum class Restore { SAME_HANDLE, BUNDLE }

    private fun recoveryModes() = Restore.entries.flatMap { mode -> listOf(false, true).map { mode to it } }

    /** 新进程维度只共享持久化后端与观察日志；Store、AppWallet、闸门和 token 缓存都是新实例。 */
    private fun restorePendingPage(
        rig: Rig,
        holder: Holder,
        mode: Restore,
        newProcess: Boolean,
    ): Pair<Rig, Holder> {
        if (!newProcess) return rig to rig.recreate(holder, mode)
        holder.model.controller.onVisibility(false)
        val saved = if (mode == Restore.BUNDLE) bundleSnapshot(holder.saved) else holder.saved
        holder.clear()
        idle()
        return reopenSavedPage(rig, saved, holder.entry, newProcess = true)
    }

    private fun reopenSavedPage(
        rig: Rig,
        saved: SavedStateHandle,
        entry: String,
        newProcess: Boolean,
    ): Pair<Rig, Holder> {
        val next =
            if (newProcess) {
                Rig(Api(rig.api.backend, rig.api.writes)).also {
                    it.now = rig.now
                    it.identity = rig.identity
                    it.api.rules = rig.api.rules
                }
            } else {
                rig
            }
        return next to next.open(saved, entry = entry)
    }

    /** 允许可序列化单槽或集合；空集合、空串、空白串都没有尚未释放的键。 */
    private fun hasMarker(saved: SavedStateHandle): Boolean =
        when (val marker = saved.get<Any>(IN_FLIGHT)) {
            null -> false
            is String -> marker.isNotBlank()
            is Collection<*> -> marker.isNotEmpty()
            is Map<*, *> -> marker.isNotEmpty()
            is Array<*> -> marker.isNotEmpty()
            is Boolean -> marker
            else -> true
        }

    /** 真正经过 Bundle/Parcel 往返，不能把仅存于 VM 内存或不可序列化的设锁键误当成已持久化。 */
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

    private fun submit(
        holder: Holder,
        amount: String = "10",
    ) {
        val model = holder.model
        val controller = model.controller
        model.onAmountChange("", controller.flow.owner)
        model.onAmountChange(amount, controller.flow.owner)
        controller.submit(controller.state.value.version, controller.flow.owner)
        idle()
    }

    /** 所有负向写计数在端口及结果采用结算后取快照；包含直接再点、重输、改金额三种用户操作。 */
    private fun attempts(
        rig: Rig,
        holder: Holder,
        dismissFeedback: Boolean = false,
    ): List<Write> {
        val controller = holder.model.controller
        if (dismissFeedback) dismissVisibleFeedback(rig, holder)
        controller.submit(controller.state.value.version, controller.flow.owner)
        rig.settle(holder)
        if (dismissFeedback) dismissVisibleFeedback(rig, holder)
        submit(holder)
        rig.settle(holder)
        if (dismissFeedback) dismissVisibleFeedback(rig, holder)
        submit(holder, "11")
        rig.settle(holder)
        return rig.api.writes.toList()
    }

    /** 与屏幕接线一致：关闭可关闭的反馈；行内 pendingNotice / ErrorBar 没有关闭按钮，不直接清状态。 */
    private fun dismissVisibleFeedback(
        rig: Rig,
        holder: Holder,
    ) {
        val flow = holder.model.controller.flow
        if (flow.state.value.riskDialog != null) flow.dismissRisk()
        if (flow.state.value.abandonConfirm) flow.cancelAbandon()
        if (flow.stepUp.showing) flow.stepUp.close()
        // WalletToast 消失时调用的同一回调；不让一次性反馈代替资金锁。
        flow.state.value.toast
            ?.let { flow.consumeToast(it.id) }
        rig.settle(holder)
    }

    private fun check(
        misses: MutableList<String>,
        label: String,
        condition: Boolean,
    ) {
        if (!condition) misses += label
    }

    /** 10003 收到时仍可原键验证；本任务的终态是用户关闭面板、结束本次键之后。 */
    private fun closeStepUp(
        rig: Rig,
        holder: Holder,
    ) {
        val stepUp = holder.model.controller.flow.stepUp
        assertTrue("前提：10003 已打开二次验证面板", stepUp.showing)
        stepUp.close()
        rig.settle(holder)
        assertFalse("关闭面板已经采用", stepUp.showing)
    }

    @Suppress("LongMethod") // 两种恢复逐项记录，缺陷不会截断同组其他断言
    private fun terminalCase(
        code: Int,
        forced: Boolean = false,
    ): List<String> {
        val misses = mutableListOf<String>()
        for (mode in Restore.entries) {
            val label = "$code / forced=$forced / $mode"
            val rig = Rig()
            rig.api.answers += failure(code, forced)
            val first = rig.open()
            submit(first)
            rig.settle(first)
            assertEquals("$label 前提：首发恰好一次", 1, rig.api.writes.size)
            val original = rig.api.writes.single()
            if (code == 10003) closeStepUp(rig, first)
            check(misses, "$label：终态采用后未释放在途标记", !hasMarker(first.saved))
            if (forced) assertEquals("强更结局交给宿主", 1, rig.forcedUpdates)
            if (code == 42901) {
                // 原限流截止时间先走完，再验证释放后的提交；不靠重建抹掉禁用期。
                rig.advance(5_001L)
            }
            val again = rig.recreate(first, mode)
            val afterRestore = rig.api.writes.toList()
            check(misses, "$label：重建本身发了写请求 $afterRestore", afterRestore == listOf(original))
            check(misses, "$label：终态后重建的表单仍锁住", !again.model.controller.state.value.successPending)
            submit(again)
            rig.settle(again)
            val writes = rig.api.writes.toList()
            check(misses, "$label：主动提交应恰好多 1 次，实为 $writes", writes.size == 2)
            val next = writes.getOrNull(1)
            check(misses, "$label：主动提交没有使用新键", next?.key != null && next.key != original.key)
            check(misses, "$label：只能发 createWithdrawal", writes.all { it.operation == WithdrawController.OPERATION })
            check(misses, "$label：第二笔成功没有被采用", again.saved.get<String>("withdraw.withdrawal_id") == "W0721")
            val afterSuccess = attempts(rig, again)
            check(misses, "$label：成功后又写出了请求 $afterSuccess", afterSuccess == writes)
            again.clear()
        }
        return misses
    }

    private fun terminal(vararg codes: Int) {
        val misses = codes.flatMap { terminalCase(it) }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test fun fand44_rateLimitedReleasesTheEndedKeyAcrossBothRestorations() = terminal(42901)

    /** lesson12 的无 socket 对照：活页面限流结束本来就能新键提交，不能把固定时钟造成的失败归给保存标记。 */
    @Test
    fun fand44_liveRateLimitExpiresThenSubmitsExactlyOneNewKey() {
        val rig = Rig()
        rig.api.answers += failure(42901)
        val holder = rig.open()
        submit(holder)
        rig.settle(holder)
        val before = rig.api.writes.toList()
        assertEquals("限流回包不自动重发", 1, before.size)
        assertTrue("限流截止时间尚未到", rig.wallet.gates.withdraw.active)
        rig.advance(5_001L)
        rig.settle(holder)
        assertFalse("业务时钟已到期", rig.wallet.gates.withdraw.active)
        assertFalse("Looper 推进后按钮闸门也已解除", rig.wallet.gates.withdraw.blocked.value)
        submit(holder)
        rig.settle(holder)
        val written = rig.api.writes.toList()
        assertEquals("活页面到期后主动提交恰好多一次", 2, written.size)
        assertTrue("活页面下一笔使用新键", written[1].key != null && written[1].key != before.single().key)
        assertEquals("成功结果已经采用", "W0721", holder.saved.get<String>("withdraw.withdrawal_id"))
        val afterSuccess = attempts(rig, holder)
        assertEquals("成功后不重复写", written, afterSuccess)
    }

    @Test fun fand44_riskBlockedReleasesTheEndedKeyAcrossBothRestorations() = terminal(44001)

    @Test fun fand44_serverErrorReleasesTheEndedKeyAcrossBothRestorations() = terminal(50001)

    @Test fun fand44_loginGuideReleasesTheEndedKeyAcrossBothRestorations() = terminal(10001)

    @Test fun fand44_closedStepUpReleasesTheEndedKeyAcrossBothRestorations() = terminal(10003)

    @Test fun fand44_consentGuideReleasesTheEndedKeyAcrossBothRestorations() = terminal(10004)

    @Test fun fand44_otherNewKeyOutcomesReleaseAcrossBothRestorations() {
        // 10405 非强更的 keyEnds=false、nextSubmitNewKey=true，专门防止只按 keyEnds 放锁。
        terminal(10005, 30304, 30305, 30307, 10405, 59999)
    }

    @Test fun fand44_existingDefiniteFailuresStillReleaseAcrossBothRestorations() {
        terminal(20001, 30306, 20903)
    }

    @Test
    @Suppress("LongMethod") // 原键存储、恢复、主动确认和成功锁在同一场景中核对
    fun fand44_busy40901KeepsTheOriginalKeyAndReplaysItAfterRestoration() {
        val misses = mutableListOf<String>()
        for ((mode, newProcess) in recoveryModes()) {
            val label = "$mode / newProcess=$newProcess"
            val rig = Rig()
            rig.api.answers += failure(40901)
            val first = rig.open()
            submit(first)
            rig.settle(first)
            val original = rig.api.writes.single()
            check(misses, "$label：40901 键未结束，在途标记不能释放", hasMarker(first.saved))
            val record = rig.api.pendingFor(WithdrawController.OPERATION)
            check(misses, "$label：40901 删除了原键的未决记录", record?.key == original.key)
            check(misses, "$label：40901 必须落成待确认", record?.stateRaw == PendingKeyStore.UNKNOWN)
            val (recovered, again) = restorePendingPage(rig, first, mode, newProcess)
            val restored = recovered.api.writes.toList()
            check(misses, "$label：重建不能自动重发", restored == listOf(original))
            val afterAttempts = attempts(recovered, again)
            check(misses, "$label：40901 后新提交入口多写了请求 $afterAttempts", afterAttempts == restored)
            val flow = again.model.controller.flow
            val key =
                flow.state.value.pending
                    ?.key
            check(misses, "$label：恢复后缺少可确认的原键", key == original.key)
            if (key == original.key && key != null) {
                flow.confirm(key, flow.owner)
                recovered.settle(again)
                val confirmed = recovered.api.writes.toList()
                check(
                    misses,
                    "$label：主动确认应恰好多 1 次原键原体重放 $confirmed",
                    confirmed.size == 2 && confirmed.last().key == original.key && confirmed.last().body == original.body,
                )
                check(misses, "$label：确认成功未被采用", again.saved.get<String>("withdraw.withdrawal_id") == "W0721")
                check(misses, "$label：确认成功后重复写", attempts(recovered, again) == confirmed)
            }
            again.clear()
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_aLiveBusy40901NeverGeneratesANewCreateKey() {
        val rig = Rig()
        rig.api.answers += failure(40901)
        val holder = rig.open()
        submit(holder)
        rig.settle(holder)
        val initial = rig.api.writes.toList()
        assertEquals("40901 采用后不自动续发", 1, initial.size)
        val original = initial.single()
        rig.advance(10_000L)
        rig.settle(holder)
        val afterTime = rig.api.writes.toList()
        assertEquals("推进时间不能自动重发未决键", initial, afterTime)
        // 第一次主动再点之前就改金额，避免错误实现先用旧金额成功、从而掩盖“同键换体”。
        holder.model.onAmountChange("11", holder.model.controller.flow.owner)
        var writes = attempts(rig, holder)
        val flow = holder.model.controller.flow
        val pending = flow.state.value.pending
        if (writes.size == 1 && pending != null) {
            flow.confirm(pending.key, flow.owner)
            rig.settle(holder)
            writes = attempts(rig, holder)
        }
        val allowed = setOf(WithdrawController.OPERATION, "confirm:${WithdrawController.OPERATION}")
        assertTrue(
            "40901 的重放必须保持 A 原键原体：$writes",
            writes.all {
                it.operation in allowed && it.key == original.key && it.body == original.body
            },
        )
        assertTrue("主动操作至多重放一次：$writes", writes.size in 1..2)
        rig.advance(10_000L)
        rig.settle(holder)
        val afterSuccessTime = rig.api.writes.toList()
        assertEquals("采用成功之后也不自动重发", writes, afterSuccessTime)
    }

    private enum class OtherPageEnd { CONFIRMED_SUCCESS, ABANDONED, DELETED_WITHOUT_RESULT }

    /** P 一直存活；Q 通过同一 AppWallet 的另一真实页面处理 A，而不是替身替 P 采用结果。 */
    private fun finishElsewhere(
        rig: Rig,
        p: Holder,
        original: Write,
        ending: OtherPageEnd,
    ) {
        p.model.controller.onVisibility(false)
        if (ending == OtherPageEnd.DELETED_WITHOUT_RESULT) {
            rig.api.backend.delete(USER, requireNotNull(original.key))
        } else {
            val q = rig.open(entry = "$ENTRY-Q")
            val flow = q.model.controller.flow
            val key = requireNotNull(flow.state.value.pending).key
            assertEquals("Q 接管的必须是 A", original.key, key)
            if (ending == OtherPageEnd.CONFIRMED_SUCCESS) {
                flow.confirm(key, flow.owner)
            } else {
                rig.api.answers += ApiResult.Abandoned(key, "trace-abandoned")
                flow.askAbandon(key, flow.owner)
                assertTrue("Q 放弃前二次确认", flow.state.value.abandonConfirm)
                flow.abandon(key, flow.owner)
            }
            rig.settle(q)
            val written = rig.api.writes.toList()
            assertEquals("P 首发加 Q 主动处理恰好两次", 2, written.size)
            assertEquals("Q 仍用 A", original.key, written[1].key)
            if (ending == OtherPageEnd.CONFIRMED_SUCCESS) {
                assertEquals("Q 确认使用原体", original.body, written[1].body)
                assertEquals("Q 已采用 A 的成功", "W0721", q.saved.get<String>("withdraw.withdrawal_id"))
            } else {
                assertFalse("放弃不是成功出款", q.saved.get<Boolean>("withdraw.succeeded") == true)
            }
            q.model.controller.onVisibility(false)
            q.clear()
        }
        assertEquals("别处处理后记录已删除", null, rig.api.pendingFor(WithdrawController.OPERATION))
    }

    /**
     * 先收集完整攻击链，再统一断言：不能在首次 A/1100 处 fail，漏掉 20901 后的新键 B。
     * 两种恢复各覆盖“20901 后、再点击前恢复”和“活页面再点击后恢复”，不借 B 成功后的锁遮住前一窗口。
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // 三种结局 × 两种恢复 × 再点前后，完整链收集后统一断言
    private fun livePageAfterOtherEnd(ending: OtherPageEnd) {
        val misses = mutableListOf<String>()
        for (mode in Restore.entries) {
            for (restoreBeforeRetry in listOf(false, true)) {
                val label = "$ending / $mode / restoreBeforeRetry=$restoreBeforeRetry"
                val rig = Rig()
                // A 与 B 的余额、次数都足够，不能由额度不足替资金锁挡住第二笔。
                rig.api.rules =
                    RULES
                        .replace("\"max_withdrawable_fen\":1200", "\"max_withdrawable_fen\":50000")
                        .replace("\"withdrawable_fen\":1234", "\"withdrawable_fen\":50000")
                        .replace("\"daily_remaining\":1,", "\"daily_remaining\":10,")
                rig.api.answers += failure(40901)
                var p = rig.open()
                submit(p)
                rig.settle(p)
                val original = rig.api.writes.single()
                assertEquals("P 首发金额是 1000", """{"amount_fen":1000}""", original.body)
                assertEquals(
                    "40901 先进入 A 待确认",
                    original.key,
                    p.model.controller.flow.state.value.pending
                        ?.key,
                )
                var releasedA = false
                val newKeyStarts = mutableListOf<Pair<String, Boolean>>()

                fun observeAdoption(holder: Holder) {
                    val controller = holder.model.controller
                    val release = controller.onSubmissionReleased
                    controller.onSubmissionReleased = { key ->
                        release(key)
                        if (key == original.key && !hasMarker(holder.saved)) releasedA = true
                    }
                    val start = controller.onSubmissionStarted
                    controller.onSubmissionStarted = { key ->
                        if (key != original.key) newKeyStarts += key to (releasedA && !hasMarker(holder.saved))
                        start(key)
                    }
                }
                observeAdoption(p)
                finishElsewhere(rig, p, original, ending)
                val afterQ = rig.api.writes.toList()
                // 只模拟服务端回答：A 配新体回 20901；合法的新键或原键原体仍直接成功。
                rig.api.submitAnswer = { call ->
                    when {
                        call.idempotencyKey == original.key && call.body != original.body -> failure(20901)
                        call.idempotencyKey != original.key -> ok("""{"withdrawal_id":"W0721-B"}""")
                        else -> ok(WITHDRAWN)
                    }
                }
                p.model.controller.onVisibility(true)
                p.model.controller.onResume()
                rig.settle(p)
                check(misses, "$label：回来本身自动发了写请求", rig.api.writes.toList() == afterQ)
                submit(p, "11")
                rig.settle(p)
                val afterFirstClick = rig.api.writes.toList()
                val mismatchedReply = rig.api.replies.any { it.first == original.key && (it.second as? ApiResult.Failure)?.code == 20901 }
                if (restoreBeforeRetry) {
                    p = rig.recreate(p, mode)
                    observeAdoption(p)
                }
                submit(p, "11")
                rig.settle(p)
                val afterSecondClick = rig.api.writes.toList()
                if (!restoreBeforeRetry) {
                    p = rig.recreate(p, mode)
                    observeAdoption(p)
                }
                val finalWrites = attempts(rig, p)
                val pWrites = finalWrites.drop(afterQ.size)
                val newCreates = pWrites.filter { it.operation == WithdrawController.OPERATION && it.key != original.key }
                check(
                    misses,
                    "$label：P 发出 A/1100，同键换体 $pWrites",
                    pWrites
                        .filter { it.key == original.key && it.operation != "abandon:${WithdrawController.OPERATION}" }
                        .all { it.body == original.body },
                )
                if (ending == OtherPageEnd.ABANDONED) {
                    // 保守锁可以不放行；采用 A 已作废后可以新提，但必须先经生产释放出口采用终态。
                    check(misses, "$label：未先采用 A 的终态就发新键 $newKeyStarts", newKeyStarts.all { it.second })
                    check(misses, "$label：新键应恰好写一次 $newCreates", newCreates.isEmpty() || newCreates.size == 1)
                    if (newCreates.isNotEmpty()) {
                        check(misses, "$label：允许的新笔没有采用成功", p.saved.get<String>("withdraw.withdrawal_id") == "W0721-B")
                    }
                } else {
                    check(misses, "$label：S0，P 发出新键 B 的 createWithdrawal $newCreates", newCreates.isEmpty())
                    // 不规定内部锁字段：保存保守锁，或明确采用 A 的成功，两种形态都可。
                    val adoptedSuccess = p.saved.get<Boolean>("withdraw.succeeded") == true
                    check(misses, "$label：P 丢失保守锁且未采用 A 的成功", hasMarker(p.saved) || adoptedSuccess)
                    if (adoptedSuccess) {
                        check(misses, "$label：成功必须有 A 的结果 ID", p.saved.get<String>("withdraw.withdrawal_id") == "W0721")
                        check(misses, "$label：采用成功后页面应显示成功锁", p.model.controller.state.value.successPending)
                    }
                }
                println("S0 $label reply20901=$mismatchedReply first=$afterFirstClick second=$afterSecondClick final=$finalWrites")
                p.clear()
            }
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_livePAfterQConfirmsSuccessNeverCreatesBIncludingAfter20901AndRestoration() =
        livePageAfterOtherEnd(OtherPageEnd.CONFIRMED_SUCCESS)

    @Test
    fun fand44_livePAfterUnexplainedRecordDeletionKeepsConservativeLock() = livePageAfterOtherEnd(OtherPageEnd.DELETED_WITHOUT_RESULT)

    @Test
    fun fand44_livePAfterQAbandonsAllowsOneNewKeyOnlyAfterAdoptingTheTerminal() = livePageAfterOtherEnd(OtherPageEnd.ABANDONED)

    @Test
    fun fand44_coveredStepUpThenChangedAmountNeverPairsOldKeyWithNewBody() {
        val rig = Rig()
        val gate = CompletableDeferred<Unit>()
        rig.api.answers += failure(10003)
        rig.api.hold = gate
        val holder = rig.open()
        submit(holder)
        FlowWait.until(::idle) { rig.api.writes.isNotEmpty() }
        val original = rig.api.writes.single()
        holder.model.controller.onVisibility(false)
        rig.api.hold = null
        gate.complete(Unit)
        rig.settle(holder)
        val covered = rig.api.writes.toList()
        assertEquals("覆盖期间 10003 不自动重放", listOf(original), covered)
        assertFalse("覆盖期间不弹验证面板", holder.model.controller.flow.stepUp.showing)
        holder.model.controller.onVisibility(true)
        holder.model.controller.onResume()
        rig.settle(holder)
        val beforeClick = rig.api.writes.toList()
        assertEquals("回来不自动重放", covered, beforeClick)
        var aReleasedBeforeNewStart = false
        val controller = holder.model.controller
        val started = controller.onSubmissionStarted
        controller.onSubmissionStarted = { key ->
            if (key != original.key) aReleasedBeforeNewStart = !hasMarker(holder.saved)
            started(key)
        }
        submit(holder, "11")
        rig.settle(holder)
        val panel = controller.flow.stepUp
        if (panel.showing) {
            panel.onCodeChange("123456")
            panel.verify()
            rig.settle(holder)
        }
        val written = rig.api.writes.toList()
        assertEquals("用户再点只续发一次", 2, written.size)
        val next = written.last()
        if (next.key == original.key) {
            assertEquals("G04②：原键重放必须用 A/1000，不能 A/1100", original.body, next.body)
        } else {
            assertTrue("改金额换新键之前 A 已结束并释放", aReleasedBeforeNewStart)
            assertEquals("新键使用新金额", """{"amount_fen":1100}""", next.body)
        }
        val afterSuccess = attempts(rig, holder)
        assertEquals("续发成功后不能再写", written, afterSuccess)
    }

    private fun secondKey(success: Boolean) {
        val misses = mutableListOf<String>()
        for (mode in Restore.entries) {
            val rig = Rig()
            rig.api.answers += failure(10003)
            rig.api.answers += if (success) ok(WITHDRAWN) else failure(20001)
            val first = rig.open()
            submit(first)
            rig.settle(first)
            val keyA =
                rig.api.writes
                    .single()
                    .key
            closeStepUp(rig, first)
            submit(first, "11")
            rig.settle(first)
            val written = rig.api.writes.toList()
            assertEquals("$mode 前提：关闭 A 后改金额，恰好提交 B 一次", 2, written.size)
            assertTrue("$mode 前提：B 是新键", written[1].key != keyA)
            assertEquals("$mode 前提：B 使用新金额", """{"amount_fen":1100}""", written[1].body)
            if (!success) {
                check(misses, "$mode：A 已结束却残留标记，B 不出款后仍多锁", !hasMarker(first.saved))
            }
            val again = rig.recreate(first, mode)
            val restored = rig.api.writes.toList()
            check(misses, "$mode：重建自动写请求", restored == written)
            if (success) {
                val afterAttempts = attempts(rig, again)
                check(misses, "$mode：B 的成功结果未被保存", again.saved.get<String>("withdraw.withdrawal_id") == "W0721")
                check(misses, "$mode：B 成功后未锁住或重复写", again.model.controller.state.value.successPending && afterAttempts == written)
            } else {
                check(misses, "$mode：B 明确不出款后仍多锁", !again.model.controller.state.value.successPending)
                submit(again, "11")
                rig.settle(again)
                val afterSubmit = rig.api.writes.toList()
                check(
                    misses,
                    "$mode：B 不出款后应只多一次新提交 $afterSubmit",
                    afterSubmit.size == 3 && afterSubmit.last().key !in written.map { it.key },
                )
                check(misses, "$mode：新提交成功后重复写", attempts(rig, again) == afterSubmit)
            }
            again.clear()
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test fun fand44_endedStepUpAThenDefiniteFailureBDoesNotLeaveAnExtraLock() = secondKey(success = false)

    @Test fun fand44_endedStepUpAThenSuccessfulBStillBlocksDuplicateWrites() = secondKey(success = true)

    @Test
    fun fand44_timeoutAndDisconnectKeepTheLockAcrossBothRestorations() {
        for (kind in listOf("timeout", "io")) {
            for ((mode, newProcess) in recoveryModes()) {
                val label = "$kind / $mode / newProcess=$newProcess"
                val rig = Rig()
                rig.api.answers += ApiResult.TransportFailure(kind, null, null, pendingKeyUnknown = true)
                val first = rig.open()
                submit(first)
                rig.settle(first)
                val original = rig.api.writes.toList()
                assertEquals(1, original.size)
                assertTrue("$label：未知结局必须保留标记", hasMarker(first.saved))
                val (recovered, again) = restorePendingPage(rig, first, mode, newProcess)
                assertEquals(
                    "$label：恢复原键待确认",
                    original.single().key,
                    again.model.controller.flow.state.value.pending
                        ?.key,
                )
                val afterAttempts = attempts(recovered, again)
                assertEquals("$label：未知结局不能新提交", original, afterAttempts)
                // 记录被别处删掉也不能推断原提现没执行；仍须由保存标记守住。
                recovered.api.backend.delete(USER, requireNotNull(original.single().key))
                again.model.controller.onResume()
                recovered.settle(again)
                val afterRemoval = attempts(recovered, again)
                assertEquals("$label：记录消失不能回退保守锁", original, afterRemoval)
                again.clear()
            }
        }
    }

    private fun verifiedTerminal(replayCode: Int): List<String> {
        val misses = mutableListOf<String>()
        for (mode in Restore.entries) {
            val label = "$replayCode / $mode"
            val rig = Rig()
            rig.api.answers += failure(10003)
            rig.api.answers += failure(replayCode)
            val first = rig.open()
            submit(first)
            rig.settle(first)
            val panel = first.model.controller.flow.stepUp
            assertTrue("$label 前提：首次 10003 打开验证面板", panel.showing)
            panel.onCodeChange("123456")
            assertTrue("$label 前提：可以验证", panel.state.value?.canVerify == true)
            panel.verify()
            rig.settle(first)
            val replayed = rig.api.writes.toList()
            assertEquals("$label：首发加验证后重放恰好两次", 2, replayed.size)
            assertEquals("$label：验证后仍是 A 原键原体", replayed[0], replayed[1])
            assertFalse("$label：验证重放结局不再开面板", panel.showing)
            check(misses, "$label：验证重放结局后未释放 A 的标记", !hasMarker(first.saved))
            val again = rig.recreate(first, mode)
            val restored = rig.api.writes.toList()
            check(misses, "$label：验证重放结局后重建自动写请求", restored == replayed)
            submit(again)
            rig.settle(again)
            val written = rig.api.writes.toList()
            val next = written.getOrNull(2)
            check(misses, "$label：验证重放结局后主动提交应恰好多一次 $written", written.size == 3)
            check(misses, "$label：验证重放结局后的主动提交没有新键", next?.key != null && next.key != replayed[0].key)
            check(misses, "$label：新键的成功未被采用", again.saved.get<String>("withdraw.withdrawal_id") == "W0721")
            check(misses, "$label：新键成功后又写请求", attempts(rig, again) == written)
            again.clear()
        }
        return misses
    }

    @Test
    fun fand44_exhaustedStepUpReleasesTheKeyAcrossBothRestorations() {
        val misses = verifiedTerminal(10003)
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_stepUpReplayServerAndRiskFailuresReleaseTheKeyAcrossBothRestorations() {
        val misses = listOf(50001, 44001).flatMap(::verifiedTerminal)
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    private enum class PendingAction { CONFIRM, ABANDON }

    private enum class ObservedAction { NONE, CONFIRM_IDENTITY_UNKNOWN, ABANDON_RATE_LIMITED, CONFIRM_RATE_LIMITED }

    private fun fundedRig(): Rig =
        Rig().also {
            it.api.rules =
                RULES
                    .replace("\"max_withdrawable_fen\":1200", "\"max_withdrawable_fen\":50000")
                    .replace("\"withdrawable_fen\":1234", "\"withdrawable_fen\":50000")
                    .replace("\"daily_remaining\":1,", "\"daily_remaining\":10,")
        }

    /** 公共卡片回调之前/之中的依赖故障，不触碰 Flow 私有状态或绕过生产装配。 */
    private fun beforeWriteReturns(
        rig: Rig,
        q: Holder,
        action: ObservedAction,
        once: Boolean,
    ) {
        val flow = q.model.controller.flow
        val key = requireNotNull(flow.state.value.pending).key
        val before = rig.api.writes.toList()
        when (action) {
            ObservedAction.NONE -> {
                // 只看见卡片，不点确认、放弃或提交。
                Unit
            }

            ObservedAction.CONFIRM_IDENTITY_UNKNOWN -> {
                val reads = mutableListOf<SessionIdentity>()
                rig.identityRead = { stable ->
                    // 点击的首次核对成功；后续读取暂时未知，分别覆盖单次故障和持续到本次点击返回。
                    val unknown = reads.isNotEmpty() && (!once || reads.size == 1)
                    stable.copy(known = !unknown).also { reads += it }
                }
                try {
                    flow.confirm(key, flow.owner)
                } finally {
                    rig.identityRead = { it }
                }
                assertTrue("身份故障前，点击归属核对成功", reads.first().known)
                assertTrue("本次确认确实经历了身份暂时未知", reads.any { !it.known })
                assertTrue("暂时未知不是换账号/换代", reads.all { it.account == USER && it.loginEpoch == 1L && it.partition == "login-1" })
                println("S0-r3 identity once=$once known=${reads.map { it.known }}")
            }

            ObservedAction.ABANDON_RATE_LIMITED -> {
                flow.askAbandon(key, flow.owner)
                assertTrue("先打开 A 的二次确认框", flow.state.value.abandonConfirm)
                rig.wallet.gates.withdraw
                    .start(5_000L)
                assertTrue("弹窗打开后共享禁用期生效", rig.wallet.gates.withdraw.active)
                flow.abandon(key, flow.owner)
            }

            ObservedAction.CONFIRM_RATE_LIMITED -> {
                rig.wallet.gates.withdraw
                    .start(5_000L)
                assertTrue("确认回调执行前共享禁用期生效", rig.wallet.gates.withdraw.active)
                flow.confirm(key, flow.owner)
            }
        }
        rig.settle(q)
        val after = rig.api.writes.toList()
        assertEquals("$action：本次不点击/提前返回，没有确认或放弃进入端口", before, after)
    }

    /** 先越过临时禁用期、关闭可关闭反馈，再经真实表单入口改金额及反复点击。 */
    private fun retryObservedPage(
        rig: Rig,
        holder: Holder,
        original: Write,
        successId: String = "W0721-B",
        originalResult: ApiResult = ok(WITHDRAWN),
    ): List<Write> {
        holder.model.controller.onVisibility(true)
        holder.model.controller.onResume()
        rig.settle(holder)
        rig.advance(10_000L)
        rig.settle(holder)
        dismissVisibleFeedback(rig, holder)
        rig.api.submitAnswer = { call ->
            if (call.idempotencyKey == original.key) originalResult else ok("""{"withdrawal_id":"$successId"}""")
        }
        submit(holder, "11")
        rig.settle(holder)
        return attempts(rig, holder, dismissFeedback = true)
    }

    /** 观察页的保守锁只能属于旧表单，不能将同账号后续新表单永久锁住。 */
    private fun freshSubmissionAfterObservedLeave(
        rig: Rig,
        restored: Holder,
        original: Write,
        priorSuccessIds: Set<String>,
        label: String,
    ) {
        val before = rig.api.writes.toList()
        val priorKeys = before.mapNotNull { it.key }.toSet()
        restored.model.controller.leave()
        restored.clear()
        idle()
        val fresh = rig.open(saved = SavedStateHandle(), entry = "${restored.entry}-fresh-after-leave")
        val successId = "W0721-FRESH-EXIT"
        assertFalse("$label：出口响应不能复用任何旧笔成功 ID", successId in priorSuccessIds)
        val written = retryObservedPage(rig, fresh, original, successId)
        assertEquals("$label：离开观察页后，新表单主动提交恰好多一个写入口", before.size + 1, written.size)
        val next = written.last()
        assertEquals("$label：新表单走 createWithdrawal", WithdrawController.OPERATION, next.operation)
        assertTrue("$label：新键不能等于 A 或此前任何键", !next.key.isNullOrBlank() && next.key !in priorKeys)
        assertEquals("$label：新表单提交 1100 分", """{"amount_fen":1100}""", next.body)
        assertTrue("$label：新表单已采用成功", fresh.saved.get<Boolean>("withdraw.succeeded") == true)
        val adoptedId = fresh.saved.get<String>("withdraw.withdrawal_id")
        assertTrue("$label：成功 ID 可以缺省，否则必须属于新笔", adoptedId == null || adoptedId == successId)
        assertFalse("$label：不能串用此前任何键的成功 ID", adoptedId in priorSuccessIds)
        println("S0-r3-exit $label writes=${written.drop(before.size)} adoptedId=$adoptedId")
        fresh.clear()
    }

    private fun checkObservedWrites(
        misses: MutableList<String>,
        label: String,
        original: Write,
        written: List<Write>,
    ) {
        val creates = written.filter { it.operation == WithdrawController.OPERATION }
        check(misses, "$label：S0，见过 A 的 Q 发出新键 createWithdrawal $creates", creates.all { it.key == original.key })
        check(misses, "$label：原键 A 不得配新体 $creates", creates.filter { it.key == original.key }.all { it.body == original.body })
    }

    /** 只旁观已有的按键终态释放出口；不得将别页回包或记录消失当作 Q 已采用终态。 */
    private class TerminalAdoption(
        private val originalKey: String,
    ) {
        var adopted = false
            private set
        val newStarts = mutableListOf<Pair<String, Boolean>>()

        fun observe(holder: Holder) {
            val controller = holder.model.controller
            val released = controller.onSubmissionReleased
            controller.onSubmissionReleased = { key ->
                released(key)
                if (key == originalKey) adopted = true
            }
            val started = controller.onSubmissionStarted
            controller.onSubmissionStarted = { key ->
                if (key != originalKey) newStarts += key to adopted
                started(key)
            }
        }
    }

    private fun checkBusyTerminalWrites(
        misses: MutableList<String>,
        label: String,
        original: Write,
        written: List<Write>,
        adoption: TerminalAdoption,
    ) {
        val creates = written.filter { it.operation == WithdrawController.OPERATION }
        val fresh = creates.filter { it.key != original.key }
        check(misses, "$label：原键 A 不能配新体 $creates", creates.filter { it.key == original.key }.all { it.body == original.body })
        check(misses, "$label：保守锁可不写，放行的新键必须恰好一次 $fresh", fresh.isEmpty() || fresh.size == 1)
        check(misses, "$label：新键必须非空、使用 1100 分", fresh.all { !it.key.isNullOrBlank() && it.body == """{"amount_fen":1100}""" })
        check(
            misses,
            "$label：新键前 Q 未先采用 A 的明确不出款终态 ${adoption.newStarts}",
            fresh.all { write -> adoption.newStarts.any { (key, ended) -> key == write.key && ended } },
        )
    }

    private fun assertBusyCard(
        rig: Rig,
        q: Holder,
        original: Write,
    ) {
        val card = q.model.controller.flow.state.value.pending
        assertEquals("Q 观察的是别页 A 的卡片", original.key, card?.key)
        assertTrue("Q 确实显示忙碌卡", requireNotNull(card).inFlight)
        assertFalse("Q 自己没有写操作，A 不能冒充 Q 的旧表单在途键", q.model.controller.flow.state.value.operationInFlight)
        val record = requireNotNull(rig.api.pendingFor(WithdrawController.OPERATION))
        assertEquals("A 首次提交仍在途", PendingKeyStore.IN_FLIGHT, record.stateRaw)
        assertFalse("A 从未 UNKNOWN，不借旧口径兜底", record.everUnknown)
    }

    /** G17：首次提交的忙碌卡也算观察；Q 从不点击卡片，所有写操作都从真实页面进入端口。 */
    @Suppress("LongMethod", "NestedBlockDepth") // 结局与换代分开报告，每例完整走三种恢复和两个保存时点
    private fun busyObservation(
        noPayment: Boolean = false,
        rotate: Boolean = false,
    ) {
        val misses = mutableListOf<String>()
        val modes = listOf(Restore.SAME_HANDLE to false, Restore.BUNDLE to false, Restore.BUNDLE to true)
        for ((mode, newProcess) in modes) {
            for (saveBeforeVanish in listOf(false, true)) {
                val label =
                    "G17 / noPayment=$noPayment / rotate=$rotate / $mode / " +
                        "newProcess=$newProcess / saveBeforeVanish=$saveBeforeVanish"
                val rig = fundedRig()
                val result = if (noPayment) failure(20001) else ok(WITHDRAWN)
                rig.api.answers += result
                val p = rig.open(entry = "$ENTRY-busy-P")
                val window = ResponseWindow()
                rig.api.holdNextResponse = window
                submit(p)
                FlowWait.until(::idle) { window.entered.isCompleted }
                val original = rig.api.writes.single()
                val key = requireNotNull(original.key)
                assertEquals("A 的原体是 1000 分", """{"amount_fen":1000}""", original.body)
                p.model.controller.onVisibility(false)
                // 此时仅 A 的响应有意挂住；Q 的初次读取仍须全部结算，不能等整个 Rig 空闲。
                val q = rig.open(entry = "$ENTRY-busy-Q", heldKey = key)
                assertBusyCard(rig, q, original)
                val adoption = TerminalAdoption(key).also { it.observe(q) }
                if (rotate) {
                    var resetSeen = false
                    val reset = q.model.controller.onReset
                    q.model.controller.onReset = { identity ->
                        resetSeen = true
                        reset(identity)
                    }
                    rig.identity = rig.identity.copy(partition = "login-2")
                    rig.wallet.onSessionChanged()
                    rig.settle(q, heldKey = key)
                    assertTrue("Q 经过真实 App 换代通知，已采用表单重置", resetSeen)
                    assertBusyCard(rig, q, original)
                }
                assertFalse("释放前 A 的响应仍被挂住", window.release.isCompleted)
                assertTrue("忙碌卡场景尚未收到 A 的任何结局", rig.api.replies.isEmpty())
                q.model.controller.onVisibility(false)
                val saved =
                    if (saveBeforeVanish) {
                        (if (mode == Restore.BUNDLE) bundleSnapshot(q.saved) else q.saved).also { q.clear() }
                    } else {
                        null
                    }
                window.release.complete(Unit)
                rig.settle(p)
                assertEquals("P 的真实响应已结算", listOf(key to result), rig.api.replies.toList())
                assertEquals("A 终态经生产 Store 清除记录", null, rig.api.pendingFor(WithdrawController.OPERATION))
                assertEquals("截至 A 结算，Q 从未点击、没有任何新写入口", listOf(original), rig.api.writes.toList())
                p.clear()
                if (saved == null) {
                    val liveWrites = retryObservedPage(rig, q, original, originalResult = result)
                    if (noPayment) {
                        checkBusyTerminalWrites(misses, "$label / 活 Q", original, liveWrites, adoption)
                    } else {
                        checkObservedWrites(misses, "$label / 活 Q", original, liveWrites)
                    }
                    println("$label live=$liveWrites adoptedEnd=${adoption.adopted}")
                }
                val (recovered, restored) =
                    if (saved != null) {
                        reopenSavedPage(rig, saved, q.entry, newProcess)
                    } else {
                        restorePendingPage(rig, q, mode, newProcess)
                    }
                adoption.observe(restored)
                val restoredWrites = retryObservedPage(recovered, restored, original, originalResult = result)
                if (noPayment) {
                    checkBusyTerminalWrites(misses, "$label / 恢复后", original, restoredWrites, adoption)
                    if (restoredWrites.any { it.key != key }) {
                        check(misses, "$label：放行的新笔成功未采用", restored.saved.get<Boolean>("withdraw.succeeded") == true)
                    }
                } else {
                    checkObservedWrites(misses, "$label / 恢复后", original, restoredWrites)
                }
                println("$label restored=$restoredWrites adoptedEnd=${adoption.adopted}")
                val priorIds =
                    (rig.api.replies + recovered.api.replies)
                        .mapNotNull { (_, reply) ->
                            val data = (reply as? ApiResult.Success)?.data as? JsonObject
                            (data?.get("withdrawal_id") as? JsonPrimitive)?.contentOrNull
                        }.toSet()
                // 每种结局/恢复都验证离开观察页后的可用出口，禁止把 G17 修成账号级或持久层永久锁。
                freshSubmissionAfterObservedLeave(recovered, restored, original, priorIds, label)
            }
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_busyCardFirstSubmissionSuccessLocksObserverAcrossRestorations() = busyObservation()

    @Test
    fun fand44_busyCardNoPaymentAllowsOnlyAdoptedNewKeyOrConservativeLock() = busyObservation(noPayment = true)

    @Test
    fun fand44_busyCardOtherPageKeyCannotUseReplacedFormExemption() = busyObservation(rotate = true)

    /**
     * 第 6 版裁定：只要本页采用过 A 的 UNKNOWN 记录，点击与否、点击是否提前返回，都不能因记录消失放出 B。
     * 一组先攻击活 Q 再恢复；另一组在卡片可见时保存/销毁 Q，A 被别处成功确认后才恢复，独立检验保存关联。
     */
    @Suppress("LongMethod", "NestedBlockDepth") // 三种恢复 × 两种保存时点，汇总行为失败后才断言
    private fun observedPendingMustSurviveOtherSuccess(action: ObservedAction) {
        val misses = mutableListOf<String>()
        val modes = listOf(Restore.SAME_HANDLE to false, Restore.BUNDLE to false, Restore.BUNDLE to true)
        val faults = if (action == ObservedAction.CONFIRM_IDENTITY_UNKNOWN) listOf(true, false) else listOf(false)
        for (once in faults) {
            for ((mode, newProcess) in modes) {
                for (saveBeforeVanish in listOf(false, true)) {
                    val label = "$action / $mode / newProcess=$newProcess / saveBeforeVanish=$saveBeforeVanish / once=$once"
                    val rig = fundedRig()
                    rig.api.answers += failure(40901)
                    val p = rig.open(entry = "$ENTRY-observed-source")
                    submit(p)
                    rig.settle(p)
                    val original = rig.api.writes.single()
                    val key = requireNotNull(original.key)
                    p.model.controller.onVisibility(false)
                    val q = rig.open(entry = "$ENTRY-observed-Q")
                    val card = q.model.controller.flow.state.value.pending
                    assertEquals("$label：Q 已采用并可显示 A 的待确认卡", key, card?.key)
                    assertFalse("$label：A 的首发已结算，不是在途卡", requireNotNull(card).inFlight)
                    assertEquals(
                        "$label：生产 Store 中 A 为 UNKNOWN",
                        PendingKeyStore.UNKNOWN,
                        rig.api.pendingFor(WithdrawController.OPERATION)?.stateRaw,
                    )
                    beforeWriteReturns(rig, q, action, once)
                    q.model.controller.onVisibility(false)
                    val saved =
                        if (saveBeforeVanish) {
                            (if (mode == Restore.BUNDLE) bundleSnapshot(q.saved) else q.saved).also { q.clear() }
                        } else {
                            null
                        }
                    rig.advance(10_000L)
                    rig.settle(p)
                    assertFalse("$label：共享禁用期已过，不能替资金锁挡写", rig.wallet.gates.withdraw.active)
                    p.model.controller.onVisibility(true)
                    p.model.controller.flow
                        .confirm(key, p.model.controller.flow.owner)
                    rig.settle(p)
                    assertEquals("$label：另一页已明确采用 A 成功", "W0721", p.saved.get<String>("withdraw.withdrawal_id"))
                    assertEquals("$label：生产 Store 已正常删除 A", null, rig.api.pendingFor(WithdrawController.OPERATION))
                    val afterOther = rig.api.writes.toList()
                    assertEquals("$label：至此只有 A 首发和 P 确认两个入口", 2, afterOther.size)
                    assertEquals("$label：P 确认仍是 A 原体", original.body, afterOther.last().body)
                    p.model.controller.onVisibility(false)
                    p.clear()
                    if (saved == null) {
                        val liveWrites = retryObservedPage(rig, q, original)
                        checkObservedWrites(misses, "$label / 活 Q", original, liveWrites)
                        println("S0-r3 $label live=${liveWrites.drop(afterOther.size)}")
                    }
                    val (recovered, restored) =
                        if (saved != null) {
                            reopenSavedPage(rig, saved, q.entry, newProcess)
                        } else {
                            restorePendingPage(rig, q, mode, newProcess)
                        }
                    val restoreEntry = recovered.api.writes.size
                    val restoredWrites = retryObservedPage(recovered, restored, original)
                    checkObservedWrites(misses, "$label / 恢复后", original, restoredWrites)
                    println("S0-r3 $label restored=${restoredWrites.drop(restoreEntry)}")
                    if (action == ObservedAction.NONE) {
                        // 新进程的 Api 没有旧进程回包日志，两份都纳入，禁止串用 A 或误发 B 的成功 ID。
                        val priorSuccessIds =
                            (rig.api.replies + recovered.api.replies)
                                .mapNotNull { (_, result) ->
                                    val data = (result as? ApiResult.Success)?.data as? JsonObject
                                    (data?.get("withdrawal_id") as? JsonPrimitive)?.contentOrNull
                                }.toSet()
                        freshSubmissionAfterObservedLeave(recovered, restored, original, priorSuccessIds, label)
                    } else {
                        restored.clear()
                    }
                }
            }
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_observedPendingWithoutClickSurvivesOtherSuccessAndRestoration() = observedPendingMustSurviveOtherSuccess(ObservedAction.NONE)

    @Test
    fun fand44_observedPendingConfirmIdentityUnknownSurvivesOtherSuccessAndRestoration() =
        observedPendingMustSurviveOtherSuccess(ObservedAction.CONFIRM_IDENTITY_UNKNOWN)

    @Test
    fun fand44_observedPendingAbandonRateLimitSurvivesOtherSuccessAndRestoration() =
        observedPendingMustSurviveOtherSuccess(ObservedAction.ABANDON_RATE_LIMITED)

    @Test
    fun fand44_observedPendingConfirmRateLimitSurvivesOtherSuccessAndRestoration() =
        observedPendingMustSurviveOtherSuccess(ObservedAction.CONFIRM_RATE_LIMITED)

    @Test
    fun fand44_neverObservedPendingFreshPageAfterSuccessSubmitsOneNewKey() {
        val rig = fundedRig()
        rig.api.answers += failure(40901)
        val p = rig.open(entry = "$ENTRY-fresh-control-source")
        submit(p)
        rig.settle(p)
        val original = rig.api.writes.single()
        p.model.controller.flow
            .confirm(requireNotNull(original.key), p.model.controller.flow.owner)
        rig.settle(p)
        assertEquals("前提：A 已在别处确认成功", "W0721", p.saved.get<String>("withdraw.withdrawal_id"))
        assertEquals("前提：A 已结束且记录已删", null, rig.api.pendingFor(WithdrawController.OPERATION))
        p.model.controller.onVisibility(false)
        p.clear()
        val before = rig.api.writes.toList()
        val fresh = rig.open(entry = "$ENTRY-never-observed")
        assertEquals("A 结束后才打开的新页面，从未显示 A 待确认卡", null, fresh.model.controller.flow.state.value.pending)
        val written = retryObservedPage(rig, fresh, original)
        assertEquals("全新页面主动提交恰好多一次", before.size + 1, written.size)
        val next = written.last()
        assertEquals("经 createWithdrawal 提交", WithdrawController.OPERATION, next.operation)
        assertTrue("正常新笔使用不同的非空键", !next.key.isNullOrBlank() && next.key != original.key)
        assertEquals("新笔金额为 1100", """{"amount_fen":1100}""", next.body)
        assertEquals("新笔成功已采用，反复点击没有额外写", "W0721-B", fresh.saved.get<String>("withdraw.withdrawal_id"))
    }

    /**
     * review-r2 S0：Q 是新页。端口受理 Q 的卡片操作后，先不读/占用 Store，让另一页正常确认 A。
     * 不能先重建 Q，否则 SavedStateHandle 的恢复锁会遮住活页面的缺陷。
     */
    @Suppress("LongMethod")
    private fun freshPendingIntentRejectedAfterOtherSuccess(action: PendingAction) {
        val misses = mutableListOf<String>()
        for (mode in Restore.entries) {
            val label = "$action / $mode"
            val rig = Rig()
            rig.api.rules =
                RULES
                    .replace("\"max_withdrawable_fen\":1200", "\"max_withdrawable_fen\":50000")
                    .replace("\"withdrawable_fen\":1234", "\"withdrawable_fen\":50000")
                    .replace("\"daily_remaining\":1,", "\"daily_remaining\":10,")
            rig.api.answers += failure(40901)
            val p = rig.open(entry = "$ENTRY-source")
            submit(p)
            rig.settle(p)
            val original = rig.api.writes.single()
            val key = requireNotNull(original.key)
            p.model.controller.onVisibility(false)
            val freshState = SavedStateHandle()
            assertFalse("$label 前提：Q 的输入 Handle 是全新的", hasMarker(freshState))
            val q = rig.open(saved = freshState, entry = "$ENTRY-fresh-Q")
            // 第 6 版裁定：观察 A 的卡片后可以立即保存关联，不能再要求此时没有标记。
            assertFalse("$label 前提：Q 尚未采用过成功", q.saved.get<Boolean>("withdraw.succeeded") == true)
            val flow = q.model.controller.flow
            assertEquals(
                "$label：Q 显示 A 的待确认卡",
                key,
                flow.state.value.pending
                    ?.key,
            )
            val window = PendingReadWindow()
            rig.api.holdNextPendingRead = window
            val qIndex = rig.api.writes.size
            when (action) {
                PendingAction.CONFIRM -> {
                    flow.confirm(key, flow.owner)
                }

                PendingAction.ABANDON -> {
                    flow.askAbandon(key, flow.owner)
                    assertTrue("$label：确定放弃前经过二次确认", flow.state.value.abandonConfirm)
                    flow.abandon(key, flow.owner)
                }
            }
            FlowWait.until(::idle) { window.entered.isCompleted }
            assertTrue("$label：受理 Q 时已同步保存 A", hasMarker(q.saved))
            assertTrue("$label：等待读取期间 Q 仍持有操作锁", flow.state.value.operationInFlight)
            assertFalse("$label：挂起位置在 Q 首次读取前", qIndex in rig.api.pendingReads)
            // Q 尚未读/占用 A；P 是另一个真实页面，按正常确认入口采用 A 成功并删除记录。
            q.model.controller.onVisibility(false)
            p.model.controller.onVisibility(true)
            p.model.controller.flow
                .confirm(key, p.model.controller.flow.owner)
            FlowWait.until(::idle) {
                p.saved.get<String>("withdraw.withdrawal_id") == "W0721" &&
                    !p.model.controller.flow.state.value.operationInFlight &&
                    rig.api.ledger.settled { it.operationId == "confirm:${WithdrawController.OPERATION}" } == 1
            }
            assertEquals("$label：另一页已确认成功、记录已删除", null, rig.api.pendingFor(WithdrawController.OPERATION))
            assertFalse("$label：Q 此时仍未读记录", qIndex in rig.api.pendingReads)
            window.release.complete(Unit)
            rig.settle(q)
            val rejected = rig.api.replies.mapNotNull { it.second as? ApiResult.LocalReject }
            assertEquals("$label：Q 实际得到缺失记录的本地拒绝", listOf(LocalRejectReason.PENDING_KEY_UNRESOLVED), rejected.map { it.reason })
            val beforeClick = rig.api.writes.toList()
            assertEquals("$label：只有 A 首发、Q 受理、另一页确认三个入口", 3, beforeClick.size)
            assertTrue("$label：缺失记录不产生请求体", beforeClick[qIndex].body == null)
            p.model.controller.onVisibility(false)
            q.model.controller.onVisibility(true)
            q.model.controller.onResume()
            rig.settle(q)
            rig.advance(10_000L)
            rig.settle(q)
            dismissVisibleFeedback(rig, q)
            // 新键若漏出，返回不同成功 ID，不能将它混作另一页已经成功的 A。
            rig.api.submitAnswer = { call ->
                if (call.idempotencyKey == key) ok(WITHDRAWN) else ok("""{"withdrawal_id":"W0721-B"}""")
            }
            submit(q, "11")
            rig.settle(q)
            val liveWrites = attempts(rig, q, dismissFeedback = true)
            val liveSuccess = q.saved.get<String>("withdraw.withdrawal_id")
            val restored = rig.recreate(q, mode)
            rig.advance(10_000L)
            rig.settle(restored)
            val finalWrites = attempts(rig, restored, dismissFeedback = true)
            for ((stage, written) in listOf("活 Q" to liveWrites, "恢复后" to finalWrites)) {
                val creates = written.filter { it.operation == WithdrawController.OPERATION }
                check(misses, "$label / $stage：S0，本地拒绝后出现新键 createWithdrawal $creates", creates.all { it.key == key })
                check(misses, "$label / $stage：不能 A 配新体 $creates", creates.filter { it.key == key }.all { it.body == original.body })
            }
            if (restored.saved.get<Boolean>("withdraw.succeeded") == true) {
                check(misses, "$label：采用明确成功后页面应保持成功锁", restored.model.controller.state.value.successPending)
            }
            println("S0-r2 $label rejected=${rejected.map { it.reason }} readOrder=${rig.api.pendingReads} liveSuccess=$liveSuccess")
            println("S0-r2 $label before=$beforeClick live=$liveWrites restored=$finalWrites")
            restored.clear()
            p.clear()
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_freshQConfirmLocallyRejectedAfterOtherSuccessNeverCreatesB() =
        freshPendingIntentRejectedAfterOtherSuccess(PendingAction.CONFIRM)

    @Test
    fun fand44_freshQAbandonLocallyRejectedAfterOtherSuccessNeverCreatesB() =
        freshPendingIntentRejectedAfterOtherSuccess(PendingAction.ABANDON)

    /** 模拟网络层重放预算用尽后交回的最终失败；不在页面测试里复制令牌刷新或设备注册重放循环。 */
    @Suppress("LongMethod") // 同金额回归与改金额的两种合法续发形态一起验证
    private fun exhaustedAutomaticReplayKeepsBody(code: Int) {
        for (amount in listOf("10", "11")) {
            val label = "$code / nextAmount=$amount"
            val rig = Rig()
            rig.api.answers += failure(code)
            val holder = rig.open()
            submit(holder)
            rig.settle(holder)
            val original = rig.api.writes.single()
            rig.advance(10_000L)
            rig.settle(holder)
            val afterTime = rig.api.writes.toList()
            assertEquals("$label：网络层已交回最终失败，页面不得再自动重放", listOf(original), afterTime)
            val controller = holder.model.controller
            var aReleasedBeforeNewStart = false
            val started = controller.onSubmissionStarted
            controller.onSubmissionStarted = { key ->
                if (key != original.key) aReleasedBeforeNewStart = !hasMarker(holder.saved)
                started(key)
            }
            holder.model.onAmountChange(amount, controller.flow.owner)
            if (amount == "11" && controller.state.value.inputFen != 1100L) {
                // 锁住金额输入也是合法形态；同金额组仍要求主动续发，不能把全禁用误当通过。
                println("F02 $label：未接收改金额，跳过改金额分支；同金额回归仍执行")
                holder.clear()
                continue
            }
            controller.submit(controller.state.value.version, controller.flow.owner)
            rig.settle(holder)
            val written = rig.api.writes.toList()
            assertEquals("$label：用户再点恰好续发一次", 2, written.size)
            val next = written.last()
            if (next.key == original.key) {
                assertEquals("$label：F02，沿用 A 必须保持最初 1000 分原体", original.body, next.body)
            } else {
                assertEquals("$label：同金额对照仍须沿用 A", "11", amount)
                assertTrue("$label：改金额的新键不可为空", !next.key.isNullOrBlank())
                assertTrue("$label：新键开始前 A 已结束并释放标记", aReleasedBeforeNewStart)
                assertEquals("$label：新键使用 1100 分新体", """{"amount_fen":1100}""", next.body)
            }
            println("F02 $label：续发 key=${next.key} body=${next.body} releasedBeforeNew=$aReleasedBeforeNewStart")
            assertEquals("$label：续发成功已采用", "W0721", holder.saved.get<String>("withdraw.withdrawal_id"))
            val afterSuccess = attempts(rig, holder)
            assertEquals("$label：续发成功后不可重复提交", written, afterSuccess)
            holder.clear()
        }
    }

    @Test
    fun fand44_refresh10002BudgetExhaustedKeepsOriginalBodyOnRetry() = exhaustedAutomaticReplayKeepsBody(10002)

    @Test
    fun fand44_registration10402BudgetExhaustedKeepsOriginalBodyOnRetry() = exhaustedAutomaticReplayKeepsBody(10402)

    /** G09 的可测出口是退出旧条目后新建表单；不将“会话换代一定解锁”写成没有依据的要求。 */
    @Test
    fun fand44_afterOtherPageAbandonsLeavingOldEntryAllowsOneFreshSubmission() {
        val rig = Rig()
        rig.api.answers += failure(40901)
        val p = rig.open()
        submit(p)
        rig.settle(p)
        val original = rig.api.writes.single()
        finishElsewhere(rig, p, original, OtherPageEnd.ABANDONED)
        p.model.controller.onVisibility(true)
        p.model.controller.onResume()
        rig.settle(p)
        val beforeLeave = rig.api.writes.toList()
        assertEquals("A 首发与 Q 放弃已经结算", 2, beforeLeave.size)
        // 原 P 可保守锁住，也可采用明确作废；不要求选哪一种。这里只检验退出并新建条目的出口。
        p.model.controller.leave()
        p.clear()
        idle()
        val fresh = rig.open(entry = "$ENTRY-reentered")
        assertFalse("新条目不继承 P 的保存锁", hasMarker(fresh.saved))
        submit(fresh, "11")
        rig.settle(fresh)
        val written = rig.api.writes.toList()
        assertEquals("退出旧表单后，新表单主动提交恰好一次", beforeLeave.size + 1, written.size)
        assertTrue("新表单用新键", written.last().key != null && written.last().key != original.key)
        assertEquals("新表单用新金额", """{"amount_fen":1100}""", written.last().body)
        assertEquals("新表单的成功已采用", "W0721", fresh.saved.get<String>("withdraw.withdrawal_id"))
        val afterSuccess = attempts(rig, fresh)
        assertEquals("新表单成功后仍不可重复写", written, afterSuccess)
    }

    /** 已未知过的 A，经确认/放弃收到这些回包仍未结束；首次提交的按码放锁不能误用到这里。 */
    @Suppress("LongMethod") // 结局与恢复方式全部执行后汇总
    private fun pendingNonterminal(
        action: PendingAction,
        startsWithBusy: Boolean = false,
    ) {
        val misses = mutableListOf<String>()
        val modes = if (startsWithBusy) recoveryModes() else Restore.entries.map { it to false }
        for (code in listOf(50001, 42901, 40901, 44001, 10001, 10004, 10405)) {
            for ((mode, newProcess) in modes) {
                val label = "$action / $code / $mode / busy=$startsWithBusy / newProcess=$newProcess"
                val rig = Rig()
                rig.api.answers +=
                    if (startsWithBusy) failure(40901) else ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
                val first = rig.open()
                submit(first)
                rig.settle(first)
                val original = rig.api.writes.single()
                val flow = first.model.controller.flow
                val key = requireNotNull(flow.state.value.pending).key
                assertEquals("$label 前提：首次提交待确认，卡片是 A", original.key, key)
                rig.api.answers += failure(code)
                when (action) {
                    PendingAction.CONFIRM -> {
                        flow.confirm(key, flow.owner)
                    }

                    PendingAction.ABANDON -> {
                        flow.askAbandon(key, flow.owner)
                        assertTrue("$label 前提：作废前二次确认", flow.state.value.abandonConfirm)
                        flow.abandon(key, flow.owner)
                    }
                }
                rig.settle(first)
                val sent = rig.api.writes.toList()
                val operation = if (action == PendingAction.CONFIRM) "confirm" else "abandon"
                assertEquals("$label：首发加主动卡片操作恰好两次", 2, sent.size)
                assertEquals("$label：没有改走新提交", "$operation:${WithdrawController.OPERATION}", sent[1].operation)
                assertEquals("$label：卡片操作仍用原键", original.key, sent[1].key)
                check(misses, "$label：非终态回包不能释放 A 的标记", hasMarker(first.saved))
                val record = rig.api.pendingFor(WithdrawController.OPERATION)
                check(misses, "$label：原记录必须保持待确认", record?.key == key && record.stateRaw == PendingKeyStore.UNKNOWN)
                check(misses, "$label：原请求体不能丢失或改变", record?.body == original.body)
                // 即使禁用期过去，也不能把 A 当成已结束；避免限流自身挡写而掩盖误放锁。
                rig.advance(10_000L)
                rig.settle(first)
                assertFalse("$label：测试时钟已越过禁用期", rig.wallet.gates.withdraw.active)
                val (recovered, again) = restorePendingPage(rig, first, mode, newProcess)
                val card = again.model.controller.flow.state.value.pending
                check(misses, "$label：重建后缺少原键待确认卡", card?.key == key)
                check(misses, "$label：重建后保存锁丢失", hasMarker(again.saved))
                val recoveredRecord = recovered.api.pendingFor(WithdrawController.OPERATION)
                check(misses, "$label：新 Store 仍保留 A 原体", recoveredRecord?.key == key && recoveredRecord.body == original.body)
                if (startsWithBusy) {
                    assertFalse(
                        "$label：Store 也必须拒绝新键，不只靠页面锁",
                        recovered.api.pending.beforeSend(WithdrawController.OPERATION, "probe-new-key", """{"amount_fen":1100}""", USER),
                    )
                }
                val attempted = attempts(recovered, again)
                check(misses, "$label：非终态后重建/主动提交增加了写请求 $attempted", attempted == sent)
                recovered.api.backend.delete(USER, key)
                again.model.controller.onResume()
                recovered.settle(again)
                val afterRemoval = attempts(recovered, again)
                check(misses, "$label：记录消失不能替代未决结论而解锁", afterRemoval == sent)
                again.clear()
            }
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test fun fand44_confirmNonterminalResultsKeepOriginalPendingAcrossBothRestorations() = pendingNonterminal(PendingAction.CONFIRM)

    @Test fun fand44_abandonNonterminalResultsKeepOriginalPendingAcrossBothRestorations() = pendingNonterminal(PendingAction.ABANDON)

    @Test
    fun fand44_busyThenConfirmNonterminalKeepsOriginalBodyIncludingNewStore() =
        pendingNonterminal(PendingAction.CONFIRM, startsWithBusy = true)

    @Test
    fun fand44_busyThenAbandonNonterminalKeepsOriginalBodyIncludingNewStore() =
        pendingNonterminal(PendingAction.ABANDON, startsWithBusy = true)

    private enum class SuccessPath { SUBMIT, CONFIRM, ABANDON_COMPLETED }

    /** M09 是四时点保存约定；成功锁本已兜底，不能把标记丢失直接说成重复出款。 */
    @Test
    @Suppress("LongMethod")
    fun fand44_successKeepsSavedMarkerAndSuccessLockAtAllFourMoments() {
        val misses = mutableListOf<String>()
        for (path in SuccessPath.entries) {
            for (mode in Restore.entries) {
                val label = "$path / $mode"
                val rig = Rig()
                val first = rig.open()
                val controller = first.model.controller
                val flow = controller.flow
                if (path != SuccessPath.SUBMIT) {
                    rig.api.answers += failure(40901)
                    submit(first)
                    rig.settle(first)
                }
                val before = rig.api.writes.size
                val moments = mutableListOf<String>()
                rig.api.onWriteEntry = {
                    moments += "发起"
                    check(misses, "$label：发起时先占操作锁", flow.state.value.operationInFlight)
                    check(misses, "$label：端口入口前保存首键", hasMarker(first.saved))
                    check(misses, "$label：发起时尚未登记成功", first.saved.get<Boolean>("withdraw.succeeded") != true)
                }
                val success = controller.onSuccessRecorded
                controller.onSuccessRecorded = { data ->
                    success(data) // 不替代生产保存接线，只在它执行后观察采用时点。
                    moments += "采用"
                    check(misses, "$label：成功采用时操作锁仍持有", flow.state.value.operationInFlight)
                    check(misses, "$label：成功采用不释放保存首键", hasMarker(first.saved))
                    check(misses, "$label：成功锁同步保存", first.saved.get<Boolean>("withdraw.succeeded") == true)
                    check(misses, "$label：成功 ID 同步保存", first.saved.get<String>("withdraw.withdrawal_id") == "W0721")
                    check(misses, "$label：采用成功后页面锁已在位", controller.state.value.successPending)
                }
                val gate = CompletableDeferred<Unit>()
                rig.api.hold = gate
                when (path) {
                    SuccessPath.SUBMIT -> {
                        submit(first)
                    }

                    SuccessPath.CONFIRM -> {
                        flow.confirm(requireNotNull(flow.state.value.pending).key, flow.owner)
                    }

                    SuccessPath.ABANDON_COMPLETED -> {
                        val key = requireNotNull(flow.state.value.pending).key
                        flow.askAbandon(key, flow.owner)
                        flow.abandon(key, flow.owner)
                    }
                }
                FlowWait.until(::idle) { rig.api.writes.size > before }
                moments += "等待"
                check(misses, "$label：等待响应期间操作锁保持", flow.state.value.operationInFlight)
                check(misses, "$label：等待响应期间保存锁保持", hasMarker(first.saved))
                controller.submit(controller.state.value.version, flow.owner)
                rig.api.hold = null
                gate.complete(Unit)
                rig.settle(first)
                moments += "收尾"
                check(misses, "$label：四时点均经真实调用", moments == listOf("发起", "等待", "采用", "收尾"))
                check(misses, "$label：采用完才释放操作锁", !flow.state.value.operationInFlight)
                check(misses, "$label：收尾后保留成功的保存首键", hasMarker(first.saved))
                check(misses, "$label：收尾后成功锁仍在", first.saved.get<Boolean>("withdraw.succeeded") == true)
                val written = rig.api.writes.toList()
                check(misses, "$label：成功写恰好多一次，在途再点不写", written.size == before + 1)
                val attempted = attempts(rig, first)
                check(misses, "$label：成功后活页面不再写", attempted == written)
                val again = rig.recreate(first, mode)
                val restoredAttempts = attempts(rig, again)
                check(misses, "$label：恢复保留成功保存首键", hasMarker(again.saved))
                check(
                    misses,
                    "$label：恢复保留成功及 ID",
                    again.saved.get<Boolean>("withdraw.succeeded") == true && again.saved.get<String>("withdraw.withdrawal_id") == "W0721",
                )
                check(misses, "$label：恢复后同一表单不能再写", restoredAttempts == written)
                again.clear()
            }
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_releasedAThenUnknownBLocksAgainAcrossBothRestorations() {
        val misses = mutableListOf<String>()
        for (mode in Restore.entries) {
            val rig = Rig()
            rig.api.answers += failure(50001)
            val first = rig.open()
            submit(first)
            rig.settle(first)
            check(misses, "$mode：A 终态后必须先释放", !hasMarker(first.saved))
            rig.api.answers += ApiResult.TransportFailure("timeout", null, null, pendingKeyUnknown = true)
            submit(first, "11")
            rig.settle(first)
            val sent = rig.api.writes.toList()
            assertEquals("$mode 前提：A 后主动提交 B 一次", 2, sent.size)
            val keyB = requireNotNull(sent[1].key)
            assertTrue("$mode 前提：B 是新键", keyB != sent[0].key)
            check(misses, "$mode：A 释放不能妨碍 B 再次设锁", hasMarker(first.saved))
            val again = rig.recreate(first, mode)
            val card = again.model.controller.flow.state.value.pending
            check(misses, "$mode：恢复后必须显示 B 的待确认卡", card?.key == keyB)
            val attempted = attempts(rig, again)
            check(misses, "$mode：B 未知时不能再写 $attempted", attempted == sent)
            rig.api.backend.delete(USER, keyB)
            again.model.controller.onResume()
            rig.settle(again)
            check(misses, "$mode：B 记录消失后仍须保锁", attempts(rig, again) == sent)
            again.clear()
        }
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    @Test
    fun fand44_forced10405ReleasesTheUnusedKeyAcrossBothRestorations() {
        // 宿主强更出口必须被调用；恢复后的环境视为已升级，验证的是本页不再被已不用的键多锁。
        val misses = terminalCase(10405, forced = true)
        assertEquals(misses.joinToString("\n"), emptyList<String>(), misses)
    }

    private fun entryResult(
        code: Int,
        covered: Boolean,
    ) {
        val rig = Rig()
        rig.api.answers += failure(code)
        val gate = CompletableDeferred<Unit>()
        rig.api.hold = gate
        val holder = rig.open(visible = false)
        submit(holder)
        val controller = holder.model.controller
        controller.onVisibility(true)
        idle()
        // 允许实现把入场提交延后到首次可见；只有确有旧请求，才制造“旧请求在覆盖后到达”的窗口。
        val lateWhileCovered = covered && rig.api.writes.isNotEmpty()
        println("F-AND-47 code=$code covered=$covered lateWhileCovered=$lateWhileCovered")
        if (covered) {
            controller.onVisibility(false)
            if (!lateWhileCovered) controller.onVisibility(true)
        }
        rig.api.hold = null
        gate.complete(Unit)
        FlowWait.until(::idle) { rig.api.writes.isNotEmpty() }
        rig.settle(holder)
        if (lateWhileCovered) {
            val hiddenDialog = controller.flow.state.value.riskDialog
            val hiddenRoutes = rig.navigation.routes.toList()
            assertEquals("被覆盖时不能弹窗", null, hiddenDialog)
            assertEquals("被覆盖时不能导航", emptyList<String>(), hiddenRoutes)
            controller.onVisibility(true)
            controller.onResume()
            rig.settle(holder)
        }
        val dialog = controller.flow.state.value.riskDialog
        val routes = rig.navigation.routes.toList()
        val writes = rig.api.writes.toList()
        if (code == 44001) {
            val window = if (lateWhileCovered) "被覆盖，回来不补弹" else "仍可见，照常弹风控窗"
            assertEquals("首次可见后$window", !lateWhileCovered, dialog != null)
            assertEquals("风控窗不发引导导航", emptyList<String>(), routes)
        } else {
            assertEquals("GUIDE 不应变成风控窗", null, dialog)
            val expectedRoutes = if (lateWhileCovered) emptyList<String>() else listOf("LaborAgreement")
            assertEquals("首次可见后的 GUIDE 导航次数与目标", expectedRoutes, routes)
            if (lateWhileCovered) assertEquals("被覆盖的引导保留手动入口", "LaborAgreement", controller.flow.state.value.guideRoute)
        }
        assertEquals("弹窗、导航或回前台本身不新增资金写", 1, writes.size)
    }

    @Test fun fand47_entryRiskAnsweredWhileVisibleStillOpensTheDialog() = entryResult(44001, covered = false)

    @Test fun fand47_entryRiskAnsweredAfterACoverDoesNotOpenOnReturn() = entryResult(44001, covered = true)

    @Test fun fand47_entryGuideAnsweredWhileVisibleStillNavigates() = entryResult(10004, covered = false)

    @Test fun fand47_entryGuideAnsweredAfterACoverDoesNotNavigateOnReturn() = entryResult(10004, covered = true)

    private companion object {
        const val USER = "user-a"
        const val ENTRY = "entry-withdraw-0721"
        const val IN_FLIGHT = "withdraw.in_flight"
        const val RULES =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,""" +
                """"est_net_fen":1000,"quick_amounts_fen":[500,1000]}"""
        const val PAYOUT =
            """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三",""" +
                """"change_remaining_this_month":2}"""
        const val WITHDRAWN = """{"withdrawal_id":"W0721"}"""

        fun ok(json: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(json), "trace-000721", null)

        fun failure(
            code: Int,
            forced: Boolean = false,
        ): ApiResult =
            ApiResult.Failure(
                code,
                "",
                Json
                    .parseToJsonElement(
                        when (code) {
                            10004 -> """{"consent_type":"labor_agreement"}"""
                            20001 -> """{"fields":["amount_fen"]}"""
                            else -> "{}"
                        },
                    ).jsonObject,
                "trace-000721",
                ErrorEffect(code, if (forced) ErrorEffectKind.FORCE_UPDATE else ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )
    }
}
