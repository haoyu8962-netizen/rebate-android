package com.couli.core.clipboard

import kotlinx.serialization.json.JsonObject

// 剪贴板识别任务的数据形状（FU-AND-0723；BR-ID-16 细则「ImportTask 门控与生命周期」；03 §4.6 结构段）。取值都是三端同名的字符串，
// 常量在 ImportTask 及各结果类型的伴生对象里。

/** 当前页面实例：[pageInstanceId] 每次新建页面换一个（旋转 / 配置变更不换）；[homeRoot] 只在首页根页（Tab 首页、没有叠页）为 true。 */
data class ImportPageScope(
    val pageInstanceId: String,
    val route: String,
    val homeRoot: Boolean,
    val ready: Boolean,
    val interactive: Boolean,
    val focused: Boolean,
)

/**
 * 协调器每次判定时现取的外部状态（不缓存）：[privacyAgreed] 已同意当前版本隐私政策；[configData] /v1/config 的 data 原形；[modal] 当前浮层或
 * 互斥模态（取值见 [ImportGates]）；[webSuppressed] 当前页面实例的 H5 暂停标记（clipboard.setAutoDetect(false)）。
 */
data class ImportEnvironment(
    val privacyAgreed: Boolean,
    val configData: JsonObject,
    val page: ImportPageScope,
    val modal: String,
    val webSuppressed: Boolean,
)

/**
 * 一次读取任务。交给端口的是发起时的不可变归属票据；回调时协调器按 [requestId] 找登记的任务、以登记的阶段与字段为准。
 * - [kind]：[KIND_AUTO] / [KIND_MANUAL]；[purpose] 只有手动任务有（[PURPOSE_PRODUCT_LOOKUP] 等）；
 * - [epoch]：发起时的前台轮次（手动任务可能为 null：进程还没报过前台）；
 * - [phase]：[PHASE_READING] → [PHASE_AWAITING_SYSTEM]（系统界面暂时失焦，Android 基本碰不到）→ [PHASE_EXTRACTING] → [PHASE_PARSING]。
 */
data class ImportTask(
    val kind: String,
    val purpose: String?,
    val requestId: String,
    val epoch: Long?,
    val pageInstanceId: String,
    val phase: String,
) {
    companion object {
        const val KIND_AUTO = "auto"
        const val KIND_MANUAL = "manual"

        const val PURPOSE_PRODUCT_LOOKUP = "product_lookup"
        const val PURPOSE_FILL_INPUT = "fill_input"
        const val PURPOSE_BRIDGE_READ = "bridge_read"

        const val PHASE_READING = "reading"
        const val PHASE_AWAITING_SYSTEM = "awaiting_system"
        const val PHASE_EXTRACTING = "extracting"
        const val PHASE_PARSING = "parsing"

        /** 六种终态（[ImportCoordinatorPorts.finished] 的 terminalRaw）。 */
        const val TERMINAL_SUCCESS = "success"
        const val TERMINAL_NO_CANDIDATES = "no_candidates"
        const val TERMINAL_DEDUP = "dedup"
        const val TERMINAL_UNAVAILABLE = "unavailable"
        const val TERMINAL_CANCELLED = "cancelled"
        const val TERMINAL_EXPIRED = "expired"
    }
}

/** 读取端口的结果：[kindRaw] = text / empty / unavailable（未知值按 unavailable）；只有 text 带 [text]。 */
data class ImportReadResult(
    val kindRaw: String,
    val text: String? = null,
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_EMPTY = "empty"
        const val KIND_UNAVAILABLE = "unavailable"
    }
}

/** 候选提取端口的结果：[kindRaw] = candidates / no_candidates / dedup（未知值、空候选按 no_candidates）。 */
data class ImportCandidateResult(
    val kindRaw: String,
    val candidates: List<String> = emptyList(),
) {
    companion object {
        const val KIND_CANDIDATES = "candidates"
        const val KIND_NO_CANDIDATES = "no_candidates"
        const val KIND_DEDUP = "dedup"
    }
}

/** 只读诊断快照：[pending] = 本轮还可以尝试自动读取；[consumed] = 本轮已消费（领取、敏感页跳过）；[activeAutoTask] 带当前阶段。 */
data class ImportCoordinatorSnapshot(
    val epoch: Long?,
    val pending: Boolean,
    val consumed: Boolean,
    val activeAutoTask: ImportTask?,
)

/**
 * 生命周期输入。只有 [PROCESS_FOREGROUND] / [PROCESS_BACKGROUND]（进程级观察者的 start / stop）决定轮次；其余几种 Activity 与系统界面事件
 * 只是噪声，不新建轮次、不撤销任务（G13：没收到 stop 的快速恢复沿用本轮；收到真正的 stop / start 对，哪怕只隔 1ms 也记新一轮）。
 */
enum class ImportLifecycleEvent {
    PROCESS_FOREGROUND,
    PROCESS_BACKGROUND,
    ACTIVITY_RESUMED,
    CONFIGURATION_CHANGED,
    SYSTEM_UI_INACTIVE,
    SYSTEM_UI_ACTIVE,
}

/**
 * 协调器对外的 I/O 端口（0724 接上 Android 适配器、候选提取 / 去重、parse 与弹卡）。全部在注入的 workScope 上被调用；结果经
 * [ImportCoordinator.onRead] / [ImportCoordinator.onCandidates] / [ImportCoordinator.onParsed] 送回（可以来自工作线程）。
 * - [read] 抛异常按 unavailable 收束；
 * - [showFallback]：自动任务读不到时在该页面保留「粘贴查返利」入口；
 * - [finished]：释放之后的观察通知，同一 requestId 至多一次；
 * - [deliver]：手动 fill_input / bridge_read 读到的成功文本原样交付（FU-AND-0724，R20）：同一 requestId 至多一次，不进候选提取、不上传；
 *   缺省空实现只为兼容既有端口实现，生产端口必须接上。它不是 bridge.complete 的错误 / 取消出口（0725）。
 */
interface ImportCoordinatorPorts {
    fun deliver(
        task: ImportTask,
        text: String,
    ) = Unit

    fun read(task: ImportTask)

    fun extract(
        task: ImportTask,
        text: String,
    )

    fun parse(
        task: ImportTask,
        candidates: List<String>,
    )

    fun present(
        task: ImportTask,
        result: JsonObject,
    )

    fun showFallback(pageInstanceId: String)

    fun finished(
        task: ImportTask,
        terminalRaw: String,
    )
}
