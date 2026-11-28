package com.couli.app.search

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.ProductVisualFixtures
import com.couli.app.purchase.PurchaseVisualApi
import com.couli.app.purchase.PurchaseVisualPorts
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.trade.AppTrade
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.feature.product.ProductDetailController
import com.couli.feature.trade.ColdAuthResume
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.TradeOperations
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-TB-WEBCODE-B 修改 1（D10-3690 接入点）：商品详情页经正式 [AppSearch.productConfig] 的 bindController 接上冷启动恢复槽的认领。
 * 下面三件事任一发生都试认领一次：详情卡就绪、页面回到前台（[AppSearch.claimColdAuthResume]，ProductDetailRoute 的 ON_START 在执行器
 * onForeground 之后调它）、票刚登记（[ColdAuthResume.subscribe]，切到主线程）。同一张票只认领一次；页面离开后不再认领。搜索页不接（源码核对见
 * WebCodeOrphanAssemblySourceTest q07）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ProductColdResumeClaimWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = PurchaseVisualApi()
    private val ports = PurchaseVisualPorts()
    private val card: JsonObject = ProductVisualFixtures.card()
    private val productKey = card.getValue("product_key").jsonPrimitive.content
    private val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
    private val routing =
        AppRouting(context, gate).also {
            it.privacy.value = PrivacyState.CONSENTED
            it.session.value = SessionState.PHONE_BOUND
        }
    private val store =
        ConfigStore(
            { null },
            {},
            { _, _ -> },
            { ProductVisualFixtures.now },
            """{"features":{"flags":{},"search_status":{"taobao":"on"},"platform_status":{"taobao":"on","jd":"on","pdd":"on"}}}""",
        )
    private val client =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { "https://api.example.test/" },
            context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )
    private val queue =
        TrackerQueue(
            readFile = { null },
            writeFile = {},
            sink = { _: List<String> -> false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "fixture-device",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "fixture-session",
            clock = { ProductVisualFixtures.now },
            canUpload = { false },
        )
    private val trade = AppTrade(api, store, routing, Provider { queue }, ports, ports, ports)
    private val search = AppSearch(client, store, routing, { PrivacyState.CONSENTED }, api = api, trade = trade)

    @After
    fun close() {
        search.purchaseController(ENTRY)?.let(trade::release)
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun opens() = api.calls(TradeOperations.OPEN_LINK)

    /** 冷启动前那个页面登记的票：link_id 是待办里的旧值，product_key 与本页详情卡一致。 */
    private fun ticket() = ColdAuthResume.Ticket("link-old", productKey, "taobao", Long.MAX_VALUE)

    private fun page(): OpenPurchaseController = checkNotNull(search.purchaseController(ENTRY))

    /** 打开详情页：正式 productConfig 的 bindController 拿到真实的 ProductDetailController，再按路由参数取详情（替身直接返回 [card]）。 */
    private fun openDetail(): ProductDetailController {
        api.product = card
        val params = """{"platform":"taobao","product_key":"$productKey"}"""
        val config = checkNotNull(search.productConfig(RouteDestination("ProductDetail", params, false), ENTRY) {})
        val detail = ProductDetailController(config.api, config.context, scope)
        config.bindController(detail)
        detail.load(config.platform, config.productKey, config.itemRef)
        idle()
        return detail
    }

    private fun assertOpenedTheTicketLink() {
        assertEquals("认领后发一次 open：${api.calls.map { it.call.operationId }}", 1, opens().size)
        assertEquals("open 的是票里（待办里）的 link_id", mapOf("link_id" to "link-old"), opens().single().call.pathParams)
        assertNull("票已取走", trade.coldResume.peek())
    }

    @Test
    fun w01_aTicketWaitingWhenTheDetailCardBecomesReadyIsClaimedOnce() {
        trade.coldResume.offer(ticket())
        openDetail()
        assertOpenedTheTicketLink()
        page().onBackground()
        page().onForeground()
        assertFalse("同一张票：回到前台不再认领", search.claimColdAuthResume(ENTRY))
        idle()
        assertEquals("只 open 一次", 1, opens().size)
    }

    @Test
    fun w02_aTicketOfferedWhileInTheBackgroundIsClaimedWhenThePageIsBackInTheForeground() {
        openDetail()
        assertTrue("没有票：不 open", opens().isEmpty())
        page().onBackground()
        trade.coldResume.offer(ticket())
        idle()
        assertTrue("不在前台：票刚登记也不认领", opens().isEmpty())
        assertNotNull("票留着", trade.coldResume.peek())
        // ProductDetailRoute 的 ON_START：执行器 onForeground，再试认领
        page().onForeground()
        assertTrue("回到前台：认领", search.claimColdAuthResume(ENTRY))
        idle()
        assertOpenedTheTicketLink()
        assertFalse("同一张票只认领一次", search.claimColdAuthResume(ENTRY))
        idle()
        assertEquals(1, opens().size)
    }

    @Test
    fun w03_aTicketOfferedWhileTheDetailIsShownIsClaimedOnce() {
        openDetail()
        assertTrue("没有票：不 open", opens().isEmpty())
        trade.coldResume.offer(ticket())
        idle()
        assertOpenedTheTicketLink()
        page().onBackground()
        page().onForeground()
        assertFalse("同一张票：之后的触发都认领不到", search.claimColdAuthResume(ENTRY))
        idle()
        assertEquals("只 open 一次", 1, opens().size)
    }

    @Test
    fun w04_aLeftDetailPageNoLongerClaims() {
        openDetail()
        // 页面出栈（回退栈里没有本页条目）：执行器作废、退订恢复槽
        search.followBackStackIds(setOf("another-entry"))
        assertNull("执行器已作废", search.purchaseController(ENTRY))
        trade.coldResume.offer(ticket())
        idle()
        assertTrue("离开后票刚登记也不认领", opens().isEmpty())
        assertNotNull("票留着（到期由处理器只提示再点一次）", trade.coldResume.peek())
        assertFalse(search.claimColdAuthResume(ENTRY))
    }

    private companion object {
        const val ENTRY = "cold-resume-detail"
    }
}
