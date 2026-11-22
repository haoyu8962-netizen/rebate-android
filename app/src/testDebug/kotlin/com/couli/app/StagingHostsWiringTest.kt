package com.couli.app

import com.couli.app.clientcore.AppConfig
import com.couli.core.bridge.BridgeOriginsVerdict
import com.couli.core.bridge.BridgeOriginsVerifier
import com.couli.core.bridge.WebConfigReader
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentEndpointsCatalog
import com.couli.core.router.DeepLinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-STAGING-HOSTS-android：五类 staging 地址里客户端不在 EnvironmentEndpoints 里配的三类（handback §1）。
 * - 深链 l-stg：配置点是 debugFeatures 的 [variantDeepLinkConfig]（DeepLinkParser 的 hosts；只按完整主机名匹配）。
 *   清单不手写 https intent-filter（CSB-11，由 StagingHostsSourceScanTest.x03Manifests… 守），所以系统不会自动把网页链接交给 App；
 *   联调用 `adb shell am start -n <包名>/.MainActivity -d https://l-stg.couliapp.cn/r/<路由名>` 显式拉起。
 * - 桥白名单 h5-stg、分享 s-stg：客户端没有配置点，由 staging 服务端 /v1/config 的 bridge_origins / share_domains 下发
 *   （WebConfigReader；N-22 起 bridge_origins 是签名白名单 {"jws": …}，经 BridgeOriginsVerifier 用构建配置的 staging 钥验过才认）。
 *   这里钉住：staging 实测下发（三端共用向量 stg_live_ok 的 jws）能验出，且 catalog 的 h5 基址的 origin 正是要下发的那一个。
 */
class StagingHostsWiringTest {
    private val stagingConfig =
        Json
            .parseToJsonElement(
                """{"bridge_origins":{"jws":"${N22TestSigning.vectorJws("stg_live_ok")}"},"share_domains":["s-stg.couliapp.cn"]}""",
            ).jsonObject

    @Test fun d01DebugFeaturesDeepLinkConfigAcceptsTheStagingLinkHost() {
        assertEquals("深链主机只配 l-stg 一个确切主机（不用通配）", listOf("l-stg.couliapp.cn"), variantDeepLinkConfig().hosts)
    }

    @Test fun d02StagingHttpsDeepLinkParsesToTheRoute() {
        val request = DeepLinkParser.parse("https://l-stg.couliapp.cn/r/Home", variantDeepLinkConfig())
        assertEquals("Home", request?.route)
    }

    @Test fun d03DebugSchemeIsKept() {
        assertEquals(listOf(DEBUG_DEEP_LINK_SCHEME), variantDeepLinkConfig().schemes)
        assertEquals("couli-dev", DEBUG_DEEP_LINK_SCHEME)
        assertEquals("Home", DeepLinkParser.parse("couli-dev://r/Home", variantDeepLinkConfig())?.route)
    }

    @Test fun d04LookalikeHostsAndPlainHttpAreRejected() {
        val config = variantDeepLinkConfig()
        listOf(
            "https://x.l-stg.couliapp.cn/r/Home",
            "https://l-stg.couliapp.cn.evil.invalid/r/Home",
            "http://l-stg.couliapp.cn/r/Home",
            "https://l-stg.couliapp.cn:8443/r/Home",
            "https://l.couliapp.com/r/Home",
            "https://s-stg.couliapp.cn/r/Home",
        ).forEach { assertNull("不得接受：$it", DeepLinkParser.parse(it, config)) }
    }

    @Test fun b01StagingH5BaseOriginIsTheBridgeOriginToBeDelivered() {
        val h5 = EnvironmentEndpointsCatalog.endpoints(AppEnvironment.STAGING).h5BaseUrl
        val verdict = stagingVerdict(stagingConfig)
        assertTrue("staging 实测下发须验过：$verdict", verdict is BridgeOriginsVerdict.Accepted)
        val trusted = (verdict as BridgeOriginsVerdict.Accepted).allowlist.origins
        assertEquals(listOf("https://h5-stg.couliapp.cn"), trusted)
        assertTrue("STAGING h5 基址（$h5）的 origin 必须在 staging 下发的 bridge_origins 里", h5.trimEnd('/') in trusted)
    }

    @Test fun b02BridgeOriginsMustBeASignedJws() {
        val objectShape = Json.parseToJsonElement("""{"bridge_origins":{"h5":"https://h5-stg.couliapp.cn"}}""").jsonObject
        assertEquals("对象形状不放行（N-22：须下发签名的 jws）", BridgeOriginsVerdict.Rejected("malformed"), stagingVerdict(objectShape))
        val arrayShape = Json.parseToJsonElement("""{"bridge_origins":["https://h5-stg.couliapp.cn"]}""").jsonObject
        assertEquals("旧的 origin 数组不再放行（N-22）", BridgeOriginsVerdict.Rejected("malformed"), stagingVerdict(arrayShape))
    }

    /** 构建配置的公钥表（debug 带 staging 钥）、运行环境 staging。 */
    private fun stagingVerdict(config: JsonObject) =
        BridgeOriginsVerifier.verify(
            config = config,
            appId = AppConfig.APP_ID,
            env = "staging",
            lastGoodVersion = null,
            keys = AppConfig.bundledSignKeys,
        )

    @Test fun s01StagingShareDomainIsReadFromConfig() {
        assertEquals(setOf("s-stg.couliapp.cn"), WebConfigReader.shareDomains(stagingConfig))
    }
}
