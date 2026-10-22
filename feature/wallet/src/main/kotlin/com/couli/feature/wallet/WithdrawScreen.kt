@file:Suppress("MatchingDeclarationName") // ViewModel 与页面放在一起

package com.couli.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.network.IdentityVerdict
import com.couli.core.network.SessionIdentity
import com.couli.core.wallet.EstimateLine
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WalletStaleCache
import com.couli.core.wallet.WalletTexts
import com.couli.core.wallet.WithdrawBlockGuide
import com.couli.core.wallet.WithdrawRulesDisplay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 提现页的 ViewModel：配置重建与进程回收后用 SavedStateHandle 恢复已输入的金额，并绑定账号归属（账号哈希对得上才恢复，APP-08 第 19、23 条）；
 * 已有成功或在途标记的同一表单只恢复提交锁，不回填金额；不恢复估算结果。身份证号、卡号、登录号不进 SavedStateHandle（本页也没有）。
 */
class WithdrawViewModel(
    config: WalletPageConfig,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val resume = ResumeTracker()

    private val deps = config.deps
    val controller: WithdrawController

    /**
     * 金额草稿归属的完整身份（账号 + 本进程登录世代 + 持久化登录世代；末轮窄修 R3-02：按 [SessionIdentity.verdictFor] 跟踪，含分区）。构造、接管、换代时
     * 都取自同一份快照。
     */
    private var draftIdentity: SessionIdentity

    /**
     * 草稿的恢复决策已做（末轮窄修 R3-02 / R3-03）：没做之前（身份或分区未知、或构造时的安全复核读不出）草稿不读不写，保存的金额原样留着，等已确认的
     * 快照再决策；用户在此期间填的金额保留，决策时不被旧草稿覆盖。
     */
    private var draftDecided = false

    /**
     * 草稿决策还没做时用户已经操作过金额（输入、清空、【全部提现】、快捷档位；末轮窄修二 RC-01）：之后的决策保留用户此刻的值（含空），不回填旧草稿。
     * 不以金额是否为空判断。
     */
    private var userEdited = false

    init {
        // 表单身份在构造时一次性取得（APP-09 共性第 65 条，Android RC-06）：恢复的金额按这份身份校验，并与它成对交给控制器与 SensitiveFlow；
        // F-X-17（FU-AND-0717）：金额草稿按「账号 + 持久化登录世代」绑定，世代与账号取自同一份身份快照（F-X-19 补充二）
        val identity = deps.env.identitySnapshot()
        val owner = FormOwner.of(identity)
        draftIdentity = identity
        val restored = initialDraft(identity, owner)
        controller =
            WithdrawController(
                deps,
                viewModelScope,
                restored,
                config.entryId,
                owner,
                initialSubmissionLocked = submissionLocked,
                initialUnknownKey = saved.get<String>(K_UNKNOWN),
            )
        // 守卫的数据来源（FU-AND-0721 不变式）：每次判定都从 Handle 现读三个保存键，不用内存镜像
        controller.readPersistedKeys = {
            val marker = saved.get<Any>(K_IN_FLIGHT)
            PersistedKeys(
                succeeded = saved.get<Boolean>(K_SUCCEEDED) == true,
                inFlight = marker != null && marker != false,
                inFlightKey = marker as? String,
                unknownKey = saved.get<String>(K_UNKNOWN),
            )
        }
        // 首个未释放的键随 Handle 保存；其他键的写不能覆盖它，也不能按恢复时的卡片反推。
        // 旧版本的 Boolean 标记没有设锁键，保守保留，直至表单换代清理。
        controller.onSubmissionStarted = { key ->
            if (saved.get<Any>(K_IN_FLIGHT) == null) saved[K_IN_FLIGHT] = key
        }
        controller.onSubmissionReleased = { key ->
            if (saved.get<Any>(K_IN_FLIGHT) == key) saved.remove<Any>(K_IN_FLIGHT)
        }
        // 本页结果未知的键（含只是观察到的待确认记录，review-r3 S0）：观察到即存，跟随这一张表单的 Handle；不是账号级标记
        controller.onUnknownSaved = { key ->
            if (key == null) saved.remove<String>(K_UNKNOWN) else saved[K_UNKNOWN] = key
        }
        controller.onSuccessRecorded = { data ->
            saved[K_SUCCEEDED] = true
            saved[K_WITHDRAWAL_ID] = (data?.get(WalletRoutes.WITHDRAWAL_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content
            clearDraft()
        }
        config.hooks.bindLeave(controller::leave)
        // 确认换代时同步清旧表单的草稿和保存标记；同一身份的通知不会调用 onReset。
        controller.onReset = { next ->
            clearDraft()
            saved.remove<Any>(K_IN_FLIGHT)
            saved.remove<String>(K_UNKNOWN)
            saved.remove<Boolean>(K_SUCCEEDED)
            saved.remove<String>(K_WITHDRAWAL_ID)
            draftIdentity = next
            draftDecided = draftReady(next)
            userEdited = false
        }
        controller.onAdopted = ::resolveDraft
        // 旧接线（没有统一入口的 App）：取一份快照按换代处理
        config.hooks.bindSession { controller.onIdentity(deps.env.identitySnapshot(), changed = true) }
        // 统一的页面恢复入口（R2-04）：会话换代按 App 那一份快照重建（同账号只换持久化世代时 App 的对账按分区标签判换代，同样走这里）；未知期建的表单第一次
        // 确认接管并补取；判据相同（含分区第一次确认、FormOwner 不看分区而判为同一身份的情形）时按含分区的判定补做草稿决策（末轮窄修 R3-02 / R3-03）
        config.hooks.bindIdentity { id, changed ->
            controller.onIdentity(id, changed)
            if (!changed) resolveDraft(id)
        }
        controller.start()
    }

    /** 草稿此刻能按这份快照绑定：已知、有账号，分区给得出（或快照的分区没接来源，旧口径按账号）。 */
    private fun draftReady(identity: SessionIdentity): Boolean =
        identity.known && identity.account != null && (identity.partition != null || !deps.env.partitionTracked())

    /**
     * 构造时的草稿决策（同一份快照 [identity]）：分区未知 / 身份未知延后；有属于这份身份的草稿时再做一次「仍是当前会话」的安全复核（RC-06）——
     * 确认是同一份才恢复，确认已换代不恢复，读不出（未知）保留待恢复状态、之后确认再决策（末轮窄修 R3-03）。
     */
    @Suppress("ReturnCount") // 延后、没有草稿、复核结论三个出口
    private fun initialDraft(
        identity: SessionIdentity,
        owner: FormOwner,
    ): String {
        if (submissionLocked) {
            draftDecided = true
            return ""
        }
        if (!draftReady(identity)) return ""
        val draft = saved.get<String>(K_AMOUNT)?.takeIf { saved.get<String>(K_OWNER) == draftHash(identity) }
        if (draft == null) {
            draftDecided = true
            return ""
        }
        return when (owner.verdict(deps.env.identitySnapshot())) {
            IdentityVerdict.SAME -> draft.also { draftDecided = true }
            IdentityVerdict.UNKNOWN -> ""
            else -> "".also { draftDecided = true }
        }
    }

    /**
     * 用一份已确认的快照补做草稿决策（未知期建的表单接管、分区第一次确认、构造时安全复核读不出之后的确认；末轮窄修 R3-02 / R3-03）：草稿归属换成这份快照；
     * 还没决策过而本机存着属于这份身份的金额、用户也还没填时按它恢复（经正常输入入口，估算照常）；否则把用户已填的金额按新归属保存。
     */
    @Suppress("ReturnCount") // 不是本表单的确认、确认换代、分区仍未知三个出口
    private fun resolveDraft(identity: SessionIdentity) {
        if (controller.state.value.successPending) return
        if (!identity.known || identity.account == null || FormOwner.of(identity) != controller.flow.owner) return
        if (identity.verdictFor(draftIdentity) == IdentityVerdict.CHANGED) return
        draftIdentity = identity
        if (!draftReady(identity)) return
        if (draftDecided) return persist()
        val draft = saved.get<String>(K_AMOUNT)?.takeIf { saved.get<String>(K_OWNER) == draftHash(identity) }
        if (draft == null || userEdited) {
            // 没有属于这份身份的草稿，或用户已操作过金额（含清空，RC-01）：决策完成，按新归属保存用户此刻的值
            draftDecided = true
            persist()
            return
        }
        // 恢复经正常输入入口（其中还有一次表单归属复核）：真正受理了才结束待决（末轮窄修二 RC-02）；复核读不出时草稿原样保留，下一次确认再决策
        val owner = controller.flow.owner
        if (controller.onAmountChange(draft, owner)) {
            draftDecided = true
            persist(owner)
        }
    }

    /**
     * 金额输入与自动填充（APP-09 共性第 59 条，Android RC-01）：[owner] 是渲染这张表单时的身份；控制器不受理的旧回调（会话已换）也不保存。
     */
    fun onAmountChange(
        text: String,
        owner: FormOwner = controller.flow.owner,
    ) {
        if (!controller.onAmountChange(text, owner)) return
        if (!draftDecided) userEdited = true
        persist(owner)
    }

    /** 【全部提现】（用户操作；草稿待决期间记为已操作金额，RC-01；保存口径不变）。 */
    fun fillAll(
        version: Long,
        owner: FormOwner,
    ) {
        // 控制器实际受理了才记用户已操作（末轮窄修三 RC-03：被拒的旧回调 / 身份复核读不出不算）
        if (controller.fillAll(version, owner) && !draftDecided) userEdited = true
    }

    /** 快捷档位（同 [fillAll]）。 */
    fun fillQuick(
        version: Long,
        fen: Long,
        owner: FormOwner,
    ) {
        if (controller.fillQuick(version, fen, owner) && !draftDecided) userEdited = true
    }

    /**
     * 金额的本机保存（SavedStateHandle）：按渲染时固定的表单身份 [owner] 保存，归属标签取草稿的完整身份（账号 + 持久化世代，不在写入时读当前世代）；写入前
     * 核对它仍是本表单的身份、且仍是当前会话。草稿决策还没做（身份 / 分区未知）时不写（末轮窄修 R3-02：不按账号回退）。
     */
    fun persist(owner: FormOwner = controller.flow.owner) {
        val target = draftIdentity
        val bound = !controller.state.value.successPending && draftDecided && draftReady(target)
        if (!bound || owner != controller.flow.owner || owner != FormOwner.of(target)) return
        if (!owner.current(deps.env)) return
        saved[K_AMOUNT] = controller.state.value.amountText
        saved[K_OWNER] = draftHash(target)
    }

    /** 标记跟随这一张表单的 SavedStateHandle；离开后新开的提现页使用新的 Handle。 */
    private val submissionLocked: Boolean get() = saved.get<Boolean>(K_SUCCEEDED) == true || saved.get<Any>(K_IN_FLIGHT) != null

    private fun clearDraft() {
        saved.remove<String>(K_AMOUNT)
        saved.remove<String>(K_OWNER)
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }

    /** 草稿归属标签：账号 + 持久化登录世代（分区没接来源时按账号，旧口径；接了来源而分区未知时根本不读写，见 [draftReady]）。 */
    private fun draftHash(identity: SessionIdentity): String {
        val account = identity.account.orEmpty()
        val partition = identity.partition
        return WalletStaleCache.Store.hash(if (partition == null) account else account + "\u0000login_epoch:" + partition)
    }

    private companion object {
        const val K_AMOUNT = "withdraw.amount"
        const val K_OWNER = "withdraw.owner"
        const val K_SUCCEEDED = "withdraw.succeeded"
        const val K_WITHDRAWAL_ID = "withdraw.withdrawal_id"
        const val K_IN_FLIGHT = "withdraw.in_flight"
        const val K_UNKNOWN = "withdraw.unknown_key"
    }
}

/**
 * 提现申请（Withdraw 路由，不接受参数；设计稿 Withdraw、WithdrawRealName、WithdrawCalculating、WithdrawEstimateFailed、WithdrawNoteNet、WithdrawStepUp、
 * WithdrawSubmitting、WithdrawBlocked、WithdrawPending* / Abandon*；AdaptLargeTextWithdraw、AdaptNarrowWithdraw、AdaptKeyboardWithdraw、
 * A11yFocusWithdraw）：
 * 待确认卡 → 错误条 → 页头（可提现余额、今日 / 本月还可提）→ 金额框（¥、【全部提现】、上限行、快捷档位、估算行）→ block 引导 → 收款账号行 → 时效与
 * 「不能自行撤销」→ 底部【提交】（随键盘上移）与【提现规则】。【提交】排在内容之后。
 */
@Suppress("CyclomaticComplexMethod") // 页面、面板、弹窗按状态逐项组合
@Composable
fun WithdrawScreen(config: WalletPageConfig) {
    val model: WithdrawViewModel = viewModel { WithdrawViewModel(config, createSavedStateHandle()) }
    val controller = model.controller
    // 渲染时取出的普通快照（APP-09 共性第 63 条）：回调只捕获它们与其中的版本、表单身份，回调执行时不读委托 State
    val state = controller.state.collectAsStateWithLifecycle().value
    val flowState =
        controller.flow.state
            .collectAsStateWithLifecycle()
            .value
    val stepUp =
        controller.flow.stepUp.state
            .collectAsStateWithLifecycle()
            .value
    val submitBlocked =
        config.deps.gates.withdraw.blocked
            .collectAsStateWithLifecycle()
            .value
    val readBlocked =
        config.deps.gates.read.blocked
            .collectAsStateWithLifecycle()
            .value
    val smsBlocked =
        config.deps.gates.sms.blocked
            .collectAsStateWithLifecycle()
            .value
    val verifyBlocked =
        config.deps.gates.verify.blocked
            .collectAsStateWithLifecycle()
            .value
    val texts = config.deps.texts
    val submitFocus = remember { FocusRequester() }
    val helpOrigin = remember { FocusRequester() }
    var help by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(controller) {
        controller.effects.collectLatest { event ->
            when (event) {
                WithdrawEvent.HideKeyboard -> {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }

                is WithdrawEvent.Announce -> {
                    val vars = event.vars.mapValues { CouliFormat.moneySpoken(it.value) }
                    announcement = texts.format(event.key, vars) to System.nanoTime()
                }
            }
        }
    }
    announcement?.let { (text, id) -> Announce(text, id) }
    OnReturn(model.resume) { controller.onResume() }
    OnVisibility(controller::onVisibility)
    WalletPage(
        tag = WalletTags.WITHDRAW_PAGE,
        title = texts.text("withdraw.title"),
        texts = texts,
        onBack = config.onBack,
        overlay = {
            flowState.toast?.let { toast ->
                // W62：「已经处理完成」用次文字色信息图标，已放弃等仍是成功对勾；位置不变（G-W18）
                WalletToast(toast.message(texts), toast.id, error = false, info = toast.isCompletedNotice()) {
                    controller.flow.consumeToast(toast.id)
                }
            }
        },
    ) {
        if (state.successPending) {
            // 成功锁卡与内容区同一套页边距（上 8、左右 16），不贴屏幕两侧
            WalletCard(modifier = Modifier.padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2)) {
                Text(
                    text = texts.text(if (state.navigationRejected) "withdraw.submitted_opening_records" else "withdraw.submission_locked"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textSecondary,
                )
                CouliButton(
                    text = texts.text("pending_confirm.withdraw.records"),
                    onClick = controller::openRecords,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                )
            }
        }
        when (state.phase) {
            WithdrawSnapshot.CONTENT -> {
                val display = state.display()
                if (display != null) {
                    WithdrawContent(model, state, flowState, display, texts, submitBlocked, submitFocus, Modifier.weight(1f)) {
                        help = true
                    }
                }
            }

            WithdrawSnapshot.ERROR -> {
                Box(
                    modifier = Modifier.padding(CouliSpacing.space4),
                ) { WithdrawPendingSection(controller, state, flowState, texts, submitBlocked) }
                Box(modifier = Modifier.fillMaxWidth().testTag(WalletTags.WITHDRAW_LOAD_ERROR)) {
                    ErrorState(
                        title = texts.text("withdraw.load_failed"),
                        message = state.loadError?.message(texts),
                        retry = StateAction(texts.text("wallet.retry"), enabled = !readBlocked) { controller.retry() },
                    )
                }
            }

            else -> {
                Column(modifier = Modifier.padding(CouliSpacing.space4), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    WithdrawPendingSection(controller, state, flowState, texts, submitBlocked)
                    Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space8))
                    Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space12))
                }
            }
        }
    }
    // W45：正文维持 wallet.help.fee_tax（G-W27），按 WithdrawNoteNet 给 14/21 正文与描边【知道了】（共用默认不变）
    if (help) {
        HelpSheet(texts, "fee_tax", helpOrigin, bodyStyle = CouliTypography.footnote, okVariant = CouliButtonVariant.SECONDARY) {
            help = false
        }
    }
    stepUp?.let { StepUpSheet(controller.flow.stepUp, it, texts, smsBlocked, verifyBlocked, submitFocus) }
    flowState.riskDialog?.let { dialog ->
        RiskBlockedDialog(
            texts,
            dialog,
            "withdraw.risk_blocked.desc",
            submitFocus,
            onOk = controller.flow::dismissRisk,
            onAppeal = controller::appeal,
        )
    }
    val abandoning = flowState.pending
    if (flowState.abandonConfirm && abandoning != null) {
        val pending = abandoning
        val key = pending.key
        // 回调带渲染时的表单身份（共性第 52 条）
        val owner = flowState.owner
        AbandonDialog(
            texts = texts,
            title = texts.text("pending_confirm.withdraw.abandon"),
            details =
                listOfNotNull(
                    pendingAmount(pending)?.let { texts.text("pending_confirm.withdraw.amount_label") to CouliFormat.money(it) },
                    state.payout?.line(texts)?.let { texts.text("pending_confirm.payout_label") to it },
                ),
            extra = null,
            onOk = { controller.flow.abandon(key, owner) },
            onCancel = controller.flow::cancelAbandon,
        )
    }
}

/**
 * 待确认卡（APP-09 共性第 2 条）：独立于 rules / 收款账号的读取状态显示——rules 还在加载或失败时也显示，确认与放弃按各自闸门与在途状态可用；
 * 按钮回调在构建时捕获未决键（共性第 4 条）。
 */
@Composable
private fun WithdrawPendingSection(
    controller: WithdrawController,
    state: WithdrawSnapshot,
    flow: FlowState,
    texts: WalletTexts,
    submitBlocked: Boolean,
) {
    val pending = flow.pending ?: return
    val key = pending.key
    // 回调捕获渲染时的表单身份（共性第 52、63 条）
    val owner = flow.owner
    val amount = pendingAmount(pending)
    val account = state.payout?.line(texts)
    val desc =
        texts.format(
            "pending_confirm.withdraw.desc",
            mapOf("amount" to amount?.let(CouliFormat::money).orEmpty(), "payout_account" to account.orEmpty()),
        )
    PendingCard(
        texts = texts,
        desc = desc,
        abandonLabel = texts.text("pending_confirm.withdraw.abandon"),
        confirmLabel = texts.text("pending_confirm.withdraw.action"),
        flow = flow,
        blocked = submitBlocked,
        onAbandon = { controller.flow.askAbandon(key, owner) },
        onConfirm = { controller.flow.confirm(key, owner) },
        note = texts.text("pending_confirm.withdraw.note"),
        link = texts.text("pending_confirm.withdraw.records") to controller::openRecords,
    )
}

/** 未决记录里的上一笔金额（摘要 {"amount_fen":…}）。 */
private fun pendingAmount(pending: PendingView?): Long? =
    pending?.summary?.let {
        runCatching {
            ((Json.parseToJsonElement(it) as JsonObject)[WithdrawController.AMOUNT_FEN] as JsonPrimitive).content.toLong()
        }.getOrNull()
    }

/**
 * 内容区（W36–W55）：上 8、左右 16、下 0、块间 12。待确认卡 → 页顶错误条（金额类提交错误改在金额框下，G-W13）→ 余额卡 → 金额卡（金额框、上限、档位、
 * 分隔线 + 估算、block 引导）→ 不能提现时隐藏的三块（收款账号行、时效条、不可撤销句，G-W14）。底栏无底色。
 * 【提交】的回调与可用条件一字不改（G-W12 不做）；所有回调仍在这里按渲染时的版本 / 表单身份捕获。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // 页面各区块的显示条件逐项
@Composable
private fun WithdrawContent(
    model: WithdrawViewModel,
    state: WithdrawSnapshot,
    flow: FlowState,
    display: WithdrawRulesDisplay,
    texts: WalletTexts,
    submitBlocked: Boolean,
    submitFocus: FocusRequester,
    modifier: Modifier,
    onHelp: () -> Unit,
) {
    val controller = model.controller
    val version = state.version
    // 渲染这张表单时的身份（共性第 52、63 条）：【提交】回调捕获它
    val owner = flow.owner
    val guide = state.guide()
    val pending = flow.pending != null || flow.pendingUnreadable
    val editable = display.amountEditable && !pending && flow.phase == FlowState.EDITING
    // G-W13：只读错误键决定显示位置；金额类业务错误挪到金额框下，页顶不再重复
    val amountError = flow.error?.takeIf { isWithdrawAmountError(it.key) }
    // G-W14：rules 明确不能提现时隐藏收款账号行、时效条、不可撤销句（不读 submitEnabled，空金额时照常显示）
    val canWithdraw = state.rules?.canWithdraw != false
    // G-W15：30309 且下发了提现规则文章时，引导框里加【查看提现规则】，去向同页底链接
    val guideRules = controller::openRules.takeIf { guide.textKey == GUIDE_RULES_TEXT_KEY && state.helpTarget != null }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        WithdrawPendingSection(controller, state, flow, texts, submitBlocked)
        flow.error?.takeIf { amountError == null }?.let { ErrorBar(it, texts, WalletTags.WITHDRAW_ERROR) }
        flow.guideRoute?.let { route -> GuideEntry(route, texts, WalletTags.FLOW_GUIDE) { controller.flow.openGuide() } }
        (state.readError ?: state.payoutError)?.let {
            val readBlocked = controller.readBlocked.collectAsStateWithLifecycle().value
            RetryErrorBar(it, texts, WalletTags.WITHDRAW_READ_ERROR, retryEnabled = !readBlocked) { controller.retry() }
        }
        // W37：标签 14 次文字 → 34/700 等宽大数（间距 4）→ 次数 12（间距 8）；标签与金额合成一个读屏元素（WITHDRAW_BALANCE）
        Column(
            modifier = Modifier.fillMaxWidth().withdrawCard().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(WalletTags.WITHDRAW_BALANCE),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            ) {
                Text(text = texts.text("wallet.withdrawable"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                Text(text = CouliFormat.money(display.balanceFen), style = CouliTextStyles.amountDisplay, color = CouliColors.textPrimary)
            }
            Text(
                text =
                    texts.format(
                        "withdraw.remaining_counts",
                        mapOf(
                            "daily" to display.dailyRemaining.toString(),
                            "monthly" to display.monthlyRemaining.toString(),
                        ),
                    ),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(WalletTags.WITHDRAW_REMAINING),
            )
        }
        WalletCard {
            AmountField(model, state, display, texts, editable, flow, amountError)
            EstimateRow(state, texts, onHelp)
            // W46：引导框进金额卡、在金额框（与估算）之下；按钮仍是用户点了才打开（openGuide 捕获渲染时版本）
            if (guide.actionRaw != WithdrawBlockGuide.NONE) GuideBox(guide, texts, guideRules) { controller.openGuide(version) }
        }
        if (canWithdraw) {
            // W51：白卡、左「收款账号」右脱敏账号 + 20 箭头；读屏与 openPayout 不变
            state.payout?.line(texts)?.let { line ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = CouliSizes.touchTarget)
                            .withdrawClickableCard()
                            .clickable(role = Role.Button, onClick = controller::openPayout)
                            .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                            .semantics(mergeDescendants = true) {
                                contentDescription =
                                    texts.text("withdraw.payout_label") + PriceTerms.SPOKEN_PAUSE + spokenMask(texts, line)
                            }.testTag(WalletTags.WITHDRAW_PAYOUT),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                ) {
                    Text(text = texts.text("withdraw.payout_label"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                    Text(text = line, style = WITHDRAW_PAYOUT_LINE_STYLE, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
                    Icon(
                        CouliIcons.chevron,
                        contentDescription = null,
                        tint = CouliColors.textSecondary,
                        modifier = Modifier.size(CouliSizes.iconMedium),
                    )
                }
            }
            // W52：时效 INFO 条 + 不可撤销句 12 次文字缩进 4
            SlaNote(display.slaText, WalletTags.WITHDRAW_SLA)
            Text(
                text = texts.text("withdraw.irrevocable"),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.padding(start = CouliSpacing.space1),
            )
        }
    }
    // 底部主操作：随键盘上移（AdaptKeyboardWithdraw）；读屏顺序排在内容之后。W53 / W55：无底色（透出页面画布色）
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val submitting = flow.phase == FlowState.SUBMITTING
        CouliButton(
            text = texts.text(if (submitting) "withdraw.submitting" else "withdraw.submit"),
            onClick = { controller.submit(version, owner) }.tapped(WalletTags.WITHDRAW_SUBMIT),
            enabled = display.submitEnabled && flow.canSubmit && !submitBlocked && !state.serverBlocked && !state.successPending,
            loading = submitting,
            loadingDescription = texts.text("withdraw.submitting"),
            modifier = Modifier.fillMaxWidth().couliFocusTarget(submitFocus).testTag(WalletTags.WITHDRAW_SUBMIT),
        )
        if (state.helpTarget != null) {
            // r1 S2-2：视觉 21、命中 48 分离（页面私有件，不改共用按钮 48 最小高）
            WithdrawRulesLink(texts.text("withdraw.rules_link"), WalletTags.WITHDRAW_RULES_LINK, controller::openRules)
        }
    }
}

/** 脱敏号读出「星号」（adaptation-a11y-spec §9）。 */
internal fun spokenMask(
    texts: WalletTexts,
    text: String,
): String = texts.text(STAR_SPOKEN_KEY).takeIf { it.isNotEmpty() }?.let { text.replace("*", it) } ?: text

internal const val STAR_SPOKEN_KEY = "wallet.star_spoken"

/**
 * 金额区（W38–W41、W49、W50、W54）：金额框走 [WalletField] 的金额样式（视觉 56、「¥」17 次文字、输入 28/600 等宽、禁用浅边 + 灰底），右侧【全部提现】
 * 14/500；上限行 12 次文字等宽；快捷档位为胶囊（[QuickChip]）。输入、【全部提现】、档位回调仍捕获渲染时的版本与表单身份并 `.tapped` 登记。
 * [placed] 是挪到金额框下显示的金额类提交错误（G-W13）：沿用字段错误路径（框变红、提示紧邻框下、移焦点并播报一次），20001 字段错误优先。
 */
@Suppress("LongParameterList")
@Composable
private fun AmountField(
    model: WithdrawViewModel,
    state: WithdrawSnapshot,
    display: WithdrawRulesDisplay,
    texts: WalletTexts,
    editable: Boolean,
    flow: FlowState,
    placed: Notice?,
) {
    val controller = model.controller
    val version = state.version
    // 渲染这张表单时的身份（共性第 59、63、64 条）：输入、【全部提现】、快捷档位回调都捕获它，写金额前由控制器核对
    val owner = flow.owner
    val fieldError =
        flow.fieldErrorKey?.takeIf { WithdrawController.AMOUNT_FEN in flow.fieldErrors || flow.fieldErrors.isEmpty() }?.let(texts::text)
    val placedError = placed?.takeIf { fieldError == null }
    WalletField(
        label = texts.text("withdraw.amount_label"),
        value = state.amountText,
        onValueChange = { model.onAmountChange(it, owner) },
        tag = WalletTags.WITHDRAW_INPUT,
        placeholder = if (!display.amountEditable) texts.text("withdraw.realname_placeholder") else null,
        error = fieldError ?: placedError?.message(texts),
        enabled = editable,
        keyboardType = KeyboardType.Decimal,
        prefix = PriceTerms.YUAN_SIGN,
        errorEvent = if (fieldError != null) flow.fieldErrorId else placedError?.id,
        errorTag = WalletTags.WITHDRAW_FIELD_ERROR,
        amount = true,
    ) {
        CouliButton(
            text = texts.text("withdraw.all"),
            onClick = { model.fillAll(version, owner) }.tapped(WalletTags.WITHDRAW_ALL),
            enabled = display.allEnabled && editable,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            textStyle = CouliTextStyles.footnoteMedium,
            modifier = Modifier.testTag(WalletTags.WITHDRAW_ALL),
        )
    }
    display.maxLineFen?.let { fen ->
        Text(
            text = texts.format("withdraw.max_line", mapOf("amount" to CouliFormat.money(fen))),
            style = CouliTextStyles.captionAmount,
            color = CouliColors.textSecondary,
            modifier = Modifier.testTag(WalletTags.WITHDRAW_MAX),
        )
    }
    if (display.quick.isNotEmpty()) {
        // 行距 16：胶囊视觉 32、命中区上下各扩 8，换行后相邻两行的命中区不重叠
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
        ) {
            display.quick.forEach { fen ->
                QuickChip(
                    text = CouliFormat.money(fen),
                    label = texts.format("withdraw.spoken.amount", mapOf("amount" to CouliFormat.moneySpoken(fen))),
                    enabled = editable,
                    tag = WalletTags.WITHDRAW_QUICK_PREFIX + fen,
                    onClick = { model.fillQuick(version, fen, owner) }.tapped(WalletTags.WITHDRAW_QUICK_PREFIX + fen),
                )
            }
        }
    }
}

/**
 * 估算行（W42–W44）：1 分隔线 + 估算，估算隐藏时分隔线一起隐藏。「计算中…」带 16 转圈、不播报（G-W20）；结果只读服务端 est_*，仍是一个 polite 文字节点，
 * 只把到账金额加粗到 17/600；问号 14、命中 48（不撑高行），打开「手续费 / 税费」说明（wallet.help.fee_tax）。
 */
@Composable
private fun EstimateRow(
    state: WithdrawSnapshot,
    texts: WalletTexts,
    onHelp: () -> Unit,
) {
    when (val estimate = state.estimate) {
        EstimateUi.Hidden -> {
            Unit
        }

        EstimateUi.Calculating -> {
            EstimateDivider()
            CalculatingRow(texts.text("withdraw.estimate.calculating"), WalletTags.WITHDRAW_CALCULATING)
        }

        is EstimateUi.Shown -> {
            val (text, net) =
                when (val line = estimate.line) {
                    is EstimateLine.NetOnly -> {
                        val net = CouliFormat.money(line.netFen)
                        texts.format(line.key, mapOf("net" to net)) to net
                    }

                    is EstimateLine.Full -> {
                        val net = CouliFormat.money(line.netFen)
                        texts.format(
                            line.key,
                            mapOf(
                                "net" to net,
                                "fee" to CouliFormat.money(line.feeFen),
                                "tax" to CouliFormat.money(line.taxFen),
                            ),
                        ) to net
                    }

                    EstimateLine.Hidden -> {
                        "" to null
                    }
                }
            EstimateDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                Text(
                    text = estimateText(text, net),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                    modifier =
                        Modifier
                            .weight(
                                1f,
                                fill = false,
                            ).semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(WalletTags.WITHDRAW_ESTIMATE),
                )
                CouliIconButton(
                    icon = CouliIcons.help,
                    label = texts.text("wallet.help.fee_tax_label"),
                    onClick = onHelp,
                    tint = CouliColors.textSecondary,
                    iconSize = CouliSizes.iconTiny,
                    modifier = Modifier.inlineHelpSlot(),
                )
            }
        }
    }
}

/**
 * block_code 引导（W46–W48；G03、G28：其余 block_code 套用 WithdrawRealName 的版式）：在金额卡内，警告底 + 20 图标 + 14/500 警告字色；按钮在文字下方、描边
 * 14/500（用户点了才打开）。纯提示码无按钮；30309 有 [rules] 时加【查看提现规则】。
 */
@Composable
private fun GuideBox(
    guide: WithdrawBlockGuide.View,
    texts: WalletTexts,
    rules: (() -> Unit)?,
    onGuide: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .testTag(WalletTags.WITHDRAW_GUIDE),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Icon(
                CouliIcons.alert,
                contentDescription = null,
                tint = CouliColors.statusWarningText,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
            Text(
                text = texts.text(guide.textKey),
                style = CouliTextStyles.footnoteMedium,
                color = CouliColors.statusWarningText,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        guide.buttonKey?.let { key -> GuideButton(texts.text(key), WalletTags.WITHDRAW_GUIDE_BUTTON, onGuide) }
        if (rules != null) GuideButton(texts.text(GUIDE_RULES_LABEL_KEY), WITHDRAW_GUIDE_RULES_TAG, rules)
    }
}
