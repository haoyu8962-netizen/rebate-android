package com.couli.app.purchase

import com.couli.feature.trade.OpenPurchaseController
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** 只计数再调用真实方法；防止双回调被控制器幂等保护掩盖。既不改状态，也不替换任何分支的结果。 */
@Implements(value = OpenPurchaseController::class, isInAndroidSdk = false)
class PurchaseVisualCallbacks {
    @RealObject private lateinit var real: OpenPurchaseController
    private val calls = mutableListOf<String>()

    private fun run(name: String) {
        calls += name
        Shadow.directlyOn<Unit, OpenPurchaseController>(real, OpenPurchaseController::class.java, name)
    }

    @Implementation fun confirm() = run("confirm")

    @Implementation fun dismissDialog() = run("dismissDialog")

    @Implementation fun retryPurchase() = run("retryPurchase")

    @Implementation fun buyWithoutRebate() = run("buyWithoutRebate")

    @Implementation fun contactService() = run("contactService")

    @Implementation fun authorize() = run("authorize")

    @Implementation fun cancelLoading() = run("cancelLoading")

    @Implementation fun retryAfterTimeout() = run("retryAfterTimeout")

    @Implementation fun cancelBrowser() = run("cancelBrowser")

    @Implementation fun viewExplanation() = run("viewExplanation")

    @Implementation fun similar(product: JsonObject?) {
        calls += "similar"
        Shadow.directlyOn<Unit, OpenPurchaseController>(
            real,
            OpenPurchaseController::class.java,
            "similar",
            ClassParameter.from(JsonObject::class.java, product),
        )
    }

    companion object {
        fun assertCalls(
            controller: OpenPurchaseController,
            vararg expected: String,
        ) {
            val probe = Shadow.extract<PurchaseVisualCallbacks>(controller)
            assertEquals("真实控制器回调顺序与次数", expected.toList(), probe.calls)
            println("CALLBACKS ${probe.calls}")
        }
    }
}
