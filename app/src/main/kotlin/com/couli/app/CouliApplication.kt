package com.couli.app

import android.app.Application
import android.util.Log
import com.couli.app.privacy.AppPrivacy
import com.couli.core.config.EnvironmentGate
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application。第三方 SDK 不在这里初始化，只经 PrivacyGate.onConsented()（03 §4.1）。 */
@HiltAndroidApp
class CouliApplication : Application() {
    @Inject
    lateinit var environmentGate: EnvironmentGate

    @Inject
    lateinit var privacy: AppPrivacy

    override fun onCreate() {
        super.onCreate()
        // 覆盖安装可能改变本次环境（debug → staging → release）：与上次生效的环境不同则先清理（03 §3.6）。
        // 失败自动再试一次；仍失败则闸门保持 Blocked：首屏只显示阻断页（RootScreen），不进入新环境、
        // 不读会话与缓存；「上次生效的环境」记录不变，阻断页【重试】或下次启动再清理。
        val state = environmentGate.settleAtStartup()
        if (state is EnvironmentGate.State.Blocked) {
            Log.e(TAG, "environment cleanup failed; app is blocked until retry succeeds", state.cause)
        }
        // 隐私闸门（03 §4.4：环境 → 隐私 → Tab）：读本机隐私决定、比对包内版本；已同意当前版本且环境已就绪才初始化 SDK。
        privacy.coldStart()
        privacy.initializeIfReady()
    }

    private companion object {
        const val TAG = "CouliApplication"
    }
}
