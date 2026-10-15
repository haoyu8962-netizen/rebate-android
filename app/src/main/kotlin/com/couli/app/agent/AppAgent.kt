package com.couli.app.agent

import android.os.Handler
import android.os.Looper
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.app.trade.AppTrade
import com.couli.core.agent.AgentCancelDispatch
import com.couli.core.agent.AgentCancelLedger
import com.couli.core.agent.AgentConsentLapse
import com.couli.core.agent.AgentContextHandoff
import com.couli.core.agent.AgentContextSource
import com.couli.core.agent.AgentContextTicket
import com.couli.core.agent.AgentContractPort
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentDeps
import com.couli.core.agent.AgentGates
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentIdentity
import com.couli.core.agent.AgentIdentityChange
import com.couli.core.agent.AgentJson
import com.couli.core.agent.AgentOwnershipEvent
import com.couli.core.agent.AgentOwnershipHandlers
import com.couli.core.agent.AgentOwnershipStop
import com.couli.core.agent.AgentProductRef
import com.couli.core.agent.AgentProductView
import com.couli.core.agent.AgentSyncAction
import com.couli.core.agent.AgentSyncPolicy
import com.couli.core.agent.AgentTimer
import com.couli.core.agent.AgentWithdrawFlow
import com.couli.core.agent.AgentWithdrawOutcome
import com.couli.core.agent.toAgentResult
import com.couli.core.clientcore.ClientEvent
import com.couli.core.me.shanghaiIso
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.SessionTokens
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.wallet.WalletRateGate
import com.couli.feature.agent.AgentPageMemory
import com.couli.feature.agent.AgentToast
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.PurchasePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 主线程 Handler 计时（生产）。 */
class MainAgentTimer : AgentTimer {
    private val main = Handler(Looper.getMainLooper())

    override fun after(
        delayMs: Long,
        block: () -> Unit,
    ): () -> Unit {
        val runnable = Runnable(block)
        main.postDelayed(runnable, delayMs)
        return { main.removeCallbacks(runnable) }
    }
}

/**
 * App 侧的 Agent 页（APP-10 Android）：组合 Core 的 [AgentController]（流协议、票据、卡片、同意）、待定 operation 的端口（debug / staging 本机假服务，
 * Release 没有）、Router、购买执行器、金额隐藏、埋点队列、强更分流；页面在 feature:agent（AgentScreen）。
 *
 * - **身份**（[identity]）：隐私结论 + 会话状态 + 账号 + 登录世代 + 隐私 / 环境世代 + 环境名 + AI 同意世代 + 页面实例，现算只用于**比较**；控制器构造时取一次。会话、
 *   隐私、环境变化时 [sync] 在同一个同步出口里作废旧控制器（关连接、停计时、清消息与金额，不发 cancel，G06）并按新身份**重建**（从空对话开始，APP-12 第 18 条）。
 * - **撤回隐私同意、切换环境**：登记为清理器的第一步（[onConsentWithdrawing]、[onEnvironmentSwitching]），清理开始即同步作废。对话只在内存，没有要删的本机数据。
 * - **可见性**（APP-10 共性第 2、17 条，APP-11 第 18、22 条）：Agent Tab 选中（外壳在提交切 Tab 的同步出口里先写目标值）、Tab 外壳之上没有别的页面（回退栈的同步出口）、
 *   App 在前台；任一变了在同一个同步出口通知控制器（离开即撤销本次进入世代：关连接、停计时）与本页购买执行器。Tab 根不随切 Tab 销毁控制器（对话保留在内存）。
 * - **页面实例**：Agent 页真正销毁（Activity 结束，配置重建除外）时 [onPageDestroyed]：page_destroyed（清空消息，不发 cancel），下次进入从空对话开始。
 * - APP-10b（F-X-04）：Debug / Release 同一个真实端口（ApiAgentContractPort，经 ApiClient 管线；Debug / Staging 的本机假服务在 ApiClient 的实际传输入口按
 *   DebugMockGate 换入）。「未开放」只看 `agent.entry.visible`、`config.agent.enabled` 与本次进入收到的 30501（[available]）；关闭时不发任何 agent 请求，
 *   `agent.entry.visible=false` 时底部 Tab 换「搜索」、首页 / 详情 / 订单详情的「问 AI」入口随之隐藏（G32、G56）。
 * - APP-10b 应用级：三组 Retry-After 闸门（[gates]，按不含页面实例的归属保存，同归属重建保留，鸿蒙 R3-03）、cancel 账本、归属协调器（退出登录 / 撤回 AI 同意 /
 *   撤回隐私的确认入口先停流并以原身份尽力发 1 次 cancel，切环境只停流，G01）、「身份变更在途」（期间不重建、不准入，R1-03）、「AI 同意失效」（所有来源的 AI 10004
 *   按不含页面实例的归属汇入，R3-01）。
 * 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList")
class AppAgent(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val routing: AppRouting,
    private val privacy: AppPrivacy,
    /** 七个 agent operation 的端口（APP-10b：生产是 ApiAgentContractPort，Debug / Release 同一份；测试可注入替身）。为 null 时「未开放」。 */
    private val port: () -> AgentContractPort?,
    private val envKey: () -> String,
    private val configData: () -> JsonObject?,
    val mask: StateFlow<Boolean>,
    private val track: (ClientEvent) -> Unit,
    private val serverForced: () -> Unit,
    /** 购买（APP-06b）；没有接（只测外壳的单测）时购买按钮不可用。 */
    val trade: AppTrade?,
    /** 「粘贴查返利」：用户点击时读一次剪贴板文字（FU-CLIP 的读取接口与敏感页规则）。 */
    val paste: () -> String? = { null },
    /** recordConsent 记录结果后通知变体（debug 假服务记下「AI 同意已记录 / 已撤回」）。 */
    private val consentRecorded: (Boolean) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val timer: AgentTimer = MainAgentTimer(),
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** APP-10 的共享 42901 截止时间（旧接线与测试观察用；APP-10b 起七个 operation 按 [gates] 三组分开）。 */
    val gate = WalletRateGate(scope, clock)

    /**
     * 三组 Retry-After 闸门（发送 / 读取 / 动作，G24、APP-12 第 5、22 条）：按此刻不含页面实例的归属判定；同归属的控制器作废 / 页面重建保留截止时间，归属真正改变才清理
     * （[AgentGates.retain]，鸿蒙 R3-03）。
     */
    val gates: AgentGates = AgentGates.create(clock, { runCatching { identity().ownerKey }.getOrNull() }, scope)

    /** 同一 run 只发 1 次 cancel（【停止】与归属协调器共用，跨页面实例）。 */
    private val ledger = AgentCancelLedger()

    /** 应用级「AI 同意失效」（鸿蒙 R2-02、R3-01）。 */
    val consentLapse = AgentConsentLapse()

    /** 应用级「身份变更在途」（鸿蒙 R1-03）。 */
    val identityChange = AgentIdentityChange()

    /** 归属协调器（G01）：结果只结算共享事实（42901 写动作闸门、10405 强更、AI 10004 → 应用级失效），不导航、不弹框。 */
    private val ownershipStop =
        AgentOwnershipStop(
            ledger,
            AgentOwnershipHandlers(
                rateLimited = { owner, ms -> if (runCatching { identity().ownerKey }.getOrNull() == owner) gates.action.start(ms) },
                forceUpdate = { serverForced() },
                aiConsentLost = { owner -> lapse(owner) },
                log = log,
            ),
            scope,
        )

    /** 设置页撤回 AI 同意的 42901 禁用期（鸿蒙 H30：本入口独立，不并入四个 operation 的共享闸门）。 */
    val withdrawGate = WalletRateGate(scope, clock)

    /** 统一客服出口（APP-12；AppModule 接 AppMe）。 */
    var customerService: () -> Unit = {}

    /** 草稿与滚动位置（只在内存）。 */
    val memory = AgentPageMemory()

    private val mutableController = MutableStateFlow<AgentController?>(null)
    val controller: StateFlow<AgentController?> = mutableController.asStateFlow()

    private val mutableToast = MutableStateFlow<AgentToast?>(null)
    val toast: StateFlow<AgentToast?> = mutableToast.asStateFlow()
    private var toastIds = 0L

    // ---------------------------------------------------------------- 可见性与阻挡层

    private var tabSelected = false
    private var stackEmpty = true
    private var foreground = true
    private val mutableVisible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()
    private val mutableBlocked = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = mutableBlocked.asStateFlow()
    private val mutableReader = MutableStateFlow(false)
    val screenReader: StateFlow<Boolean> = mutableReader.asStateFlow()
    private var blockerSource: () -> Boolean = { false }

    /** 阻挡层（隐私弹窗、强更、更新提示、半屏）：来源现算，[changes] 变化时复核（同意弹窗随之收起 / 再显示，共性第 5 条）。 */
    fun connectBlockers(
        active: () -> Boolean,
        changes: Flow<*>,
    ) {
        blockerSource = active
        mutableBlocked.value = runCatching(active).getOrDefault(false)
        scope.launch { changes.collect { mutableBlocked.value = runCatching(blockerSource).getOrDefault(false) } }
    }

    fun setScreenReader(on: Boolean) {
        mutableReader.value = on
    }

    /** 外壳提交切 Tab 的同步出口（改选中项之前，APP-11 第 22 条、APP-12 第 28 条）。 */
    fun onTab(route: String) {
        tabSelected = route == AGENT_ROUTE
        visibilityChanged()
    }

    /** 回退栈变了（主线程、同一条消息）：Tab 外壳之上有没有别的页面。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        val top = entries.lastOrNull { it.destination !is NavGraph }
        stackEmpty = top == null || top.destination.hasRoute(HomeDestination::class)
        visibilityChanged()
    }

    fun onForeground(value: Boolean) {
        foreground = value
        // 进后台暂停全部历史重载（03 §7.2、G38、鸿蒙 R1-08）；回前台不续
        mutableController.value?.onForeground(value)
        visibilityChanged()
    }

    /** 只读同步写入的目标值（不读转场后才更新的选中项，APP-11 第 22 条）。 */
    fun visibleNow(): Boolean = tabSelected && stackEmpty && foreground

    private fun visibilityChanged() {
        val now = visibleNow()
        val changed = mutableVisible.value != now
        mutableVisible.value = now
        mutableController.value?.let { c ->
            c.setVisible(now)
            if (now) c.start()
        }
        // 本页购买执行器：被盖住、切走、进后台之后到达的 open 响应只更新卡片（与发起页 ON_STOP 同一语义）；只在可见性真正变化时通知
        if (changed) purchase?.let { if (now) it.controller.onForeground() else it.controller.onBackground() }
    }

    // ---------------------------------------------------------------- 身份

    /** AI 服务同意世代（设置页撤回成功 +1：控制器按新身份重建，下次进入由服务端 10004 弹同意框）。 */
    private var aiConsentGeneration = 0L

    /** 页面实例编号（Agent 页真正销毁后 +1）。 */
    private var pageSeq = 1L

    /** 此刻的身份（现算；只用于比较与新控制器的构造）。 */
    fun identity(): AgentIdentity {
        val level =
            when {
                privacy.conclusion != PrivacyState.CONSENTED -> AgentIdentity.BASIC
                privacy.credentialAccess.cleanupPending || routing.session.value == SessionState.GUEST -> AgentIdentity.GUEST
                else -> AgentIdentity.MEMBER
            }
        return AgentIdentity(
            env = envKey(),
            level = level,
            account = if (level == AgentIdentity.MEMBER) client.activeUserId() else null,
            loginEpoch = tokens.loginEpoch,
            epochs = client.inFlight.epochs(),
            aiConsent = aiConsentGeneration,
            page = pageSeq,
        )
    }

    /** Agent 页已经显示过（之后身份变化时立即按新身份重建）。 */
    private var wanted = false

    /** Agent 根页组合时调用：没有控制器就按此刻的身份建一个。 */
    fun ensure(): AgentController {
        wanted = true
        sync()
        return mutableController.value ?: build(identity())
    }

    /** 上一次构造控制器时的可用性（配置变化而身份没变也要同步，鸿蒙 R1-07）。 */
    private var builtAvailable = true

    /** 上一次构造的是「身份变更在途」期间的挂起态（流程结束后必须重建）。 */
    private var builtSuspended = false

    /**
     * 会话、隐私、环境、配置变化后同步（同一同步出口，[AgentSyncPolicy]）：身份或可用性变了 → 作废旧控制器，已显示过 Agent 页时立即按新身份与配置重建；「身份变更在途」
     * （退出登录 / 撤回 AI 同意 / 撤回隐私的请求还没落定）期间只作废、不重建（鸿蒙 R1-03）。
     */
    fun sync() {
        // 隐私结论、身份、配置变化都经这里：详情页【问问 AI】的绘制随之更新（R1-04）
        refreshAskVisible()
        val current = mutableController.value
        val next = identity()
        // 身份变化：销毁旧身份打开的撤回确认框（Android R1-01）
        mutableWithdrawConfirm.value?.let { if (it.identity.ownerKey != next.ownerKey) mutableWithdrawConfirm.value = null }
        val oldKey = current?.takeIf { it.alive() && !builtSuspended }?.boundIdentity
        val action = AgentSyncPolicy.decide(oldKey, builtAvailable, next, available(), identityChange.active, wanted)
        if (action == AgentSyncAction.KEEP) return
        // 购买执行器随归属变化释放（Android R1-02）；唯一的例外是游客点购买被 10001 拦下、登录完成（见 [loginResumable]）
        val resume =
            purchase?.takeIf {
                current != null && action == AgentSyncAction.REBUILD &&
                    loginResumable(current.boundIdentity, next, it)
            }
        if (resume != null) purchase = null
        if (current != null) invalidate()
        val rebuilt = if (action == AgentSyncAction.REBUILD) build(next) else null
        if (resume != null) {
            if (rebuilt != null) adoptPurchase(resume, rebuilt) else resume.release()
        }
    }

    private fun build(identity: AgentIdentity): AgentController {
        var built: AgentController? = null
        builtAvailable = available()
        builtSuspended = identityChange.active
        // 三组闸门按不含页面实例的归属保存（同归属重建保留，换归属才清，鸿蒙 R3-03）
        gates.retain(identity.ownerKey)
        val deps =
            AgentDeps(
                identity = identity,
                currentIdentity = ::identity,
                contract = if (builtAvailable) port() else null,
                gate = gate,
                gates = gates,
                ledger = ledger,
                currentOwnerKey = { runCatching { identity().ownerKey }.getOrNull() },
                aiConsentLost = { owner -> lapse(owner) },
                consentLapsed = consentLapse.blocks(identity.ownerKey),
                allowed = ::requestsAllowed,
                foreground = { foreground },
                suspended = builtSuspended,
                scope = scope,
                timer = timer,
                clock = clock,
                masked = mask,
                // 只在用户点击时调用；page_guide 与收益卡按钮的来源是 agent_card（EntryGuard 再按包内 agent_guide 校验、参数必须为空）
                open = { request -> routing.router.open(request) },
                buy = ::buy,
                customerService = { customerService() },
                recordConsent = { accepted -> recordConsent(accepted, CHANNEL_SHEET, identity) },
                track = track,
                toast = ::toast,
                forceUpdate = serverForced,
                // 共性第 3 条：【停止】、【新对话】作废本页购买执行器的在途尝试（只挡外跳；之后的响应带旧卡片世代，控制器不再更新卡片）
                // Android R1-02：同步释放绑定这个控制器的购买执行器（加载、确认框、授权、续执行、待办一并作废），下次点购买按新世代重建
                cancelPurchase = { built?.let { b -> if (purchase?.serial == b.serial) releasePurchase() } },
                // 额度用完时已绑手机的会员不再引导绑定（鸿蒙 H10）
                phoneBound = { if (routing.session.value == SessionState.PHONE_BOUND) true else null },
                log = log,
                examples = ::examples,
                filingText = ::filingText,
                // 还没交出的情境：只交给同一归属键的控制器（期间换号、撤回、切环境就丢弃，共性第 4 条）
                context = handoff.take(identity.ownerKey),
                // F-AND-28（FU-AND-0714 R1-04）：会员但账号读不出的控制器点【重试】——仍是当前控制器才同步作废（清空）并按此刻的身份重建
                rebuildIdentity = { built?.let(::rebuildAfterIdentityFailure) },
            )
        val c = AgentController(deps)
        built = c
        mutableController.value = c
        // 可见性在构造后立即同步写入（共性第 2 条）；可见才取当前会话
        c.setVisible(visibleNow())
        c.start()
        log("agent controller level=${identity.level} phase=${c.currentPhase}")
        return c
    }

    /**
     * 身份初始化失败后的重建（R1-04）：[failed] 仍是当前控制器时同步作废它（清空），Agent 页仍想要时按此刻的身份重建（账号读得出 → 正常加载；
     * 仍读不出 → 新的可恢复失败）。
     */
    private fun rebuildAfterIdentityFailure(failed: AgentController) {
        if (mutableController.value !== failed) return
        invalidate()
        if (wanted) build(identity())
    }

    /** 同步作废当前控制器（关连接、停计时、cancelOwned、清消息与金额、释放订阅；控制器自己不发 cancel，G06）。三组闸门同归属保留（R3-03）。 */
    fun invalidate() {
        val old = mutableController.value
        mutableController.value = null
        old?.dispose()
        releasePurchase()
        gate.reset()
        gates.retain(runCatching { identity().ownerKey }.getOrNull() ?: Unit)
        memory.clear()
        mutableToast.value = null
        mutableWithdrawConfirm.value = null
    }

    // ---------------------------------------------------------------- 可用性与应用级准入（APP-10b）

    /** 「未开放」之外（配置）：`agent.entry.visible` 与 `config.agent.enabled` 都没关（缺省可用；只认 false 为关，G32）。 */
    fun available(): Boolean = entryVisible() && agentEnabled()

    /** features.flags['agent.entry.visible']（缺省可见；只认 false 为关）。Tab 与「问 AI」入口按它裁剪（G32、G56）。 */
    fun entryVisible(): Boolean =
        runCatching {
            val flags = (configData()?.get(CONFIG_FEATURES) as? JsonObject)?.get(CONFIG_FLAGS) as? JsonObject
            AgentJson.boolOf(flags?.get(ENTRY_VISIBLE_FLAG)) != false
        }.getOrDefault(true)

    /** config.agent.enabled（缺省可用；只认 false 为关）。 */
    private fun agentEnabled(): Boolean = runCatching { AgentJson.boolOf(agentConfig()?.get(CONFIG_ENABLED)) != false }.getOrDefault(true)

    /**
     * 应用级准入（端口出口与控制器准入共用，线程安全）：身份变更在途、此刻归属处于「AI 同意失效」、配置关闭 → 不放行。捕获的那一次身份 cancel 与流程自己的请求不经它。
     */
    fun requestsAllowed(): Boolean =
        runCatching { !identityChange.active && !consentLapse.blocks(identity().ownerKey) && available() }.getOrDefault(false)

    /** 配置变化（Config 拉取完成、LKG 更新）：可用性变了就同步作废并重建（关闭时立即停流、取消所属请求、切「未开放」，鸿蒙 R1-07）。 */
    fun onConfigChanged() = sync()

    /**
     * 任何来源的 AI 10004（控制器内、【停止】cancel、身份事件 cancel）汇入应用级「AI 同意失效」（共性第 28、33 条，S0）：按不含页面实例的归属核对，此刻仍是同一归属才记下，
     * 并同步停下该归属此刻的控制器；不同归属整体丢弃。
     */
    private fun lapse(owner: Any) {
        consentLapse.lapse(owner, runCatching { identity().ownerKey }.getOrNull(), mutableController.value)
    }

    // ---------------------------------------------------------------- 身份事件（APP-10b G01，编排者补充第 1 条）

    /**
     * 退出登录、撤回 AI 同意、撤回隐私同意在**用户确认后的同步入口、发出该流程自己的请求之前**调用：先进入「身份变更在途」（期间不重建、不准入），同步停流（控制器作废：
     * 关连接、停计时、作废票据与卡片回调、清空对话），再以原身份尽力发一次 cancel（单次发送、不挂页面归属、不等结果）。返回写出信号：流程等它写出（或确定不写出，
     * 最多 [AgentCancelDispatch.WRITE_WAIT_MS]）再发自己的请求，保证 cancel 早于自身请求。流程结束（成功、失败、被拒）必须调 [endIdentityChange]。
     */
    fun beginIdentityChange(event: String): AgentCancelDispatch {
        identityChange.begin(event)
        val decision = ownershipStop.before(event, mutableController.value)
        handoff.clear()
        invalidate()
        return ownershipStop.dispatch(decision, if (available()) port() else null)
    }

    /** 身份事件流程结束：解除「身份变更在途」，按此刻身份与配置重建。 */
    fun endIdentityChange(event: String) {
        identityChange.end(event)
        sync()
    }

    /** 应用级 cancel 的发出次数（测试与诊断）。 */
    val ownershipCancels: Int get() = ownershipStop.sent

    /** 切换环境开始（清理器第一步之前）：作废控制器与还没交出的情境（之后的同步按新环境重建）。 */
    fun onEnvironmentSwitching() {
        handoff.clear()
        invalidate()
    }

    /** 撤回隐私同意（撤回清理名单里的第一批）：同步作废控制器与还没交出的情境；隐私结论变化后的 [sync] 按基本模式重建。 */
    fun onConsentWithdrawing() {
        handoff.clear()
        invalidate()
    }

    /** Agent 页真正销毁（Activity 结束，配置重建除外）：page_destroyed（清空消息，不发 cancel）；释放本页购买执行器。 */
    fun onPageDestroyed() {
        pageSeq += 1
        wanted = false
        invalidate()
    }

    /** 接 Router 的会话状态与隐私结论（AppModule 与集成测试共用）。 */
    fun connect() {
        privacy.onConclusionChanged { sync() }
        scope.launch { routing.session.drop(1).collect { sync() } }
    }

    private val mutableEntryVisible = MutableStateFlow(true)

    /** 底部 Agent Tab 与「问 AI」入口此刻是否显示（`agent.entry.visible`，G32、G56）；外壳与首页 / 详情页订阅它。 */
    val entryVisibleState: StateFlow<Boolean> = mutableEntryVisible.asStateFlow()

    /** 接 Config 变化（AppModule）：入口开关与可用性变了立即同步（关闭时停流、取消所属请求、切「未开放」，鸿蒙 R1-07）。 */
    fun connectConfig(changes: Flow<*>) {
        mutableEntryVisible.value = entryVisible()
        refreshAskVisible()
        scope.launch {
            changes.collect {
                mutableEntryVisible.value = entryVisible()
                refreshAskVisible()
                sync()
            }
        }
    }

    // ---------------------------------------------------------------- 购买

    /**
     * 本页的购买执行器（场景 agent）。Android R1-02（共性第 26 条）：一份执行器**绑定一个 Agent 控制器（固定身份 + 页面实例）与它的卡片动作世代**；
     * 控制器作废（会话失效、换号、撤回、切环境、页面销毁）或卡片动作世代推进（【停止】、【新对话】、流内 10004）时同步 [release]：在途请求取消、确认框 /
     * AuthSheet / 加载层 / 超时条随实例消失、续执行与授权许可作废、本页登记的购买待办删除。下一次点购买按新控制器、新世代新建一份。
     */
    private inner class PurchasePage(
        trade: AppTrade,
        /** 绑定的 Agent 控制器编号与卡片动作世代。 */
        var serial: Long,
        var epoch: Long,
    ) : PurchaseEnv {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller: OpenPurchaseController = trade.purchaseController(this, scope)
        private val owner = trade

        /**
         * 交给执行器的逻辑卡片（Android R2-01，共性第 34 条）：卡片快照里的 card_id 换成本执行器内唯一、不复用的逻辑编号，一个编号对应一个不可变的
         * [AgentProductRef]。执行器内部认「同一张卡」、尝试保存的原卡快照、超时后的原键重试判定、卡片回写都只按这个编号，跨轮重复的 card_id 不会串卡。
         */
        private val logical = mutableMapOf<AgentProductRef, String>()
        private val refs = mutableMapOf<String, AgentProductRef>()
        private var nextLogical = 0L

        fun logicalId(ref: AgentProductRef): String =
            logical.getOrPut(ref) {
                nextLogical += 1
                "$LOGICAL_PREFIX$nextLogical".also { refs[it] = ref }
            }

        /** 已交给过执行器的逻辑编号（按钮认卡用；没交过为 null）。 */
        fun knownId(ref: AgentProductRef): String? = logical[ref]

        /** 执行器给的卡片（尝试的原卡快照、回写目标）属于哪一张逻辑卡片。 */
        fun refOf(card: JsonObject): AgentProductRef? = AgentJson.stringOf(card["card_id"])?.let(refs::get)

        /** 旧卡片定位一并作废（编号不回收：之后的新编号不会撞上旧尝试的快照）。 */
        fun forget() {
            logical.clear()
            refs.clear()
        }

        override fun loggedIn(): Boolean = owner.loggedIn()

        override fun basicMode(): Boolean = privacy.conclusion == PrivacyState.BASIC_MODE

        override fun requestConsent() = owner.requestConsent()

        override fun platformStatus(platform: String): String? = owner.platformStatus(platform)

        override fun markPlatformStatus(
            platform: String,
            status: String,
        ) = owner.markPlatformStatus(platform, status)

        /** 只替换这一张卡（以 card_id 定位、渲染票据比较）；只交给绑定的那个控制器，离开 Agent 页后到达的 open 响应只更新卡片。 */
        override fun updateCard(
            target: JsonObject,
            transform: (JsonObject) -> JsonObject,
        ) = this@AppAgent.updateCard(this, target, transform)

        override fun open(
            route: String,
            params: JsonObject,
        ) = owner.open(route, params)

        override fun contactService() = customerService()

        /** Agent 卡片没有权威重取入口（卡片由流下发）：保持原样（PurchaseEnv 约定：不能重取时什么都不做）。 */
        override fun refetchCard(
            target: JsonObject,
            merge: (current: JsonObject, fresh: JsonObject) -> JsonObject,
        ) = Unit

        override fun helpLink(key: String): Pair<String, JsonObject>? = owner.helpLink(key)

        fun release() {
            forget()
            owner.release(controller)
            scope.cancel()
        }

        /** 这份执行器的当前尝试是被 10001 拦下、登记了登录待办（kind=login，同一 link_id 与幂等键）的那一次。 */
        fun awaitingLogin(): Boolean {
            val attempt = controller.currentAttempt
            val record = owner.pending.peek()
            return attempt != null &&
                record != null &&
                AgentJson.stringOf(record["kind"]) == PENDING_LOGIN &&
                AgentJson.stringOf(record["link_id"]) == attempt.linkId &&
                AgentJson.stringOf(record["idempotency_key"]) == attempt.key
        }
    }

    private var purchase: PurchasePage? = null
        set(value) {
            field = value
            mutablePurchase.value = value?.controller
        }
    private val mutablePurchase = MutableStateFlow<OpenPurchaseController?>(null)

    /** 本页此刻的购买执行器（还没点过购买、刚被释放、没有接购买为 null；页面按它画购买按钮、弹层与超时条）。 */
    val purchaseState: StateFlow<OpenPurchaseController?> = mutablePurchase.asStateFlow()

    /**
     * 按钮认卡（Android R2-01）：渲染的这件商品（按它的购买票据）在 [controller] 里的逻辑卡片快照；没交给过这份执行器时给一个认不上的空编号，
     * 加载、超时、未安装只落在这次尝试所属的那张卡上（42901 置灰仍对每一张卡）。
     */
    fun purchaseCardOf(
        controller: OpenPurchaseController,
        view: AgentProductView,
    ): JsonObject {
        val page = purchase?.takeIf { it.controller === controller }
        val id =
            view.buy
                ?.ref
                ?.let { page?.knownId(it) }
                .orEmpty()
        return JsonObject(view.json + (CARD_ID to JsonPrimitive(id)))
    }

    /** 本页此刻的购买执行器（测试观察用）。 */
    fun purchaseController(): OpenPurchaseController? = purchase?.controller

    /** 同步释放本页购买执行器（Android R1-02）。 */
    private fun releasePurchase() {
        val old = purchase ?: return
        purchase = null
        old.release()
    }

    /**
     * 游客登录续买（Android R1-02 的唯一例外，BR-ID-10，rule-gaps A16）：只在**游客 → 会员**（同一环境、同一 AI 同意世代、同一页面实例）且执行器的当前尝试
     * 正等着这次登录（Router 里的购买待办 kind=login、link_id 与幂等键都是这次尝试的）时，执行器不释放、改绑到按新身份重建的控制器；之后只由 AppTrade 的
     * 登录恢复按已有规则（归属、有效期、原键）自动执行一次。其余归属变化（会话失效、换号、退出、撤回、切环境）一律释放、删除待办。
     */
    private fun loginResumable(
        old: AgentIdentity,
        next: AgentIdentity,
        page: PurchasePage,
    ): Boolean =
        old.level == AgentIdentity.GUEST &&
            next.level == AgentIdentity.MEMBER &&
            old.env == next.env &&
            old.aiConsent == next.aiConsent &&
            old.page == next.page &&
            page.awaitingLogin()

    /** 续买的执行器改绑到新控制器（旧卡片的定位一并作废：新对话里没有那张卡）。 */
    private fun adoptPurchase(
        page: PurchasePage,
        controller: AgentController,
    ) {
        page.serial = controller.serial
        page.epoch = controller.cardActionEpoch
        page.forget()
        purchase = page
        if (!visibleNow()) page.controller.onBackground()
    }

    /** 这次点击用的执行器：绑定的控制器与卡片动作世代不是这张卡的 → 释放旧的、按这张卡的控制器与世代新建。 */
    @Suppress("ReturnCount") // 没有购买、仍是同一绑定、新建三个出口
    private fun pageFor(ref: AgentProductRef): PurchasePage? {
        val t = trade ?: return null
        purchase?.let { if (it.serial == ref.controller && it.epoch == ref.epoch) return it }
        releasePurchase()
        return PurchasePage(t, ref.controller, ref.epoch).also { purchase = it }
    }

    private fun buy(
        product: JsonObject,
        ref: AgentProductRef,
    ) {
        val page = pageFor(ref) ?: return
        val controller = page.controller
        // 超时之后再点「这次购买尝试所属的那张逻辑卡片」= 超时条上的【重试】：同一执行器、原尝试、原幂等键（BR-PRICE-13、03 §7.4，Android R1-03）。
        // 只认尝试原卡快照对应的整个不可变引用（控制器、对话、run、外层卡、商品卡、link_id、世代），不比 card_id（跨轮可重复，Android R2-01）
        val attemptRef = controller.currentAttempt?.card?.let(page::refOf)
        if (controller.state.value.phase == PurchasePhase.TIMEOUT && attemptRef == ref) {
            controller.retryAfterTimeout()
            return
        }
        // 别的卡：用它自己的商品快照（card_id 换成逻辑编号）发起新购买、新键。场景 agent：spm 照已有规则（页面.区块.位置）
        val snapshot = JsonObject(product + (CARD_ID to JsonPrimitive(page.logicalId(ref))))
        controller.buy(snapshot, BUY_SPM)
    }

    private fun updateCard(
        page: PurchasePage,
        target: JsonObject,
        transform: (JsonObject) -> JsonObject,
    ) {
        if (purchase !== page) return
        // 回写绑定原尝试：目标是尝试的原卡快照，按它的逻辑编号找回不可变引用，之后的点击不会把它改绑到别的卡（Android R2-01）
        val ref = page.refOf(target) ?: return
        mutableController.value?.takeIf { it.serial == ref.controller && it.serial == page.serial }?.updateProduct(ref, transform)
    }

    // ---------------------------------------------------------------- 同意

    /**
     * recordConsent{type: ai_third_party, version, accepted, channel, client_at}（经真实 ApiClient；绑定发起时的身份：归属与发送前闸门核对固定身份仍是此刻
     * 身份，expectedAccount 是发起时的账号）。失败保留完整结果（共性第 21 条）：42901 的 Retry-After（ApiClient 已解析成 disableMillis）、本地拒绝（没有发出）原样交回。
     * TODO(N-36)：G16 契约没有 AgentConsent 版本键，Debug / Staging 用代拟常量 1，提审前必须替换。
     */
    private suspend fun recordConsent(
        accepted: Boolean,
        channel: String,
        fixed: AgentIdentity,
    ): AgentHttpResult {
        val body =
            JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive(AI_CONSENT_TYPE),
                    "version" to JsonPrimitive(AI_CONSENT_VERSION),
                    "accepted" to JsonPrimitive(accepted),
                    "channel" to JsonPrimitive(channel),
                    "client_at" to JsonPrimitive(shanghaiIso(clock())),
                ),
            )
        // 页面身份没建立（F-AND-28：已登录却读不出账号）：不以 expectedAccount = null 记录同意，按没有发出结束
        if (fixed.member && fixed.account == null) return AgentHttpResult.notSentResult()
        val stillFixed = { runCatching { identity().ownerKey == fixed.ownerKey }.getOrDefault(false) }
        val owner = CallOwnership(fixed.epochs, sendAllowed = stillFixed) { stillFixed() }
        val r = client.send(ApiCall(RECORD_CONSENT, body = body.toString(), ownership = owner, expectedAccount = fixed.account))
        if (r is ApiResult.Success) {
            consentRecorded(accepted)
            // 同一归属重新同意记录成功：解除应用级「AI 同意失效」（鸿蒙 R2-02）
            if (accepted && stillFixed()) consentLapse.agreed(fixed.ownerKey)
        }
        return r.toAgentResult()
    }

    /** 此刻的导航票据（设置页撤回的导航许可用，APP-12 G09）。 */
    fun navTicket(): Any? = routing.navTickets.ticket()

    /** 设置页【撤回 AI 服务同意】是否显示：Agent 可用（入口开关、agent.enabled）、已同意隐私政策时（APP-10b：Release 同样显示，§2 第 12 条）。 */
    fun withdrawAvailable(): Boolean = available() && port() != null && privacy.conclusion == PrivacyState.CONSENTED

    private var withdrawConfirmSeq = 0L
    private val mutableWithdrawConfirm = MutableStateFlow<AgentWithdrawConfirm?>(null)

    /** 设置页此刻显示的撤回确认框实例（没有为 null；身份变化时同步销毁，Android R1-01）。 */
    val withdrawConfirm: StateFlow<AgentWithdrawConfirm?> = mutableWithdrawConfirm.asStateFlow()

    /**
     * 用户点了设置页【撤回 AI 服务同意】：在**打开确认框的这一刻**固定此刻身份、发起行的页面许可与一次性编号（Android R1-01，APP-12 第 7、20 条，共性第 25 条）。
     * 撤回进行中、Agent 不可用时不打开。
     */
    fun openWithdrawConfirm(permit: () -> Boolean): AgentWithdrawConfirm? {
        if (mutableWithdrawing.value || !withdrawAvailable()) return null
        withdrawConfirmSeq += 1
        return AgentWithdrawConfirm(withdrawConfirmSeq, identity(), permit).also { mutableWithdrawConfirm.value = it }
    }

    /** 取消（【取消】、系统返回）：只关自己这一个实例，编号随之作废。 */
    fun cancelWithdrawConfirm(confirm: AgentWithdrawConfirm) {
        if (mutableWithdrawConfirm.value === confirm) mutableWithdrawConfirm.value = null
    }

    /** 发起行离开组合（进入设置内部子页、设置页出栈）：销毁确认框实例。 */
    fun dropWithdrawConfirm() {
        mutableWithdrawConfirm.value = null
    }

    private val mutableWithdrawing = MutableStateFlow(false)

    /** 撤回进行中（设置行置灰）。 */
    val withdrawing: StateFlow<Boolean> = mutableWithdrawing.asStateFlow()

    /**
     * 设置页【撤回 AI 服务同意】（G18）：recordConsent{accepted: false, channel: privacy_center}；成功后同步作废 Agent 控制器并清空对话（AI 同意世代 +1，下次进入由
     * 服务端 10004 触发同意框）。失败保留完整结果按统一规则分流（共性第 21 条，[AgentWithdrawOutcome]），不当成已撤回，可重试。[navigable]：设置页这一次确认
     * 时固定的导航许可（发起页仍在、未被覆盖、内部子页世代未变，共性第 23、24 条），打开前置页前与固定身份一起核对；离页后的迟到结果只结算、不导航。
     *
     * [confirm] 是打开确认框时固定的实例（Android R1-01）：提交前只比较——必须仍是此刻显示的那一个（一次性：比较后立即作废）、发起行的页面许可仍在、固定
     * 身份仍等于此刻身份；任一不符直接拒绝、不发请求。请求绑定 [confirm] 里固定的身份，执行时不再取 identity() 重新绑定。
     */
    @Suppress("ReturnCount") // 实例、许可、身份依次拒绝
    fun withdrawConsent(
        confirm: AgentWithdrawConfirm,
        navigable: () -> Boolean,
        onText: (String) -> Unit,
    ) {
        if (mutableWithdrawConfirm.value !== confirm) {
            log("agent consent withdraw rejected: stale confirm")
            return
        }
        mutableWithdrawConfirm.value = null
        if (mutableWithdrawing.value || !confirm.permit()) return
        val fixed = confirm.identity
        val stillFixed = { runCatching { identity().ownerKey == fixed.ownerKey }.getOrDefault(false) }
        if (!stillFixed()) {
            log("agent consent withdraw rejected: identity changed")
            return
        }
        // 42901 禁用期内不进入身份变更（流程不会发请求，G63）
        if (withdrawGate.active) return
        // 确认后的同步入口（G01）：先进入「身份变更在途」并停流、以原身份尽力发 cancel，再发 recordConsent(false)
        val cancel = beginIdentityChange(AgentOwnershipEvent.AI_CONSENT_WITHDRAWN)
        val flow =
            AgentWithdrawFlow(
                gate = withdrawGate,
                record = {
                    cancel.awaitWritten()
                    recordConsent(false, CHANNEL_PRIVACY_CENTER, fixed)
                },
                withdrawn = {
                    aiConsentGeneration += 1
                    handoff.clear()
                    invalidate()
                    sync()
                },
                open = { route ->
                    if (stillFixed() &&
                        navigable()
                    ) {
                        routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP))
                    }
                },
                navigable = { stillFixed() && navigable() },
            )
        mutableWithdrawing.value = true
        scope.launch {
            val o =
                try {
                    flow.run()
                } finally {
                    mutableWithdrawing.value = false
                    endIdentityChange(AgentOwnershipEvent.AI_CONSENT_WITHDRAWN)
                }
            log("agent consent withdraw ok=${o.ok} code=${o.result.code}")
            if (o.text.isNotEmpty() && (o.ok || stillFixed())) onText(o.text)
        }
    }

    // ---------------------------------------------------------------- 情境入口（G27）

    /** 情境入口的交接（共性第 4 条）。 */
    private val handoff = AgentContextHandoff()

    /** 情境入口（【问问 AI】）此刻能不能显示：Agent 可用（入口开关、agent.enabled，G56）、已同意隐私政策时。 */
    fun askAvailable(): Boolean = available() && port() != null && privacy.conclusion == PrivacyState.CONSENTED

    private val mutableAskVisible = MutableStateFlow(false)

    /**
     * 商品详情 / 订单详情的【问问 AI】此刻是否绘制（[askAvailable] 的可订阅版，Android R1-04、共性第 39 条）：配置（`agent.entry.visible`、agent.enabled）、
     * 隐私结论、身份变化时同步更新，详情页订阅它决定画不画按钮。
     */
    val askVisibleState: StateFlow<Boolean> = mutableAskVisible.asStateFlow()

    private fun refreshAskVisible() {
        mutableAskVisible.value = runCatching { askAvailable() }.getOrDefault(false)
    }

    /**
     * 来源页的【问问 AI】（共性第 4 条）：[sources] 是来源页自己的「回退栈条目 → 构造时固定的票据」表（第一次取时按此刻身份固定，之后只比较）；[pageAlive] 是来源页
     * 实例仍在栈里；[context] 是渲染时固定的 {order_id} / {product_key}。没有回退栈条目时为 null。是否绘制由 [askVisibleState] 决定（运行中配置变化随之更新，
     * R1-04）；点击时 [openWithContext] 再核对一次可用性，入口已关闭时不交出情境、不打开。
     */
    fun askEntry(
        sources: MutableMap<String, AgentContextSource>,
        entryId: String?,
        pageAlive: () -> Boolean,
        context: JsonObject,
    ): (() -> Unit)? {
        refreshAskVisible()
        if (entryId == null) return null
        val source = sources.getOrPut(entryId) { contextSource(pageAlive) }
        return { openWithContext(AgentContextTicket(source, context)) }
    }

    /** 来源页（订单详情、商品详情）构造时取一次的票据：此刻的归属键 + 来源页实例是否仍在。之后只比较。 */
    fun contextSource(pageAlive: () -> Boolean): AgentContextSource = AgentContextSource(identity().ownerKey, pageAlive)

    /**
     * in_app 入口（商品详情【问问 AI】、订单详情【问问 AI】）：来源票据与此刻的归属键一致、来源页仍在，才交出 context 并经 Router 打开 AgentChat（不带参数、不自动发送）；
     * 旧回调一律拒绝。push / 桥带来的 AgentChat.context 一律不读（03 §4.4）。
     */
    fun openWithContext(ticket: AgentContextTicket) {
        // G56（FU-HM-0715）：入口开关已关（隐藏后残留的旧按钮）不交出情境、不打开
        if (!askAvailable()) {
            log("agent entry hidden; in-app open ignored")
            return
        }
        if (!handoff.offer(ticket, identity().ownerKey, mutableController.value)) {
            log("agent context handoff rejected")
            return
        }
        routing.router.open(RouteRequest(AGENT_ROUTE, JsonObject(emptyMap()), RouteSource.IN_APP))
    }

    // ---------------------------------------------------------------- 配置与提示

    private fun agentConfig(): JsonObject? = configData()?.get(CONFIG_AGENT) as? JsonObject

    /** config.agent.examples（空 → 不显示「你可以这样问我」分组，G30）。 */
    fun examples(): List<String> =
        (agentConfig()?.get("examples") as? JsonArray)
            .orEmpty()
            .mapNotNull { AgentJson.stringOf(it)?.trim()?.takeIf(String::isNotEmpty) }
            .take(EXAMPLES_MAX)

    /** config.agent.filing_text（空串不显示，G30）。 */
    fun filingText(): String = AgentJson.stringOf(agentConfig()?.get("filing_text"))?.trim().orEmpty()

    private fun toast(text: String) {
        if (text.isEmpty()) return
        toastIds += 1
        mutableToast.value = AgentToast(toastIds, text)
    }

    fun consumeToast(id: Long) {
        if (mutableToast.value?.id == id) mutableToast.value = null
    }

    companion object {
        const val AGENT_ROUTE = "AgentChat"
        const val AGENT_CONTROLLER_CLEANER = "agent_controller"
        const val RECORD_CONSENT = "recordConsent"
        const val AI_CONSENT_TYPE = "ai_third_party"

        /** G16：代拟版本（TODO(N-36)）。 */
        const val AI_CONSENT_VERSION = 1
        const val CHANNEL_SHEET = "agent_sheet"
        const val CHANNEL_PRIVACY_CENTER = "privacy_center"
        private const val BUY_SPM = "AgentChat.card.buy"
        private const val CARD_ID = "card_id"

        /** 逻辑卡片编号前缀（只在本机执行器内部使用，不进请求，Android R2-01）。 */
        private const val LOGICAL_PREFIX = "agent-card-"

        /** 购买待办的「登录」类型（OpenPurchaseController 登记的 kind 原值）。 */
        private const val PENDING_LOGIN = "login"
        private const val CONFIG_AGENT = "agent"
        private const val CONFIG_FEATURES = "features"
        private const val CONFIG_FLAGS = "flags"
        private const val CONFIG_ENABLED = "enabled"
        const val ENTRY_VISIBLE_FLAG = "agent.entry.visible"
        private const val EXAMPLES_MAX = 6
    }
}

/** 设置页撤回 AI 同意的确认框实例：打开时固定的身份、发起行的页面许可、一次性编号（Android R1-01）。 */
class AgentWithdrawConfirm internal constructor(
    val id: Long,
    internal val identity: AgentIdentity,
    internal val permit: () -> Boolean,
) {
    override fun toString(): String = "AgentWithdrawConfirm#$id"
}
