package com.couli.app.me

import com.couli.core.me.MeLevel
import com.couli.core.network.CallEpochs
import com.couli.feature.me.MeEnv
import com.couli.feature.me.MeIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/** 页面测试的出口账本；不实现任何产品队列、文字切分或读屏逻辑。 */
internal class MeVisualEnv : MeEnv {
    override val identityFlow = MutableStateFlow(MeIdentity(MeLevel.MEMBER, "me-visual-a", 1, CallEpochs(0, 0)))
    override val withdrawing = MutableStateFlow(false)
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val copies = mutableListOf<String>()
    val cs = mutableListOf<String>()
    var copyOk = true
    var scope = "full"
    var guide = false
    var earnings = false
    var routes = MeVisualFixtures.routes()
    var settings = 0

    override fun identity() = identityFlow.value

    override fun sessionScope() = scope

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun copy(text: String): Boolean {
        copies += text
        return copyOk
    }

    override fun forceUpdate() = error("不应触发强更")

    override fun configData(): JsonObject? = null

    override fun availableRoutes() = routes

    override fun earningsFlag() = earnings

    override fun customerService(fromRoute: String) {
        cs += fromRoute
    }

    override fun notificationStatus() = if (guide) "denied" else "granted"

    override fun notificationDeniedAt(): Long? = if (guide) MeVisualFixtures.now - 72 * 3600_000L else null

    override fun openNotificationSettings() {
        settings += 1
    }

    override fun permissionEntries() = emptyList<JsonObject>()

    override fun permissionStatus(type: String) = "not_determined"

    override fun openAppSettings() = error("我的引导条只能进通知设置")

    override fun requestFullFunction() = Unit

    override fun forceUpdateContext() = false

    override fun enteredBy10006(identity: MeIdentity) = false

    override fun deviceRegistered() = true

    override fun consentedPrivacyVersion() = 3

    override fun legalPrivacyVersion() = 3

    override fun cacheBytes() = 0L

    override fun clearCache() = true

    override fun appVersion() = "1.0.0"

    override fun appBuild() = "1"

    override fun diagnosticsFields() = JsonObject(emptyMap())

    override fun logout(
        identity: MeIdentity,
        stillHere: () -> Boolean,
    ) = error("退出真实入口由 AppMeIntegrationTest 验证")

    override fun withdrawConsent(identity: MeIdentity) = error("撤回真实入口由 AppMeIntegrationTest 验证")

    override fun close(entryId: String?) = Unit
}
