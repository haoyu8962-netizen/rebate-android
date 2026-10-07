package com.couli.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-04 三端共性第 7、8、12 条的判定层：授权页隔离、商品意图去片段、external_page_nav_host 抽样与注册域。 */
class ExternalNavGuardsTest {
    @Test
    fun knownLoginAuthorizationPagesAreAuthPages() {
        listOf(
            "https://appleid.apple.com/auth/authorize?client_id=x",
            "https://open.weixin.qq.com/connect/qrconnect?appid=x",
            "https://oauth-login.cloud.huawei.com/oauth2/v3/authorize",
            "https://OAUTH.taobao.com/authorize",
            "https://sub.passport.jd.com/login",
        ).forEach { assertTrue(it, AuthPages.isAuthPage(it)) }
    }

    @Test
    fun oauthPathsOnUnknownHostsAreAuthPagesToo() {
        assertTrue(AuthPages.isAuthPage("https://login.example.test/oauth2/authorize?x=1"))
        assertTrue(AuthPages.isAuthPage("https://shop.example.test/OAuth/start"))
    }

    /**
     * R1-03 / 共性第 35 条：授权页判定用统一规范化后的结果，路径先按 BR-ATTR-29 百分号解码一次再匹配——编码过的段、编码过的点段、
     * 反斜杠变体都绕不过去；有主机却规范化不了的（不合法的编码）宁可拦下。
     */
    @Test
    fun percentEncodedAndDotSegmentVariantsOfAuthPathsAreStillAuthPages() {
        listOf(
            "https://login.example.test/%6fauth2/%61uthorize?x=1",
            "https://login.example.test/%6Fauth2/start",
            "https://login.example.test/a/%2e%2e/OAUTH/start",
            "https://login.example.test/a\\..\\oauth2\\authorize",
            "https://login.example.test/x/..%5Coauth",
            "https://login.example.test:443/%61uthorize",
            "http://10.0.2.2:8787/%6fauth2/authorize",
            "https://login.example.test/%zz/authorize",
            "https://APPLEID.apple.com./auth",
        ).forEach { assertTrue(it, AuthPages.isAuthPage(it)) }
        assertFalse("解码只做一次：%256f 解一次是 %6f，不是 oauth", AuthPages.isAuthPage("https://login.example.test/%256fauth2/x"))
        assertFalse(AuthPages.isAuthPage("about:blank"))
        assertFalse(AuthPages.isAuthPage("javascript:void(0)"))
    }

    @Test
    fun ordinaryPagesAreNotAuthPages() {
        assertFalse(AuthPages.isAuthPage("https://brand.example.test/about"))
        assertFalse("相似主机名不算", AuthPages.isAuthPage("https://notappleid.apple.com.example.test/"))
        assertFalse(AuthPages.isAuthPage("not a url"))
    }

    @Test
    fun productIntentUrlDropsTheFragmentOnly() {
        assertEquals("https://item.example.test/i?id=1", ProductIntentUrl.withoutFragment("https://item.example.test/i?id=1#sku"))
    }

    @Test
    fun samplingUsesBasisPoints() {
        assertFalse(NavHostSample.sampled(0, 0))
        assertTrue(NavHostSample.sampled(100, 99))
        assertFalse(NavHostSample.sampled(100, 100))
        assertTrue(NavHostSample.sampled(10_000, 9_999))
    }

    @Test
    fun registeredDomainKeepsOnlyTheRegistrableName() {
        assertEquals("example.test", NavHostSample.registeredDomain("a.b.Example.test."))
        assertEquals("shop.com.cn", NavHostSample.registeredDomain("m.shop.com.cn"))
        assertEquals("10.0.0.1", NavHostSample.registeredDomain("10.0.0.1"))
        assertNull(NavHostSample.registeredDomain(null))
    }

    /** R1-09 / 共性第 39 条：缺失按 1000，显式 0 关闭，合法值原样，非法值按缺省 1000。 */
    @Test
    fun navHostSampleRateDefaultsTo1000AndExplicitZeroTurnsSamplingOff() {
        fun bp(json: String): Int =
            WebConfigReader.navHostSampleBp(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(json) as kotlinx.serialization.json.JsonObject,
            )
        assertEquals("缺失：整份配置没有 external_page", 1000, bp("{}"))
        assertEquals("缺失：external_page 里没有这个键", 1000, bp("""{"external_page":{}}"""))
        assertEquals("显式 0：关闭采样", 0, bp("""{"external_page":{"nav_host_sample_bp":0}}"""))
        assertEquals(100, bp("""{"external_page":{"nav_host_sample_bp":100}}"""))
        assertEquals(10_000, bp("""{"external_page":{"nav_host_sample_bp":10000}}"""))
        listOf("\"100\"", "12.5", "-1", "10001", "null", "true", "[]").forEach { invalid ->
            assertEquals("非法值 $invalid 按缺省", 1000, bp("""{"external_page":{"nav_host_sample_bp":$invalid}}"""))
        }
    }
}
