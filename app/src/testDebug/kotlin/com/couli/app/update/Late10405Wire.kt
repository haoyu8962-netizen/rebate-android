package com.couli.app.update

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 只扣住第一笔首页 HTTP 响应；后续请求沿用 v1/v2 的离线传输替身，不接管 ApiClient 的判定。 */
@Implements(className = "com.couli.app.DebugFakeLoginServer", isInAndroidSdk = false)
class Late10405Wire {
    @Implementation
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val pending = held
        if (request.url.encodedPath != SingleSettle10405Wire.HOME || pending.requests.incrementAndGet() != 1) {
            return SingleSettle10405Wire().intercept(chain)
        }
        SingleSettle10405Wire.calls += SingleSettle10405Wire.HOME
        pending.arrived.countDown()
        check(pending.release.await(30, TimeUnit.SECONDS)) { "旧首页响应未获放行；不能靠超时伪造迟到 10405" }
        val body =
            """{"code":10405,"msg":"","data":{"min_supported_version":"${pending.minimum}"},"trace_id":"late-10405"}"""
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(null))
            .build()
    }

    class HeldResponse(
        val minimum: String,
    ) {
        val requests = AtomicInteger()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    companion object {
        @Volatile var held = HeldResponse(SingleSettle10405Graph.VERSION)
    }
}
