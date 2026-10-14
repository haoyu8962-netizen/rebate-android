package com.couli.feature.me

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeLevel
import com.couli.core.me.MePlatform
import com.couli.core.me.SettingsItems
import com.couli.core.me.SettingsModel
import com.couli.core.me.SettingsRow
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.WalletError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 设置页的页内状态：子页（修改昵称、通知设置）、半屏（一次性编号）、确认框（一次性编号，R1-02）、轻提示、缓存大小。 */
data class SettingsState(
    val sub: String = NONE,
    val sheet: Long? = null,
    val dialog: MeDialog? = null,
    val toast: MeNotice? = null,
    val cacheBytes: Long? = null,
    val busy: Boolean = false,
    val clearing: Boolean = false,
    val version: Long = 0,
) {
    companion object {
        const val NONE = ""
        const val NICKNAME = "nickname"
        const val NOTIFY = "notify"
        const val CLEAR = "clear"
        const val LOGOUT = "logout"
    }
}

/**
 * 设置页执行器（Settings 路由；brief §2.3，G01、G02、G07、G30、G32；BR-ID-02 / 16）。构造时固定身份（共性第 18 条：身份变了页面重建本执行器，子页随之关闭、输入清空）；
 * 行、子页、半屏、确认框的回调都捕获渲染时的执行器与版本，执行时核对身份与版本（鸿蒙 R2-02 / H22）：
 * - 条目只按 [SettingsItems]（基本模式五项、游客五项 + Android 剪贴板开关、已登录三组）；
 * - 修改昵称、通知设置是页内子页（不注册路由，G32），子页执行器只按已登录身份建；
 * - 「重新显示下单须知」调 resetTip jump_tip（42901 执行层 + 页面）；清除缓存先确认；【退出登录】先确认，确认才调已有 logout()。
 */
@Suppress("TooManyFunctions")
class SettingsController(
    private val deps: MeDeps,
    val identity: MeIdentity,
    private val entryId: String?,
) : MeReader {
    private var left = false
    private var ids = 0L
    private var dialogIds = 0L
    private val mutable = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = mutable.asStateFlow()
    private val s: SettingsState get() = mutable.value
    private var resetOwner: CallOwnership? = null

    /** 缓存大小读取的世代：清除缓存开始与完成时推进，之前发起的大小读取晚到不把旧大小写回（R2-02 同类，共性第 29 条）。 */
    private var cacheSeq = 0L

    /** 子页执行器（只在已登录身份下建；身份变了整页重建，旧子页随旧执行器作废）。 */
    var nickname: NicknameController? = null
        private set
    var notify: NotifySettingsController? = null
        private set

    fun alive(): Boolean = !left && identity.current(deps.env)

    /** 本页发起的 Me 读取仍有效（R1-03）：出栈、换代即停。 */
    override fun reading(): Boolean = alive()

    /** 渲染时的条目（只按本执行器固定的身份档）。 */
    fun model(): SettingsModel {
        val me = deps.repo.meOf(identity)
        return SettingsItems.model(
            levelRaw = identity.level,
            platformRaw = MePlatform.ANDROID,
            phoneBound = me?.phoneBound,
            nicknameEditable = deps.nickname != null && me != null,
            serviceSwitchAvailable = deps.notify != null,
            deleteAccountAvailable = DELETE_ACCOUNT in deps.env.availableRoutes(),
        )
    }

    /** 进入、回到本页：已登录时取 Me（昵称、手机号行）；重算缓存大小。 */
    fun refresh() {
        if (!alive()) return
        if (identity.member) deps.repo.refresh(identity, this)
        val mine = ++cacheSeq
        deps.service.launch {
            val bytes = withContext(Dispatchers.IO) { deps.env.cacheBytes() }
            if (alive() && cacheSeq == mine) update { it.copy(cacheBytes = bytes) }
        }
    }

    /** 账号资料加载失败的【重试】（R1-06）：受读取闸门控制（禁用期内执行层与页面都拦）。 */
    fun retryMe() {
        if (alive() && identity.member) deps.repo.refresh(identity, this)
    }

    private fun bound(version: Long): Boolean = alive() && s.version == version

    /** 一行被点（渲染时的行与版本）：每个分支前核对身份与页面世代（鸿蒙 R2-02）。 */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun onRow(
        row: SettingsRow,
        version: Long,
    ) {
        if (!bound(version) || s.busy) return
        val route = row.route
        when {
            row.id == SettingsItems.ID_NICKNAME -> {
                if (!row.clickable || !identity.member) return
                val current = deps.repo.meOf(identity)?.nickname ?: return
                nickname = NicknameController(deps, identity, current, host = this)
                update { it.copy(sub = SettingsState.NICKNAME, version = it.version + 1) }
            }

            row.id == SettingsItems.ID_NOTIFY -> {
                if (!identity.member) return
                notify = NotifySettingsController(deps, identity).also { it.load() }
                update { it.copy(sub = SettingsState.NOTIFY, version = it.version + 1) }
            }

            row.id == SettingsItems.ID_AGREEMENT -> {
                update { it.copy(sheet = ++dialogIds) }
            }

            row.id == SettingsItems.ID_RESET_TIP -> {
                resetTip()
            }

            row.id == SettingsItems.ID_CLEAR_CACHE -> {
                update { it.copy(dialog = MeDialog(SettingsState.CLEAR, ++dialogIds)) }
            }

            route != null && row.clickable -> {
                deps.env.open(route)
            }
        }
    }

    /** 协议半屏的一个按钮（G32：半屏两个按钮 → Agreement {type}）：只认正显示的这一个半屏（[sheet] 编号），用过即作废。 */
    fun openAgreement(
        params: JsonObject,
        version: Long,
        sheet: Long,
    ) {
        if (s.sheet != sheet) return
        update { it.copy(sheet = null) }
        if (!bound(version)) return
        deps.env.open(SettingsItems.AGREEMENT, params)
    }

    /** 关闭半屏（同一编号才生效）。 */
    fun closeSheet(sheet: Long) {
        if (s.sheet == sheet) update { it.copy(sheet = null) }
    }

    /** 取消确认框（【取消】、系统返回；同一编号才生效）。 */
    fun closeDialog(dialog: MeDialog) {
        if (s.dialog == dialog) update { it.copy(dialog = null) }
    }

    /** 消费一次确认（R1-02）：类型与编号都是正显示的这一个才放行，并立即作废。 */
    private fun consume(
        dialog: MeDialog,
        kind: String,
    ): Boolean {
        if (dialog.kind != kind || s.dialog != dialog) return false
        update { it.copy(dialog = null) }
        return true
    }

    /** 关掉子页（只关发起的这一个，H13）；子页的输入与读请求随执行器作废。 */
    fun closeSub(sub: NicknameController? = nickname) {
        if (sub != null && sub !== nickname) return
        nickname?.leave()
        notify?.leave()
        nickname = null
        notify = null
        update { it.copy(sub = SettingsState.NONE, version = it.version + 1) }
    }

    fun closeNotify(sub: NotifySettingsController?) {
        if (sub == null || sub !== notify) return
        closeSub(nickname)
    }

    /** 昵称保存成功：轻提示「昵称已修改」，只关发起的这个子页（APP-09 第 50 条）。 */
    internal fun nicknameSaved(sub: NicknameController) {
        if (sub !== nickname) return
        closeSub(sub)
        toast(MeNotice(SAVED_KEY, ++ids, assertive = false))
    }

    /** 底部【去登录】（游客）。 */
    fun login(version: Long) {
        if (bound(version) && identity.level == MeLevel.GUEST) deps.env.open(LOGIN)
    }

    /** 底部【开启完整功能】（基本模式）。 */
    fun enableFull(version: Long) {
        if (bound(version) && identity.level == MeLevel.BASIC) deps.env.requestFullFunction()
    }

    fun askLogout(version: Long) {
        if (bound(version) && identity.member && !s.busy) update { it.copy(dialog = MeDialog(SettingsState.LOGOUT, ++dialogIds)) }
    }

    /**
     * 【退出登录】确认（确认框生成时的编号与渲染时的版本）：核对身份、页面、弹窗类型与编号（R1-02），只调已有 logout()，落点由 App 按发起实例判断（R1-09）。
     */
    fun confirmLogout(
        dialog: MeDialog,
        version: Long,
    ) {
        if (!consume(dialog, SettingsState.LOGOUT)) return
        if (!bound(version) || !identity.member) return
        update { it.copy(busy = true) }
        deps.env.logout(identity) { !left }
    }

    /**
     * 清除缓存确认（确认框编号，R1-02）：只清图片与 WebView 缓存（AC-S1-137）——先在主线程清 WebView 的内存与磁盘资源缓存（R1-10），再在 IO 线程清
     * 缓存目录；全部完成后才提示「已清除」并重算大小，任一失败按失败提示。
     */
    fun confirmClear(
        dialog: MeDialog,
        version: Long,
    ) {
        if (!consume(dialog, SettingsState.CLEAR)) return
        if (!bound(version)) return
        update { it.copy(clearing = true) }
        cacheSeq += 1
        deps.service.launch {
            val web = runCatching { deps.env.clearWebCache() }.getOrDefault(false)
            val disk = withContext(Dispatchers.IO) { deps.env.clearCache() }
            val ok = web && disk
            val bytes = withContext(Dispatchers.IO) { deps.env.cacheBytes() }
            if (!alive()) return@launch
            // 清除完成：推进大小读取的世代，清除期间发起的大小读取（可能读到清除前的大小）不再写回
            cacheSeq += 1
            update { it.copy(cacheBytes = bytes, clearing = false) }
            toast(MeNotice(if (ok) CLEARED_KEY else "error.50001", ++ids, assertive = !ok))
        }
    }

    /** 「重新显示下单须知」：DELETE /v1/me/tips/jump_tip（gate true、x-error-codes 20001 / 10405）；42901 执行层与页面都拦。 */
    @Suppress("ReturnCount")
    private fun resetTip() {
        if (!identity.member) return
        val gate = deps.gates.resetTip
        if (gate.active) {
            toast(MeNotice(RATE_KEY, ++ids))
            return
        }
        val probe = gate.probe()
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) { identity.current(deps.env) }
        resetOwner = owner
        update { it.copy(busy = true) }
        deps.service.launch {
            val result =
                deps.api.send(
                    ApiCall(RESET_TIP, pathParams = mapOf("tip_key" to JUMP_TIP), ownership = owner, expectedAccount = identity.account),
                )
            if (resetOwner === owner) resetOwner = null
            // 写请求离开页面不取消，但响应回来不得改动已出栈的页面
            if (result is ApiResult.Success && identity.current(deps.env)) deps.env.tipsReset()
            if (!alive()) return@launch
            update { it.copy(busy = false) }
            if (result is ApiResult.Success) {
                toast(MeNotice(RESET_DONE_KEY, ++ids, assertive = false))
                return@launch
            }
            val error = MeErrors.of(result)
            when {
                error.kind == WalletError.CANCELLED -> {
                    if (probe.gated(error)) toast(MeNotice(RATE_KEY, ++ids))
                }

                error.kind == WalletError.RETRY_AFTER -> {
                    gate.start(error.disableMillis)
                    toast(MeNotice(RATE_KEY, ++ids))
                }

                error.kind == WalletError.FORCE_UPDATE -> {
                    deps.env.forceUpdate()
                }

                else -> {
                    toast(MeErrors.notice(error, ++ids))
                }
            }
        }
    }

    private fun toast(notice: MeNotice) = update { it.copy(toast = notice) }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    /** 出栈、换代、撤回的同步出口：撤销本页发起的 Me 读取（R1-03），清空子页草稿。配置重建不经过这里（执行器保存在回退栈条目的 ViewModel，R1-08）。 */
    fun leave() {
        if (left) return
        left = true
        deps.repo.release(this)
        nickname?.leave()
        notify?.leave()
        nickname = null
        notify = null
    }

    private inline fun update(change: (SettingsState) -> SettingsState) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val ROUTE = "Settings"
        const val LOGIN = "Login"
        const val DELETE_ACCOUNT = "DeleteAccount"
        const val RESET_TIP = "resetTip"
        const val JUMP_TIP = "jump_tip"
        const val RATE_KEY = "error.42901"
        const val SAVED_KEY = "nickname.saved"
        const val CLEARED_KEY = "settings.clear_cache.done"
        const val RESET_DONE_KEY = "settings.reset_tip.done"
    }
}

/** 设置页配置（App 给）。[clipboardRow]：Android「打开 App 自动识别剪贴板」一行（APP-07 原样挪进「通用」组；只在已同意时组合，基本模式不显示）。 */
class SettingsConfig(
    val deps: MeDeps,
    val entryId: String?,
    val onBack: () -> Unit,
    val clipboardRow: (@Composable () -> Unit)? = null,
    val bindLeave: (() -> Unit) -> Unit = {},
    /**
     * APP-10 G18（鸿蒙 H07）：【撤回 AI 服务同意】一行（App 给；只在 Agent 可用的构建与环境、已同意时显示，Release 为 null）。设置条目表受 me-vectors 锁定，
     * 不改条目表：分组卡之后单独一张卡一行。
     */
    val agentRow: (@Composable () -> Unit)? = null,
)

/**
 * 设置（设计稿 Settings、SettingsGuest、SettingsAgreementSheet、SettingsClearCache、SettingsCacheCleared、SettingsLogoutConfirm、SettingsNickname*、
 * NotifySettings*）：分组小标题 + 白卡片行；基本模式五项 +【开启完整功能】；游客底部「登录后可以管理账号、授权和通知」+【去登录】；已登录底部【退出登录】。
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun SettingsScreen(config: SettingsConfig) {
    val deps = config.deps
    val identity by deps.env.identityFlow.collectAsStateWithLifecycle()
    // 执行器保存在回退栈条目的 ViewModel（R1-08）：配置重建（折叠、窗口变化）不清子页与昵称草稿；真正出栈、换身份才作废
    val controller =
        rememberPageController(SettingsController.ROUTE, identity, { SettingsController(deps, identity, config.entryId) }) { it.leave() }
    DisposableEffect(controller) {
        config.bindLeave(controller::leave)
        onDispose { }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val snapshot by deps.repo.state.collectAsStateWithLifecycle()
    val readBlocked by deps.gates.read.blocked
        .collectAsStateWithLifecycle()
    OnResumed(controller) { controller.refresh() }
    val texts = deps.texts
    // 子页（G32）：系统返回先关子页
    when (state.sub) {
        SettingsState.NICKNAME -> {
            controller.nickname?.let { sub ->
                NicknamePage(deps, sub, onBack = { controller.closeSub(sub) }) { controller.nicknameSaved(sub) }
                return
            }
        }

        SettingsState.NOTIFY -> {
            controller.notify?.let { sub ->
                NotifySettingsPage(deps, sub) { controller.closeNotify(sub) }
                return
            }
        }
    }
    val version = state.version
    val model = remember(controller, snapshot, version) { controller.model() }
    MeSubPage(
        tag = MeTags.SETTINGS_PAGE,
        title = texts.text("settings.title"),
        texts = texts,
        onBack = config.onBack,
        overlay = {
            MeToastHost(state.toast?.message(texts), state.toast?.id) { state.toast?.id?.let(controller::consumeToast) }
            // 已有资料时刷新失败：保留资料，提示一次（统一错误文案 + trace6，R1-06）
            val refreshError = snapshot.error?.takeIf { snapshot.me != null && snapshot.identity == controller.identity }
            if (state.toast ==
                null
            ) {
                MeToastHost(refreshError?.message(texts), refreshError?.id) { refreshError?.id?.let(deps.repo::consumeError) }
            }
        },
    ) {
        if (controller.identity.member) AccountStatus(deps, controller, snapshot, readBlocked)
        model.groups.forEach { group ->
            if (group.name.isNotEmpty()) MeDraftTexts.settingsKey(group.name)?.let { GroupLabel(texts.text(it)) }
            RowCard(
                group.rows.mapNotNull { row ->
                    if (row.id == SettingsItems.ID_CLIPBOARD) {
                        config.clipboardRow
                    } else {
                        { SettingsRowView(deps, controller, row, version, state) }
                    }
                },
            )
        }
        if (identityAllowsAgentRow(controller.identity.level)) config.agentRow?.let { row -> RowCard(listOf(row)) }
        when (model.footer) {
            SettingsItems.FOOTER_LOGOUT -> {
                CouliButton(
                    text = texts.text("settings.logout"),
                    onClick = { controller.askLogout(version) },
                    enabled = !state.busy,
                    variant = CouliButtonVariant.SECONDARY,
                    loading = state.busy && state.dialog == null && state.sub == SettingsState.NONE,
                    modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space3).testTag(MeTags.SETTINGS_LOGOUT),
                )
            }

            SettingsItems.FOOTER_LOGIN -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space3).testTag(MeTags.SETTINGS_FOOTER),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                ) {
                    Text(text = texts.text("settings.guest_footer"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                    CouliButton(
                        text = texts.text("settings.go_login"),
                        onClick = { controller.login(version) },
                        modifier = Modifier.fillMaxWidth().testTag(MeTags.SETTINGS_LOGIN),
                    )
                }
            }

            else -> {
                CouliButton(
                    text = texts.text("privacy.basic_mode.enable"),
                    onClick = { controller.enableFull(version) },
                    modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space3).testTag(MeTags.SETTINGS_ENABLE_FULL),
                )
            }
        }
    }
    state.sheet?.let { sheet -> AgreementSheet(deps, controller, model, version, sheet) }
    // 确认框的回调捕获这一个确认框的编号（R1-02）：取消、确认之后旧闭包一律不生效
    val dialog = state.dialog
    when (dialog?.kind) {
        SettingsState.CLEAR -> {
            CouliDialog(
                title = texts.text("settings.clear_cache"),
                message = texts.text("settings.clear_cache.confirm"),
                confirmText = texts.text("settings.clear_cache.confirm_ok"),
                onConfirm = { controller.confirmClear(dialog, version) },
                dismissText = texts.text("settings.clear_cache.confirm_cancel"),
                onDismissRequest = { controller.closeDialog(dialog) },
                confirmTag = MeTags.CLEAR_DIALOG,
            )
        }

        SettingsState.LOGOUT -> {
            // 退出确认（SettingsLogoutConfirm）：点遮罩不执行、系统返回 = 取消（APP-09 第 48 条）
            CouliDialog(
                title = texts.text("settings.logout.confirm"),
                confirmText = texts.text("settings.logout.confirm_ok"),
                onConfirm = { controller.confirmLogout(dialog, version) },
                dismissText = texts.text("settings.logout.confirm_cancel"),
                onDismissRequest = { controller.closeDialog(dialog) },
                dismissOnClickOutside = false,
                confirmTag = MeTags.LOGOUT_DIALOG,
            )
        }
    }
}

/**
 * 账号资料区的加载状态（R1-06）：已登录且还没有本身份的 Me 快照时，加载中显示「加载中」，失败显示统一错误文案（5xxxx 带 trace6）与受读取闸门控制的【重试】；
 * 公共设置入口照常显示在下面。
 */
@Composable
private fun AccountStatus(
    deps: MeDeps,
    controller: SettingsController,
    snapshot: MeSnapshot,
    blocked: Boolean,
) {
    if (snapshot.identity != controller.identity || snapshot.me != null) return
    val texts = deps.texts
    when (snapshot.phase) {
        MeSnapshot.ERROR -> {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth().testTag(MeTags.SETTINGS_ME_ERROR)) {
                com.couli.core.designsystem.ErrorState(
                    title = texts.text("me.load_failed"),
                    message = snapshot.error?.message(texts),
                    retry =
                        com.couli.core.designsystem
                            .StateAction(texts.text("me.retry"), enabled = !blocked) { controller.retryMe() },
                )
            }
        }

        MeSnapshot.LOADING -> {
            Text(
                text = texts.text("me.loading"),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(MeTags.SETTINGS_ME_LOADING),
            )
        }
    }
}

@Composable
private fun SettingsRowView(
    deps: MeDeps,
    controller: SettingsController,
    row: SettingsRow,
    version: Long,
    state: SettingsState,
) {
    val texts = deps.texts
    val key = MeDraftTexts.settingsKey(row.id) ?: return
    val me = deps.repo.meOf(controller.identity)
    val value =
        when (row.id) {
            SettingsItems.ID_NICKNAME -> {
                me?.nickname
            }

            SettingsItems.ID_PHONE -> {
                me?.let { texts.text(if (it.phoneBound) "settings.phone.bound" else "settings.phone.unbound") }
            }

            SettingsItems.ID_CLEAR_CACHE -> {
                state.cacheBytes?.let {
                    texts.format(
                        "settings.clear_cache.size",
                        mapOf("size" to cacheSize(it)),
                    )
                }
            }

            else -> {
                null
            }
        }
    val clickable =
        when (row.id) {
            SettingsItems.ID_NICKNAME, SettingsItems.ID_PHONE -> row.clickable
            else -> true
        }
    NavRow(
        title = texts.text(key),
        tag = MeTags.SETTINGS_ROW_PREFIX + key,
        value = value,
        onClick = if (clickable) ({ controller.onRow(row, version) }) else null,
    )
}

/** 缓存大小按 KB / MB 取整（AC-S1-137）。 */
fun cacheSize(bytes: Long): String {
    val kb = (bytes + KB - 1) / KB
    return if (kb < KB) "$kb KB" else "${(bytes + MB / 2) / MB} MB"
}

private const val KB = 1024L
private const val MB = KB * KB

@Composable
private fun AgreementSheet(
    deps: MeDeps,
    controller: SettingsController,
    model: SettingsModel,
    version: Long,
    sheet: Long,
) {
    val texts = deps.texts
    val choices =
        model.groups
            .flatMap { it.rows }
            .firstOrNull { it.id == SettingsItems.ID_AGREEMENT }
            ?.choices ?: return
    CouliSheet(
        title = texts.text("settings.agreement"),
        onDismissRequest = { controller.closeSheet(sheet) },
        closeLabel = texts.text("me.close"),
    ) {
        Column(modifier = Modifier.testTag(MeTags.AGREEMENT_SHEET), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            choices.forEach { (_, params) ->
                val type = (params["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val user = type == "agreement"
                CouliButton(
                    text = texts.text(if (user) "settings.agreement.user" else "settings.agreement.privacy"),
                    onClick = { controller.openAgreement(params, version, sheet) },
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.fillMaxWidth().testTag(if (user) MeTags.AGREEMENT_USER else MeTags.AGREEMENT_PRIVACY),
                )
            }
        }
    }
}

/** 撤回、退出确认框（系统返回 = 取消，点遮罩不执行）。 */
@Composable
internal fun BackAsCancel(onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
}

/** 【撤回 AI 服务同意】只在已同意隐私政策时显示（基本模式不显示，鸿蒙 H07）。 */
private fun identityAllowsAgentRow(level: String): Boolean = level != MeLevel.BASIC
