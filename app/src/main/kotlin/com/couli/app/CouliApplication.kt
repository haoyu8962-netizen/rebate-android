package com.couli.app

import android.app.Application
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ShareIntake
import com.couli.app.clipboard.ShareIntakeProvider
import com.couli.app.inbound.WebCodeCallbackSinkProvider
import com.couli.feature.trade.WebCodeCallbackSink
import com.couli.feature.trade.WebCodeInbox
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/** Application。第三方 SDK 不在这里初始化，只经 PrivacyGate.onConsented()（03 §4.1）。启动顺序见 [AppStartup]。 */
@HiltAndroidApp
class CouliApplication :
    Application(),
    ShareIntakeProvider,
    WebCodeCallbackSinkProvider {
    @Inject
    lateinit var startup: AppStartup

    /** 系统分享导入（FU-CLIP C6）：专用接收 Activity 经它把分享交给剪贴板模块（用到时才构造）。 */
    @Inject
    lateinit var clipboard: Provider<AppClipboard>

    override fun shareIntake(): ShareIntake = ShareIntake { mime, text -> clipboard.get().receiveShare(mime, text) }

    /** 淘宝 web_code 授权回跳（FU-AND-TB-WEBCODE-A）：入站中转 Activity 经它把回跳交给进程内收件箱（只在内存）。 */
    @Inject
    lateinit var webCodeInbox: Provider<WebCodeInbox>

    override fun webCodeCallbackSink(): WebCodeCallbackSink = webCodeInbox.get()

    override fun onCreate() {
        super.onCreate()
        startup.onApplicationCreate()
    }
}
