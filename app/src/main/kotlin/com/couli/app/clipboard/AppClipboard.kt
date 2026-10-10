package com.couli.app.clipboard

import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.trade.AppTrade
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardBridgeRead
import com.couli.core.clipboard.ClipboardCandidate
import com.couli.core.clipboard.ClipboardClassifier
import com.couli.core.clipboard.ClipboardContext
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardLimits
import com.couli.core.clipboard.ClipboardPromptDisplay
import com.couli.core.clipboard.ClipboardPromptInfo
import com.couli.core.clipboard.ClipboardReadPolicy
import com.couli.core.clipboard.ClipboardReadState
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardRules
import com.couli.core.clipboard.ClipboardSelfWriteLedger
import com.couli.core.clipboard.ClipboardSensitive
import com.couli.core.clipboard.ClipboardText
import com.couli.core.clipboard.ClipboardTitlePolicy
import com.couli.core.clipboard.ClipboardWatcher
import com.couli.core.clipboard.ClipboardWriter
import com.couli.core.clipboard.InviteConfirmAction
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.ParseAction
import com.couli.core.clipboard.ParseInputService
import com.couli.core.clipboard.ParseResultAction
import com.couli.core.clipboard.ParseRow
import com.couli.core.clipboard.ShareImportAction
import com.couli.core.clipboard.ShareImportDecision
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.clipboard.WatcherReadOutcome
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 首页提示条（界面状态；不含剪贴板原文，只有抽出的邀请码与缩略候选）。
 * [display]：商品提示条上显示的平台与缩略候选（FU-CLIP C7，K26；点击上传的就是同一编号下的那一份候选）；[share]：系统分享提示条（C6）。
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
 * parse 或手动粘贴之后要在发起页上显示的东西（只在发起页仍是当前页时显示）。
 * [id]：每次发布自增的唯一编号（APP-07 共性第 26 条）。内容相同的两次结果编号不同，StateFlow 不会把它们合并；界面回调带渲染时的
 * 结果，执行层按编号核对是不是当前结果，不靠对象身份或 equals。
 */
sealed interface ClipboardResult {
    val origin: String
    val id: Long

    /** 轻提示（30131、30141、42901、5xxxx 带 trace6）。 */
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
     * 多条结果：当前页弹层按搜索卡样式列出（G09）。行内 error_code 逐条按码处理（A-R1-05）：30132 给【用商品名搜索】、42901 已启动
     * 共享禁用期、5xxxx 带 trace6（[traceId] 是本次响应的 trace_id）；文案由 [AppClipboard.rowText] 统一生成。
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
 * 剪贴板识别的 App 接线（APP-07；brief §2；BR-ID-16、BR-INV-04；03 §4.6）：
 * - 读取时机（[watcher]）：MainActivity 的生命周期入口 [onForeground] / [onBackground] / [onWindowFocus]；前提由 [readState] 现取：本机已同意当前
 *   版本隐私政策、配置开关 clipboard.enabled.android 不为 false（G05）、当前路由与浮层不在敏感清单、栈顶网页容器没有经 setAutoDetect(false)
 *   抑制（G06）、设备级开关开启；另外只在首页 Tab 根页读（提示条只在首页显示，别的页面读了也不提示——不读，TODO(N-30) rule-gaps A01）；
 * - 读到的原文只在内存里分类（[ClipboardClassifier]），立刻丢弃，只留抽出的片段（uploadText / 邀请码）与 hash；
 * - 提示条与请求绑定：前台轮次、首页世代、隐私与环境世代、会话世代（[Ticket]）；用户主动粘贴的请求绑定发起页与界面世代（[screenGen]：
 *   页面被覆盖、出栈、切 Tab、敏感浮层出现时同步加 1，回到同一页面实例也不恢复，A-R1-02）。点击、发请求、结果回来，每次等待之后复核
 *   （含敏感浮层，A-R1-04）；进后台、切 Tab（点击的同步出口 [onShell]，A-R1-03）、进二级页、撤回同意、切换账号、切换环境都同步作废提示条
 *   与在途 parse，迟到的响应只丢弃（不出卡、不跳转、不提示）；
 * - 用户主动粘贴（[paste]）：首页卡、Android 开关关闭时首页的按钮、SearchTyping；42901 按 Retry-After 禁用所有粘贴入口与提示条按钮；
 * - 写剪贴板只经 [writer]（先记 hash）；识别、提示、上传、绑定的前后都不写系统剪贴板。
 * 只在主线程使用；唯一的例外是交给网络层的归属核对（CallOwnership.stillOwned，可能在网络线程上调）：它只读主线程发布的不可变快照
 * [OwnerSnapshot]，不碰界面状态与集合（A-R2-04）。
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass") // 生命周期、页面、粘贴入口与作废出口各一个入口；各状态源逐项注入；共享 busy（R1-05）
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 去重与自写记录（FU-CLIP C3）：只存本机盐的 HMAC 指纹与时间（盐经 Keystore 包裹存在 [ClipboardPreferences.salt]）。 */
    val records = ClipboardRecords(prefs.hashes, prefs.salt, clock, randomSalt)

    /** 加盐的自写记录（观察用）。 */
    val ledger get() = records.ledger

    /** 加盐的 24 小时提示记录（观察用）。 */
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

    /** 升级迁移（FU-CLIP §2.2 第 6 条）：旧版未加盐记录还没确认删掉（失败时下一次回前台重试）。 */
    private var legacyPending = true

    private fun dropLegacy() {
        if (legacyPending) legacyPending = !records.dropLegacy()
    }

    init {
        // 升级后第一次运行（已同意或未同意都做）主动删掉旧版未加盐的记录
        dropLegacy()
    }

    /** 进入强更分流（APP-03a 的同一个入口 AppUpdate.on10405；App 接线时给，APP-07 共性第 20 条）。 */
    var onForceUpdate: () -> Unit = {}

    /** 浮层来源（AuthSheet、auth_jump 执行中、第三方登录进行中等，G12）；App 接线时给。 */
    var overlay: () -> String? = { null }

    /**
     * 此刻的平台购买状态（features.platform_status + 点击确认的 50301 覆盖，与搜索卡同一来源，APP-07 共性第 32 条）：多卡弹层（含退化成
     * 一行弹层的单卡）的商品卡按它映射（not_launched 隐藏金额与读屏金额、maintenance 禁用购买）。App 接线见 [connectPurchaseStatus]。
     */
    var purchaseStatus: (platform: String) -> String? = { null }

    private val mutableStatusVersion = MutableStateFlow(0L)

    /** 平台购买状态可能变了（配置快照换了、50301 覆盖变了）就加 1：多卡弹层按它重新映射商品卡（结果编号不变、不重复播报）。 */
    val purchaseStatusVersion: StateFlow<Long> = mutableStatusVersion.asStateFlow()

    /** 平台购买状态的来源变了（主线程）。 */
    fun onPurchaseStatusChanged() {
        mutableStatusVersion.value += 1
    }

    /** 一张结果卡此刻的平台购买状态（卡上的 platform）；没有平台为 null。 */
    fun purchaseStatusOf(card: JsonObject): String? = card.text("platform")?.let(purchaseStatus)

    val watcher = ClipboardWatcher(::readState, ::autoRead)

    // ---------------------------------------------------------------- 当前前台界面

    private var topRoute: String? = null
    private var topEntryId: String? = null
    private var homeOnTop = true
    private var tab: String = RouteNames.HOME
    private var basicShell = false
    private var homeGen = 0L

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

    /** 42901 限流中：所有粘贴入口与提示条按钮置灰（执行层另在 [paste] / [onPromptAction] 拦）。 */
    val blocked: StateFlow<Boolean> = mutableBlocked.asStateFlow()

    /** 共享 busy 的持有者（在途商品 parse 的编号；null = 空闲）。 */
    private var busyToken: Long? = null
    private var busySerial = 0L
    private val mutableBusy = MutableStateFlow(false)

    /**
     * 商品 parse 在途（APP-07 共性第 31 条共享 busy；FU-AND-0710 R1-05）：提示条按钮与所有粘贴入口共用一份。执行层（[paste]、[onPromptAction]）
     * 在途期间不读剪贴板、不发 parse；界面同步置灰。作废（进后台、撤回、换会话、切环境）时随之结束（旧结果按世代丢弃）。
     */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private fun takeBusy(): Long {
        busySerial += 1
        busyToken = busySerial
        mutableBusy.value = true
        return busySerial
    }

    private fun releaseBusy(token: Long) {
        if (busyToken != token) return
        busyToken = null
        mutableBusy.value = false
    }

    private fun releaseAllBusy() {
        busyToken = null
        mutableBusy.value = false
    }

    private val mutableAutoSwitch = MutableStateFlow(prefs.autoDetect)

    /** 设备级开关「打开 App 自动识别剪贴板」（默认开启）。 */
    val autoSwitch: StateFlow<Boolean> = mutableAutoSwitch.asStateFlow()

    /** 手动粘贴的请求世代：作废时加 1，旧请求的归属随之失效。 */
    private var manualGen = 0L
    private val owners = mutableListOf<CallOwnership>()

    /** 测试与诊断：parse 请求发出的次数（不含内容）。 */
    var parseCount: Int = 0
        private set

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
        /** 提示条显示与点击上传的同一份候选（FU-CLIP C7）；规则表变化时据此作废（§2.1 第 5 条）。 */
        val candidates: List<ClipboardCandidate> = emptyList(),
        /** parse 的 scene：剪贴板 clipboard，系统分享 share_ext（K16）。 */
        val scene: String = ParseInputService.SCENE_CLIPBOARD,
    )

    /**
     * 归属快照（A-R2-04，三端共性第 12 条）：页面世代、界面标识、前台轮次、提示条与敏感浮层，由主线程在每个变化出口（导航、切 Tab、
     * 浮层出现、前后台、作废、提示条出现与消失、发请求前）同步发布；网络线程的归属核对只读它（原子引用，整体替换，不可变）。
     * 浮层关闭不必另发：快照里「有浮层」只会让核对偏向失效，新的请求发出前会重新发布。
     */
    private data class OwnerSnapshot(
        val manual: Long = 0L,
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
                manual = manualGen,
                screen = screenGen,
                home = homeGen,
                origin = currentOrigin(),
                foreground = watcher.inForeground,
                round = watcher.round,
                ticket = ticket?.id,
                overlayClear = overlayClear(),
            ),
        )
    }

    // ---------------------------------------------------------------- 生命周期入口（MainActivity）

    /** ON_START（冷启动那次 [cold]；配置变更重建不调）。 */
    fun onForeground(
        cold: Boolean,
        hasFocus: Boolean,
    ) {
        dropLegacy()
        watcher.onForeground(cold, hasFocus)
        publish()
        // 不在前台时到达的系统分享：前台与外壳都就绪后处理一次（FU-CLIP 共性第 2、7 条）
        processShare()
    }

    /** ON_STOP（配置变更重建不调）：作废未点击的提示条、在途读取与 parse、结果提示。 */
    fun onBackground() {
        watcher.onBackground()
        invalidateAll()
    }

    fun onWindowFocus(hasFocus: Boolean) = watcher.onWindowFocus(hasFocus)

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
    }

    /** 外壳（AppShell）进入组合：之后要重新收到 Tab 与回退栈的同步才算就绪（Activity 重建、进程恢复，R1-01）。 */
    fun onShellAttached() {
        tabSynced = false
        stackSynced = false
    }

    /** 外壳离开组合：Router 的结论落不到界面上，暂存的分享等下一次外壳就绪。 */
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

    /** 网页容器（回退栈条目 [entryId]）经桥 clipboard.setAutoDetect：只对这个容器实例生效的临时抑制，容器出栈即失效；不改设置页开关（G06）。 */
    fun setWebAutoDetect(
        entryId: String?,
        enabled: Boolean,
    ) {
        val id = entryId ?: return
        if (enabled) suppressedWeb -= id else suppressedWeb += id
    }

    /** 首页 Tab 根页在前台（不在基本模式页、没有推入的页面、首启弹窗没有显示）。 */
    fun homeRoot(): Boolean = homeOnTop && tab == RouteNames.HOME && !basicShell && !promptShowing()

    /** 当前页的标识：首页根页 = home:<世代>；推入的页面 = 回退栈条目 id；其余 Tab 根页 = tab:<路由名>。 */
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
        val moved = origin != lastOrigin
        if (moved) {
            // 发起页被覆盖、出栈或切走（同步出口）：界面世代加 1，离开前发起的 parse 作废并取消；回到同一页面实例也不恢复（A-R1-02）
            lastOrigin = origin
            screenGen += 1
            pageMoved(nowHome)
        }
        // 先发布新快照、再取消：网络线程此后读到的已是作废后的归属（A-R2-04）
        publish()
        if (moved) cancelOwned()
        if (mutableResult.value?.origin != origin) mutableResult.value = null
        // 分享已经回首页：到达首页 Tab 根页时再出提示条，身份按此刻一次取定（共性第 7 条）
        presentShare()
    }

    /**
     * 敏感浮层出现（AuthSheet、auth_jump、第三方授权等；AppTrade 在显示的同步出口调）：作废提示条、在途 parse 与结果提示；界面世代加 1，
     * 浮层关闭后旧请求也不恢复（A-R1-04）。
     */
    fun onSensitiveOverlay() {
        // 暂存与正回首页的系统分享同步作废（R1-02）
        pageGen += 1
        pendingShare = null
        awaitingShare = null
        homeGen += 1
        bumpScreen()
        clearPrompt()
        publish()
        cancelOwned()
        mutableResult.value = null
    }

    // ---------------------------------------------------------------- 作废出口

    /** 隐私结论变化：不再是已同意（撤回、基本模式）时作废全部，并清掉本机的自写记录与提示记录（BR-ID-13 思路，brief §0）。 */
    fun onPrivacyChanged(state: PrivacyState) {
        if (state == PrivacyState.CONSENTED) return
        invalidateAll()
        clearRecords()
    }

    /**
     * 撤回同意的本地清理：先删盐、再删两份记录（FU-CLIP §2.2 第 4 条）。这里是隐私结论通知里的即时清理；确认落盘与失败重试由
     * CredentialCleanerRegistry 登记的 [ClipboardPreferences.clearHashes] 负责（APP-07 第 14 条）。
     */
    fun clearRecords() {
        records.withdraw()
    }

    /**
     * 登录、退出、切换账号：作废提示条与在途 parse（请求绑定会话世代）。还没处理的系统分享只在登录世代真的变了时作废（冷启动时会话状态恢复
     * 的通知不算换号，FU-CLIP 共性第 7 条）。
     */
    fun onSessionChanged() {
        val keep = listOfNotNull(pendingShare, awaitingShare).all { it.session == sessionEpoch() }
        val pending = pendingShare
        val awaiting = awaitingShare
        invalidateAll()
        if (keep) {
            pendingShare = pending
            awaitingShare = awaiting
        }
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
        manualGen += 1
        publish()
        cancelOwned()
        releaseAllBusy()
        mutableResult.value = null
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

    // ---------------------------------------------------------------- 自动识别

    private fun consentRaw(): String =
        when (privacy()) {
            PrivacyState.CONSENTED -> ClipboardReadPolicy.CONSENT_AGREED
            PrivacyState.BASIC_MODE -> ClipboardReadPolicy.CONSENT_BASIC
            PrivacyState.NOT_DECIDED -> ClipboardReadPolicy.CONSENT_OUTDATED
        }

    /** 当前没有敏感浮层（首启弹窗、强更、AuthSheet、auth_jump、第三方授权等）。 */
    private fun overlayClear(): Boolean = currentOverlay() !in ClipboardSensitive.overlays

    private fun currentOverlay(): String =
        when {
            promptShowing() -> ClipboardSensitive.OVERLAY_PRIVACY_FIRST_LAUNCH
            routing.update.value == UpdateCheck.BELOW_MIN -> ClipboardSensitive.OVERLAY_FORCE_UPDATE
            else -> overlay() ?: ClipboardSensitive.OVERLAY_NONE
        }

    /** 配置开关 clipboard.enabled.android（G05：只认端键；明确 false 才关自动识别，缺键按开启；手动粘贴保留）。 */
    fun configEnabled(): Boolean {
        val flags = (configData()["features"] as? JsonObject)?.get("flags") as? JsonObject
        return (flags?.get(CONFIG_FLAG) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != false
    }

    private fun shareDomains(): List<String> =
        (configData()["share_domains"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .orEmpty()

    /**
     * 当前规则：包内默认（config.clipboard 键名未定，整体回落，G02 / K02）按 features.flags 的 clipboard.enabled.<电商平台>=false 收紧（K03，
     * F-CLIP-05「按平台一键关闭」，只收紧）。
     */
    private fun rules(): ClipboardRules {
        val data = configData()
        return ClipboardRules.fromConfig(data["clipboard"] as? JsonElement).tightened(ClipboardLimits.fromConfigData(data))
    }

    fun readState(): ClipboardReadState =
        ClipboardReadState(
            consent = consentRaw(),
            event = ClipboardReadPolicy.EVENT_FOREGROUND,
            route = if (homeOnTop) RouteNames.HOME else topRoute,
            overlay = currentOverlay(),
            configEnabled = configEnabled(),
            webSuppressed = !homeOnTop && topEntryId in suppressedWeb,
            androidAutoSwitch = mutableAutoSwitch.value,
        )

    /** 回前台后第一次获得焦点：读 1 次（只在首页根页），读到的原文立即分类后丢弃。 */
    @Suppress("ReturnCount") // 不在首页、分享优先、读取结果
    private fun autoRead(round: Long): WatcherReadOutcome {
        // TODO(N-30) rule-gaps A01：提示条只在首页根页显示，别的页面上回前台不读（读了也不会提示）
        if (!homeRoot()) return WatcherReadOutcome.DONE
        // 用户刚从别的 App 分享过来（暂存、正回首页或分享提示条显示中）：这一次前台不再读剪贴板，不用剪贴板提示条替换分享（AS04）
        if (pendingShare != null || awaitingShare != null || ticket?.scene == ShareImportAction.SCENE_SHARE) return WatcherReadOutcome.DONE
        return when (val read = system.read()) {
            ClipRead.Unavailable -> {
                WatcherReadOutcome.UNAVAILABLE
            }

            ClipRead.NotText -> {
                WatcherReadOutcome.DONE
            }

            is ClipRead.Text -> {
                classifyAndPrompt(read.value, round)
                WatcherReadOutcome.DONE
            }
        }
    }

    private fun classifyAndPrompt(
        text: String,
        round: Long,
    ) {
        val guest = routing.session.value == SessionState.GUEST
        // 加盐视图（FU-CLIP C3）：盐不可用 → 24 小时去重与自写排除都判断不了，这一次不自动出提示条（不回落到未加盐 hash）
        val view = records.view() ?: return
        val context =
            ClipboardContext(
                selfWritten = view.selfWritten,
                prompted = view.prompted,
                // 已登录用户的邀请分支只写判定（G01），界面不出提示条：这里按不可绑定；TODO(N-30) G01 契约补接口后接 getMe
                inviteEligible = guest,
                selfInviteCode = null,
                shareDomains = shareDomains(),
                hash = view.fingerprint::hash,
            )
        val rules = rules()
        val verdict = ClipboardClassifier.classify(text, context, rules, clock())
        val hash = verdict.hash ?: return
        val stamp = Stamp(view.fingerprint, hash)
        when (verdict.kindRaw) {
            ClipboardClassifier.KIND_PRODUCT -> {
                // 只有候选非空才出条（分类已保证）；「」标题按上传同一套最小化后才留作 30132 预填（FU-CLIP 共性第 5、8 条）
                val title = ClipboardTitlePolicy.minimize(ClipboardText.quotedTitle(text), rules, shareDomains())
                show(round, stamp, ClipboardClassifier.KIND_PRODUCT, verdict.uploadText, null, title, verdict.candidates)
            }

            ClipboardClassifier.KIND_INVITE -> {
                if (guest) show(round, stamp, ClipboardClassifier.KIND_INVITE, null, verdict.inviteCode)
            }

            else -> {
                Unit
            }
        }
    }

    /** 分类时的加盐指纹与原文指纹（出提示条时记入提示记录）。 */
    private class Stamp(
        val fingerprint: ClipboardFingerprint,
        val hash: String,
    )

    @Suppress("LongParameterList") // 提示条的各项一次取定
    private fun show(
        round: Long,
        stamp: Stamp,
        kind: String,
        upload: String?,
        inviteCode: String?,
        title: String? = null,
        candidates: List<ClipboardCandidate> = emptyList(),
    ) {
        if (!homeRoot() || privacy() != PrivacyState.CONSENTED) return
        // 出了提示条就记录（点了或关了都算，G08）；只记加盐指纹（FU-CLIP C3）
        records.recordPromptedHash(stamp.fingerprint, stamp.hash)
        counter += 1
        val next = Ticket(counter, round, homeGen, screenGen, sessionEpoch(), api.epochs(), kind, upload, inviteCode, title, candidates)
        ticket = next
        mutablePrompt.value = ClipboardPrompt(next.id, kind, inviteCode, display = ClipboardPromptDisplay.describe(candidates))
        publish()
    }

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
            watcher.inForeground &&
            watcher.round == t.round &&
            homeGen == t.homeGen &&
            screenGen == t.screen &&
            homeRoot() &&
            overlayClear() &&
            privacy() == PrivacyState.CONSENTED &&
            sessionEpoch() == t.session &&
            api.epochs() == t.epochs

    /**
     * 提示条的按钮：商品 → 这时才上传已读到的片段；邀请（游客）→ 预填登录页，不发请求。[id] 是渲染时这条提示的编号（APP-07 共性第 18 条）：
     * 已不是当前提示（被新提示替换）的旧回调直接返回，不执行新提示；缺省（null）只给测试直接调用。
     */
    @Suppress("ReturnCount") // 归属失效、限流、邀请、没有片段逐项短路
    fun onPromptAction(id: Long? = null) {
        val t = ticket ?: return
        if (id != null && id != t.id) return
        if (!valid(t)) {
            clearPrompt()
            return
        }
        // 共享限流与共享 busy 在所有分支（邀请、商品、分享）之前（FU-CLIP R1-03）：已有商品 parse 在途（提示条或任一粘贴入口发起的）时，
        // 重组前到达的确认点击、排队的旧回调都不执行——不预填邀请码、不打开登录页、不再发 parse，不打断在途识别
        if (gate.active || mutableBusy.value) return
        if (t.kind == ClipboardClassifier.KIND_INVITE) {
            confirmInvite(t)
            return
        }
        val upload = t.upload ?: return
        val busy = takeBusy()
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
                    releaseBusy(busy)
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
            outcome.action?.let { act(it, origin) }
        }
    }

    private fun confirmInvite(t: Ticket) {
        val code = t.inviteCode ?: return
        val session =
            if (routing.session.value ==
                SessionState.GUEST
            ) {
                InviteConfirmAction.SESSION_GUEST
            } else {
                InviteConfirmAction.SESSION_PHONE
            }
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
     * 用户点粘贴入口（[entry]：home / search_typing）：这时才读剪贴板。基本模式不读；42901 限流中不读不发。结果绑定发起页
     * （[currentOrigin]），发起页离开后迟到的响应只丢弃。
     */
    fun paste(entry: String) {
        // 共享 busy（R1-05）：商品 parse 在途时不读剪贴板、不发
        if (!pasteAllowed()) return
        val origin = currentOrigin()
        val read = system.read()
        val text = (read as? ClipRead.Text)?.value
        // App 自己写入的内容（复制口令、桥 clipboard.write 等）手动粘贴同样排除（APP-07 共性第 17 条，BR-ID-16、03 §4.6）：不上传 parse、不出提示
        if (text != null && records.isSelfWritten(text)) return
        val rules = rules()
        val decision = ManualPasteAction.decide(entry, text, rules, shareDomains())
        when (decision.actionRaw) {
            ManualPasteAction.OPEN_SEARCH_TYPING -> {
                if (entry == ManualPasteAction.ENTRY_HOME) open(SEARCH_ROUTE, JsonObject(emptyMap()))
            }

            ManualPasteAction.SEARCH_PREFILL -> {
                mutableResult.value = unrecognized(origin, decision.prefill)
            }

            ManualPasteAction.PARSE -> {
                // 「」标题在请求前本地抽出、随请求绑定（整体 30132 预填，共性第 30 条）；原文只留在这个局部变量里
                val title = text?.let { ClipboardTitlePolicy.minimize(ClipboardText.quotedTitle(it), rules, shareDomains()) }
                decision.uploadText?.let { manualParse(it, origin, title) }
            }

            // 只有邀请内容：不上传 parse（A-R3-01）。游客出确认条，确认后预填登录页；已登录按 G01 不出邀请提示、不发绑定请求
            // TODO(N-30) G01：契约补 POST /v1/me/inviter 与 InviteBackfill 路由后接已登录用户的补填
            ManualPasteAction.INVITE -> {
                val code = decision.code
                if (code != null && guest()) {
                    mutableResult.value = ClipboardResult.InviteConfirm(origin, code, nextResultId())
                }
            }
        }
    }

    /**
     * 手动粘贴的邀请确认条点确认（游客）：发起页仍是当前页才预填登录页；不发请求。执行层先核对共享限流与「该结果仍是当前结果」
     * （APP-07 共性第 16 条，F-AND-22）：旧 parse 返回 42901 已启动禁用期、结果已被替换，而界面还没重组时到达的点击直接返回，
     * 不预填、不导航。
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

    /** 粘贴入口此刻能用：已同意、不在 42901 禁用期、没有商品 parse 在途（共享 busy）、没有敏感浮层。 */
    private fun pasteAllowed(): Boolean = privacy() == PrivacyState.CONSENTED && !gate.active && !mutableBusy.value && overlayClear()

    private fun manualParse(
        upload: String,
        origin: String,
        title: String?,
    ) {
        // 每次手动 parse 推进世代（R1-05）：先发请求的迟到结果不覆盖后发的结果
        manualGen += 1
        // 已有的商品提示条让位给这次手动粘贴（共享 busy 期间不能再点它）
        if (ticket?.kind == ClipboardClassifier.KIND_PRODUCT) clearPrompt()
        val busy = takeBusy()
        val gen = manualGen
        val screen = screenGen
        val session = sessionEpoch()
        val epochs = api.epochs()
        publish()
        // 网络线程的核对只读快照（A-R2-04）
        val ownedInBackground = { manualOwnedInBackground(gen, screen, origin, session, epochs) }
        // 主线程上每次等待之后的全量复核：发起页实例 + 界面世代（A-R1-02）、敏感浮层（A-R1-04）
        val stillOwned = {
            gen == manualGen &&
                screen == screenGen &&
                watcher.inForeground &&
                currentOrigin() == origin &&
                overlayClear() &&
                privacy() == PrivacyState.CONSENTED &&
                sessionEpoch() == session &&
                api.epochs() == epochs
        }
        val owner = CallOwnership(epochs, stillOwned = ownedInBackground)
        owners += owner
        parseCount += 1
        scope.launch {
            val outcome =
                try {
                    parser.parse(upload, owner, title)
                } finally {
                    releaseBusy(busy)
                }
            owners -= owner
            if (outcome.dropped || !stillOwned()) return@launch
            outcome.action?.let { act(it, origin) }
        }
    }

    /** 网络线程的手动粘贴归属核对：只读快照里的手动粘贴世代、界面世代、发起页、前台、敏感浮层；会话与隐私 / 环境世代本身线程安全。 */
    private fun manualOwnedInBackground(
        gen: Long,
        screen: Long,
        origin: String,
        session: Long,
        epochs: CallEpochs,
    ): Boolean {
        val snap = published.get()
        return snap.manual == gen &&
            snap.screen == screen &&
            snap.foreground &&
            snap.origin == origin &&
            snap.overlayClear &&
            sessionEpoch() == session &&
            api.epochs() == epochs
    }

    /**
     * 登录页邀请码行【粘贴】：只抽码；抽不到返回 null（页面轻提示「未识别到邀请码」，不清空已有输入）。用户点击，BR-ID-16 允许。
     * 42901 禁用期内执行层也拦（不读剪贴板，A-R1-06）；页面按 [blocked] 置灰并给限流提示，不误报「未识别到邀请码」。
     */
    fun pasteInviteCode(): String? {
        if (privacy() != PrivacyState.CONSENTED || gate.active) return null
        val text = (system.read() as? ClipRead.Text)?.value
        return ManualPasteAction.decide(ManualPasteAction.ENTRY_LOGIN_INVITE, text, rules()).code
    }

    // ---------------------------------------------------------------- 桥 clipboard.read（FU-CLIP C4）

    /**
     * H5 经桥 clipboard.read（L2：白名单来源、主框架、已登录、1 秒内手势由 BridgeHost 先判）：读之前再核对隐私已同意当前版本（K12：不满足不读、
     * 回 ""）；读到的原文只在这一次调用里做候选拼接（白名单链接 / 口令最多 3 个 + 最小化标题），抽不到回 ""；邀请内容、非白名单链接与其余文字
     * 都不回；不套自写排除与 24 小时去重（K13）。原文不进日志与桥事件。
     */
    fun bridgeRead(): String =
        ClipboardBridgeRead.respond(
            consentRaw = consentRaw(),
            read = { (system.read() as? ClipRead.Text)?.value },
            rules = rules(),
            shareDomains = shareDomains(),
        )

    // ---------------------------------------------------------------- 规则表变化（FU-CLIP §2.1 第 5 条）

    /** 配置快照换了：已显示但未点击的提示条、暂存的分享，若候选已不被允许就同步作废（在途 parse 不追回）。 */
    fun onRulesChanged() {
        val rules = rules()

        fun stale(candidates: List<ClipboardCandidate>) = candidates.any { !it.stillAllowed(rules) }
        if (ticket?.let { it.busyFree() && stale(it.candidates) } == true) clearPrompt()
        if (pendingShare?.let { stale(it.decision.candidates) } == true) pendingShare = null
        if (awaitingShare?.let { stale(it.decision.candidates) } == true) awaitingShare = null
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
        val decision = ShareImportAction.decideWith(shareState(), ShareImportAction.payload(mime, text, rules(), shareDomains()))
        if (decision.actionRaw !in HANDLED_SHARES) return false
        val page = if (shellReady()) pageGen else null
        pendingShare = PendingShare(shareSerial, decision, api.epochs(), sessionEpoch(), watcher.round, watcher.inForeground, page)
        processShare()
        return true
    }

    /** 分享接收 Activity 拉起 MainActivity 之后（onNewIntent）：已在前台时立即处理暂存的分享。 */
    fun onShareDelivered() = processShare()

    /** 是否有还没处理完的分享（测试与诊断用）。 */
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
     * 回前台（到达时在后台、冷启动）的交接（共性第 2 条）；再过一次闸门，经 Router 回到首页 Tab 根页，到达后出条。
     */
    @Suppress("ReturnCount") // 未就绪、归属失效、闸门逐项短路
    private fun processShare() {
        val p = pendingShare ?: return
        if (!watcher.inForeground || !shellReady()) return
        pendingShare = null
        if (!shareStillCurrent(p)) return
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
        return watcher.round == expectedRound && shareOwned(p) && samePage
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
        val p = awaitingShare ?: return
        if (!homeRoot() || !watcher.inForeground) return
        awaitingShare = null
        if (!shareOwned(p) || !overlayClear() || mutableBusy.value) return
        val decision = p.decision
        // 发布前按此刻的规则再校验全部候选（FU-CLIP 共性第 9 条）：配置收紧后已不被允许 → 丢弃（不改用当前身份重新判定）
        val rules = rules()
        if (decision.candidates.any { !it.stillAllowed(rules) }) return
        when (decision.actionRaw) {
            ShareImportAction.PROMPT -> {
                // 新分享替换未点击的提示条（剪贴板或分享，K22）与首页的结果提示；不记提示历史、不查自写（K21）
                mutableResult.value = null
                counter += 1
                val next =
                    Ticket(
                        counter,
                        watcher.round,
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
                    )
                ticket = next
                mutablePrompt.value =
                    ClipboardPrompt(next.id, ClipboardClassifier.KIND_PRODUCT, display = decision.display, share = true)
                publish()
            }

            ShareImportAction.SEARCH_PREFILL -> {
                // 非商品：本地 30132 同款提示，预填只取最小化后的「」标题（没有就留空，不拿原文首行），不上传
                clearPrompt()
                mutableResult.value = unrecognized(currentOrigin(), decision.prefill)
            }

            else -> {
                Unit
            }
        }
    }

    // ---------------------------------------------------------------- 结果

    private var serial = 0L

    @Suppress("CyclomaticComplexMethod") // parse 动作逐项
    private fun act(
        action: ParseAction,
        origin: String,
    ) {
        when (action.actionRaw) {
            ParseResultAction.OPEN_DETAIL -> {
                val card = action.card ?: return
                if (!openDetail(card)) {
                    mutableResult.value =
                        ClipboardResult.CardList(origin, listOf(ParseRow(card, null, null)), id = nextResultId())
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
                mutableResult.value = ClipboardResult.CardList(origin, action.rows, action.traceId, nextResultId())
            }

            ParseResultAction.SEARCH_PREFILL -> {
                mutableResult.value = unrecognized(origin, action.prefill)
            }

            ParseResultAction.RETRY_AFTER -> {
                block(action.disableMs ?: DEFAULT_BLOCK_MS)
                notice(origin, action.textKey, null)
            }

            ParseResultAction.NOTICE, ParseResultAction.ERROR -> {
                notice(origin, action.textKey, action.traceId.takeIf { action.showTrace6 != false })
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

    private fun notice(
        origin: String,
        key: String?,
        traceId: String?,
    ) {
        val text = textOrGeneric(key ?: GENERIC_ERROR_KEY)
        mutableResult.value = ClipboardResult.Notice(origin, ResultSignals.withTrace6(text, traceId), nextResultId())
    }

    private fun unrecognized(
        origin: String,
        prefill: String?,
    ) = ClipboardResult.Unrecognized(origin, ClipboardDraftTexts.text(UNRECOGNIZED_KEY), prefill, nextResultId())

    /** 每次发布结果的新编号（APP-07 共性第 26 条）。 */
    private fun nextResultId(): Long {
        serial += 1
        return serial
    }

    /** 结果提示被关掉或显示完：只消费渲染时的那一条（按编号认，APP-07 共性第 18、26 条：旧关闭回调不清掉内容相同的新结果）。 */
    fun consumeResult(result: ClipboardResult) {
        if (current(result)) mutableResult.value = null
    }

    /** [result] 仍是当前结果（渲染时绑定的那一条，按发布编号核对；旧回调不读最新状态去执行，APP-07 共性第 16、18、26 条）。 */
    private fun current(result: ClipboardResult): Boolean = mutableResult.value?.id == result.id

    /**
     * 结果按钮（打开卡片、用商品名搜索、邀请确认）在执行层的统一核对（APP-07 共性第 16 条，F1-04）：共享限流（42901 禁用期）中不执行，
     * 且必须仍是当前结果（F1-05）。界面同步置灰（[blocked]）。
     */
    private fun actionable(result: ClipboardResult): Boolean = !gate.active && !mutableBusy.value && current(result)

    /** 30132 的【用商品名搜索】：[fill] 是 SearchTyping 本页的搜索框；没有时经 Router 打开 Search（q 预填，提取不到就留空）。 */
    fun searchByName(result: ClipboardResult.Unrecognized) {
        if (!actionable(result)) return
        consumeResult(result)
        searchByName(result.origin, result.prefill)
    }

    /** 多卡弹层里行内 30132 的【用商品名搜索】（A-R1-05）：预填取这一行命中的「」内或首行（与单条 30132 同一规则）。 */
    fun searchByName(
        result: ClipboardResult.CardList,
        row: ParseRow,
    ) {
        if (!actionable(result) || result.rows.none { it === row }) return
        consumeResult(result)
        searchByName(result.origin, ClipboardText.searchPrefill(row.hitRaw))
    }

    /**
     * 多卡弹层里一条错误行的文案（A-R1-05，按码）：30132 → error.30132（另给【用商品名搜索】）；42901 → error.42901（禁用期已启动）；
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

    /**
     * 文案键的正文；键不存在或正文为空（未收录的 5xxxx 等）回落到 error.50001 的通用正文（A-R2-01，规划 03 §4.2、BR-TEXT-14）。
     * 整体失败（[notice]）与多结果单条 error_code（[rowText]）共用，trace6 由调用方统一附加。
     */
    private fun textOrGeneric(key: String): String = ClipboardDraftTexts.text(key).ifEmpty { ClipboardDraftTexts.text(GENERIC_ERROR_KEY) }

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

    /** 多卡弹层点一张卡：以这张卡为种子打开详情。 */
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

    /** 设置页开关（设备级，存本机；登录、退出、换账号都不改它）。 */
    fun setAutoSwitch(enabled: Boolean) {
        prefs.autoDetect = enabled
        mutableAutoSwitch.value = enabled
    }

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)

    companion object {
        const val CONFIG_FLAG = "clipboard.enabled.android"

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

        /** 通用错误正文（未收录的 5xxxx、网络失败）。 */
        private const val GENERIC_ERROR_KEY = ParseInputService.NETWORK_TEXT_KEY
        private const val DEFAULT_BLOCK_MS = 5_000L
        private const val SERVER_ERROR_MIN = 50_000

        /** routes.json Search.q 的 maxLength。 */
        private const val SEARCH_Q_MAX = 100
    }
}

/**
 * 平台购买状态的接线（AppModule 与集成测试共用，APP-07 共性第 32 条）：取值与搜索卡同一来源（[AppTrade.platformStatus]：50301 覆盖优先，
 * 其次生效配置的 features.platform_status）；配置快照或 50301 覆盖变化时通知多卡弹层重新映射。没有接购买时只看配置。
 */
fun AppClipboard.connectPurchaseStatus(
    trade: AppTrade?,
    store: ConfigStore?,
) {
    purchaseStatus = { platform -> trade?.platformStatus(platform) ?: AppTrade.platformStatusIn(store, platform) }
    trade?.subscribeStatus(::onPurchaseStatusChanged)
    if (store != null) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            store.snapshots.drop(1).collect {
                onPurchaseStatusChanged()
                // 规则表（features.flags 的电商平台开关）可能变了：已显示的提示条若候选不再被允许就作废（FU-CLIP §2.1 第 5 条）
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
    privacy.onConclusionChanged { onPrivacyChanged(it) }
    if (privacy.conclusion != PrivacyState.CONSENTED) clearRecords()
    // 切换环境：同步作废（在任何等待之前）
    cleaners?.register { _, _ -> onEnvironmentSwitched() }
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
        routing.session.drop(1).collect { onSessionChanged() }
    }
}

/** 规则表的接线（AppModule）：配置快照每换一次，复核已显示的提示条与暂存分享的候选（FU-CLIP §2.1 第 5 条）。 */
fun AppClipboard.connectRules(store: ConfigStore) {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
        store.snapshots.drop(1).collect { onRulesChanged() }
    }
}
