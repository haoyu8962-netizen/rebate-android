package com.couli.feature.search

import com.couli.core.designsystem.PriceTerms
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 2 轮评审（android-review-r2.md）的执行器测试（本地假服务同 [SearchControllerIntegrationTest]）：
 * - R2-01 配置确认夹在两个 search_disabled 响应之间：所有自动回落走同一入口，本轮已关闭的平台不再自动搜；
 * - R2-02 翻页 42901 限流期间配置关掉全部平台：立即作废请求、清理分页、进入「无可搜索平台」；
 * - R2-03 按保存的条件恢复：重搜第一页、不算新一轮查询、不看路由参数。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerReviewR2Test {
    private val api = FakeApi()
    private val config = FakeConfig()

    private fun TestScope.controller(): SearchController = SearchController(api, config, backgroundScope) { testScheduler.currentTime }

    @Test
    fun r2_01_configConfirmationBetweenTwoDisabledResponsesDoesNotSearchTheFirstPlatformAgain() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "taobao")
            runCurrent()
            api.fail(0, 50304, """{"platform":"taobao","reason":"search_disabled"}""")
            runCurrent()
            assertTrue(
                "淘宝关闭，自动回落京东",
                api.calls[1]
                    .call.query!!
                    .contains("platform=jd"),
            )
            // 配置确认夹在两个关闭响应之间：刷新之后的配置仍说淘宝 on，淘宝 Tab 恢复（京东在途、当前平台不变）
            config.applied = config.issued
            config.notifyChanged()
            runCurrent()
            assertEquals(listOf("taobao", "jd"), c.state.value.tabs)
            assertEquals(2, api.calls.size)
            // 京东随后也返回 search_disabled：淘宝本轮已关闭过，不再自动搜它
            api.fail(1, 50304, """{"platform":"jd","reason":"search_disabled"}""")
            runCurrent()
            assertEquals("没有自动循环回淘宝", 2, api.calls.size)
            assertEquals(listOf("taobao"), c.state.value.tabs)
            assertEquals(SearchPhase.IDLE, c.state.value.phase)
            val notice = c.state.value.notice
            assertEquals("提示保留", "jd", notice?.platform)
            // 配置继续确认（两个平台都 on）也不自动搜
            config.applied = config.issued
            config.notifyChanged()
            runCurrent()
            assertEquals(2, api.calls.size)
            // 下一次由用户触发
            assertTrue(c.submit("牛奶"))
            runCurrent()
            assertEquals(3, api.calls.size)
        }

    @Test
    fun r2_02_allPlatformsTurnedOffDuringALoadMoreRateLimitGoesUnavailableImmediately() =
        runTest {
            config.layers = layers(current = """{"taobao":"on"}""")
            val c = controller()
            c.open("牛奶", "taobao")
            runCurrent()
            api.complete(0, page(listOf("tb:1"), cursor = "c2"))
            runCurrent()
            assertTrue(c.loadMore())
            runCurrent()
            api.fail(1, 42901, "null", disableMillis = 10_000)
            runCurrent()
            assertTrue(c.state.value.blocked)
            assertNotNull(c.state.value.loadMoreError)
            // 禁用期间配置把全部平台关掉
            config.layers = layers(current = """{"taobao":"off","jd":"off","pdd":"off"}""")
            config.notifyChanged()
            runCurrent()
            val s = c.state.value
            assertEquals("限流不阻止状态切换", SearchPhase.UNAVAILABLE, s.phase)
            assertTrue(s.tabs.isEmpty())
            assertNull(s.platform)
            assertTrue("旧结果清掉", s.items.isEmpty())
            assertTrue("分页清掉", s.endReached)
            assertNull("翻页错误清掉", s.loadMoreError)
            assertFalse(c.loadMore())
            assertFalse(c.retry())
            assertEquals(2, api.calls.size)
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals("限流到期后仍是无可搜索平台", SearchPhase.UNAVAILABLE, c.state.value.phase)
            assertFalse(c.retry())
            assertEquals(2, api.calls.size)
        }

    @Test
    fun r2_02_allPlatformsTurnedOffWhileAFirstPageIsInFlightInvalidatesIt() =
        runTest {
            config.layers = layers(current = """{"taobao":"on"}""")
            val c = controller()
            c.open("牛奶", "taobao")
            runCurrent()
            config.layers = layers(current = """{"taobao":"off"}""")
            config.notifyChanged()
            runCurrent()
            assertFalse(api.calls[0].owner.stillOwned())
            assertTrue(api.calls[0].owner in api.cancelled)
            api.complete(0, page(listOf("tb:late")))
            runCurrent()
            assertEquals(SearchPhase.UNAVAILABLE, c.state.value.phase)
            assertTrue(
                c.state.value.items
                    .isEmpty(),
            )
        }

    @Test
    fun r2_03_restoredConditionsSearchAgainWithoutStartingANewRound() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            val saved = SearchConditions("牛奶", "jd", "final_price_asc", true, 1000, 5000, round = 4)
            c.open(q = null, platform = null, restored = saved)
            runCurrent()
            assertEquals(
                "q=%E7%89%9B%E5%A5%B6&platform=jd&sort=final_price_asc&has_coupon=true&price_min_fen=1000&price_max_fen=5000",
                api.calls
                    .single()
                    .call.query,
            )
            assertEquals("恢复不算新一轮查询", 4, c.state.value.round)
            assertEquals(saved, c.state.value.conditions)
            api.complete(0, page(listOf("jd:1")))
            runCurrent()
            assertEquals(SearchPhase.CONTENT, c.state.value.phase)
            c.submit("纸巾")
            runCurrent()
            assertEquals("之后用户的新查询照常加一轮", 5, c.state.value.round)
        }

    @Test
    fun r2_03_restoringIgnoresTheRouteQueryAndKeepsAnEmptyKeyword() =
        runTest {
            val c = controller()
            c.open(q = "路由词", platform = null, restored = SearchConditions("", null, "relevance", false, null, null, 0))
            runCurrent()
            assertTrue(api.calls.isEmpty())
            assertEquals(SearchPhase.IDLE, c.state.value.phase)
        }

    // ---- 假服务

    private class Pending(
        val call: ApiCall,
        val owner: CallOwnership,
        val result: CompletableDeferred<ApiResult>,
    )

    private class FakeApi : PageApi {
        val calls = mutableListOf<Pending>()
        val cancelled = mutableListOf<CallOwnership>()

        override suspend fun send(call: ApiCall): ApiResult {
            val pending = Pending(call, call.ownership!!, CompletableDeferred())
            calls += pending
            return pending.result.await()
        }

        override fun cancelOwned(owner: CallOwnership) {
            cancelled += owner
        }

        override fun epochs(): CallEpochs = CallEpochs(0, 0)

        fun complete(
            index: Int,
            data: String,
        ) {
            calls[index].result.complete(ApiResult.Success(Json.parseToJsonElement(data), TRACE, null))
        }

        fun fail(
            index: Int,
            code: Int,
            data: String,
            disableMillis: Long? = null,
        ) {
            val parsed = Json.parseToJsonElement(data) as? JsonObject ?: JsonObject(emptyMap())
            val kind = if (disableMillis != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.GENERIC_ERROR
            val effect = ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = disableMillis)
            calls[index].result.complete(ApiResult.Failure(code, "", parsed, TRACE, effect, null))
        }
    }

    private class FakeConfig : SearchConfigPort {
        var layers: JsonObject = layers(current = """{"taobao":"on"}""")
        var issued = 0L
        var applied = 0L
        var refreshes = 0
        private val subscribers = mutableListOf<() -> Unit>()
        val listeners: Int get() = subscribers.size

        override fun statusLayers(): JsonObject = layers

        override fun refresh() {
            refreshes += 1
            issued += 1
        }

        override fun issuedMark(): Long = issued

        override fun appliedMark(): Long = applied

        override fun subscribe(listener: () -> Unit): () -> Unit {
            subscribers += listener
            return { subscribers -= listener }
        }

        fun notifyChanged() = subscribers.toList().forEach { it() }
    }

    private object TestTexts : SearchTexts {
        private val platforms = mapOf("platform.taobao" to "淘宝", "platform.jd" to "京东")

        override fun text(key: String): String = DefaultTexts.text(key) ?: platforms[key] ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return text
        }
    }

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000123"

        fun layers(
            current: String? = null,
            lkg: String? = null,
        ): JsonObject {
            val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            current?.let { fields["current"] = Json.parseToJsonElement(it).jsonObject }
            lkg?.let { fields["lkg"] = Json.parseToJsonElement(it).jsonObject }
            return JsonObject(fields)
        }

        fun page(
            keys: List<String>,
            cursor: String? = null,
            stale: Boolean = false,
        ): String {
            val items =
                keys.joinToString(",") { key ->
                    """{"product_key":"$key","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                        """"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","stale":$stale,"age_sec":12,""" +
                        """"cta":{"text_key":"btn.buy.coupon"},"availability":"ok"}"""
                }
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$items],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
