package com.couli.app.web

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android 第 1 轮评审 R1-05、APP-04 三端共性第 9 条：【在浏览器打开】只交给浏览器。
 * 原实现把 makeMainSelectorActivity 的整个返回值再设为 selector（两层），系统只看一层 selector，实际按 MAIN/LAUNCHER 解析。
 * 现在：先单层查询 ACTION_MAIN + CATEGORY_APP_BROWSER 确认浏览器包，再用 setPackage 限定在这个包里解析 ACTION_VIEW；
 * 可用性判断与点击启动是同一个函数。认领了该域名的 App Links 应用（非浏览器）永远收不到。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BrowserExitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packages = shadowOf(context.packageManager)

    private fun register(
        component: ComponentName,
        vararg filters: IntentFilter,
    ) {
        packages.addActivityIfNotPresent(component)
        filters.forEach { packages.addIntentFilterForActivity(component, it) }
    }

    private fun httpsViewer(): IntentFilter =
        IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
        }

    private fun appBrowser(): IntentFilter =
        IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_BROWSER)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

    @Test
    fun onlyAnAppLinksAppAndNoBrowserMeansNoExit() {
        // 认领了 https 链接的普通 App（App Links），不是浏览器
        register(ComponentName(SHOP_APP, "$SHOP_APP.LinkActivity"), httpsViewer())

        assertNull("没有浏览器：不提供出口（可用性与启动同一判断）", BrowserExit.intentFor(context.packageManager, BrowserExit.PROBE_URL))
        assertNull(BrowserExit.intentFor(context.packageManager, URL))
    }

    @Test
    fun theIntentIsPinnedToTheBrowserPackageSoAppLinksAppsNeverReceiveIt() {
        register(ComponentName(SHOP_APP, "$SHOP_APP.LinkActivity"), httpsViewer())
        register(ComponentName(BROWSER, "$BROWSER.Main"), appBrowser(), httpsViewer())

        val intent = checkNotNull(BrowserExit.intentFor(context.packageManager, URL))

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(URL, intent.dataString)
        assertEquals("限定在浏览器包内解析", BROWSER, intent.`package`)
        assertNull("不再用嵌套 selector", intent.selector)
        val target =
            context.packageManager
                .queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        assertEquals("系统只会在浏览器包里找活动", setOf(BROWSER), target)
    }

    @Test
    fun aBrowserWithoutAViewHandlerIsNotOffered() {
        // 只声明了 APP_BROWSER、却不能打开 https 链接：同样不提供出口（不会点了没反应）
        register(ComponentName(BROWSER, "$BROWSER.Main"), appBrowser())

        assertNull(BrowserExit.intentFor(context.packageManager, URL))
    }

    private companion object {
        const val SHOP_APP = "com.example.shop"
        const val BROWSER = "com.example.browser"
        const val URL = "https://brand.example.test/page"
    }
}
