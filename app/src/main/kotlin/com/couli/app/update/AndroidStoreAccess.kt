package com.couli.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.couli.generated.AppStoreSpec
import com.couli.generated.AppStores
import com.couli.generated.ClientPlatform

/**
 * Android 的商店能力（03 §4.1「【去更新】跳哪家商店」；商店对照表 specs/app-stores.yaml → [AppStores]）。
 *
 * - 安装来源：API 30+ 用 `PackageManager.getInstallSourceInfo().installingPackageName`，旧系统用 `getInstallerPackageName`；
 *   与对照表里本端、本渠道商店的 install_source_packages 相等即认作那家商店（商店键为 null 的行不算）。
 * - 能否打开：对照表里商店键、打开方式、目标都不为 null 才算能打开；目前全为 null（needs N-17）→ 一律打不开，
 *   【去更新】进入 unavailable。
 * - 打开：打开方式的取值契约还没定义，不臆造（TODO(N-17)）；现在 [open] 一律返回 false（按打开失败处理，继续下一家）。
 */
class AndroidStoreAccess(
    context: Context,
    private val channel: String,
    private val table: List<AppStoreSpec> = AppStores.forPlatform(ClientPlatform.ANDROID.raw),
    installerPackage: () -> String? = { readInstallerPackage(context.applicationContext) },
) : StoreAccess {
    override val installSource: String? by lazy {
        val installer = installerPackage() ?: return@lazy null
        storesOfChannel().firstOrNull { installer in it.installSourcePackages }?.key
    }

    override fun canOpen(storeKey: String): Boolean {
        val spec = storesOfChannel().firstOrNull { it.key == storeKey } ?: return false
        return spec.openMethod != null && spec.target != null
    }

    // TODO(N-17): 对照表补 open_method 的取值与含义后按它打开商店详情页（打开失败返回 false，由调用方换下一家）。
    override fun open(storeKey: String): Boolean = false

    private fun storesOfChannel(): List<AppStoreSpec> = table.filter { it.key != null && channel in it.channels }

    companion object {
        private const val TAG = "AndroidStoreAccess"

        /** 本机的安装来源包名；读不到为 null。 */
        @Suppress("DEPRECATION") // getInstallerPackageName：API 30 以下唯一的读法
        fun readInstallerPackage(context: Context): String? =
            try {
                val manager = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    manager.getInstallerPackageName(context.packageName)
                }
            } catch (error: PackageManager.NameNotFoundException) {
                Log.w(TAG, "install source unavailable", error)
                null
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "install source unavailable", error)
                null
            }
    }
}
