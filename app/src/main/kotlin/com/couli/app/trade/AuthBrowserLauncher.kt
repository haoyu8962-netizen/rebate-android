package com.couli.app.trade

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.core.net.toUri
import com.couli.app.web.BrowserExit
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 打开淘宝授权页（设计稿 §4）：Custom Tab，没有就退回系统浏览器；都打不开返回 false。授权器只经这个接口打开，单测注入替身
 * （测试不依赖 androidx.browser，D10-3568）。生产实现是 [CustomTabAuthBrowserLauncher]。
 */
fun interface AuthBrowserLauncher {
    fun open(authUrl: String): Boolean
}

/** MainActivity 的可见性事件（不含配置变更重建）。 */
enum class AuthReturnEvent {
    STOPPED,
    STARTED,
}

/**
 * 「用户从授权页回到 App」的信号（设计稿 §3.1、§6.1）：MainActivity onStop / onStart 的事件流。授权器只认拉起之后「先 STOPPED、再 STARTED」。
 * 生产实现是 [ActivityAuthReturnSignal]。
 */
interface AuthReturnSignal {
    val events: Flow<AuthReturnEvent>
}

/**
 * MainActivity 的可见性信号（进程内一份）：MainActivity 在 onStop（配置变更除外）调 [stopped]、在 onStart 调 [started]。
 * 只有事件，不带任何页面内容；没有订阅者时事件直接丢弃（不回放）。
 */
class ActivityAuthReturnSignal : AuthReturnSignal {
    private val flow = MutableSharedFlow<AuthReturnEvent>(extraBufferCapacity = EVENT_BUFFER)

    override val events: Flow<AuthReturnEvent> = flow.asSharedFlow()

    fun stopped() {
        flow.tryEmit(AuthReturnEvent.STOPPED)
    }

    fun started() {
        flow.tryEmit(AuthReturnEvent.STARTED)
    }

    private companion object {
        const val EVENT_BUFFER = 8
    }
}

/**
 * 授权页打开方式（设计稿 §4.2–§4.4）：
 * 1. 有当前 Activity（MainActivity 在 onCreate / onDestroy 时 [attach] / [detach]），并且本机有支持 Custom Tab 的浏览器（从 CATEGORY_APP_BROWSER
 *    里挑声明了 CustomTabsService 的包，系统默认浏览器优先）：用 Custom Tab 从这个 Activity 打开，`setPackage` 限定在该浏览器里，
 *    不加 NEW_TASK、不加 NO_HISTORY；不分享、显示标题；**不绑定 CustomTabsService、不预取**（预取会提前发出带 state 的地址）。
 * 2. 否则（或拉起失败）：`BrowserExit.intentFor` 加 NEW_TASK 交给系统浏览器（同样只交给浏览器包）。
 * 3. 都没有 / 都失败：返回 false（授权器按 Failed）。
 */
class CustomTabAuthBrowserLauncher(
    context: Context,
) : AuthBrowserLauncher {
    private val appContext = context.applicationContext

    @Volatile
    private var host: WeakReference<Activity>? = null

    fun attach(activity: Activity) {
        host = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (host?.get() === activity) host = null
    }

    override fun open(authUrl: String): Boolean = openInCustomTab(authUrl) || openInBrowser(authUrl)

    private fun openInCustomTab(authUrl: String): Boolean {
        val activity = host?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        val tabPackage = activity?.let { customTabsPackage(appContext.packageManager) }
        if (activity == null || tabPackage == null) return false
        val tab =
            CustomTabsIntent
                .Builder()
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .setShowTitle(true)
                .build()
        tab.intent.setPackage(tabPackage)
        return start { tab.launchUrl(activity, authUrl.toUri()) }
    }

    private fun openInBrowser(authUrl: String): Boolean {
        val browser = BrowserExit.intentFor(appContext.packageManager, authUrl) ?: return false
        return start { appContext.startActivity(browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 支持 Custom Tab 的浏览器包：系统默认浏览器优先，否则取第一个；没有为 null。 */
    private fun customTabsPackage(manager: PackageManager): String? {
        val browsers =
            manager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        val preferred =
            manager
                .resolveActivity(
                    Intent(Intent.ACTION_VIEW, BrowserExit.PROBE_URL.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo
                ?.packageName
                ?.takeIf { it in browsers }
        return (listOfNotNull(preferred) + browsers).distinct().firstOrNull { supportsCustomTabs(manager, it) }
    }

    private fun supportsCustomTabs(
        manager: PackageManager,
        browserPackage: String,
    ): Boolean = manager.resolveService(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(browserPackage), 0) != null

    /** 拉起失败（没有能处理的 Activity、被系统拒绝）按打不开处理。 */
    private inline fun start(launch: () -> Unit): Boolean =
        try {
            launch()
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
}
