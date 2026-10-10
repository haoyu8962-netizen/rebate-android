package com.couli.app.me

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.DeviceCredentials
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-12 Android 第 1 轮评审 R1-10（AC-S1-137）：生产的 [AndroidMeSystem] 清缓存——WebView 资源缓存（主线程，真实 WebView 的请求次数核对见
 * feature:web 仪器测试 WebViewCachesTest）与缓存目录都清；会话、待办（pending_action）、本机偏好与 filesDir 下的数据保留。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class AndroidMeSystemTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture-ABC123"
        override val installSecret: String = "install-fixture"
    }

    @Test
    fun clearingTheCacheKeepsTheSessionPendingActionAndFiles() {
        val cached = File(context.cacheDir, "WebView/Default/HTTP Cache/entry").apply { parentFile?.mkdirs() }
        cached.writeText("cached")
        val kept = File(context.filesDir, "config_lkg.json").apply { writeText("{}") }
        context
            .getSharedPreferences(SESSION, Context.MODE_PRIVATE)
            .edit()
            .putString("access_token", "access-test")
            .commit()
        context
            .getSharedPreferences(ROUTER, Context.MODE_PRIVATE)
            .edit()
            .putString("pending_action", "{\"route\":\"Wallet\"}")
            .commit()
        val system = AndroidMeSystem(context, permissions = null, device = Device, images = null)
        assertTrue("缓存大小读得出", (system.cacheBytes() ?: 0L) > 0L)

        assertTrue("WebView 资源缓存清除成功（主线程）", system.clearWebCache())
        assertTrue("缓存目录清空并核对", system.clearCache())

        assertEquals(0, context.cacheDir.listFiles()?.size)
        assertTrue("filesDir 的数据保留", kept.exists())
        assertEquals("access-test", context.getSharedPreferences(SESSION, Context.MODE_PRIVATE).getString("access_token", null))
        assertEquals(
            "待办保留",
            "{\"route\":\"Wallet\"}",
            context.getSharedPreferences(ROUTER, Context.MODE_PRIVATE).getString("pending_action", null),
        )
    }

    private companion object {
        const val SESSION = "couli.session"
        const val ROUTER = "couli.router"
    }
}
