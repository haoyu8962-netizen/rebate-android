package com.couli.feature.home

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ReplayMode
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** FU-X-HOME-PAGE-API 测试 v4 / r1 S1：尚无商品的空页加载链，遇到限流后自动恢复原游标及 D4 预算。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions")
class HomePageApiRateLimitTest {
    @Test
    fun s1UnionMaterialLocalGateResumesC2AndShowsProducts() = recovery(pool = false, local = true, exhausted = false)

    @Test
    fun s1ProductPoolLocalGateResumesC2AndShowsProducts() = recovery(pool = true, local = true, exhausted = false)

    @Test
    fun s1UnionMaterialServer42901ResumesC2AndShowsProducts() = recovery(pool = false, local = false, exhausted = false)

    @Test
    fun s1ProductPoolServer42901ResumesC2AndShowsProducts() = recovery(pool = true, local = false, exhausted = false)

    @Test
    fun s1UnionMaterialLocalGateKeepsEmptyRefetchBudget() = recovery(pool = false, local = true, exhausted = true)

    @Test
    fun s1ProductPoolLocalGateKeepsEmptyRefetchBudget() = recovery(pool = true, local = true, exhausted = true)

    @Test
    fun s1UnionMaterialServer42901KeepsEmptyRefetchBudget() = recovery(pool = false, local = false, exhausted = true)

    @Test
    fun s1ProductPoolServer42901KeepsEmptyRefetchBudget() = recovery(pool = true, local = false, exhausted = true)

    @Test
    fun s1UnionMaterialThreeEmptyRefetchesHideCardAndStop() = emptyLimit(pool = false)

    @Test
    fun s1ProductPoolThreeEmptyRefetchesHideCardAndStop() = emptyLimit(pool = true)

    private fun recovery(
        pool: Boolean,
        local: Boolean,
        exhausted: Boolean,
    ) {
        val rig = Rig(pool, local, exhausted)
        try {
            rig.controller.start()
            idle()
            assertTrue("42901 必须经另一张卡或 c2 的实际响应启动共享闸门", rig.controller.state.value.rateLimited)
            if (local) {
                assertEquals(listOf(request(rig.target, "c2")), rig.port.waiting)
                rig.port.release.complete(Unit)
                idle()
                assertEquals("c2 确实被发送前闸门拒绝，没有发出", listOf(request(rig.target, "c2")), rig.port.rejected)
                assertNull("本地拦截不记成服务端失败", rig.controller.state.value.sectionErrors[rig.target])
            }
            val before = if (local) listOf<String?>(null) else listOf(null, "c2")
            rig.assertRequests(before)
            assertNull("尚未拿到商品，卡片隐藏", rig.card())
            rig.pass(RETRY_AFTER_MS - 1)
            assertTrue("到期前 1 毫秒仍禁用", rig.controller.state.value.rateLimited)
            rig.assertRequests(before)
            assertNull(rig.card())

            // 只推进时钟：不刷新、不触底、不调 retryFeed，也不挂 Compose 触底项帮忙恢复。
            rig.pass(1)
            assertFalse(rig.controller.state.value.rateLimited)
            val cursors = before + if (exhausted) listOf("c2", "c3", "c4") else listOf("c2", "c3")
            rig.assertRequests(cursors)
            if (exhausted) {
                assertNull("空页预算保留：c4 仍空即隐藏，不能重置预算再取 c5 的商品", rig.card())
            } else {
                val keys =
                    when (val card = rig.card()) {
                        is HomeCardUi.Feed -> card.items.map { it.key }
                        is HomeCardUi.ProductScroll -> card.items.map { it.key }
                        else -> emptyList()
                    }
                assertEquals("恢复后取到商品，目标卡显示", listOf("test:p0", "test:p1"), keys)
            }
            if (local) {
                assertEquals(
                    "触发限流的另一张卡也按既有首屏逻辑恢复",
                    listOf(request(BLOCKER, null), request(BLOCKER, null)),
                    rig.port.sent.filter { it.sectionId == BLOCKER },
                )
            }
            val visible = rig.card() != null
            rig.pass(RETRY_AFTER_MS)
            rig.assertRequests(cursors)
            assertEquals("再次经过到期时间也不追加请求或改变可见性", visible, rig.card() != null)
        } finally {
            rig.controller.invalidate()
        }
    }

    private fun emptyLimit(pool: Boolean) {
        val rig = Rig(pool, local = false, exhausted = true, limited = false)
        try {
            rig.controller.start()
            idle()
            val cursors = listOf(null, "c2", "c3", "c4")
            rig.assertRequests(cursors)
            assertNull("首取 + 3 次续取仍空，卡隐藏；c5 的商品不可取", rig.card())
            rig.pass(RETRY_AFTER_MS)
            rig.assertRequests(cursors)
            assertNull(rig.card())
        } finally {
            rig.controller.invalidate()
        }
    }

    private class Rig(
        pool: Boolean,
        local: Boolean,
        exhausted: Boolean,
        limited: Boolean = true,
    ) {
        val target = if (pool) "demo-today" else "feed"
        private var now = HomeTestKit.NOON
        private val timers = ManualTimers { now }
        private var serverLimited = limited && !local
        private var blockerLimited = true
        private val fake =
            FakePort(page = { HomeTestKit.ok(page(pool, local)) }).apply {
                items = { section, cursor ->
                    when {
                        section == BLOCKER && blockerLimited -> {
                            blockerLimited = false
                            rateLimited()
                        }

                        section == BLOCKER -> {
                            HomeTestKit.items(10, 2, null)
                        }

                        cursor == null -> {
                            HomeTestKit.items(0, 0, "c2")
                        }

                        cursor == "c2" && serverLimited -> {
                            serverLimited = false
                            rateLimited()
                        }

                        cursor == "c2" -> {
                            HomeTestKit.items(0, 0, "c3")
                        }

                        cursor == "c3" && !exhausted -> {
                            HomeTestKit.items(0, 2, "c4")
                        }

                        cursor == "c3" -> {
                            HomeTestKit.items(0, 0, "c4")
                        }

                        cursor == "c4" -> {
                            HomeTestKit.items(0, 0, "c5")
                        }

                        cursor == "c5" -> {
                            HomeTestKit.items(0, 2, null)
                        }

                        else -> {
                            error("未预期的取卡请求：$section / $cursor")
                        }
                    }
                }
            }
        val port = RecordingPort(fake, target, local)
        val controller =
            HomeController(FakeHost.IDENTITY, testDeps(port = port, clock = { now }, timers = timers))

        fun card(): HomeCardUi? =
            controller.state.value.cards
                .firstOrNull { it.id == target }

        fun assertRequests(cursors: List<String?>) {
            assertEquals(
                "operationId + 原游标的完整目标卡发送序列",
                cursors.map { request(target, it) },
                port.sent.filter { it.sectionId == target },
            )
            assertEquals("恢复不重新取整页", listOf(Request("getPage", null, null)), port.sent.filter { it.operationId == "getPage" })
        }

        fun pass(millis: Long) {
            now += millis
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
            timers.fire(now)
            idle()
        }
    }

    private data class Request(
        val operationId: String,
        val sectionId: String?,
        val cursor: String?,
    )

    /** 沿用 FakePort，只在 c2 发送前设置一个可控挂起点，让另一张卡的 42901 先到达控制器。 */
    private class RecordingPort(
        private val fake: FakePort,
        private val target: String,
        private var holdC2: Boolean,
    ) : HomeContractPort {
        val release = CompletableDeferred<Unit>()
        val waiting = mutableListOf<Request>()
        val rejected = mutableListOf<Request>()
        val sent = mutableListOf<Request>()

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult {
            assertEquals("home", pageKey)
            if (call.allowed()) sent += Request("getPage", null, null)
            return fake.getPage(pageKey, call)
        }

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult {
            assertEquals("home", pageKey)
            val request = request(sectionId, cursor)
            if (holdC2 && sectionId == target && cursor == "c2") {
                holdC2 = false
                waiting += request
                release.await()
            }
            if (!call.allowed()) {
                rejected += request
                return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            }
            sent += request
            return fake.listSectionItems(pageKey, sectionId, cursor, call)
        }
    }

    private companion object {
        const val BLOCKER = "limit-source"
        const val RETRY_AFTER_MS = 5_000L

        fun idle() = shadowOf(Looper.getMainLooper()).idle()

        fun request(
            sectionId: String,
            cursor: String?,
        ): Request = Request("listPageSectionItems", sectionId, cursor)

        fun rateLimited(): ApiResult =
            ApiResult.Failure(
                42901,
                "",
                JsonObject(emptyMap()),
                null,
                ErrorEffect(42901, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )

        fun page(
            pool: Boolean,
            local: Boolean,
        ): JsonObject {
            val page = if (pool) HomePageApiControllerTest.scrollOnlyPage() else HomePageApiControllerTest.feedOnlyPage()
            if (!local) return page
            val blocker = (HomePageApiControllerTest.scrollOnlyPage()["sections"] as JsonArray).single() as JsonObject
            val sections = (page["sections"] as JsonArray) + JsonObject(blocker + ("id" to JsonPrimitive(BLOCKER)))
            return JsonObject(page + ("sections" to JsonArray(sections)))
        }
    }
}
