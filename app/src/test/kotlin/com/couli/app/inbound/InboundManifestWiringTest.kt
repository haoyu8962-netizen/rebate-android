package com.couli.app.inbound

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Properties

/**
 * FU-AND-TB-WEBCODE-A 测试：合并后的清单（设计稿 §5.2；intent-filter 由 tools/codegen 生成到 core/generated 的库清单，靠清单合并进 App）。
 * couli 的 VIEW / BROWSABLE 只挂在 InboundSchemeActivity 上；MainActivity 不带 couli；中转 Activity 的属性
 * exported、noHistory、excludeFromRecents、taskAffinity=""、persistNever 齐全。用 PackageManager 查（Robolectric 读合并清单）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class InboundManifestWiringTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun handlers(uri: String): List<String> =
        app.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(app.packageName),
                PackageManager.MATCH_ALL,
            ).map { it.activityInfo.name }

    /** 合并清单（AGP 给 Robolectric 的 test_config.properties 里的 android_merged_manifest）里 InboundSchemeActivity 的 <activity …> 开标签。 */
    private fun inboundActivityElement(): String {
        val props = Properties()
        checkNotNull(javaClass.classLoader?.getResourceAsStream("com/android/tools/test_config.properties")) { "缺 test_config.properties" }
            .use { props.load(it) }
        val text = File(checkNotNull(props.getProperty("android_merged_manifest")) { "缺 android_merged_manifest" }).readText()
        return Regex("<activity\\b[^>]*>").findAll(text).map { it.value }.first { InboundSchemeActivity::class.java.name in it }
    }

    @Test
    fun m01_couliViewBrowsableResolvesOnlyToTheInboundActivity() {
        listOf(
            "couli://auth/taobao?code=c&state=s",
            "couli://auth/taobao?state=s&error=1",
            "couli://r/Search?q=a",
        ).forEach { uri ->
            assertEquals(
                "couli 的 VIEW / BROWSABLE 只由 InboundSchemeActivity 接：$uri",
                listOf(InboundSchemeActivity::class.java.name),
                handlers(uri),
            )
        }
    }

    @Test
    fun m02_mainActivityDoesNotDeclareCouli() {
        val all =
            app.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, Uri.parse("couli://auth/taobao?code=c&state=s")).setPackage(app.packageName),
                    PackageManager.MATCH_ALL,
                ).map { it.activityInfo.name }
        assertTrue("MainActivity 不带 couli（回跳不进 MainActivity 的 Intent）：$all", MainActivity::class.java.name !in all)
    }

    @Test
    fun m03_inboundActivityAttributes() {
        val info =
            runCatching {
                app.packageManager.getActivityInfo(ComponentName(app, InboundSchemeActivity::class.java), 0)
            }.getOrNull()
        assertNotNull("合并后的清单里要有 InboundSchemeActivity", info)
        info!!
        assertTrue("exported", info.exported)
        assertTrue("不留历史（noHistory）", (info.flags and ActivityInfo.FLAG_NO_HISTORY) != 0)
        assertTrue("不进最近任务（excludeFromRecents）", (info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0)
        assertTrue("taskAffinity=\"\"（解析后为空或 null，不是包名默认值）：${info.taskAffinity}", info.taskAffinity.isNullOrEmpty())
        // persistableMode 改读合并清单文本（D10-3587）：Robolectric 的 ActivityInfo 拷贝构造不带这个字段，PackageManager 读出恒为 0；期望值不变
        assertTrue(
            "persistableMode = persistNever（合并清单）",
            inboundActivityElement().contains("android:persistableMode=\"persistNever\""),
        )
        assertEquals("无界面主题", android.R.style.Theme_NoDisplay, info.theme)
    }
}
