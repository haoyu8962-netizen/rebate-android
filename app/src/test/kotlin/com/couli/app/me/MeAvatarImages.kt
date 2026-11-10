package com.couli.app.me

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.designsystem.ProductImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 无网络、无协议过滤、无账号隔离；刻意让错误 URL 也能返回图，避免假源替产品挡错。 */
internal class MeAvatarImages : ProductImageLoader {
    data class Request(
        val url: String,
        val answer: CompletableDeferred<ImageBitmap?> = CompletableDeferred(),
    )

    val requests = mutableListOf<Request>()
    var failImmediately = false
    var ignoreCancellation = false
    private val bitmaps = mutableListOf<Bitmap>()

    override suspend fun load(url: String): ImageBitmap? {
        val request = Request(url).also { requests += it }
        if (failImmediately) request.answer.complete(null)
        return if (ignoreCancellation) {
            withContext(NonCancellable) { request.answer.await() }
        } else {
            request.answer.await()
        }
    }

    fun finish(
        index: Int,
        color: Int?,
    ) {
        val image =
            color?.let {
                Bitmap
                    .createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(it) }
                    .also { bitmaps += it }
                    .asImageBitmap()
            }
        requests[index].answer.complete(image)
    }

    fun release() {
        requests.forEach { it.answer.complete(null) }
        // 位图留给测试 Activity 销毁后 GC，不能在 Compose 仍可能绘制时 recycle。
        bitmaps.clear()
    }

    companion object {
        const val A = "https://avatar.invalid/account-a.png"
        const val B = "https://avatar.invalid/account-b.png"
        const val SHARED = "https://avatar.invalid/shared.png"
    }
}
