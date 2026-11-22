package com.couli.app.clientcore

import com.couli.app.BuildConfig
import com.couli.core.bridge.ConfigSignKeys
import com.couli.core.clientcore.BridgeOriginsLastGood
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.UploadTicket
import com.couli.core.router.PrivacyState
import com.couli.core.router.UpdateCheck
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject

/**
 * 配置的启动接线（03 §4.1 F、§4.3）：环境闸门就绪后冷启动一次（读 LKG → 后台拉 /v1/config，基本模式也拉：getConfig 在白名单内）；
 * 回前台带 If-None-Match 再拉。环境闸门阻断时不拉（闸门【重试】成功后由 MainActivity 补冷启动）。
 */
class AppConfig(
    val store: ConfigStore,
    private val environmentSettled: () -> Boolean,
) {
    private val started = AtomicBoolean(false)

    /** 冷启动（Application.onCreate、闸门【重试】成功后各调一次；只生效一次）。 */
    fun startIfReady() {
        if (environmentSettled() && started.compareAndSet(false, true)) store.onColdStart()
    }

    /**
     * 运行中切换环境、新环境已提交（APP-03b R1-05）：旧环境的 LKG 已由清理删除，这里按冷启动重新读（包内配置）并在新环境拉取。
     * 环境闸门未打开时不拉（之后由 [startIfReady] / [onForeground] 补）。
     */
    fun onEnvironmentSwitched() {
        if (!environmentSettled()) {
            started.set(false)
            return
        }
        started.set(true)
        store.onColdStart()
    }

    /** 回前台（MainActivity.onStart，冷启动那次除外）。 */
    fun onForeground() {
        if (!environmentSettled()) return
        if (started.compareAndSet(false, true)) store.onColdStart() else store.onForeground()
    }

    companion object {
        /** 本应用的 app_id（X-App-Id 与 bridge_origins payload.app_id 同源；TODO(N-19) 契约未登记取值，先用占位 couli）。 */
        const val APP_ID: String = "couli"

        /**
         * 随包的配置签名公钥表（N-22，D10-2539）：构建配置 BuildConfig.CONFIG_SIGN_KEYS（app/build.gradle.kts）经 [ConfigSignKeys.parse]。
         * debug / staging 内置 staging 钥；release 只有生产钥（目前为空 → 生产可信白名单为空，WebPage 不开）。
         */
        val bundledSignKeys: ConfigSignKeys by lazy { ConfigSignKeys.parse(BuildConfig.CONFIG_SIGN_KEYS) }

        /**
         * ConfigStore 的 bridge_origins 验签闭包（N-22）：每次调用现取 [environmentId]（切换环境后用新环境），用 [keys] 与 [appId] 经
         * BridgeOriginsVerifier.verify(config = …) 判定；通过返回白名单，不通过返回 null 并记一条 warn（reason + kid + env，同一版只记一次）。
         * 测试注入测试钥时传 [keys]。
         */
        @Suppress("UnusedParameter") // N-22 接口桩：第 2 段实现
        fun bridgeOriginsVerifier(
            environmentId: () -> String,
            keys: ConfigSignKeys = bundledSignKeys,
            appId: String = APP_ID,
        ): (config: JsonObject, lastGoodVersion: Long?) -> BridgeOriginsLastGood? = { _, _ -> null } // TODO(N-22 第 2 段)
    }
}

/**
 * 设备注册与 Tracker 用的隐私状态：已同意且撤回清理已完成才是 consented（BR-ID-11：同意前不读设备标识、不注册；基本模式与未同意不采集、不上报）。
 */
fun clientPrivacyRaw(
    conclusion: PrivacyState,
    cleanupPending: Boolean,
): String =
    when {
        conclusion == PrivacyState.CONSENTED && !cleanupPending -> PrivacyStateRaw.CONSENTED
        conclusion == PrivacyState.BASIC_MODE -> PrivacyStateRaw.BASIC_MODE
        else -> PrivacyStateRaw.NOT_DECIDED
    }

/** 强更检查有了结果才上报埋点（检查中、未知只入队，03 §4.7）；低于最低版本时不上报（上报接口同样受版本闸门约束）。 */
fun trackerCanUpload(update: UpdateCheck): Boolean = update == UpdateCheck.OK || update == UpdateCheck.BELOW_RECOMMENDED

/**
 * 生产的埋点上报 sink。TODO(N-20): openapi 还没有 /v1/events（needs.md N-20），先接空实现：不发任何请求、不确认，事件留在本地队列
 * （上限 500，TrackerQueue）。契约补上后改为经 ApiClient 批量上报。
 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // 空实现：接口补上前不上报
fun pendingEventsSink(
    events: List<String>,
    ticket: UploadTicket,
): Boolean = false
