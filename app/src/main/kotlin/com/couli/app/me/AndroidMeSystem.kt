package com.couli.app.me

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.couli.app.trade.AppProductImages
import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.network.DeviceCredentials
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.feature.web.WebViewCaches
import java.io.File

/**
 * Android 的「我的」类系统能力：
 * - 通知状态只查不申请（AndroidPermissionRequester.status / lastDeniedAt）；跳系统里本 App 的通知设置 / 应用设置（不出系统授权框）；
 * - 外部链接经系统打开（ACTION_VIEW + BROWSABLE，不在 App 内 WebView 加载）；
 * - 微信已装检测：只查生成物登记的查询项（apps.json 没有微信条目，manifest `<queries>` 也就没有），一律 unknown，不调 getInstalledPackages、不手写清单
 *   （rule-gaps A 段）；debug / staging 可用状态文件覆盖（[variantWechatInstalled]，只给截图）；
 * - 清除缓存：WebView 的内存与磁盘资源缓存（主线程经 [WebViewCaches] 调 WebView.clearCache(true)，对本进程所有 WebView 生效，R1-10）+ 内存图片缓存 +
 *   应用缓存目录（WebView 的 HTTP 缓存在 cacheDir 下）；会话、设备注册、配置 LKG、未决记录、pending_action、本机偏好都在 filesDir / noBackupFilesDir /
 *   SharedPreferences，不受影响（AC-S1-137，03 §4.3）。删后再枚举核对，枚举失败按失败。
 */
@Suppress("TooManyFunctions") // MeSystemPorts 的每项能力一个入口
class AndroidMeSystem(
    context: Context,
    private val permissions: AndroidPermissionRequester?,
    private val device: DeviceCredentials,
    private val images: AppProductImages?,
) : MeSystemPorts {
    private val app = context.applicationContext

    override fun pushStatus(): String =
        if (variantPushDeniedAt(app) != null) DENIED else permissions?.status(PermissionTypeRaw.PUSH) ?: UNAVAILABLE

    override fun pushDeniedAt(): Long? = variantPushDeniedAt(app) ?: permissions?.lastDeniedAt(PermissionTypeRaw.PUSH)

    override fun openNotificationSettings() {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(intent)) openAppSettings()
    }

    override fun openAppSettings() {
        start(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openExternal(url: String): Boolean {
        if (variantOpenExternalFails(app)) return false
        val intent =
            Intent(
                Intent.ACTION_VIEW,
                url.toUri(),
            ).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent)
    }

    @Suppress("SwallowedException") // 拉不起（没有可处理的 App、被系统拒绝）按 false
    private fun start(intent: Intent): Boolean =
        try {
            app.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            false
        } catch (error: SecurityException) {
            false
        }

    // TODO(N-33) G20：apps.json 补微信的 android_package、生成 <queries> 后按 resolveActivity 判断
    override fun wechatInstalled(): String = variantWechatInstalled(app) ?: UNKNOWN

    override fun cacheBytes(): Long? = runCatching { size(app.cacheDir) }.getOrNull()

    override fun clearWebCache(): Boolean = WebViewCaches.clear(app)

    @Suppress("ReturnCount") // 没有目录、枚举失败、核对三个出口
    override fun clearCache(): Boolean {
        images?.clearMemory()
        val dir = app.cacheDir ?: return false
        val children = dir.listFiles() ?: return !dir.exists()
        children.forEach { it.deleteRecursively() }
        // 删后再枚举核对：读不出按失败
        return dir.listFiles()?.isEmpty() == true
    }

    override fun deviceId(): String? = device.deviceId

    override fun osVersion(): String? = Build.VERSION.RELEASE

    override fun model(): String? = Build.MODEL

    private fun size(file: File): Long =
        if (file.isDirectory) {
            (file.listFiles() ?: error("cache dir not readable")).sumOf(::size)
        } else {
            file.length()
        }

    private companion object {
        const val UNKNOWN = "unknown"
        const val DENIED = "denied"
        const val UNAVAILABLE = "unavailable"
    }
}
