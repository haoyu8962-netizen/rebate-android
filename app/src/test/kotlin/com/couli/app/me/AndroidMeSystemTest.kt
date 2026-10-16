package com.couli.app.me

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.DeviceCredentials
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * FU-AND-0716（APP-12 共性第 39 条）：成功口径是「清理动作完成且没有 I/O 错误」——清理途中活着的 WebView / 磁盘缓存在 cacheDir 里重建了文件，
     * 仍报成功（原口径「清后目录为空」会把它误报成失败）；开始时就有的文件都删掉了。
     */
    @Test
    fun fuand0716_filesRecreatedWhileClearingStillCountAsASuccessfulClear() {
        val old = File(context.cacheDir, "WebView/Default/HTTP Cache/old-entry").apply { parentFile?.mkdirs() }
        old.writeText("old")
        val image = File(context.cacheDir, "image_cache/a.bin").apply { parentFile?.mkdirs() }
        image.writeText("img")
        val recreated = File(context.cacheDir, "WebView/Default/HTTP Cache/recreated-entry")

        val system = AndroidMeSystem(context, permissions = null, device = Device, images = null)
        // 旧缓存文件刚删掉，WebView 立刻在同一目录重建缓存文件（与清理并发写入；这个目录已经枚举过）
        system.afterEachCleared = { entry -> if (entry == old) recreated.writeText("new") }

        val ok = system.clearCache()

        assertTrue("清理途中有新写入仍报成功", ok)
        assertFalse("开始时就有的文件已删", old.exists())
        assertFalse(image.exists())
        assertTrue("场景成立：清后目录并不为空（原口径会误报失败）", recreated.exists())
    }

    /** FU-AND-0716：开始时就有的文件删不掉（I/O 错误）才报失败。 */
    @Test
    fun fuand0716_aFileThatCannotBeDeletedFailsTheClear() {
        val locked = File(context.cacheDir, "locked").apply { mkdirs() }
        val stuck = File(locked, "stuck.bin").apply { writeText("x") }
        locked.setWritable(false)
        try {
            assertFalse("删不掉按失败", AndroidMeSystem(context, permissions = null, device = Device, images = null).clearCache())
            assertTrue(stuck.exists())
        } finally {
            locked.setWritable(true)
            locked.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION = "couli.session"
        const val ROUTER = "couli.router"
    }
}
