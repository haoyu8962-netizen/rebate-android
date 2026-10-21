package com.couli.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.couli.app.home.HomeTonalButton
import com.couli.app.trade.AuthManageEntry
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.SearchEntry as CouliSearchEntry
import com.couli.core.designsystem.TabBar
import com.couli.core.designsystem.TabItem
import com.couli.core.home.HomeDraftTexts
import com.couli.core.me.MeDraftTexts
import com.couli.core.wallet.WalletDraftTexts
import com.couli.generated.DefaultTexts

const val TAB_SHELL_TAG = "tab_shell"
const val BASIC_MODE_PAGE_TAG = "basic_mode_page"
const val BASIC_MODE_ENABLE_TAG = "basic_mode_enable_full"
const val BASIC_MODE_SETTINGS_TAG = "basic_mode_settings"
const val HOME_SEARCH_ENTRY_TAG = "home_search_entry"
const val HOME_ASK_AI_TAG = "home_ask_ai"

/** 底部四个 Tab：路由名（routes.json）→ 图标与名称（名称取 [ShellDraftTexts]，TODO(N-16)）。 */
enum class ShellTab(
    val route: String,
) {
    HOME("Home"),
    AGENT("AgentChat"),
    ORDERS("OrderList"),
    ME("Me"),
    ;

    val item: TabItem
        get() =
            when (this) {
                HOME -> TabItem(CouliIcons.home, ShellDraftTexts.TAB_HOME)
                AGENT -> TabItem(CouliIcons.chat, ShellDraftTexts.TAB_AGENT)
                ORDERS -> TabItem(CouliIcons.orders, ShellDraftTexts.TAB_ORDERS)
                ME -> TabItem(CouliIcons.user, ShellDraftTexts.TAB_ME)
            }

    companion object {
        val routes: Set<String> = entries.map { it.route }.toSet()

        fun ofRoute(route: String): ShellTab? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Tab 外壳（GUIDE §3「底部 Tab」）：上面是选中 Tab 的根页（经路由注册表渲染各 Feature 的占位页，不 import Feature 类型），
 * 下面是 DesignSystem 的 [TabBar]，白底铺满到屏幕底边（adaptation-a11y-spec「Tab 栏同样铺满到底」）。
 * 基本模式下四个 Tab 照常显示（设计稿 BasicMode）；点非放行的 Tab 由调用方经 Router 判定（补同意），见 AppShell。
 */
@Composable
fun TabShell(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    /**
     * APP-10b G32（BR-AI-12）：`agent.entry.visible=false` 时 AI 助手 Tab 换成「搜索」——同一位置显示搜索图标与名称，点它经 Router 打开 Search（[onSearch]），
     * 不切到 Agent 页。缺省为 false（Tab 照旧）。
     */
    agentAsSearch: Boolean = false,
    onSearch: () -> Unit = {},
    tabRoot: @Composable (ShellTab) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(TAB_SHELL_TAG)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
            tabRoot(selected)
        }
        Box(modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface).navigationBarsPadding()) {
            TabBar(
                items =
                    ShellTab.entries.map {
                        if (agentAsSearch &&
                            it == ShellTab.AGENT
                        ) {
                            TabItem(CouliIcons.search, ShellDraftTexts.TAB_SEARCH)
                        } else {
                            it.item
                        }
                    },
                selectedIndex = selected.ordinal,
                onSelect = { index ->
                    val tab = ShellTab.entries[index]
                    if (agentAsSearch && tab == ShellTab.AGENT) onSearch() else onSelect(tab)
                },
            )
        }
    }
}

/**
 * 基本模式页（设计稿 BasicMode；BR-ID-11、BR-ID-02 细则）：首页 Tab 的根在基本模式下换成本页——
 * 顶部搜索入口（只读搜索）与【设置】，提示条 privacy.basic_mode.notice + 【开启完整功能】，下面是首页内容（[content]，占位）。
 */
@Composable
fun BasicModePage(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onEnableFullFunction: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 大屏与首页同一内容列（r1 S2-4）：搜索行、提示区限宽 600 居中，与下面的价值卡 / SDUI 卡边界一致（03 §10.4）
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().testTag(BASIC_MODE_PAGE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = HOME_CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space2, top = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            SearchEntry(onClick = onSearch, modifier = Modifier.weight(1f))
            CouliIconButton(
                icon = CouliIcons.settings,
                label = ShellDraftTexts.SETTINGS_LABEL,
                onClick = onSettings,
                modifier = Modifier.testTag(BASIC_MODE_SETTINGS_TAG),
            )
        }
        Column(
            modifier =
                Modifier
                    .widthIn(max = HOME_CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space3),
        ) {
            BasicModeNotice(onEnableFullFunction)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** 搜索入口（只是入口，点了经 Router 打开 Search）：组件库 [CouliSearchEntry]（按钮语义），占位「搜商品名，或粘贴商品链接」（G-V09，首页与基本模式同键）。 */
@Composable
private fun SearchEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CouliSearchEntry(
        placeholder = HomeDraftTexts.text(HomeDraftTexts.SEARCH_PLACEHOLDER),
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 首页 Tab（已同意）：顶部搜索行吸顶（APP-11v G-V02：搜索入口 +【问 AI】浅底次按钮，限宽 600 居中，03 §10.4），下面是首页内容（[content]：
 * 首页 SDUI 列表，剪贴板提示条、游客价值卡、粘贴卡作为同一列表的头部项，经 [com.couli.app.home.LocalHomeNativeHeader] 传入，与 SDUI 卡一起滚动、
 * 一起下拉刷新）。
 */
@Composable
fun HomeWithSearch(
    onSearch: () -> Unit,
    onAskAi: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier =
                Modifier
                    .widthIn(max = HOME_CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            SearchEntry(
                onClick = onSearch,
                modifier = Modifier.weight(1f).testTag(HOME_SEARCH_ENTRY_TAG),
            )
            // 【问 AI】（G26 固定区，Main-v3 浅底次按钮 + 对话图标；经 Router 打开 AgentChat，APP-11）
            if (onAskAi != null) {
                HomeTonalButton(
                    text = HomeDraftTexts.text(HomeDraftTexts.ASK_AI),
                    onClick = onAskAi,
                    icon = CouliIcons.chat,
                    modifier = Modifier.testTag(HOME_ASK_AI_TAG),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** 首页内容的最大宽度（与搜索、商品详情页同为 600dp，03 §10.4）。 */
private const val HOME_CONTENT_MAX_WIDTH = 600

/** 「我的」Tab 的入口行：设置（ME_SETTINGS_ENTRY_TAG）。 */
const val ME_SETTINGS_ENTRY_TAG = "me_settings_entry"

/**
 * 「我的」Tab 的早期占位（【授权管理】A-R1-14、【设置】、钱包冒烟入口）：生产已由 APP-12 的正式「我的」页（feature:me MeTabScreen，宫格里有授权管理、设置、
 * 钱包）取代；这里只留给没有接「我的」的外壳单测（更早任务的测试不改）。
 */
@Composable
fun MeWithEntries(
    onAuthManage: () -> Unit,
    onSettings: () -> Unit,
    onWallet: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                    .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.card)),
        ) {
            AuthManageEntry(onOpen = onAuthManage)
            // 【钱包】冒烟入口（APP-09；「我的」页正文属 APP-12）
            if (onWallet != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = CouliSizes.touchTarget)
                            .clickable(role = Role.Button, onClick = onWallet)
                            .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                            .testTag(ME_WALLET_ENTRY_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = WalletDraftTexts.text(WALLET_TEXT_KEY),
                        style = CouliTypography.body,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(CouliIcons.chevron, contentDescription = null, tint = CouliColors.textSecondary)
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = CouliSizes.touchTarget)
                        .clickable(role = Role.Button, onClick = onSettings)
                        .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                        .testTag(ME_SETTINGS_ENTRY_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = MeDraftTexts.text(SETTINGS_TEXT_KEY),
                    style = CouliTypography.body,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(CouliIcons.chevron, contentDescription = null, tint = CouliColors.textSecondary)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

private const val SETTINGS_TEXT_KEY = "settings.title"
private const val WALLET_TEXT_KEY = "wallet.title"

/** 「我的」占位页上的【钱包】冒烟入口（APP-09）。 */
const val ME_WALLET_ENTRY_TAG = "me_wallet_entry"

/** 提示条：品牌浅底、圆角 16、内边距 16；信息图标 + 说明，下面整行主按钮（紧凑尺寸）。 */
@Composable
private fun BasicModeNotice(onEnableFullFunction: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.brandSubtle, RoundedCornerShape(CouliRadius.card))
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Icon(
                CouliIcons.info,
                contentDescription = null,
                tint = CouliColors.brandEmphasis,
                modifier = Modifier.padding(top = NOTICE_ICON_TOP).size(CouliSizes.iconMedium),
            )
            Text(
                text = DefaultTexts.text("privacy.basic_mode.notice").orEmpty(),
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
            )
        }
        CouliButton(
            text = DefaultTexts.text("privacy.basic_mode.enable").orEmpty(),
            onClick = onEnableFullFunction,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.fillMaxWidth().testTag(BASIC_MODE_ENABLE_TAG),
        )
    }
}

private val NOTICE_ICON_TOP = 0.5.dp
