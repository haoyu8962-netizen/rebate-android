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
 *   SharedPreferences，不受影响（AC-S1-137，03 §4.3）。成功口径见 [clearCacheDirectory]（清理动作无错即成功，不按清后是否为空判，APP-12 共性第 39 条）。
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

    /** 每清完一项的观察点（只给测试模拟「清理途中 WebView 重建缓存文件」，生产不设）。 */
    internal var afterEachCleared: (File) -> Unit = {}

    override fun clearCache(): Boolean {
        images?.clearMemory()
        val dir = app.cacheDir ?: return false
        return clearCacheDirectory(dir, afterEachCleared)
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

/**
 * 清缓存目录的成功口径（APP-12 共性第 39 条，FU-AND-0716）：清理动作完成且没有 I/O 错误即成功——开始时枚举到的每个文件都删掉了（或删时已不在），
 * 每个目录都枚举得出；不拿清后目录是否为空判成败（活着的 WebView、图片 / 网络磁盘缓存会马上在 cacheDir 里重建文件，那不是清理失败；
 * 因新写入而删不掉的目录本身也不算失败）。显示的大小由调用方之后重算。[afterEach]：每处理完一项（测试模拟并发重建用）。
 */
internal fun clearCacheDirectory(
    dir: File,
    afterEach: (File) -> Unit = {},
): Boolean {
    val children = dir.listFiles() ?: return !dir.exists()
    return children.fold(true) { ok, child -> clearCacheEntry(child, afterEach) && ok }
}

private fun clearCacheEntry(
    entry: File,
    afterEach: (File) -> Unit,
): Boolean {
    val ok =
        if (entry.isDirectory) {
            val children = entry.listFiles()
            if (children == null) {
                // 枚举不出：已不在算删掉，还在按失败
                !entry.exists()
            } else {
                children.fold(true) { all, child -> clearCacheEntry(child, afterEach) && all }.also { entry.delete() }
            }
        } else {
            entry.delete() || !entry.exists()
        }
    afterEach(entry)
    return ok
}
