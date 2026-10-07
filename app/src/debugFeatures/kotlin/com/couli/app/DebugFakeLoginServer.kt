package com.couli.app

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Debug 专用的本机假登录服务（APP-05a 设计稿比对与冒烟用；src/debugFeatures，Release 不编译，release-scan 查 [FAKE_LOGIN_SERVER_MARKER]）。
 * 只在应用私有目录里有开关文件 files/<[FAKE_LOGIN_SERVER_MARKER]> 时接管（`adb shell run-as com.couli.app touch files/<标记>`，或调试面板），
 * 接管的请求全部在本机回答，不连任何地址；没有开关时原样交给网络（debug 默认环境本来就连不上）。
 *
 * 场景（按输入值选，号码与验证码都是演示用的假值）：
 * - 设备注册：固定的假凭据；
 * - 发码：13900000000 → 42901（Retry-After 120）；13900000001 → 50401（依赖超时，结果不确定）；其余成功（resend_after_sec 60、expires_in_sec 300）；
 * - 登录：123456 新号成功（带邀请码时：语法合法 bound，否则 failed 30401）；222222 老用户成功；200020 → 20002；200030 → 20003；
 *   444444 → 44001；100070 → 10007 processing；104050 → 10405 no_account；999999 → 受限会话 deletion_only；
 * - 刷新：换一对令牌（full）；登出、getMe、其余：code 0。
 */
internal class DebugFakeLoginServer(
    private val switch: File,
) : Interceptor {
    private var serial = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!switch.exists()) return chain.proceed(request)
        val body =
            request.body?.let { content ->
                val buffer = Buffer()
                content.writeTo(buffer)
                runCatching { Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject }.getOrNull()
            } ?: JsonObject(emptyMap())
        val (code, data, headers) = answer(request.url.encodedPath, body)
        val envelope = """{"code":$code,"msg":"","data":$data,"trace_id":"fake-trace-${"%06d".format(++serial)}"}"""
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (code == RATE_LIMITED) HTTP_TOO_MANY else HTTP_OK)
            .message("OK")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(envelope.toResponseBody(JSON))
            .build()
    }

    private fun answer(
        path: String,
        body: JsonObject,
    ): Triple<Int, String, Map<String, String>> =
        when (path) {
            "/v1/devices" -> ok("""{"device_id":"fake-device-0001","install_secret":"fake-install-0001"}""")
            "/v1/auth/sms-codes" -> sms(body.text("phone"))
            "/v1/auth/login/sms" -> login(body.text("code"), body.text("invite_code"))
            "/v1/auth/refresh" -> ok("""{${tokens("full")}}""")
            else -> ok("{}")
        }

    private fun sms(phone: String?): Triple<Int, String, Map<String, String>> =
        when (phone) {
            LIMITED_PHONE -> Triple(RATE_LIMITED, "null", mapOf("Retry-After" to "120"))
            UNCERTAIN_PHONE -> fail(UPSTREAM_TIMEOUT)
            else -> ok("""{"resend_after_sec":60,"expires_in_sec":300}""")
        }

    private fun login(
        code: String?,
        invite: String?,
    ): Triple<Int, String, Map<String, String>> =
        when (code) {
            "123456" -> ok(loggedIn("full", isNew = true, invite = invite))
            "222222" -> ok(loggedIn("full", isNew = false, invite = invite))
            "999999" -> ok(loggedIn("deletion_only", isNew = false, invite = null))
            "200020" -> fail(WRONG_CODE)
            "200030" -> fail(EXPIRED_CODE)
            "444444" -> fail(RISK_BLOCKED)
            "100070" -> Triple(DELETION, """{"stage":"processing"}""", emptyMap())
            "104050" -> Triple(FORCE_UPDATE, """{"reason":"no_account","min_supported_version":"9.0.0"}""", emptyMap())
            else -> fail(WRONG_CODE)
        }

    private fun loggedIn(
        scope: String,
        isNew: Boolean,
        invite: String?,
    ): String {
        val bind =
            when {
                invite == null -> ""
                !isNew -> ""","invite_bind":{"result":"ignored_existing_user","code":null}"""
                INVITE.matches(invite) -> ""","invite_bind":{"result":"bound","code":null}"""
                else -> ""","invite_bind":{"result":"failed","code":30401}"""
            }
        return """{"user_id":"fake-user-0001","is_new_user":$isNew,"tokens":{${tokens(scope)}}$bind}"""
    }

    private fun tokens(scope: String): String {
        serial += 1
        return """"access_token":"fake-access-$serial","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
            """"refresh_token":"fake-refresh-$serial","refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"$scope""""
    }

    private fun ok(data: String) = Triple(0, data, emptyMap<String, String>())

    private fun fail(code: Int) = Triple(code, "null", emptyMap<String, String>())

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        val JSON = "application/json".toMediaType()
        val INVITE = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}")
        const val LIMITED_PHONE = "13900000000"
        const val RATE_LIMITED = 42901
        const val UNCERTAIN_PHONE = "13900000001"
        const val UPSTREAM_TIMEOUT = 50401
        const val WRONG_CODE = 20002
        const val EXPIRED_CODE = 20003
        const val RISK_BLOCKED = 44001
        const val DELETION = 10007
        const val FORCE_UPDATE = 10405
        const val HTTP_OK = 200
        const val HTTP_TOO_MANY = 429
    }
}

/** 假服务的开关文件名，也是 release-scan 查的唯一标记（Release 制品里不得出现）。 */
internal const val FAKE_LOGIN_SERVER_MARKER = "COULI_FAKE_LOGIN_SERVER_APP05A_7f3a91c2"

/** 假服务开关文件。 */
internal fun fakeLoginServerSwitch(context: Context): File = File(context.applicationContext.filesDir, FAKE_LOGIN_SERVER_MARKER)

/** debug / staging：带 Debug 专用假登录服务的 OkHttpClient（开关文件不存在时不接管）。 */
internal fun variantHttpClient(context: Context): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(DebugFakeLoginServer(fakeLoginServerSwitch(context))).build()
