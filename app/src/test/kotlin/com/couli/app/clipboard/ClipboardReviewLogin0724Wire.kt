package com.couli.app.clipboard

import com.couli.core.network.AndroidKeystoreCipher
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 只替换工厂已经选择的网络出口；不接管 ApiClient/登录保存/NetworkHooks/AppModule 通知。 */
@Implements(className = "com.couli.app.DebugFakeLoginServer", isInAndroidSdk = false)
class ClipboardReviewLogin0724Wire {
    @Implementation
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        calls.add(request.url.encodedPath to body)
        val data =
            when (request.url.encodedPath) {
                "/v1/auth/login/sms" -> {
                    val phone =
                        Json
                            .parseToJsonElement(body)
                            .jsonObject
                            .getValue("phone")
                            .jsonPrimitive.content
                    account = if (phone == PHONE_A) "account-A" else "account-B"
                    loginData(account)
                }

                "/v1/auth/sms-codes" -> {
                    """{"resend_after_sec":60,"expires_in_sec":300}"""
                }

                "/v1/auth/logout" -> {
                    "{}"
                }

                "/v1/me" -> {
                    """{"user_id":"$account","phone_bound":true}"""
                }

                "/v1/inputs/parse" -> {
                    Clipboard0724Fixture.success().data.toString()
                }

                else -> {
                    error("未预期的网络出口 ${request.url.encodedPath}")
                }
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"code":0,"msg":"","data":$data,"trace_id":"fixture-0724"}""".toResponseBody(null))
            .build()
    }

    companion object {
        const val PHONE_A = "13800138000"
        const val PHONE_B = "13900139000"
        val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

        @Volatile var account = ""

        fun parseCount(): Int = synchronized(calls) { calls.count { it.first == "/v1/inputs/parse" } }

        private fun loginData(user: String) =
            """{"user_id":"$user","is_new_user":false,"tokens":{"access_token":"sample-access-$user",""" +
                """"access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"sample-refresh-$user",""" +
                """"refresh_expires_at":"2099-01-01T00:00:00Z","session_scope":"full"}}"""
    }
}

/** Robolectric 没有 AndroidKeyStore；只用已有样例字节加解密，真实偏好写入与清理仍执行。 */
@Implements(value = AndroidKeystoreCipher::class, isInAndroidSdk = false)
class ClipboardReviewCipher0724 {
    @Implementation fun encrypt(plain: ByteArray): ByteArray = SampleCipher.encrypt(plain)

    @Implementation fun decrypt(sealed: ByteArray): ByteArray = SampleCipher.decrypt(sealed)
}
