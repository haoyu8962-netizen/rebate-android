package com.couli.app.login

import android.content.Context
import android.content.pm.PackageManager
import com.couli.feature.login.InstalledAppProbe

/**
 * 本机是否装了某个第三方 App（APP-05b；BR-ID-04 细则「未安装微信时不显示」）：只查 contracts/apps.json sdk_queries 里登记的
 * android_package（Android `<queries>` 由 CT-05 从同一张表生成，不手写；没有声明的包在 Android 11+ 上本来就查不到）。
 * 锁定的 apps.json（9cd1e6f）sdk_queries 为空，没有微信的包名，所以一律返回未知（null）——不臆造包名
 * （TODO(N-29)【Android 实现发现】WECHAT-QUERY）。查询出错同样按未知。
 */
class AndroidInstalledAppProbe(
    context: Context,
    /** source_sdk → android_package（来自 apps.json sdk_queries；当前为空）。 */
    private val packages: Map<String, List<String>> = SDK_QUERY_PACKAGES,
) : InstalledAppProbe {
    private val manager: PackageManager = context.applicationContext.packageManager

    override fun installed(sourceSdk: String): Boolean? {
        val candidates = packages[sourceSdk].orEmpty()
        if (candidates.isEmpty()) return null
        return runCatching {
            candidates.any { name ->
                runCatching { manager.getPackageInfo(name, 0) }.isSuccess
            }
        }.getOrNull()
    }

    companion object {
        /** apps.json sdk_queries（contract.lock 9cd1e6f）：空表。CT-05 生成后从生成物读取。 */
        val SDK_QUERY_PACKAGES: Map<String, List<String>> = emptyMap()
    }
}
