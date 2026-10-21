package com.couli.app.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.HomeClipboardPaste
import com.couli.app.clipboard.HomeClipboardTop
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.couliCard
import com.couli.core.home.HomeDraftTexts
import com.couli.feature.home.HomeListKeys

/** 首页原生头部（APP-11v）的测试标记。 */
object HomeNativeHeader {
    /** 游客价值卡容器（无整卡点击、无关闭）。 */
    const val GUEST_VALUE = "home_guest_value"

    /** 价值卡上的【登录领返利】。 */
    const val GUEST_LOGIN = "home_guest_login"
}

/**
 * 首页列表的原生头部项（APP-11v G-V02，Main-v3 / HomeGuest-v3 自上而下）：剪贴板提示条与内联提示 → 游客价值卡（只游客 / 基本模式）→ 常驻粘贴卡。
 * 键一律走 [HomeListKeys.header]（不进 SDUI 卡片命名空间，共性第 24 条）；每项限宽 600、左右 16，与 SDUI 卡同一宽度。
 * [clipboard] 为 null（基本模式、只测外壳）时没有剪贴板项。[guest] 来自可观察的会话状态：登录后立即撤掉价值卡，不等新首页加载。
 */
@Composable
internal fun rememberHomeNativeHeader(
    clipboard: AppClipboard?,
    guest: Boolean,
    onLogin: () -> Unit,
): LazyListScope.() -> Unit {
    val login by rememberUpdatedState(onLogin)
    return remember(clipboard, guest) {
        {
            if (clipboard != null) {
                item(key = HomeListKeys.header("clipboard"), contentType = "home_native") {
                    NativeItem { HomeClipboardTop(clipboard) }
                }
            }
            if (guest) {
                item(key = HomeListKeys.header("guest_value"), contentType = "home_native") {
                    NativeItem { GuestValueCard(onLogin = { login() }, modifier = Modifier.padding(bottom = CouliSpacing.space3)) }
                }
            }
            if (clipboard != null) {
                item(key = HomeListKeys.header("paste"), contentType = "home_native") {
                    NativeItem { HomeClipboardPaste(clipboard) }
                }
            }
        }
    }
}

@Composable
private fun NativeItem(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .widthIn(max = HOME_NATIVE_MAX_WIDTH.dp)
                .fillMaxWidth()
                .padding(horizontal = CouliSpacing.space4),
    ) { content() }
}

/**
 * 游客价值卡（HomeGuest-v3）：白卡，左「下单前先在凑狸查返利」17/600，右【登录领返利】浅底次按钮 → Login（经 Router，入口 in_app）。
 * 不做关闭、无金额；放不下时按钮换到下一行（大字号）。
 */
@Composable
internal fun GuestValueCard(
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(HomeNativeHeader.GUEST_VALUE)
                .couliCard()
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space3, top = CouliSpacing.space3, bottom = CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = HomeDraftTexts.text(HomeDraftTexts.GUEST_VALUE_DESC),
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.weight(1f).widthIn(min = GUEST_TEXT_MIN_WIDTH.dp),
        )
        HomeTonalButton(
            text = HomeDraftTexts.text(HomeDraftTexts.GUEST_VALUE_LOGIN),
            onClick = onLogin,
            modifier = Modifier.testTag(HomeNativeHeader.GUEST_LOGIN),
        )
    }
}

/**
 * 首页的浅底次按钮（Main-v3【问 AI】、HomeGuest-v3【登录领返利】）：brand.subtle 底 + brand.emphasis 字 14/600，可带 20 图标；最小高 48、圆角 12，
 * 系统水波纹。组件库暂无此变体（DS-02 跟进），先在首页内用令牌组合。
 */
@Composable
internal fun HomeTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .clip(shape)
                .background(CouliColors.brandSubtle, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = if (icon != null) CouliSpacing.space3 else CouliSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = CouliColors.brandEmphasis, modifier = Modifier.size(CouliSizes.iconMedium))
        }
        Text(text = text, style = CouliTextStyles.footnoteSemibold, color = CouliColors.brandEmphasis)
    }
}

/** 与搜索、详情页同为 600dp（03 §10.4）。 */
private const val HOME_NATIVE_MAX_WIDTH = 600

/** 价值卡文字至少留 160 宽，放不下时按钮换行（HomeGuest-v3 flex 1 1 160px）。 */
private const val GUEST_TEXT_MIN_WIDTH = 160
