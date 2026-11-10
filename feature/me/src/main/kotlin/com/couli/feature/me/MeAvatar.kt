// 「我的」资料卡头像（FU-AND-ME-AVATAR，D10-644；BR-ID-39）：显示 /v1/me 下发的 avatar_url，内置人形只作加载中 / 失败 / 空值 / 非 https 的兜底。
// 页面私有件（不进 DS）：图片经 App 在根部提供的 [LocalProductImageLoader] 加载，不新增依赖。

package com.couli.feature.me

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.me.Me
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonPrimitive

/**
 * 头像（Me / MeDark / MeGuest 画板）：56 圆、muted 底；有图时圆内满铺服务端图（Crop、不染色），否则人形 32 次文字色。
 * - 只加载 `https://` 开头的地址（与 App 图片客户端同一口径、大小写敏感）；空值、非 https、没有图片源时不调用加载，直接兜底。
 * - 状态按 [owner]（身份）+ 地址 + 图片源取键：换号、退出、同账号换地址都从兜底重新开始，同一地址换号也重新加载；
 *   旧的加载随键变化取消，迟到结果写不进新状态。
 * - 读屏：装饰，不单独朗读、不可点、不声明角色（画板无读屏文字）。
 */
@Composable
internal fun MeAvatar(
    url: String? = null,
    owner: Any? = null,
) {
    val loader = LocalProductImageLoader.current
    val target = url?.takeIf { it.startsWith(HTTPS) }
    var bitmap by remember(owner, target, loader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(owner, target, loader) {
        if (target == null || loader == null) return@LaunchedEffect
        // 与商品图同口径：加载异常按失败处理（兜底）；键已变（被取消）时结果丢弃
        val loaded = runCatching { loader.load(target) }.getOrNull()
        if (isActive) bitmap = loaded
    }
    Box(
        // 底与兜底态和原内置头像逐像素一致（只给图片裁圆），旧「我的」整页快照零变化
        modifier = Modifier.size(SIZE).background(CouliColors.backgroundMuted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
        } else {
            Icon(
                CouliIcons.user,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSpacing.space8),
            )
        }
    }
}

/** 资料里的 avatar_url（契约必填 string；MeJson 已校验，这里只取值）。 */
internal fun meAvatarUrl(me: Me): String? = (me.raw["avatar_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val HTTPS = "https://"
private val SIZE = CouliSpacing.space12 + CouliSpacing.space2
