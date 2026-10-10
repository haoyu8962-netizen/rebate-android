package com.couli.app.privacy

import com.couli.core.privacy.CredentialAccessGuard
import com.couli.core.privacy.PrivacyDecision
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyGateState
import com.couli.core.privacy.PromptStage
import com.couli.core.router.PrivacyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用户在首启弹窗 / 二次说明里做出的明确决定（与隐私结论是否变化无关）。 */
enum class ConsentEvent {
    /** 点了【同意】且已同意当前版本：完成了一个前置步骤（R1-03）。 */
    AGREED,

    /** 二次说明里点了【仍不同意】（首启或基本模式下补同意）：本次补同意对应的待办不可达（R1-04）。 */
    DECLINED,
}

/**
 * App 对 [PrivacyGate] 的接线（APP-02b）：界面读 [state]，按钮调这里的动作；已同意当前版本且环境闸门已就绪时调
 * [PrivacyGate.onConsented] 初始化（03 §4.4 冷启动顺序：环境 → 隐私 → Tab）。
 *
 * 每次动作之后按固定顺序通知（同步，主线程）：
 * 1. [onConclusionChanged]（beforeRouting = true）：外壳先收紧（进入基本模式时清导航栈、切回基本模式页，R1-06），
 *    再让 Router 往外壳里打开页面；
 * 2. [onConclusionChanged]：隐私结论变化（AppModule 接到 AppRouting.privacy：Router.onPrivacyChanged、重排恢复）；
 * 3. [onConsentEvent]：明确的【同意】/【仍不同意】（AppModule 接到 AppRouting.onConsentCompleted / onConsentDeclined）。
 */
@Suppress("TooManyFunctions") // 闸门的每个动作一个入口
class AppPrivacy(
    private val gate: PrivacyGate,
    private val environmentSettled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(gate.state(clock()))

    /** 闸门状态（决定、是否要弹窗、弹哪个）。 */
    val state: StateFlow<PrivacyGateState> = mutableState.asStateFlow()

    /** 交给 Router / 启动意图排队器的隐私结论。 */
    val conclusion: PrivacyState get() = state.value.toRouterPrivacy()

    /** 首启弹窗 / 二次说明正在显示（含基本模式下补同意）：运行中收到的深链与推送要丢弃（03 §4.4，R1-05）。 */
    val promptShowing: Boolean get() = state.value.mustPrompt

    private val shellListeners = mutableListOf<(PrivacyState) -> Unit>()
    private val listeners = mutableListOf<(PrivacyState) -> Unit>()
    private val eventListeners = mutableListOf<(ConsentEvent) -> Unit>()

    /**
     * 隐私结论每次变化时回调，返回取消订阅的函数。AppModule 接到 AppRouting.privacy（触发 Router.onPrivacyChanged 与
     * pending_action 恢复）；外壳用 [beforeRouting] = true 订阅，先于 Router 收到通知（R1-06：先收紧外壳，再让 Router 往里打开页面）。
     */
    fun onConclusionChanged(
        beforeRouting: Boolean = false,
        listener: (PrivacyState) -> Unit,
    ): () -> Unit {
        val target = if (beforeRouting) shellListeners else listeners
        target += listener
        return { target -= listener }
    }

    /** 明确的【同意】/【仍不同意】，在结论回调之后回调。 */
    fun onConsentEvent(listener: (ConsentEvent) -> Unit) {
        eventListeners += listener
    }

    /** 冷启动（Application.onCreate，环境闸门对账之后）。 */
    fun coldStart() = update { gate.coldStart(clock()) }

    fun agree() =
        update {
            val wasPrompting = gate.state(clock()).mustPrompt
            gate.agree(clock())
            if (wasPrompting && gate.state(clock()).fullyConsented) ConsentEvent.AGREED else null
        }

    fun disagree() = update { gate.disagree(clock()) }

    fun stillDisagree() =
        update {
            val wasSecond = gate.state(clock()).promptStage == PromptStage.SECOND
            gate.stillDisagree(clock())
            if (wasSecond && !gate.state(clock()).mustPrompt) ConsentEvent.DECLINED else null
        }

    /** 基本模式点【开启完整功能】，或 Router 要求补同意（基本模式下点需同意的功能）。 */
    fun requestFullFunction() = update { gate.requestFullFunction(clock()) }

    /**
     * 撤回同意（BR-ID-13；隐私中心页面在 APP-12）。清理失败时仍进入基本模式并留下「待清理」标记（下次启动、读凭据前重试，
     * R1-01），错误继续抛给调用方。
     */
    fun withdraw() = update { gate.withdraw(clock()) }

    /** 本机已同意的隐私政策版本（APP-12 撤回记录的 version）；没有为 null。 */
    fun consentedVersion(): String? = gate.consentedVersion()

    /** 会话层读凭据前调 [CredentialAccessGuard.readable]（R1-01）：撤回清理未完成时先重试，仍未完成返回 false（当作没有凭据）。 */
    val credentialAccess: CredentialAccessGuard get() = gate.credentialAccess

    /**
     * 已同意当前版本、环境已就绪时初始化 SDK（闸门保证同一同意周期只一次）。冷启动、同意之后、环境闸门【重试】成功之后各调一次。
     */
    fun initializeIfReady() {
        if (environmentSettled() && state.value.fullyConsented) gate.onConsented(clock())
    }

    private fun update(action: () -> Any?) {
        val before = conclusion
        var event: ConsentEvent? = null
        try {
            event = action() as? ConsentEvent
        } finally {
            mutableState.value = gate.state(clock())
            val after = conclusion
            if (after != before) {
                shellListeners.toList().forEach { it(after) }
                listeners.toList().forEach { it(after) }
            }
            event?.let { happened -> eventListeners.forEach { it(happened) } }
            initializeIfReady()
        }
    }
}

/**
 * 闸门状态 → Router 的隐私状态：
 * - 基本模式（含基本模式下点了【开启完整功能】、弹窗还没结论）→ BASIC_MODE；
 * - 已同意当前版本 → CONSENTED；
 * - 其余（首次安装、隐私政策升级后的重新同意尚无结论）→ NOT_DECIDED：启动意图与 pending_action 等待。
 */
fun PrivacyGateState.toRouterPrivacy(): PrivacyState =
    when {
        decision == PrivacyDecision.BASIC_MODE -> PrivacyState.BASIC_MODE
        fullyConsented -> PrivacyState.CONSENTED
        else -> PrivacyState.NOT_DECIDED
    }
