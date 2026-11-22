package com.couli.app

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clientcore.AppConfig
import com.couli.core.bridge.ConfigSignKeys
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-22（D10-2539）debug / staging 构建的随包公钥表（BuildConfig.CONFIG_SIGN_KEYS → AppConfig.bundledSignKeys）：
 * - 含 (staging, config-staging-2026-11b, SPKI sha256-16 07ba14efc5908e5c)，不含向量测试钥（env test / kid n22-vector-test-key-1）；
 * - staging 实测：/v1/config 的 data（bridge_origins.jws 取自三端共用向量 stg_live_ok，与 2026-11-22 api-stg 回包逐字相同；回包本身不另存
 *   进仓库，免得 gitleaks 报）经真 ConfigStore + 生产验签闭包（运行环境 staging、构建配置的公钥表）→ 白名单 == [h5-stg]。
 * 要 debug 变体的 BuildConfig，所以放 testDebug；release 不含 staging 钥由 app/src/test 的 N22BridgeOriginsWiringTest 读源码核对。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class N22BridgeOriginsBuildKeysTest {
    @Test
    fun k1DebugBuildCarriesTheStagingKeyAndNotTheTestKey() {
        val entries = AppConfig.bundledSignKeys.entries
        assertTrue(
            "含 staging 钥：$entries",
            ConfigSignKeys.Entry("staging", "config-staging-2026-11b", "07ba14efc5908e5c") in entries,
        )
        assertTrue(AppConfig.bundledSignKeys.hasKey("staging", "config-staging-2026-11b"))
        assertFalse("staging kid 不在 prod 下", AppConfig.bundledSignKeys.hasKey("prod", "config-staging-2026-11b"))
        assertTrue("不含 env test 的钥：$entries", entries.none { it.env == "test" })
        assertTrue("不含向量测试钥 kid：$entries", entries.none { it.kid == "n22-vector-test-key-1" })
        assertTrue("不含向量测试钥（按 SPKI）：$entries", entries.none { it.spkiSha256Prefix16 == TEST_KEY_SHA16 })
        val testKeyHex = hex(pemToDer(File(N22TestSigning.root, TEST_KEY_PEM).readText()))
        assertFalse("构建配置串里没有测试钥", BuildConfig.CONFIG_SIGN_KEYS.contains(testKeyHex))
        assertFalse("构建配置串里没有 n22-vector-test-key-1", BuildConfig.CONFIG_SIGN_KEYS.contains("n22-vector-test-key-1"))
        val stagingHex = hex(pemToDer(File(N22TestSigning.root, STAGING_KEY_PEM).readText()))
        assertTrue("staging 钥就是 es256.pub.pem 的 SPKI", BuildConfig.CONFIG_SIGN_KEYS.contains("staging:config-staging-2026-11b:$stagingHex"))
    }

    @Test
    fun s1StagingLiveConfigGivesTheStagingH5Origin() {
        val store = stagingStore(environment = "staging")
        store.onColdStart()
        assertEquals(listOf("https://h5-stg.couliapp.cn"), store.trustedOrigins)
        assertEquals(0L, store.trustedOriginsVersion)
    }

    @Test
    fun s2SameLiveConfigInAProdRuntimeIsNotTrusted() {
        val store = stagingStore(environment = "prod")
        store.onColdStart()
        assertEquals("prod 运行环境按 kid + env 找不到 staging 钥", emptyList<String>(), store.trustedOrigins)
        assertNull(store.trustedOriginsVersion)
    }

    private fun stagingStore(environment: String) =
        ConfigStore(
            readFile = { null },
            writeFile = {},
            fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, stagingData(), "\"stg\"")) },
            clock = { 0L },
            bundled = """{"features":{"flags":{}}}""",
            verifyBridgeOrigins = AppConfig.bridgeOriginsVerifier(environmentId = { environment }),
        )

    /** staging 实测 /v1/config 的 data（NATIVE/N22-VECTORS/config-stg-android.json）里与本单相关的字段；jws 取自向量 stg_live_ok。 */
    private fun stagingData(): String =
        """{"dict_version":"0","h5_base_url":"https://h5-stg.couliapp.cn","share_domains":["s-stg.couliapp.cn"],""" +
            """"bridge_origins":{"jws":"${N22TestSigning.vectorJws("stg_live_ok")}"}}"""

    private fun pemToDer(pem: String): ByteArray =
        Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEST_KEY_PEM = "core/bridge/src/test/resources/n22/n22-vector-test-key-1.pub.pem"
        const val TEST_KEY_SHA16 = "f3494fc5043fc593"
        const val STAGING_KEY_PEM = "core/bridge/src/test/resources/n22/es256.pub.pem"
    }
}
