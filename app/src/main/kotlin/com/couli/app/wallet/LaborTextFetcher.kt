package com.couli.app.wallet

import androidx.core.text.HtmlCompat
import com.couli.core.network.RequestSigner
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException

/**
 * 劳务协议全文的生产取文函数（FU-AND-LABOR-AGREEMENT-API，RemoteLaborBackend 的 fetchText(url, expectedSha256)）：按契约 text_url 取回全文。
 * - 只取 https（不收带用户名 / 密码的地址），主机不限（契约样例的 text_url 在 CDN 上）；正文完整性靠下面的摘要核对保证；
 * - 整次调用 10 秒超时，不跟随重定向（只收 200）；正文上限 1 MiB（声明长度或实际读到的超过都按取不到）；
 * - 经 App 的 HTTP 客户端（与商品图同一个 variantHttpClient）裸发 GET：请求里不带登录令牌、签名头、设备标识（这些只由 ApiClient 自己加），
 *   并且关掉 Cookie（CookieJar.NO_COOKIES）、401 认证回调与缓存；
 * - 按取回的**原始字节**算 sha256（小写 hex），与 expectedSha256（服务端给的 text_sha256）相等才交出；不等按取不到；
 * - Content-Type 是 text/html（或正文明显是 HTML）时用 HtmlCompat 转成纯文字再交给页面（不经 WebView）；摘要始终按原始字节算。
 * 取不到一律返回 null（RemoteLaborBackend 按加载失败处理，不可签）。本文件不打印任何内容。
 */
internal class LaborTextFetcher(
    base: () -> OkHttpClient,
) {
    private val client: OkHttpClient by lazy {
        base()
            .newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .cache(null)
            .build()
    }

    @Suppress("ReturnCount") // 地址不许可、取不到、摘要不符、成功
    suspend fun fetch(
        url: String,
        expectedSha256: String,
    ): String? {
        val target = url.toHttpUrlOrNull()?.takeIf { allowed(it) } ?: return null
        val raw = download(target) ?: return null
        return withContext(Dispatchers.Default) {
            if (RequestSigner.sha256Hex(raw.bytes) != expectedSha256) return@withContext null
            display(raw)?.takeIf { it.isNotBlank() }
        }
    }

    /** 只要求 https，且地址里不带用户名 / 密码；主机不限。 */
    private fun allowed(target: HttpUrl): Boolean = target.isHttps && target.username.isEmpty() && target.password.isEmpty()

    private class Raw(
        val bytes: ByteArray,
        val type: MediaType?,
    )

    private suspend fun download(target: HttpUrl): Raw? {
        val request =
            runCatching {
                Request
                    .Builder()
                    .url(target)
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
                        val raw = response.use { runCatching { read(it) }.getOrNull() }
                        if (continuation.isActive) continuation.resume(raw)
                    }
                },
            )
        }
    }

    /** 只收 200；声明长度或实际读到的超过上限按取不到（最多读上限 + 1 字节）。 */
    private fun read(response: Response): Raw? {
        if (response.code != HTTP_OK) return null
        val body = response.body
        if (body.contentLength() > MAX_BYTES) return null
        val source = body.source()
        if (source.request(MAX_BYTES + 1)) return null
        return Raw(source.buffer.readByteArray(), body.contentType())
    }

    /** 原始字节按声明的字符集（缺省 UTF-8）解码；HTML 转纯文字。 */
    private fun display(raw: Raw): String? {
        val charset = runCatching { raw.type?.charset(Charsets.UTF_8) }.getOrNull() ?: Charsets.UTF_8
        val text = String(raw.bytes, charset).removePrefix(BOM)
        return if (isHtml(raw.type, text)) plain(text) else text.trim()
    }

    private fun isHtml(
        type: MediaType?,
        text: String,
    ): Boolean = (type?.type == "text" && type.subtype == "html") || HTML_SNIFF.containsMatchIn(text.take(SNIFF_CHARS))

    /** 先去掉不展示的段（head、script、style），再交给 HtmlCompat；多余空行收成一个。 */
    private fun plain(html: String): String {
        val body = HIDDEN_BLOCKS.replace(html, "")
        return HtmlCompat
            .fromHtml(body, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(NBSP, ' ')
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .trim()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val MAX_BYTES = 1024L * 1024
        const val HTTP_OK = 200
        const val SNIFF_CHARS = 512
        const val BOM = "\uFEFF"
        const val NBSP = '\u00A0'
        val HTML_SNIFF = Regex("""(?i)^\s*(<!doctype\s+html|<html[\s>]|<head[\s>]|<body[\s>])""")
        val HIDDEN_BLOCKS = Regex("""(?is)<(head|script|style|title)\b[^>]*>.*?</\1\s*>""")
        val EXTRA_BLANK_LINES = Regex("""\n[ \t]*(\n[ \t]*){2,}""")
    }
}
