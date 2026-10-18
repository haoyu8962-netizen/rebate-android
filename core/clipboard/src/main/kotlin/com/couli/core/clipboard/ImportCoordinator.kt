package com.couli.core.clipboard

import com.couli.core.clipboard.ImportTask.Companion.KIND_AUTO
import com.couli.core.clipboard.ImportTask.Companion.KIND_MANUAL
import com.couli.core.clipboard.ImportTask.Companion.PHASE_AWAITING_SYSTEM
import com.couli.core.clipboard.ImportTask.Companion.PHASE_EXTRACTING
import com.couli.core.clipboard.ImportTask.Companion.PHASE_PARSING
import com.couli.core.clipboard.ImportTask.Companion.PHASE_READING
import com.couli.core.clipboard.ImportTask.Companion.TERMINAL_CANCELLED
import com.couli.core.clipboard.ImportTask.Companion.TERMINAL_DEDUP
import com.couli.core.clipboard.ImportTask.Companion.TERMINAL_EXPIRED
import com.couli.core.clipboard.ImportTask.Companion.TERMINAL_NO_CANDIDATES
import com.couli.core.clipboard.ImportTask.Companion.TERMINAL_SUCCESS
import com.couli.core.clipboard.ImportTask.Companion.TERMINAL_UNAVAILABLE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 剪贴板识别的统一入口（FU-AND-0723；BR-ID-16；03 §4.6 结构、前台轮次与门控、伪代码）：前台轮次、自动任务 7 项门槛的原子检查、
 * ImportTask 的登记与六种终态、四类撤销、过期回调先释放再丢弃。纯逻辑，读取 / 提取 / parse / 弹卡都经 [ports]，由 0724 接上。
 *
 * 线程：生命周期与界面入口（onLifecycle / onPageReady / onPageDestroyed / onSwitchOff / onConsentRevoked / onNoticeShown / onPasteTap）
 * 在主线程调用，轮次与撤销同步生效；I/O 一律经 [workScope] 排队（只放有限工作，不挂常驻循环），执行时在同一主线程步骤里重查门槛、领取并
 * 调用端口。结果回调（onRead / onCandidates / onParsed / onAwaitingSystem）可以来自工作线程，先回 [workScope] 再看状态。
 *
 * 轮次（R12，G13）：只有 [ImportLifecycleEvent.PROCESS_FOREGROUND] 在「冷启动或此前确实进过后台」时记新一轮；重复的前台事件、Activity
 * 与系统界面事件都不换轮。[ImportLifecycleEvent.PROCESS_BACKGROUND] 不加轮次，只撤销。
 *
 * 自动任务（R11–R17）：[onPageReady] 排一次尝试；执行时按 [ImportGates.decide]：敏感页跳过即消费、非目标页本轮失效、等待保留 pending、
 * 没看过弹层就弹层并消费、全部通过就在同一步里领取本轮（consumed）、登记活动任务、再调 [ImportCoordinatorPorts.read]。同一时刻至多一个
 * 活动的自动任务；本轮「已消费」不因任何终态回退。
 *
 * 手动任务（R19）：只查隐私同意与发起页面仍有效，不查开关、功能同意版本、轮次与节流，不占自动任务的位置、不改 consumed。看过用途说明的
 * 前置（G15：Android 自绘按钮先弹层、关弹层后本次照常读）由界面在调用 [onPasteTap] 之前处理。同一目的同时只有一个手动任务（连点去重）。
 *
 * 撤销（R16）只有四类：真正进后台、页面实例销毁、关开关、撤回同意。关开关与撤回功能同意只撤自动任务（手动任务不校验开关与功能同意：
 * BR-ID-16 正文「手动任务不校验开关、功能同意版本」，03 §4.6「关闭与撤回：取消待执行的自动任务」）；撤回隐私同意（[onConsentRevoked]
 * 时环境已是未同意）连手动任务一起撤。
 *
 * 回调归属（R15）：按 requestId 找登记的任务（找不到、或阶段不对的重复回调静默丢弃）；两种任务都核对页面实例与隐私同意，自动任务另核对
 * 轮次、consent_version、本机开关与服务端按端开关、页面可交互。不符即过期：先释放自己，再通知 finished(expired)，不提取、不上传、不弹卡。
 */
@Suppress("TooManyFunctions") // 每个外部入口一个函数，另有登记、核对与终态的私有步骤
class ImportCoordinator(
    private val prefs: ImportPreferenceStore,
    private val environment: () -> ImportEnvironment,
    private val ports: ImportCoordinatorPorts,
    private val workScope: CoroutineScope,
    private val clock: () -> Long,
    private val requestIdGen: () -> String,
) {
    private enum class Round { OPEN, DROPPED, CONSUMED }

    private class QueuedManual(
        val job: Job,
        val pageInstanceId: String,
        val purpose: String,
    )

    private var epoch: Long? = null
    private var foreground = false
    private var round = Round.OPEN

    /** 当前轮次开始的时刻（诊断用；Android 门槛⑦不做跨轮节流）。 */
    var epochStartedAtMillis: Long? = null
        private set

    private var attempt: Job? = null
    private var attemptPage: String? = null
    private var activeAuto: ImportTask? = null
    private val manualTasks = LinkedHashMap<String, ImportTask>()
    private val queuedManual = mutableListOf<QueuedManual>()

    fun snapshot(): ImportCoordinatorSnapshot =
        ImportCoordinatorSnapshot(
            epoch = epoch,
            pending = roundOpen(),
            consumed = round == Round.CONSUMED,
            activeAutoTask = activeAuto,
        )

    fun onLifecycle(event: ImportLifecycleEvent) {
        when (event) {
            ImportLifecycleEvent.PROCESS_FOREGROUND -> enterForeground()

            ImportLifecycleEvent.PROCESS_BACKGROUND -> enterBackground()

            // Activity 恢复、配置变更、系统界面失焦 / 恢复：不换轮、不撤销；是否可读由下一次 onPageReady 现查
            else -> Unit
        }
    }

    /** 冷启动或此前确实进过后台才记新一轮；重复的前台事件幂等。 */
    private fun enterForeground() {
        if (foreground) return
        foreground = true
        epoch = (epoch ?: 0L) + 1
        round = Round.OPEN
        epochStartedAtMillis = clock()
    }

    /** 真正进后台：不加轮次，撤销排队的尝试、自动任务与手动任务。 */
    private fun enterBackground() {
        foreground = false
        cancelAttempt()
        cancelAuto()
        cancelManual { true }
    }

    /** 目标页就绪 / 获焦 / 模态关闭等「可以再试」的通知：本轮还开着就排一次尝试（已排着的不重复排）。 */
    fun onPageReady() {
        if (!roundOpen() || attempt?.isActive == true) return
        attemptPage = environment().page.pageInstanceId
        attempt = workScope.launch { runAttempt() }
    }

    fun onPageDestroyed(pageInstanceId: String) {
        if (attemptPage == pageInstanceId) cancelAttempt()
        if (activeAuto?.pageInstanceId == pageInstanceId) cancelAuto()
        cancelManual { it == pageInstanceId }
    }

    /** 0722 钩子 onAutoReadDisabled 在 store 已关之后转发：只撤自动任务。 */
    fun onSwitchOff() {
        cancelAttempt()
        cancelAuto()
    }

    /**
     * 0722 钩子 onImportConsentWithdrawn（撤回隐私，store 已清）或撤回功能同意之后转发：撤自动任务；此时隐私已不是同意（撤回隐私）则手动任务
     * 一起撤。
     */
    fun onConsentRevoked() {
        cancelAttempt()
        cancelAuto()
        if (!environment().privacyAgreed) cancelManual { true }
    }

    /** 用途说明弹层已显示：只消费它携带的当前轮次（上一轮迟到的通知无效，G21）。 */
    fun onNoticeShown(epoch: Long) {
        if (foreground && epoch == this.epoch) round = Round.CONSUMED
    }

    fun onAwaitingSystem(task: ImportTask) {
        workScope.launch {
            val registered = registered(task.requestId) ?: return@launch
            if (registered.phase == PHASE_READING) update(registered.copy(phase = PHASE_AWAITING_SYSTEM))
        }
    }

    /** 用户在粘贴入口上的有效点击（R19）。隐私未同意直接不受理；恢复同意后不重放，须再点。 */
    fun onPasteTap(purpose: String) {
        val env = environment()
        if (!env.privacyAgreed) return
        if (manualTasks.values.any { it.purpose == purpose } || queuedManual.any { it.purpose == purpose }) return
        val holder = arrayOfNulls<QueuedManual>(1)
        // 先登记再启动：即使注入的是立即执行的调度器，执行时也能找到自己的排队记录
        val job = workScope.launch(start = CoroutineStart.LAZY) { holder[0]?.let { startManual(it, purpose) } }
        val queued = QueuedManual(job, env.page.pageInstanceId, purpose)
        holder[0] = queued
        queuedManual += queued
        job.start()
    }

    fun onRead(
        task: ImportTask,
        result: ImportReadResult,
    ) {
        workScope.launch { handleRead(task.requestId, result) }
    }

    fun onCandidates(
        task: ImportTask,
        result: ImportCandidateResult,
    ) {
        workScope.launch { handleCandidates(task.requestId, result) }
    }

    fun onParsed(
        task: ImportTask,
        result: JsonObject,
    ) {
        workScope.launch { handleParsed(task.requestId, result) }
    }

    private fun roundOpen(): Boolean = foreground && epoch != null && round == Round.OPEN

    /** 排队的尝试真正执行：此刻重查轮次与全部门槛（不信排队时的判断），通过就在同一步里领取、登记、读取。 */
    private fun runAttempt() {
        attempt = null
        attemptPage = null
        val current = epoch
        if (!roundOpen() || current == null || activeAuto != null) return
        val env = environment()
        when (ImportGates.decide(env, prefs)) {
            ImportGateDecision.SKIP_SENSITIVE -> {
                round = Round.CONSUMED
            }

            ImportGateDecision.DROP_NON_TARGET -> {
                round = Round.DROPPED
            }

            // 被挡或等待：不领取、pending 保留，等下一次 onPageReady
            ImportGateDecision.BLOCKED, ImportGateDecision.WAIT -> {
                return
            }

            ImportGateDecision.NOTICE -> {
                round = Round.CONSUMED
                ports.showNotice(current, requireNotNull(ImportPreferenceStore.currentConsentVersion(env.configData)))
            }

            ImportGateDecision.READ -> {
                round = Round.CONSUMED
                val task =
                    ImportTask(
                        kind = KIND_AUTO,
                        purpose = null,
                        requestId = requestIdGen(),
                        epoch = current,
                        pageInstanceId = env.page.pageInstanceId,
                        consentVersion = ImportPreferenceStore.currentConsentVersion(env.configData),
                        phase = PHASE_READING,
                    )
                activeAuto = task
                read(task)
            }
        }
    }

    private fun startManual(
        queued: QueuedManual,
        purpose: String,
    ) {
        queuedManual -= queued
        val env = environment()
        if (!env.privacyAgreed || env.page.pageInstanceId != queued.pageInstanceId) return
        val task =
            ImportTask(
                kind = KIND_MANUAL,
                purpose = purpose,
                requestId = requestIdGen(),
                epoch = epoch,
                pageInstanceId = queued.pageInstanceId,
                consentVersion = ImportPreferenceStore.currentConsentVersion(env.configData),
                phase = PHASE_READING,
            )
        manualTasks[task.requestId] = task
        read(task)
    }

    /** 调读取端口；端口抛异常按不可用收束（R18，G16），不重试。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 系统剪贴板与厂商实现可能抛任何异常，一律按不可用；异常内容不记（可能带剪贴板信息）
    private fun read(task: ImportTask) {
        try {
            ports.read(task)
        } catch (error: Exception) {
            val registered = registered(task.requestId) ?: return
            if (registered.phase in READ_PHASES) unavailable(registered)
        }
    }

    @Suppress("ReturnCount") // 归属、阶段、过期逐项短路
    private fun handleRead(
        requestId: String,
        result: ImportReadResult,
    ) {
        val task = registered(requestId) ?: return
        if (task.phase !in READ_PHASES) return
        if (!stillValid(task)) return expire(task)
        val text = result.text
        if (result.kindRaw != ImportReadResult.KIND_TEXT || text.isNullOrEmpty()) return unavailable(task)
        val next = task.copy(phase = PHASE_EXTRACTING)
        update(next)
        ports.extract(next, text)
    }

    @Suppress("ReturnCount") // 归属、阶段、过期逐项短路
    private fun handleCandidates(
        requestId: String,
        result: ImportCandidateResult,
    ) {
        val task = registered(requestId) ?: return
        if (task.phase != PHASE_EXTRACTING) return
        if (!stillValid(task)) return expire(task)
        when {
            result.kindRaw == ImportCandidateResult.KIND_DEDUP -> {
                end(task, TERMINAL_DEDUP)
            }

            result.kindRaw != ImportCandidateResult.KIND_CANDIDATES || result.candidates.isEmpty() -> {
                end(task, TERMINAL_NO_CANDIDATES)
            }

            else -> {
                val next = task.copy(phase = PHASE_PARSING)
                update(next)
                ports.parse(next, result.candidates.toList())
            }
        }
    }

    @Suppress("ReturnCount") // 归属、阶段、过期逐项短路
    private fun handleParsed(
        requestId: String,
        result: JsonObject,
    ) {
        val task = registered(requestId) ?: return
        if (task.phase != PHASE_PARSING) return
        if (!stillValid(task)) return expire(task)
        release(task)
        ports.present(task, result)
        ports.finished(task, TERMINAL_SUCCESS)
    }

    /**
     * 回调时的归属核对（R15，03 §4.6「回调匹配」）。requestId 已由登记表匹配；这里核对页面实例与隐私同意，自动任务另核对仍在发起时的
     * 前台轮次、consent_version 未变且仍有效、本机开关与服务端按端开关仍开、页面仍可交互。
     */
    private fun stillValid(task: ImportTask): Boolean {
        val env = environment()
        val pageValid = env.page.pageInstanceId == task.pageInstanceId && env.privacyAgreed
        return pageValid && (task.kind != KIND_AUTO || autoStillValid(task, env))
    }

    private fun autoStillValid(
        task: ImportTask,
        env: ImportEnvironment,
    ): Boolean {
        val version = ImportPreferenceStore.currentConsentVersion(env.configData)
        return foreground &&
            task.epoch == epoch &&
            env.page.interactive &&
            version != null &&
            version == task.consentVersion &&
            prefs.isAutoReadAllowed(version) &&
            ImportGates.autoReadEnabled(env.configData)
    }

    private fun registered(requestId: String): ImportTask? = activeAuto?.takeIf { it.requestId == requestId } ?: manualTasks[requestId]

    private fun update(task: ImportTask) {
        if (task.kind == KIND_AUTO) {
            if (activeAuto?.requestId == task.requestId) activeAuto = task
        } else if (task.requestId in manualTasks) {
            manualTasks[task.requestId] = task
        }
    }

    /** 只释放同一 requestId 的登记（finish 只清自己，R17）。 */
    private fun release(task: ImportTask) {
        if (activeAuto?.requestId == task.requestId) activeAuto = null
        manualTasks.remove(task.requestId)
    }

    private fun end(
        task: ImportTask,
        terminal: String,
    ) {
        release(task)
        ports.finished(task, terminal)
    }

    /** 过期：先按 requestId 释放自己，再丢弃（BR-ID-16 细则「过期回调先释放再丢弃」）。 */
    private fun expire(task: ImportTask) = end(task, TERMINAL_EXPIRED)

    /** 空、不可用、读取异常：本轮终态，不重试；自动任务在该页保留粘贴入口（03 §4.6 伪代码 showPasteFallback）。 */
    private fun unavailable(task: ImportTask) {
        release(task)
        if (task.kind == KIND_AUTO) ports.showFallback(task.pageInstanceId)
        ports.finished(task, TERMINAL_UNAVAILABLE)
    }

    private fun cancelAttempt() {
        attempt?.cancel()
        attempt = null
        attemptPage = null
    }

    private fun cancelAuto() {
        activeAuto?.let { end(it, TERMINAL_CANCELLED) }
    }

    private fun cancelManual(onPage: (String) -> Boolean) {
        queuedManual.filter { onPage(it.pageInstanceId) }.forEach {
            it.job.cancel()
            queuedManual -= it
        }
        manualTasks.values.filter { onPage(it.pageInstanceId) }.forEach { end(it, TERMINAL_CANCELLED) }
    }

    private companion object {
        val READ_PHASES = setOf(PHASE_READING, PHASE_AWAITING_SYSTEM)
    }
}
