package com.couli.feature.home

import com.couli.core.home.HomeCard
import com.couli.core.home.HomeJson
import com.couli.core.home.HomePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-HOME-PAGE-API-android 测试 v1（独立测试作者）：页面响应解析里信息流数据源的形状（D10-607 安卓第 1 处，HomeJson.kt:345–361；D2「platforms / channels
 * 两者都认」）。契约（rebate-platform 00deded0ef6f contracts/home-schema.json `data_source_union_material`）只有
 * `channels: [{platform, channel_id}]`（minItems 1）；三端向量与包内默认仍是旧的 `platforms`，本单两种都认，三端向量改形另立 FU-X-GOLDEN-CHANNELS。
 * 放在 feature:home 的测试集里（只用已有的 HomeJson 接口），底版能编译、按行为红。
 */
class HomePageApiJsonTest {
    private fun parse(text: String): HomePage = requireNotNull(HomeJson.parseText(text, "android", "1.0.0")) { "页面应能解出：$text" }

    private fun feedOf(page: HomePage): HomeCard.FeedInfinite? = page.cards.firstNotNullOfOrNull { it as? HomeCard.FeedInfinite }

    @Test
    fun j01ContractChannelsFeedDecodesAndTakesThePlatformsFromTheChannels() {
        val page = parse(CHANNELS_PAGE)
        assertTrue("页面级有效", page.valid)
        val feed = feedOf(page)
        assertNotNull("契约形状（channels）的信息流不得被丢：${page.dropped}", feed)
        assertEquals("平台取各频道的 platform，按下发顺序", listOf("taobao", "jd"), requireNotNull(feed).platforms)
        assertEquals("union_material", feed.source.kind)
        assertTrue("这一页没有被丢的卡：${page.dropped}", page.dropped.isEmpty())
        // 同页的商品池卡照常（productSource 只核 kind）
        assertEquals(listOf("demo-today", "demo-grid", "feed"), page.cards.map { it.id })
    }

    @Test
    fun j02ContractFixtureUnionMaterialKeepsTheFeedSection() {
        // rebate-platform 00deded0ef6f contracts/fixtures/home-golden/union-material.json 原文（sha256 d3c788d2…0605e；a7c9f1f 与之逐字节相同）
        val page = parse(UNION_MATERIAL_FIXTURE)
        val feed = feedOf(page)
        assertNotNull("契约 fixture 的 feed 必须保留：dropped=${page.dropped}", feed)
        assertEquals(listOf("taobao", "jd"), requireNotNull(feed).platforms)
        assertTrue("material_grid（union_material 宫格）保留", page.cards.any { it.id == "material_grid" && it is HomeCard.ProductGrid })
        assertTrue("feed 不在丢弃清单里", page.dropped.none { it.id == "feed" })
        // material_scroll 没有 props.title（D3 props 约定比契约严，FU-X-HOME-PROPS 另定）：本单不钉它保留与否
    }

    @Test
    fun j03LegacyPlatformsShapeStillDecodes() {
        // 三端向量 goldenPages.feed_infinite、包内默认、假服务场景仍用旧形状（D2：两者都认，不要求拒绝 platforms）
        val feed = feedOf(parse(LEGACY_PAGE))
        assertNotNull("旧形状（platforms）照旧能解", feed)
        assertEquals(listOf("taobao", "jd", "pdd"), requireNotNull(feed).platforms)
    }

    @Test
    fun j04EmptyChannelsIsDroppedLikeEmptyPlatforms() {
        // 契约 invalid/union-material-empty-channels.json（00deded0ef6f，sha256 6b5fdf3f…b0ff）：channels 为空 → 只丢这一张
        val page = parse(EMPTY_CHANNELS_FIXTURE)
        assertTrue(page.valid)
        assertTrue("channels 为空的信息流不得保留", feedOf(page) == null)
        assertEquals(listOf("feed"), page.dropped.map { it.id })
    }

    @Test
    fun j05ChannelsWithoutAPlatformIsDropped() {
        val noPlatform = CHANNELS_PAGE.replace(""""platform":"jd","channel_id"""", """"channel_id"""")
        val page = parse(noPlatform)
        assertTrue("频道缺 platform（契约 required）整张丢弃，不猜平台", feedOf(page) == null)
        assertTrue(page.dropped.any { it.id == "feed" })
    }

    companion object {
        /** 00deded0ef6f 契约形状：两张商品池卡（照 ops 07:14 回读的 staging home v1）+ channels 信息流。 */
        const val CHANNELS_PAGE: String =
            """{"page_key":"home","version":41,"ttl_sec":300,"sections":[""" +
                """{"id":"demo-today","type":"product_scroll","version":1,"props":{"title":"today"},""" +
                """"data_source":{"kind":"product_pool","limit":10,"pool_id":"01a1a33a-f20f-7c9d-84c7-28db459b845f"}},""" +
                """{"id":"demo-grid","type":"product_grid","version":1,"props":{"title":"grid","columns":2},""" +
                """"data_source":{"kind":"product_pool","limit":20,"pool_id":"01a1a33a-f20f-7c9d-84c7-28db459b845f"}},""" +
                """{"id":"feed","type":"feed_infinite","version":1,"data_source":{"kind":"union_material",""" +
                """"channels":[{"platform":"taobao","channel_id":"demo_channel_1"},{"platform":"jd","channel_id":"demo_channel_2"}],""" +
                """"mix":"interleave"}}]}"""

        const val LEGACY_PAGE: String =
            """{"page_key":"home","version":9,"sections":[{"id":"feed","type":"feed_infinite","version":1,""" +
                """"data_source":{"kind":"union_material","platforms":["taobao","jd","pdd"],"mix":"interleave"}}]}"""

        const val UNION_MATERIAL_FIXTURE: String =
            """{"page_key":"home","version":1,"ttl_sec":300,"sections":[""" +
                """{"id":"material_scroll","type":"product_scroll","version":1,"data_source":{"kind":"union_material",""" +
                """"channels":[{"platform":"jd","channel_id":"demo_channel_2"}],"mix":"interleave"}},""" +
                """{"id":"material_grid","type":"product_grid","version":1,"props":{"columns":1},""" +
                """"data_source":{"kind":"union_material",""" +
                """"channels":[{"platform":"pdd","channel_id":"demo_channel_3"}]}},""" +
                """{"id":"feed","type":"feed_infinite","version":1,"data_source":{"kind":"union_material",""" +
                """"channels":[{"platform":"taobao","channel_id":"demo_channel_1"},{"platform":"jd","channel_id":"demo_channel_2"}],""" +
                """"mix":"interleave"}}]}"""

        const val EMPTY_CHANNELS_FIXTURE: String =
            """{"page_key":"home","version":1,"ttl_sec":300,"sections":[{"id":"feed","type":"feed_infinite","version":1,""" +
                """"data_source":{"kind":"union_material","channels":[]}}]}"""
    }
}
