// 执行器的每个入口逐项核对身份、版本、禁用期与结果类别（APP-09 第 52、63、65 条）
@file:Suppress("CyclomaticComplexMethod", "ReturnCount")

package com.couli.feature.me

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.me.NicknameCheck
import com.couli.core.me.NicknameInput
import com.couli.core.me.NicknameOutcome
import com.couli.core.me.WriteOutcome
import com.couli.core.network.ApiResult
import com.couli.core.network.LocalRejectReason
import com.couli.core.wallet.WalletError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 修改昵称子页的状态。 */
data class NicknameState(
    val input: String,
    val saving: Boolean = false,
    val error: MeNotice? = null,
    val fieldError: Boolean = false,
)

/**
 * 修改昵称（设置页的页内子页，G01、G27、G32；设计稿 SettingsNickname 一族）。表单构造时固定身份（APP-09 第 65 条）与原昵称；输入、清除、保存都只比较当前身份，
 * 不重新绑定。【保存】：去首尾空白后 1–16 个字素才可用、与当前昵称相同不可用；昵称 42901 禁用期在执行层（[NicknameBackend] 的 allowed）与页面两处生效；
 * 不自动重试写请求。离开页面（出栈、换代、撤回）清空输入草稿；已发出的保存不取消，但响应不改已出栈的页面。
 */
class NicknameController(
    private val deps: MeDeps,
    val identity: MeIdentity,
    val original: String,
    /** 宿主设置页：保存成功后的 Me 重取由它发起（读取许可绑定设置页，R1-03）。 */
    private val host: MeReader = MeReader { true },
) {
    private var left = false
    private var ids = 0L
    private val mutable = MutableStateFlow(NicknameState(original))
    val state: StateFlow<NicknameState> = mutable.asStateFlow()

    val editable: Boolean get() = deps.nickname != null && identity.member

    fun alive(): Boolean = !left && identity.current(deps.env)

    fun check(input: String = mutable.value.input): NicknameCheck = NicknameInput.model(original, input)

    fun onInput(text: String) {
        if (!alive()) return
        mutable.value = mutable.value.copy(input = text, fieldError = false, error = null)
    }

    fun clear() = onInput("")

    /** 【保存】（渲染时的执行器）：只按本表单固定的身份发。 */
    @Suppress("ReturnCount")
    fun save(): Boolean {
        val backend = deps.nickname ?: return false
        val account = identity.account ?: return false
        if (!alive() || mutable.value.saving || !editable) return false
        val gate = deps.gates.nickname
        if (gate.active) {
            mutable.value = mutable.value.copy(error = MeNotice(RATE_KEY, ++ids))
            return false
        }
        val check = check()
        if (!check.saveEnabled) return false
        val nickname = check.trimmed
        mutable.value = mutable.value.copy(saving = true, error = null, fieldError = false)
        val probe = gate.probe()
        deps.service.launch {
            val result = backend.save(account, nickname) { probe.allowed() && identity.current(deps.env) }
            // 换代之后到达：不改任何状态（新会话的页面已重建）
            if (!identity.current(deps.env)) return@launch
            val rejected = result is ApiResult.LocalReject && result.reason == LocalRejectReason.CALL_CANCELLED
            val outcome =
                if (rejected) {
                    if (probe.rejected) WriteOutcome(WriteOutcome.RETRY_AFTER, RATE_KEY, keepInput = true) else null
                } else {
                    NicknameOutcome.model(MeResultInput.of(result))
                }
            if (outcome?.actionRaw == WriteOutcome.SAVED) deps.repo.nicknameSaved(identity, nickname, host)
            if (outcome?.actionRaw == WriteOutcome.RETRY_AFTER && !rejected) gate.start(outcome.disableMs ?: DEFAULT_DISABLE)
            if (outcome?.actionRaw == WriteOutcome.FORCE_UPDATE) deps.env.forceUpdate()
            if (left) return@launch
            settle(outcome, result)
        }
        return true
    }

    /** 保存成功的出口（设置页关这个子页并提示，由页面接上）。 */
    var onSaved: () -> Unit = {}

    private fun settle(
        outcome: WriteOutcome?,
        result: ApiResult,
    ) {
        val now = mutable.value.copy(saving = false)
        mutable.value =
            when (outcome?.actionRaw) {
                null -> {
                    now
                }

                WriteOutcome.SAVED -> {
                    now.also { onSaved() }
                }

                WriteOutcome.FIELD_ERROR -> {
                    now.copy(fieldError = true, error = MeNotice(outcome.textKey.orEmpty(), ++ids))
                }

                WriteOutcome.FORCE_UPDATE, WriteOutcome.RISK_NOTICE -> {
                    now
                }

                WriteOutcome.ERROR_TRACE6 -> {
                    val trace = (result as? ApiResult.Failure)?.traceId
                    now.copy(error = MeNotice(outcome.textKey.orEmpty(), ++ids, trace, showTrace6 = true))
                }

                else -> {
                    now.copy(error = MeNotice(outcome.textKey.orEmpty(), ++ids, serverMsg = (result as? ApiResult.Failure)?.msg))
                }
            }
    }

    fun leave() {
        left = true
        // 昵称草稿不残留（撤回同意、换代、出栈，brief §0 第 1 条）
        mutable.value = NicknameState(input = "")
    }

    companion object {
        const val RATE_KEY = "error.42901"
        private const val DEFAULT_DISABLE = 5_000L
    }
}

@Composable
internal fun NicknamePage(
    deps: MeDeps,
    controller: NicknameController,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    controller.onSaved = onSaved
    BackHandler(onBack = onBack)
    val texts = deps.texts
    val state by controller.state.collectAsStateWithLifecycle()
    val blocked by deps.gates.nickname.blocked
        .collectAsStateWithLifecycle()
    val check = controller.check(state.input)
    val errorFocus = remember { FocusRequester() }
    MeSubPage(tag = MeTags.NICKNAME_PAGE, title = texts.text("nickname.title"), texts = texts, onBack = onBack) {
        val error = state.error?.message(texts)
        Column(
            modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = controller::onInput,
                label = { Text(texts.text("nickname.label")) },
                singleLine = true,
                isError = state.fieldError,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CouliColors.brandPrimary),
                trailingIcon = {
                    if (state.input.isNotEmpty()) {
                        CouliIconButton(
                            icon = CouliIcons.close,
                            label = texts.text("nickname.clear"),
                            onClick = controller::clear,
                            tint = CouliColors.textSecondary,
                            iconSize = CouliSizes.iconSmall,
                            modifier = Modifier.testTag(MeTags.NICKNAME_CLEAR),
                        )
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { if (state.fieldError && error != null) error(error) }
                        .testTag(MeTags.NICKNAME_INPUT),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hint =
                    when {
                        check.length > NicknameInput.MAX -> texts.text("nickname.too_long")
                        check.length == 0 && state.input.isNotEmpty() -> texts.text("nickname.empty")
                        else -> null
                    }
                Text(
                    text = hint.orEmpty(),
                    style = CouliTypography.caption,
                    color = CouliColors.statusErrorText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = texts.format("nickname.counter", mapOf("n" to check.length.toString())),
                    style = CouliTypography.caption,
                    color = if (check.length > NicknameInput.MAX) CouliColors.statusErrorText else CouliColors.textSecondary,
                    modifier = Modifier.testTag(MeTags.NICKNAME_COUNTER),
                )
            }
            if (error != null) {
                ErrorFocusText(error, state.error?.id, errorFocus, MeTags.NICKNAME_ERROR)
            }
        }
        Text(text = texts.text("nickname.hint"), style = CouliTypography.caption, color = CouliColors.textSecondary)
        CouliButton(
            text = texts.text("nickname.save"),
            onClick = { controller.save() },
            enabled = check.saveEnabled && !blocked && controller.editable,
            loading = state.saving,
            modifier = Modifier.fillMaxWidth().testTag(MeTags.NICKNAME_SAVE),
        )
    }
}

/** 通知设置子页的状态。[service] 为 null = 还没读到（或 Release 不显示开关行）。 */
data class NotifyState(
    val system: String,
    val service: Boolean? = null,
    val switching: Boolean = false,
    val toast: MeNotice? = null,
    /** 开关值读取失败（统一错误文案 + trace6，受读取闸门控制的【重试】，R1-04 / 07 同类）。 */
    val loadError: MeNotice? = null,
)

/**
 * 通知设置（设置页的页内子页，G02、G32；设计稿 NotifySettings、NotifySettingsOn；BR-WATCH-19 ③ 只开放服务类开关）：
 * - 系统通知状态只查不申请（PermissionRequester 的状态查询）：未开启 → 提示条 perm.push.denied_forever +【去设置】（跳系统设置，用户主动，不受 48 小时限制）；
 *   回前台重新查询；
 * - 「服务类推送」开关：Debug 走待定 operation（[NotifyBackend]），Release 不显示开关行；切换失败回到切换前的值并提示；通知开关 42901 执行层 + 页面。
 */
class NotifySettingsController(
    private val deps: MeDeps,
    val identity: MeIdentity,
) {
    private var left = false
    private var ids = 0L
    private var seq = 0L
    private val mutable = MutableStateFlow(NotifyState(deps.env.notificationStatus()))
    val state: StateFlow<NotifyState> = mutable.asStateFlow()

    val available: Boolean get() = deps.notify != null

    fun alive(): Boolean = !left && identity.current(deps.env)

    /** 回到本页、回前台：重新查系统通知状态。 */
    fun recheck() {
        if (alive()) mutable.value = mutable.value.copy(system = deps.env.notificationStatus())
    }

    /**
     * 读开关值（也是失败后的【重试】）：走共用读取闸门（禁用期内不发，执行层 + 页面）；失败按统一错误处理先处理本响应的 42901 / 10405，再显示错误与重试，
     * 不当成「没有开关」（R1-04 / 07 同类）。
     */
    @Suppress("ReturnCount")
    fun load() {
        val backend = deps.notify ?: return
        val account = identity.account ?: return
        if (!alive()) return
        val gate = deps.gates.read
        if (gate.active) {
            mutable.value = mutable.value.copy(loadError = MeNotice(RATE_KEY, ++ids))
            return
        }
        val request = ++seq
        mutable.value = mutable.value.copy(loadError = null)
        val probe = gate.probe()
        deps.service.launch {
            val result = backend.get(account) { probe.allowed() && identity.current(deps.env) && !left && seq == request }
            if (!alive() || seq != request) return@launch
            val service = ((result as? ApiResult.Success)?.data as? JsonObject)?.get(SERVICE) as? JsonPrimitive
            val value = service?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            if (value != null) {
                mutable.value = mutable.value.copy(service = value, loadError = null)
                return@launch
            }
            val notice = MeReadFailure.handle(result, probe, gate, deps.env) { ++ids } ?: return@launch
            mutable.value = mutable.value.copy(service = null, loadError = notice)
        }
    }

    /** 开关（渲染时的执行器与值）：失败回到切换前的值并提示。 */
    @Suppress("ReturnCount")
    fun toggle(to: Boolean) {
        val backend = deps.notify ?: return
        val account = identity.account ?: return
        val before = mutable.value.service ?: return
        if (!alive() || mutable.value.switching || before == to) return
        val gate = deps.gates.notify
        if (gate.active) {
            mutable.value = mutable.value.copy(toast = MeNotice(RATE_KEY, ++ids))
            return
        }
        seq += 1
        mutable.value = mutable.value.copy(service = to, switching = true)
        val probe = gate.probe()
        deps.service.launch {
            val result = backend.put(account, to) { probe.allowed() && identity.current(deps.env) }
            if (result is ApiResult.Failure) {
                val error = MeErrors.of(result)
                if (error.kind == WalletError.RETRY_AFTER) gate.start(error.disableMillis)
                if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
            }
            if (!alive()) return@launch
            if (result is ApiResult.Success) {
                // 写成功的结算先推进读取世代（R2-02 同类，共性第 29 条）：切换之前发起的开关读取不再覆盖新值
                seq += 1
                mutable.value = mutable.value.copy(switching = false)
                return@launch
            }
            val key = if (probe.rejected || (result as? ApiResult.Failure)?.code == RATE_CODE) RATE_KEY else FAILED_KEY
            mutable.value = mutable.value.copy(service = before, switching = false, toast = MeNotice(key, ++ids))
        }
    }

    fun goSettings() {
        if (alive()) deps.env.openNotificationSettings()
    }

    fun consumeToast(id: Long) {
        if (mutable.value.toast?.id == id) mutable.value = mutable.value.copy(toast = null)
    }

    fun leave() {
        left = true
        seq += 1
        mutable.value = NotifyState(system = mutable.value.system)
    }

    companion object {
        const val SERVICE = "service"
        const val RATE_KEY = "error.42901"
        const val FAILED_KEY = "notify.switch_failed"
        private const val RATE_CODE = 42901
    }
}

@Composable
internal fun NotifySettingsPage(
    deps: MeDeps,
    controller: NotifySettingsController,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val texts = deps.texts
    val state by controller.state.collectAsStateWithLifecycle()
    val blocked by deps.gates.notify.blocked
        .collectAsStateWithLifecycle()
    val readBlocked by deps.gates.read.blocked
        .collectAsStateWithLifecycle()
    OnResumed(controller) { controller.recheck() }
    MeSubPage(
        tag = MeTags.NOTIFY_PAGE,
        title = texts.text("notify.title"),
        texts = texts,
        onBack = onBack,
        overlay = { MeToastHost(state.toast?.message(texts), state.toast?.id) { state.toast?.id?.let(controller::consumeToast) } },
    ) {
        if (state.system != STATUS_GRANTED) {
            NoteStrip(
                text = texts.text("perm.push.denied_forever"),
                modifier = Modifier.testTag(MeTags.NOTIFY_BAR),
                icon = CouliIcons.bell,
            ) {
                CouliButton(
                    text = texts.text("perm.go_settings"),
                    onClick = controller::goSettings,
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.testTag(MeTags.NOTIFY_GO_SETTINGS),
                )
            }
        }
        val service = state.service
        if (controller.available && service != null) {
            RowCard(
                listOf {
                    SwitchRow(
                        title = texts.text("notify.service"),
                        desc = texts.text("notify.service_desc"),
                        checked = service,
                        texts = texts,
                        tag = MeTags.NOTIFY_SWITCH,
                        enabled = !state.switching && !blocked,
                        onChange = controller::toggle,
                    )
                },
            )
        } else if (controller.available && state.loadError != null) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(MeTags.NOTIFY_ERROR),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                Text(
                    text = state.loadError?.message(texts).orEmpty(),
                    style = CouliTypography.footnote,
                    color = CouliColors.statusErrorText,
                )
                CouliButton(
                    text = texts.text("me.retry"),
                    onClick = controller::load,
                    variant = CouliButtonVariant.SECONDARY,
                    enabled = !readBlocked,
                    modifier = Modifier.testTag(MeTags.NOTIFY_RETRY),
                )
            }
        } else {
            // Release（G02）：不显示开关行（不画成置灰），只留说明句
            Text(text = texts.text("notify.service_desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        }
    }
}

private const val STATUS_GRANTED = "granted"
