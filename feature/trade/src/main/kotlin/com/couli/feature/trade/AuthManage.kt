// 错误码是契约取值（contracts/error-codes.yaml），按码直接写
@file:Suppress("MagicNumber")

package com.couli.feature.trade

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorAnnouncement
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.network.RetryAfterGate
import com.couli.core.network.percentEncode
import com.couli.core.tradejump.BaichuanAdapter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 授权管理的一行（BR-ID-17 细则「授权管理页」）。 */
data class AuthManageRow(
    val platform: String,
    /** 只有两种文案：active 已授权，其余未授权（失效不另写「已失效」）。 */
    val authorized: Boolean,
    /** blocked 且用户未被封禁：不给【去授权】，行下方 error.30153 +【联系客服】（AuthManageBlocked）。 */
    val blocked: Boolean,
) {
    /** 【去授权】只给未授权、未停用的行。 */
    val canAuthorize: Boolean get() = !authorized && !blocked
}

/** 授权管理页状态。 */
data class AuthManageState(
    val phase: String = PHASE_LOADING,
    val rows: List<AuthManageRow> = emptyList(),
    val busyPlatform: String? = null,
    /** 列表加载失败的文案键（按实际错误映射，A-R3-04）：42901 → error.42901，5xxxx → 对应错误，传输失败 → 网络错误。 */
    val errorKey: String? = null,
    /** 列表加载失败要展示的 trace（只有 5xxxx 带 trace6；其余为 null）。 */
    val errorTraceId: String? = null,
    val toast: PurchaseToast? = null,
    /** 42901 限时禁用（Retry-After，缺省 5 秒）：列表【重试】与【去授权】都不可点，到期自动恢复（03 §4.2，A-R1-11）。 */
    val rateLimited: Boolean = false,
) {
    companion object {
        const val PHASE_LOADING = "loading"
        const val PHASE_CONTENT = "content"
        const val PHASE_ERROR = "error"
    }
}

/**
 * 授权管理执行器（APP-06b；BR-ID-17 细则「授权管理页」、BR-ID-22，routes.json AuthManage）：
 * - 只列淘宝、拼多多（京东不需要用户授权，不列）；listUnionBindings 的 active → 已授权；unbound / pending_auth / invalid / released → 未授权
 *   +【去授权】；blocked → 未授权、不给【去授权】，显示 error.30153 +【联系客服】；
 * - 【去授权】只能由用户在 App 内点击发起：getUnionAuthUrl → 淘宝按 auth_methods 取本端能执行的第一种（bindings 每次新键；30104 按 reason
 *   重新取授权上下文并换方式，[TaobaoAuthFlow]，A-R2-05），拼多多执行 auth_jump（不上报 link_jump），回到前台后刷新本页（查询失败进错误态、
 *   带 trace6 与 42901 禁用，不当成未授权）；本页发起的授权不重放任何 open；
 * - auth-url 返回 30153 → 该行改为停用（只给联系客服）；30101 / 30102 auth_unavailable → 只提示；
 * - 不提供改绑、解绑；不显示任何账号名、昵称、头像或授权时间。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 依赖逐项注入；加载、授权与生命周期各一个入口
class AuthManageController(
    private val api: PageApi,
    private val executor: TradeJumpExecutor,
    private val ports: JumpPorts,
    private val webCode: WebCodeAuthorizer,
    private val baichuan: BaichuanAdapter,
    private val scope: CoroutineScope,
    private val keyGenerator: () -> String = { UUID.randomUUID().toString() },
    clock: () -> Long = System::currentTimeMillis,
) {
    private val gate = RetryAfterGate(clock)
    private var gateTimer: Job? = null
    private val mutableState = MutableStateFlow(AuthManageState())
    val state: StateFlow<AuthManageState> = mutableState.asStateFlow()
    private var left = false
    private var generation = 0L
    private var awaitingReturn = false
    private var toastSeq = 0L
    private val owners = mutableListOf<CallOwnership>()

    /** 页面在前台（ON_START 之后、ON_STOP 之前）。 */
    private var foreground = true

    /**
     * 授权外跳许可（APP-06b 共性第 25、31 条）：用户点【去授权】时发一张；进入后台、被覆盖、离开都作废，回到前台也不恢复。取到授权地址后、
     * 授权跳转每一步、30104 恢复后再次拉起授权之前都复核它与前台状态。
     */
    private var permit: Permit? = null

    /** 授权跳转已发起、拉起结果还没回来（拉起的回调可能晚于回前台通知，共性第 38 条）。 */
    private var jumpInFlight = false

    /** 授权跳转发起之后进过后台（已离开过 App）。 */
    private var leftApp = false

    private class Permit {
        var valid = true
    }

    fun load() {
        // 42901 禁用期间执行层直接拦下（不只靠按钮置灰）
        if (left || gate.active) return
        generation += 1
        val gen = generation
        mutableState.value = mutableState.value.copy(phase = AuthManageState.PHASE_LOADING)
        scope.launch {
            val result = api.send(ApiCall(TradeOperations.LIST_UNION_BINDINGS, ownership = owner()))
            if (left || gen != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val items = ((result.data as? JsonObject)?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                    mutableState.value = mutableState.value.copy(phase = AuthManageState.PHASE_CONTENT, rows = rows(items))
                }

                is ApiResult.Failure -> {
                    // 按实际错误码选文案（A-R3-04）：42901 用 error.42901 并按 Retry-After 禁用；5xxxx 用对应文案并带 trace6
                    val key = OpenErrorAction.map(result.code, result.data).textKey ?: GENERIC_KEY
                    val trace = result.traceId.takeIf { result.code in SERVER_ERRORS }
                    mutableState.value = mutableState.value.copy(phase = AuthManageState.PHASE_ERROR, errorKey = key, errorTraceId = trace)
                    rateLimit(result)
                }

                else -> {
                    // 传输失败（断网、超时）：网络错误文案，不冒充服务端错误
                    mutableState.value =
                        mutableState.value.copy(phase = AuthManageState.PHASE_ERROR, errorKey = NETWORK_KEY, errorTraceId = null)
                }
            }
        }
    }

    /** 用户点【去授权】。不在前台不发起；这一下发授权外跳许可（共性第 25 条）。 */
    @Suppress("ReturnCount", "ComplexCondition") // 没有这一行、限流、不可授权（离开、后台、不可授权、忙）逐项短路
    fun authorize(platform: String) {
        val row = mutableState.value.rows.firstOrNull { it.platform == platform } ?: return
        // 42901 禁用期间执行层直接拦下
        if (gate.active) return
        if (left || !foreground || !row.canAuthorize || mutableState.value.busyPlatform != null) return
        mutableState.value = mutableState.value.copy(busyPlatform = platform)
        val granted = Permit().also { permit?.valid = false }
        permit = granted
        jumpInFlight = false
        leftApp = false
        val installed = ports.installed(platform)
        scope.launch {
            val result =
                api.send(
                    ApiCall(
                        TradeOperations.GET_UNION_AUTH_URL,
                        pathParams = mapOf("platform" to platform),
                        query = "installed=${percentEncode(installed)}",
                        ownership = owner(),
                    ),
                )
            if (left) return@launch
            when (result) {
                is ApiResult.Success -> run(platform, result.data as? JsonObject ?: JsonObject(emptyMap()), granted)
                is ApiResult.Failure -> failed(platform, result)
                else -> done(NETWORK_KEY)
            }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition", "LongMethod") // 拼多多跳转、淘宝两种方式与本端能力逐项判定
    private suspend fun run(
        platform: String,
        data: JsonObject,
        granted: Permit,
    ) {
        // 取授权地址期间进入后台、被覆盖（许可已撤销）：不从后台拉起授权，按钮复原，等用户在前台再点（共性第 25 条）
        if (!jumpAllowed(granted)) {
            mutableState.value = mutableState.value.copy(busyPlatform = null)
            return
        }
        val authJump = data["auth_jump"] as? JsonObject
        if (authJump != null) {
            // 发起前就登记「外跳在途」：拉起的回调可能晚于回前台通知（共性第 38 条）
            jumpInFlight = true
            val outcome =
                executor.execute(
                    authJump,
                    platform,
                    ports.installed(platform),
                    identity = null,
                    allowed = { jumpAllowed(granted) },
                    confirmBrowser = { true },
                )
            jumpInFlight = false
            if (left) return
            val launched = outcome.run.final == JumpTypes.FINAL_OPENED || outcome.run.final == JumpTypes.FINAL_BROWSER
            if (launched || outcome.stopped) {
                // 已拉起，或期间进入后台而停在半路：回到前台后刷新本页（服务端复查）；拉起结果晚于回前台通知时此刻就刷新
                mutableState.value = mutableState.value.copy(busyPlatform = null)
                awaitReturn(returned = !launched || leftApp)
            } else {
                done(AUTH_FAILED_KEY)
            }
            return
        }
        // 淘宝：含 30104 按 reason 的恢复（重新取授权上下文、换方式、新凭证与新幂等键，A-R2-05）；本页发起的授权不重放任何 open。
        // 每次拉起授权之前复核用户点击发出的许可与前台状态（共性第 25、31 条）
        val flow =
            TaobaoAuthFlow(
                api,
                webCode,
                baichuan,
                keyGenerator,
                ::owner,
                alive = { !left },
                installed = ports::installed,
                canJump = { jumpAllowed(granted) },
            )
        if (flow.pick(platform, data) == null) {
            // 本端执行不了下发的任何方式（TODO(N-28)：web_code 回跳未登记、百川不可用）
            done(AUTH_FAILED_KEY)
            return
        }
        when (val outcome = flow.run(platform, data)) {
            // 绑定成功：刷新本页，不重放任何 open（BR-ID-17 细则）
            TaobaoAuthOutcome.Bound -> {
                mutableState.value = mutableState.value.copy(busyPlatform = null)
                load()
            }

            TaobaoAuthOutcome.Cancelled -> {
                mutableState.value = mutableState.value.copy(busyPlatform = null)
            }

            TaobaoAuthOutcome.Failed -> {
                done(AUTH_FAILED_KEY)
            }

            is TaobaoAuthOutcome.RequestFailed -> {
                (outcome.result as? ApiResult.Failure)?.let { failed(platform, it) } ?: done(NETWORK_KEY)
            }

            TaobaoAuthOutcome.Interrupted -> {
                // 30104 恢复后要再次拉起时许可已撤销（进过后台）：不再自动拉起；回到前台刷新本页，没授权由用户再点
                mutableState.value = mutableState.value.copy(busyPlatform = null)
                awaitReturn(returned = true)
            }

            TaobaoAuthOutcome.Abandoned -> {
                if (!left) mutableState.value = mutableState.value.copy(busyPlatform = null)
            }
        }
    }

    /** 授权外跳此刻可以继续：许可仍有效、页面没离开且在前台。 */
    private fun jumpAllowed(granted: Permit): Boolean = granted.valid && permit === granted && !left && foreground

    /** 授权外跳之后等回流：[returned] 且此刻在前台就立即刷新，否则等 [onForeground]。 */
    private fun awaitReturn(returned: Boolean) {
        if (returned && foreground) {
            awaitingReturn = false
            leftApp = false
            load()
        } else {
            awaitingReturn = true
        }
    }

    private fun failed(
        platform: String,
        failure: ApiResult.Failure,
    ) {
        val outcome = OpenErrorAction.map(failure.code, failure.data)
        rateLimit(failure)
        if (failure.code == 30153) {
            // 授权管理页的 30153：只给联系客服（error-codes 30153）
            mutableState.value =
                mutableState.value.copy(
                    busyPlatform = null,
                    rows = mutableState.value.rows.map { if (it.platform == platform) it.copy(authorized = false, blocked = true) else it },
                )
            return
        }
        done(outcome.textKey ?: GENERIC_KEY, failure.traceId.takeIf { outcome.showTrace6 })
    }

    /** 回到前台：拼多多授权跳转之后刷新本页（服务端按 authority.query 复查）。 */
    fun onForeground() {
        if (left) return
        foreground = true
        if (!awaitingReturn) return
        awaitingReturn = false
        leftApp = false
        load()
    }

    /** 被覆盖或进入后台（ON_STOP）：作废授权外跳许可（共性第 25、31 条）；授权跳转在途或等回流时记下「离开过 App」（共性第 38 条）。 */
    fun onBackground() {
        foreground = false
        permit?.valid = false
        permit = null
        if (jumpInFlight || awaitingReturn) leftApp = true
    }

    fun leave() {
        left = true
        permit?.valid = false
        permit = null
        gateTimer?.cancel()
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    fun consumeToast() {
        mutableState.value = mutableState.value.copy(toast = null)
    }

    private fun done(
        key: String,
        traceId: String? = null,
    ) {
        toastSeq += 1
        mutableState.value = mutableState.value.copy(busyPlatform = null, toast = PurchaseToast(key, traceId, toastSeq))
    }

    private fun owner(): CallOwnership = CallOwnership(api.epochs()) { !left }.also { owners += it }

    /** 42901：按 Retry-After（缺省 5 秒）禁用加载与授权，到期自动恢复（A-R1-11）。 */
    private fun rateLimit(failure: ApiResult.Failure) {
        val millis = ResultSignals.disableMillis(failure)
        if (millis <= 0) return
        gate.start(millis)
        mutableState.value = mutableState.value.copy(rateLimited = true)
        gateTimer?.cancel()
        gateTimer =
            scope.launch {
                while (gate.active) delay(gate.remainingMillis())
                if (!left) mutableState.value = mutableState.value.copy(rateLimited = false)
            }
    }

    companion object {
        /** 授权管理页列出的平台（需要用户授权的：淘宝、拼多多；京东不列，BR-ID-22）。 */
        val PLATFORMS = listOf("taobao", "pdd")
        private const val ACTIVE = "active"
        private const val BLOCKED = "blocked"
        const val GENERIC_KEY = "error.50001"
        const val AUTH_FAILED_KEY = "error.30104.credential_invalid"
        const val NETWORK_KEY = "buy.timeout"
        private val SERVER_ERRORS = 50_000..59_999

        /** 列表错误态的正文（A-R3-04）：按映射后的文案键（[AuthManageState.errorKey]）取字；只有 5xxxx 带 trace6。 */
        fun errorMessage(
            state: AuthManageState,
            texts: TradeTexts,
        ): String =
            ResultSignals.withTrace6(
                texts.format(state.errorKey ?: GENERIC_KEY, mapOf("trace6" to ResultSignals.trace6(state.errorTraceId))),
                state.errorTraceId,
            )

        /** listUnionBindings 的投影（向量 G11 未覆盖，本端单测覆盖）：没有返回的平台按未授权。 */
        fun rows(items: List<JsonObject>): List<AuthManageRow> =
            PLATFORMS.map { platform ->
                val status = items.firstOrNull { it.text("platform") == platform }?.text("status")
                AuthManageRow(platform, authorized = status == ACTIVE, blocked = status == BLOCKED)
            }
    }
}

/** 授权管理页配置（App 给）。 */
class AuthManageConfig(
    val controller: (scope: CoroutineScope) -> AuthManageController,
    val texts: TradeTexts,
    val onBack: () -> Unit,
    /** 【联系客服】：统一客服出口未接入前经 Router 打开帮助中心（TODO(N-28)）。 */
    val onContact: () -> Unit,
    val bindLeave: (leave: () -> Unit) -> Unit = {},
)

/** 授权管理 ViewModel：执行器随回退栈条目。 */
class AuthManageViewModel(
    config: AuthManageConfig,
) : ViewModel() {
    val controller = config.controller(viewModelScope)

    init {
        config.bindLeave(controller::leave)
        controller.load()
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }
}

/** 测试与冒烟用的标记。 */
object AuthManageTags {
    const val PAGE = "auth_manage_page"
    const val ROW_PREFIX = "auth_manage_row_"
    const val AUTHORIZE_PREFIX = "auth_manage_authorize_"
    const val CONTACT = "auth_manage_contact"
    const val RETRY = "auth_manage_retry"
}

private object AuthManageKeys {
    const val TITLE = "auth_manage.title"
    const val AUTHORIZED = "auth_manage.status.authorized"
    const val UNAUTHORIZED = "auth_manage.status.unauthorized"
    const val AUTHORIZE = "auth_manage.action.authorize"
    const val REBIND = "auth_manage.rebind_note"
    const val BLOCKED = "error.30153"
    const val CONTACT = "trade.contact"
    const val BACK = "product.back"
    const val LOADING = "search.loading"
    const val RETRY = "external_page.retry"
}

/** 授权管理（设计稿 AuthManage、AuthManageBlocked）。 */
@Composable
fun AuthManageScreen(config: AuthManageConfig) {
    val model: AuthManageViewModel = viewModel { AuthManageViewModel(config) }
    val controller = model.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val texts = config.texts
    BackHandler { config.onBack() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> controller.onForeground()
                    Lifecycle.Event.ON_STOP -> controller.onBackground()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(AuthManageTags.PAGE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH.dp).fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = CouliSpacing.space1), contentAlignment = Alignment.Center) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    CouliIconButton(icon = CouliIcons.back, label = texts.text(AuthManageKeys.BACK), onClick = config.onBack)
                }
                Text(
                    text = texts.text(AuthManageKeys.TITLE),
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                when (state.phase) {
                    AuthManageState.PHASE_LOADING -> {
                        Text(text = texts.text(AuthManageKeys.LOADING), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                    }

                    AuthManageState.PHASE_ERROR -> {
                        ErrorBlock(state, controller, texts)
                    }

                    else -> {
                        Column(
                            modifier =
                                Modifier.fillMaxWidth().background(
                                    CouliColors.backgroundSurface,
                                    RoundedCornerShape(CouliRadius.panel),
                                ),
                        ) {
                            state.rows.forEachIndexed { index, row ->
                                if (index > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = CouliSpacing.space4,
                                            ).heightIn(min = CouliSizes.hairline)
                                            .background(CouliColors.borderSubtle),
                                    )
                                }
                                RowItem(row, state.busyPlatform == row.platform, state.rateLimited, controller, config, texts)
                            }
                        }
                        Text(text = texts.text(AuthManageKeys.REBIND), style = CouliTypography.caption, color = CouliColors.textSecondary)
                    }
                }
            }
        }
    }
    state.toast?.let { toast ->
        LaunchedEffect(toast.id) {
            delay(TOAST_MILLIS)
            controller.consumeToast()
        }
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            CouliToast(
                message = ResultSignals.withTrace6(texts.text(toast.key), toast.traceId),
                modifier = Modifier.padding(top = CouliSpacing.space12),
                icon = null,
                announcementId = toast.id,
            )
        }
    }
}

@Composable
private fun RowItem(
    row: AuthManageRow,
    busy: Boolean,
    rateLimited: Boolean,
    controller: AuthManageController,
    config: AuthManageConfig,
    texts: TradeTexts,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = CouliSpacing.space4,
                    vertical = CouliSpacing.space3,
                ).testTag(AuthManageTags.ROW_PREFIX + row.platform),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Text(
                text = texts.text(TradeKeys.platform(row.platform)),
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = texts.text(if (row.authorized) AuthManageKeys.AUTHORIZED else AuthManageKeys.UNAUTHORIZED),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
            )
            if (row.canAuthorize) {
                CouliButton(
                    text = texts.text(AuthManageKeys.AUTHORIZE),
                    onClick = { controller.authorize(row.platform) },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    loading = busy,
                    enabled = !rateLimited,
                    modifier = Modifier.testTag(AuthManageTags.AUTHORIZE_PREFIX + row.platform),
                )
            }
        }
        if (row.blocked) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Text(
                    text = texts.text(AuthManageKeys.BLOCKED),
                    style = CouliTypography.footnote,
                    color = CouliColors.statusErrorText,
                    modifier = Modifier.weight(1f),
                )
                CouliButton(
                    text = texts.text(AuthManageKeys.CONTACT),
                    onClick = config.onContact,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AuthManageTags.CONTACT),
                )
            }
        }
    }
}

@Composable
private fun ErrorBlock(
    state: AuthManageState,
    controller: AuthManageController,
    texts: TradeTexts,
) {
    val message = AuthManageController.errorMessage(state, texts)
    val focus = remember { FocusRequester() }
    ErrorAnnouncement(message, focus)
    Column(
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = message, style = CouliTypography.body, color = CouliColors.textPrimary)
        CouliButton(
            text = texts.text(AuthManageKeys.RETRY),
            onClick = controller::load,
            variant = CouliButtonVariant.SECONDARY,
            size = CouliButtonSize.COMPACT,
            enabled = !state.rateLimited,
            modifier = Modifier.couliFocusTarget(focus).testTag(AuthManageTags.RETRY),
        )
    }
}

private const val CONTENT_MAX_WIDTH = 600
private const val TOAST_MILLIS = 2_500L
