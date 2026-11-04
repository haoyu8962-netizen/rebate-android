package com.couli.app.update

import java.util.Collections
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 沿用 ClipboardReviewLogin0724Wire 的传输替身方式；绝不调用 chain.proceed，不访问外网。 */
@Implements(className = "com.couli.app.DebugFakeLoginServer", isInAndroidSdk = false)
class SingleSettle10405Wire {
    @Implementation
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        calls += path
        val body =
            if (path == failingPath) {
                envelope(10405, minimum?.let { """{"min_supported_version":"$it"}""" } ?: "{}")
            } else {
                val data =
                    when (path) {
                        HOME -> {
                            if (withPool) POOL_PAGE else PAGE
                        }

                        ITEMS -> {
                            """{"items":[],"next_cursor":null}"""
                        }

                        ME -> {
                            ME_DATA
                        }

                        "/v1/me/deletion" -> {
                            // AppModule 的真实账号辅助接线可能在作用域同步时刷新注销入口标签。
                            "null"
                        }

                        REFRESH -> {
                            """{"access_token":"fixture-refreshed","refresh_token":"fixture-refresh-2","session_scope":"full",""" +
                                """"access_expires_at":"2099-01-01T00:00:00Z","refresh_expires_at":"2099-01-01T00:00:00Z"}"""
                        }

                        "/v1/articles" -> {
                            """{"items":[],"next_cursor":null}"""
                        }

                        else -> {
                            error("未配置的传输请求：$path")
                        }
                    }
                envelope(0, data)
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(null))
            .build()
    }

    companion object {
        const val HOME = "/v1/pages/home"
        const val ITEMS = "/v1/pages/home/sections/pool/items"
        const val ME = "/v1/me"
        const val REFRESH = "/v1/auth/refresh"
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var failingPath: String? = null

        @Volatile var minimum: String? = null

        @Volatile var withPool = false

        fun recorded(): List<String> = synchronized(calls) { calls.toList() }

        fun reset() {
            calls.clear()
            failingPath = null
            minimum = null
            withPool = false
        }

        private fun envelope(
            code: Int,
            data: String,
        ) = """{"code":$code,"msg":"","data":$data,"trace_id":"fixture-10405"}"""

        private const val PAGE =
            """{"page_key":"home","version":1,"sections":[{"id":"entry","type":"activity_entry","version":1,"props":{},""" +
                """"data_source":{"kind":"static","items":[{"title":"fixture","action":{"route":"Help"}}]}}]}"""
        private const val POOL_PAGE =
            """{"page_key":"home","version":2,"sections":[{"id":"pool","type":"product_grid","version":1,"props":{"columns":2},""" +
                """"data_source":{"kind":"product_pool","pool_id":"fixture-pool","limit":10}}]}"""
        private const val ME_DATA =
            """{"user_id":"fixture-user","nickname":"fixture","phone_masked":"138****0000","avatar_url":"","invite_code":"K7M3QX",""" +
                """"inviter_bound":true,"invite_backfill":{"eligible":false,"deadline_at":null},""" +
                """"identity_level":"normal","phone_bound":true,""" +
                """"balance":{"available_fen":1234,"withdrawable_fen":1234,"estimated_total_fen":5678},"union_bindings":[],""" +
                """"realname_status":"verified","need_reconsent":false,""" +
                """"risk":{"state":"normal","reason_category":null,"frozen_until":null}}"""
    }
}
