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
    var resumes by remember { mutableLongStateOf(0L) }
    OnResumed(controller) {
        resumes += 1
        controller.autoRefresh()
    }
    val texts = deps.texts
    val view = controller.view(snapshot, masked)
    val scroll = remember(identity) { deps.tabScroll.of(identity) }
    val eye = rememberMeEyeSpeaker(controller)
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
                    // 标题栏到首卡 8（M1）；左右 16、底 16、块间 12
                    modifier =
                        Modifier.padding(
                            start = CouliSpacing.space4,
                            top = CouliSpacing.space2,
                            end = CouliSpacing.space4,
                            bottom = CouliSpacing.space4,
                        ),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                ) {
                    if (view.loginPrompt) {
                        GuestCard(deps, controller)
                    } else {
                        MemberBody(deps, controller, snapshot, view, mask, blocked, resumes, eye)
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
        val error = snapshot.error?.takeIf { snapshot.me != null && snapshot.identity == identity }
        // 复制结果与刷新失败一次只显示一条、刷新失败优先（G-M14）；整个提示宿主按执行器（身份）隔离（评审 r1 S1）
        MeNotices(texts, controller, error, deps.repo::consumeError)
        // 眼睛切换播报节点在滚动容器外、页面最上层（评审 r2 S2）：卡顶滚出视口时也在可见语义树里
        MeEyeLiveRegion(eye)
    }
}

/** 游客卡（MeGuest）：内边距 16、头像行与按钮间 16；标题 17/600、说明 14 次文字。 */
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
                .padding(CouliSpacing.space4)
                .testTag(MeTags.ME_GUEST),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.CenterVertically) {
            Avatar()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                Text(text = texts.text("me.guest.title"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
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

/** 默认头像：56 圆、muted 底、人形 32 次文字色（身份卡与游客卡共用；页面私有，不进 DS，G-M17）。 */
@Composable
private fun Avatar() {
    Box(
        modifier = Modifier.size(AVATAR).background(CouliColors.backgroundMuted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CouliIcons.user, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSpacing.space8))
    }
}

@Suppress("LongParameterList")
@Composable
private fun MemberBody(
    deps: MeDeps,
    controller: MeTabController,
    snapshot: MeSnapshot,
    view: MeViewModel,
    mask: com.couli.core.clientcore.AmountMaskStore?,
    blocked: Boolean,
    resumes: Long,
    eye: MeEyeSpeaker,
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
            HeroCard(texts, controller, view, mask, version, eye)
        }

        snapshot.phase == MeSnapshot.ERROR && snapshot.identity == controller.identity -> {
            Box(modifier = Modifier.fillMaxWidth().testTag(MeTags.ME_ERROR)) {
                ErrorState(
                    title = texts.text("me.load_failed"),
                    message = snapshot.error?.message(texts),
                    retry = StateAction(texts.text("me.retry"), enabled = !blocked) { controller.refresh() },
                    actionModifier = Modifier.testTag(MeTags.ME_RETRY),
                )
            }
        }

        else -> {
            MeSkeleton(texts)
        }
    }
}

/**
 * 余额主卡（M9–M12）：首行紧凑（眼睛 20、48 命中区外溢，已有参数 compactHeader）；【提现】至少 96 宽；预估块钟表 14 +「预估收益」14 常规、数值 22/600、纵向 2。
 * 眼睛切换后 polite 播报一次「金额已隐藏 / 金额已显示」（G-M13）：只在用户点眼睛、且开关确实变了时播，进入页面不播。
 */
@Composable
private fun HeroCard(
    texts: com.couli.core.me.MeTexts,
    controller: MeTabController,
    view: MeViewModel,
    mask: com.couli.core.clientcore.AmountMaskStore?,
    version: Long,
    eye: MeEyeSpeaker,
) {
    // 播报经页面宿主上的常驻 polite liveRegion 单一出口（评审 r1 / r2 S2），不主动播报
    HeroCardBody(texts, controller, view, mask, version, eye::announce)
}

@Composable
private fun HeroCardBody(
    texts: com.couli.core.me.MeTexts,
    controller: MeTabController,
    view: MeViewModel,
    mask: com.couli.core.clientcore.AmountMaskStore?,
    version: Long,
    announce: (String) -> Unit,
) {
    val withdrawable = view.withdrawableFen ?: 0L
    val hidden = texts.text("me.amount_hidden")
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
        hiddenMask = MeView.MASK,
        onToggleHidden =
            if (mask != null) {
                {
                    val before = mask.hidden.value
                    controller.toggleMask(mask)
                    val after = mask.hidden.value
                    if (after != before) announce(texts.text(if (after) "me.amount_hidden" else AMOUNT_SHOWN))
                }
            } else {
                null
            },
        modifier = Modifier.testTag(MeTags.ME_BALANCE),
        withdrawModifier = Modifier.widthIn(min = WITHDRAW_MIN_WIDTH).testTag(MeTags.ME_WITHDRAW),
        compactHeader = true,
        bottom = { Estimated(texts, view, hidden) },
    )
}

@Composable
private fun Estimated(
    texts: com.couli.core.me.MeTexts,
    view: MeViewModel,
    hidden: String,
) {
    val estimated = view.estimatedFen ?: 0L
    Column(
        modifier =
            Modifier
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        texts.text("me.estimated") + " " + if (view.masked) hidden else CouliFormat.moneySpoken(estimated)
                }.testTag(MeTags.ME_ESTIMATED),
        verticalArrangement = Arrangement.spacedBy(ESTIMATED_GAP),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Icon(
                CouliIcons.clock,
                contentDescription = null,
                tint = CouliColors.rebateSolidText,
                modifier = Modifier.size(CouliSizes.iconTiny),
            )
            Text(text = texts.text("me.estimated"), style = CouliTypography.footnote, color = CouliColors.rebateSolidText)
        }
        Text(
            text = if (view.masked) MeView.MASK else CouliFormat.money(estimated),
            style = CouliTextStyles.amountTitle,
            color = CouliColors.rebateSolidText,
        )
    }
}

/** 加载骨架（M17，无稿）：按新结构——身份卡块（56 圆 + 两条横块）→ 主卡块 → 一组宫格块；整块合并读屏「加载中」。 */
@Composable
private fun MeSkeleton(texts: com.couli.core.me.MeTexts) {
    val card = RoundedCornerShape(CouliRadius.card)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = texts.text("me.loading") }
                .testTag(MeTags.ME_LOADING),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(AVATAR).background(CouliColors.backgroundMuted, CircleShape).testTag(MeTags.ME_SKELETON_AVATAR))
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Skeleton(Modifier.size(SKELETON_NAME_WIDTH, CouliSpacing.space4).testTag(MeTags.ME_SKELETON_NAME))
                Skeleton(Modifier.size(SKELETON_INVITE_WIDTH, CouliSpacing.space3).testTag(MeTags.ME_SKELETON_INVITE))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SKELETON_BALANCE_HEIGHT)
                .background(CouliColors.backgroundMuted, card)
                .testTag(MeTags.ME_SKELETON_BALANCE),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = SKELETON_GRID_HEIGHT)
                .background(CouliColors.backgroundMuted, card)
                .testTag(MeTags.ME_SKELETON_GRID),
        )
    }
}

/**
 * 通知引导条（MeHidden，M3 / M4，G-M10）：muted 底、圆角 12、内边距 4/4/4/12；铃铛 20 次文字色；正文 14，最后一句「去开启」600 下划线链接色
 * （[guideSpans] 切分失败整句普通）。正文区一个按钮（合并读屏整句一次），只跳系统通知设置；关闭按钮视觉 20、命中 48。
 */
@Composable
private fun GuideBar(
    texts: com.couli.core.me.MeTexts,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val sentence = texts.text("perm.push.guide_bar")
    val link = CouliColors.textLink
    val styled = remember(sentence, link) { guideSpans(sentence, link) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space1)
                .testTag(MeTags.ME_GUIDE_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = CouliSizes.touchTarget)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(start = CouliSpacing.space2, top = CouliSpacing.space2, bottom = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Icon(
                CouliIcons.bell,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
            Text(text = styled, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
        CouliIconButton(
            icon = CouliIcons.close,
            label = texts.text("me.guide_bar.close"),
            onClick = onClose,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconMedium,
            modifier = Modifier.testTag(MeTags.ME_GUIDE_CLOSE),
        )
    }
}

/** 身份卡（M5–M8）：内边距 16、头像与文字间 12；昵称 17/600 可折行；邀请码行见 [InviteFlow]。 */
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
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text(
                text = nickname,
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(MeTags.ME_NICKNAME),
            )
            // G05：invite_code 为 null 时码与复制都不显示（不显示原因）
            val code = view.inviteCode
            val inviterKey = view.inviterTextKey
            if (code != null || inviterKey != null) {
                InviteFlow(hasCode = code != null) { onCodeLayout ->
                    if (code != null) {
                        Text(
                            text = inviteCodeText(texts.text("me.invite_code"), code, CouliColors.textPrimary),
                            style = CouliTypography.caption,
                            color = CouliColors.textSecondary,
                            onTextLayout = onCodeLayout,
                            modifier = Modifier.testTag(MeTags.ME_INVITE_CODE),
                        )
                        CouliIconButton(
                            icon = CouliIcons.copy,
                            label = texts.text("me.copy") + texts.text("me.invite_code"),
                            // 捕获渲染时的码与版本（共性第 63 条）
                            onClick = { controller.copyInviteCode(version, code) },
                            tint = CouliColors.textLink,
                            iconSize = CouliSizes.iconSmall,
                            // 视觉 16、48 命中区以图标为中心外溢（不撑高本行，照 WalletHeroHeader 紧凑眼睛）
                            modifier = Modifier.meCompactHit().testTag(MeTags.ME_COPY),
                        )
                    }
                    if (inviterKey != null) {
                        Text(
                            text = texts.text(inviterKey),
                            style = CouliTypography.caption,
                            color = CouliColors.textSecondary,
                            modifier = Modifier.testTag(MeTags.ME_INVITER),
                        )
                    }
                }
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
        "me.grid.earnings" -> CouliIcons.barChart
        "me.grid.help" -> CouliIcons.help
        "me.grid.cs" -> CouliIcons.headset
        "me.grid.auth" -> CouliIcons.link
        else -> CouliIcons.settings
    }

private val AVATAR = CouliSpacing.space12 + CouliSpacing.space2
private val WITHDRAW_MIN_WIDTH = CouliSpacing.space16 + CouliSpacing.space8
private val ESTIMATED_GAP = CouliSpacing.space1 / 2
private val SKELETON_NAME_WIDTH = CouliSpacing.space16 + CouliSpacing.space8
private val SKELETON_INVITE_WIDTH = CouliSpacing.space16 * 2 + CouliSpacing.space4
private val SKELETON_BALANCE_HEIGHT = CouliSpacing.space16 * 2 + CouliSpacing.space12
private val SKELETON_GRID_HEIGHT = CouliSpacing.space16 + CouliSpacing.space12 + CouliSpacing.space2
private const val AMOUNT_SHOWN = "me.amount_shown"
