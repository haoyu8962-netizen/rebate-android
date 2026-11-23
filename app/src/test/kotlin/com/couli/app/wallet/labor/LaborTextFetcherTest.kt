package com.couli.app.wallet.labor

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-LABOR-AGREEMENT-API 改口 r1：劳务协议全文的生产取文函数 LaborTextFetcher（app/src/main/kotlin/com/couli/app/wallet/LaborTextFetcher.kt，
 * RemoteLaborBackend 的 fetchText(url, expectedSha256)）。口径：只要求 https、主机不限；不跟重定向；请求里不带登录令牌、签名头、Cookie；整次超时
 * ≤ 10 秒；正文上限 1 MiB；按原始字节算小写 hex sha256，与 expectedSha256 相等才交出（否则 null）；HTML 转纯文字、纯文本原样。
 *
 * 传输层：基础客户端上的应用拦截器直接回答假响应（https 不需要证书、不连网），同时记下取文发出的请求头和该次调用生效的客户端配置
 * （是否跟重定向、Cookie、整次超时）。HTML 转纯文字走 HtmlCompat，所以跑在 Robolectric 上。
 * HEAD 9f015b2 上这个类不存在：按名反射取（构造 (base: () -> OkHttpClient)、suspend fetch(url, expectedSha256)），取不到由断言判红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions")
class LaborTextFetcherTest {
    /** 取文发出的一次请求，连同这次调用生效的客户端配置。 */
    @Suppress("LongParameterList") // 一次请求的全部可观察配置，逐项断言
    private class Seen(
        val url: HttpUrl,
        val method: String,
        val headers: Headers,
        val followRedirects: Boolean,
        val followSslRedirects: Boolean,
        val cookies: List<Cookie>,
        val callTimeoutNanos: Long,
    )

    /** 基础客户端故意带一个总会给 Cookie 的 CookieJar：取文必须把它关掉。 */
    private object StickyCookies : CookieJar {
        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
        ) = Unit

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            listOf(
                Cookie
                    .Builder()
                    .name("sid")
                    .value("cookie-fixture")
                    .domain(url.host)
                    .build(),
            )
    }

    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile
    private var reply: (Request) -> Response = { ok(it, TEXT.toByteArray(), PLAIN) }

    private val base: OkHttpClient =
        OkHttpClient
            .Builder()
            .cookieJar(StickyCookies)
            .addInterceptor { chain ->
                val request = chain.request()
                seen +=
                    Seen(
                        request.url,
                        request.method,
                        request.headers,
                        chain.followRedirects,
                        chain.followSslRedirects,
                        chain.cookieJar.loadForRequest(request.url),
                        chain.call().timeout().timeoutNanos(),
                    )
                reply(request)
            }.build()

    // ---------------------------------------------------------------- 地址

    @Test fun nonHttpsIsRejectedWithoutAnyRequest() {
        val fetch = fetcher()
        val digest = sha(TEXT.toByteArray())
        listOf(
            "http://cdn.example.test/labor/v2.html",
            "ftp://cdn.example.test/labor/v2.html",
            "cdn.example.test/labor/v2.html",
            "https://user:pass@cdn.example.test/labor/v2.html",
            "",
        ).forEach { url ->
            assertNull("$url：非 https（或带用户名密码）一律拒", runBlocking { fetch(url, digest) })
        }
        assertTrue("被拒的地址一个请求都不发", seen.isEmpty())
    }

    @Test fun plainTextComesBackAsIsFromAnyHttpsHost() {
        val fetch = fetcher()
        val digest = sha(TEXT.toByteArray())
        listOf(LaborContract.textUrl, "https://static.other-cdn.test:8443/labor/agreement.txt").forEach { url ->
            seen.clear()
            assertEquals("$url：任意 https 主机都可以，纯文本原样交出", TEXT, runBlocking { fetch(url, digest) })
            assertEquals("只发一次 GET", listOf("GET"), seen.map { it.method })
            assertEquals("取的就是 text_url", url, seen.single().url.toString())
        }
    }

    // ---------------------------------------------------------------- 摘要

    @Test fun digestMismatchReturnsNull() {
        val fetch = fetcher()
        assertNull("摘要与正文不符：按取不到", runBlocking { fetch(LaborContract.textUrl, OTHER_SHA) })
        assertNull("期望摘要是服务端原值：大小写不同也不算相符", runBlocking { fetch(LaborContract.textUrl, sha(TEXT.toByteArray()).uppercase()) })
        assertEquals("相符才交出", TEXT, runBlocking { fetch(LaborContract.textUrl, sha(TEXT.toByteArray())) })
    }

    // ---------------------------------------------------------------- 上限

    @Test fun bodyOverOneMebibyteReturnsNull() {
        val fetch = fetcher()
        val over = ByteArray(MAX_BYTES + 1) { 'a'.code.toByte() }
        reply = { ok(it, over, PLAIN) }
        assertNull("声明长度超过 1 MiB：按取不到（摘要相符也不行）", runBlocking { fetch(LaborContract.textUrl, sha(over)) })
        reply = { request -> respond(request, HTTP_OK, unknownLength(over, PLAIN)) }
        assertNull("不声明长度、实际超过 1 MiB：按取不到", runBlocking { fetch(LaborContract.textUrl, sha(over)) })
        val exact = ByteArray(MAX_BYTES) { 'a'.code.toByte() }
        reply = { request -> respond(request, HTTP_OK, unknownLength(exact, PLAIN)) }
        assertEquals("恰好 1 MiB 仍可取", MAX_BYTES, runBlocking { fetch(LaborContract.textUrl, sha(exact)) }?.length)
    }

    // ---------------------------------------------------------------- 重定向

    @Test fun redirectsAreNotFollowed() {
        val fetch = fetcher()
        reply = { request ->
            if (request.url.encodedPath == MOVED_PATH) {
                ok(request, TEXT.toByteArray(), PLAIN)
            } else {
                respond(request, HTTP_FOUND, ByteArray(0).toResponseBody(PLAIN), "Location" to "https://cdn.example.test$MOVED_PATH")
            }
        }
        assertNull("302：不跟、按取不到", runBlocking { fetch(LaborContract.textUrl, sha(TEXT.toByteArray())) })
        val only = seen.single()
        assertFalse("取文客户端不跟随重定向", only.followRedirects)
        assertFalse("取文客户端不跟随 https 之间的重定向", only.followSslRedirects)
    }

    // ---------------------------------------------------------------- 请求

    @Test fun requestCarriesNoTokenSignatureOrCookie() {
        val fetch = fetcher()
        assertEquals(TEXT, runBlocking { fetch(LaborContract.textUrl, sha(TEXT.toByteArray())) })
        val request = seen.single()
        CREDENTIAL_HEADERS.forEach { name -> assertNull("不带 $name", request.headers[name]) }
        assertTrue("不带任何 X- 头（签名、设备、链路标识都不带）", request.headers.names().none { it.startsWith("X-", ignoreCase = true) })
        assertTrue("Cookie 关掉（基础客户端的 CookieJar 不生效）", request.cookies.isEmpty())
        assertTrue("整次调用设了超时", request.callTimeoutNanos > 0)
        assertTrue("整次超时不超过 10 秒", request.callTimeoutNanos <= TimeUnit.SECONDS.toNanos(MAX_TIMEOUT_SECONDS))
    }

    // ---------------------------------------------------------------- 展示

    @Test fun htmlBecomesPlainTextWhileDigestCoversRawBytes() {
        val fetch = fetcher()
        val raw = HTML.toByteArray()
        reply = { ok(it, raw, "text/html; charset=utf-8".toMediaType()) }
        val text = runBlocking { fetch(LaborContract.textUrl, sha(raw)) }
        assertNotNull("摘要按原始 HTML 字节算：相符就交出", text)
        val shown = checkNotNull(text)
        assertTrue("标题文字在", shown.contains("劳务协议"))
        assertTrue("段落文字在，实体已解码", shown.contains("第一条 甲方&乙方"))
        assertTrue("第二段在", shown.contains("第二条"))
        assertFalse("不露标签", shown.contains("<") || shown.contains(">"))
        assertFalse("head / title 不展示", shown.contains("页面标题"))
        assertFalse("style 不展示", shown.contains("color"))
        assertFalse("script 不展示", shown.contains("track("))
        assertNull("摘要不按转换后的文字算", runBlocking { fetch(LaborContract.textUrl, sha(shown.toByteArray())) })
    }

    // ---------------------------------------------------------------- 底座

    private fun fetcher(): suspend (String, String) -> String? {
        val type = runCatching { Class.forName(FETCHER_CLASS) }.getOrNull()
        assertNotNull("需要 app 的 LaborTextFetcher（app/src/main/kotlin/com/couli/app/wallet/LaborTextFetcher.kt）", type)
        val constructor =
            checkNotNull(type).declaredConstructors.firstOrNull { c ->
                c.parameterTypes.size == 1 && c.parameterTypes[0] == Function0::class.java
            }
        assertNotNull("LaborTextFetcher 构造参数应为 (base: () -> OkHttpClient)", constructor)
        checkNotNull(constructor).isAccessible = true
        val supplier: () -> OkHttpClient = { base }
        val instance = unwrap { constructor.newInstance(supplier) }
        val method =
            type.declaredMethods.firstOrNull { m ->
                m.name == "fetch" &&
                    m.parameterTypes.size == FETCH_ARITY &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == Continuation::class.java
            }
        assertNotNull("LaborTextFetcher 要有 suspend fun fetch(url: String, expectedSha256: String): String?", method)
        checkNotNull(method).isAccessible = true
        return { url, expectedSha256 -> invokeSuspend(method, instance, url, expectedSha256) as String? }
    }

    private suspend fun invokeSuspend(
        method: Method,
        target: Any,
        vararg args: Any?,
    ): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            unwrap { method.invoke(target, *args, continuation) }
        }

    private fun <T> unwrap(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun respond(
        request: Request,
        code: Int,
        body: ResponseBody,
        vararg headers: Pair<String, String>,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fixture")
            .body(body)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

    private fun ok(
        request: Request,
        bytes: ByteArray,
        type: MediaType,
    ): Response = respond(request, HTTP_OK, bytes.toResponseBody(type))

    /** 不声明长度的正文（分块传输的情形）。 */
    private fun unknownLength(
        bytes: ByteArray,
        type: MediaType,
    ): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType = type

            override fun contentLength(): Long = -1L

            override fun source(): BufferedSource = Buffer().write(bytes)
        }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FETCHER_CLASS = "com.couli.app.wallet.LaborTextFetcher"
        const val FETCH_ARITY = 3
        const val MAX_BYTES = 1024 * 1024
        const val MAX_TIMEOUT_SECONDS = 10L
        const val HTTP_OK = 200
        const val HTTP_FOUND = 302
        const val MOVED_PATH = "/labor/moved.html"
        const val OTHER_SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val PLAIN = "text/plain; charset=utf-8".toMediaType()
        val CREDENTIAL_HEADERS = listOf("Authorization", "Cookie", "X-Sign", "X-Timestamp", "X-Nonce", "X-Device-Id", "Idempotency-Key")

        /** 法务正文的替身（测试值，不是真实协议）：纯文本、多行。 */
        const val TEXT = "劳务协议测试正文\n第一条……\n第二条……（测试替身）"

        val HTML =
            """
            <!doctype html>
            <html><head><title>页面标题</title><style>p { color: red; }</style></head>
            <body><h1>劳务协议</h1><p>第一条 甲方&amp;乙方</p><p>第二条……</p><script>track("x")</script></body></html>
            """.trimIndent()
    }
}
