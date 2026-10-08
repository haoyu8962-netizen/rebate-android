package com.couli.core.network

/**
 * 页面执行器（搜索、商品详情，APP-06a）用到的网络能力：生产为 [ApiClientPageApi]；集成测试用 MockWebServer 上的真实 ApiClient 或替身。
 * 每个请求带调用方的归属（[ApiCall.ownership]）：页面换条件、离开时先让归属失效，再 [cancelOwned] 取消已交给传输层的请求。
 */
interface PageApi {
    /** 发出一次调用（挂起；网络在 IO 线程）。 */
    suspend fun send(call: ApiCall): ApiResult

    /** 取消归属为 [owner] 的在途请求。 */
    fun cancelOwned(owner: CallOwnership)

    /** 当前隐私 / 环境世代（归属绑定用）。 */
    fun epochs(): CallEpochs
}

/** 生产接线：[ApiClient]。 */
class ApiClientPageApi(
    private val client: ApiClient,
) : PageApi {
    override suspend fun send(call: ApiCall): ApiResult = client.send(call)

    override fun cancelOwned(owner: CallOwnership) = client.cancelOwned(owner)

    override fun epochs(): CallEpochs = client.inFlight.epochs()
}

/** RFC 3986 百分号编码（UTF-8；unreserved 原样）：页面拼 ApiCall.query 用（搜索参数、item_ref 原样透传）。 */
fun percentEncode(value: String): String {
    val out = StringBuilder()
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val c = byte.toInt() and BYTE_MASK
        if (c.toChar().isUnreserved()) out.append(c.toChar()) else out.append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW_NIBBLE])
    }
    return out.toString()
}

private fun Char.isUnreserved(): Boolean = isAsciiAlphaNumeric() || this in "-._~"

private fun Char.isAsciiAlphaNumeric(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private const val HEX = "0123456789ABCDEF"
private const val BYTE_MASK = 0xFF
private const val NIBBLE = 4
private const val LOW_NIBBLE = 0x0F
