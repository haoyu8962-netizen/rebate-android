@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "TooManyFunctions") // 样例数据逐条对照组件清单画板，不抽常量

package com.couli.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.ActionMenuItem
import com.couli.core.designsystem.ActionMenuSheetContent
import com.couli.core.designsystem.AgreementCheckRow
import com.couli.core.designsystem.AiLabel
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AnchoredMenuContent
import com.couli.core.designsystem.Avatar
import com.couli.core.designsystem.BenefitTag
import com.couli.core.designsystem.BrandMark
import com.couli.core.designsystem.BuyButton
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CodeInput
import com.couli.core.designsystem.CodeResendRow
import com.couli.core.designsystem.CouliAlertDialogContent
import com.couli.core.designsystem.CouliBadge
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliCheckbox
import com.couli.core.designsystem.CouliCheckboxRow
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialogContent
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliFilterChip
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliLoading
import com.couli.core.designsystem.CouliPullToRefreshIndicator
import com.couli.core.designsystem.CouliRadio
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSegmented
import com.couli.core.designsystem.CouliSheetBody
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliSwitch
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.CouliTextField
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerInfoButton
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.DisclaimerStyle
import com.couli.core.designsystem.DisclosureRow
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.EntryGrid
import com.couli.core.designsystem.EntryItem
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.EstNetLine
import com.couli.core.designsystem.FilterChipKind
import com.couli.core.designsystem.ImageLoadState
import com.couli.core.designsystem.ImagePlaceholder
import com.couli.core.designsystem.LastLoginTag
import com.couli.core.designsystem.ListRow
import com.couli.core.designsystem.ListRowTrailing
import com.couli.core.designsystem.LoadingSize
import com.couli.core.designsystem.MatchBadge
import com.couli.core.designsystem.MatchBadgeKind
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.NoticeBar
import com.couli.core.designsystem.NoticeTone
import com.couli.core.designsystem.OAuthButton
import com.couli.core.designsystem.OAuthProvider
import com.couli.core.designsystem.PageTitleBar
import com.couli.core.designsystem.PasteButton
import com.couli.core.designsystem.PhoneField
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.PriceTag
import com.couli.core.designsystem.PriceTagModel
import com.couli.core.designsystem.PriceTagSize
import com.couli.core.designsystem.PriceTrendContent
import com.couli.core.designsystem.PriceTrendPanel
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.ProductCardGridLayout
import com.couli.core.designsystem.ProductCardLarge
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardVariant
import com.couli.core.designsystem.RebateTag
import com.couli.core.designsystem.RebateTagModel
import com.couli.core.designsystem.RebateTagSize
import com.couli.core.designsystem.SearchEntry
import com.couli.core.designsystem.SearchField
import com.couli.core.designsystem.ShareCopyEditor
import com.couli.core.designsystem.ShareCopyMode
import com.couli.core.designsystem.ShareCopySegment
import com.couli.core.designsystem.ShareMaterial
import com.couli.core.designsystem.ShareMaterialPicker
import com.couli.core.designsystem.SheetFrame
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.SkeletonProductCard
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.TabBar
import com.couli.core.designsystem.TabItem
import com.couli.core.designsystem.ThirdPartyLoginRow
import com.couli.core.designsystem.ThirdPartyLoginTexts
import com.couli.core.designsystem.Timeline
import com.couli.core.designsystem.TimelineState
import com.couli.core.designsystem.TimelineStep
import com.couli.core.designsystem.TopTabItem
import com.couli.core.designsystem.TopTabs
import com.couli.core.designsystem.WalletHeroCard
import com.couli.core.designsystem.WalletHeroTexts
import com.couli.core.designsystem.couliCard

/**
 * 一个组件的一个「变体 × 状态」样例：真实目录页与 DS-02 快照 / 令牌 / 大字号测试共用这一入口，样例组装真实组件库组件。
 * 不铺页面画布、不拿 surface / muted 整块铺外壳：四周留 8 透明留白（露出外面的画布），[modifier] 挂这个透明容器；
 * 弹层只排内容（真实容器在目录页页顶打开）；不拉网络、SDK 或登录。隐藏态保留有尺寸的透明容器。
 */
@Composable
internal fun CatalogComponentSample(
    componentId: String,
    variantId: String,
    stateId: String,
    modifier: Modifier = Modifier,
) {
    val key = "$componentId/$variantId/$stateId"
    val control = if (key in FEATURED) Modifier.testTag("catalog-control/$key") else Modifier
    Box(modifier = modifier.fillMaxWidth().heightIn(min = SAMPLE_MIN).padding(SAMPLE_GAP)) {
        Sample(componentId, variantId, stateId, control)
    }
}

@Composable
private fun Sample(
    component: String,
    v: String,
    s: String,
    modifier: Modifier = Modifier,
) {
    when (component) {
        "CouliButton" -> {
            ButtonSample(v, s, modifier)
        }

        "CouliIconButton" -> {
            IconButtonSample(v, s)
        }

        "CouliTextField" -> {
            TextFieldSample(v, s, multiline = false)
        }

        "CouliTextArea" -> {
            TextFieldSample(v, s, multiline = true)
        }

        "PhoneField" -> {
            PhoneField(
                value = if (s == "empty" || s == "focused") "" else "13800138000",
                onValueChange = {},
                placeholder = "请输入手机号",
                clearLabel = "清除",
                error = if (s == "error") "请输入正确的手机号" else null,
                interactionSource = focusedSource(s == "focused"),
            )
        }

        "CodeInput" -> {
            CodeInputSample(v, s)
        }

        "SearchField" -> {
            if (v == "entry") {
                SearchEntry(placeholder = "搜索商品或粘贴链接", onClick = {})
            } else {
                SearchField(
                    value = if (s == "filled") "纯牛奶" else "",
                    onValueChange = {},
                    placeholder = "搜索商品或粘贴链接",
                    clearLabel = "清除",
                    onSearch = {},
                    interactionSource = focusedSource(s == "focused"),
                )
            }
        }

        "PasteButton" -> {
            PasteButton(text = "粘贴", onClick = {}, enabled = s != "disabled")
        }

        "CouliCheckbox" -> {
            CheckboxSample(v, s)
        }

        "CouliRadio" -> {
            Box(Modifier.couliCard()) {
                CouliRadio(
                    selected = s == "selected",
                    onClick = {},
                    label = "微信零钱",
                    supportingText = "到账以微信通知为准",
                    enabled = s != "disabled",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        "CouliSwitch" -> {
            Box(Modifier.couliCard()) {
                CouliSwitch(
                    checked = s == "on" || s == "disabled-on",
                    onCheckedChange = {},
                    label = "剪贴板识别",
                    enabled = !s.startsWith("disabled"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        "Segmented" -> {
            val options =
                when (v) {
                    "two" -> listOf("自购", "分享")
                    "three" -> listOf("全部", "自购", "分享")
                    else -> listOf("全部", "待结算", "已结算", "已失效")
                }
            CouliSegmented(
                options = options,
                selectedIndex = if (s == "unselected") -1 else 0,
                onSelect = {},
                enabled = s != "disabled",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        "FilterChip" -> {
            CouliFilterChip(
                label =
                    when (v) {
                        "filter" -> "只看有券"
                        "dropdown" -> "价格区间"
                        else -> "销量"
                    },
                selected = s == "selected",
                onClick = {},
                enabled = s != "disabled",
                kind =
                    when (v) {
                        "filter" -> FilterChipKind.FILTER
                        "dropdown" -> FilterChipKind.DROPDOWN
                        else -> FilterChipKind.SORT
                    },
            )
        }

        "NavBar" -> {
            NavBarSample(v, s == "scrolled")
        }

        "TopTabs" -> {
            TopTabs(
                items =
                    (if (v == "fixed") listOf("淘宝", "京东", "拼多多") else listOf("淘宝", "京东", "拼多多", "抖音", "唯品会", "美团"))
                        .map(::TopTabItem),
                selectedIndex = if (s == "selected") 0 else 1,
                onSelect = {},
                scrollable = v == "scrollable",
            )
        }

        "TabBar" -> {
            TabBar(
                items =
                    listOf(
                        TabItem(CouliIcons.home, "首页"),
                        TabItem(CouliIcons.chat, "AI 助手"),
                        TabItem(CouliIcons.orders, "订单", badgeDescription = "有未读"),
                        TabItem(CouliIcons.user, "我的"),
                    ),
                selectedIndex = if (s == "selected") 2 else 0,
                onSelect = {},
            )
        }

        "ListRow" -> {
            ListRowSample(v, s)
        }

        "Divider" -> {
            when (v) {
                "full" -> CouliDivider()
                "inset" -> CouliDivider(inset = CouliSpacing.space4)
                else -> CouliDivider(text = "其他登录方式")
            }
        }

        "CouliTag" -> {
            TagSample(v)
        }

        "CouliBadge" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space4), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CouliIconButton(icon = CouliIcons.bell, label = "消息中心", onClick = {})
                    CouliBadge(
                        contentDescription = "有未读",
                        count = if (v == "number") 3 else null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(CouliSpacing.space1),
                    )
                }
            }
        }

        "Avatar" -> {
            Avatar(
                painter = if (s == "image") samplePhoto() else null,
                size = if (v == "size56") 56.dp else 32.dp,
            )
        }

        "ImagePlaceholder" -> {
            ImagePlaceholderSample(v, s)
        }

        "Timeline" -> {
            TimelineSample(s)
        }

        "DisclosureRow" -> {
            Box(Modifier.couliCard()) {
                DisclosureRow(title = "近 30 天券后价", expanded = s == "expanded", onToggle = {}) {
                    Text(
                        text = "最低 ¥27.90（10 月 3 日），当前 ¥29.90",
                        style = CouliTextStyles.captionAmount,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.padding(CouliSpacing.space4),
                    )
                }
            }
        }

        "Loading" -> {
            CouliLoading(description = "加载中", size = if (v == "small") LoadingSize.SMALL else LoadingSize.MEDIUM)
        }

        "PullToRefresh" -> {
            PullSample(refreshing = s == "refreshing")
        }

        "Skeleton" -> {
            SkeletonSample(v)
        }

        "ProductCardSkeleton" -> {
            SkeletonProductCard(description = "加载中")
        }

        "EmptyState" -> {
            EmptyState(title = "还没有收藏的商品", message = "看到喜欢的商品点一下收藏", action = StateAction("去逛逛") {})
        }

        "ErrorState" -> {
            if (v == "trace6") {
                ErrorState(title = "页面加载失败，请重试", message = "问题编号 7F3A9C", retry = StateAction("重试") {})
            } else {
                ErrorState(title = "网络连接失败", message = "请检查网络后重试", retry = StateAction("重试") {})
            }
        }

        "NoticeBar" -> {
            NoticeSample(v, s == "action")
        }

        "Toast" -> {
            CouliToast(message = "已复制，可以打开微信粘贴给好友", icon = if (v == "icon") CouliIcons.check else null)
        }

        "AlertDialog" -> {
            if (v == "normal") {
                CouliAlertDialogContent(title = "清除缓存？", message = "清除后图片需要重新加载。", confirmText = "清除", onConfirm = {}, dismissText = "取消")
            } else {
                CouliAlertDialogContent(
                    title = "退出登录？",
                    message = "退出后需要重新登录才能查看订单与钱包。",
                    confirmText = "退出登录",
                    onConfirm = {},
                    dismissText = "取消",
                    destructive = true,
                )
            }
        }

        "Dialog" -> {
            DialogSample(v, stacked = s == "stacked")
        }

        "Sheet" -> {
            SheetSample(protected = v == "protected", s)
        }

        "ActionMenu" -> {
            val items = listOf(ActionMenuItem("用户协议"), ActionMenuItem("隐私政策"))
            if (v == "bottom") {
                SheetFrame { ActionMenuSheetContent(items = items, onSelect = {}, title = "查看协议") }
            } else {
                AnchoredMenuContent(items = items + ActionMenuItem("移除", destructive = true), onSelect = {})
            }
        }

        "OneTapLoginPanel" -> {
            OneTapLoginSketch(s)
        }

        "OAuthButton" -> {
            OAuthSample(v, s)
        }

        "AgreementCheckRow" -> {
            AgreementCheckRow(
                checked = s == "checked" || s == "confirmed" || s == "disabled-checked",
                onCheckedChange = {},
                text = agreementText(three = v == "three"),
                enabled = !s.startsWith("disabled"),
            )
        }

        "ThirdPartyLoginRow" -> {
            ThirdPartyLoginRow(
                wechatInstalled = s == "wechat-installed",
                huaweiChannel = v == "huawei-channel",
                texts = loginTexts,
                onLogin = {},
                onHelp = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        "LastLoginTag" -> {
            LastLoginSample(v, visible = s == "visible")
        }

        "BrandMark" -> {
            BrandMark()
        }

        "PriceTag" -> {
            PriceSample(v, long = s == "long-price", modifier = modifier)
        }

        "RebateTag" -> {
            RebateSample(v, s, modifier)
        }

        "EstNetLine" -> {
            EstNetLine(text = if (s == "normal") "预估返后 ¥12.89" else "预估返后 " + CatalogSamples.HIDDEN_MASK)
        }

        "DisclaimerFooter" -> {
            when (v) {
                "detail" -> {
                    DisclaimerFooter(
                        style = DisclaimerStyle.DETAIL_PANEL,
                        lines = listOf(DisclaimerLine(CatalogSamples.PRICE_BASIS), DisclaimerLine(CatalogSamples.REBATE_COMPARE)),
                    )
                }

                "list" -> {
                    DisclaimerFooter(
                        lines = listOf(DisclaimerLine(CatalogSamples.PRICE_BASIS_GENERAL), DisclaimerLine(CatalogSamples.REBATE_ESTIMATE)),
                    )
                }

                else -> {
                    DisclaimerInfoButton(label = CatalogSamples.INFO, expanded = false, onToggle = {})
                }
            }
        }

        "PlatformBadge" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                val samples =
                    if (v == "list") {
                        listOf("taobao" to CatalogSamples.TAOBAO, "jd" to CatalogSamples.JD)
                    } else {
                        listOf("pdd" to CatalogSamples.PDD)
                    }
                // loaded：左侧 14dp 包内官方图；fallback：缺官方图（天猫等，F-X-05a）只显示文字，不画占位方块
                samples.forEach { (code, name) -> PlatformBadge(name = name, platformCode = if (s == "fallback") "" else code) }
            }
        }

        "ProductCard" -> {
            ProductCardSample(v, s)
        }

        "BuyButton" -> {
            BuySample(v, s)
        }

        "AmountGrid" -> {
            AmountGridSample(v, hidden = s == "amount-hidden")
        }

        "WalletHeroCard" -> {
            HeroSample(v, s)
        }

        "EntryGrid" -> {
            EntryGrid(
                title = "订单与钱包",
                items =
                    listOfNotNull(
                        EntryItem(CouliIcons.orders, "我的订单") {},
                        EntryItem(CouliIcons.wallet, "钱包") {},
                        EntryItem(CouliIcons.ledger, "余额流水") {},
                        if (s == "hidden-entry") null else EntryItem(CouliIcons.claim, "找回订单") {},
                    ),
            )
        }

        "PriceTrendPanel" -> {
            PriceTrendPanel(
                title = "近 30 天券后价",
                expanded = s != "collapsed",
                onToggle = {},
                content =
                    when {
                        s == "loading" -> {
                            PriceTrendContent.Loading("加载中")
                        }

                        v == "records" -> {
                            PriceTrendContent.Records(
                                points = listOf(2990, 2990, 2790, 2890, 2990, 3190, 2990, 2790, 2990),
                                summary = "最低 ¥27.90，最高 ¥31.90，当前 ¥29.90",
                            )
                        }

                        else -> {
                            PriceTrendContent.Empty("暂无价格记录")
                        }
                    },
            )
        }

        "ShareMaterialPicker" -> {
            ShareSample(s)
        }

        "ShareCopyEditor" -> {
            CopyEditorSample(v)
        }

        "MatchBadge" -> {
            if (v == "normal") {
                MatchBadge("符合条件", MatchBadgeKind.MATCHED)
            } else {
                MatchBadge("规格待确认", MatchBadgeKind.UNCONFIRMED)
            }
        }

        "AiLabel" -> {
            AiLabel("内容由 AI 生成")
        }

        "AgentConsentSheet" -> {
            AgentConsentSample(checked = s == "checked")
        }

        "AuthSheet" -> {
            SheetFrame {
                CouliSheetBody(
                    title = "授权淘宝账号",
                    actions = { CouliButton(text = "去授权", onClick = {}, modifier = Modifier.fillMaxWidth()) },
                ) {
                    Text("购买前需完成淘宝授权，用于识别你的订单", style = CouliTypography.footnote, color = CouliColors.textPrimary)
                }
            }
        }

        "JumpTip" -> {
            JumpTipSample()
        }

        "StepUpSheet" -> {
            StepUpSample(s)
        }

        else -> {
            error("目录没有这个组件：$component")
        }
    }
}

// ———— 交互态（按下、输入中）只在样例里静态展示 ————

@Composable
private fun pressedSource(pressed: Boolean): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(pressed) {
        // 等一帧：组件内部收集交互的协程晚于这里启动，先发会丢（交互流不回放）
        withFrameNanos { }
        if (pressed) source.emit(PressInteraction.Press(Offset(PRESS_AT, PRESS_AT)))
    }
    return source
}

@Composable
private fun focusedSource(focused: Boolean): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(focused) {
        withFrameNanos { }
        if (focused) source.emit(FocusInteraction.Focus())
    }
    return source
}

/** 样例照片：一块暖色渐变（目录不拉网络图）。 */
@Composable
private fun samplePhoto() = remember { BrushPainter(Brush.linearGradient(listOf(Color(0xFFF3C7A4), Color(0xFFB4532E)))) }

@Composable
private fun ButtonSample(
    v: String,
    s: String,
    modifier: Modifier = Modifier,
) {
    val kind = v.substringBefore('-')
    val sizeName = v.substringAfter('-')
    val variant =
        when (kind) {
            "primary" -> CouliButtonVariant.PRIMARY
            "secondary" -> CouliButtonVariant.SECONDARY
            "text" -> CouliButtonVariant.TEXT
            else -> CouliButtonVariant.ON_SOLID
        }
    val text =
        when (variant) {
            CouliButtonVariant.PRIMARY -> "获取验证码"
            CouliButtonVariant.SECONDARY -> "保存图片"
            CouliButtonVariant.TEXT -> "登录遇到问题"
            CouliButtonVariant.ON_SOLID -> "提现"
        }
    val button: @Composable () -> Unit = {
        CouliButton(
            text = text,
            onClick = {},
            modifier = modifier.then(if (sizeName == "full") Modifier.fillMaxWidth() else Modifier),
            variant = variant,
            size = if (sizeName == "compact") CouliButtonSize.COMPACT else CouliButtonSize.STANDARD,
            enabled = s != "disabled",
            loading = s == "loading",
            loadingDescription = "加载中",
            interactionSource = pressedSource(s == "pressed"),
        )
    }
    if (variant == CouliButtonVariant.ON_SOLID) {
        // 狸橙实底卡内（钱包主卡）
        Box(
            Modifier
                .clip(RoundedCornerShape(CouliRadius.card))
                .background(CouliColors.rebateSolidBackground)
                .padding(CouliSpacing.space4),
        ) { button() }
    } else {
        button()
    }
}

@Composable
private fun IconButtonSample(
    v: String,
    s: String,
) {
    val pressed = pressedSource(s == "pressed")
    when (v) {
        "toggle" -> {
            val hidden = s == "selected"
            CouliIconButton(
                icon = if (hidden) CouliIcons.eyeOff else CouliIcons.eye,
                label = "隐藏金额",
                onClick = {},
                enabled = s != "disabled",
                interactionSource = pressed,
                modifier = Modifier.semantics { stateDescription = if (hidden) "已隐藏" else "未隐藏" },
            )
        }

        "badge" -> {
            Box {
                CouliIconButton(
                    icon = CouliIcons.bell,
                    label = "消息中心，有未读",
                    onClick = {},
                    enabled = s != "disabled",
                    interactionSource = pressed,
                )
                CouliBadge(contentDescription = "有未读", modifier = Modifier.align(Alignment.TopEnd).padding(CouliSpacing.space2))
            }
        }

        else -> {
            CouliIconButton(icon = CouliIcons.close, label = "关闭", onClick = {}, enabled = s != "disabled", interactionSource = pressed)
        }
    }
}

@Composable
private fun TextFieldSample(
    v: String,
    s: String,
    multiline: Boolean,
) {
    val filled = s == "filled" || s == "error" || s == "disabled" || s == "readonly"
    val value =
        when {
            !filled -> ""
            multiline -> "订单 3 天前已确认收货，但钱包里一直显示预估中，麻烦帮忙看一下。"
            else -> "凑狸小助手"
        }
    CouliTextField(
        value = value,
        onValueChange = {},
        placeholder = if (multiline) "请描述遇到的问题" else "请输入昵称",
        enabled = s != "disabled",
        readOnly = s == "readonly",
        error = if (s == "error") (if (multiline) "请至少输入 10 个字" else "昵称不能超过 20 个字") else null,
        clearLabel = if (v == "clear") "清除" else null,
        maxLength = if (v == "counter" || multiline) (if (multiline) 200 else 20) else null,
        singleLine = !multiline,
        interactionSource = focusedSource(s == "focused"),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val codeErrors =
    mapOf(
        "error-20002" to "验证码错误，请重新输入",
        "error-20003" to "验证码已失效，请重新获取",
        "error-42901" to "操作太频繁，请 58 秒后再试",
        "error-50001" to "服务开小差了，请稍后再试",
        "error-network" to "网络连接失败，请检查网络后重试",
        "error-44001" to "该手机号暂时无法登录，请联系客服",
        "error-10007" to "验证码发送太频繁，请稍后再试",
        "error-10405" to "登录失败，请稍后再试（10405）",
    )

@Composable
private fun CodeInputSample(
    v: String,
    s: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        val error = codeErrors[s]
        CodeInput(
            value = if (s == "submitting") "482913" else "",
            onValueChange = {},
            accessibilityLabel = "验证码",
            cells = v == "six-cell",
            submitting = s == "submitting",
            error = error != null,
            message = error ?: if (s == "submitting") "正在登录…" else null,
            interactionSource = focusedSource(s == "focused" || error != null),
        )
        when (s) {
            "cooldown" -> CodeResendRow(hint = "收不到验证码？", resendText = "重新获取", onResend = {}, countdown = "59 秒后可重新获取")
            "resend-ready" -> CodeResendRow(hint = "收不到验证码？", resendText = "重新获取", onResend = {})
        }
    }
}

@Composable
private fun CheckboxSample(
    v: String,
    s: String,
) {
    val checked = s == "checked" || s == "disabled-checked"
    val enabled = !s.startsWith("disabled")
    val pressed = pressedSource(s == "pressed")
    when (v) {
        "standalone" -> {
            CouliCheckbox(checked, {}, "同意劳务协议", enabled = enabled, interactionSource = pressed)
        }

        "row" -> {
            Box(Modifier.couliCard()) {
                CouliCheckboxRow(checked, {}, "淘宝授权", enabled = enabled, interactionSource = pressed, modifier = Modifier.fillMaxWidth())
            }
        }

        else -> {
            Box(Modifier.size(96.dp)) {
                ImagePlaceholder(state = ImageLoadState.SUCCESS, label = "商品图 1", painter = samplePhoto(), modifier = Modifier.size(96.dp))
                CouliCheckbox(
                    checked,
                    {},
                    "商品图 1",
                    enabled = enabled,
                    glyphSize = 22.dp,
                    interactionSource = pressed,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun NavBarSample(
    v: String,
    scrolled: Boolean,
) {
    when (v) {
        "secondary" -> {
            NavBar(title = "钱包", backLabel = CatalogSamples.BACK, onBack = {}, scrolled = scrolled, handleSystemBack = false)
        }

        "primary" -> {
            PageTitleBar(title = "我的", scrolled = scrolled) {
                CouliIconButton(icon = CouliIcons.settings, label = "设置", onClick = {})
            }
        }

        else -> {
            Box {
                ImagePlaceholder(
                    state = ImageLoadState.SUCCESS,
                    label = "商品头图",
                    painter = samplePhoto(),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    decorative = true,
                )
                NavBar(
                    title = "商品详情",
                    backLabel = CatalogSamples.BACK,
                    onBack = {},
                    scrolled = scrolled,
                    handleSystemBack = false,
                    trailing = { CouliIconButton(icon = CouliIcons.heart, label = "收藏", onClick = {}) },
                )
            }
        }
    }
}

@Composable
private fun ListRowSample(
    v: String,
    s: String,
) {
    Box(Modifier.couliCard().clip(RoundedCornerShape(CouliRadius.card))) {
        ListRow(
            title =
                when (v) {
                    "arrow" -> "账号与安全"
                    "value" -> "清除缓存"
                    else -> "剪贴板识别"
                },
            subtitle = if (v == "switch-subtitle") "复制商品链接后打开凑狸自动识别" else null,
            trailing =
                when (v) {
                    "arrow" -> ListRowTrailing.Arrow
                    "value" -> ListRowTrailing.Value("23.4 MB")
                    else -> ListRowTrailing.Toggle(checked = true, onCheckedChange = {})
                },
            enabled = s != "disabled",
            interactionSource = pressedSource(s == "pressed"),
        )
    }
}

@Composable
private fun TagSample(v: String) {
    when (v) {
        "pending" -> CouliTag("预估中", tone = CouliTagTone.NEUTRAL, icon = CouliIcons.clock)
        "success" -> CouliTag("已结算", tone = CouliTagTone.SUCCESS, icon = CouliIcons.check)
        "warning" -> CouliTag("规格待确认", tone = CouliTagTone.WARNING, icon = CouliIcons.alert)
        "error" -> CouliTag("已失效", tone = CouliTagTone.ERROR, icon = CouliIcons.alert)
        "benefit" -> CouliTag(CatalogSamples.HAS_COUPON)
        else -> CouliTag("热销", tone = CouliTagTone.SELECTED)
    }
}

@Composable
private fun ImagePlaceholderSample(
    v: String,
    s: String,
) {
    val state =
        when (s) {
            "loading" -> ImageLoadState.LOADING
            "success" -> ImageLoadState.SUCCESS
            else -> ImageLoadState.ERROR
        }
    val modifier =
        when (v) {
            "list96" -> Modifier.size(96.dp)
            "grid" -> Modifier.widthIn(max = 180.dp).fillMaxWidth().aspectRatio(1f)
            "horizontal" -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            else -> Modifier.fillMaxWidth().aspectRatio(1f)
        }
    ImagePlaceholder(state = state, label = "商品图", painter = samplePhoto(), modifier = modifier)
}

@Composable
private fun TimelineSample(s: String) {
    val states =
        when (s) {
            "completed" -> listOf(TimelineState.COMPLETED, TimelineState.COMPLETED, TimelineState.COMPLETED)
            "current" -> listOf(TimelineState.COMPLETED, TimelineState.CURRENT, TimelineState.FUTURE)
            else -> listOf(TimelineState.CURRENT, TimelineState.FUTURE, TimelineState.FUTURE)
        }
    val titles = listOf("已下单", "已确认收货", "已结算入账")
    val times = listOf("10 月 3 日 14:20", "10 月 9 日 09:12", null)
    Timeline(
        steps =
            titles.indices.map { index ->
                TimelineStep(
                    title = titles[index],
                    state = states[index],
                    time = times[index].takeIf { states[index] != TimelineState.FUTURE },
                    stateLabel =
                        when (states[index]) {
                            TimelineState.COMPLETED -> "已完成"
                            TimelineState.CURRENT -> "进行中"
                            TimelineState.FUTURE -> "未开始"
                        },
                )
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullSample(refreshing: Boolean) {
    val state = rememberPullToRefreshState()
    LaunchedEffect(refreshing) { state.snapTo(if (refreshing) 1f else PULL_FRACTION) }
    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.TopCenter) {
        CouliPullToRefreshIndicator(state = state, isRefreshing = refreshing)
    }
}

@Composable
private fun SkeletonSample(v: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        when (v) {
            "product" -> {
                Skeleton(Modifier.fillMaxWidth().aspectRatio(1.6f))
                Skeleton(Modifier.fillMaxWidth().height(16.dp))
                Skeleton(Modifier.fillMaxWidth(0.4f).height(24.dp))
            }

            "order" -> {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                        Skeleton(Modifier.size(64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                            Skeleton(Modifier.fillMaxWidth().height(16.dp))
                            Skeleton(Modifier.fillMaxWidth(0.6f).height(16.dp))
                        }
                    }
                }
            }

            else -> {
                Skeleton(Modifier.fillMaxWidth().height(120.dp))
                Skeleton(Modifier.fillMaxWidth().height(64.dp))
            }
        }
    }
}

@Composable
private fun NoticeSample(
    v: String,
    action: Boolean,
) {
    val (tone, text, label) =
        when (v) {
            "info" -> Triple(NoticeTone.INFO, "已识别到好友邀请码 K7M3QX", "移除")
            "warning" -> Triple(NoticeTone.WARNING, "价格已变动，以下单页为准", "刷新")
            "error" -> Triple(NoticeTone.ERROR, "登录已失效，请重新登录", "重新登录")
            else -> Triple(NoticeTone.SUCCESS, "已绑定邀请人", "查看")
        }
    NoticeBar(text = text, tone = tone, action = if (action) StateAction(label) {} else null)
}

@Composable
private fun DialogSample(
    v: String,
    stacked: Boolean,
) {
    val confirm = if (stacked) "同意并获取验证码" else "同意"
    val dismiss = if (stacked) "不同意，返回修改" else "不同意"
    when (v) {
        "primary-secondary" -> {
            CouliDialogContent(
                title = "请阅读并同意协议",
                message = "为保障你的权益，请先阅读并同意《用户协议》和《隐私政策》。",
                confirmText = confirm,
                onConfirm = {},
                dismissText = dismiss,
            )
        }

        "single" -> {
            CouliDialogContent(
                title = "注销处理中",
                message = "注销申请已提交，处理完成前不能再次登录。",
                confirmText = if (stacked) "我知道了，返回首页" else "我知道了",
                onConfirm = {},
            )
        }

        else -> {
            Box(Modifier.heightIn(max = 320.dp)) {
                CouliDialogContent(
                    title = "隐私政策摘要",
                    message = List(8) { "第 ${it + 1} 条：我们只在你同意后收集为完成服务所必需的信息，不会出售你的个人信息。" }.joinToString("\n"),
                    confirmText = confirm,
                    onConfirm = {},
                    dismissText = dismiss,
                )
            }
        }
    }
}

@Composable
private fun SheetSample(
    protected: Boolean,
    s: String,
) {
    val height =
        when (s) {
            "full" -> 520.dp
            else -> 280.dp
        }
    SheetFrame(modifier = Modifier.heightIn(min = height)) {
        CouliSheetBody(
            title = if (protected) "身份验证" else "价格区间",
            onClose = if (protected) ({}) else null,
            closeLabel = if (protected) "关闭" else null,
            actions = { CouliButton(text = if (protected) "确认提现" else "确定", onClick = {}, modifier = Modifier.fillMaxWidth()) },
        ) {
            if (s == "keyboard") {
                CouliTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = if (protected) "请输入验证码" else "最低价",
                    interactionSource = focusedSource(true),
                )
                Text("键盘弹起时操作区随键盘上移", style = CouliTypography.caption, color = CouliColors.textSecondary)
            } else {
                Text(
                    text = if (protected) "资金类半屏不能下滑或点遮罩关闭，可点右上关闭或系统返回。" else "下滑、点遮罩、返回都能关闭。",
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                )
            }
        }
    }
}

private fun agreementText(three: Boolean): AnnotatedString =
    buildAnnotatedString {
        append("我已阅读并同意")
        withLink(LinkAnnotation.Clickable("user") {}) { append("《用户协议》") }
        append(if (three) "、" else "和")
        withLink(LinkAnnotation.Clickable("privacy") {}) { append("《隐私政策》") }
        if (three) {
            append("和")
            withLink(LinkAnnotation.Clickable("carrier") {}) { append("《中国移动认证服务条款》") }
        }
        append("，未注册的手机号验证后将自动注册凑狸账号")
    }

private val loginTexts = ThirdPartyLoginTexts("微信登录", "华为账号登录", "登录遇到问题", "其他登录方式", "上次登录")

@Composable
private fun OAuthSample(
    v: String,
    s: String,
) {
    val provider = if (v == "wechat") OAuthProvider.WECHAT else OAuthProvider.HUAWEI
    OAuthButton(
        provider = provider,
        label = if (provider == OAuthProvider.WECHAT) loginTexts.wechatLabel else loginTexts.huaweiLabel,
        onClick = {},
        enabled = s != "disabled",
        authorizing = s == "authorizing",
        lastLoginLabel = if (s == "last-login") loginTexts.lastLoginLabel else null,
        interactionSource = pressedSource(s == "pressed"),
    )
}

@Composable
private fun LastLoginSample(
    v: String,
    visible: Boolean,
) {
    if (v == "oauth") {
        OAuthButton(
            provider = OAuthProvider.WECHAT,
            label = loginTexts.wechatLabel,
            onClick = {},
            lastLoginLabel = if (visible) loginTexts.lastLoginLabel else null,
        )
    } else {
        Box(Modifier.fillMaxWidth().padding(top = CouliSpacing.space3)) {
            CouliButton(text = "本机号码一键登录", onClick = {}, modifier = Modifier.fillMaxWidth())
            if (visible) {
                LastLoginTag(
                    text = loginTexts.lastLoginLabel,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = -CouliSpacing.space3),
                )
            }
        }
    }
}

/** 一键登录区：SDK 授权页的配置示意（号码、认证说明、运营商协议名由 SDK 提供）；取号失败等直接显示手机号页。 */
@Composable
private fun OneTapLoginSketch(s: String) {
    val fallback =
        when (s) {
            "number-failure" -> "取号失败，已切换到手机号登录"
            "unavailable" -> "当前网络不支持一键登录，已切换到手机号登录"
            "error-20004" -> "一键登录暂不可用（20004），请用手机号登录"
            "error-50305" -> "一键登录服务繁忙（50305），请用手机号登录"
            else -> null
        }
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        if (fallback != null) {
            NoticeBar(text = fallback, tone = NoticeTone.WARNING)
            PhoneField(value = "", onValueChange = {}, placeholder = "请输入手机号", clearLabel = "清除")
            CouliButton(text = "获取验证码", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
            return@Column
        }
        BrandMark()
        Text("本机号码", style = CouliTypography.caption, color = CouliColors.textSecondary)
        Text("138****5678", style = CouliTypography.heading, color = CouliColors.textPrimary)
        Text("中国移动提供认证服务", style = CouliTypography.caption, color = CouliColors.textSecondary)
        Box(Modifier.fillMaxWidth().padding(top = CouliSpacing.space2)) {
            CouliButton(text = "本机号码一键登录", onClick = {}, modifier = Modifier.fillMaxWidth())
            if (s == "last-login") {
                LastLoginTag(text = loginTexts.lastLoginLabel, modifier = Modifier.align(Alignment.TopEnd).offset(y = -CouliSpacing.space3))
            }
        }
        AgreementCheckRow(checked = s == "checked", onCheckedChange = {}, text = agreementText(three = true))
        CouliButton(text = "其他手机号登录", onClick = {}, variant = CouliButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PriceSample(
    v: String,
    long: Boolean,
    modifier: Modifier = Modifier,
) {
    val kind = v.substringBeforeLast('-')
    val size =
        when (v.substringAfterLast('-')) {
            "list" -> PriceTagSize.LIST
            "ai" -> PriceTagSize.AI
            else -> PriceTagSize.LARGE
        }
    val scale = if (long) 1000L else 1L
    val content =
        when (kind) {
            "coupon" -> PriceTagModel.make("taobao", 3990 * scale, 1000 * scale, 2990 * scale, false)
            "no-coupon" -> PriceTagModel.make("taobao", 3990 * scale, 0, 3990 * scale, false)
            "pdd" -> PriceTagModel.make("pdd", 1990 * scale, 300 * scale, 1690 * scale, false)
            else -> PriceTagModel.make("taobao", 9999 * scale, 0, 9999 * scale, true)
        }
    PriceTag(content = content, size = size, modifier = modifier)
}

@Composable
private fun RebateSample(
    v: String,
    s: String,
    modifier: Modifier = Modifier,
) {
    val content =
        when (v) {
            "single" -> {
                RebateTagModel.make("normal", 269, 269, false)
            }

            "range", "large" -> {
                if (s ==
                    "wrap-at-dash"
                ) {
                    RebateTagModel.make("price_compare_risk", 888888, 999999, false)
                } else {
                    RebateTagModel.make("price_compare_risk", 135, 269, false)
                }
            }

            "amount_unknown" -> {
                RebateTagModel.make("amount_unknown", null, null, false)
            }

            else -> {
                RebateTagModel.make("login_required", null, null, true)
            }
        }
    val shown = if (s == "amount-hidden") content.masked(AmountMask.DEFAULT_HIDDEN) else content
    Box(if (s == "wrap-at-dash") Modifier.widthIn(max = 168.dp) else Modifier) {
        RebateTag(content = shown, size = if (v == "large") RebateTagSize.LARGE else RebateTagSize.SMALL, modifier = modifier)
    }
}

private fun productFor(s: String): ProductCardData =
    when (s) {
        "single" -> CatalogSamples.emptyTissue
        "range" -> CatalogSamples.searchMilk
        "no-rebate" -> CatalogSamples.stressNoRebate
        "long-price" -> CatalogSamples.stressCoffeeSingle
        else -> CatalogSamples.emptyTissue.copy(amountMask = AmountMask.DEFAULT_HIDDEN)
    }

@Composable
private fun ProductCardSample(
    v: String,
    s: String,
) {
    if (s == "skeleton") {
        SkeletonProductCard(description = "加载中")
        return
    }
    val data = productFor(s)
    when (v) {
        "search" -> {
            ProductCardList(data = data, onBuy = {})
        }

        "ai" -> {
            ProductCardList(data = data, onBuy = {}, variant = ProductCardVariant.AI)
        }

        "favorite" -> {
            ProductCardList(data = data.copy(shopName = "乳品生活旗舰店"), onBuy = {})
        }

        "ranking" -> {
            ProductCardList(
                data = data.copy(benefitTags = listOf(BenefitTag("热销榜 第 1 名", CouliTagTone.SELECTED)) + data.benefitTags),
                onBuy = {},
            )
        }

        "grid" -> {
            ProductCardGridLayout(items = listOf(data), onInfo = {})
        }

        else -> {
            ProductCardLarge(data = data)
        }
    }
}

@Composable
private fun BuySample(
    v: String,
    s: String,
) {
    val rebate =
        when (v) {
            "single" -> RebateTagModel.make("normal", 269, 269, false)
            "range" -> RebateTagModel.make("price_compare_risk", 135, 269, false)
            else -> RebateTagModel.make("no_rebate", 0, 0, false)
        }
    BuyButton(
        base = if (v == "no-rebate") CatalogSamples.BUY_NO_REBATE else CatalogSamples.BUY_COUPON,
        rebate = rebate,
        onClick = {},
        state =
            when (s) {
                "loading" -> BuyButtonState.LOADING
                "disabled" -> BuyButtonState.DISABLED
                "retry-later" -> BuyButtonState.RETRY_LATER
                else -> BuyButtonState.NORMAL
            },
        retryLaterText = CatalogSamples.RETRY_LATER,
        loadingDescription = CatalogSamples.LOADING,
    )
}

private fun mask(hidden: Boolean) = AmountMask(hidden = hidden, mask = CatalogSamples.HIDDEN_MASK, spoken = CatalogSamples.AMOUNT_HIDDEN)

@Composable
private fun AmountGridSample(
    v: String,
    hidden: Boolean,
) {
    val help = v == "help"
    Box(Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4)) {
        AmountGrid(
            mask = mask(hidden),
            cells =
                listOf(
                    AmountCell(
                        "预估收益",
                        "¥56.78",
                        "预估收益，56.78 元",
                        note = "其中已收货 ¥34.56",
                        hiddenNote = "其中已收货 " + CatalogSamples.HIDDEN_MASK,
                        emphasis = true,
                        labelIcon = CouliIcons.clock,
                        helpLabel = if (help) "预估收益说明" else null,
                        onHelp = if (help) ({}) else null,
                    ),
                    AmountCell("已提现", "¥5.00", "已提现，5 元", helpLabel = if (help) "已提现说明" else null, onHelp = if (help) ({}) else null),
                ),
        )
    }
}

private val heroTexts =
    WalletHeroTexts(
        label = "可提现余额",
        amount = "¥12.34",
        amountSpoken = "可提现余额，12.34 元",
        withdraw = "提现",
        records = "提现记录",
        hideLabel = "隐藏金额",
        showLabel = "显示金额",
        hiddenSpoken = "可提现余额，" + CatalogSamples.AMOUNT_HIDDEN,
    )

@Composable
private fun HeroSample(
    v: String,
    s: String,
) {
    val zero = s == "zero"
    val texts =
        heroTexts.copy(
            amount = if (zero) "¥0.00" else heroTexts.amount,
            amountSpoken = if (zero) "可提现余额，0 元" else heroTexts.amountSpoken,
            records = if (v == "me") null else heroTexts.records,
        )
    WalletHeroCard(
        texts = texts,
        onWithdraw = {},
        amountHidden = s == "amount-hidden",
        hiddenMask = CatalogSamples.HIDDEN_MASK,
        onToggleHidden = {},
        onRecords = if (v == "wallet") ({}) else null,
        withdrawEnabled = !zero,
    )
}

@Composable
private fun ShareSample(s: String) {
    val materials = listOf(ShareMaterial("二维码海报", isQrCode = true)) + (1..7).map { ShareMaterial("商品图 $it") }
    val selected =
        when (s) {
            "selected" -> setOf(0, 2)
            "unselected" -> emptySet()
            "all-selected" -> materials.indices.toSet()
            else -> setOf(0, 1, 2)
        }
    ShareMaterialPicker(
        materials = materials,
        selected = selected,
        onToggle = {},
        selectAllText = if (s == "all-selected") "取消全选" else "全选",
        onSelectAll = {},
        saveCountText = if (s == "save-count") "将保存 ${selected.size} 张到相册" else "已选 ${selected.size} 张",
    )
}

@Composable
private fun CopyEditorSample(v: String) {
    val segments =
        listOf(
            ShareCopySegment("全脂纯牛奶 250ml × 24 盒 整箱装", checked = true, required = true),
            ShareCopySegment("领券下单更划算，到手价以下单页为准", checked = true),
            ShareCopySegment("复制这段话打开淘宝即可查看", checked = false),
        )
    ShareCopyEditor(
        segments = segments,
        onToggle = {},
        mode =
            when (v) {
                "view" -> ShareCopyMode.VIEW
                "edit" -> ShareCopyMode.EDIT
                else -> ShareCopyMode.OVERWRITE_CONFIRM
            },
        editedText = "全脂纯牛奶 250ml × 24 盒 整箱装，领券下单更划算",
        onEditedTextChange = {},
        overwriteNotice = "改勾选会覆盖你编辑过的文字",
        overwriteConfirm = StateAction("覆盖") {},
        overwriteCancel = StateAction("保留编辑") {},
    )
}

@Composable
private fun AgentConsentSample(checked: Boolean) {
    SheetFrame {
        CouliSheetBody(
            title = "开启 AI 助手",
            actions = { CouliButton(text = "同意并开启", onClick = {}, enabled = checked, modifier = Modifier.fillMaxWidth()) },
        ) {
            Text(
                "AI 助手会根据你的描述帮你找商品，对话内容用于生成结果，不会用于其他用途。",
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
            )
            AgreementCheckRow(
                checked = checked,
                onCheckedChange = {},
                text =
                    buildAnnotatedString {
                        append("我已阅读并同意")
                        withLink(LinkAnnotation.Clickable("ai") {}) { append("《AI 助手服务说明》") }
                    },
            )
        }
    }
}

@Composable
private fun JumpTipSample() {
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        CouliLoading(description = "正在打开淘宝")
        Text("正在打开淘宝…", style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
        Text("在淘宝下单后返利会自动记录", style = CouliTypography.footnote, color = CouliColors.textSecondary)
        CouliButton(text = "取消", onClick = {}, variant = CouliButtonVariant.SECONDARY, size = CouliButtonSize.COMPACT)
    }
}

@Composable
private fun StepUpSample(s: String) {
    SheetFrame {
        CouliSheetBody(title = "身份验证", onClose = {}, closeLabel = "关闭") {
            Text("验证码已发送至 138****5678", style = CouliTypography.footnote, color = CouliColors.textSecondary)
            CodeInput(
                value = if (s == "submitting") "482913" else "",
                onValueChange = {},
                accessibilityLabel = "验证码",
                submitting = s == "submitting",
                error = s == "error",
                message =
                    when (s) {
                        "submitting" -> "正在验证…"
                        "error" -> "验证码错误，请重新输入"
                        else -> null
                    },
                interactionSource = focusedSource(s == "focused"),
            )
        }
    }
}

private val SAMPLE_GAP = 8.dp
private val SAMPLE_MIN = 32.dp
private const val PRESS_AT = 24f
private const val PULL_FRACTION = 0.6f
