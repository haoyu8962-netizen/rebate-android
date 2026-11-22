package com.couli.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clientcore.AppConfig
import com.couli.app.home.variantEnvironmentKey
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import com.couli.core.network.ApiClient
import java.io.File
import javax.inject.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-22-android 评审 r1 S2-3：生产接线 `AppModule.appConfig` 里验签闭包的运行环境 id 每次现取（brief §1.2；AppModule.kt 里
 * `environmentId = { variantEnvironmentKey(context) }`），不是构造时或首次调用时取一次。
 *
 * 走的就是生产 provider 本身：直接调 [AppModule.appConfig]（Hilt 用的同一个函数，不另写一份接线），得到真 ConfigStore + 真闭包
 * （构建配置的公钥表 = debug 内置 staging 钥，环境取 debug 的 [variantEnvironmentKey]，即调试面板的选择）。
 * 喂法：在 filesDir 放一份不带 bridge_origins_last_good 字段的旧格式 LKG（data = staging 实测 /v1/config，jws 取三端共用向量
 * stg_live_ok），这样每次冷启动（[AppConfig.startIfReady] / [AppConfig.onEnvironmentSwitched] → store.onColdStart）都会用
 * **同一个闭包**现验一次；拉取器的 ApiClient 一取就抛，按拉取失败结算，不改上次可用、不改 LKG。
 * 切环境直接写调试面板的选择（DEBUG_SELECTION_PREFERENCES 的 environment 键，测试结束后删除），故意不经 EnvironmentSwitcher.switchTo：
 * 那条路会先删 LKG，就看不出闭包用的是哪个环境了。
 *
 * 判定：staging → 收下 [h5-stg]；切到 test / prod → 同一份文档被拒（kid 只在 staging 下有钥），白名单为空；再切回 staging → 又收下。
 * 若闭包在构造时或首次调用时把环境定死，第 2 步或第 1 步必红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("FunctionNaming") // 用例名前缀 n22r1_ 对应评审轮次
class N22R1RuntimeEnvironmentWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val selection =
        context.getSharedPreferences(SharedPreferencesNamespaceCleaner.DEBUG_SELECTION_PREFERENCES, Context.MODE_PRIVATE)
    private val lkgFile = File(context.filesDir, LKG_CONFIG_FILE)

    @After
    fun restore() {
        selection.edit().remove(KEY_ENVIRONMENT).commit()
        lkgFile.delete()
    }

    @Test
    fun n22r1_productionVerifierReadsTheSelectedEnvironmentOnEveryCall() {
        lkgFile.parentFile?.mkdirs()
        lkgFile.writeText("""{"format":1,"saved_at":0,"data":${stagingData()}}""")
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.apply { settleAtStartup() }

        select("staging")
        val appConfig: AppConfig = AppModule.appConfig(context, gate, Provider<ApiClient> { error("本用例不联网：拉取按失败结算") })
        appConfig.startIfReady()
        assertEquals("staging：生产闭包用 staging 钥收下实测文档", listOf(STG_ORIGIN), appConfig.store.trustedOrigins)
        assertEquals(0L, appConfig.store.trustedOriginsVersion)

        listOf("test", "prod").forEach { env ->
            select(env)
            appConfig.onEnvironmentSwitched()
            assertEquals("切到 $env：同一闭包按 $env 取钥，staging 文档不认", emptyList<String>(), appConfig.store.trustedOrigins)
            assertNull(appConfig.store.trustedOriginsVersion)
        }

        select("staging")
        appConfig.onEnvironmentSwitched()
        assertEquals("切回 staging：同一闭包再按 staging 收下", listOf(STG_ORIGIN), appConfig.store.trustedOrigins)
        assertEquals(0L, appConfig.store.trustedOriginsVersion)
        assertFalse("LKG 没被改写（拉取失败不写盘），每步现验的是同一份旧格式文档", lkgFile.readText().contains("bridge_origins_last_good"))
    }

    /** 写调试面板的选择，并核对生产取环境的函数确实读到它（键名写错会在这里红，而不是静默用构建默认）。 */
    private fun select(env: String) {
        check(selection.edit().putString(KEY_ENVIRONMENT, env).commit())
        assertEquals("variantEnvironmentKey 读到调试面板的选择", env, variantEnvironmentKey(context))
    }

    /** staging 实测 /v1/config 的 data（同 N22BridgeOriginsBuildKeysTest）；jws 取自向量 stg_live_ok。 */
    private fun stagingData(): String =
        """{"dict_version":"0","h5_base_url":"https://h5-stg.couliapp.cn","share_domains":["s-stg.couliapp.cn"],""" +
            """"bridge_origins":{"jws":"${N22TestSigning.vectorJws("stg_live_ok")}"}}"""

    private companion object {
        /** AppModule.LKG_CONFIG_FILE（private）。写错时第 1 步就收不到白名单而红。 */
        const val LKG_CONFIG_FILE = "couli_config/lkg.json"

        /** EnvironmentSwitcher.KEY_ENVIRONMENT（private）；由 [select] 里的 variantEnvironmentKey 核对。 */
        const val KEY_ENVIRONMENT = "environment"
        const val STG_ORIGIN = "https://h5-stg.couliapp.cn"
    }
}
