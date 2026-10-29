// 执行器的每个入口逐项核对身份、版本、禁用期与结果类别（APP-09 第 52、63、65 条）
@file:Suppress("CyclomaticComplexMethod", "ComplexCondition")

package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextField
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
import com.couli.core.me.AppealCopy
import com.couli.core.me.AppealForm
import com.couli.core.me.AppealOutcome
import com.couli.core.me.AppealView
import com.couli.core.me.MeJson
import com.couli.core.me.MeTexts
import com.couli.core.me.RiskReasonText
import com.couli.core.me.WriteOutcome
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 申诉页的状态。[override] 为 submit 时显示提交页（维持后点【再次申诉】）。 */
data class AppealState(
    val phase: String = LOADING,
    val risk: JsonObject? = null,
    val appeals: List<JsonObject> = emptyList(),
    val override: String? = null,
    val input: String = "",
    val submitting: Boolean = false,
    val fieldError: Boolean = false,
    val error: MeNotice? = null,
    val loadError: MeNotice? = null,
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
 * 账户申诉执行器（Appeal 路由；brief §2.11，§0 第 4 条；G10–G15；BR-ID-36、BR-TEXT-23）。构造时固定身份；只做账户申诉（G12）：
 * - 进入取 listAppeals 与 GET /v1/me：列表里 target_type=account 的最新一条 → 提交 / 进度 / 结果版式（G15）；申诉记录只作历史结论，当前是否受限一律按本次
 *   risk.state（[AppealCopy]，鸿蒙 R1-04 / R2-01）；列表读取失败或坏数据时清掉摘要、显示错误态（鸿蒙 R1-12），不按上一轮结论显示；
 * - 提交：说明去首尾空白后非空才可用；body {target_type: account, content}（不带 target_id，G11）；每次点击生成新的 Idempotency-Key（G14）；结果未知不自动
 *   重发；成功 → 本页切到进度态（不新开页）；20001 字段旁提示；42901 申诉提交禁用期（执行层 + 页面）；表外码走统一错误消息函数（G13）；
 * - 不显示处理时限或截止时刻。离开页面：输入与进度清空；已发出的提交不取消，响应不改已出栈的页面。
 */
@Suppress("TooManyFunctions")
class AppealController(
    private val deps: MeDeps,
    val identity: MeIdentity,
    private val enteredBy10006: Boolean = deps.env.enteredBy10006(identity),
) {
    private var left = false
    private var seq = 0L
    private var ids = 0L
    private var dialogIds = 0L
    private val owners = mutableListOf<CallOwnership>()
    private val mutable = MutableStateFlow(AppealState())
    val state: StateFlow<AppealState> = mutable.asStateFlow()

    fun alive(): Boolean = !left && identity.current(deps.env)

    /** 本次 risk.state（取不到为 null）。 */
    fun riskState(): String? = (mutable.value.risk?.get("state") as? JsonPrimitive)?.content

    /** G10 的版式组：frozen → 冻结组；banned → 封禁组；appealing / normal 按本次运行是否因 10006 进入。 */
    fun frozenLayout(): Boolean =
        when (riskState()) {
            "frozen" -> true
            "banned" -> false
            else -> !enteredBy10006
        }

    fun layout(): String = mutable.value.override ?: AppealView.layout(mutable.value.appeals)

    fun latest(): JsonObject? = AppealView.latestAccount(mutable.value.appeals)

    @Suppress("ReturnCount")
    fun load() {
        if (!alive() || !identity.member) return
        if (deps.gates.read.active) {
            update {
                it.copy(
                    phase = AppealState.ERROR,
                    loadError = MeNotice(RATE_KEY, ++ids),
                    appeals = emptyList(),
                    version =
                        it.version + 1,
                )
            }
            return
        }
        val request = ++seq
        cancelAll()
        if (mutable.value.phase != AppealState.CONTENT) update { it.copy(phase = AppealState.LOADING, loadError = null) }
        val gate = deps.gates.read
        deps.service.launch {
            // 每个响应到达即先处理它自己的限流与统一副作用，再决定是否续发（R1-05，共性第 22 条）：getMe 失败就不再发 listAppeals；
            // 续发的 listAppeals 另取发送前闸门，getMe 刚启动的禁用期在执行层同样拦住它
            val meProbe = gate.probe()
            val meOwner = owner(request, meProbe::allowed)
            val meResult = deps.api.send(ApiCall(GET_ME, ownership = meOwner, expectedAccount = identity.account))
            owners -= meOwner
            if (!current(request)) return@launch
            val me = (meResult as? ApiResult.Success)?.let { MeJson.me(it.data) }
            if (me == null) {
                failed(MeReadFailure.handle(meResult, meProbe, gate, deps.env) { ++ids })
                return@launch
            }
            val listProbe = gate.probe()
            val listOwner = owner(request, listProbe::allowed)
            val listResult = deps.api.send(ApiCall(LIST_APPEALS, ownership = listOwner, expectedAccount = identity.account))
            owners -= listOwner
            if (!current(request)) return@launch
            val list = (listResult as? ApiResult.Success)?.let { MeJson.appealList(it.data) }
            if (list == null) {
                failed(MeReadFailure.handle(listResult, listProbe, gate, deps.env) { ++ids })
                return@launch
            }
            update {
                it.copy(
                    phase = AppealState.CONTENT,
                    risk = me.risk,
                    appeals = list.items,
                    loadError = null,
                    version = it.version + 1,
                )
            }
        }
    }

    /** 读取失败（[notice] 为 null = 被作废的静默结果，不改页面）：申诉摘要清空并重新发布（鸿蒙 R1-12），不把上一轮结论继续当最近一次结果。 */
    private fun failed(notice: MeNotice?) {
        if (notice == null) return
        update { it.copy(phase = AppealState.ERROR, appeals = emptyList(), risk = null, loadError = notice, version = it.version + 1) }
    }

    private fun owner(
        request: Long,
        allowed: () -> Boolean,
    ): CallOwnership =
        CallOwnership(deps.api.epochs(), sendAllowed = allowed) { !left && seq == request && identity.current(deps.env) }.also {
            owners +=
                it
        }

    private fun current(request: Long): Boolean = !left && seq == request && identity.current(deps.env)

    fun onInput(text: String) {
        if (alive()) update { it.copy(input = text, fieldError = false, error = null) }
    }

    /** 【提交申诉】（渲染时的执行器与版本）。 */
    @Suppress("ReturnCount")
    fun submit(version: Long): Boolean {
        val s = mutable.value
        if (!alive() || s.submitting || s.version != version || layout() != AppealView.SUBMIT) return false
        val gate = deps.gates.appeal
        if (gate.active) {
            update { it.copy(error = MeNotice(RATE_KEY, ++ids)) }
            return false
        }
        val body = AppealForm.body(s.input) ?: return false
        // 每次用户点击生成新键；同一次点击的传输重试沿用原键（网络层，G14）
        val key = deps.newKey()
        val probe = gate.probe()
        val owner = CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { identity.current(deps.env) }
        update { it.copy(submitting = true, error = null, fieldError = false) }
        deps.service.launch {
            val result =
                deps.api.send(
                    ApiCall(SUBMIT, body = body.toString(), idempotencyKey = key, ownership = owner, expectedAccount = identity.account),
                )
            if (!identity.current(deps.env)) return@launch
            val rejected = result is ApiResult.LocalReject && result.reason == LocalRejectReason.CALL_CANCELLED
            val outcome =
                if (rejected) {
                    if (probe.rejected) WriteOutcome(WriteOutcome.RETRY_AFTER, RATE_KEY, keepInput = true) else null
                } else {
                    AppealOutcome.model(MeResultInput.of(result))
                }
            if (outcome?.actionRaw == WriteOutcome.RETRY_AFTER && !rejected) gate.start(outcome.disableMs ?: DEFAULT_DISABLE)
            if (outcome?.actionRaw == WriteOutcome.FORCE_UPDATE) deps.env.forceUpdate()
            // 写请求离开页面不取消，但响应回来不得改动已出栈的页面
            if (left) return@launch
            settle(outcome, result)
        }
        return true
    }

    private fun settle(
        outcome: WriteOutcome?,
        result: ApiResult,
    ) {
        update { it.copy(submitting = false) }
        when (outcome?.actionRaw) {
            null, WriteOutcome.FORCE_UPDATE, WriteOutcome.RISK_NOTICE -> {
                Unit
            }

            WriteOutcome.PROGRESS -> {
                // 成功：先推进读取世代并取消提交之前发起的读取（Android R2-02，共性第 29 条）——晚到的旧列表 / 旧 getMe 不再覆盖进度、不恢复旧结论；
                // 之后的刷新（回前台、重试）绑定新世代
                invalidateReads()
                // 本页切到进度态（处理中再次提交服务端返回原单，同样进度态）
                val appeal = MeJson.appeal((result as? ApiResult.Success)?.data)
                update {
                    val list =
                        if (appeal !=
                            null
                        ) {
                            listOf(appeal) + it.appeals.filter { a -> a["appeal_id"] != appeal["appeal_id"] }
                        } else {
                            it.appeals
                        }
                    // 一次性恢复页面（Android R3-03，共性第 35 条）：提交成功先于 / 晚于一次失败的刷新都切到 CONTENT 并清掉旧的加载错误，同时应用返回的申诉单；
                    // 当前风险状态不从申诉单推算，仍只取新的 getMe（risk 保持原值）
                    it.copy(
                        phase = AppealState.CONTENT,
                        loadError = null,
                        appeals = list,
                        override =
                            if (appeal !=
                                null
                            ) {
                                null
                            } else {
                                AppealView.PROGRESS
                            },
                        input = "",
                        version = it.version + 1,
                    )
                }
            }

            WriteOutcome.FIELD_ERROR -> {
                update { it.copy(fieldError = true, error = MeNotice(outcome.textKey.orEmpty(), ++ids)) }
            }

            WriteOutcome.ERROR_TRACE6 -> {
                update {
                    it.copy(
                        error = MeNotice(outcome.textKey.orEmpty(), ++ids, (result as? ApiResult.Failure)?.traceId, showTrace6 = true),
                    )
                }
            }

            else -> {
                update { it.copy(error = MeNotice(outcome.textKey.orEmpty(), ++ids, serverMsg = (result as? ApiResult.Failure)?.msg)) }
            }
        }
    }

    /** 【再次申诉】（维持结果页）：回到提交页（客户端不计次数、不预判第 4 次，G13）。 */
    fun again(version: Long) {
        if (alive() && mutable.value.version == version && layout() == AppealView.RESULT_UPHELD) {
            update { it.copy(override = AppealView.SUBMIT, input = "", version = it.version + 1) }
        }
    }

    /** 结果页按钮：go_wallet / start / view_messages / contact_cs。 */
    fun action(
        raw: String,
        version: Long,
    ) {
        if (!alive() || mutable.value.version != version) return
        when (raw) {
            "go_wallet" -> deps.env.open(WALLET)
            "start" -> deps.env.open(HOME)
            "view_messages" -> deps.env.open(MESSAGES)
            "contact_cs" -> deps.env.customerService(ROUTE)
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

    /** 出栈、换代：作废读请求，清空输入与进度（申诉草稿不残留）。 */
    fun leave() {
        if (left) return
        left = true
        invalidateReads()
        mutable.value = AppealState()
    }

    /** 推进读取世代并取消在途读取：之前发起的读取（含已交给传输层的）结果一律不再结算。 */
    private fun invalidateReads() {
        seq += 1
        cancelAll()
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    private inline fun update(change: (AppealState) -> AppealState) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val ROUTE = "Appeal"
        const val GET_ME = "getMe"
        const val LIST_APPEALS = "listAppeals"
        const val SUBMIT = "submitAppeal"
        const val WALLET = "Wallet"
        const val HOME = "Home"
        const val MESSAGES = "Messages"
        const val RATE_KEY = "error.42901"
        private const val DEFAULT_DISABLE = 5_000L
    }
}

class AppealConfig(
    val deps: MeDeps,
    val onBack: () -> Unit,
    val bindLeave: (() -> Unit) -> Unit = {},
)

/**
 * 账户申诉（设计稿 Appeal、AppealFrozen、AppealProgress、AppealProgressFrozen、AppealResultLifted(Frozen)、AppealResultUpheld(Frozen)）：同一页按 G15 切版式。
 * 时间线与处理时间用 BR-TEXT-11「YYYY-MM-DD HH:mm」（H11）；不显示任何处理时限。
 */
@Suppress("LongMethod")
@Composable
fun AppealScreen(config: AppealConfig) {
    val deps = config.deps
    val identity by deps.env.identityFlow.collectAsStateWithLifecycle()
    // 执行器保存在回退栈条目的 ViewModel（R1-08）：配置重建不清申诉输入；出栈、换身份才作废
    val controller = rememberPageController(AppealController.ROUTE, identity, { AppealController(deps, identity) }) { it.leave() }
    DisposableEffect(controller) {
        config.bindLeave(controller::leave)
        onDispose { }
    }
    OnResumed(controller) { controller.load() }
    val state by controller.state.collectAsStateWithLifecycle()
    val readBlocked by deps.gates.read.blocked
        .collectAsStateWithLifecycle()
    val submitBlocked by deps.gates.appeal.blocked
        .collectAsStateWithLifecycle()
    val texts = deps.texts
    val layout = controller.layout()
    val content = state.phase == AppealState.CONTENT
    MeSubPage(
        tag = MeTags.APPEAL_PAGE,
        // G-R12：提交版式「申诉」，处理中 / 结果版式都是「申诉进度」（键已有）；返回在所有版式保留
        title = texts.text(if (content && layout != AppealView.SUBMIT) "appeal.progress.title" else "appeal.title"),
        texts = texts,
        onBack = config.onBack,
        // R11：上 8、左右 16、底 16
        contentPadding = SETTINGS_CONTENT_PADDING,
        // R15 / R27：【提交申诉】与维持结果的【再次申诉】+ 出口行在白底固定栏（键盘弹起贴在键盘上，G-R22）
        bottomSurface = true,
        bottom =
            if (content && layout == AppealView.SUBMIT) {
                {
                    val version = state.version
                    CouliButton(
                        text = texts.text("appeal.submit"),
                        onClick = { controller.submit(version) },
                        enabled = AppealForm.body(state.input) != null && !submitBlocked,
                        loading = state.submitting,
                        modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_SUBMIT),
                    )
                }
            } else if (content && layout == AppealView.RESULT_UPHELD) {
                { AppealUpheldBar(texts, controller, state) }
            } else {
                null
            },
    ) {
        when {
            content && layout == AppealView.SUBMIT -> {
                AppealSubmitBody(deps, controller, state)
            }

            content && layout == AppealView.PROGRESS -> {
                AppealProgressBody(deps, controller)
            }

            content -> {
                AppealResultBody(deps, controller, state, layout)
            }

            state.phase == AppealState.ERROR -> {
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_LOAD_ERROR)) {
                    ErrorState(
                        title = texts.text("me.load_failed"),
                        message = state.loadError?.message(texts),
                        retry = StateAction(texts.text("me.retry"), enabled = !readBlocked) { controller.load() },
                    )
                }
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
            confirmTag = MeTags.APPEAL_LOGOUT_DIALOG,
            titleAlign = TextAlign.Center,
            scrim = CouliColors.overlayScrim,
        )
    }
}

/**
 * 维持结果的白底固定栏（R27）：次按钮【再次申诉】+ 出口行（客服 | 注销 | 退出，冻结组只有客服——条件与改前相同）。
 * 回调与去向不变：again(version) / contactCs / askLogout / DeleteAccountLink。
 */
@Composable
private fun AppealUpheldBar(
    texts: MeTexts,
    controller: AppealController,
    state: AppealState,
) {
    val version = state.version
    val frozen = controller.frozenLayout()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        CouliButton(
            text = texts.text("appeal.again"),
            onClick = { controller.again(version) },
            variant = CouliButtonVariant.SECONDARY,
            modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_AGAIN),
        )
        RiskExitRow {
            LinkText(
                texts.text("appeal.contact_cs"),
                MeTags.APPEAL_CS,
                CouliTypography.footnote,
                CouliColors.textLink,
                controller::contactCs,
            )
            if (!frozen) {
                // 「| 注销账号」「| 退出登录」各自成组（折行时分隔符随链接换行，F-AND-75 ②）
                if (deletionAvailable()) {
                    ExitLinkGroup {
                        DeleteAccountLink(
                            AccountTags.APPEAL_DELETE,
                            linkStyle = CouliTypography.footnote,
                            linkColor = CouliColors.textLink,
                            separatorColor = CouliColors.borderSubtle,
                            separatorSilent = true,
                        )
                    }
                }
                ExitLinkGroup {
                    ExitSeparator()
                    LinkText(
                        texts.text("risk.logout"),
                        MeTags.APPEAL_LOGOUT,
                        CouliTypography.footnote,
                        CouliColors.textLink,
                        controller::askLogout,
                    )
                }
            }
        }
    }
}

/** 本次 getMe 的原因键（表外类别与 null 为 null，不显示原始编码）。 */
private fun riskReasonKey(state: AppealState): String? =
    RiskReasonText.key((state.risk?.get("reason_category") as? JsonPrimitive)?.takeIf { it.isString }?.content)

/** 本次 getMe 的 frozen_until（只取服务端字段，不推算）。 */
private fun frozenUntil(state: AppealState) =
    (state.risk?.get("frozen_until") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(MeJson::instant)

/** 当前状态胶囊（G-R11）：色调只按本次 risk.state——banned = error + 三角、frozen = warning + 三角、其余 = pending 无图标。 */
@Composable
private fun RiskStatePill(
    text: String,
    riskState: String?,
) {
    when (riskState) {
        "banned" -> StatusPill(text, PillTone.ERROR, CouliIcons.alert)
        "frozen" -> StatusPill(text, PillTone.WARNING, CouliIcons.alert)
        else -> StatusPill(text, PillTone.PENDING)
    }
}

/** 申诉对象（类型、当前状态、原因、预计恢复）：当前状态只按本次 risk.state。 */
@Composable
private fun TargetCard(
    deps: MeDeps,
    controller: AppealController,
    state: AppealState,
) {
    val texts = deps.texts
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += { InlineRow(texts.text("appeal.type"), texts.text("appeal.type.account")) }
    AppealCopy.currentStateKey(controller.riskState())?.let { key ->
        val text = texts.text(key)
        rows += { InlineRow(texts.text("appeal.current_state"), text) { RiskStatePill(text, controller.riskState()) } }
    }
    riskReasonKey(state)?.let { key -> rows += { InlineRow(texts.text("appeal.reason"), texts.text(key)) } }
    val until = frozenUntil(state)
    if (until != null && controller.riskState() == "frozen") {
        rows += { InlineRow(texts.text("appeal.frozen_until"), CouliFormat.timeListFuture(until, deps.clock())) }
    }
    AppealInfoCard(texts.text("appeal.target")) { rows.forEach { it() } }
}

@Composable
private fun AppealSubmitBody(
    deps: MeDeps,
    controller: AppealController,
    state: AppealState,
) {
    val texts = deps.texts
    TargetCard(deps, controller, state)
    val error = state.error?.message(texts)
    val errorFocus = remember { FocusRequester() }
    AppealInfoCard(texts.text("appeal.content_label")) {
        // R13 / G-R14：组件库多行输入（占位草稿键、读屏名称「申诉说明」）；错误语义由组件 error 承担（与改前 semantics { error } 同条件），
        // 错误文字仍由下方 ErrorFocusText 显示（showErrorText = false 不重复）；最小高 160 用 leading 槽里 0 宽占位撑高（组件库不改）
        CouliTextField(
            value = state.input,
            onValueChange = controller::onInput,
            singleLine = false,
            placeholder = texts.text("appeal.placeholder"),
            accessibilityLabel = texts.text("appeal.content_label"),
            error = if (state.fieldError) error else null,
            showErrorText = false,
            leading = { Spacer(Modifier.height(APPEAL_INPUT_MIN_HEIGHT)) },
            modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_INPUT),
        )
        if (error != null) {
            // 提交失败 assertive 播报一次，焦点移到错误提示（真正可聚焦的目标，布局完成后移焦，R1-09；保留输入）
            ErrorFocusText(error, state.error?.id, errorFocus, MeTags.APPEAL_ERROR)
        }
    }
    NoteStrip(text = texts.text(AppealCopy.submitNote(controller.frozenLayout(), controller.riskState())))
}

/** 申诉内容卡（R18 / R26）：类型 + 说明原文；「原因」行不加（申诉记录不带原因，G-R11）。 */
@Composable
private fun AppealContentCard(
    texts: MeTexts,
    content: String,
) {
    AppealInfoCard(texts.text("appeal.content_section")) {
        InlineRow(texts.text("appeal.type"), texts.text("appeal.type.account"))
        InlineRow(texts.text("appeal.content"), content)
    }
}

@Composable
private fun AppealProgressBody(
    deps: MeDeps,
    controller: AppealController,
) {
    val texts = deps.texts
    val frozen = controller.frozenLayout()
    val latest = controller.latest()
    // R16：状态卡（胶囊 pending + 时钟 → 标题 22/600 → 说明 14）
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(MeTags.APPEAL_PROGRESS)
                .couliCard()
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        StatusPill(texts.text("appeal.progress.badge"), PillTone.PENDING, CouliIcons.clock)
        Text(
            text = texts.text("appeal.progress.heading"),
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = texts.text(if (frozen) "appeal.progress.desc.frozen" else "appeal.progress.desc.banned"),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
        )
    }
    // R17：步骤内容与时间取值原样，步态由版式决定（已提交 ✓ / 处理中 当前 / 处理结果 未到）
    val created = (latest?.get("created_at") as? JsonPrimitive)?.content?.let(MeJson::instant)
    AppealTimeline(
        listOf(
            TimelineStep(texts.text("appeal.timeline.submitted"), created?.let(CouliFormat::timeMinute), StepState.DONE),
            TimelineStep(
                texts.text("appeal.timeline.processing"),
                AppealCopy.processingDesc(frozen, controller.riskState())?.let(texts::text),
                StepState.CURRENT,
            ),
            TimelineStep(
                texts.text("appeal.timeline.result"),
                texts.text(if (frozen) "appeal.timeline.result_pending.frozen" else "appeal.timeline.result_pending.banned"),
                StepState.PENDING,
            ),
        ),
    )
    (latest?.get("content") as? JsonPrimitive)?.content?.let { text -> AppealContentCard(texts, text) }
    // R19：底部句居中、14 次文字 + 链接 14/500 text.link（句子左侧补 8 与链接内边距对称，整句居中）
    RiskExitRow {
        Text(
            text = texts.text("appeal.footer"),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = Modifier.padding(start = CouliSpacing.space2),
        )
        LinkText(
            texts.text("appeal.contact_cs"),
            MeTags.APPEAL_CS,
            CouliTextStyles.footnoteMedium,
            CouliColors.textLink,
            controller::contactCs,
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun AppealResultBody(
    deps: MeDeps,
    controller: AppealController,
    state: AppealState,
    layout: String,
) {
    val texts = deps.texts
    val frozen = controller.frozenLayout()
    val risk = controller.riskState()
    val latest = controller.latest()
    val closed = (latest?.get("closed_at") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(MeJson::instant)
    val version = state.version
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_RESULT),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        if (layout == AppealView.RESULT_LIFTED) {
            // R20：头部图标块（success + 圆勾）；卡（结论胶囊 / 处理时间 / 账号状态，行键与条件原样）；提示条原样（G-R10）
            RiskHeader(
                icon = CouliIcons.check,
                title = texts.text(AppealCopy.liftedTitle(frozen, risk)),
                desc = texts.text(AppealCopy.liftedDesc(risk)),
                blockColor = CouliColors.statusSuccessBackground,
                iconTint = CouliColors.statusSuccessText,
            )
            val rows = mutableListOf<@Composable () -> Unit>()
            val passed = texts.text("appeal.passed")
            rows += { RiskInfoRow(texts.text("appeal.conclusion"), passed) { StatusPill(passed, PillTone.SUCCESS, CouliIcons.check) } }
            closed?.let { at -> rows += { RiskInfoRow(texts.text("appeal.closed_at"), CouliFormat.timeMinute(at)) } }
            AppealCopy.currentStateKey(risk)?.let { key -> rows += { RiskInfoRow(texts.text("appeal.account_state"), texts.text(key)) } }
            RiskInfoCard(rows)
            AppealCopy.liftedNote(frozen, risk)?.let { NoteStrip(text = texts.text(it)) }
            // G-R13：第一个动作是主按钮，其余是居中文字链接；去向与回调不变（同一个 action(raw, version)）
            AppealCopy.liftedActions(frozen, risk).forEachIndexed { index, raw ->
                val key =
                    when (raw) {
                        "go_wallet" -> "appeal.go_wallet"
                        "start" -> "appeal.start"
                        "view_messages" -> "appeal.view_messages"
                        else -> "appeal.contact_cs"
                    }
                val onAction = { controller.action(raw, version) }
                if (index == 0) {
                    CouliButton(
                        text = texts.text(key),
                        onClick = onAction,
                        variant = CouliButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_ACTION_PREFIX + raw),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LinkText(
                            texts.text(key),
                            MeTags.APPEAL_ACTION_PREFIX + raw,
                            CouliTypography.footnote,
                            CouliColors.textLink,
                            onAction,
                        )
                    }
                }
            }
        } else {
            // R22：状态卡（warning 胶囊 + 标题 + 说明 + 处理时间）
            Column(
                modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                StatusPill(texts.text("appeal.upheld.badge"), PillTone.WARNING, CouliIcons.alert)
                Text(
                    text = texts.text("appeal.upheld.title"),
                    style = CouliTypography.title,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = texts.text("appeal.upheld.desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                closed?.let { at -> InlineRow(texts.text("appeal.closed_at"), CouliFormat.timeMinute(at)) }
            }
            // R23：已提交 ✓（created_at，与处理中版式同取法）→ 处理中 ✓ → 处理结果 ⚠（600，说明「维持原处理结果 · 时间」）
            val created = (latest?.get("created_at") as? JsonPrimitive)?.content?.let(MeJson::instant)
            AppealTimeline(
                listOf(
                    TimelineStep(texts.text("appeal.timeline.submitted"), created?.let(CouliFormat::timeMinute), StepState.DONE),
                    TimelineStep(
                        texts.text("appeal.timeline.processing"),
                        AppealCopy.processingDesc(frozen, risk)?.let(texts::text),
                        StepState.DONE,
                    ),
                    TimelineStep(
                        texts.text("appeal.timeline.result"),
                        closed?.let { at -> texts.format("appeal.upheld.result_line", mapOf("time" to CouliFormat.timeMinute(at))) },
                        StepState.WARNING,
                    ),
                ),
            )
            CurrentStateCard(deps, state, frozen, risk)
            // R25：接下来可以（逐条成行；条目与显隐条件原样，冻结组那句按画板放首位）
            BulletCard(texts.text("appeal.next"), nextSteps(texts, frozen, risk, deletionAvailable()))
            (latest?.get("content") as? JsonPrimitive)?.content?.let { text -> AppealContentCard(texts, text) }
        }
    }
}

/** 当前账号状态卡（R24）：只读本次 getMe，与账号状态页同条件——状态胶囊、原因（有才显示）、限制期限（banned）/ 预计恢复（frozen 且有值）。 */
@Composable
private fun CurrentStateCard(
    deps: MeDeps,
    state: AppealState,
    frozen: Boolean,
    risk: String?,
) {
    val texts = deps.texts
    val rows = mutableListOf<@Composable () -> Unit>()
    AppealCopy.currentStateKey(risk)?.let { key ->
        val text = texts.text(key)
        rows += { InlineRow(texts.text("appeal.state"), text) { RiskStatePill(text, risk) } }
    }
    riskReasonKey(state)?.let { key -> rows += { InlineRow(texts.text("appeal.reason"), texts.text(key)) } }
    if (risk == "banned") rows += { InlineRow(texts.text("risk.limit"), texts.text("risk.limit.long")) }
    val until = frozenUntil(state)
    if (until != null && risk == "frozen") {
        rows += { InlineRow(texts.text("appeal.frozen_until"), CouliFormat.timeListFuture(until, deps.clock())) }
    }
    if (rows.isEmpty()) return
    AppealInfoCard(texts.text(if (frozen) "appeal.current_frozen_state" else "appeal.current_account_state")) { rows.forEach { it() } }
}

/** 「接下来可以」各条（条目与条件同改前；第 3 条提到注销 ⇔ 有注销入口，与 DeleteAccountLink 同判据，G-R17）。 */
private fun nextSteps(
    texts: MeTexts,
    frozen: Boolean,
    risk: String?,
    withDeletion: Boolean,
): List<String> {
    val lines = mutableListOf<String>()
    if (AppealCopy.upheldFrozenNext(frozen, risk)) lines += texts.text("appeal.next.frozen")
    lines += texts.text("appeal.next.again")
    lines += texts.text("appeal.next.cs")
    if (!frozen) lines += texts.text(if (withDeletion) "appeal.next.logout.with_deletion" else "appeal.next.logout")
    return lines
}
