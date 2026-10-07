package com.couli.app

import android.util.Log
import com.couli.app.clientcore.AppConfig
import com.couli.app.clientcore.AppTracker
import com.couli.app.privacy.AppPrivacy
import com.couli.app.update.AppUpdate
import com.couli.core.config.EnvironmentGate

/**
 * 真实启动流程（CouliApplication.onCreate 与 MainActivity 的闸门变化共用，APP-03b R2-04 把它拆出来以便接线测试）：
 * 环境对账 → 配置冷启动 → 隐私冷启动与初始化 → Tracker（读回旧队列、启动定时上报）→ 强更冷启动检查。
 */
class AppStartup(
    private val environmentGate: EnvironmentGate,
    private val config: AppConfig,
    private val privacy: AppPrivacy,
    private val update: AppUpdate,
    private val tracker: AppTracker,
) {
    /** Application.onCreate。第三方 SDK 不在这里初始化，只经 PrivacyGate.onConsented()（03 §4.1）。 */
    fun onApplicationCreate() {
        // 覆盖安装可能改变本次环境（debug → staging → release）：与上次生效的环境不同则先清理（03 §3.6）。
        // 失败自动再试一次；仍失败则闸门保持 Blocked：首屏只显示阻断页（RootScreen），不进入新环境、
        // 不读会话与缓存；「上次生效的环境」记录不变，阻断页【重试】或下次启动再清理。
        val state = environmentGate.settleAtStartup()
        if (state is EnvironmentGate.State.Blocked) {
            Log.e(TAG, "environment cleanup failed; app is blocked until retry succeeds", state.cause)
        }
        // 配置（APP-03b，03 §4.1 F、§4.3）：环境就绪后先读 LKG，再后台拉 /v1/config（同意前也可拉：getConfig 在白名单内）
        config.startIfReady()
        // 隐私闸门（03 §4.4：环境 → 隐私 → Tab）：读本机隐私决定、比对包内版本；已同意当前版本且环境已就绪才初始化 SDK。
        privacy.coldStart()
        privacy.initializeIfReady()
        // Tracker（APP-03b R2-04）：环境与隐私状态就绪后显式初始化，读回上次留下的队列并每 10 秒定时上报
        tracker.startIfReady()
        // 强更检查（03 §4.1：冷启动必查）：环境 settled 且已同意隐私政策才发；否则同意后由 AppUpdate 补查（APP-02c）
        update.coldStart()
    }

    /** 环境闸门状态变化（MainActivity；含【重试】成功）：补做闸门阻断时跳过的初始化（各自只生效一次）。 */
    fun onEnvironmentGateChanged() {
        privacy.initializeIfReady()
        update.onCheckAllowedChanged()
        config.startIfReady()
        tracker.startIfReady()
    }

    private companion object {
        const val TAG = "CouliApplication"
    }
}
