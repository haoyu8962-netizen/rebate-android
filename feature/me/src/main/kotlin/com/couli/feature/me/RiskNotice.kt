package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.me.AppealView
import com.couli.core.me.MeJson
import com.couli.core.me.PendingWithdrawals
import com.couli.core.me.RiskLayout
import com.couli.core.me.RiskNoticeModel
import com.couli.core.me.RiskNoticeView
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WithdrawalDisplay
import com.couli.core.wallet.WithdrawalText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 进行中的一笔提现（G16）：金额与提现记录页同一份显示结论（WithdrawalText.of：BR-TEXT-06 状态标题 + 副文案；共性第 36 条）。 */
data class PendingWithdrawalRow(
    val amountFen: Long,
    val display: WithdrawalDisplay,
)

/** 封禁 / 冻结说明的页面状态。 */
data class RiskState(
    val phase: String = LOADING,
    val risk: JsonObject? = null,
    val latestAppeal: JsonObject? = null,
    /** 最近一次申诉摘要读取失败（统一错误文案 + trace6 + 本区重试，R1-07）；失败时摘要已清空。 */
    val appealsError: MeNotice? = null,
    val withdrawals: List<PendingWithdrawalRow> = emptyList(),
    val withdrawalsError: MeNotice? = null,
    val error: MeNotice? = null,
    /**
     * 已有说明内容时的刷新失败（Android R2-04，共性第 31 条）：本地读取禁用期拦下、42901、5xxxx 等。保留上次读到的内容，但在页面上明确标示「没有刷新」并给受
     * 共享截止时间控制的【重试】——不把上次的账号状态当成刚读到的结果，也不静默。
     */
    val refreshError: MeNotice? = null,
    /** 退出确认框的一次性编号（R1-02）；null = 没有显示。 */
    val logoutDialog: Long? = null,
    val version: Long = 0,
) {
    companion object {
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val ERROR = "error"
    }
}

/**
 * 封禁 / 冻结说明执行器（RiskNotice 路由；brief §2.10，§0 第 4 条；G08、G09、G10、G16、G30；BR-ID-31、BR-TEXT-23）：
 * - 进入即取 GET /v1/me，版式只按服务端 risk.state（[RiskNoticeView]）；不缓存封禁结论、不本地推算解冻或解封、不显示处理时限；
 * - 最近一次账户申诉维持且仍受限时附「最近一次申诉：维持原处理结果」（再取 listAppeals）；读取失败清掉摘要，显示统一错误、trace6 与本区【重试】（R1-07）；
 * - 进行中的提现只取 GET /v1/withdrawals 第一页的 PENDING_REVIEW、APPROVED、PAYING（读取失败只影响这一节，显示错误与本区【重试】，只重取提现，G16）；
 * - 两个附加读取与主读取共用读取闸门：禁用期内不发（执行层 + 页面），各自的 42901 到达即按 Retry-After 启动禁用期（R1-04）；
 * - 读取失败：错误态 + trace6 +【重试】；banned 场景下【退出登录】与联系客服始终可用。
 * 构造时固定身份；出栈在同步出口作废读请求（[leave]）；会话换代时整页由 App 移出导航栈。
 */
@Suppress("TooManyFunctions")
class RiskNoticeController(
    private val deps: MeDeps,
    val identity: MeIdentity,
    /** 本次运行是否因 10006 进入（G10，进入时固定）。 */
    private val enteredBy10006: Boolean = deps.env.enteredBy10006(identity),
) {
    private var left = false
    private var seq = 0L
    private var appealSeq = 0L
    private var withdrawalSeq = 0L
    private var ids = 0L
    private var dialogIds = 0L
    private val owners = mutableListOf<CallOwnership>()
    private val mutable = MutableStateFlow(RiskState())
    val state: StateFlow<RiskState> = mutable.asStateFlow()

    fun alive(): Boolean = !left && identity.current(deps.env)

    fun model(): RiskNoticeModel? {
        val risk = mutable.value.risk ?: return null
        return RiskNoticeView.model(risk, enteredBy10006, mutable.value.latestAppeal, deps.clock())
    }

    @Suppress("ReturnCount")
    fun load() {
        if (!alive() || !identity.member) return
        if (deps.gates.read.active) {
            gated()
            return
        }
        val request = ++seq
        cancelAll()
        if (mutable.value.risk == null) update { it.copy(phase = RiskState.LOADING, error = null) }
        val probe = deps.gates.read.probe()
        val owner = owner(request, probe::allowed)
        deps.service.launch {
            val result = deps.api.send(ApiCall(GET_ME, ownership = owner, expectedAccount = identity.account))
            owners -= owner
            if (!current(request)) return@launch
            val me = (result as? ApiResult.Success)?.let { MeJson.me(it.data) }
            if (me == null) {
                val error = MeErrors.of(result)
                if (error.kind == WalletError.CANCELLED && !probe.gated(error)) return@launch
                if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
                if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
                val notice = if (probe.gated(error)) MeNotice(RATE_KEY, ++ids) else MeErrors.notice(error, ++ids)
                refreshFailed(notice)
                return@launch
            }
            update { RiskState(RiskState.CONTENT, risk = me.risk, logoutDialog = it.logoutDialog, version = it.version + 1) }
            if (me.riskState != NORMAL) {
                loadAppeals(request)
                loadWithdrawals(request)
            }
        }
    }

    private fun owner(
        request: Long,
        allowed: () -> Boolean,
        section: () -> Boolean = { true },
    ): CallOwnership =
        CallOwnership(deps.api.epochs(), sendAllowed = allowed) { !left && seq == request && section() && identity.current(deps.env) }
            .also { owners += it }

    private fun current(request: Long): Boolean = !left && seq == request && identity.current(deps.env)

    /** 附加读取可以发（R1-04）：页面有效、已有非 normal 的说明内容。 */
    private fun sectionsLive(): Boolean =
        alive() && mutable.value.risk != null &&
            (mutable.value.risk?.get("state") as? kotlinx.serialization.json.JsonPrimitive)?.content != NORMAL

    /** 申诉摘要区的【重试】：只重取申诉摘要。 */
    fun retryAppeals() {
        if (sectionsLive()) loadAppeals(seq)
    }

    /** 提现区的【重试】：只重取提现（G16）。 */
    fun retryWithdrawals() {
        if (sectionsLive()) loadWithdrawals(seq)
    }

    /**
     * 最近一次账户申诉（G15 同一口径）：走读取闸门（禁用期内不发，执行层 + 页面，R1-04）；本响应的 42901 到达即启动禁用期；读取失败或坏数据清掉摘要，显示统一错误
     * 与 trace6、本区重试（R1-07）。
     */
    private fun loadAppeals(request: Long) {
        val mine = ++appealSeq
        val gate = deps.gates.read
        if (gate.active) {
            update { it.copy(latestAppeal = null, appealsError = MeNotice(RATE_KEY, ++ids), version = it.version + 1) }
            return
        }
        update { it.copy(appealsError = null) }
        val probe = gate.probe()
        val owner = owner(request, probe::allowed) { appealSeq == mine }
        deps.service.launch {
            val result = deps.api.send(ApiCall(LIST_APPEALS, ownership = owner, expectedAccount = identity.account))
            owners -= owner
            if (!current(request) || appealSeq != mine) return@launch
            val list = (result as? ApiResult.Success)?.let { MeJson.appealList(it.data) }
            if (list != null) {
                update { it.copy(latestAppeal = AppealView.latestAccount(list.items), appealsError = null, version = it.version + 1) }
                return@launch
            }
            val notice = MeReadFailure.handle(result, probe, gate, deps.env) { ++ids } ?: return@launch
            update { it.copy(latestAppeal = null, appealsError = notice, version = it.version + 1) }
        }
    }

    /** 进行中的提现（G16）：走读取闸门（R1-04）；读取失败只影响这一节（统一错误 + trace6 + 只重取提现的【重试】，R1-07）。 */
    private fun loadWithdrawals(request: Long) {
        val mine = ++withdrawalSeq
        val gate = deps.gates.read
        if (gate.active) {
            update { it.copy(withdrawals = emptyList(), withdrawalsError = MeNotice(RATE_KEY, ++ids), version = it.version + 1) }
            return
        }
        update { it.copy(withdrawalsError = null) }
        val probe = gate.probe()
        val owner = owner(request, probe::allowed) { withdrawalSeq == mine }
        deps.service.launch {
            val result = deps.api.send(ApiCall(LIST_WITHDRAWALS, ownership = owner, expectedAccount = identity.account))
            owners -= owner
            if (!current(request) || withdrawalSeq != mine) return@launch
            val list = (result as? ApiResult.Success)?.let { WalletJson.withdrawalList(it.data) }
            if (list == null) {
                val notice = MeReadFailure.handle(result, probe, gate, deps.env) { ++ids } ?: return@launch
                update { it.copy(withdrawals = emptyList(), withdrawalsError = notice, version = it.version + 1) }
                return@launch
            }
            val sla =
                (
                    deps.env
                        .configData()
                        ?.get("texts") as? kotlinx.serialization.json.JsonObject
                )?.get("withdraw.sla_text")
                    ?.let { it as? JsonPrimitive }
                    ?.takeIf { it.isString }
                    ?.content
            val rows =
                list.items
                    .filter { it.status in PendingWithdrawals.STATUSES }
                    .map { PendingWithdrawalRow(it.amountFen, WithdrawalText.of(it, sla)) }
            update { it.copy(withdrawals = rows, withdrawalsError = null, version = it.version + 1) }
        }
    }

    /** 本地读取禁用期拦下（页面层）：与收到 42901 同样处理（R2-04：已有内容时也要显示，不静默）。 */
    private fun gated() = refreshFailed(MeNotice(RATE_KEY, ++ids))

    /** 读取失败：没有内容 → 错误态（统一错误 + trace6 +【重试】）；已有内容 → 保留内容并标示「没有刷新」+【重试】（R2-04）。 */
    private fun refreshFailed(notice: MeNotice) {
        if (mutable.value.risk == null) {
            update { RiskState(RiskState.ERROR, error = notice, logoutDialog = it.logoutDialog, version = it.version + 1) }
        } else {
            update { it.copy(refreshError = notice, version = it.version + 1) }
        }
    }

    /** 主按钮（渲染时的版本）：【提交申诉】/【查看申诉进度】→ Appeal；normal 的【返回首页】→ Home。 */
    fun primary(version: Long) {
        if (!alive() || mutable.value.version != version) return
        when (model()?.primaryRaw) {
            RiskNoticeView.SUBMIT, RiskNoticeView.VIEW_PROGRESS -> deps.env.open(APPEAL)
            RiskNoticeView.BACK_HOME -> deps.env.open(HOME)
        }
    }

    fun contactCs() {
        if (alive()) deps.env.customerService(ROUTE)
    }

    fun askLogout() {
        if (alive()) update { it.copy(logoutDialog = ++dialogIds) }
    }

    /** 【取消】、系统返回（同一编号才生效，R1-02）。 */
    fun cancelLogout(dialog: Long) {
        if (mutable.value.logoutDialog == dialog) update { it.copy(logoutDialog = null) }
    }

    /** 【退出登录】确认（确认框生成时的编号）：只认正显示的这一个，用过即作废；再核对身份与页面（R1-02）。 */
    fun confirmLogout(dialog: Long) {
        if (mutable.value.logoutDialog != dialog) return
        update { it.copy(logoutDialog = null) }
        if (alive()) deps.env.logout(identity) { !left }
    }

    /** 出栈的同步出口：作废并取消读请求，清掉已显示的内容。 */
    fun leave() {
        if (left) return
        left = true
        seq += 1
        cancelAll()
        mutable.value = RiskState()
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    private inline fun update(change: (RiskState) -> RiskState) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val ROUTE = "RiskNotice"
        const val APPEAL = "Appeal"
        const val HOME = "Home"
        const val GET_ME = "getMe"
        const val LIST_APPEALS = "listAppeals"
        const val LIST_WITHDRAWALS = "listWithdrawals"
        const val RATE_KEY = "error.42901"
        const val NORMAL = "normal"
    }
}

class RiskNoticeConfig(
    val deps: MeDeps,
    val onBack: () -> Unit,
    val bindLeave: (() -> Unit) -> Unit = {},
)

/**
 * 封禁 / 冻结说明（设计稿 RiskNoticeBanned、RiskNoticeBannedAppealing、RiskNoticeBannedUpheld、RiskNoticeFrozen、
 * RiskNoticeFrozenAppealing；WalletRiskFrozen 的落点）。
 * banned 版式没有返回（系统返回被吞），底部「联系客服 | 退出登录」（注销按 G30 不显示；APP-13a：按路由可用性显示，G01）；frozen 有返回。
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun RiskNoticeScreen(config: RiskNoticeConfig) {
    val deps = config.deps
    val identity by deps.env.identityFlow.collectAsStateWithLifecycle()
    // 执行器保存在回退栈条目的 ViewModel（R1-08）：配置重建不清；出栈、换身份才作废
    val controller = rememberPageController(RiskNoticeController.ROUTE, identity, { RiskNoticeController(deps, identity) }) { it.leave() }
    DisposableEffect(controller) {
        config.bindLeave(controller::leave)
        onDispose { }
    }
    OnResumed(controller) { controller.load() }
    val state by controller.state.collectAsStateWithLifecycle()
    val blocked by deps.gates.read.blocked
        .collectAsStateWithLifecycle()
    val texts = deps.texts
    val model = controller.model()
    val banned = model?.layoutRaw?.let(RiskLayout::isBanned) ?: false
    // 加载中、出错时也不给返回（可能是封禁）；frozen 与 normal 有返回
    val onBack = if (model != null && model.showBack) config.onBack else null
    MeSubPage(
        tag = MeTags.RISK_PAGE,
        title = texts.text("risk.title"),
        texts = texts,
        onBack = onBack,
        // R1：内容上 24、左右 16、底 16（块间 24 在 RiskContent 里）
        contentPadding =
            PaddingValues(
                start = CouliSpacing.space4,
                top = CouliSpacing.space6,
                end = CouliSpacing.space4,
                bottom = CouliSpacing.space4,
            ),
        bottom =
            if (model != null) {
                {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                    ) {
                        val version = state.version
                        val primaryKey =
                            when (model.primaryRaw) {
                                RiskNoticeView.VIEW_PROGRESS -> "risk.view_progress"
                                RiskNoticeView.BACK_HOME -> "risk.normal.back_home"
                                else -> "risk.submit"
                            }
                        CouliButton(
                            text = texts.text(primaryKey),
                            onClick = { controller.primary(version) },
                            modifier = Modifier.fillMaxWidth().testTag(MeTags.RISK_PRIMARY),
                        )
                        if (banned) RiskFooter(texts, controller)
                    }
                }
            } else {
                null
            },
    ) {
        when {
            model != null -> {
                RiskContent(deps, controller, state, model, blocked)
            }

            state.phase == RiskState.ERROR -> {
                Box(modifier = Modifier.fillMaxWidth().testTag(MeTags.RISK_ERROR)) {
                    ErrorState(
                        title = texts.text("me.load_failed"),
                        message = state.error?.message(texts),
                        retry = StateAction(texts.text("me.retry"), enabled = !blocked) { controller.load() },
                    )
                }
                // 读取失败时联系客服与退出登录始终可用（封禁场景，brief §2.10）；出口行与封禁态同一个 RiskFooter
                RiskFooter(texts, controller)
            }

            else -> {
                Text(text = texts.text("me.loading"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
    }
    val dialog = state.logoutDialog
    if (dialog != null) {
        // 回调捕获这一个确认框的编号（R1-02）
        CouliDialog(
            title = texts.text("settings.logout.confirm"),
            confirmText = texts.text("settings.logout.confirm_ok"),
            onConfirm = { controller.confirmLogout(dialog) },
            dismissText = texts.text("settings.logout.confirm_cancel"),
            onDismissRequest = { controller.cancelLogout(dialog) },
            dismissOnClickOutside = false,
            confirmTag = MeTags.RISK_LOGOUT_DIALOG,
            titleAlign = TextAlign.Center,
            scrim = CouliColors.overlayScrim,
        )
    }
}

/**
 * 出口行（G-R04）：联系客服 | 注销账号 | 退出登录（画板顺序），14 号 text.link、分隔符 border.subtle 且不读；放不下整块换行。
 * 去向不变：客服 / 退出仍是执行器的 contactCs / askLogout，注销仍是 DeleteAccountLink（按路由可用性显示，自带前面的「|」）。
 */
@Composable
private fun RiskFooter(
    texts: com.couli.core.me.MeTexts,
    controller: RiskNoticeController,
) {
    RiskExitRow {
        LinkText(texts.text("risk.contact_cs"), MeTags.RISK_CS, CouliTypography.footnote, CouliColors.textLink, controller::contactCs)
        // 「| 注销账号」「| 退出登录」各自成组（折行时分隔符随链接换行）；注销组只在入口可用时出现（与 DeleteAccountLink 同一判据）
        if (deletionAvailable()) {
            ExitLinkGroup {
                DeleteAccountLink(
                    AccountTags.RISK_DELETE,
                    linkStyle = CouliTypography.footnote,
                    linkColor = CouliColors.textLink,
                    separatorColor = CouliColors.borderSubtle,
                    separatorSilent = true,
                )
            }
        }
        ExitLinkGroup {
            ExitSeparator()
            LinkText(texts.text("risk.logout"), MeTags.RISK_LOGOUT, CouliTypography.footnote, CouliColors.textLink, controller::askLogout)
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun RiskContent(
    deps: MeDeps,
    controller: RiskNoticeController,
    state: RiskState,
    model: RiskNoticeModel,
    blocked: Boolean,
) {
    // R1：块间 24（头部 → 信息卡 → 分区错误 → 提示条）
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space6)) {
        RiskBlocks(deps, controller, state, model, blocked)
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun RiskBlocks(
    deps: MeDeps,
    controller: RiskNoticeController,
    state: RiskState,
    model: RiskNoticeModel,
    blocked: Boolean,
) {
    val texts = deps.texts
    // 已有内容时刷新失败（R2-04）：页首明确标示「没有刷新、下面是上次读到的状态」+ 统一错误文案（42901 / trace6）+【重试】（读取禁用期内置灰，执行层另拦）
    state.refreshError?.let { notice ->
        SectionError(
            texts.text("risk.refresh_failed") + " " + notice.message(texts),
            texts.text("me.retry"),
            MeTags.RISK_REFRESH_ERROR,
            MeTags.RISK_REFRESH_RETRY,
            !blocked,
        ) { controller.load() }
    }
    val layout = model.layoutRaw
    val frozen = RiskLayout.isFrozen(layout)
    val (titleKey, descKey, noteKey) =
        when (layout) {
            RiskLayout.BANNED -> {
                Triple("risk.banned.title", "risk.banned.desc", "risk.banned.note")
            }

            RiskLayout.BANNED_APPEALING -> {
                Triple("risk.banned.title", "risk.banned.desc", "risk.banned_appealing.note")
            }

            RiskLayout.BANNED_UPHELD -> {
                Triple("risk.banned.title", "risk.banned.desc", "risk.banned_upheld.note")
            }

            RiskLayout.FROZEN -> {
                Triple(
                    "risk.frozen.title",
                    "risk.frozen.desc",
                    if (model.frozenUntilText !=
                        null
                    ) {
                        "risk.frozen.note"
                    } else {
                        "risk.frozen.note_no_until"
                    },
                )
            }

            RiskLayout.FROZEN_APPEALING -> {
                Triple("risk.frozen.title", "risk.frozen.desc", "risk.frozen_appealing.note")
            }

            RiskLayout.FROZEN_UPHELD -> {
                Triple("risk.frozen.title", "risk.frozen.desc", "risk.frozen_upheld.note")
            }

            else -> {
                Triple("risk.normal.title", null, null)
            }
        }
    // G-R17：封禁说明句提到注销 ⇔ 页面有注销入口（与 DeleteAccountLink 同一判据 deletionAvailable()）；内测版换不含注销的句子
    val desc = if (descKey == "risk.banned.desc" && !deletionAvailable()) "risk.banned.desc.no_deletion" else descKey
    // R2 / G-R06：图标块 96、图标 32（封禁锁 / 冻结暂停圆）、标题 22/600、间距 16 / 8；normal 无稿（R10），图标维持改前的三角，只随尺寸
    val icon =
        when {
            frozen -> CouliIcons.pauseCircle
            layout == RiskLayout.NORMAL -> CouliIcons.alert
            else -> CouliIcons.lock
        }
    RiskHeader(
        icon = icon,
        title = texts.text(titleKey),
        desc = desc?.let(texts::text),
        titleTag = MeTags.RISK_TITLE,
    )
    if (layout == RiskLayout.NORMAL) return
    val rows = mutableListOf<@Composable () -> Unit>()
    // G08：表外 category 与 null 不显示原因行（不显示原始编码）
    model.reasonKey?.let { key -> rows += { RiskInfoRow(texts.text("risk.reason"), texts.text(key), MeTags.RISK_REASON) } }
    if (!frozen) rows += { RiskInfoRow(texts.text("risk.limit"), texts.text("risk.limit.long"), MeTags.RISK_LIMIT) }
    model.frozenUntilText?.let { until -> rows += { RiskInfoRow(texts.text("risk.frozen_until"), until, MeTags.RISK_UNTIL) } }
    if (layout == RiskLayout.BANNED_APPEALING || layout == RiskLayout.FROZEN_APPEALING) {
        // R4 / G-R07：值画成状态胶囊（处理中 = pending + 时钟），整行仍只读、不加点击与箭头
        val processing = texts.text("risk.appeal.processing")
        rows += {
            RiskInfoRow(texts.text("risk.appeal"), processing) { StatusPill(processing, PillTone.PENDING, CouliIcons.clock) }
        }
    }
    if (model.upheldLine) {
        val upheld = texts.text("risk.upheld")
        rows += {
            RiskInfoRow(
                texts.text("risk.latest_appeal"),
                upheld,
                MeTags.RISK_UPHELD,
            ) { StatusPill(upheld, PillTone.WARNING, CouliIcons.alert) }
        }
    }
    state.withdrawals.forEach { row ->
        val value =
            texts.format(
                "risk.withdrawal_row",
                mapOf("amount" to CouliFormat.money(row.amountFen), "status" to WalletDraftTexts.text(row.display.titleKey)),
            )
        // 共性第 36 条：值 + 与提现记录页同源的副文案（WithdrawalText.render，同一份 WalletDraftTexts）；整行仍一个合并节点、不可点
        val sublines = WithdrawalText.render(row.display, WalletDraftTexts)
        rows += {
            RiskInfoRow(texts.text("risk.withdrawals"), value, MeTags.RISK_WITHDRAWALS) { WithdrawalValue(value, sublines) }
        }
    }
    if (rows.isNotEmpty()) RiskInfoCard(rows)
    state.appealsError?.let { notice ->
        SectionError(notice.message(texts), texts.text("me.retry"), MeTags.RISK_APPEALS_ERROR, MeTags.RISK_APPEALS_RETRY, !blocked) {
            controller.retryAppeals()
        }
    }
    state.withdrawalsError?.let { notice ->
        SectionError(
            notice.message(texts),
            texts.text("me.retry"),
            MeTags.RISK_WITHDRAWALS_ERROR,
            MeTags.RISK_WITHDRAWALS_RETRY,
            !blocked,
        ) {
            controller.retryWithdrawals()
        }
    }
    noteKey?.let { NoteStrip(text = texts.text(it)) }
}

/**
 * 在途提现的值列（画板：column / flex-end / gap 2）：值 14/500 主文字等宽数字，其下每行副文案 12 次文字色；
 * 各行占满值列宽再右对齐（与 RiskInfoRow 的值文字同一写法：字形右缘 = 卡内 16，折行时每行右对齐）。
 */
@Composable
private fun WithdrawalValue(
    value: String,
    sublines: List<String>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2),
    ) {
        Text(
            text = value,
            style = CouliTextStyles.footnoteMedium.tabular(),
            color = CouliColors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        sublines.forEach { line ->
            Text(
                text = line,
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一节的读取失败（R1-07）：统一错误文案（含 trace6）+ 只重取这一节的【重试】（禁用期内置灰，执行层另拦）。 */
@Suppress("LongParameterList")
@Composable
private fun SectionError(
    message: String,
    retry: String,
    tag: String,
    retryTag: String,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = message, style = CouliTypography.caption, color = CouliColors.statusErrorText, modifier = Modifier.weight(1f))
        com.couli.core.designsystem.CouliButton(
            text = retry,
            onClick = onRetry,
            variant = com.couli.core.designsystem.CouliButtonVariant.SECONDARY,
            size = com.couli.core.designsystem.CouliButtonSize.COMPACT,
            enabled = enabled,
            modifier = Modifier.testTag(retryTag),
        )
    }
}
