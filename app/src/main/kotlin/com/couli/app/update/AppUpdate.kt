package com.couli.app.update

import com.couli.core.router.UpdateCheck
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateChecker
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateState
import com.couli.core.update.UpdateStatus
import com.couli.core.update.UpdateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 强更页与可关闭提示要显示的东西。
 * @property result 最近一次成功检查的结果（标题、更新内容；选店用它的商店列表）。
 * @property forceUnavailable 强更页【去更新】没有可去的商店：显示 app_update.unavailable 与【重试】，不给任何下载地址。
 * @property promptUnavailable 可关闭提示【去更新】时来源商店打不开（BR-ID-01：可选更新不改去别家）。
 * @property working 正在重试检查或打开商店（按钮防连点）。
 */
data class UpdateUi(
    val state: UpdateState,
    val result: UpdateCheckResult?,
    val forceUnavailable: Boolean = false,
    val promptUnavailable: Boolean = false,
    val working: Boolean = false,
) {
    val status: UpdateStatus get() = state.status
}

/**
 * 商店能力（本机）：读安装来源、能否打开、打开。[canOpen] 只是能力检查；[open] 真正打开失败时继续下一家（rule-gaps G08）。
 */
interface StoreAccess {
    /** 已按商店对照表归一化的安装来源商店键；读不到或不在表里为 null。 */
    val installSource: String?

    fun canOpen(storeKey: String): Boolean

    fun open(storeKey: String): Boolean
}

/**
 * App 的强更检查接线（APP-02c；03 §4.1、§4.4）：[UpdateGate] 的结论 → Router 的 update 状态源（[onRouterUpdate]）与界面（[ui]）。
 *
 * - 冷启动必查、回前台按间隔复查；检查只在 [canCheck]（环境 settled 且已同意隐私政策）时发：同意前只允许请求协议文本与
 *   /v1/config（BR-ID-11），基本模式不发 check、保留已知强更（rule-gaps G06）。冷启动时还不能查的，等能查了（同意后）再补一次冷启动检查。
 * - 检查失败不拦用户自己的操作（Gate 保持上次已知结论或 unknown）。
 * - 10405 入口 [on10405] 给网络层（APP-03）调用：直接进 below_min；服务端已判定强更走 [onServerForced]（闸门落盘）。
 * - 【去更新】按 Gate 选店；都不行 → 强更页显示 unavailable 与【重试】；【重试】重新检查再选。
 *
 * 非线程安全：在主线程使用（[scope] 用 Main）。
 */
@Suppress("TooManyFunctions") // 检查时机、10405、提示、选店四组入口都是 App 层接线，不拆类
class AppUpdate
    @Suppress("LongParameterList") // Gate、存储、检查器、商店能力、协程域、能否检查、时钟逐项注入（可替身）
    constructor(
        private val gate: UpdateGate,
        private val store: UpdateStore,
        private val checker: UpdateChecker,
        private val stores: StoreAccess,
        private val scope: CoroutineScope,
        private val canCheck: () -> Boolean,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val mutableUi = MutableStateFlow(UpdateUi(gate.state(clock()), store.load().result))
        val ui: StateFlow<UpdateUi> = mutableUi.asStateFlow()

        private val routerListeners = mutableListOf<(UpdateCheck) -> Unit>()
        private var coldStartChecked = false
        private var checkJob: Job? = null

        /** 本进程发出的检查还没有结果（冷启动、回前台复查、【重试】）。 */
        private var checkInFlight = false

        /**
         * 版本检查轮次（主线程）：每发起一轮检查加 1。桥调用等待之后、导航之前据此判断「检查中」是不是新的一轮（APP-04 共性第 63 条：
         * 不拿上一轮的结论顶替新一轮的检查中）。
         */
        @Volatile
        var checkRound: Long = 0L
            private set

        /** 本进程最近一次检查失败（下一次成功检查之前 Router 按 unknown，F-AND-09）。 */
        private var lastCheckFailed = false

        /** 上一次交给 Router 的状态（变化才通知）。 */
        private var publishedRouterUpdate: UpdateCheck = routerView(ui.value.state)

        /**
         * 交给 Router 的状态（启动排队器与守卫）。检查进行中、本进程的已知结论不是强更（ok / below_recommended）时按 CHECKING：
         * 回前台复查进行中，外部入口与待办恢复 defer（update_checking），不用本进程旧的 ok 直接放行（F-AND-08）。
         * 最近一次检查失败、还没有新的成功检查时按 UNKNOWN（F-AND-09，APP-02c rule-gaps 编排者裁定：界面可保留旧结论，
         * 外部入口只读、待办恢复受未知限制）。界面（[ui]）仍显示已知结论，不因复查闪成检查中；below_min 保持强更。
         */
        val routerUpdate: UpdateCheck get() = routerView(ui.value.state)

        /** Router 的 update 状态源（AppModule 接 AppRouting.update）。 */
        fun onRouterUpdate(listener: (UpdateCheck) -> Unit) {
            routerListeners += listener
        }

        /** 冷启动（每个进程一次）；还不能查时什么都不做，之后 [onCheckAllowedChanged] 再补。 */
        fun coldStart() {
            if (coldStartChecked || !canCheck()) {
                publish()
                return
            }
            coldStartChecked = true
            if (gate.onColdStart(clock())) runCheck()
            publish()
        }

        /** 隐私结论或环境闸门变化：变得可以查了而冷启动还没查过 → 补冷启动检查。 */
        fun onCheckAllowedChanged() = coldStart()

        /** 回前台（不含冷启动那一次）：距上次成功检查超过间隔才查。 */
        fun onForeground() {
            if (!coldStartChecked) {
                coldStart()
                return
            }
            if (canCheck() && gate.onForeground(clock())) runCheck()
            publish()
        }

        /** 立即重新检查（强更页【重试】、调试面板切换假数据）。[thenPickStore]：检查后仍需强更就再选一次店。 */
        fun recheck(thenPickStore: Boolean = false) {
            if (!canCheck()) return
            runCheck(thenPickStore)
        }

        /**
         * 接口层收到 10405（APP-03 网络层调用，只送确需强更的，rule-gaps G07：受限令牌且本机已受支持的先刷新，不送进来）。
         * 没带最低版本：服务端已判定强更，走 [onServerForced]（不按缓存最低版本比较，F-AND-23）；带了最低版本：按它比较
         * （[UpdateGate.on10405]，最低版本高于本机即强更并落盘；本机不低于它时按 Gate 的防御不改结论）。
         */
        fun on10405(minSupported: String?) {
            if (minSupported == null) {
                onServerForced()
                return
            }
            gate.on10405(minSupported, clock())
            publish()
            checkIfForcedWithoutCache()
        }

        /**
         * 服务端已判定强更（APP-07 共性第 20、21、24 条与 F-AND-23）：parse 行内 10405、登录 / 刷新拿到受限会话、网络层整体
         * 10405 没带最低版本。进闸门本身（[UpdateGate.onServerForced]，落盘），界面、Router、【去更新】选店都按 Gate 同一结论；
         * 成功复查或本机升级后由 Gate 解除。不在这里另做界面覆盖。
         * 无缓存补查见 [checkIfForcedWithoutCache]。
         */
        fun onServerForced() {
            gate.onServerForced(clock())
            publish()
            checkIfForcedWithoutCache()
        }

        /**
         * 所有强更闸门（[on10405] 带或不带最低版本、[onServerForced]）之后共用（03 §4.2，F2-02 / F3-01；APP-07 共性第 27 条：
         * 任何路径进入强更）：Gate 确已是 below_min、没有最近成功检查结果（如首次检查超时，没有商店列表）且允许检查时立即补查一次；
         * 已有在途检查就复用它（[runCheck] 不重复发）。补查失败保持已知强更，成功按新结果处理。有成功缓存时不复查
         * （缓存里的商店列表可直接选店）；没进强更（如带回的最低版本不高于本机）也不查。
         */
        private fun checkIfForcedWithoutCache() {
            if (gate.state(clock()).status != UpdateStatus.BELOW_MIN) return
            if (store.load().result != null || !canCheck()) return
            runCheck()
        }

        /** 可关闭提示【暂不更新】：按提示时的目标版本记录。 */
        fun dismissPrompt() {
            gate.dismissPrompt(clock())
            publish()
        }

        /** 【去更新】：选店并打开；没有可去的商店时置 unavailable（强更页显示提示与【重试】）。 */
        fun goToStore() {
            val result = store.load().result
            val status = gate.state(clock()).status
            val opened = result != null && openFirstStore(result)
            mutableUi.value =
                mutableUi.value.copy(
                    forceUnavailable = status == UpdateStatus.BELOW_MIN && !opened,
                    promptUnavailable = status == UpdateStatus.BELOW_RECOMMENDED && !opened,
                )
        }

        /** 按 Gate 的顺序逐家试；能力检查通过但真正打开失败的，排除后再选下一家（rule-gaps G08）。 */
        private fun openFirstStore(result: UpdateCheckResult): Boolean {
            val failed = mutableSetOf<String>()
            while (true) {
                val choice = gate.pickStore(result, clock()) { key -> key !in failed && stores.canOpen(key) }
                val key = choice.storeKey ?: return false
                if (stores.open(key)) return true
                failed += key
            }
        }

        private fun runCheck(thenPickStore: Boolean = false) {
            if (checkJob?.isActive == true) return
            mutableUi.value = mutableUi.value.copy(working = thenPickStore)
            checkRound += 1
            checkInFlight = true
            checkJob =
                scope.launch {
                    val outcome = checker.check()
                    checkInFlight = false
                    lastCheckFailed = outcome is UpdateCheckOutcome.Failure
                    when (outcome) {
                        is UpdateCheckOutcome.Success -> gate.apply(outcome.result, clock())
                        UpdateCheckOutcome.Failure -> gate.applyFailure(clock())
                    }
                    mutableUi.value = mutableUi.value.copy(working = false, forceUnavailable = false, promptUnavailable = false)
                    publish()
                    if (thenPickStore && gate.state(clock()).status == UpdateStatus.BELOW_MIN) goToStore()
                }
        }

        private fun publish() {
            val state = gate.state(clock())
            mutableUi.value = mutableUi.value.copy(state = state, result = store.load().result)
            val after = routerView(state)
            if (after != publishedRouterUpdate) {
                publishedRouterUpdate = after
                routerListeners.forEach { it(after) }
            }
        }

        /** Gate 的结论 + 本进程检查进度 → Router 的强更进度（F-AND-08）。 */
        private fun routerView(state: UpdateState): UpdateCheck {
            val known = state.toRouterUpdate()
            val provisional = known == UpdateCheck.OK || known == UpdateCheck.BELOW_RECOMMENDED
            return when {
                !provisional -> known
                checkInFlight -> UpdateCheck.CHECKING
                lastCheckFailed -> UpdateCheck.UNKNOWN
                else -> known
            }
        }
    }

/** Gate 的结论 → Router 的强更进度。 */
fun UpdateState.toRouterUpdate(): UpdateCheck =
    when (status) {
        UpdateStatus.CHECKING -> UpdateCheck.CHECKING
        UpdateStatus.OK -> UpdateCheck.OK
        UpdateStatus.BELOW_RECOMMENDED -> UpdateCheck.BELOW_RECOMMENDED
        UpdateStatus.BELOW_MIN -> UpdateCheck.BELOW_MIN
        UpdateStatus.UNKNOWN -> UpdateCheck.UNKNOWN
    }
