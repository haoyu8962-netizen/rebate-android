package com.couli.app

import android.app.Application
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ShareIntake
import com.couli.app.clipboard.ShareIntakeProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/** Application。第三方 SDK 不在这里初始化，只经 PrivacyGate.onConsented()（03 §4.1）。启动顺序见 [AppStartup]。 */
@HiltAndroidApp
class CouliApplication :
    Application(),
    ShareIntakeProvider {
    @Inject
    lateinit var startup: AppStartup

    /** 系统分享导入（FU-CLIP C6）：专用接收 Activity 经它把分享交给剪贴板模块（用到时才构造）。 */
    @Inject
    lateinit var clipboard: Provider<AppClipboard>

    override fun shareIntake(): ShareIntake = ShareIntake { mime, text -> clipboard.get().receiveShare(mime, text) }

    override fun onCreate() {
        super.onCreate()
        startup.onApplicationCreate()
    }
}
