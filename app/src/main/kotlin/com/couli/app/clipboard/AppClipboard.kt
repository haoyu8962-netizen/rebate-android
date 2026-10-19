package com.couli.app.clipboard

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.trade.AppTrade
import com.couli.app.update.AppUpdate
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.AndroidClipboardAdapter
import com.couli.core.clipboard.CandidateExtractor
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardBridgeRead
import com.couli.core.clipboard.ClipboardCandidate
import com.couli.core.clipboard.ClipboardClassifier
import com.couli.core.clipboard.ClipboardConsentRaw
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardLimits
import com.couli.core.clipboard.ClipboardPromptInfo
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardRules
import com.couli.core.clipboard.ClipboardSelfWriteLedger
import com.couli.core.clipboard.ClipboardSensitive
import com.couli.core.clipboard.ClipboardText
import com.couli.core.clipboard.ClipboardTitlePolicy
import com.couli.core.clipboard.ClipboardWriter
import com.couli.core.clipboard.ImportCandidateResult
import com.couli.core.clipboard.ImportCoordinator
import com.couli.core.clipboard.ImportCoordinatorPorts
import com.couli.core.clipboard.ImportEnvironment
import com.couli.core.clipboard.ImportGates
import com.couli.core.clipboard.ImportLifecycleEvent
import com.couli.core.clipboard.ImportPageScope
import com.couli.core.clipboard.ImportPreferenceStore
import com.couli.core.clipboard.ImportReadResult
import com.couli.core.clipboard.ImportTask
import com.couli.core.clipboard.InviteCodeExtractor
import com.couli.core.clipboard.InviteConfirmAction
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.ParseAction
import com.couli.core.clipboard.ParseInputService
import com.couli.core.clipboard.ParseOutcome
import com.couli.core.clipboard.ParseResultAction
import com.couli.core.clipboard.ParseRow
import com.couli.core.clipboard.ShareImportAction
import com.couli.core.clipboard.ShareImportDecision
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.network.RetryAfterGate
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 首页提示条（界面状态；不含剪贴板原文，只有抽出的邀请码与缩略候选）。FU-AND-0724 起商品不再出提示条（命中候选直接 parse 出卡，G-20 作废），
 * 提示条只剩两种：自动识别到的好友邀请码（[kind] invite，R25）与系统分享（[share]，G19：分享流程保留确认提示条，从剪贴板流程剥离）。
 * [display]：分享提示条上显示的平台与缩略候选（FU-CLIP C7，K26；点击上传的就是同一编号下的那一份候选）。
 */
data class ClipboardPrompt(
    val id: Long,
    val kind: String,
    val inviteCode: String? = null,
    val busy: Boolean = false,
    val display: ClipboardPromptInfo? = null,
    val share: Boolean = false,
)

/**
 * 要显示的首次用途弹层（[source]）：回前台的可读取场景（只由协调器的 showNotice 端口拉起，0723 接线说明第 5 条）、设置页打开开关、
 * 第一次点手动粘贴入口（G15：关弹层后继续那次点击）。
 */
data class ClipboardConsentNotice(
    val source: String,
) {
    companion object {
        const val SOURCE_FOREGROUND = "foreground"
        const val SOURCE_SETTINGS = "settings"
        const val SOURCE_MANUAL = "manual"
    }
}

/**
 * parse 或手动粘贴之后要在发起页上显示的东西（只在发起页仍是当前页时显示）。
 * [id]：每次发布自增的唯一编号（APP-07 共性第 26 条）。内容相同的两次结果编号不同，StateFlow 不会把它们合并；界面回调带渲染时的
 * 结果，执行层按编号核对是不是当前结果，不靠对象身份或 equals。
 */
sealed interface ClipboardResult {
    val origin: String
    val id: Long

    /** 轻提示（30131、30141、42901、5xxxx 带 trace6；网络异常时另可显式重试，见 [AppClipboard.canRetry]）。 */
    data class Notice(
        override val origin: String,
        val text: String,
        override val id: Long,
    ) : ClipboardResult

    /** 30132：error.30132 加【用商品名搜索】，预填「」内或首行（G10、BR-TEXT-14）。 */
    data class Unrecognized(
        override val origin: String,
        val text: String,
        val prefill: String?,
        override val id: Long,
    ) : ClipboardResult

    /**
     * 商品卡（G09：单卡 rows=1 也在当前页弹出，点卡才进详情；多条结果按搜索卡样式列出）。行内 error_code 逐条按码处理（A-R1-05）：30132 给
     * 【用商品名搜索】、42901 已启动共享禁用期、5xxxx 带 trace6（[traceId] 是本次响应的 trace_id）；文案由 [AppClipboard.rowText] 统一生成。
     */
    data class CardList(
        override val origin: String,
        val rows: List<ParseRow>,
        val traceId: String? = null,
        override val id: Long,
    ) : ClipboardResult

    /**
     * 游客在商品粘贴入口粘到的只有邀请内容（A-R3-01，BR-ID-16）：本地抽出的码（[code]），在发起页内联出邀请确认条；点确认才预填登录页，
     * 不发任何请求、不持久化。
     */
    data class InviteConfirm(
        override val origin: String,
        val code: String,
        override val id: Long,
    ) : ClipboardResult
}

/**
 * 剪贴板普通互斥模态的生产来源（FU-AND-0724 G10 / G28 / G29）：任一为 true 时自动识别等待（不读、不弹用途层），全部回到 false 后同一轮
 * 重新尝试。[current] 给出此刻的模态取值（[ImportGates] 的 MODAL_*），没有为 null。生产里支付与回跳确认两路 Android 还没有对应状态，恒为
 * false（F-AND-55）；登录任务、外跳进行中、更新提示三路接真实服务（[AppClipboard.connectModalSources]）。
 */
class ClipboardModalSources(
    private val payment: StateFlow<Boolean>,
    private val loginTask: StateFlow<Boolean>,
    private val tradeJump: StateFlow<Boolean>,
    private val tradeReturnConfirmation: StateFlow<Boolean>,
    private val updatePrompt: StateFlow<Boolean>,
) {
    fun current(): String? =
        when {
            payment.value -> ImportGates.MODAL_PAYMENT
            loginTask.value -> ImportGates.MODAL_LOGIN
            tradeJump.value -> ImportGates.MODAL_TRADE_JUMP
            tradeReturnConfirmation.value -> ImportGates.MODAL_TRADE_RETURN_CONFIRMATION
            updatePrompt.value -> ImportGates.MODAL_UPDATE_PROMPT
            else -> null
        }

    internal val flows: List<StateFlow<Boolean>> get() = listOf(payment, loginTask, tradeJump, tradeReturnConfirmation, updatePrompt)
}

/**
 * 剪贴板识别的 App 接线（APP-07；FU-AND-0722～0724；BR-ID-16、BR-INV-04；03 §4.6；10 AC-S1-03）：
 * - 统一入口是进程单例的 [importCoordinator]（FU-AND-0723 的 ImportCoordinator）：前台轮次由进程级生命周期（[bindProcessLifecycle]，
 *   ProcessLifecycleOwner 的 ON_START / ON_STOP，在观察者回调里同步转发）决定；Activity resumed、窗口获焦、外壳的 Tab 与回退栈、弹窗与普通模态
 *   关闭、H5 恢复只通知它「可以再试」（onPageReady），不建新轮。门槛现取自 [importEnvironment]；
 * - 端口：读取经 [AndroidClipboardAdapter]（只读主剪贴项文本、有上限）；提取经 [CandidateExtractor]；自动任务另做自写排除（最近 10 次）与
 *   24 小时候选摘要去重（持久盐 HMAC，G07 / G14）；命中候选直接 POST /v1/inputs/parse（scene=clipboard，只带候选与最小化标题，G24 不上送
 *   requestId），发出前记摘要（G08）；结果在当前有效页弹卡 1 次（G09：单卡也先在当前页弹卡，点卡才进详情）；网络异常可显式重试，复用已批准的
 *   候选（R24）。没有商品时走邀请分支（R25：独立 24 小时提示去重，自动与手动共用；商品优先；邀请码不送 parse）；
 * - 手动粘贴（[paste]、[pasteInviteCode]）：G15 第一次点击先出用途弹层，关掉后继续那次点击；不查开关与轮次、不读写商品去重、不做自写排除（G14）；
 * - 原文只在内存里过一遍，不进存储、日志、导航参数与请求（R26）；识别前后都不写系统剪贴板，写只经 [writer]（先记自写 hash）；
 * - 系统分享（G19）：保留确认提示条与 scene=share_ext，分享在本轮处理时本轮跳过自动读剪贴板（协调器 markSkipped）。
 * 只在主线程使用；唯一的例外是交给网络层的归属核对（CallOwnership.stillOwned，可能在网络线程上调）：它只读原子标记或主线程发布的不可变快照。
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass") // 生命周期、页面、粘贴入口、端口与作废出口各一个入口；各状态源逐项注入
class AppClipboard(
    private val system: SystemClipboard,
    private val api: PageApi,
    private val routing: AppRouting,
    private val privacy: () -> PrivacyState,
    private val promptShowing: () -> Boolean,
    private val configData: () -> JsonObject,
    private val prefs: ClipboardPreferences,
    val seeds: ProductSeeds = ProductSeeds(),
    val invitePrefill: InvitePrefill = InvitePrefill(),
    private val sessionEpoch: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 本机盐的随机源（FU-CLIP C3：生产为系统 CSPRNG；测试注入样例字节）。 */
    randomSalt: () -> ByteArray = ::secureSalt,
    /** 开关与功能同意的本机状态（FU-AND-0722；设备级，与 [prefs] 同一偏好文件）。 */
    private val importPreferences: ImportPreferenceStore = prefs.importPreferences(clock),
    /** 开关关闭的同步通知（G20：立即生效）：本机已置关、[autoSwitch] 已更新、协调器已撤销自动任务之后调。 */
    private val onAutoReadDisabled: () -> Unit = {},
    /** 撤回隐私同意的同步通知（R06/R16）：本机开关与功能同意已清、[autoSwitch] 已置关、协调器已撤销之后调；隐私政策升级（NOT_DECIDED）不调。 */
    private val onImportConsentWithdrawn: () -> Unit = {},
    /** 本组件的有限工作（协调器的排队尝试与回调、parse、限流计时）与普通模态来源的订阅都跑在这里。 */
    workScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val scope = workScope

    /** 去重与自写记录（FU-CLIP C3）：只存本机盐的 HMAC 指纹与时间（盐经 Keystore 包裹存在 [ClipboardPreferences.salt]）。 */
    val records = ClipboardRecords(prefs.hashes, prefs.salt, clock, randomSalt)

    /** 加盐的自写记录（观察用）。 */
    val ledger get() = records.ledger

    /** 加盐的 24 小时邀请提示记录（观察用）。 */
    val history get() = records.history

    /**
     * 自写记录的写入口：已同意时记加盐指纹（记不上 → 不写系统剪贴板，APP-07 第 23、25 条）；未同意（基本模式）不生成盐、不记、照常写
     * （识别在未同意时整体关闭，撤回 / 再同意时记录本就清空；规则空白 AS03，同鸿蒙 HS02）。
     */
    private val selfWrites =
        object : ClipboardSelfWriteLedger {
            override fun record(text: String): Boolean = privacy() != PrivacyState.CONSENTED || records.recordSelfWritten(text)

            override fun contains(text: String): Boolean = records.isSelfWritten(text)
        }

    /** App 写剪贴板的唯一入口（桥 clipboard.write、复制口令 copy_tpwd、各原生复制按钮）。 */
    val writer = ClipboardWriter(system, selfWrites)
    private val parser = ParseInputService(api)

    /** 读取适配器（R18）：自动与手动共用，每次只读一次主剪贴项文本。 */
    private val adapter = AndroidClipboardAdapter(system)

    /** 升级迁移（FU-CLIP §2.2 第 6 条）：旧版未加盐记录还没确认删掉（失败时下一次回前台重试）。 */
    private var legacyPending = true

    private fun dropLegacy() {
        if (legacyPending) legacyPending = !records.dropLegacy()
    }

    /** 商品去重摘要按会话世代清（G07）：记下最近一次见到的世代，变了就清。 */
    private var productSession = sessionEpoch()

    /** 已按会话变化处理过的登录世代（[onSessionStored] 只在它变了时作废）。 */
    private var notifiedSession = productSession

    init {
        // 升级后第一次运行（已同意或未同意都做）主动删掉旧版未加盐的记录
        dropLegacy()
    }

    /** 进入强更分流（APP-03a 的同一个入口 AppUpdate.on10405；App 接线时给，APP-07 共性第 20 条）。 */
    var onForceUpdate: () -> Unit = {}

    /** 浮层来源（AuthSheet、auth_jump 执行中、第三方登录进行中等，G12）；App 接线时给。 */
    var overlay: () -> String? = { null }

    /**
     * 此刻的平台购买状态（features.platform_status + 点击确认的 50301 覆盖，与搜索卡同一来源，APP-07 共性第 32 条）：商品卡按它映射
     * （not_launched 隐藏金额与读屏金额、maintenance 禁用购买）。App 接线见 [connectPurchaseStatus]。
     */
    var purchaseStatus: (platform: String) -> String? = { null }

    private val mutableStatusVersion = MutableStateFlow(0L)

    /** 平台购买状态可能变了（配置快照换了、50301 覆盖变了）就加 1：商品卡按它重新映射（结果编号不变、不重复播报）。 */
    val purchaseStatusVersion: StateFlow<Long> = mutableStatusVersion.asStateFlow()

    /** 平台购买状态的来源变了（主线程）。 */
    fun onPurchaseStatusChanged() {
        mutableStatusVersion.value += 1
    }

    /** 一张结果卡此刻的平台购买状态（卡上的 platform）；没有平台为 null。 */
    fun purchaseStatusOf(card: JsonObject): String? = card.text("platform")?.let(purchaseStatus)

    // ---------------------------------------------------------------- 当前前台界面

    private var topRoute: String? = null
    private var topEntryId: String? = null
    private var homeOnTop = true
    private var tab: String = RouteNames.HOME
    private var basicShell = false
    private var homeGen = 0L
    private var windowFocused = false

    /** 界面世代：当前前台界面（[currentOrigin]）每变一次、敏感浮层每出现一次都加 1，不会回退（A-R1-02、A-R1-04）。 */
    private var screenGen = 0L
    private var lastOrigin: String? = null
    private val suppressedWeb = mutableSetOf<String>()
    private val fills = mutableMapOf<String, (String) -> Unit>()

    private val mutablePrompt = MutableStateFlow<ClipboardPrompt?>(null)
    val prompt: StateFlow<ClipboardPrompt?> = mutablePrompt.asStateFlow()
    private var ticket: Ticket? = null
    private var counter = 0L

    private val mutableResult = MutableStateFlow<ClipboardResult?>(null)
    val result: StateFlow<ClipboardResult?> = mutableResult.asStateFlow()

    private val gate = RetryAfterGate(clock)
    private var gateTimer: Job? = null
    private val mutableBlocked = MutableStateFlow(false)

    /** 42901 限流中：所有粘贴入口与提示条按钮置灰（执行层另在 [paste] / [onPromptAction] 拦，自动识别这期间不发 parse）。 */
    val blocked: StateFlow<Boolean> = mutableBlocked.asStateFlow()

    /** 分享提示条发起的 parse 在途（null = 空闲）。 */
    private var shareBusy: Long? = null
    private var busySerial = 0L
    private val mutableBusy = MutableStateFlow(false)

    /**
     * 商品 parse 在途（APP-07 共性第 31 条共享 busy；FU-AND-0710 R1-05）：当前页上的自动 / 手动 / 重试 parse 或分享提示条的 parse 还没结束。
     * 粘贴入口与提示条按钮同步置灰，执行层（[paste]、[onPromptAction]）这期间不读剪贴板、不发 parse。离开发起页时那一页的任务随之撤销
     * （F-AND-54：别的页面实例上的有效点击不受旧页阻挡）；作废（进后台、撤回、换会话、切环境）时随之结束（旧结果丢弃）。
     */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private fun refreshBusy() {
        mutableBusy.value = shareBusy != null || flights.values.any { it.live.get() }
    }

    private fun takeShareBusy(): Long {
        busySerial += 1
        shareBusy = busySerial
        refreshBusy()
        return busySerial
    }

    private fun releaseShareBusy(token: Long) {
        if (shareBusy != token) return
        shareBusy = null
        refreshBusy()
    }

    private val mutableAutoSwitch = MutableStateFlow(importPreferences.snapshot().enabled == true)

    /**
     * 设备级开关「打开 App 自动识别剪贴板」此刻是否为开（BR-ID-16 新版：默认关；未选择、关都为 false）。功能同意版本失效时开关原值保留（R05），
     * 是否真的自动读另看 [autoReadAllowed]。
     */
    val autoSwitch: StateFlow<Boolean> = mutableAutoSwitch.asStateFlow()

    private val mutableConsentNotice = MutableStateFlow<ClipboardConsentNotice?>(null)

    /** 首次用途说明弹层（R02）：非 null 时由外壳（AppShell）显示 ClipboardNotice；【开启】→ [onNoticeEnable]，【暂不开启】/ 返回 → [onNoticeNotNow]。 */
    val consentNotice: StateFlow<ClipboardConsentNotice?> = mutableConsentNotice.asStateFlow()

    /**
     * 外壳此刻有别的弹窗（可关闭的更新提示等，G22）：外壳接线时给。自动识别与用途弹层排在它之后——它在时等待（本轮不读不弹），它关掉后同一轮
     * 接着尝试（G31 修正）；用途弹层显示期间它晚到，用途弹层收起让位。它的显示状态一变，外壳就调 [onDialogsChanged]。
     */
    var otherDialogShowing: () -> Boolean = { false }

    /** 设置来源与手动来源的用途弹层出现时所在的页面（[currentOrigin]）：页面一变（被覆盖、出栈、切走）就收起。 */
    private var noticeOrigin: String? = null

    /** 回前台来源的用途弹层是哪一轮弹出的（让位后只在同一轮里恢复）。 */
    private var noticeEpoch: Long? = null

    /** 回前台来源的用途弹层被挡住让位（G41：同一轮里挡的东西没了再出同一个弹层，不重新判定、不读）。 */
    private var yieldedNotice: ClipboardConsentNotice? = null

    /** G15：第一次点手动粘贴时先出用途弹层，关掉后继续的那次点击（目的、发起页、邀请码框的填入回调）。 */
    private class PendingTap(
        val purpose: String,
        val origin: String,
        val onFilled: ((String?) -> Unit)?,
    )

    private var pendingTap: PendingTap? = null

    /** 邀请码框（fill_input）这次点击的填入回调，按发起页登记（同页同目的只有一个，F-AND-54）。 */
    private val fillCallbacks = mutableMapOf<String, (String?) -> Unit>()

    /** 测试与诊断：parse 请求发出的次数（不含内容）。 */
    var parseCount: Int = 0
        private set

    private val owners = mutableListOf<CallOwnership>()

    private data class Ticket(
        val id: Long,
        val round: Long,
        val homeGen: Long,
        val screen: Long,
        val session: Long,
        val epochs: CallEpochs,
        val kind: String,
        val upload: String?,
        val inviteCode: String?,
        /** 请求前本地抽出、已最小化的「」标题片段（整体 30132 预填用，APP-07 共性第 30 条、FU-CLIP 共性第 8 条）；不是原文。 */
        val title: String? = null,
        /** 分享提示条显示与点击上传的同一份候选（FU-CLIP C7）；规则表变化时据此作废（§2.1 第 5 条）。 */
        val candidates: List<ClipboardCandidate> = emptyList(),
        /** parse 的 scene：系统分享 share_ext（K16）。 */
        val scene: String = ParseInputService.SCENE_CLIPBOARD,
        /** 取定 [candidates] 之前的规则修订号（R2-05）：之后发布的每一份规则快照都要逐份复核它。 */
        val rulesRev: Long = 0L,
    )

    /**
     * 归属快照（A-R2-04，三端共性第 12 条）：页面世代、界面标识、前台轮次、提示条与敏感浮层，由主线程在每个变化出口（导航、切 Tab、
     * 浮层出现、前后台、作废、提示条出现与消失、发请求前）同步发布；网络线程的归属核对只读它（原子引用，整体替换，不可变）。
     */
    private data class OwnerSnapshot(
        val screen: Long = 0L,
        val home: Long = 0L,
        val origin: String = "",
        val foreground: Boolean = false,
        val round: Long = 0L,
        val ticket: Long? = null,
        val overlayClear: Boolean = true,
    )

    private val published = AtomicReference(OwnerSnapshot())

    /** 主线程：按当前状态整体替换归属快照（同步出口里调）。 */
    private fun publish() {
        published.set(
            OwnerSnapshot(
                screen = screenGen,
                home = homeGen,
                origin = currentOrigin(),
                foreground = importCoordinator.inForeground,
                round = currentEpoch(),
                ticket = ticket?.id,
                overlayClear = overlayClear(),
            ),
        )
    }

    // ---------------------------------------------------------------- 自动识别：协调器与端口

    /**
     * 自动 / 手动 product_lookup 已批准上传的东西（R23、G24）：本地抽出的候选与上传内容、最小化后的「」标题；自动任务另带候选摘要（G07）。
     * 不含原文。网络异常时的显式重试复用同一份（R24）。
     */
    private class Approved(
        val upload: String,
        val title: String?,
        val candidates: List<ClipboardCandidate>,
        val rulesRev: Long,
        val fingerprint: ClipboardFingerprint? = null,
        val summary: String? = null,
    )

    /**
     * 一次在途的商品 parse（按 requestId；显式重试用自己的编号）：[live] 是网络线程也能读的归属标记，任何作废出口（离开发起页、进后台、撤回、
     * 换会话、切环境、规则收紧、敏感浮层）都把它置 false。结果回来后交给协调器核对（自动 / 手动）或直接按发起页核对（重试）。
     */
    private class Flight(
        val origin: String,
        val approved: Approved,
    ) {
        val live = AtomicBoolean(true)
        var owner: CallOwnership? = null
        var outcome: ParseOutcome? = null
    }

    private val flights = LinkedHashMap<String, Flight>()
    private val approvals = mutableMapOf<String, Approved>()
    private var retrySerial = 0L

    /** 当前结果可显式重试的那一份（结果编号 → 已批准的上传）；结果被替换或消费即失效。 */
    private var retryPlan: Pair<Long, Approved>? = null

    private val ports =
        object : ImportCoordinatorPorts {
            override fun read(task: ImportTask) = importCoordinator.onRead(task, adapter.read())

            override fun extract(
                task: ImportTask,
                text: String,
            ) = importCoordinator.onCandidates(task, extractCandidates(task, text))

            override fun parse(
                task: ImportTask,
                candidates: List<String>,
            ) = sendApproved(task)

            override fun present(
                task: ImportTask,
                result: JsonObject,
            ) = presentFlight(task)

            // 读不到时的粘贴入口本来就常驻（首页「粘贴链接，先查返利」卡；开关没开时另有自绘按钮），不另出
            override fun showFallback(pageInstanceId: String) = Unit

            override fun showNotice(
                epoch: Long,
                consentVersion: String,
            ) {
                // 回前台来源的用途弹层只由这里拉起（0723 接线说明第 5 条）；协调器在调用时已消费本轮，这里回执显示
                if (mutableConsentNotice.value != null) return
                yieldedNotice = null
                noticeEpoch = epoch
                noticeOrigin = currentOrigin()
                mutableConsentNotice.value = ClipboardConsentNotice(ClipboardConsentNotice.SOURCE_FOREGROUND)
                importCoordinator.onNoticeShown(epoch)
            }

            override fun finished(
                task: ImportTask,
                terminalRaw: String,
            ) = finishTask(task, terminalRaw)

            override fun deliver(
                task: ImportTask,
                text: String,
            ) = deliverText(task, text)
        }

    /** 剪贴板识别的统一协调器（进程内一个；AppModule 的单例 AppClipboard 持有，测试与生产用同一个）。 */
    val importCoordinator: ImportCoordinator =
        ImportCoordinator(
            prefs = importPreferences,
            environment = ::importEnvironment,
            ports = ports,
            workScope = scope,
            clock = clock,
            requestIdGen = { UUID.randomUUID().toString() },
        )

    private fun currentEpoch(): Long = importCoordinator.snapshot().epoch ?: 0L

    /** 协调器每次判定时现取的外部状态（R11）：隐私、配置、当前页面实例与就绪 / 获焦、模态、H5 暂停。 */
    private fun importEnvironment(): ImportEnvironment =
        ImportEnvironment(
            privacyAgreed = privacy() == PrivacyState.CONSENTED,
            configData = configData(),
            page =
                ImportPageScope(
                    pageInstanceId = currentOrigin(),
                    route = currentRoute(),
                    homeRoot = homeRoot(),
                    ready = tabSynced && stackSynced,
                    interactive = true,
                    focused = windowFocused,
                ),
            modal = currentModal(),
            webSuppressed = !homeOnTop && topEntryId in suppressedWeb,
        )

    private fun currentRoute(): String = if (homeOnTop) tab else topRoute.orEmpty()

    /**
     * 此刻的模态（0723 接线说明第 4 条）：敏感浮层（首启隐私、强更、AuthSheet、授权跳转、第三方登录进行中）→ 普通互斥模态的生产来源
     * （[connectModalSources]）→ 外壳的其他弹窗与用途弹层自己 → 本轮的系统分享 → none。
     */
    private fun currentModal(): String {
        val overlay = currentOverlay()
        return when {
            overlay in ClipboardSensitive.overlays -> overlay
            else -> modalSources?.current() ?: plainModal()
        }
    }

    private fun plainModal(): String =
        when {
            otherDialogShowing() || mutableConsentNotice.value != null -> ImportGates.MODAL_OTHER_DIALOG
            shareInRound() -> ImportGates.MODAL_SHARE_IMPORT
            else -> ImportGates.MODAL_NONE
        }

    // ---------------------------------------------------------------- 进程生命周期与界面事件

    private var boundLifecycle: Lifecycle? = null

    private val processObserver =
        LifecycleEventObserver { _, event ->
            // 观察者回调里同步转发（不经 workScope / Handler 二次排队）：轮次在这里建立或撤销，读取再由协调器异步排队
            when (event) {
                Lifecycle.Event.ON_START -> enterForeground()
                Lifecycle.Event.ON_STOP -> enterBackground()
                else -> Unit
            }
        }

    /**
     * 绑定进程级生命周期（R12、G13；AppModule 的单例 provider 以 ProcessLifecycleOwner 调用）：ON_START → 前台轮次，ON_STOP → 进后台
     * （ProcessLifecycleOwner 的 stop 有延迟：旋转、快速的 Activity 替换不会进后台）。重复绑定同一个 [lifecycle] 幂等；换一个时先解绑旧的。
     */
    fun bindProcessLifecycle(lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle) {
        if (boundLifecycle === lifecycle) return
        boundLifecycle?.removeObserver(processObserver)
        boundLifecycle = lifecycle
        lifecycle.addObserver(processObserver)
    }

    /** 兼容入口（旧的 Activity onStart；测试夹具沿用）：转给同一个协调器，等同进程 ON_START。[cold] 不再使用；[hasFocus] 是此刻的窗口焦点。 */
    @Suppress("UnusedParameter") // 保留旧签名，冷启动与否由协调器按「此前是否进过后台」判定
    fun onForeground(
        cold: Boolean,
        hasFocus: Boolean,
    ) {
        windowFocused = hasFocus
        enterForeground()
    }

    /** 兼容入口（旧的 Activity onStop）：等同进程 ON_STOP。 */
    fun onBackground() = enterBackground()

    private fun enterForeground() {
        // G42：回前台立即补试还没落盘的关闭 / 撤回 / 入队（只写此刻最新的意图），不等退避；在判定读取之前，失败也不影响本轮
        importPreferences.flushPendingWrites()
        dropLegacy()
        importCoordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
        publish()
        // 不在前台时到达的系统分享：前台与外壳都就绪后处理一次（FU-CLIP 共性第 2、7 条）；分享在本轮处理时本轮跳过自动读（G19）
        processShare()
        importCoordinator.onPageReady()
    }

    /** 真正进后台：协调器撤销排队的尝试、自动与手动任务；作废提示条、在途 parse 与结果提示。 */
    private fun enterBackground() {
        importCoordinator.onLifecycle(ImportLifecycleEvent.PROCESS_BACKGROUND)
        yieldedNotice = null
        invalidateAll()
    }

    /** Activity 恢复（MainActivity.onResume）：不建新轮，只通知协调器再试一次。 */
    fun onActivityResumed() {
        importCoordinator.onLifecycle(ImportLifecycleEvent.ACTIVITY_RESUMED)
        importCoordinator.onPageReady()
    }

    /** 窗口焦点（MainActivity.onWindowFocusChanged 原样转发）：获焦时再试一次；失焦只是系统界面的暂时变化，不撤销任务。 */
    fun onWindowFocus(hasFocus: Boolean) {
        windowFocused = hasFocus
        importCoordinator.onLifecycle(if (hasFocus) ImportLifecycleEvent.SYSTEM_UI_ACTIVE else ImportLifecycleEvent.SYSTEM_UI_INACTIVE)
        if (hasFocus) importCoordinator.onPageReady()
    }

    // ---------------------------------------------------------------- 当前前台界面（AppShell 同步告知）

    /**
     * 回退栈变了（主线程、回退栈一更新就调）：[top] 栈顶路由名（起点 = null）、[entryId] 栈顶条目、[ids] 栈里全部条目。离开首页根页即作废
     * 提示条（同步出口）；已出栈的网页容器的抑制随之失效（G06）。
     */
    fun onBackStack(
        top: String?,
        entryId: String?,
        ids: Set<String>,
    ) {
        suppressedWeb.retainAll(ids)
        fills.keys.retainAll(ids)
        seeds.retain(ids)
        topRoute = top
        topEntryId = entryId
        homeOnTop = top == null
        screenChanged()
        // 先同步回退栈、再宣布就绪（R1-01）：恢复后的栈顶已知，冷启动收到的分享才按真实界面处理
        stackSynced = true
        processShare()
        importCoordinator.onPageReady()
    }

    /**
     * 外壳选中的 Tab 与基本模式页（首页 Tab 的根在基本模式下是基本模式页，不显示提示条）。先同步恢复后的 Tab 与基本模式，再判断外壳是否就绪、
     * 处理冷启动收到的分享（R1-01：不能在 Tab 还是缺省首页时就出条，随后恢复成「我的」又被清掉）。
     */
    fun onShell(
        selectedTab: String,
        basicMode: Boolean,
    ) {
        if (selectedTab != tab || basicMode != basicShell) {
            tab = selectedTab
            basicShell = basicMode
            screenChanged()
        }
        tabSynced = true
        processShare()
        importCoordinator.onPageReady()
    }

    /** 外壳（AppShell）进入组合：之后要重新收到 Tab 与回退栈的同步才算就绪（Activity 重建、进程恢复，R1-01）。 */
    fun onShellAttached() {
        tabSynced = false
        stackSynced = false
    }

    /** 外壳离开组合：Router 的结论落不到界面上，暂存的分享等下一次外壳就绪；自动识别等外壳重新就绪。 */
    fun onShellDetached() {
        tabSynced = false
        stackSynced = false
    }

    /** SearchTyping（回退栈条目 [entryId]）登记本页搜索框的填入：30132 的【用商品名搜索】直接填进本页，不另开一页。 */
    fun bindSearchFill(
        entryId: String?,
        fill: (String) -> Unit,
    ) {
        if (entryId != null) fills[entryId] = fill
    }

    /**
     * 网页容器（回退栈条目 [entryId]）经桥 clipboard.setAutoDetect：只对这个容器实例生效的临时抑制，容器出栈即失效；不改设置页开关（G06）。
     * 恢复（true）时通知协调器按当前页重新判定（R10）。
     */
    fun setWebAutoDetect(
        entryId: String?,
        enabled: Boolean,
    ) {
        val id = entryId ?: return
        if (enabled) {
            suppressedWeb -= id
            importCoordinator.onPageReady()
        } else {
            suppressedWeb += id
        }
    }

    /** 首页 Tab 根页在前台（不在基本模式页、没有推入的页面、首启弹窗没有显示）。 */
    fun homeRoot(): Boolean = homeOnTop && tab == RouteNames.HOME && !basicShell && !promptShowing()

    /** 当前页的标识（也是协调器的页面实例）：首页根页 = home:<世代>；推入的页面 = 回退栈条目 id；其余 Tab 根页 = tab:<路由名>。 */
    fun currentOrigin(): String =
        when {
            homeRoot() -> HOME_ORIGIN_PREFIX + homeGen
            !homeOnTop -> topEntryId ?: topRoute.orEmpty()
            else -> TAB_ORIGIN_PREFIX + tab
        }

    private var lastHomeRoot = true

    private fun screenChanged() {
        val nowHome = homeRoot()
        // 离开首页根页：首页世代换代（旧提示条、首页发起的在途 parse 一并失效），回来也不补出
        if (lastHomeRoot && !nowHome) homeGen += 1
        lastHomeRoot = nowHome
        if (!nowHome) clearPrompt()
        val origin = currentOrigin()
        val previous = lastOrigin
        val moved = origin != previous
        if (moved) {
            // 发起页被覆盖、出栈或切走（同步出口）：界面世代加 1，离开前发起的识别任务与 parse 作废并取消；回到同一页面实例也不恢复（A-R1-02）
            lastOrigin = origin
            screenGen += 1
            pageMoved(nowHome)
        }
        // 先发布新快照、再取消：网络线程此后读到的已是作废后的归属（A-R2-04）
        publish()
        if (moved) {
            cancelOwned()
            previous?.let(::leavePage)
        }
        if (mutableResult.value?.origin != origin) clearResult()
        // 分享已经回首页：到达首页 Tab 根页时再出提示条，身份按此刻一次取定（共性第 7 条）
        presentShare()
        // 用途弹层随界面让位或恢复（G22）：首页来源离开首页根页、设置与手动来源离开发起页（敏感页、强更页推入）即收起
        reviewConsentNotice()
    }

    /** 发起页 [origin] 已离开：协调器撤销它的排队尝试与任务（F-AND-54：别的页面实例不受影响）；它发起的重试 parse 作废。 */
    private fun leavePage(origin: String) {
        importCoordinator.onPageDestroyed(origin)
        flights.values.filter { it.origin == origin }.forEach(::drop)
        fillCallbacks.remove(origin)
        refreshBusy()
    }

    /**
     * 敏感浮层出现（AuthSheet、auth_jump、第三方授权等；AppTrade 在显示的同步出口调）：作废提示条、在途 parse 与结果提示；界面世代加 1，
     * 浮层关闭后旧请求也不恢复（A-R1-04）。
     */
    fun onSensitiveOverlay() {
        // 用途弹层不压在敏感浮层上（G22，各来源）：收起，不记看过、不入队；首页来源的浮层关了以后同一轮里可以再出
        yieldConsentNotice()
        // 暂存与正回首页的系统分享同步作废（R1-02）
        pageGen += 1
        pendingShare = null
        awaitingShare = null
        homeGen += 1
        bumpScreen()
        clearPrompt()
        publish()
        cancelOwned()
        dropAllFlights()
        clearResult()
    }

    // ---------------------------------------------------------------- 作废出口

    /**
     * 隐私结论变化：不再是已同意（撤回、基本模式）时作废全部，协调器撤销任务（隐私已不是同意，手动任务一起撤），并清掉本机的自写记录与提示记录
     * （BR-ID-13 思路，brief §0）。[withdrawn]：这次变化是真实的「撤回隐私同意」（[connect] 按「已同意 → 基本模式」认定）——只有它清功能同意、
     * 通知撤回钩子；首启或隐私政策升级后选【仍不同意】进入基本模式、政策升级转入未决定都不清（BR-ID-16「政策升级不清功能同意」，口径修正推翻 G33）。
     */
    fun onPrivacyChanged(
        state: PrivacyState,
        withdrawn: Boolean = false,
    ) {
        if (state == PrivacyState.CONSENTED) return
        // 首启弹窗（含隐私政策升级）在前：用途弹层先收起，同一轮不再补出
        mutableConsentNotice.value = null
        noticeOrigin = null
        yieldedNotice = null
        pendingTap = null
        invalidateAll()
        clearRecords()
        if (withdrawn) withdrawImportConsent() else importCoordinator.onConsentRevoked()
    }

    /**
     * 撤回隐私同意（R06）：同步清本机开关、功能同意与看过弹层（待上报队列保留，G26；写盘失败时内存里也已清、不许读，并自动重试，G35），置关后
     * 协调器撤销（R16），再通知撤回钩子。确认落盘与失败重试另由 CredentialCleanerRegistry 登记的 [ClipboardPreferences.clearHashes] 负责。
     */
    private fun withdrawImportConsent() {
        importPreferences.clearOnPrivacyWithdraw()
        mutableAutoSwitch.value = false
        importCoordinator.onConsentRevoked()
        onImportConsentWithdrawn()
    }

    /**
     * 撤回同意的本地清理：先删盐、再删全部记录（FU-CLIP §2.2 第 4 条）。这里是隐私结论通知里的即时清理；确认落盘与失败重试由
     * CredentialCleanerRegistry 登记的 [ClipboardPreferences.clearHashes] 负责（APP-07 第 14 条）。
     */
    fun clearRecords() {
        records.withdraw()
    }

    /**
     * 登录、退出、切换账号：作废提示条与在途 parse（请求绑定会话世代），并清商品去重摘要（G07：自写记录、邀请记录与盐保留）。还没处理的系统分享
     * 只在登录世代真的变了时作废（冷启动时会话状态恢复的通知不算换号，FU-CLIP 共性第 7 条）。
     */
    fun onSessionChanged() {
        notifiedSession = sessionEpoch()
        val keep = listOfNotNull(pendingShare, awaitingShare).all { it.session == sessionEpoch() }
        val pending = pendingShare
        val awaiting = awaitingShare
        invalidateAll()
        syncProductSession()
        if (keep) {
            pendingShare = pending
            awaitingShare = awaiting
        }
    }

    /**
     * 网络层的会话保存通知（AppLogin 转发 NetworkHooks.onSessionStored，主线程）：登录世代变了就按会话变化处理——同一身份状态下的 A 换 B
     * （都是 PHONE_BOUND）会话枚举不变、收不到 routing.session 的变化（代码复审 r1 S2-2）；同一世代的刷新只补清没清掉的摘要。
     */
    fun onSessionStored() {
        if (sessionEpoch() != notifiedSession) onSessionChanged() else syncProductSession()
    }

    /** 会话世代变了（退出、换号；同一会话状态下 A 换 B 也算）就清商品去重摘要（G07）。 */
    private fun syncProductSession() {
        val current = sessionEpoch()
        if (current == productSession) return
        if (records.clearProducts()) productSession = current
    }

    /** 切换环境：作废全部。 */
    fun onEnvironmentSwitched() = invalidateAll()

    private fun invalidateAll() {
        // 系统分享的暂存与「回首页后出条」同步作废（FU-CLIP 共性第 2 条）
        pendingShare = null
        awaitingShare = null
        homeGen += 1
        bumpScreen()
        clearPrompt()
        publish()
        cancelOwned()
        dropAllFlights()
        shareBusy = null
        refreshBusy()
        clearResult()
    }

    /** 界面世代加 1（首页世代已先换代时，当前界面标识随之同步，之后的回退栈通知不再重复加）。 */
    private fun bumpScreen() {
        screenGen += 1
        lastOrigin = currentOrigin()
    }

    private fun clearPrompt() {
        ticket = null
        mutablePrompt.value = null
        publish()
    }

    private fun cancelOwned() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    /** 作废一次在途 parse：归属标记置 false（网络线程随即判失效）、取消传输；结果回来后只收尾，不出卡、不提示。 */
    private fun drop(flight: Flight) {
        if (!flight.live.getAndSet(false)) return
        flight.owner?.let(api::cancelOwned)
    }

    private fun dropAllFlights() {
        flights.values.forEach(::drop)
        retryPlan = null
        refreshBusy()
    }

    // ---------------------------------------------------------------- 门槛的现取状态

    private fun consentRaw(): String =
        when (privacy()) {
            PrivacyState.CONSENTED -> ClipboardConsentRaw.AGREED
            PrivacyState.BASIC_MODE -> ClipboardConsentRaw.BASIC
            PrivacyState.NOT_DECIDED -> ClipboardConsentRaw.OUTDATED
        }

    /** 当前没有敏感浮层（首启弹窗、强更、AuthSheet、auth_jump、第三方授权等）。 */
    private fun overlayClear(): Boolean = currentOverlay() !in ClipboardSensitive.overlays

    private fun currentOverlay(): String =
        when {
            promptShowing() -> ClipboardSensitive.OVERLAY_PRIVACY_FIRST_LAUNCH
            routing.update.value == UpdateCheck.BELOW_MIN -> ClipboardSensitive.OVERLAY_FORCE_UPDATE
            else -> overlay() ?: ClipboardSensitive.OVERLAY_NONE
        }

    private fun shareDomains(): List<String> =
        (configData()["share_domains"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .orEmpty()

    /**
     * 当前规则：包内默认（config.clipboard 键名未定，整体回落，G02 / K02）按 features.flags 的 clipboard.enabled.<电商平台>=false 收紧（K03，
     * F-CLIP-05「按平台一键关闭」，只收紧；R09 ①：只过滤、不停读取）。
     */
    private fun rules(): ClipboardRules = rulesOf(configData())

    private fun rulesOf(data: JsonObject): ClipboardRules = baseRules(data).tightened(ClipboardLimits.fromConfigData(data))

    private fun baseRules(data: JsonObject): ClipboardRules = ClipboardRules.fromConfig(data["clipboard"] as? JsonElement)

    /** 当前功能同意版本（config.clipboard.consent_version）；缺键为 null，不用包内常量兜底（G47）。 */
    fun consentVersion(): String? = ImportPreferenceStore.currentConsentVersion(configData())

    /** 自动读取的开关门槛（R11 ②③）：开关开，且功能同意版本就是当前版本；当前版本缺失时一律不满足（G47）。 */
    fun autoReadAllowed(): Boolean = importPreferences.isAutoReadAllowed(consentVersion())

    // ---------------------------------------------------------------- 普通互斥模态的来源（G28 / G29）

    private var modalSources: ClipboardModalSources? = null
    private var modalJob: Job? = null
    private var modalServices: List<Any?>? = null

    /**
     * 绑定普通互斥模态的来源快照：初始同步一次（不触发重试），之后任一来源变化就重新核对；模态从有到无（关闭）时通知协调器在同一轮再试
     * （onPageReady），并让用途弹层按当前状态让位或恢复。同一个 [sources] 重复绑定幂等；换一份时释放旧订阅。订阅跑在注入的 workScope。
     */
    fun bindModalSources(sources: ClipboardModalSources) {
        if (modalSources === sources) return
        modalJob?.cancel()
        modalSources = sources
        var last = sources.current()
        modalJob =
            scope.launch {
                sources.flows.merge().collect {
                    val now = sources.current()
                    val closed = last != null && now == null
                    last = now
                    reviewConsentNotice()
                    if (closed) importCoordinator.onPageReady()
                }
            }
    }

    /**
     * 普通互斥模态的生产装配（FU-AND-0724 G28 / G29；AppModule 在服务构造完成后调用）：登录任务 = [AppLogin.taskActive]，外跳进行中 =
     * [AppTrade.jumpInProgress]，更新提示 = [AppUpdate.promptShowing]；支付与回跳确认 Android 还没有对应状态，恒为 false（F-AND-55）。缺的服务
     * 按 false，实例到达后再以完整快照绑定；同一组服务重复调用不重复订阅。
     */
    fun connectModalSources(
        trade: AppTrade? = null,
        login: AppLogin? = null,
        update: AppUpdate? = null,
    ) {
        val services = listOf(trade, login, update)
        val current = modalServices
        if (current != null && current.size == services.size && current.indices.all { current[it] === services[it] }) return
        modalServices = services
        bindModalSources(
            ClipboardModalSources(
                payment = NO_MODAL,
                loginTask = login?.taskActive ?: NO_MODAL,
                tradeJump = trade?.jumpInProgress ?: NO_MODAL,
                tradeReturnConfirmation = NO_MODAL,
                updatePrompt = update?.promptShowing ?: NO_MODAL,
            ),
        )
    }

    // ---------------------------------------------------------------- 首次用途说明弹层

    /** 回前台来源可以（再）显示用途弹层：已同意隐私、有当前版本、没看过、还没有有效同意、服务端没停本端自动读取。 */
    private fun noticeEligible(): Boolean {
        // 配置缺 consent_version：无版本可记，不出用途弹层（G47）
        val version = consentVersion() ?: return false
        return privacy() == PrivacyState.CONSENTED &&
            !importPreferences.hasSeenNotice(version) &&
            !autoReadAllowed() &&
            ImportGates.autoReadEnabled(configData())
    }

    /** 别的弹窗、普通互斥模态、首启弹窗、强更与敏感浮层（G22）都不在：用途弹层此刻可以显示。 */
    private fun noticeUnobstructed(): Boolean =
        !otherDialogShowing() && modalSources?.current() == null && !promptShowing() && overlayClear()

    /**
     * 外壳的弹窗显示状态变了（可关闭的更新提示出现 / 关闭、强更结论到达、敏感浮层关闭，AppShell 接线）：用途弹层让位或在同一轮里恢复（G22、G41），
     * 并通知协调器同一轮再试（G31：等待中的回前台判定继续）。
     */
    fun onDialogsChanged() {
        reviewConsentNotice()
        importCoordinator.onPageReady()
    }

    /**
     * 用途弹层显示期间持续让位（口径修正）：首启弹窗、强更、更新提示、普通互斥模态、敏感浮层出现，或它所在的页面变了（首页来源离开首页根页、
     * 设置与手动来源离开发起页）就收起——不记看过、不入队；首页来源记下，挡的东西没了且仍在同一轮、首页根页时恢复同一个弹层（本轮仍不读，G21）。
     */
    private fun reviewConsentNotice() {
        val notice = mutableConsentNotice.value
        if (notice == null) {
            resumeYieldedNotice()
        } else if (!noticeStays(notice)) {
            yieldConsentNotice()
        }
    }

    /** 正在显示的用途弹层可以留着：仍已同意隐私、没有别的弹窗与敏感浮层、它所在的页面没变。 */
    private fun noticeStays(notice: ClipboardConsentNotice): Boolean {
        val onItsPage =
            when (notice.source) {
                ClipboardConsentNotice.SOURCE_FOREGROUND -> homeRoot()
                else -> currentOrigin() == noticeOrigin
            }
        return privacy() == PrivacyState.CONSENTED && noticeUnobstructed() && onItsPage
    }

    /** 被挡住让位的回前台弹层：仍是同一轮（在前台、轮次没变）、仍在首页根页、没有被挡且仍该出时恢复显示（同一个弹层，不重新消费轮次）。 */
    private fun resumeYieldedNotice() {
        val notice = yieldedNotice ?: return
        val sameRound = importCoordinator.inForeground && noticeEpoch == importCoordinator.snapshot().epoch
        if (!sameRound) {
            yieldedNotice = null
            return
        }
        val canShow = homeRoot() && !shareInRound() && noticeEligible()
        if (canShow && noticeUnobstructed()) {
            yieldedNotice = null
            noticeOrigin = currentOrigin()
            mutableConsentNotice.value = notice
        }
    }

    /** 收起用途弹层让位（不是用户选择：不记看过、不入队）；首页来源的本轮仍可在同一轮里恢复；手动来源那次点击作废（须再点）。 */
    private fun yieldConsentNotice() {
        val notice = mutableConsentNotice.value ?: return
        mutableConsentNotice.value = null
        noticeOrigin = null
        pendingTap = null
        if (notice.source == ClipboardConsentNotice.SOURCE_FOREGROUND && importCoordinator.inForeground) yieldedNotice = notice
    }

    private fun showConsentNotice(source: String) {
        noticeOrigin = currentOrigin()
        mutableConsentNotice.value = ClipboardConsentNotice(source)
    }

    /** 收起用途弹层（选择本身由调用方记录或不记）；弹窗关闭也是「可以再试」的时刻。 */
    private fun closeNotice() {
        mutableConsentNotice.value = null
        noticeOrigin = null
        importCoordinator.onPageReady()
    }

    /**
     * 手动粘贴入口这次点击要不要先出用途弹层（R02 ③、R19、G15）：已同意隐私、配置有当前版本、还没看过这一版的弹层。配置缺版本（G47）无版本可记、
     * 不出弹层，手动照常。
     */
    fun manualNoticeNeeded(): Boolean {
        val version = consentVersion() ?: return false
        return privacy() == PrivacyState.CONSENTED && !importPreferences.hasSeenNotice(version)
    }

    /** G15：先出用途弹层（也消费本轮，G21），记下这次点击，关弹层后继续。 */
    private fun showManualNotice(
        purpose: String,
        onFilled: ((String?) -> Unit)?,
    ) {
        if (mutableConsentNotice.value != null || !noticeUnobstructed()) return
        pendingTap = PendingTap(purpose, currentOrigin(), onFilled)
        showConsentNotice(ClipboardConsentNotice.SOURCE_MANUAL)
        importCoordinator.snapshot().epoch?.let(importCoordinator::onNoticeShown)
    }

    /** 用途弹层关了（开启或暂不开启都算）：继续挂起的那次手动点击（仍在发起页、仍已同意隐私）。 */
    private fun continuePendingTap() {
        val tap = pendingTap ?: return
        pendingTap = null
        if (tap.origin != currentOrigin() || privacy() != PrivacyState.CONSENTED) return
        tap.onFilled?.let { fillCallbacks[tap.origin] = it }
        importCoordinator.onPasteTap(tap.purpose)
    }

    // ---------------------------------------------------------------- 端口实现：提取、去重、邀请分支

    /**
     * 提取端口（R21、R22、R25）：product_lookup 与自动任务共用候选提取；自动任务另做自写排除与候选摘要去重（只对自动，G14）。盐不可用时判断
     * 不了自写与去重：自动不上传（按去重收束），手动照常。没有商品时走邀请分支；手动命中被关闭的平台给「该平台暂不支持」（R09 ①）。
     */
    @Suppress("ReturnCount") // 限流、自写、去重、商品、邀请逐项短路
    private fun extractCandidates(
        task: ImportTask,
        text: String,
    ): ImportCandidateResult {
        val auto = task.kind == ImportTask.KIND_AUTO
        // 先应用还没处理的规则快照，再记下取定候选之前的修订号（R2-05）
        applyRuleHistory()
        val rulesRev = rulesRevision.get()
        val data = configData()
        val rules = rulesOf(data)
        val domains = shareDomains()
        val view =
            if (auto) {
                syncProductSession()
                records.view() ?: return ImportCandidateResult(ImportCandidateResult.KIND_DEDUP)
            } else {
                null
            }
        // App 自写的内容（最近 10 次）：自动任务不识别（商品与邀请都不，R22）；手动照常（G14）
        if (view != null && view.fingerprint.hash(text) in view.selfWritten) return ImportCandidateResult(ImportCandidateResult.KIND_DEDUP)
        val product = CandidateExtractor.extract(text, rules, domains)
        if (product.kindRaw != ClipboardClassifier.KIND_PRODUCT || product.candidates.isEmpty()) {
            // 只因平台被服务端关闭而没有候选：自动安静结束（只过滤、不停读取），手动提示「该平台暂不支持」（R09 ①，G18）；否则走邀请分支
            if (!auto && CandidateExtractor.extract(text, baseRules(data), domains).kindRaw == ClipboardClassifier.KIND_PRODUCT) {
                publishResult(ClipboardResult.Notice(task.pageInstanceId, ClipboardDraftTexts.text(PLATFORM_OFF_KEY), nextResultId()))
            } else {
                inviteBranch(task, text, rules, domains)
            }
            return ImportCandidateResult(ImportCandidateResult.KIND_NO_CANDIDATES)
        }
        // 42901 共享禁用期：自动识别不发 parse（手动入口执行层已拦）
        if (gate.active) return ImportCandidateResult(ImportCandidateResult.KIND_NO_CANDIDATES)
        val title = ClipboardTitlePolicy.minimize(ClipboardText.quotedTitle(text), rules, domains)
        val upload = product.uploadText ?: return ImportCandidateResult(ImportCandidateResult.KIND_NO_CANDIDATES)
        val approved =
            if (view == null) {
                Approved(upload, title, product.candidates, rulesRev)
            } else {
                // G07：候选摘要（按提取顺序去重、换行连接，不含标题与包装）的持久盐 HMAC；24 小时内同一摘要只自动识别一次
                val summary = view.fingerprint.hash(CandidateExtractor.summaryInput(product.candidates))
                if (records.productSeenWithin24h(view.fingerprint, summary)) return ImportCandidateResult(ImportCandidateResult.KIND_DEDUP)
                Approved(upload, title, product.candidates, rulesRev, view.fingerprint, summary)
            }
        approvals[task.requestId] = approved
        return ImportCandidateResult(ImportCandidateResult.KIND_CANDIDATES, product.candidates.map { it.raw })
    }

    /**
     * 邀请分支（R25、BR-INV-04）：抽到码、满足触发前提（落地页或「邀请码」字样）、游客（G23：已登录不出）→ 自动在首页根页出邀请提示条，
     * 手动在发起页出确认条；确认才保存待绑定码。邀请码不送 parse。
     * 同一内容 24 小时去重只对自动任务（BR-ID-16、G14「TTL 与自写排除只对自动任务」）：自动任务查并记 fp.prompted，盐不可用时不提示；
     * 手动粘贴每次都出新的确认条，既不查也不记 fp.prompted、不依赖盐。
     */
    @Suppress("ReturnCount") // 抽码、触发、会话、去重逐项短路
    private fun inviteBranch(
        task: ImportTask,
        text: String,
        rules: ClipboardRules,
        domains: List<String>,
    ) {
        val code = InviteCodeExtractor.extract(text) ?: return
        if (!ClipboardClassifier.inviteTriggered(text, domains, rules) || !guest()) return
        if (task.kind != ImportTask.KIND_AUTO) {
            publishResult(ClipboardResult.InviteConfirm(task.pageInstanceId, code, nextResultId()))
            return
        }
        if (!homeRoot()) return
        val view = records.view() ?: return
        val hash = view.fingerprint.hash(text)
        val now = clock()
        if (view.prompted.any { it.hash == hash && now - it.atMs in 0 until ClipboardClassifier.PROMPT_WINDOW_MS }) return
        if (!records.recordPromptedHash(view.fingerprint, hash)) return
        showInvitePrompt(code)
    }

    private fun showInvitePrompt(code: String) {
        if (!homeRoot() || privacy() != PrivacyState.CONSENTED) return
        counter += 1
        val next =
            Ticket(counter, currentEpoch(), homeGen, screenGen, sessionEpoch(), api.epochs(), ClipboardClassifier.KIND_INVITE, null, code)
        ticket = next
        mutablePrompt.value = ClipboardPrompt(next.id, ClipboardClassifier.KIND_INVITE, code)
        publish()
    }

    /** fill_input 的成功文本（R20）：只在发起页仍是当前页时按 BR-INV-04 抽码交给那次点击的输入框；bridge_read 的交付在 FU-AND-0725 接桥。 */
    @Suppress("ReturnCount") // 目的、回调、发起页逐项短路
    private fun deliverText(
        task: ImportTask,
        text: String,
    ) {
        if (task.purpose != ImportTask.PURPOSE_FILL_INPUT) return
        val fill = fillCallbacks.remove(task.pageInstanceId) ?: return
        if (task.pageInstanceId != currentOrigin()) return
        fill(ManualPasteAction.decide(ManualPasteAction.ENTRY_LOGIN_INVITE, text, rules()).code)
    }

    // ---------------------------------------------------------------- 端口实现：parse 与呈现

    /**
     * parse 端口：发出之前先记候选摘要（G08：发出前记、网络失败也记；只对自动任务），再发 1 次 parse。摘要没能确认落盘（commit 失败、盐已换）时
     * 这次自动任务不上传、按本轮终态结束（不出卡）：否则重启后 24 小时内会再识别、再上传同一候选（代码复审 r1 S1-1）；手动粘贴仍可再试。
     */
    private fun sendApproved(task: ImportTask) {
        val approved = approvals.remove(task.requestId)
        val fingerprint = approved?.fingerprint
        val summary = approved?.summary
        val unrecorded = fingerprint != null && summary != null && !records.recordProduct(fingerprint, summary)
        if (approved == null || unrecorded) {
            importCoordinator.onParsed(task, EMPTY)
            return
        }
        launchParse(task.requestId, task.pageInstanceId, approved) { importCoordinator.onParsed(task, EMPTY) }
    }

    private fun launchParse(
        key: String,
        origin: String,
        approved: Approved,
        done: () -> Unit,
    ) {
        val flight = Flight(origin, approved)
        val owner = CallOwnership(api.epochs()) { flight.live.get() }
        flight.owner = owner
        flights[key] = flight
        refreshBusy()
        parseCount += 1
        scope.launch {
            val outcome = parser.parse(approved.upload, owner, approved.title)
            // 已经收尾（撤销、过期）的任务：迟到的结果只丢弃
            if (flights[key] !== flight) return@launch
            flight.outcome = outcome
            refreshBusy()
            done()
        }
    }

    /** 呈现端口（协调器已核对归属）：在发起页弹商品卡 1 次（G09）或给对应的提示；在途期间被作废的、规则已收紧的只丢弃。 */
    private fun presentFlight(task: ImportTask) {
        takeFlight(task.requestId)?.let(::present)
    }

    /**
     * 取出在途识别 [key]：先按发布顺序应用期间记下的全部规则快照、再移出（代码复审 r1 S2-1）——移出之后的复核遍历不到它，
     * 「关闭后又开启」的中间快照会被漏掉。
     */
    private fun takeFlight(key: String): Flight? {
        applyRuleHistory()
        val flight = flights.remove(key) ?: return null
        refreshBusy()
        return flight
    }

    @Suppress("ReturnCount") // 作废、本地拒绝、页面、规则逐项短路
    private fun present(flight: Flight) {
        val outcome = flight.outcome ?: return
        if (!flight.live.get() || outcome.dropped) return
        // 期间发布过的规则快照逐份复核（R2-05 / FU-CLIP §2.1 第 5 条）：在途候选已不被允许的结果不呈现
        applyRuleHistory()
        if (!flight.live.get() || flight.approved.candidates.any { !it.stillAllowed(rules()) }) return
        if (!stillOnPage(flight.origin) || !importCoordinator.inForeground) return
        outcome.action?.let { act(it, flight.origin, cardInPlace = true, retry = flight.approved) }
    }

    /** 发起页 [origin] 仍是当前页、仍已同意隐私、没有敏感浮层。 */
    private fun stillOnPage(origin: String): Boolean = origin == currentOrigin() && privacy() == PrivacyState.CONSENTED && overlayClear()

    /** 收尾端口：释放这一任务的已批准上传、在途 parse 与填入回调（撤销、过期时取消传输）。 */
    private fun finishTask(
        task: ImportTask,
        terminalRaw: String,
    ) {
        approvals.remove(task.requestId)
        flights.remove(task.requestId)?.let(::drop)
        refreshBusy()
        if (task.purpose == ImportTask.PURPOSE_FILL_INPUT && terminalRaw != ImportTask.TERMINAL_SUCCESS) {
            val fill = fillCallbacks.remove(task.pageInstanceId)
            // 读不到（空、不可用）按「未识别到邀请码」交回；撤销、过期不填
            if (terminalRaw == ImportTask.TERMINAL_UNAVAILABLE && task.pageInstanceId == currentOrigin()) fill?.invoke(null)
        }
    }

    // ---------------------------------------------------------------- 提示条（邀请、系统分享）

    /** 网络线程的提示条归属核对：只读主线程发布的快照（A-R2-04）；会话、隐私 / 环境世代本身线程安全。主线程上等待之后另用 [valid] 全量复核。 */
    private fun validInBackground(t: Ticket): Boolean {
        val snap = published.get()
        return snap.ticket == t.id &&
            snap.foreground &&
            snap.round == t.round &&
            snap.home == t.homeGen &&
            snap.screen == t.screen &&
            snap.overlayClear &&
            sessionEpoch() == t.session &&
            api.epochs() == t.epochs
    }

    private fun valid(t: Ticket): Boolean =
        ticket?.id == t.id &&
            importCoordinator.inForeground &&
            currentEpoch() == t.round &&
            homeGen == t.homeGen &&
            screenGen == t.screen &&
            homeRoot() &&
            overlayClear() &&
            privacy() == PrivacyState.CONSENTED &&
            sessionEpoch() == t.session &&
            api.epochs() == t.epochs

    /**
     * 提示条的按钮：系统分享 → 这时才上传分享里抽出的片段（G19）；邀请（游客）→ 预填登录页，不发请求。[id] 是渲染时这条提示的编号（APP-07 共性
     * 第 18 条）：已不是当前提示（被新提示替换）的旧回调直接返回，不执行新提示；缺省（null）只给测试直接调用。
     */
    @Suppress("ReturnCount") // 归属失效、限流、邀请、没有片段逐项短路
    fun onPromptAction(id: Long? = null) {
        // 先逐份应用还没处理的规则快照（R2-05：期间曾收紧过的候选在这里作废，不看合并后的最新状态）
        applyRuleHistory()
        val t = ticket ?: return
        if (id != null && id != t.id) return
        if (!valid(t)) {
            clearPrompt()
            return
        }
        // 共享限流与共享 busy 在所有分支（邀请、分享）之前（FU-CLIP R1-03）：已有商品 parse 在途时，重组前到达的确认点击、排队的旧回调都不执行
        if (gate.active || mutableBusy.value) return
        if (t.kind == ClipboardClassifier.KIND_INVITE) {
            confirmInvite(t)
            return
        }
        val upload = t.upload ?: return
        val busy = takeShareBusy()
        mutablePrompt.value = mutablePrompt.value?.copy(busy = true)
        publish()
        val owner = CallOwnership(api.epochs()) { validInBackground(t) }
        owners += owner
        parseCount += 1
        scope.launch {
            val outcome =
                try {
                    parser.parse(upload, owner, t.title, t.scene)
                } finally {
                    releaseShareBusy(busy)
                }
            owners -= owner
            // 等待之后复核：已进后台、离开首页、撤回同意、换账号、换环境的迟到响应只丢弃
            if (!valid(t)) return@launch
            if (outcome.dropped) {
                // 本地拒绝等（提示条仍有效）：按钮恢复可点，不出卡、不提示
                mutablePrompt.value = mutablePrompt.value?.copy(busy = false)
                return@launch
            }
            val origin = currentOrigin()
            clearPrompt()
            // 系统分享沿用原有的结果去向（单卡以种子打开详情，G19 不改分享流程）
            outcome.action?.let { act(it, origin, cardInPlace = false, retry = null) }
        }
    }

    private fun confirmInvite(t: Ticket) {
        val code = t.inviteCode ?: return
        val session = if (guest()) InviteConfirmAction.SESSION_GUEST else InviteConfirmAction.SESSION_PHONE
        val decision = InviteConfirmAction.decide(session, me = null)
        clearPrompt()
        if (decision.actionRaw == InviteConfirmAction.PREFILL_LOGIN) {
            invitePrefill.set(code)
            open(RouteNames.LOGIN, JsonObject(emptyMap()))
        }
        // TODO(N-30) G01：已登录用户的补填绑定等契约补 POST /v1/me/inviter 与 InviteBackfill 路由
    }

    /** 提示条的【关闭】：放弃，同一内容 24 小时内不再提示（出提示条时已记录）。[id] 不是当前提示时不动（旧关闭回调不清掉新提示，共性第 18 条）。 */
    fun onPromptClose(id: Long? = null) {
        if (id != null && ticket?.id != id) return
        clearPrompt()
    }

    // ---------------------------------------------------------------- 用户主动粘贴

    /**
     * 用户点商品粘贴入口（[entry]：首页卡与开关关闭时首页的自绘按钮 home、SearchTyping 的「粘贴查返利」search_typing，都是 product_lookup，G25）。
     * 已同意隐私、不在 42901 禁用期、本页没有商品 parse 在途、没有敏感浮层时受理；没看过用途弹层先出弹层（G15）。读取、提取、上传、出卡经协调器的
     * 手动任务（R19：不查开关与轮次、不读写去重、不做自写排除），结果绑定发起页。
     */
    @Suppress("UnusedParameter") // 两个商品入口同一个目的（G25）；保留入口参数供诊断与旧调用方
    fun paste(entry: String) {
        if (!pasteAllowed() || mutableConsentNotice.value != null) return
        if (manualNoticeNeeded()) {
            showManualNotice(ImportTask.PURPOSE_PRODUCT_LOOKUP, onFilled = null)
            return
        }
        importCoordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
    }

    /**
     * 手动粘贴的邀请确认条点确认（游客）：发起页仍是当前页才预填登录页；不发请求。执行层先核对共享限流与「该结果仍是当前结果」
     * （APP-07 共性第 16 条，F-AND-22）。
     */
    fun confirmManualInvite(result: ClipboardResult.InviteConfirm) {
        if (!actionable(result)) return
        consumeResult(result)
        if (result.origin != currentOrigin() || privacy() != PrivacyState.CONSENTED || !overlayClear()) return
        val session = if (guest()) InviteConfirmAction.SESSION_GUEST else InviteConfirmAction.SESSION_PHONE
        val decision = InviteConfirmAction.decide(session, me = null)
        if (decision.actionRaw == InviteConfirmAction.PREFILL_LOGIN) {
            invitePrefill.set(result.code)
            open(RouteNames.LOGIN, JsonObject(emptyMap()))
        }
    }

    private fun guest(): Boolean = routing.session.value == SessionState.GUEST

    /** 粘贴入口此刻能用：已同意、不在 42901 禁用期、本页没有商品 parse 在途（共享 busy）、没有敏感浮层。 */
    private fun pasteAllowed(): Boolean = privacy() == PrivacyState.CONSENTED && !gate.active && !mutableBusy.value && overlayClear()

    /**
     * 邀请码框【粘贴】（fill_input，R20）：只抽码，不上传；抽不到返回 null（页面轻提示「未识别到邀请码」，不清空已有输入）。42901 禁用期内执行层
     * 也拦（不读剪贴板，A-R1-06）。G15：还没看过用途弹层时先出弹层并返回 null，关掉后同一次点击经协调器读一次、抽码交给 [onFilled] 一次（发起页
     * 离开则不填）；已看过时立即读一次并返回码（[onFilled] 不调用）。基本模式不读、不出弹层。
     */
    @Suppress("ReturnCount") // 未同意 / 限流、先出用途弹层、立即读三个出口
    fun pasteInviteCode(onFilled: ((String?) -> Unit)? = null): String? {
        if (privacy() != PrivacyState.CONSENTED || gate.active) return null
        if (manualNoticeNeeded()) {
            if (mutableConsentNotice.value == null) showManualNotice(ImportTask.PURPOSE_FILL_INPUT, onFilled)
            return null
        }
        val read = adapter.read()
        val text = read.text.takeIf { read.kindRaw == ImportReadResult.KIND_TEXT }
        return ManualPasteAction.decide(ManualPasteAction.ENTRY_LOGIN_INVITE, text, rules()).code
    }

    /**
     * Agent 页「粘贴查返利」（APP-10 G29，BR-ID-16：用户点击才读）：已同意隐私政策、不在 42901 禁用期、没有 parse 在途、没有授权类敏感浮层时读一次主剪贴板文字，
     * 原样交给发起它的那一个输入框（不 parse、不记 hash、不改写剪贴板、不进日志）；读不到为 null。由用户点发送，不自动发送剪贴板内容。
     * TODO(FU-AND-0725) AgentChat 直送：改为 product_lookup 并在对话页出卡。
     */
    fun pasteForAgent(): String? {
        if (!pasteAllowed()) return null
        return (system.read() as? ClipRead.Text)?.value?.takeIf(String::isNotBlank)
    }

    // ---------------------------------------------------------------- 桥 clipboard.read（FU-CLIP C4）

    /**
     * H5 经桥 clipboard.read（L2：白名单来源、主框架、已登录、1 秒内手势由 BridgeHost 先判）：读之前再核对隐私已同意当前版本（K12：不满足不读、
     * 回 ""）；读到的原文只在这一次调用里做候选拼接（白名单链接 / 口令最多 3 个 + 最小化标题），抽不到回 ""；邀请内容、非白名单链接与其余文字
     * 都不回；不套自写排除与 24 小时去重（K13）。原文不进日志与桥事件。TODO(FU-AND-0725) bridge_read 目的与 90004 / 90500 错误码。
     */
    fun bridgeRead(): String =
        ClipboardBridgeRead.respond(
            consentRaw = consentRaw(),
            read = { (system.read() as? ClipRead.Text)?.value },
            rules = rules(),
            shareDomains = shareDomains(),
        )

    // ---------------------------------------------------------------- 规则表变化（FU-CLIP §2.1 第 5 条）

    /**
     * 配置快照换了：已显示但未点击的提示条、暂存的分享、在途的识别 parse，若候选已不被允许就同步作废。先逐份应用期间发布过的每一份规则快照
     * （[applyRuleHistory]），再按此刻的规则复核。
     */
    fun onRulesChanged() {
        applyRuleHistory()
        dropDisallowed(rules(), Long.MAX_VALUE)
    }

    /** 规则修订号（R2-05）：[watchRules] 每记下一份发布的配置快照加 1。提示条、暂存分享与识别任务记下取定候选之前的修订号。 */
    private val rulesRevision = AtomicLong()

    /** 已发布、还没在主线程应用的规则快照（修订号, 当时的规则），按发布顺序；逐份应用，不合并（R2-05）。 */
    private val ruleHistory = ConcurrentLinkedQueue<Pair<Long, ClipboardRules>>()

    private val watchedStores: MutableSet<ConfigStore> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    /**
     * 规则收紧的通知链（FU-AND-0714 第 2 轮评审 R2-05）：不依赖可合并的 [ConfigStore.snapshots]（界面线程来不及收时「关 → 开」只剩「开」）。
     * 在 ConfigStore 发布每一份快照的同一处（[ConfigStore.onEachSnapshot]，发布线程、发布顺序）同步记下那一刻的规则与修订号，再切到主线程逐份应用；
     * 主线程上任何按规则放行的入口（出分享提示条、处理暂存分享、点提示条、取定识别候选、呈现识别结果）也先逐份应用。同一个 store 只接一次。
     */
    internal fun watchRules(store: ConfigStore) {
        if (!watchedStores.add(store)) return
        store.onEachSnapshot { snapshot ->
            ruleHistory.add(rulesRevision.incrementAndGet() to rulesOf(snapshot.data))
            scope.launch { applyRuleHistory() }
        }
    }

    /** 主线程：按发布顺序逐份应用还没处理的规则快照——每一份只作废它发布之前就已取定候选（修订号更小）且在它之下不被允许的东西。 */
    private fun applyRuleHistory() {
        while (true) {
            val (revision, rules) = ruleHistory.poll() ?: return
            dropDisallowed(rules, revision)
        }
    }

    /** 候选在 [rules] 下已不被允许、且是在修订号 [revision] 之前取定的提示条（无在途 parse）、暂存分享、在途识别与可重试结果：同步作废。 */
    private fun dropDisallowed(
        rules: ClipboardRules,
        revision: Long,
    ) {
        fun stale(candidates: List<ClipboardCandidate>) = candidates.any { !it.stillAllowed(rules) }
        if (ticket?.let { it.rulesRev < revision && it.busyFree() && stale(it.candidates) } == true) clearPrompt()
        if (pendingShare?.let { it.rulesRev < revision && stale(it.decision.candidates) } == true) pendingShare = null
        if (awaitingShare?.let { it.rulesRev < revision && stale(it.decision.candidates) } == true) awaitingShare = null
        dropDisallowedImports(revision, ::stale)
    }

    /** 在途识别、已批准未发出的上传、可重试结果：在修订号 [revision] 之前取定且已不被允许的作废。 */
    private fun dropDisallowedImports(
        revision: Long,
        stale: (List<ClipboardCandidate>) -> Boolean,
    ) {
        fun disallowed(approved: Approved) = approved.rulesRev < revision && stale(approved.candidates)
        flights.values.filter { disallowed(it.approved) }.forEach(::drop)
        approvals.values.removeAll(::disallowed)
        if (retryPlan?.second?.let(::disallowed) == true) retryPlan = null
        refreshBusy()
    }

    /** 提示条没有在途 parse（在途的不追回）。 */
    private fun Ticket.busyFree(): Boolean = mutablePrompt.value?.let { it.id != id || !it.busy } ?: true

    // ---------------------------------------------------------------- 系统分享导入（FU-CLIP C6）

    /**
     * 收到的一次分享（不含原文）：[serial] 递增接收编号；身份在到达时一次取定（隐私 / 环境世代 [epochs]、登录世代 [session]、前台轮次 [round]
     * 与此刻是否在前台 [foreground]，共性第 1、2 条）；之后只拿它们与当前状态比较。
     */
    @Suppress("LongParameterList") // 到达时一次取定的身份逐项
    private class PendingShare(
        val serial: Long,
        val decision: ShareImportDecision,
        val epochs: CallEpochs,
        val session: Long,
        val round: Long,
        val foreground: Boolean,
        /**
         * 到达时的页面票据（[pageGen]，R1-02）：外壳已就绪时取定，之后页面每变一次（入站路由、待办恢复、切 Tab、敏感浮层）都同步作废这次分享；
         * 外壳还没就绪（冷启动、进程恢复）时为 null——此时还没有可确定的页面实例，恢复过程中的 Tab / 回退栈同步不算页面变化（R1-01）。
         */
        val page: Long?,
        /** 取定 [decision] 的候选之前的规则修订号（R2-05）。 */
        val rulesRev: Long,
    )

    private var shareSerial = 0L

    /** 已收到、还没处理（等前台与外壳就绪）的分享。 */
    private var pendingShare: PendingShare? = null

    /** 已经经 Router 回首页、等到达首页 Tab 根页再出条的分享。 */
    private var awaitingShare: PendingShare? = null

    /** 外壳已同步过当前的 Tab 与基本模式（[onShell]）、回退栈（[onBackStack]）；外壳重新进入组合时清零（[onShellAttached]）。 */
    private var tabSynced = false
    private var stackSynced = false

    /**
     * 页面世代（系统分享用，R1-02）：当前前台界面每变一次（[screenChanged] 判定为移动）、敏感浮层每出现一次加 1。不随作废出口（进后台、会话恢复
     * 通知等）变化，所以只反映「页面换了」。
     */
    private var pageGen = 0L

    /** 正在执行本次分享主动回首页的受控交接（[processShare] 里 Router 打开首页的同步调用期间）。 */
    private var shareHandoff = false

    /** 外壳就绪：Tab、基本模式与回退栈都已按恢复后的状态同步，且 NavHost 接着 Router（R1-01：确认导航器已接好）。 */
    private fun shellReady(): Boolean = tabSynced && stackSynced && routing.navigatorAttached

    /** 用户刚从别的 App 分享过来（暂存、正回首页或分享提示条显示中）：这一轮前台不读剪贴板，也不出用途弹层（AS04、G19）。 */
    private fun shareInRound(): Boolean = pendingShare != null || awaitingShare != null || ticket?.scene == ShareImportAction.SCENE_SHARE

    /**
     * 页面换了（同步出口）：暂存的分享若已有页面票据就作废（R1-02）；正回首页的分享只在本次受控交接的导航中、或这次变化正好到达首页 Tab 根页
     * （交接的回退栈通知稍后才到）时保留，其余一律作废，不给失效的分享重新绑定当前页面。
     */
    private fun pageMoved(nowHome: Boolean) {
        pageGen += 1
        if (pendingShare?.page != null) pendingShare = null
        if (awaitingShare != null && !shareHandoff && !nowHome) awaitingShare = null
    }

    /**
     * 系统分享到达（专用接收 Activity 的 onCreate 当场调，FU-CLIP §2.5）：[text] 是 EXTRA_TEXT，当场抽候选后即丢弃，不保存、不进日志。
     * 到达这一刻就过闸门（隐私、首启弹窗、敏感页与敏感浮层、parse 在途，K18、K19、K22）并固定身份与递增接收编号（共性第 1 条）；
     * 每次到达都替代之前未处理的分享（HS15 同口径）。返回是否留下待处理的分享（测试与诊断用；不含内容）。
     */
    fun receiveShare(
        mime: String?,
        text: String?,
    ): Boolean {
        shareSerial += 1
        pendingShare = null
        awaitingShare = null
        applyRuleHistory()
        val rulesRev = rulesRevision.get()
        val decision = ShareImportAction.decideWith(shareState(), ShareImportAction.payload(mime, text, rules(), shareDomains()))
        if (decision.actionRaw !in HANDLED_SHARES) return false
        val page = if (shellReady()) pageGen else null
        val foreground = importCoordinator.inForeground
        pendingShare = PendingShare(shareSerial, decision, api.epochs(), sessionEpoch(), currentEpoch(), foreground, page, rulesRev)
        processShare()
        return true
    }

    /** 分享接收 Activity 拉起 MainActivity 之后（onNewIntent）：已在前台时立即处理暂存的分享。 */
    fun onShareDelivered() = processShare()

    /** 是否有还没处理完的分享（测试与诊断用；不含内容）。 */
    val sharePending: Boolean get() = pendingShare != null || awaitingShare != null

    private fun shareState(): JsonObject =
        buildJsonObject {
            put("consent", consentRaw())
            put("route", if (homeOnTop) tab else topRoute)
            put("overlay", currentOverlay())
            put("parseInFlight", mutableBusy.value)
        }

    /** 分享到达时取定的归属仍成立：隐私仍已同意、隐私 / 环境世代与登录世代都没变（真实撤回、切环境、换号立即作废，共性第 7 条）。 */
    private fun shareOwned(p: PendingShare): Boolean =
        privacy() == PrivacyState.CONSENTED && api.epochs() == p.epochs && sessionEpoch() == p.session

    /**
     * 处理暂存的分享：前台与外壳都就绪后才处理（冷启动等环境、首屏与前台，共性第 7 条）；只消费正好这一轮前台（到达时在前台）或紧接着的下一次
     * 回前台（到达时在后台、冷启动）的交接（共性第 2 条）；这一轮随之跳过自动读剪贴板（G19，协调器 markSkipped）；再过一次闸门，经 Router 回到
     * 首页 Tab 根页，到达后出条。
     */
    @Suppress("ReturnCount") // 未就绪、归属失效、闸门逐项短路
    private fun processShare() {
        applyRuleHistory()
        val p = pendingShare ?: return
        if (!importCoordinator.inForeground || !shellReady()) return
        pendingShare = null
        if (!shareStillCurrent(p)) return
        importCoordinator.markSkipped(currentEpoch())
        // 等待之后复核：此刻停在敏感页 / 敏感浮层、parse 在途 → 丢弃，不跳走（K19、K22）
        if (ClipboardSensitive.sensitive(if (homeOnTop) tab else topRoute, currentOverlay()) || mutableBusy.value) return
        awaitingShare = p
        if (!homeRoot()) handOffHome(p)
        presentShare()
    }

    /**
     * 暂存的分享仍属于这次交接：正好这一轮前台（到达时在前台）或紧接着的下一次回前台（到达时在后台、冷启动），归属未变，且到达后页面没换过
     * （页面票据，R1-02；页面变化的同步出口已作废，这里再核对一次）。
     */
    private fun shareStillCurrent(p: PendingShare): Boolean {
        val expectedRound = if (p.foreground) p.round else p.round + 1
        val samePage = p.page == null || p.page == pageGen
        return currentEpoch() == expectedRound && shareOwned(p) && samePage
    }

    /** 本次分享主动回首页的受控交接（R1-02）：只有这次导航引起的页面变化不作废它。 */
    private fun handOffHome(p: PendingShare) {
        shareHandoff = true
        try {
            open(RouteNames.HOME, JsonObject(emptyMap()))
        } finally {
            shareHandoff = false
        }
        // 没有切到首页 Tab（Router 拦下、导航器不在）：不留着等以后碰巧回到首页再出旧分享
        if (awaitingShare === p && tab != RouteNames.HOME) awaitingShare = null
    }

    /** 回到首页 Tab 根页之后出分享提示条或本地 30132（身份按此刻一次取定，之后只比较，共性第 7 条、APP-09 第 65 条）。 */
    @Suppress("ReturnCount") // 未到首页、归属失效、规则失效逐项短路
    private fun presentShare() {
        // 发布前复核也要看到期间曾经的收紧（R2-05）：先逐份应用还没处理的规则快照，再按此刻的规则复核
        applyRuleHistory()
        val p = awaitingShare ?: return
        if (!homeRoot() || !importCoordinator.inForeground) return
        awaitingShare = null
        if (!shareOwned(p) || !overlayClear() || mutableBusy.value) return
        val decision = p.decision
        // 发布前按此刻的规则再校验全部候选（FU-CLIP 共性第 9 条）：配置收紧后已不被允许 → 丢弃（不改用当前身份重新判定）
        val rules = rules()
        if (decision.candidates.any { !it.stillAllowed(rules) }) return
        when (decision.actionRaw) {
            ShareImportAction.PROMPT -> {
                // 新分享替换未点击的提示条（邀请或分享，K22）与首页的结果提示；不记提示历史、不查自写（K21）
                clearResult()
                counter += 1
                val next =
                    Ticket(
                        counter,
                        currentEpoch(),
                        homeGen,
                        screenGen,
                        sessionEpoch(),
                        api.epochs(),
                        ClipboardClassifier.KIND_PRODUCT,
                        decision.uploadText,
                        null,
                        decision.prefill,
                        decision.candidates,
                        ShareImportAction.SCENE_SHARE,
                        rulesRev = p.rulesRev,
                    )
                ticket = next
                mutablePrompt.value =
                    ClipboardPrompt(next.id, ClipboardClassifier.KIND_PRODUCT, display = decision.display, share = true)
                publish()
            }

            ShareImportAction.SEARCH_PREFILL -> {
                // 非商品：本地 30132 同款提示，预填只取最小化后的「」标题（没有就留空，不拿原文首行），不上传
                clearPrompt()
                publishResult(unrecognized(currentOrigin(), decision.prefill))
            }

            else -> {
                Unit
            }
        }
    }

    // ---------------------------------------------------------------- 结果

    private var serial = 0L

    /**
     * parse 动作 → 结果。[cardInPlace]：剪贴板识别（自动、手动、重试）的单卡也在当前页弹卡（G09）；系统分享沿用以种子打开详情。[retry]：网络异常时
     * 可显式重试的已批准上传（R24）。
     */
    @Suppress("CyclomaticComplexMethod") // parse 动作逐项
    private fun act(
        action: ParseAction,
        origin: String,
        cardInPlace: Boolean,
        retry: Approved?,
    ) {
        when (action.actionRaw) {
            ParseResultAction.OPEN_DETAIL -> {
                val card = action.card ?: return
                if (cardInPlace || !openDetail(card)) {
                    publishResult(ClipboardResult.CardList(origin, listOf(ParseRow(card, null, null)), action.traceId, nextResultId()))
                }
            }

            ParseResultAction.CARD_LIST -> {
                // 行内 10405（外层成功）：主动进入已有的强更分流（APP-07 共性第 20 条），不出弹层
                if (action.rows.any { it.card == null && it.errorCode == ParseResultAction.FORCE_UPDATE_CODE }) {
                    onForceUpdate()
                    return
                }
                // 行内 42901：启动共享禁用期（成功响应没有 Retry-After，按 5 秒，A11）；其余行内码在弹层里逐条按码给动作（A-R1-05）
                if (action.rows.any { it.card == null && it.errorCode == ParseResultAction.RATE_LIMITED }) block(DEFAULT_BLOCK_MS)
                publishResult(ClipboardResult.CardList(origin, action.rows, action.traceId, nextResultId()))
            }

            ParseResultAction.SEARCH_PREFILL -> {
                publishResult(unrecognized(origin, action.prefill))
            }

            ParseResultAction.RETRY_AFTER -> {
                block(action.disableMs ?: DEFAULT_BLOCK_MS)
                notice(origin, action.textKey, null)
            }

            ParseResultAction.NOTICE, ParseResultAction.ERROR -> {
                notice(origin, action.textKey, action.traceId.takeIf { action.showTrace6 != false })
                // 网络异常（没有业务码）：不自动重读，结果上保留显式重试，复用已批准的候选（R24）
                if (retry != null && networkFailure(action)) retryPlan = checkNotNull(mutableResult.value).id to retry
            }

            // 单条结果 error_code=10405（外层成功，网络层看到的是成功外壳）：主动进入已有的强更分流（APP-07 共性第 20 条）；整体 10405
            // 网络层已分流，这里再调一次也只是同一个结论
            ParseResultAction.FORCE_UPDATE -> {
                onForceUpdate()
            }

            else -> {
                Unit
            }
        }
    }

    private fun networkFailure(action: ParseAction): Boolean =
        action.actionRaw == ParseResultAction.ERROR &&
            action.errorCode == null &&
            action.textKey == ParseInputService.NETWORK_TEXT_KEY

    private fun notice(
        origin: String,
        key: String?,
        traceId: String?,
    ) {
        val text = textOrGeneric(key ?: GENERIC_ERROR_KEY)
        publishResult(ClipboardResult.Notice(origin, ResultSignals.withTrace6(text, traceId), nextResultId()))
    }

    private fun unrecognized(
        origin: String,
        prefill: String?,
    ) = ClipboardResult.Unrecognized(origin, ClipboardDraftTexts.text(UNRECOGNIZED_KEY), prefill, nextResultId())

    /** 发布新结果：替换之前的结果，旧结果上的重试随之失效。 */
    private fun publishResult(next: ClipboardResult) {
        retryPlan = null
        mutableResult.value = next
    }

    private fun clearResult() {
        retryPlan = null
        mutableResult.value = null
    }

    /** 每次发布结果的新编号（APP-07 共性第 26 条）。 */
    private fun nextResultId(): Long {
        serial += 1
        return serial
    }

    /** 结果提示被关掉或显示完：只消费渲染时的那一条（按编号认，APP-07 共性第 18、26 条：旧关闭回调不清掉内容相同的新结果）。 */
    fun consumeResult(result: ClipboardResult) {
        if (current(result)) clearResult()
    }

    /** [result] 仍是当前结果（渲染时绑定的那一条，按发布编号核对；旧回调不读最新状态去执行，APP-07 共性第 16、18、26 条）。 */
    private fun current(result: ClipboardResult): Boolean = mutableResult.value?.id == result.id

    /**
     * 结果按钮（打开卡片、用商品名搜索、邀请确认、重试）在执行层的统一核对（APP-07 共性第 16 条，F1-04）：共享限流（42901 禁用期）中不执行，
     * 且必须仍是当前结果（F1-05）。界面同步置灰（[blocked]）。
     */
    private fun actionable(result: ClipboardResult): Boolean = !gate.active && !mutableBusy.value && current(result)

    /** [result] 是网络异常、可以显式重试的当前结果（R24）。 */
    fun canRetry(result: ClipboardResult): Boolean = retryPlan?.first == result.id && current(result)

    /**
     * 网络异常结果上的【重试】（R24）：只重试仍是当前结果、仍在发起页的失败，复用原已批准的候选与标题（同一个请求体），不读此刻的剪贴板；重试后
     * 旧结果的按钮不再生效。期间规则收紧不再允许的候选不重试。
     */
    @Suppress("ReturnCount") // 不可重试、不在发起页、规则失效逐项短路
    fun retryResult(result: ClipboardResult) {
        val plan = retryPlan?.takeIf { it.first == result.id }?.second ?: return
        if (!actionable(result) || !stillOnPage(result.origin)) return
        applyRuleHistory()
        if (retryPlan?.first != result.id || plan.candidates.any { !it.stillAllowed(rules()) }) return
        consumeResult(result)
        retrySerial += 1
        val key = RETRY_KEY_PREFIX + retrySerial
        launchParse(key, result.origin, plan) { takeFlight(key)?.let(::present) }
    }

    /**
     * 文案键的正文；键不存在或正文为空（未收录的 5xxxx 等）回落到 error.50001 的通用正文（A-R2-01，规划 03 §4.2、BR-TEXT-14）。
     * 整体失败（[notice]）与多结果单条 error_code（[rowText]）共用，trace6 由调用方统一附加。
     */
    private fun textOrGeneric(key: String): String = ClipboardDraftTexts.text(key).ifEmpty { ClipboardDraftTexts.text(GENERIC_ERROR_KEY) }

    /** 30132 的【用商品名搜索】：[fill] 是 SearchTyping 本页的搜索框；没有时经 Router 打开 Search（q 预填，提取不到就留空）。 */
    fun searchByName(result: ClipboardResult.Unrecognized) {
        if (!actionable(result)) return
        consumeResult(result)
        searchByName(result.origin, result.prefill)
    }

    /** 商品卡弹层里行内 30132 的【用商品名搜索】（A-R1-05）：预填取这一行命中的「」内或首行（与单条 30132 同一规则）。 */
    fun searchByName(
        result: ClipboardResult.CardList,
        row: ParseRow,
    ) {
        if (!actionable(result) || result.rows.none { it === row }) return
        consumeResult(result)
        searchByName(result.origin, ClipboardText.searchPrefill(row.hitRaw))
    }

    /**
     * 商品卡弹层里一条错误行的文案（A-R1-05，按码）：30132 → error.30132（另给【用商品名搜索】）；42901 → error.42901（禁用期已启动）；
     * 5xxxx → error.<码> 带本次 trace_id 末 6 位；其余（30131、30141…）→ error.<码>。
     */
    fun rowText(
        result: ClipboardResult.CardList,
        row: ParseRow,
    ): String {
        val code = row.errorCode ?: ParseResultAction.UNSUPPORTED
        if (code < SERVER_ERROR_MIN) return ClipboardDraftTexts.text("error.$code")
        // 5xxxx：没有专门文案的码用通用错误文案；统一带 trace6（不依赖文案里有占位符）
        return ResultSignals.withTrace6(textOrGeneric("error.$code"), result.traceId)
    }

    private fun searchByName(
        origin: String,
        prefill: String?,
    ) {
        val q = prefill?.take(SEARCH_Q_MAX)
        val fill = fills[origin]
        if (fill != null) {
            fill(q.orEmpty())
            return
        }
        open(SEARCH_ROUTE, if (q.isNullOrEmpty()) JsonObject(emptyMap()) else JsonObject(mapOf("q" to JsonPrimitive(q))))
    }

    /** 商品卡弹层点一张卡（G09：单卡也是点了才进详情）：以这张卡为种子打开详情，详情与购买沿用 parse 卡的 link_id，不重取商品。 */
    fun openCard(
        result: ClipboardResult.CardList,
        card: JsonObject,
    ) {
        if (!actionable(result) || result.rows.none { it.card === card }) return
        consumeResult(result)
        openDetail(card)
    }

    @Suppress("ReturnCount") // 缺平台、缺 product_key、打开三个出口
    private fun openDetail(card: JsonObject): Boolean {
        val platform = card.text("platform") ?: return false
        val key = card.text("product_key") ?: return false
        seeds.put(key, card)
        val params = linkedMapOf<String, JsonElement>("platform" to JsonPrimitive(platform), "product_key" to JsonPrimitive(key))
        card.text("item_ref")?.let { params["item_ref"] = JsonPrimitive(it) }
        return open(PRODUCT_DETAIL_ROUTE, JsonObject(params))
    }

    private fun open(
        route: String,
        params: JsonObject,
    ): Boolean = routing.router.open(RouteRequest(route, params, RouteSource.IN_APP)).opensPage

    private fun block(millis: Long) {
        gate.start(millis)
        mutableBlocked.value = true
        gateTimer?.cancel()
        gateTimer =
            scope.launch {
                while (gate.active) delay(gate.remainingMillis())
                mutableBlocked.value = false
            }
    }

    // ---------------------------------------------------------------- 设置

    /**
     * 设置页开关（设备级，存本机；登录、退出、换账号都不改它）。打开：功能同意版本仍有效时直接开（G20，不再弹层、不重复上报）；否则先出用途弹层，
     * 点【开启】才开（R02 ②）。重新打开不通知协调器再试（G29：只改开关状态，下一次真实页面就绪或下一轮再判定）。关闭：立即置关，协调器同步撤销
     * 排队的尝试与在途自动任务（R16），再通知 [onAutoReadDisabled]（G20）；只改本机，不写撤回记录。
     */
    fun setAutoSwitch(enabled: Boolean) {
        if (!enabled) {
            // 写盘失败也立即按关闭生效（不读），store 自动重试落盘（G35）
            importPreferences.setOff()
            mutableAutoSwitch.value = false
            importCoordinator.onSwitchOff()
            onAutoReadDisabled()
            return
        }
        val version = consentVersion()
        if (version == null) {
            // 配置缺 consent_version（G47）：开关可拨，只记本机开关；不写功能同意、不入队、不弹层，门槛②不满足、不读。写盘失败已回滚（G35）
            mutableAutoSwitch.value = importPreferences.switchOnWithoutConsent() || importPreferences.snapshot().enabled == true
        } else if (importPreferences.hasValidConsent(version)) {
            // 写盘失败：store 已回滚，开关保持原样，用户可再点（G35）
            mutableAutoSwitch.value = importPreferences.enable(version) || importPreferences.snapshot().enabled == true
        } else if (mutableConsentNotice.value == null && noticeUnobstructed()) {
            showConsentNotice(ClipboardConsentNotice.SOURCE_SETTINGS)
        }
    }

    /**
     * 用途弹层【开启】（R03）：写当前版本、点击时刻、开，并把设备级同意记录入待上报队列（不发请求，G03）；本轮不补读（G21）。手动来源的弹层随后
     * 继续那次点击（G15）。写盘失败（G35）：store 已回滚为点击前的状态（不读、不入队），弹层留着让用户再点，不自动重试开启。
     */
    fun onNoticeEnable() {
        if (mutableConsentNotice.value == null) return
        // 弹层显示期间版本被撤掉（G47）：无版本可记，收起弹层，不写同意、不记看过、不入队
        val version =
            consentVersion() ?: run {
                pendingTap = null
                return closeNotice()
            }
        if (importPreferences.enable(version)) {
            closeNotice()
            mutableAutoSwitch.value = true
            continuePendingTap()
        } else {
            mutableAutoSwitch.value = importPreferences.snapshot().enabled == true
        }
    }

    /**
     * 用途弹层【暂不开启】、返回键或点外部（R04、G27）：只记看过当前版本，保持原选择，不读（写盘失败时内存里已记，store 自动重试）；手动来源的
     * 弹层随后继续那次点击（G15：当次的手动粘贴照常）。
     */
    fun onNoticeNotNow() {
        if (mutableConsentNotice.value == null) return
        closeNotice()
        // 配置缺 consent_version 时无版本可记（G47）：只收起
        consentVersion()?.let(importPreferences::markNoticeSeen)
        continuePendingTap()
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)

    companion object {
        /** 结果的发起页是首页根页（[currentOrigin] 的首页形态）。 */
        fun isHomeOrigin(origin: String): Boolean = origin.startsWith(HOME_ORIGIN_PREFIX)

        /** 分享判定里需要继续处理（回首页）的几种。 */
        private val HANDLED_SHARES =
            setOf(ShareImportAction.PROMPT, ShareImportAction.SEARCH_PREFILL, ShareImportAction.HOME_ONLY)

        /** 系统 CSPRNG 生成 32 字节盐（FU-CLIP §2.2 第 2 条）。 */
        fun secureSalt(): ByteArray = ByteArray(ClipboardFingerprint.SALT_BYTES).also { SecureRandom().nextBytes(it) }

        const val SEARCH_ROUTE = "Search"
        const val PRODUCT_DETAIL_ROUTE = "ProductDetail"
        private const val HOME_ORIGIN_PREFIX = "home:"
        private const val TAB_ORIGIN_PREFIX = "tab:"
        private const val UNRECOGNIZED_KEY = "error.30132"
        private const val PLATFORM_OFF_KEY = "clipboard.platform_off"
        private const val RETRY_KEY_PREFIX = "retry-"

        /** 通用错误正文（未收录的 5xxxx、网络失败）。 */
        private const val GENERIC_ERROR_KEY = ParseInputService.NETWORK_TEXT_KEY
        private const val DEFAULT_BLOCK_MS = 5_000L
        private const val SERVER_ERROR_MIN = 50_000

        /** routes.json Search.q 的 maxLength。 */
        private const val SEARCH_Q_MAX = 100

        private val EMPTY = JsonObject(emptyMap())

        /** 本端没有对应状态的普通模态来源（支付、回跳确认，F-AND-55）与暂缺的服务：恒为 false。 */
        private val NO_MODAL: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    }
}

/**
 * 平台购买状态的接线（AppModule 与集成测试共用，APP-07 共性第 32 条）：取值与搜索卡同一来源（[AppTrade.platformStatus]：50301 覆盖优先，
 * 其次生效配置的 features.platform_status）；配置快照或 50301 覆盖变化时通知商品卡弹层重新映射。没有接购买时只看配置。
 */
fun AppClipboard.connectPurchaseStatus(
    trade: AppTrade?,
    store: ConfigStore?,
) {
    purchaseStatus = { platform -> trade?.platformStatus(platform) ?: AppTrade.platformStatusIn(store, platform) }
    trade?.subscribeStatus(::onPurchaseStatusChanged)
    if (store != null) {
        // 规则表（features.flags 的电商平台开关）的收紧：逐份快照同步记下，不经可合并的状态流（R2-05，见 [AppClipboard.watchRules]）
        watchRules(store)
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            store.snapshots.drop(1).collect {
                // 购买状态按此刻取值重新映射（只看最新，合并无妨）
                onPurchaseStatusChanged()
                // 已显示的提示条、在途识别若候选不再被允许就作废（FU-CLIP §2.1 第 5 条）；期间每份快照已由 watchRules 逐份应用
                onRulesChanged()
            }
        }
    }
}

/**
 * 作废出口的接线（AppModule 与集成测试共用）：隐私结论变化（撤回、基本模式）、登录 / 退出 / 切换账号、切换环境时作废提示条与在途 parse；
 * 隐私不再是已同意时清掉本机 hash 记录。
 */
fun AppClipboard.connect(
    privacy: AppPrivacy,
    routing: AppRouting,
    cleaners: EnvironmentSwitchCleanerRegistry?,
) {
    // 真实撤回 = 已同意 → 基本模式（PrivacyGate.withdraw，含冷启动补完被打断的撤回）；首启 / 政策升级后【仍不同意】是未决定 → 基本模式，不算撤回
    var last = privacy.conclusion
    privacy.onConclusionChanged { next ->
        val withdrawn = last == PrivacyState.CONSENTED && next == PrivacyState.BASIC_MODE
        last = next
        onPrivacyChanged(next, withdrawn)
    }
    if (privacy.conclusion != PrivacyState.CONSENTED) clearRecords()
    // 切换环境：同步作废（在任何等待之前）
    cleaners?.register { _, _ -> onEnvironmentSwitched() }
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
        routing.session.drop(1).collect { onSessionChanged() }
    }
}

/**
 * 规则表的接线（AppModule）：配置快照每换一次，复核已显示的提示条、暂存分享与在途识别的候选（FU-CLIP §2.1 第 5 条）。收紧不靠可合并的状态流：
 * 每份快照在发布处同步记下、主线程逐份应用（[AppClipboard.watchRules]，FU-AND-0714 第 2 轮评审 R2-05）；状态流的收集只作为「按此刻规则复核」的补充。
 */
fun AppClipboard.connectRules(store: ConfigStore) {
    watchRules(store)
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
        store.snapshots.drop(1).collect { onRulesChanged() }
    }
}
