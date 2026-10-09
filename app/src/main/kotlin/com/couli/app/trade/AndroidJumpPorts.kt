package com.couli.app.trade

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import com.couli.app.web.BrowserExit
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.JumpTypes

/**
 * 外跳的 Android 实现（03 §4.5、BR-ATTR-27）：
 * - 安装状态只查 apps.json apps.<平台>.android.packages（Android `<queries>` 由 CT-05 从同一张表生成，不手写）。锁定的 apps.json（9cd1e6f）
 *   三个平台的 Android 包名都是空表（candidate），所以一律 unknown——不臆造包名，检测失败也按 unknown，不当 false（TODO(N-28) G05）；
 * - scheme：ACTION_VIEW 交给声明了该 scheme 的 App，没有能处理的 App 即失败；
 * - universal_link：只交给非浏览器 App（API 30+ FLAG_ACTIVITY_REQUIRE_NON_BROWSER；更低版本解析到浏览器即按失败），浏览器的落点由 h5 步骤负责；
 * - h5：只交系统浏览器（[BrowserExit]：限定在浏览器包内解析，App Links 认领该域名的其他 App 收不到，APP-04 共性第 9 条）；
 * - copy_tpwd：写系统剪贴板。TODO(APP-07)：本 App 写入的内容记 hash，剪贴板识别不再提示（03 §4.6）。
 */
class AndroidJumpPorts(
    context: Context,
    private val packages: Map<String, List<String>> = APP_PACKAGES,
) : JumpPorts {
    private val app = context.applicationContext
    private val manager: PackageManager = app.packageManager

    override fun installed(platform: String): String {
        val candidates = packages[platform].orEmpty()
        if (candidates.isEmpty()) return UNKNOWN
        return runCatching { if (candidates.any { name -> runCatching { manager.getPackageInfo(name, 0) }.isSuccess }) "true" else "false" }
            .getOrDefault(UNKNOWN)
    }

    @Suppress("ReturnCount") // 地址不合法、只给浏览器、启动三个出口
    override fun launchApp(
        type: String,
        value: String,
    ): Boolean {
        val uri = runCatching { value.toUri() }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (type == JumpTypes.UNIVERSAL_LINK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            } else if (resolvesToBrowser(intent)) {
                return false
            }
        }
        return start(intent)
    }

    override fun openBrowser(url: String): Boolean {
        val intent = BrowserExit.intentFor(manager, url) ?: return false
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun copy(text: String): Boolean =
        runCatching {
            val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            true
        }.getOrDefault(false)

    private fun resolvesToBrowser(intent: Intent): Boolean {
        val target = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return true
        val browsers =
            manager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
                .mapNotNull { it.activityInfo?.packageName }
        return target in browsers
    }

    private fun start(intent: Intent): Boolean =
        try {
            app.startActivity(intent)
            true
        } catch (
            @Suppress("SwallowedException") missing: ActivityNotFoundException,
        ) {
            false
        } catch (
            @Suppress("SwallowedException") denied: SecurityException,
        ) {
            false
        }

    companion object {
        const val UNKNOWN = "unknown"
        private const val CLIP_LABEL = "tpwd"

        /** apps.json apps.<平台>.android.packages（contract.lock 9cd1e6f）：全部为空。CT-05 生成后从生成物读取。 */
        val APP_PACKAGES: Map<String, List<String>> = mapOf("taobao" to emptyList(), "jd" to emptyList(), "pdd" to emptyList())
    }
}
