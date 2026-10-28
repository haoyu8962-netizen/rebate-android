package com.couli.app.me

import com.couli.core.me.MeLevel
import com.couli.core.me.account.AccountRaw
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.feature.me.AccountApi
import com.couli.feature.me.AccountEnv
import com.couli.feature.me.AccountIdentity
import com.couli.feature.me.MeEnv
import com.couli.feature.me.MeIdentity
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设置一族的出口账本（只计数、不实现产品规则）：路由、系统通知设置、应用设置、开启完整功能、清缓存（图片 / WebView）、退出、下单须知已读世代。
 * 通知权限只能「查」：本类没有任何申请权限的入口，误调应用设置也只记账，由测试断言为 0（BR-ID-13）。
 */
internal class SettingsVisualEnv : MeEnv {
    override val identityFlow = MutableStateFlow(member())
    override val withdrawing = MutableStateFlow(false)
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val logouts = mutableListOf<MeIdentity>()
    val cs = mutableListOf<String>()
    var routes: Set<String> = SettingsVisualFixtures.routes(public = false)
    var status: String = SettingsVisualFixtures.notify("granted")
    var cache: Long? = SettingsVisualFixtures.cacheBytes()
    var notifySettings = 0
    var appSettings = 0
    var fullFunction = 0
    var diskClears = 0
    var webClears = 0
    var tipsResets = 0

    override fun identity() = identityFlow.value

    override fun sessionScope() = AccountRaw.FULL

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun copy(text: String): Boolean = error("设置一族不写剪贴板")

    override fun forceUpdate() = error("设置一族视觉测试不应触发强更")

    override fun configData(): JsonObject? = null

    override fun availableRoutes() = routes

    override fun earningsFlag(): Boolean? = null

    override fun customerService(fromRoute: String) {
        cs += fromRoute
    }

    override fun notificationStatus() = status

    override fun notificationDeniedAt(): Long? = null

    override fun openNotificationSettings() {
        notifySettings += 1
    }

    override fun permissionEntries() = emptyList<JsonObject>()

    override fun permissionStatus(type: String) = "not_determined"

    override fun openAppSettings() {
        appSettings += 1
    }

    override fun requestFullFunction() {
        fullFunction += 1
    }

    override fun forceUpdateContext() = false

    override fun enteredBy10006(identity: MeIdentity) = false

    override fun deviceRegistered() = true

    override fun consentedPrivacyVersion(): Int? = 3

    override fun legalPrivacyVersion(): Int? = 3

    override fun cacheBytes(): Long? = cache

    override fun clearCache(): Boolean {
        diskClears += 1
        cache = 0L
        return true
    }

    override fun clearWebCache(): Boolean {
        webClears += 1
        return true
    }

    override fun appVersion() = "1.0.0"

    override fun appBuild() = "1"

    override fun diagnosticsFields() = JsonObject(emptyMap())

    override fun logout(
        identity: MeIdentity,
        stillHere: () -> Boolean,
    ) {
        logouts += identity
    }

    override fun withdrawConsent(identity: MeIdentity) = error("撤回同意不在设置一族")

    override fun close(entryId: String?) = Unit

    override fun tipsReset() {
        tipsResets += 1
    }

    companion object {
        const val ACCOUNT = "settings-visual-a"

        fun member() = MeIdentity(MeLevel.MEMBER, ACCOUNT, 1, CallEpochs(0, 0))

        fun guest() = MeIdentity(MeLevel.GUEST, null, 1, CallEpochs(0, 0))

        fun basic() = MeIdentity(MeLevel.BASIC, null, 1, CallEpochs(0, 0))
    }
}

/** 传输层替身：按 operationId 回放；没登记的请求记进 [unexpected] 并回 50001（测试断言 [unexpected] 为空）。 */
internal class SettingsVisualApi : PageApi {
    val calls = mutableListOf<ApiCall>()
    val unexpected = mutableListOf<String>()
    val answers = mutableMapOf<String, ApiResult>()
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        if (call.operationId == GET_ME) hold?.await()
        return answers[call.operationId] ?: SettingsVisualFixtures.failure().also { unexpected += call.operationId }
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs() = CallEpochs(0, 0)

    fun count(operationId: String) = calls.count { it.operationId == operationId }

    companion object {
        const val GET_ME = "getMe"
        const val RESET_TIP = "resetTip"
    }
}

/** 修改昵称（Debug 待定 operation 的替身）：发送前先过执行层闸门 [allowed]，记账后回 [answer]。 */
internal class SettingsVisualNickname : NicknameBackend {
    val saves = mutableListOf<Pair<String, String>>()
    var answer: ApiResult = SettingsVisualFixtures.ok()
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun save(
        account: String,
        nickname: String,
        allowed: () -> Boolean,
    ): ApiResult {
        if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        saves += account to nickname
        hold?.await()
        return answer
    }
}

/** 服务类推送开关（Debug 待定 operation 的替身）。 */
internal class SettingsVisualNotify : NotifyBackend {
    val gets = mutableListOf<String>()
    val puts = mutableListOf<Boolean>()
    var service: Boolean = SettingsVisualFixtures.notifyService
    var getAnswer: ApiResult? = null
    var putAnswer: ApiResult? = null

    override suspend fun get(
        account: String,
        allowed: () -> Boolean,
    ): ApiResult {
        if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        gets += account
        return getAnswer ?: SettingsVisualFixtures.ok(JsonObject(mapOf("service" to JsonPrimitive(service))))
    }

    override suspend fun put(
        account: String,
        service: Boolean,
        allowed: () -> Boolean,
    ): ApiResult {
        if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        puts += service
        return putAnswer ?: SettingsVisualFixtures.ok(JsonObject(mapOf("service" to JsonPrimitive(service))))
    }
}

/** 注销与换号的账号环境替身：本机号码（换号入口可点）、注销入口来源记账。 */
internal class SettingsVisualAccountEnv(
    private val me: SettingsVisualEnv,
) : AccountEnv {
    override val accountIdentityFlow = MutableStateFlow(AccountIdentity(me.identity(), AccountRaw.FULL))
    val opened = mutableListOf<Pair<String, JsonObject>>()
    val deletions = mutableListOf<String>()
    var phone: String? = SettingsVisualFixtures.localPhone

    override fun accountIdentity() = accountIdentityFlow.value

    override fun open(
        route: String,
        params: JsonObject,
    ) {
        opened += route to params
    }

    override fun customerService(fromRoute: String) = Unit

    override fun availableRoutes() = me.routes

    override fun openDeletion(source: String) {
        deletions += source
    }

    override fun takeDeletionSource(entryId: String?) = AccountRaw.SETTINGS

    override fun finishDeletion(
        entryId: String?,
        source: String,
        toastKey: String?,
    ) = false

    override fun stepUpPhone(account: String): String? = phone

    override fun forceUpdate() = error("设置一族视觉测试不应触发强更")

    override fun sessionEpoch() = me.identity().epoch

    override fun account() = me.identity().account
}

internal class SettingsVisualAccountApi(
    api: SettingsVisualApi,
) : AccountApi,
    PageApi by api {
    override fun pendingFor(operationId: String): PendingKeyRecord? = null

    override fun restorePending(): Boolean = false

    override suspend fun confirmPending(
        operationId: String,
        stepUpToken: String?,
        target: PendingTarget,
    ): ApiResult = error("设置一族不确认未决提交")

    override suspend fun abandonPending(
        operationId: String,
        target: PendingTarget,
    ): ApiResult = error("设置一族不放弃未决提交")
}
