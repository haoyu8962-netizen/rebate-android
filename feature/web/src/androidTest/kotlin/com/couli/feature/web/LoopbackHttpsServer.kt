package com.couli.feature.web

import android.content.Context
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * 本机回环的真实 HTTPS 服务（只在仪器测试里用，不连任何外网）：在 127.0.0.1 上监听，证书由测试专用 CA 签发
 * （SAN：IP 127.0.0.1、DNS localhost；CA 只经测试 APK 的 network-security-config 对这两个主机生效）。
 * 同一端口上 `https://127.0.0.1:port` 与 `https://localhost:port` 是两个不同的 origin，测试用它们区分可信源与跨源 / 平台域。
 * 每个请求记下 Host、方法与路径；应答由 [handler] 给出（可以是真正的 3xx 重定向——这正是拦截回调里给不出的东西）。
 */
class LoopbackHttpsServer(
    context: Context,
    private val handler: (Recorded) -> Reply,
) : AutoCloseable {
    /** 一次收到的请求。 */
    data class Recorded(
        val host: String,
        val method: String,
        val path: String,
    )

    /**
     * 应答：状态码、额外的头、HTML 正文。[cacheControl] 缺省 no-store（原有用例不变）；[contentType] 缺省为 HTML（APP-12 R1-10 的可缓存脚本资源用）。
     */
    data class Reply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val html: String = "",
        val cacheControl: String = "no-store",
        val contentType: String = "text/html; charset=utf-8",
    )

    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())
    private val socket: SSLServerSocket
    private val workers = Executors.newCachedThreadPool()

    @Volatile
    private var closed = false

    val port: Int get() = socket.localPort

    init {
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open(KEYSTORE).use { keyStore.load(it, PASSWORD) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        socket = tls.serverSocketFactory.createServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK)) as SSLServerSocket
        workers.execute { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            workers.execute { runCatching { client.use(::serve) } }
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        headers["content-length"]?.toIntOrNull()?.let { length -> input.readNBytesCompat(length) }
        val parts = requestLine.split(' ')
        val recorded = Recorded(headers["host"].orEmpty().substringBefore(':'), parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
        requests += recorded
        val reply = handler(recorded)
        val body = reply.html.toByteArray()
        val head =
            buildString {
                append("HTTP/1.1 ${reply.status} X\r\n")
                if (reply.html.isNotEmpty()) append("Content-Type: ${reply.contentType}\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: ${reply.cacheControl}\r\n")
                append("Connection: close\r\n")
                reply.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    /** 某个主机收到的请求。 */
    fun to(host: String): List<Recorded> = synchronized(requests) { requests.filter { it.host == host } }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private fun readLine(input: InputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.isEmpty()) null else bytes.toString()
            if (next == '\n'.code) return bytes.toString().trimEnd('\r')
            bytes.append(next.toChar())
        }
    }

    private fun InputStream.readNBytesCompat(length: Int) {
        var remaining = length
        val buffer = ByteArray(BUFFER)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) return
            remaining -= read
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LOCALHOST = "localhost"
        private const val KEYSTORE = "tls/loopback-test-only-server.p12"
        private val PASSWORD = "test-only".toCharArray()
        private const val BACKLOG = 16
        private const val BUFFER = 4096
    }
}
