package com.couli.core.designsystem

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 平台内置图标（F-X-05a；规划/08 BR-TEXT-24）。图是负责人提供的官方原图，逐字节放在 assets/platform/
 * （来源提交与 SHA-256 见同目录 manifest.json），只当图片解码显示：不着色、不反色、不放大文件。
 * 暂缺官方图的平台（天猫、微信、微信支付、支付宝、企业微信等）返回 null，由调用方只显示文字，不画占位方块。
 */
private val PLATFORM_ICON_FILES: Map<String, String> =
    mapOf(
        "taobao" to "taobao",
        "jd" to "jd",
        // 平台编码 pdd ↔ 标识键 pinduoduo（BR-TEXT-24 表）
        "pdd" to "pinduoduo",
        "vip" to "vip",
        "douyin" to "douyin",
        "meituan" to "meituan",
        "eleme" to "eleme",
        "fliggy" to "fliggy",
        "didi" to "didi",
    )

/**
 * 只给旧调用（仅传平台中文名、没给编码）兼容取图用；中文名本身仍由调用方从文案字典传入（BR-TEXT-12）。
 * 显式给了编码（含空串、未知编码）时不走这里，不拿名字猜图。
 */
private val LEGACY_NAME_CODES: Map<String, String> =
    mapOf(
        "淘宝" to "taobao",
        "京东" to "jd",
        "拼多多" to "pdd",
        "唯品会" to "vip",
        "抖音" to "douyin",
        "美团" to "meituan",
        "饿了么" to "eleme",
        "飞猪" to "fliggy",
        "滴滴" to "didi",
    )

private const val PLATFORM_ASSET_DIR = "platform"

/** 平台编码 → 包内图片路径（AssetManager 相对路径，如 `platform/pinduoduo.png`）；未知或暂缺返回 null。 */
fun platformIconAssetPath(platformCode: String): String? = PLATFORM_ICON_FILES[platformCode]?.let { "$PLATFORM_ASSET_DIR/$it.png" }

/** 平台图标两档：徽标 14、首页金刚区平台格 44（APP-11v A7）。同一张原图按尺寸缩小显示。 */
enum class PlatformIconSize(
    val dp: Dp,
) {
    BADGE(14.dp),
    HOME(44.dp),
}

/** 徽标实际取图用的编码：显式编码优先；只有没给编码的旧调用才按中文名兼容。 */
internal fun badgeIconCode(
    name: String,
    platformCode: String?,
): String = platformCode ?: LEGACY_NAME_CODES[name].orEmpty()

/**
 * 按平台编码画内置官方图标（装饰性，读屏不可见，不单独可聚焦）。暂缺 / 未知编码什么都不画。
 * [remoteUrl] 只是 F-X-05 后台替换图的签名预留：本任务一律走内置图，不下载、不改变画面与读屏。
 */
@Composable
fun PlatformIcon(
    platformCode: String,
    modifier: Modifier = Modifier,
    size: PlatformIconSize = PlatformIconSize.BADGE,
    @Suppress("UnusedParameter") remoteUrl: String? = null,
) {
    val path = platformIconAssetPath(platformCode) ?: return
    val assets = LocalContext.current.assets
    val bitmap = remember(path) { PlatformIconCache.load(assets, path) } ?: return
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size.dp))
}

/** 解码后的原图按路径缓存（9 张 224 方图），列表滚动不重复解码；读不到时返回 null 而不是崩溃。 */
private object PlatformIconCache {
    private val bitmaps = ConcurrentHashMap<String, ImageBitmap>()

    fun load(
        assets: AssetManager,
        path: String,
    ): ImageBitmap? =
        bitmaps[path] ?: try {
            assets
                .open(path)
                .use { stream -> BitmapFactory.decodeStream(stream) }
                ?.asImageBitmap()
                ?.also { bitmaps[path] = it }
        } catch (_: IOException) {
            null
        }
}
