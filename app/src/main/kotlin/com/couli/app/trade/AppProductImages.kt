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
 */
class AppProductImages(
    base: OkHttpClient,
) : ProductImageLoader {
    private val client =
        base
            .newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    @Suppress("ReturnCount") // 非 https、命中缓存、取不到、解不出、成功
    override suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith(HTTPS)) return null
        cache.get(url)?.let { return it }
        val bytes = fetch(url) ?: return null
        val image =
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
            } ?: return null
        cache.put(url, image)
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

    private companion object {
        const val HTTPS = "https://"
        const val IMAGE = "image"
        const val TIMEOUT_SECONDS = 15L
        const val CACHE_ENTRIES = 48
        const val MAX_EDGE = 1024
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}
