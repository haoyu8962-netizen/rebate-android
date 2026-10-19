package com.couli.app.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import com.couli.core.designsystem.ActionMenuItem
import com.couli.core.designsystem.CouliActionMenu
import com.couli.core.designsystem.CouliAlertDialog
import com.couli.core.designsystem.CouliAnchoredMenu
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliFilterChip
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.NavBar

/**
 * 组件目录页的固定长标记：只在 debug / staging（src/debugFeatures）里出现；
 * tools/ci/release-scan.sh 在 release 制品里查它（命中即失败），--self-test 要求 debug / staging 命中。
 */
const val COMPONENT_CATALOG_MARKER = "COULI_COMPONENT_CATALOG_DS01_6f2c9a4e81b7d350"

/** 目录页里的固定页面（[key] 也用于 adb 直接打开：`am start -n com.couli.app/.MainActivity --es couli.catalog <key>`）。 */
internal enum class CatalogPage(
    val key: String,
    val title: String,
) {
    INDEX("index", "Component catalog"),
    SEARCH("Search", "Compare: Search"),
    STRESS("ProductCardStress", "Compare: ProductCardStress"),
    PRODUCT("Product", "Compare: Product"),
    WALLET("Wallet", "Compare: Wallet"),
    ME("Me", "Compare: Me"),
    SEARCH_EMPTY("SearchEmpty", "Compare: SearchEmpty"),
    ;

    companion object {
        fun fromKey(key: String?): CatalogPage? = entries.firstOrNull { it.key == key }
    }
}

/** 已注入 intent extra 的键名。 */
internal const val CATALOG_EXTRA = "couli.catalog"

private const val COMPONENT_PREFIX = "component:"

/**
 * 组件目录（debug / staging，DS-02「组件展示页」）：索引按组件清单分组 → 每个组件一屏，按变体 × 状态逐个摆出，
 * 页顶可切「浅色 / 深色」「字号 100% / 200%」，标注 Android 实现是「系统控件」还是「自绘」；另有 6 张设计对照页。
 * 目录自己初始浅色 / 100%（App 默认主题仍跟随系统）；切换只作用于目录。
 */
@Composable
internal fun ComponentCatalog(
    initial: CatalogPage,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(initial.key) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var large by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = { if (page == CatalogPage.INDEX.key) onClose() else page = CatalogPage.INDEX.key }
    BackHandler(onBack = back)
    CouliTheme(darkTheme = dark) {
        Box(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(COMPONENT_CATALOG_MARKER)) {
            val component = page.removePrefix(COMPONENT_PREFIX).takeIf { page.startsWith(COMPONENT_PREFIX) }
            val entry = component?.let { CatalogRegistry.find(it) }
            when {
                entry != null -> {
                    ComponentPage(
                        entry = entry,
                        dark = dark,
                        large = large,
                        onTheme = { dark = it },
                        onFont = { large = it },
                        onBack = back,
                    )
                }

                else -> {
                    when (CatalogPage.fromKey(page) ?: CatalogPage.INDEX) {
                        CatalogPage.INDEX -> CatalogIndex(onOpen = { page = it }, onClose = onClose)
                        CatalogPage.SEARCH -> SearchComparePage(onBack = back)
                        CatalogPage.STRESS -> StressComparePage(onBack = back)
                        CatalogPage.PRODUCT -> ProductComparePage(onBack = back)
                        CatalogPage.WALLET -> WalletComparePage(onBack = back)
                        CatalogPage.ME -> MeComparePage(onBack = back)
                        CatalogPage.SEARCH_EMPTY -> SearchEmptyComparePage(onBack = back)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogIndex(
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        NavBar(title = CatalogPage.INDEX.title, backLabel = CatalogSamples.BACK, onBack = onClose, handleSystemBack = false)
        LazyColumn(modifier = Modifier.weight(1f).testTag("catalog-index").navigationBarsPadding()) {
            CatalogRegistry.entries.groupBy { it.group }.forEach { (group, entries) ->
                item(key = "group/$group") { GroupHeader(group) }
                items(entries, key = { it.id }) { entry ->
                    IndexRow(
                        title = entry.id,
                        subtitle = "${entry.kind} · ${entry.cases.size} 个样例",
                        modifier = Modifier.testTag("catalog-entry/${entry.id}"),
                        onClick = { onOpen(COMPONENT_PREFIX + entry.id) },
                    )
                }
            }
            item(key = "group/compare") { GroupHeader("设计稿对照页") }
            items(CatalogPage.entries.filter { it != CatalogPage.INDEX }, key = { it.key }) { entry ->
                IndexRow(title = entry.title, subtitle = null, onClick = { onOpen(entry.key) })
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = CouliTextStyles.footnoteSemibold,
        color = CouliColors.textSecondary,
        modifier =
            Modifier
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space4, bottom = CouliSpacing.space1)
                .semantics { heading() },
    )
}

@Composable
private fun IndexRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
    ) {
        Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary)
        if (subtitle != null) Text(text = subtitle, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/** 一个组件一屏：页顶分类、说明与浅深 / 字号切换，下面按变体 × 状态逐个摆出（可滚动）。 */
@Composable
private fun ComponentPage(
    entry: CatalogEntry,
    dark: Boolean,
    large: Boolean,
    onTheme: (Boolean) -> Unit,
    onFont: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val id = entry.id
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        NavBar(title = id, backLabel = CatalogSamples.BACK, onBack = onBack, handleSystemBack = false)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                CouliFilterChip("浅色", selected = !dark, onClick = { onTheme(false) }, modifier = Modifier.testTag("catalog-theme-light"))
                CouliFilterChip("深色", selected = dark, onClick = { onTheme(true) }, modifier = Modifier.testTag("catalog-theme-dark"))
                CouliFilterChip("字号 100%", selected = !large, onClick = { onFont(false) }, modifier = Modifier.testTag("catalog-font-100"))
                CouliFilterChip("字号 200%", selected = large, onClick = { onFont(true) }, modifier = Modifier.testTag("catalog-font-200"))
            }
            Text(
                text = entry.kind,
                style = CouliTextStyles.footnoteSemibold,
                color = CouliColors.brandEmphasis,
                modifier = Modifier.testTag("catalog-kind/$id"),
            )
            Text(
                text = entry.note,
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag("catalog-note/$id"),
            )
            RealContainers(id, large)
        }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) LARGE_FONT else 1f)) {
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("catalog-cases/$id")
                        .navigationBarsPadding(),
            ) {
                items(entry.cases, key = { (variant, state) -> "$variant/$state" }) { (variant, state) ->
                    Column(modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space3)) {
                        Text(
                            text = CatalogRegistry.label(variant, state),
                            style = CouliTypography.caption,
                            color = CouliColors.textSecondary,
                            modifier = Modifier.padding(horizontal = CouliSpacing.space4).testTag("catalog-label/$id/$variant/$state"),
                        )
                        CatalogComponentSample(
                            componentId = id,
                            variantId = variant,
                            stateId = state,
                            modifier = Modifier.padding(horizontal = CouliSpacing.space2).testTag("catalog-sample/$id/$variant/$state"),
                        )
                    }
                }
            }
        }
    }
}

/** 弹层类组件：页顶按钮打开真实系统容器（目录列表里只排内容，不同时弹出所有窗口）。 */
@Suppress("CyclomaticComplexMethod") // 每种弹层一个分支，调试页
@Composable
private fun RealContainers(
    id: String,
    large: Boolean,
) {
    var open by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val options =
        when (id) {
            "Dialog" -> listOf("dialog" to "打开真实对话框")
            "AlertDialog" -> listOf("alert" to "打开系统确认框")
            "Sheet" -> listOf("sheet" to "打开普通半屏", "protected" to "打开保护型半屏")
            "ActionMenu" -> listOf("menu" to "打开底部菜单", "anchor" to "打开锚点菜单")
            else -> emptyList()
        }
    if (options.isEmpty()) return
    Box {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            options.forEach { (key, label) ->
                CouliButton(
                    text = label,
                    onClick = { open = key },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                )
            }
        }
        val close = { open = null }
        val menuItems = listOf(ActionMenuItem("用户协议"), ActionMenuItem("隐私政策"))
        // 真实窗口跟目录选定字号（组件库把调用处字号带进窗口），不只是下面的静态样例
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) LARGE_FONT else 1f)) {
            when (open) {
                "dialog" -> {
                    CouliDialog(
                        title = "请阅读并同意协议",
                        message = "为保障你的权益，请先阅读并同意《用户协议》和《隐私政策》。",
                        confirmText = "同意",
                        onConfirm = close,
                        dismissText = "不同意",
                        onDismissRequest = close,
                    )
                }

                "alert" -> {
                    CouliAlertDialog(
                        title = "清除缓存？",
                        message = "清除后图片需要重新加载。",
                        confirmText = "清除",
                        onConfirm = close,
                        dismissText = "取消",
                        onDismissRequest = close,
                    )
                }

                "sheet", "protected" -> {
                    val protected = open == "protected"
                    CouliSheet(
                        title = if (protected) "身份验证" else "价格区间",
                        onDismissRequest = close,
                        closeLabel = if (protected) "关闭" else null,
                        dismissOnSwipe = !protected,
                        dismissOnClickOutside = !protected,
                        actions = { CouliButton(text = "确定", onClick = close, modifier = Modifier.fillMaxWidth()) },
                    ) {
                        Text(
                            text = if (protected) "资金类半屏：不能下滑或点遮罩关闭，可点右上关闭或系统返回。" else "下滑、点遮罩、返回都能关闭。",
                            style = CouliTypography.footnote,
                            color = CouliColors.textPrimary,
                        )
                    }
                }

                "menu" -> {
                    CouliActionMenu(items = menuItems, onSelect = { close() }, onDismissRequest = close, title = "查看协议")
                }

                "anchor" -> {
                    CouliAnchoredMenu(expanded = true, items = menuItems, onSelect = { close() }, onDismissRequest = close)
                }
            }
        }
    }
}

private const val LARGE_FONT = 2f
