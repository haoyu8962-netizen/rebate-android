package com.couli.app.web

import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgeServices
import kotlinx.serialization.json.JsonElement
import org.robolectric.util.ReflectionHelpers

/**
 * 只观察真实 AppWeb 宿主的等待与处理器入口；所有调用继续交给原实现。
 * 产品没有处理器观察接口，测试通过 Robolectric 替换两个私有委托，避免用 ApiClient Provider 读取次数冒充处理器次数
 * （callerState 同样会读取 Provider）。字段改名会直接令测试失败，不会静默跳过观察。
 */
internal class Fx24BridgeProbe(
    host: BridgeHost,
) {
    var updateWaitEntries = 0
        private set
    var signedRequestEntries = 0
        private set
    var loginEntries = 0
        private set

    init {
        val originalWait: suspend () -> String = ReflectionHelpers.getField(host, "awaitUpdate")
        val observedWait: suspend () -> String = {
            updateWaitEntries += 1
            originalWait()
        }
        ReflectionHelpers.setField(host, "awaitUpdate", observedWait)

        val originalServices: BridgeServices = ReflectionHelpers.getField(host, "services")
        val observedServices =
            object : BridgeServices by originalServices {
                override suspend fun login(update: String): BridgeOutcome {
                    loginEntries += 1
                    return originalServices.login(update)
                }

                override suspend fun login(
                    update: String,
                    validity: BridgeCallValidity,
                    opening: suspend (suspend () -> BridgeOutcome) -> BridgeOutcome,
                ): BridgeOutcome {
                    loginEntries += 1
                    return originalServices.login(update, validity, opening)
                }

                override suspend fun signedRequest(
                    method: String,
                    path: String,
                    body: JsonElement?,
                    validity: BridgeCallValidity,
                ): BridgeOutcome {
                    signedRequestEntries += 1
                    return originalServices.signedRequest(method, path, body, validity)
                }
            }
        ReflectionHelpers.setField(host, "services", observedServices)
    }
}
