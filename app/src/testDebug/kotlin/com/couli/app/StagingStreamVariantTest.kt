package com.couli.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FU-X-STAGING-HOSTS-android 测试 v2（D10-598 ①）：debug / staging 变体的 `currentStreamBaseUrl(context, cleaners)` 跟着当前环境走——
 * STAGING 为 stream-stg，LOCAL / TEST / PROD 为 null（ApiClient 照底版把 agent 请求发往 api 基址）。与 currentApiBaseUrl 同签名、同一个
 * EnvironmentSwitcher（handback v2 §2 V4）。用反射调用，函数没加之前 app 的其余测试照常编译。
 */
@RunWith(AndroidJUnit4::class)
class StagingStreamVariantTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun currentStreamBaseUrl(cleaners: EnvironmentSwitchCleanerRegistry): String? {
        val method =
            runCatching {
                Class
                    .forName("com.couli.app.VariantEnvironmentKt")
                    .getDeclaredMethod("currentStreamBaseUrl", Context::class.java, EnvironmentSwitchCleanerRegistry::class.java)
            }.getOrNull()
        assertNotNull("VariantEnvironment.kt 须有 currentStreamBaseUrl(context, cleaners)（与 currentApiBaseUrl 同签名）", method)
        return method!!.apply { isAccessible = true }.invoke(null, context, cleaners) as String?
    }

    @Test fun v01StreamBaseFollowsTheSelectedEnvironment() {
        val cleaners = EnvironmentSwitchCleanerRegistry()
        val switcher = EnvironmentSwitcher(context, buildDefaultEnvironment(), cleaners)
        val expected =
            mapOf(
                AppEnvironment.STAGING to "https://stream-stg.couliapp.cn",
                AppEnvironment.LOCAL to null,
                AppEnvironment.TEST to null,
                AppEnvironment.PROD to null,
            )
        expected.forEach { (environment, stream) ->
            if (switcher.current != environment) assertTrue(switcher.switchTo(environment))
            assertEquals("$environment 的流式基址", stream, currentStreamBaseUrl(cleaners))
            assertEquals("$environment 的 api 基址不受影响", switcher.endpoints.apiBaseUrl, currentApiBaseUrl(context, cleaners))
        }
    }
}
