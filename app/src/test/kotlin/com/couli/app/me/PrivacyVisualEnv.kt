package com.couli.app.me

import com.couli.core.me.CustomerServicePorts
import com.couli.core.me.MeLevel
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.feature.me.MeEnv
import com.couli.feature.me.MeIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 隐私中心 / 关于的出口账本（只计数、不实现产品规则）：路由、系统设置、首启弹窗（开启完整功能）、撤回同意（交应用级协调器）、剪贴板统一写入口、强更。
 * 本类没有任何「申请系统权限」的入口：误调通知设置也只记账，由测试断言为 0（BR-ID-13）。撤回同意只记下渲染时的身份，不改身份（换身份由测试显式做）。
 */
internal class PrivacyVisualEnv : MeEnv {
    override val identityFlow = MutableStateFlow(member())
    override val withdrawing = MutableStateFlow(false)
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val withdrawals = mutableListOf<MeIdentity>()
    val copies = mutableListOf<String>()
    val cs = mutableListOf<String>()
    var copyOk = true
    var config: JsonObject? = null
    var diagnostics: JsonObject = PrivacyVisualFixtures.diagnostics
    var entries: List<JsonObject> = emptyList()
    var statuses: Map<String, String> = emptyMap()
    var forceContext = false
    var registered = true
    var legalVersion: Int? = 3
    var appSettings = 0
    var notifySettings = 0
    var fullFunction = 0
    var forceUpdates = 0
    var logouts = 0

    override fun identity() = identityFlow.value

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

    override fun forceUpdate() {
        forceUpdates += 1
    }

    override fun configData(): JsonObject? = config

    override fun availableRoutes() = setOf("Agreement", "Help", "DeleteAccount")

    override fun earningsFlag(): Boolean? = null

    override fun customerService(fromRoute: String) {
        cs += fromRoute
    }

    override fun notificationStatus() = "not_determined"

    override fun notificationDeniedAt(): Long? = null

    override fun openNotificationSettings() {
        notifySettings += 1
    }

    override fun permissionEntries() = entries

    override fun permissionStatus(type: String) = statuses[type] ?: "not_asked"

    override fun openAppSettings() {
        appSettings += 1
    }

    override fun requestFullFunction() {
        fullFunction += 1
    }

    override fun forceUpdateContext() = forceContext

    override fun enteredBy10006(identity: MeIdentity) = false

    override fun deviceRegistered() = registered

    override fun consentedPrivacyVersion(): Int? = legalVersion

    override fun legalPrivacyVersion(): Int? = legalVersion

    override fun cacheBytes(): Long? = null

    override fun clearCache(): Boolean = error("隐私中心 / 关于不清缓存")

    override fun appVersion() = PrivacyVisualFixtures.version

    override fun appBuild() = PrivacyVisualFixtures.build

    override fun diagnosticsFields() = diagnostics

    override fun logout(
        identity: MeIdentity,
        stillHere: () -> Boolean,
    ) {
        logouts += 1
    }

    override fun withdrawConsent(identity: MeIdentity) {
        withdrawals += identity
    }

    override fun close(entryId: String?) = Unit

    companion object {
        const val ACCOUNT = "privacy-visual-a"
        const val ACCOUNT_B = "privacy-visual-b"

        fun member(
            account: String = ACCOUNT,
            epoch: Long = 1,
        ) = MeIdentity(MeLevel.MEMBER, account, epoch, CallEpochs(0, 0))

        fun guest() = MeIdentity(MeLevel.GUEST, null, 1, CallEpochs(0, 0))

        fun basic() = MeIdentity(MeLevel.BASIC, null, 1, CallEpochs(0, 0))

        fun of(level: String): MeIdentity =
            when (level) {
                MeLevel.MEMBER -> member()
                MeLevel.GUEST -> guest()
                else -> basic()
            }
    }
}

/** 传输层替身：记下每个请求；recordConsent 按 [consent] 回放，其余一律记进 [unexpected] 并回 50001（测试断言为空）。 */
internal class PrivacyVisualApi : PageApi {
    val calls = mutableListOf<ApiCall>()
    val unexpected = mutableListOf<String>()
    var consent: ApiResult = PrivacyVisualFixtures.ok()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        if (call.operationId == RECORD_CONSENT) return consent
        unexpected += call.operationId
        return PrivacyVisualFixtures.failure()
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs() = CallEpochs(0, 0)

    fun count(operationId: String) = calls.count { it.operationId == operationId }

    companion object {
        const val RECORD_CONSENT = "recordConsent"
        const val GET_ME = "getMe"
    }
}

/** 统一客服出口的系统能力替身（真 CustomerServiceEntry 用）：kf.url、微信是否已装、经系统打开、统一写入口、经 Router 开帮助中心——只记账。 */
internal class PrivacyVisualCsPorts : CustomerServicePorts {
    var url: String? = PrivacyVisualFixtures.kf
    var wechat: String = "unknown"
    var openOk = false
    var copyOk = true
    val opened = mutableListOf<String>()
    val copies = mutableListOf<String>()
    var help = 0

    override fun kfUrl(): String? = url

    override fun wechatInstalled(): String = wechat

    override fun openExternal(url: String): Boolean {
        opened += url
        return openOk
    }

    override fun copy(text: String): Boolean {
        copies += text
        return copyOk
    }

    override fun openHelp() {
        help += 1
    }

    fun apply(case: String) {
        url = PrivacyVisualFixtures.caseKf(case)
        wechat = PrivacyVisualFixtures.text("wechat", PrivacyVisualFixtures.case(case))
        openOk = PrivacyVisualFixtures.caseBool(case, "opens")
        copyOk = PrivacyVisualFixtures.caseBool(case, "copies")
    }
}
