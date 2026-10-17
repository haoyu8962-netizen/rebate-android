@file:Suppress("MagicNumber")

package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
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
import com.couli.feature.wallet.FlowHost
import com.couli.feature.wallet.FormOwner
import com.couli.feature.wallet.SensitiveFlow
import com.couli.feature.wallet.WalletApi
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0718 终版补测二（G07「独立操作锁」，资金核心锁）：把 Codex 离线探针 impl-r3-flow-probe.kt 的断言补成正式单测。
 *
 * 被测对象是生产的 [SensitiveFlow]（提现与收款账号共用的写流程），依赖取自真实 AppWallet（env、闸门、token 缓存、未决记录通知、会话级执行作用域与 App
 * 同一份）；宿主回调 [FlowHost] 由测试实现，在流程交给宿主的每个时点（发起、共享结算、成功采用、非成功采用）记录此刻的锁状态，并立即尝试一切写入口
 * （提交、确认、放弃二次确认、放弃）。写请求在端口入口同步计数，每一笔都挂在独立的 [CompletableDeferred] 上，由测试按顺序放行。
 *
 * - 发起时已持锁（M42）；结果采用期间（共享结算、成功 / 非成功回调）锁不提前释放、FlowState.canSubmit 为 false，期间任何写入口都不发（M43、M45–M47）；
 * - 在途时本机记录被清空 / 换键再同步：阶段与待确认版本不变，不可提交（M44）；
 * - 旧表单请求的收尾不释放新表单的锁（M49）；
 * - 二次验证后迟到的确认续发与本页另一笔写重叠时不重入（M50）；
 * - 采用完毕后释放锁、写请求恰好 1 次。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0718FlowLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = pageScope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val identity = SessionIdentity(known = true, account = USER, loginEpoch = 1L, partition = "login-1")

    /** 钱包端口：资金写（提交 / 保存 / 确认 / 放弃）入口同步计数，每笔挂起等测试放行；二次验证发码、验证即答。 */
    private class Api : WalletApi {
        val writes = mutableListOf<String>()
        val answers = mutableListOf<CompletableDeferred<ApiResult>>()

        @Volatile var pending: PendingKeyRecord? = null

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending?.takeIf { it.operationId == operationId }

        override fun restorePending() = true

        private suspend fun write(name: String): ApiResult {
            writes += name
            return CompletableDeferred<ApiResult>().also { answers += it }.await()
        }

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = write("confirm:$operationId")

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = write("abandon:$operationId")

        override suspend fun send(call: ApiCall): ApiResult =
            when (call.operationId) {
                "sendSmsCode" -> ok("""{"resend_after_sec":60,"expires_in_sec":300}""")
                "stepUp" -> ok("""{"step_up_token":"fixture-step-up-token","expire_at":"2099-01-01T00:00:00+08:00"}""")
                WITHDRAW, PAYOUT -> write(call.operationId)
                else -> CompletableDeferred<ApiResult>().await()
            }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private fun appWallet(api: Api) =
        AppWallet(
            api = api,
            store = null,
            routing =
                AppRouting(
                    context,
                    EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
                    initialUpdate = UpdateCheck.OK,
                ).also { it.updateSession(SessionState.PHONE_BOUND, resumePending = false) },
            sessionEpoch = { identity.loginEpoch },
            account = { identity.account },
            writer = { true },
            forceUpdate = {},
            cache = null,
            mask = null,
            realname = null,
            labor = null,
            stepUpPhone = { "13800005678" },
            partitionTracked = true,
            identity = { identity },
        ).also { it.onSessionChanged() }

    /** 一个写流程与它的探测宿主。 */
    private inner class Rig(
        val op: String,
    ) {
        val api = Api()
        val wallet = appWallet(api)
        val violations = mutableListOf<String>()
        var successes = 0
        var starts = 0
        lateinit var flow: SensitiveFlow

        /** 一切写入口（提交、确认、放弃二次确认、放弃），每次都用此刻的表单身份。 */
        fun retryAll(key: String = ORIGINAL) {
            flow.submit("{}", "{}", flow.owner)
            flow.confirm(key, flow.owner)
            flow.askAbandon(key, flow.owner)
            flow.abandon(key, flow.owner)
        }

        private fun locked(where: String) {
            val s = flow.state.value
            if (!s.operationInFlight ||
                s.canSubmit
            ) {
                violations += "$where：operationInFlight=${s.operationInFlight} canSubmit=${s.canSubmit}"
            }
        }

        val host =
            object : FlowHost {
                override fun onWriteStarted() {
                    starts += 1
                    locked("发起回调")
                    retryAll()
                }

                override fun onSharedSuccess(
                    account: String,
                    epoch: Long,
                ) {
                    locked("共享结算")
                    retryAll()
                }

                override fun onSettled() {
                    locked("非成功采用")
                    retryAll()
                }

                override fun onSucceeded(
                    data: JsonObject?,
                    alreadyDone: Boolean,
                ) {
                    locked("成功采用")
                    retryAll()
                    successes += 1
                }
            }

        init {
            val withdraw = op == WITHDRAW
            val deps = wallet.deps
            flow =
                SensitiveFlow(
                    deps,
                    pageScope,
                    operationId = op,
                    action = if (withdraw) "withdraw" else "payout_account_change",
                    outcomeOp = if (withdraw) "withdraw" else "payout_account",
                    gate = if (withdraw) deps.gates.withdraw else deps.gates.payout,
                    host = host,
                    alive = { true },
                    initialOwner = FormOwner.of(identity),
                )
        }

        fun withPending(key: String = ORIGINAL) {
            api.pending = record(op, key)
            flow.refreshPending()
            idle()
            assertEquals(
                "前提：待确认卡",
                key,
                flow.state.value.pending
                    ?.key,
            )
        }
    }

    private fun record(
        op: String,
        key: String = ORIGINAL,
    ) = PendingKeyRecord(key, op, "{}", USER, "{}", stateRaw = PendingKeyStore.UNKNOWN, everUnknown = true)

    /**
     * M42 / M43 / M44 / M45 / M46 / M47：提现与收款保存 × {提交、确认上一笔、放弃得原成功、放弃得已作废}：
     * 发起回调时已持锁；在途时记录清空 / 换键再同步，阶段与待确认版本不变、不可提交，任何写入口都不发；共享结算、成功 / 非成功采用回调里锁仍在、
     * canSubmit 为 false、再试写入口都不发；采用完毕后释放锁；写请求恰好 1 次。
     */
    @Test
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // 两个操作 × 四种结局 × 两种在途同步，逐项收集不符后一次断言
    fun fand0718_g07_theOperationLockHoldsFromStartUntilTheResultIsAdopted() {
        val misses = mutableListOf<String>()
        for (op in listOf(WITHDRAW, PAYOUT)) {
            for (kind in listOf("send", "confirm", "abandon", "abandoned")) {
                val label = "$op/$kind"
                val rig = Rig(op)
                val flow = rig.flow
                if (kind != "send") rig.withPending()
                when (kind) {
                    "send" -> {
                        flow.submit("{}", "{}", flow.owner)
                    }

                    "confirm" -> {
                        flow.confirm(ORIGINAL, flow.owner)
                    }

                    else -> {
                        flow.askAbandon(ORIGINAL, flow.owner)
                        flow.abandon(ORIGINAL, flow.owner)
                    }
                }
                idle()
                assertEquals("$label 前提：发起 1 次", 1, rig.starts)
                assertEquals("$label 前提：已发出 1 笔", 1, rig.api.writes.size)
                val before = flow.state.value
                for (next in listOf(null, record(op, "other-key"))) {
                    rig.api.pending = next
                    flow.refreshPending()
                    idle()
                    val now = flow.state.value
                    if (now.phase != before.phase || now.pendingVersion != before.pendingVersion) {
                        val what = if (next == null) "空记录" else "换键"
                        val change = "${before.phase}/${before.pendingVersion} → ${now.phase}/${now.pendingVersion}"
                        misses += "$label：在途同步${what}改了阶段 / 版本（$change）"
                    }
                    if (now.canSubmit) misses += "$label：在途同步后可提交"
                    rig.retryAll()
                    rig.retryAll("other-key")
                    idle()
                }
                rig.api.pending = null
                val answer =
                    if (kind == "abandoned") ApiResult.Abandoned(ORIGINAL, "trace-000123") else ok("""{"withdrawal_id":"W1"}""")
                rig.api.answers
                    .first()
                    .complete(answer)
                idle()
                if (rig.api.writes.size != 1) misses += "$label：写请求 ${rig.api.writes}"
                if (rig.violations.isNotEmpty()) misses += "$label：${rig.violations}"
                if (rig.successes != (if (kind == "abandoned") 0 else 1)) misses += "$label：成功采用 ${rig.successes} 次"
                if (flow.state.value.operationInFlight) misses += "$label：采用完毕仍未释放锁"
            }
        }
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * M49：确认上一笔在途时表单按新身份重建（同一进程），新表单另发一笔确认；旧表单的确认先回来（成功）——它的收尾不得释放新表单的锁：新表单仍持锁、
     * 不可提交、任何写入口都不发；旧结果不算本表单的成功（R2-04 口径）。新表单的确认回来后才释放。
     */
    @Test
    fun fand0718_g07_anOldFormsFinallyNeverReleasesTheNewFormsLock() {
        val rig = Rig(WITHDRAW)
        val flow = rig.flow
        rig.withPending()
        flow.confirm(ORIGINAL, flow.owner)
        idle()
        rig.api.pending = record(WITHDRAW, "next")
        flow.onSessionChanged(FormOwner.of(identity))
        idle()
        flow.confirm("next", flow.owner)
        idle()
        assertEquals("前提：旧、新表单各发 1 笔确认", 2, rig.api.writes.size)
        rig.api.answers[0].complete(ok("""{"withdrawal_id":"W0"}"""))
        idle()
        val s = flow.state.value
        assertTrue("旧表单收尾后新表单仍持锁", s.operationInFlight)
        assertFalse("旧表单收尾后新表单不可提交", s.canSubmit)
        rig.retryAll("next")
        idle()
        assertEquals("旧表单收尾后任何写入口都不发", 2, rig.api.writes.size)
        assertEquals("旧结果不算新表单的成功", 0, rig.successes)
        rig.api.pending = null
        rig.api.answers[1].complete(ok("""{"withdrawal_id":"W2"}"""))
        idle()
        assertEquals(1, rig.successes)
        assertFalse("新表单采用完毕后释放", flow.state.value.operationInFlight)
        assertEquals(2, rig.api.writes.size)
    }

    /**
     * M50：【确认上一笔】得到 10003，打开二次验证面板（本笔已采用、锁已释放）；面板还开着时用户在本页【放弃上一笔】（另一笔写在途）；随后验证通过，
     * 迟到的确认续发与在途的放弃重叠——续发不得重入：放弃回来之前只有「确认、放弃」两笔写。
     */
    @Test
    fun fand0718_g07_aLateStepUpReplayNeverOverlapsAnotherWriteInFlight() {
        val rig = Rig(WITHDRAW)
        val flow = rig.flow
        rig.withPending()
        flow.confirm(ORIGINAL, flow.owner)
        idle()
        rig.api.answers[0].complete(fail(STEP_UP))
        idle()
        assertNotNull("前提：二次验证面板已打开", flow.stepUp.state.value)
        assertFalse("前提：确认已采用、锁已释放", flow.state.value.operationInFlight)
        flow.askAbandon(ORIGINAL, flow.owner)
        flow.abandon(ORIGINAL, flow.owner)
        idle()
        assertEquals("前提：放弃在途", listOf("confirm:$WITHDRAW", "abandon:$WITHDRAW"), rig.api.writes)
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        idle()
        assertEquals("续发不与在途的放弃重叠", listOf("confirm:$WITHDRAW", "abandon:$WITHDRAW"), rig.api.writes)
        rig.api.pending = null
        rig.api.answers[1].complete(ApiResult.Abandoned(ORIGINAL, "trace-000123"))
        idle()
        assertEquals(listOf("confirm:$WITHDRAW", "abandon:$WITHDRAW"), rig.api.writes)
    }

    private companion object {
        const val USER = "user-a"
        const val ORIGINAL = "original"
        const val WITHDRAW = "createWithdrawal"
        const val PAYOUT = "savePayoutAccount"
        const val STEP_UP = 10003

        fun ok(json: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(json), "trace-000123", null)

        fun fail(code: Int) =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                "trace-000123",
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )
    }
}
