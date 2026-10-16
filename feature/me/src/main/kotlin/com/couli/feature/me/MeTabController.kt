// 执行器的每个入口逐项核对身份、版本、禁用期与结果类别（APP-09 第 52、63、65 条）
@file:Suppress("TooManyFunctions", "ComplexCondition", "ReturnCount")

package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EntryGrid
import com.couli.core.designsystem.EntryItem
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.PageTitleBar
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.WalletHeroCard
import com.couli.core.designsystem.WalletHeroTexts
import com.couli.core.designsystem.couliCard
import com.couli.core.me.GridCell
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeView
import com.couli.core.me.MeViewModel
import com.couli.core.me.NotifyGuideBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「我的」Tab 的执行器（brief §2.2；G04、G05、G06、G28、G31、G36）。构造时固定身份（[identity]，APP-09 第 65 条）；身份变了页面重建本执行器，旧执行器的
 * 回调一律被拒。按钮回调都带渲染时的快照版本与身份，执行前核对。
 */
class MeTabController(
    private val deps: MeDeps,
    val identity: MeIdentity,
) : MeReader {
    private var left = false
    private var toastIds = 0L
    private val mutableToast = MutableStateFlow<MeNotice?>(null)

    /** 一次性的复制结果提示（polite 播报一次）。 */
    val toast: StateFlow<MeNotice?> = mutableToast.asStateFlow()

    /** 本执行器仍有效：没离开、身份仍是当前身份。 */
    fun alive(): Boolean = !left && identity.current(deps.env)

    /** 本执行器（这一次进入）仍可读取（R1-03）：离开 Tab 或身份变了即停。 */
    override fun reading(): Boolean = alive()

    /** 进入 Tab、下拉、回前台、从子页返回（G36）：读取许可绑定本执行器（这一次进入）。 */
    fun refresh(pull: Boolean = false): Boolean = alive() && deps.repo.refresh(identity, this, pull)

    /**
     * 自动同步（进入 Tab、回前台、从子页返回、身份变了重建执行器后的那一次读取；F-X-14，FU-AND-0717，鸿蒙 FU-HM-0721 R1-01 同法）：会话是受限登录
     * （deletion_only）时不读——不发 getMe、不动快照；其余照常 [refresh]。下拉与【重试】是页面主动读取，仍走 [refresh]。
     */
    fun autoRefresh(): Boolean = deps.env.sessionScope() != DELETION_ONLY && refresh()

    /** 页面显示的规则（MeView）。 */
    fun view(
        snapshot: MeSnapshot,
        masked: Boolean,
    ): MeViewModel {
        val me = snapshot.me?.takeIf { snapshot.identity == identity }
        return MeView.model(identity.level, me?.raw, masked, deps.env.availableRoutes(), deps.env.earningsFlag())
    }

    /** 引导条是否显示（G28）：已登录 && 系统通知未开启（拒绝过）&& 被拒满 48 小时 && 关闭满 7 天。 */
    fun guideBarVisible(): Boolean =
        NotifyGuideBar.visible(
            loggedIn = identity.member,
            systemRaw = deps.env.notificationStatus(),
            lastDeniedMs = deps.env.notificationDeniedAt(),
            lastClosedMs = deps.local?.guideClosedAt(),
            nowMs = deps.clock(),
        )

    /** 【复制】邀请码（G19）：捕获渲染时的码与版本，执行时核对仍是当前快照里的同一个码；只走统一写入口。 */
    fun copyInviteCode(
        version: Long,
        code: String,
    ) {
        if (!alive()) return
        val snapshot = deps.repo.state.value
        if (snapshot.version != version || snapshot.identity != identity || snapshot.me?.inviteCode != code) return
        val ok = deps.env.copy(code)
        mutableToast.value = MeNotice(if (ok) COPIED else COPY_FAILED, ++toastIds, assertive = false)
    }

    fun consumeToast(id: Long) {
        if (mutableToast.value?.id == id) mutableToast.value = null
    }

    /** 宫格一格（G31）：渲染时的格；执行时核对身份与格仍在当前规则里。 */
    fun tap(cell: GridCell) {
        if (!alive()) return
        if (cell.cs) {
            deps.env.customerService(ROUTE)
            return
        }
        val route = cell.route ?: return
        deps.env.open(route)
    }

    /** 【提现】→ Wallet（G06）：只在渲染时的快照仍是当前快照时打开。 */
    fun withdraw(version: Long) {
        val snapshot = deps.repo.state.value
        if (!alive() || snapshot.version != version || snapshot.identity != identity || snapshot.me == null) return
        deps.env.open(MeView.WALLET)
    }

    /** 铃铛 → Messages（G04，h5；无红点）。 */
    fun bell() {
        if (alive() && identity.member) deps.env.open(MeView.MESSAGES)
    }

    /** 游客【登录 / 注册】。 */
    fun login() {
        if (alive() && !identity.member) deps.env.open(LOGIN)
    }

    /** 引导条点击：只跳系统里本 App 的通知设置，不经 PermissionRequester 申请（G28）。 */
    fun openGuide() {
        if (alive()) deps.env.openNotificationSettings()
    }

    /** 引导条关闭：记下关闭时刻（设备级，G28）。返回是否已收起（写不进也收起，本次运行内不再显示）。 */
    fun closeGuide(): Boolean {
        if (!alive()) return false
        deps.local?.markGuideClosed(deps.clock())
        return true
    }

    fun toggleMask(mask: com.couli.core.clientcore.AmountMaskStore?) {
        if (alive()) mask?.toggle()
    }

    /** 离开（切到其他 Tab、进入下一页、身份变了）的同步出口：撤销并取消本次进入发起的 Me 读取（R1-03）。 */
    fun leave() {
        if (left) return
        left = true
        deps.repo.release(this)
    }

    companion object {
        const val ROUTE = "Me"
        const val LOGIN = "Login"
        const val COPIED = "invite.copy_code.done"
        const val COPY_FAILED = "me.copy_failed"
        const val DELETION_ONLY = "deletion_only"
    }
}

/**
 * 「我的」Tab 根（设计稿 Me、MeGuest、MeHidden、MeMinor；AdaptBackRestore）：
 * - 已登录：标题「我的」+ 铃铛 → 通知引导条 → 身份卡（默认头像、昵称、我的邀请码【复制】、邀请人：已绑定 / 未绑定）→ 余额卡（可提现余额 + 眼睛 +【提现】、预估收益）
 *   → 宫格三组；资料加载失败显示错误态与【重试】（不回落游客）；下拉刷新；
 * - 游客：还没有登录 +【登录 / 注册】→ 宫格只有「帮助中心」「设置」（没有铃铛、余额卡与其他宫格）。
 * 返回本页、切 Tab 往返都保持滚动位置（滚动状态放在会话级的 [MeDeps.tabScroll]，按身份分开，R1-11）。大屏限宽 600 居中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeTabScreen(
    deps: MeDeps,
    mask: com.couli.core.clientcore.AmountMaskStore? = null,
) {
    val identity by deps.env.identityFlow.collectAsStateWithLifecycle()
    // 每一次进入一个执行器（R1-03）：身份变了、或外壳在同步出口已让上一次进入离开（R2-01，[MeTabEntry.entry]）都重建
    val entry by deps.tab.entry.collectAsStateWithLifecycle()
    val controller = remember(identity, entry) { MeTabController(deps, identity) }
    DisposableEffect(controller) {
        deps.tab.bind(controller)
        onDispose {
            // 兜底：同步出口没有经过（如身份变了重建）时，离开组合也撤销读取
            deps.tab.unbind(controller)
            controller.leave()
        }
    }
    val snapshot by deps.repo.state.collectAsStateWithLifecycle()
    val noMask = remember { MutableStateFlow(false) }
    val masked by (mask?.hidden ?: noMask).collectAsStateWithLifecycle()
    val blocked by deps.gates.read.blocked
        .collectAsStateWithLifecycle()
    val toast by controller.toast.collectAsStateWithLifecycle()
    var resumes by remember { mutableLongStateOf(0L) }
    OnResumed(controller) {
        resumes += 1
        controller.autoRefresh()
    }
    val texts = deps.texts
    val view = controller.view(snapshot, masked)
    val scroll = remember(identity) { deps.tabScroll.of(identity) }
    val pullState = rememberPullToRefreshState()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .statusBarsPadding()
                .testTag(MeTags.ME_PAGE),
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize().pullToRefresh(
                    isRefreshing = snapshot.refreshing,
                    state = pullState,
                    enabled = identity.member && !blocked,
                    onRefresh = { controller.refresh(pull = true) },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = ME_MAX_WIDTH)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .testTag(MeTags.ME_SCROLL),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                PageTitleBar(
                    title = texts.text("me.title"),
                    trailing = {
                        if (view.bell) {
                            CouliIconButton(
                                icon = CouliIcons.bell,
                                label = texts.text("me.bell"),
                                onClick = controller::bell,
                                modifier = Modifier.testTag(MeTags.ME_BELL),
                            )
                        }
                    },
                )
                Column(
                    modifier = Modifier.padding(horizontal = CouliSpacing.space4).padding(bottom = CouliSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                ) {
                    if (view.loginPrompt) {
                        GuestCard(deps, controller)
                    } else {
                        MemberBody(deps, controller, snapshot, view, mask, blocked, resumes)
                    }
                    MeGrid(deps, controller, view)
                }
            }
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = snapshot.refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        MeToastHost(toast?.message(texts), toast?.id) { toast?.id?.let(controller::consumeToast) }
        val error = snapshot.error?.takeIf { snapshot.me != null && snapshot.identity == identity }
        MeToastHost(error?.message(texts), error?.id) { error?.id?.let(deps.repo::consumeError) }
    }
}

@Composable
private fun GuestCard(
    deps: MeDeps,
    controller: MeTabController,
) {
    val texts = deps.texts
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space5)
                .testTag(MeTags.ME_GUEST),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.CenterVertically) {
            Avatar()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                Text(text = texts.text("me.guest.title"), style = CouliTypography.title, color = CouliColors.textPrimary)
                Text(text = texts.text("me.guest.desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
        CouliButton(
            text = texts.text("me.guest.login"),
            onClick = controller::login,
            modifier = Modifier.fillMaxWidth().testTag(MeTags.ME_LOGIN),
        )
    }
}

@Composable
private fun Avatar() {
    Box(
        modifier = Modifier.size(CouliSpacing.space16).background(CouliColors.backgroundMuted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CouliIcons.user, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconLarge))
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun MemberBody(
    deps: MeDeps,
    controller: MeTabController,
    snapshot: MeSnapshot,
    view: MeViewModel,
    mask: com.couli.core.clientcore.AmountMaskStore?,
    blocked: Boolean,
    resumes: Long,
) {
    val texts = deps.texts
    var closedAt by remember(controller) { mutableLongStateOf(-1L) }
    // 回到本页（resumes 变化）时重新判断系统通知状态（从系统设置回来）
    val guide = remember(controller, resumes, closedAt) { closedAt < 0 && controller.guideBarVisible() }
    if (guide) {
        GuideBar(texts, onOpen = controller::openGuide) { if (controller.closeGuide()) closedAt = deps.clock() }
    }
    val me = snapshot.me?.takeIf { snapshot.identity == controller.identity }
    when {
        me != null -> {
            val version = snapshot.version
            IdentityCard(deps, controller, me.nickname, view, version)
            val withdrawable = view.withdrawableFen ?: 0L
            val estimated = view.estimatedFen ?: 0L
            val hidden = texts.text("me.amount_hidden")
            val maskText = MeView.MASK
            WalletHeroCard(
                texts =
                    WalletHeroTexts(
                        label = texts.text("me.withdrawable"),
                        amount = CouliFormat.money(withdrawable),
                        amountSpoken = texts.text("me.withdrawable") + " " + CouliFormat.moneySpoken(withdrawable),
                        withdraw = texts.text("me.withdraw"),
                        records = null,
                        hideLabel = texts.text("amount_mask.hide"),
                        showLabel = texts.text("amount_mask.show"),
                        hiddenSpoken = texts.text("me.withdrawable") + " " + hidden,
                    ),
                onWithdraw = { controller.withdraw(version) },
                amountHidden = view.masked,
                hiddenMask = maskText,
                onToggleHidden = if (mask != null) ({ controller.toggleMask(mask) }) else null,
                modifier = Modifier.testTag(MeTags.ME_BALANCE),
                withdrawModifier = Modifier.testTag(MeTags.ME_WITHDRAW),
                bottom = {
                    Column(
                        modifier =
                            Modifier
                                .semantics(mergeDescendants = true) {
                                    contentDescription =
                                        texts.text("me.estimated") + " " + if (view.masked) hidden else CouliFormat.moneySpoken(estimated)
                                }.testTag(MeTags.ME_ESTIMATED),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
                        ) {
                            Icon(
                                CouliIcons.clock,
                                contentDescription = null,
                                tint = CouliColors.rebateSolidText,
                                modifier = Modifier.size(CouliSizes.iconSmall),
                            )
                            Text(
                                text = texts.text("me.estimated"),
                                style = CouliTextStyles.footnoteMedium,
                                color = CouliColors.rebateSolidText,
                            )
                        }
                        Text(
                            text = if (view.masked) maskText else CouliFormat.money(estimated),
                            style = CouliTextStyles.amountTitle,
                            color = CouliColors.rebateSolidText,
                        )
                    }
                },
            )
        }

        snapshot.phase == MeSnapshot.ERROR && snapshot.identity == controller.identity -> {
            Box(modifier = Modifier.fillMaxWidth().testTag(MeTags.ME_ERROR)) {
                ErrorState(
                    title = texts.text("me.load_failed"),
                    message = snapshot.error?.message(texts),
                    retry = StateAction(texts.text("me.retry"), enabled = !blocked) { controller.refresh() },
                )
            }
        }

        else -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { contentDescription = texts.text("me.loading") }
                        .testTag(MeTags.ME_LOADING),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Skeleton(Modifier.fillMaxWidth().heightIn(min = CouliSpacing.space16 + CouliSpacing.space8))
                Skeleton(Modifier.fillMaxWidth().heightIn(min = CouliSpacing.space16 + CouliSpacing.space16))
            }
        }
    }
}

@Composable
private fun GuideBar(
    texts: com.couli.core.me.MeTexts,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .testTag(MeTags.ME_GUIDE_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = CouliSizes.touchTarget)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = CouliSpacing.space3, top = CouliSpacing.space2, bottom = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Icon(
                CouliIcons.bell,
                contentDescription = null,
                tint = CouliColors.statusWarningText,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
            Text(text = texts.text("perm.push.guide_bar"), style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
        CouliIconButton(
            icon = CouliIcons.close,
            label = texts.text("me.guide_bar.close"),
            onClick = onClose,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconSmall,
            modifier = Modifier.testTag(MeTags.ME_GUIDE_CLOSE),
        )
    }
}

@Composable
private fun IdentityCard(
    deps: MeDeps,
    controller: MeTabController,
    nickname: String,
    view: MeViewModel,
    version: Long,
) {
    val texts = deps.texts
    Row(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space5),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text(
                text = nickname,
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(MeTags.ME_NICKNAME),
            )
            // G05：invite_code 为 null 时整块不显示（不显示原因）
            val code = view.inviteCode
            if (code != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = texts.text("me.invite_code") + " " + code,
                        style = CouliTypography.footnote,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.weight(1f, fill = false).testTag(MeTags.ME_INVITE_CODE),
                    )
                    CouliIconButton(
                        icon = CouliIcons.copy,
                        label = texts.text("me.copy") + texts.text("me.invite_code"),
                        // 捕获渲染时的码与版本（共性第 63 条）
                        onClick = { controller.copyInviteCode(version, code) },
                        tint = CouliColors.brandPrimary,
                        iconSize = CouliSizes.iconSmall,
                        modifier = Modifier.testTag(MeTags.ME_COPY),
                    )
                }
            }
            view.inviterTextKey?.let { key ->
                Text(
                    text = texts.text(key),
                    style = CouliTypography.footnote,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.testTag(MeTags.ME_INVITER),
                )
            }
        }
    }
}

@Composable
private fun MeGrid(
    deps: MeDeps,
    controller: MeTabController,
    view: MeViewModel,
) {
    val texts = deps.texts
    view.grid.groupBy { it.group }.forEach { (group, cells) ->
        val items =
            cells.mapNotNull { cell ->
                val key = MeDraftTexts.gridKey(cell.item) ?: return@mapNotNull null
                EntryItem(icon = gridIcon(key), label = texts.text(key)) { controller.tap(cell) }
            }
        if (items.isNotEmpty()) {
            EntryGrid(items = items, title = texts.text("me.group.$group"), modifier = Modifier.testTag(MeTags.ME_GRID_PREFIX + group))
        }
    }
}

private fun gridIcon(key: String) =
    when (key) {
        "me.grid.orders" -> CouliIcons.orders
        "me.grid.wallet" -> CouliIcons.wallet
        "me.grid.ledger" -> CouliIcons.ledger
        "me.grid.find_order" -> CouliIcons.claim
        "me.grid.invite" -> CouliIcons.invite
        "me.grid.earnings" -> CouliIcons.trend
        "me.grid.help" -> CouliIcons.help
        "me.grid.cs" -> CouliIcons.headset
        "me.grid.auth" -> CouliIcons.link
        else -> CouliIcons.settings
    }
