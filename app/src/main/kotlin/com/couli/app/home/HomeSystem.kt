package com.couli.app.home

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.couli.app.persistedLoginEpochOf
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomePrefsStore
import com.couli.core.home.SharedPreferencesPendingFlag
import com.couli.core.network.SessionTokens
import java.io.File

/**
 * 读屏状态（触摸浏览）：进程级注册一次，结果写进 [AppHome.setScreenReader]；正式首页与每个 HomePreview 实例各自订阅 [AppHome.screenReader]
 * （共性第 20 条：不是只在创建时复制一次）。
 */
internal fun connectScreenReader(
    context: Context,
    home: AppHome,
) {
    val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java) ?: return
    home.setScreenReader(manager.isTouchExplorationEnabled)
    manager.addTouchExplorationStateChangeListener { enabled -> home.setScreenReader(enabled) }
}

/**
 * 首页 LKG（G16，brief §3 Android）：noBackupFilesDir/couli_home_lkg/；待清理标记在目录外（SharedPreferences [CouliPreferenceNamespaces.HOME_LKG_PENDING]，
 * commit 核对）。首页与两个清理入口共用同一个实例（同步、互斥）。首页取实例处给 [tokens]：会员作用域按「持久化登录世代」再分区（F-AND-25，
 * FU-AND-0714 R1-07；实例先由清理入口建出来的，这时接上）；清理入口与单测不给（清理删整个作用域目录，不看分区）。
 */
internal fun homeLkgStore(
    context: Context,
    tokens: SessionTokens? = null,
): HomeLkgStore {
    val root = File(context.applicationContext.noBackupFilesDir, HomeLkgStore.ROOT_DIR)
    return synchronized(HomeStores) {
        HomeStores.lkg.getOrPut(root.absolutePath) {
            HomeLkgStore(root, SharedPreferencesPendingFlag(context, CouliPreferenceNamespaces.HOME_LKG_PENDING))
        }
    }.also { store -> tokens?.let { store.bindLoginEpoch(persistedLoginEpochOf(it)) } }
}

/** 公告关闭记录与弹窗计数（couli_home_prefs，设备级）。 */
internal fun homePrefsStore(context: Context): HomePrefsStore = HomePrefsStore(context)

private object HomeStores {
    /** 按绝对路径各一份（生产只有一个；单测每个用例的目录不同）。 */
    val lkg = mutableMapOf<String, HomeLkgStore>()
}
