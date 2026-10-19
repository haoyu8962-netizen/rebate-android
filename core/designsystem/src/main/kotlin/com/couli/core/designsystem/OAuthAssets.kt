package com.couli.core.designsystem

import androidx.compose.runtime.Immutable

/** 第三方登录行的文案（全部由调用方从字典给：login.* 键）。 */
@Immutable
data class ThirdPartyLoginTexts(
    val wechatLabel: String,
    val huaweiLabel: String,
    val helpLabel: String,
    val dividerLabel: String,
    val lastLoginLabel: String,
)

/** 第三方登录渠道（组件清单 §3.7；iOS 的 Apple 不在圆钮行）。 */
enum class OAuthProvider {
    WECHAT,
    HUAWEI,
}

/**
 * 官方平台标识是否已核准入包（DS-02）：官方素材由负责人放进规划仓库 `design/icons/platform/`，人工核源后登记
 * `core/designsystem/src/test/resources/ds02-approved-oauth-assets.properties`（resource / sha256 / source），
 * 再把矢量放进 `src/main/res/drawable/` 并在 [oauthIconResource] 接线。未到位前圆钮不画任何标识、明确标注「待官方素材」，
 * 不用通用聊天气泡等图标冒充（规划/01 F-PRIV-08）。
 */
internal object OAuthAssets {
    /** 核准后改为 true（与核准表同一次提交）。 */
    const val APPROVED: Boolean = false

    /** 未核准时的可见标注（开发期标记；登录页迁移到 ThirdPartyLoginRow 前素材必须到位）。 */
    const val PENDING_LABEL: String = "待官方素材"
}

/**
 * 官方标识的 drawable 资源 id。只在素材核准后调用（[OAuthAssets.APPROVED]）；未核准时抛错，防止拿任意图标顶替。
 */
fun oauthIconResource(provider: OAuthProvider): Int = error("官方平台标识尚未核准入包：${provider.name}（见 design/icons/platform/README.md）")
