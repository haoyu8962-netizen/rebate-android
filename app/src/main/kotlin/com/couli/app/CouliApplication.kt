package com.couli.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application。第三方 SDK 不在这里初始化，只经 PrivacyGate.onConsented()（03 §4.1）。启动顺序见 [AppStartup]。 */
@HiltAndroidApp
class CouliApplication : Application() {
    @Inject
    lateinit var startup: AppStartup

    override fun onCreate() {
        super.onCreate()
        startup.onApplicationCreate()
    }
}
