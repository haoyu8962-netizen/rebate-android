package com.couli.app.update

import com.couli.app.home.AppHome
import com.couli.core.network.ApiResult
import com.couli.core.wallet.SendProbe
import com.couli.feature.home.HomeController
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** 沿用 PurchaseVisualCallbacks：计数后原样调用真实方法，保留 AppHome → AppModule → AppUpdate 接线。 */
@Implements(value = AppHome::class, isInAndroidSdk = false)
class Late10405PageCalls {
    @RealObject private lateinit var real: AppHome
    var forced = 0
        private set

    @Implementation
    fun forceUpdate() {
        forced += 1
        Shadow.directlyOn<Unit, AppHome>(real, AppHome::class.java, "forceUpdate")
    }
}

/** 只在真实 settlePage 返回后记录；不能用 HTTP 发出、refreshing=false 或固定 sleep 代替旧请求结算完成。 */
@Implements(value = HomeController::class, isInAndroidSdk = false)
class Late10405Settlements {
    @RealObject private lateinit var real: HomeController
    val completed = mutableListOf<Completion>()

    @Implementation
    fun settlePage(
        seq: Long,
        pull: Boolean,
        result: ApiResult,
        probe: SendProbe,
    ) {
        Shadow.directlyOn<Unit, HomeController>(
            real,
            HomeController::class.java,
            "settlePage",
            ClassParameter.from(Long::class.javaPrimitiveType, seq),
            ClassParameter.from(Boolean::class.javaPrimitiveType, pull),
            ClassParameter.from(ApiResult::class.java, result),
            ClassParameter.from(SendProbe::class.java, probe),
        )
        completed += Completion(seq, pull, result, real.owned())
    }

    data class Completion(
        val seq: Long,
        val pull: Boolean,
        val result: ApiResult,
        val owned: Boolean,
    )
}
