package com.couli.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 第三方登录行（组件清单 §3.7，03 §10.2 `ThirdPartyLoginRow`）：「其他登录方式」分隔线 + 一排 [OAuthButton] +「登录遇到问题」。
 * 华为渠道包华为账号排第一、微信第二；未装微信不显示微信（BR-ID-04）；整行为空时只留「登录遇到问题」。
 * [authorizing] 不为空时（授权中）全部圆钮不可点，正在授权的那颗显示进度；[enabled] 为 false（42901 冷却等）同样不可点。
 * [lastLogin] 是上次登录方式：标签挂在那颗圆钮右上，读屏并入其名称「微信登录，上次登录」。
 */
@Composable
fun ThirdPartyLoginRow(
    wechatInstalled: Boolean,
    huaweiChannel: Boolean,
    texts: ThirdPartyLoginTexts,
    onLogin: (OAuthProvider) -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    authorizing: OAuthProvider? = null,
    lastLogin: OAuthProvider? = null,
) {
    val providers =
        buildList {
            if (huaweiChannel) add(OAuthProvider.HUAWEI)
            if (wechatInstalled) add(OAuthProvider.WECHAT)
        }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) {
        if (providers.isNotEmpty()) {
            CouliDivider(text = texts.dividerLabel, modifier = Modifier.testTag(OAUTH_DIVIDER_TAG))
            FlowRow(
                // 命中区 64 宽、圆心距 = 48 + 32：圆钮间距 32（T12）减去两侧命中扩展各 8
                horizontalArrangement =
                    Arrangement.spacedBy(
                        CouliComponentTokens.oauthGap - OAUTH_TARGET_EXTRA * 2,
                        Alignment.CenterHorizontally,
                    ),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                providers.forEach { provider ->
                    OAuthButton(
                        provider = provider,
                        label = if (provider == OAuthProvider.WECHAT) texts.wechatLabel else texts.huaweiLabel,
                        onClick = { onLogin(provider) },
                        enabled = enabled && authorizing == null,
                        authorizing = authorizing == provider,
                        lastLoginLabel = if (lastLogin == provider) texts.lastLoginLabel else null,
                    )
                }
            }
        }
        CouliButton(text = texts.helpLabel, onClick = onHelp, variant = CouliButtonVariant.TEXT)
    }
}

/**
 * 第三方登录圆形按钮（组件清单 §3.7 `OAuthButton`）：48 圆（T12）+ 官方标识 + 下方短名；命中区 64 × ≥48，系统水波纹（无界）。
 * 官方标识未核准前（[OAuthAssets.APPROVED] = false）圆内不画标识，下方明确标注「待官方素材」，不拿通用图标冒充。
 * [authorizing] 时圆内显示进度（读屏为进度）；[lastLoginLabel] 不为空时右上挂 [LastLoginTag]，读屏并入名称。
 */
@Composable
fun OAuthButton(
    provider: OAuthProvider,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    authorizing: Boolean = false,
    lastLoginLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val name = provider.name
    Box(
        modifier =
            modifier
                .testTag("$OAUTH_BUTTON_TAG/$name")
                .width(OAuthButtonWidth)
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false, radius = CouliComponentTokens.oauthSize / 2),
                    // 授权中不能重复触发（不依赖外层 ThirdPartyLoginRow 传 enabled=false）；读屏同时报禁用
                    enabled = enabled && !authorizing,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    contentDescription = label
                    // 进度条自带合并边界，不会并入按钮：授权中由按钮自己报「进行中」
                    if (authorizing) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Box(
                modifier =
                    Modifier
                        .testTag("$OAUTH_CIRCLE_TAG/$name")
                        .size(CouliComponentTokens.oauthSize)
                        .clip(CircleShape)
                        .background(if (enabled || authorizing) CouliColors.backgroundSurface else CouliColors.backgroundMuted)
                        .border(CouliSizes.hairline, CouliColors.borderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (authorizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(CouliSpacing.space6),
                        color = CouliColors.brandPrimary,
                        trackColor = CouliColors.brandSubtle,
                        strokeWidth = CouliSizes.hairline * 2,
                    )
                } else if (OAuthAssets.APPROVED) {
                    Image(
                        painter =
                            androidx.compose.ui.res
                                .painterResource(oauthIconResource(provider)),
                        contentDescription = null,
                        modifier = Modifier.size(CouliSpacing.space6).testTag("$OAUTH_ICON_TAG/$name"),
                    )
                }
            }
            Text(
                text = label,
                style = CouliTypography.caption,
                color = if (enabled || authorizing) CouliColors.textSecondary else CouliColors.buttonPrimaryDisabledText,
                textAlign = TextAlign.Center,
            )
            if (!OAuthAssets.APPROVED) {
                Text(
                    text = OAuthAssets.PENDING_LABEL,
                    style = CouliTypography.caption,
                    color = CouliColors.statusWarningText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("$OAUTH_PENDING_TAG/$name"),
                )
            }
        }
        if (lastLoginLabel != null) {
            LastLoginTag(
                text = lastLoginLabel,
                modifier =
                    Modifier
                        // 挂在圆钮右上、向右伸出（不受 64 命中区宽度限制，不折行）
                        .align(Alignment.TopStart)
                        .offset(x = OAUTH_TAG_START, y = -CouliSpacing.space2)
                        .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                        .testTag("$OAUTH_LAST_LOGIN_TAG/$name"),
            )
        }
    }
}

/** 上次登录标签（组件清单 §3.7 `LastLoginTag`）：小胶囊，brand.subtle 底、brand.emphasis 字、surface 描边；挂圆钮或主按钮右上。 */
@Composable
fun LastLoginTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    Text(
        text = text,
        style = CouliTextStyles.captionMedium,
        color = CouliColors.brandEmphasis,
        modifier =
            modifier
                .clip(shape)
                .background(CouliColors.brandSubtle)
                .border(CouliSizes.hairline, CouliColors.backgroundSurface, shape)
                .padding(horizontal = CouliSpacing.space2),
    )
}

/**
 * 品牌标识（组件清单 §3.7 `BrandMark`）：正式 Logo 图形（规划仓库 design/logo/source/logo-mark.svg 的两段路径，官方固定狸橙，不随主题），
 * 默认 56；读屏跳过（装饰）。
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = BRAND_MARK_SIZE,
) {
    Image(
        imageVector = BrandMarkVector.mark,
        contentDescription = null,
        modifier = modifier.size(size).clearAndSetSemantics { },
    )
}

private object BrandMarkVector {
    private const val VIEWPORT = 256f
    private const val PATH =
        "M69 89 79 45Q81 37 87 42L110 60Q126 51 145 54L161 38Q168 31 171 40L180 73C198 91 194 111 175 121 157 130 134 126 115 119 " +
            "97 112 89 119 83 132 70 163 91 193 122 194 142 195 161 184 172 170L203 189C183 216 154 229 120 226 67 223 29 184 33 139 " +
            "35 114 50 99 69 89Z M181 144Q184 134 195 136C207 138 214 150 210 164L207 174 178 158Z"

    val mark: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "BrandMark",
                defaultWidth = BRAND_MARK_SIZE,
                defaultHeight = BRAND_MARK_SIZE,
                viewportWidth = VIEWPORT,
                viewportHeight = VIEWPORT,
            ).addPath(
                pathData = PathParser().parsePathString(PATH).toNodes(),
                // 官方 Logo 色 = 狸橙（brand.primary 的令牌源值），固定不随深色变化
                fill = SolidColor(CouliColors.all.getValue("brand.primary")),
            ).build()
    }
}

private val BRAND_MARK_SIZE = 56.dp

/** 上次登录标签左缘：圆心右侧（命中区左缘起 36）。 */
private val OAUTH_TAG_START = 36.dp

/** 圆钮命中区比可见圆每侧多 8（64 宽）。 */
private val OAUTH_TARGET_EXTRA = 8.dp

const val OAUTH_BUTTON_TAG = "oauth-button"
const val OAUTH_CIRCLE_TAG = "oauth-circle"
const val OAUTH_ICON_TAG = "oauth-icon"
const val OAUTH_PENDING_TAG = "oauth-pending"
const val OAUTH_LAST_LOGIN_TAG = "oauth-last-login"
const val OAUTH_DIVIDER_TAG = "oauth-divider"

/** 圆钮命中区宽 = 48 圆 + 两侧各 8（固定宽，长短名换行，圆心距不随文字变）。 */
private val OAuthButtonWidth: Dp = CouliComponentTokens.oauthSize + OAUTH_TARGET_EXTRA * 2
