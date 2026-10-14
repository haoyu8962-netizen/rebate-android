package com.couli.app.trade

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.designsystem.ProductImageLoader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException

/**
 * 商品图加载（APP-06a 共性第 22 条，契约 ProductCard.image）：只取 https 图片（不带任何凭据、设备标识或签名头），按显示需要降采样解码，
 * 内存里留最近用过的若干张。失败、非图片、超大都返回 null（页面显示占位）。页面离开时协程取消，在途请求随之取消。
 *
 * 缓存与在途结果绑定「环境 + 替身许可」（F-X-02，FU-AND-0714 第 1 轮评审 R1-05）：[source] 每次现算（debug / staging 为「当前环境 + Debug 统一闸门
 * 开没开」，Release 恒定）。它一变（例如在 test 用假服务加载过图片后切到 prod），先清空内存缓存，旧的位图不再交出；加载开始时记下它，取回字节后、
 * 解码之后、写缓存之前各核对一次，已变的结果丢弃（不交、不缓存）。
 */
class AppProductImages(
    base: OkHttpClient,
    /** 「环境 + 替身许可」的现值（[variantImageSource]；单测注入）。 */
    private val source: () -> String = { RELEASE_SOURCE },
    /** 解码（单测替换：模拟解码期间切环境）。 */
    private val decode: suspend (ByteArray) -> ImageBitmap? = ::decodeSampled,
) : ProductImageLoader {
    private val client =
        base
            .newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    /** 缓存里的位图属于哪一份「环境 + 替身许可」（与 [cache] 同一把锁）。 */
    private var cachedSource: String? = null

    /** 设置页「清除缓存」（APP-12，AC-S1-137）：清掉内存里的图片缓存。 */
    fun clearMemory() = cache.evictAll()

    /** 现值；读不出按一个不会与任何现值相等的值（不交出、不缓存）。 */
    private fun sourceNow(): String = runCatching(source).getOrDefault(UNKNOWN_SOURCE)

    /** 现值与 [cachedSource] 不同就先清空缓存（切环境、关替身之后旧位图不再交出）。 */
    private fun cacheFor(now: String): LruCache<String, ImageBitmap> =
        synchronized(cache) {
            if (now != cachedSource) {
                cache.evictAll()
                cachedSource = now
            }
            cache
        }

    @Suppress("ReturnCount") // 非 https、命中缓存、取不到、来源已变、解不出、成功
    override suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith(HTTPS)) return null
        val started = sourceNow()
        if (started == UNKNOWN_SOURCE) return null
        cacheFor(started).get(url)?.let { return it }
        val bytes = fetch(url) ?: return null
        // 等待期间（取图在途）环境或替身许可变了：丢弃
        if (sourceNow() != started) return null
        val image = decode(bytes) ?: return null
        // 解码期间变了：丢弃，不交、不缓存
        synchronized(cache) {
            if (sourceNow() != started || cachedSource != started) return null
            cache.put(url, image)
        }
        return image
    }

    private suspend fun fetch(url: String): ByteArray? {
        val request =
            runCatching {
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()
            }.getOrNull() ?: return null
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val bytes =
                            response.use {
                                val body = it.body
                                val image = it.isSuccessful && body.contentType()?.type == IMAGE
                                if (image && body.contentLength() <= MAX_BYTES) runCatching { body.bytes() }.getOrNull() else null
                            }
                        if (continuation.isActive) continuation.resume(bytes?.takeIf { it.size <= MAX_BYTES })
                    }
                },
            )
        }
    }

    companion object {
        /** Release 没有替身：来源恒定。 */
        const val RELEASE_SOURCE = "release"
        private const val UNKNOWN_SOURCE = "(unknown)"
        private const val HTTPS = "https://"
        private const val IMAGE = "image"
        private const val TIMEOUT_SECONDS = 15L
        private const val CACHE_ENTRIES = 48
        private const val MAX_EDGE = 1024
        private const val MAX_BYTES = 5L * 1024 * 1024

        /** 按显示需要降采样解码（在 Default 上）。 */
        private suspend fun decodeSampled(bytes: ByteArray): ImageBitmap? =
            withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2
                BitmapFactory
                    .decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )?.asImageBitmap()
            }
    }
}
