// 执行器的每个入口逐项核对身份、版本、禁用期与结果类别（APP-09 第 52、63、65 条）
@file:Suppress("CyclomaticComplexMethod", "ComplexCondition")

package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSpacing
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
        title = texts.text(if (content && layout == AppealView.PROGRESS) "appeal.progress.title" else "appeal.title"),
        texts = texts,
        onBack = config.onBack,
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
        )
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
    val risk = state.risk
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += { ValueRow(texts.text("appeal.type"), texts.text("appeal.type.account")) }
    AppealCopy.currentStateKey(controller.riskState())?.let { key ->
        rows +=
            { ValueRow(texts.text("appeal.current_state"), texts.text(key)) }
    }
    val reason = RiskReasonText.key((risk?.get("reason_category") as? JsonPrimitive)?.takeIf { it.isString }?.content)
    reason?.let { key -> rows += { ValueRow(texts.text("appeal.reason"), texts.text(key)) } }
    val until = (risk?.get("frozen_until") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(MeJson::instant)
    if (until != null && controller.riskState() == "frozen") {
        rows += { ValueRow(texts.text("appeal.frozen_until"), CouliFormat.timeListFuture(until, deps.clock())) }
    }
    GroupLabel(texts.text("appeal.target"))
    RowCard(rows)
}

@Composable
private fun AppealSubmitBody(
    deps: MeDeps,
    controller: AppealController,
    state: AppealState,
) {
    val texts = deps.texts
    TargetCard(deps, controller, state)
    GroupLabel(texts.text("appeal.content_label"))
    val error = state.error?.message(texts)
    val errorFocus = remember { FocusRequester() }
    OutlinedTextField(
        value = state.input,
        onValueChange = controller::onInput,
        minLines = 4,
        isError = state.fieldError,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CouliColors.brandPrimary),
        label = { Text(texts.text("appeal.content_label")) },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { if (state.fieldError && error != null) error(error) }
                .testTag(MeTags.APPEAL_INPUT),
    )
    if (error != null) {
        // 提交失败 assertive 播报一次，焦点移到错误提示（真正可聚焦的目标，布局完成后移焦，R1-09；保留输入）
        ErrorFocusText(error, state.error?.id, errorFocus, MeTags.APPEAL_ERROR)
    }
    NoteStrip(text = texts.text(AppealCopy.submitNote(controller.frozenLayout(), controller.riskState())))
}

@Composable
private fun AppealProgressBody(
    deps: MeDeps,
    controller: AppealController,
) {
    val texts = deps.texts
    val frozen = controller.frozenLayout()
    val latest = controller.latest()
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_PROGRESS),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Badge(texts.text("appeal.progress.badge"))
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
    val created = (latest?.get("created_at") as? JsonPrimitive)?.content?.let(MeJson::instant)
    RowCard(
        listOf(
            { TimelineRow(texts.text("appeal.timeline.submitted"), created?.let(CouliFormat::timeMinute)) },
            {
                TimelineRow(
                    texts.text("appeal.timeline.processing"),
                    AppealCopy.processingDesc(frozen, controller.riskState())?.let(texts::text),
                )
            },
            {
                TimelineRow(
                    texts.text("appeal.timeline.result"),
                    texts.text(if (frozen) "appeal.timeline.result_pending.frozen" else "appeal.timeline.result_pending.banned"),
                )
            },
        ),
    )
    (latest?.get("content") as? JsonPrimitive)?.content?.let { text ->
        GroupLabel(texts.text("appeal.content_section"))
        Column(modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4)) {
            Text(text = text, style = CouliTypography.body, color = CouliColors.textPrimary)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = texts.text("appeal.footer"),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = Modifier.weight(1f, false),
        )
        LinkText(texts.text("appeal.contact_cs"), MeTags.APPEAL_CS, controller::contactCs)
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
            Text(
                text = texts.text(AppealCopy.liftedTitle(frozen, risk)),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = texts.text(AppealCopy.liftedDesc(risk)), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            val rows = mutableListOf<@Composable () -> Unit>()
            rows += { ValueRow(texts.text("appeal.conclusion"), texts.text("appeal.passed")) }
            closed?.let { at -> rows += { ValueRow(texts.text("appeal.closed_at"), CouliFormat.timeMinute(at)) } }
            AppealCopy.currentStateKey(risk)?.let { key -> rows += { ValueRow(texts.text("appeal.account_state"), texts.text(key)) } }
            RowCard(rows)
            AppealCopy.liftedNote(frozen, risk)?.let { NoteStrip(text = texts.text(it)) }
            AppealCopy.liftedActions(frozen, risk).forEachIndexed { index, raw ->
                val key =
                    when (raw) {
                        "go_wallet" -> "appeal.go_wallet"
                        "start" -> "appeal.start"
                        "view_messages" -> "appeal.view_messages"
                        else -> "appeal.contact_cs"
                    }
                CouliButton(
                    text = texts.text(key),
                    onClick = { controller.action(raw, version) },
                    variant = if (index == 0) com.couli.core.designsystem.CouliButtonVariant.PRIMARY else CouliButtonVariant.SECONDARY,
                    modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_ACTION_PREFIX + raw),
                )
            }
        } else {
            Badge(texts.text("appeal.upheld.badge"))
            Text(
                text = texts.text("appeal.upheld.title"),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = texts.text("appeal.upheld.desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            val rows = mutableListOf<@Composable () -> Unit>()
            closed?.let { at ->
                rows += { TimelineRow(texts.format("appeal.upheld.result_line", mapOf("time" to CouliFormat.timeMinute(at))), null) }
            }
            AppealCopy.currentStateKey(risk)?.let { key ->
                rows +=
                    { ValueRow(texts.text(if (frozen) "appeal.current_frozen_state" else "appeal.current_account_state"), texts.text(key)) }
            }
            if (rows.isNotEmpty()) RowCard(rows)
            GroupLabel(texts.text("appeal.next"))
            NoteStrip(text = nextSteps(texts, frozen, risk))
            CouliButton(
                text = texts.text("appeal.again"),
                onClick = { controller.again(version) },
                modifier = Modifier.fillMaxWidth().testTag(MeTags.APPEAL_AGAIN),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkText(texts.text("appeal.contact_cs"), MeTags.APPEAL_CS, controller::contactCs)
                if (!frozen) {
                    Text(text = "|", style = CouliTypography.footnote, color = CouliColors.borderControl)
                    LinkText(texts.text("risk.logout"), MeTags.APPEAL_LOGOUT, controller::askLogout)
                }
            }
        }
    }
}

private fun nextSteps(
    texts: MeTexts,
    frozen: Boolean,
    risk: String?,
): String {
    val lines = mutableListOf(texts.text("appeal.next.again"), texts.text("appeal.next.cs"))
    if (AppealCopy.upheldFrozenNext(frozen, risk)) lines += texts.text("appeal.next.frozen")
    if (!frozen) lines += texts.text("appeal.next.logout")
    return lines.joinToString("\n")
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = CouliTextStyles.captionSemibold,
        color = CouliColors.statusPendingText,
        modifier =
            Modifier
                .background(CouliColors.statusPendingBackground, RoundedCornerShape(CouliRadius.pill))
                .padding(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1),
    )
}

@Composable
private fun TimelineRow(
    title: String,
    desc: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = com.couli.core.designsystem.CouliSizes.touchTarget)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary)
        desc?.let { Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary) }
    }
}
