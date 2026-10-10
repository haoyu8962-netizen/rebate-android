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
 * - 刷新：换一对令牌（full）；登出、其余：code 0。
 * - 第三方登录（APP-05b）：取授权尝试 → 假的 attempt_id / nonce；微信登录 → 第三方新号（is_new_user=true）；
 *   getMe → phone_bound（第三方新号在本进程里绑定成功之前为 false，其余为 true）、realname_status（APP-09 假钱包服务）；
 * - 绑手机：13800005678 → 30411 mergeable（带假的 merge_ticket）；13800001111 → 30411；13800004444 → 44001；
 *   验证码 123456 → 成功；200030 → 20003；其余 → 20002。绑手机发码（purpose=bind）同登录发码。
 */
@Suppress("TooManyFunctions") // 每类接口一个场景函数
internal class DebugFakeLoginServer(
    private val switch: File,
) : Interceptor {
    private var serial = 0

    /**
     * 本进程里假账号是否已绑手机（新的第三方登录重置为 false，绑手机成功后回 true）。APP-09：缺省按已绑手机（短信登录的账号冷启动后 getMe 也回 true，
     * 资金页的守卫不再引去绑手机）。
     */
    @Volatile
    private var phoneBound = true

    /** 购买、外跳与授权（APP-06b，[DebugFakeTrade]）；状态开关文件与开关文件同目录。 */
    private val trade = DebugFakeTrade(File(switch.parentFile, FAKE_TRADE_STATE_FILE))

    /** 订单（APP-08，[DebugFakeOrders]）；状态开关文件与开关文件同目录。 */
    private val orders = DebugFakeOrders(File(switch.parentFile, FAKE_ORDERS_STATE_FILE))

    /** 钱包、提现、收款账号、二次验证与作废（APP-09，[DebugFakeWallet]，进程内唯一）；状态开关文件与开关文件同目录。 */
    private val wallet = DebugFakeWallet.of(File(switch.parentFile, FAKE_WALLET_STATE_FILE)).also { it.switchOn = { switch.exists() } }

    @Suppress("ReturnCount") // 开关关着、商品图、其余三个出口
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!switch.exists()) return chain.proceed(request)
        // 商品图（APP-06a 共性第 22 条的冒烟）：img.example.test 的图片在本机现画
        if (request.url.host == DebugFakeTrade.IMAGE_HOST) {
            return Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .body(DebugFakeTrade.image(request.url.encodedPath).toResponseBody(PNG))
                .build()
        }
        val body =
            request.body?.let { content ->
                val buffer = Buffer()
                content.writeTo(buffer)
                runCatching { Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject }.getOrNull()
            } ?: JsonObject(emptyMap())
        // 搜索与详情（APP-06a，DebugFakeSearch）先答；其余按登录场景
        val loggedIn = request.header("Authorization") != null
        val (code, data, headers) =
            wallet.answer(request.method, request.url.encodedPath, request.url.encodedQuery, body, request.headers.toMap())
                ?: orders.answer(request.method, request.url.encodedPath, request.url.encodedQuery) { chain.call().isCanceled() }
                ?: trade.answer(
                    request.method,
                    request.url.encodedPath,
                    body,
                    request.headers.toMap(),
                    loggedIn,
                ) { chain.call().isCanceled() }
                ?: DebugFakeSearch.answer(
                    request.url.encodedPath,
                    request.url.encodedQuery,
                    trade.platformStatus(),
                ) { chain.call().isCanceled() }
                ?: answer(request.url.encodedPath, body)
        // 复核过的商品，详情按新报价回答（APP-06b 重取权威卡片的冒烟）
        val answered = if (code == 0) trade.requoted(request.url.encodedPath, data) else data
        val envelope = """{"code":$code,"msg":"","data":$answered,"trace_id":"fake-trace-${"%06d".format(++serial)}"}"""
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(httpStatus(code))
            .message("OK")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(envelope.toResponseBody(JSON))
            .build()
    }

    /** 外壳的 HTTP 状态按 error-codes 的 http 列（ApiClient 只看外壳，状态码影响不大）。 */
    private fun httpStatus(code: Int): Int =
        when (code) {
            RATE_LIMITED -> HTTP_TOO_MANY
            LOGIN_REQUIRED -> HTTP_UNAUTHORIZED
            else -> HTTP_OK
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

            "/v1/auth/oauth-attempts" -> ok(attempt())

            "/v1/auth/login/wechat" -> wechatLogin()

            "/v1/me" -> ok("""{"user_id":"fake-user-0002","phone_bound":$phoneBound,"realname_status":"${wallet.realnameStatus()}"}""")

            "/v1/me/phone" -> bindPhone(body.text("phone"), body.text("code"))

            // 剪贴板识别（APP-07，DebugFakeSearch.parseInput）：按上传片段里的场景词回答
            "/v1/inputs/parse" -> DebugFakeSearch.parseInput(body.text("text").orEmpty())

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
    ): Triple<Int, String, Map<String, String>> {
        // 短信登录的账号就是已绑手机（APP-09 冒烟：冷启动后 getMe 回 phone_bound=true，资金页的守卫不再引去绑手机）
        if (code == "123456" || code == "222222") phoneBound = true
        return when (code) {
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
    }

    private fun attempt(): String {
        serial += 1
        return """{"attempt_id":"fake-attempt-$serial","nonce":"fake-nonce-0000-0000-$serial","expire_at":"2099-01-01T00:00:00+08:00"}"""
    }

    private fun wechatLogin(): Triple<Int, String, Map<String, String>> {
        phoneBound = false
        return ok("""{"user_id":"fake-user-0002","is_new_user":true,"tokens":{${tokens("full")}}}""")
    }

    private fun bindPhone(
        phone: String?,
        code: String?,
    ): Triple<Int, String, Map<String, String>> =
        when {
            phone == MERGEABLE_PHONE -> {
                Triple(OCCUPIED, """{"reason":"mergeable","merge_ticket":"fake-merge-ticket"}""", emptyMap())
            }

            phone == OCCUPIED_PHONE -> {
                fail(OCCUPIED)
            }

            phone == BLOCKED_PHONE -> {
                fail(RISK_BLOCKED)
            }

            code == "123456" -> {
                phoneBound = true
                ok("{}")
            }

            code == "200030" -> {
                fail(EXPIRED_CODE)
            }

            else -> {
                fail(WRONG_CODE)
            }
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
        val PNG = "image/png".toMediaType()
        val INVITE = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}")
        const val LIMITED_PHONE = "13900000000"
        const val MERGEABLE_PHONE = "13800005678"
        const val OCCUPIED_PHONE = "13800001111"
        const val BLOCKED_PHONE = "13800004444"
        const val OCCUPIED = 30411
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
        const val HTTP_UNAUTHORIZED = 401
        const val LOGIN_REQUIRED = 10001
    }
}

/** 假服务的开关文件名，也是 release-scan 查的唯一标记（Release 制品里不得出现）。 */
internal const val FAKE_LOGIN_SERVER_MARKER = "COULI_FAKE_LOGIN_SERVER_APP05A_7f3a91c2"

/** 假服务开关文件。 */
internal fun fakeLoginServerSwitch(context: Context): File = File(context.applicationContext.filesDir, FAKE_LOGIN_SERVER_MARKER)

/** debug / staging：带 Debug 专用假登录服务的 OkHttpClient（开关文件不存在时不接管）。 */
internal fun variantHttpClient(context: Context): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(DebugFakeLoginServer(fakeLoginServerSwitch(context))).build()
