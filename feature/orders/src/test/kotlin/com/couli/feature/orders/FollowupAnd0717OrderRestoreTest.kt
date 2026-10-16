package com.couli.feature.orders

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTheme
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.SessionIdentity
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderRestoreRecord
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0717（F-X-19 / 补充、F-X-20、F-X-18 补充）：订单 Tab 根在进程重建后的一次性恢复，走真实 [OrderListScreen]、[OrderListViewModel] 与
 * [OrderRestoreStore]（按持久化登录世代绑定），网络换成可控的假接口。
 * - 冷启动打开订单 Tab：按恢复记录回到锚点（目标卡顶对齐）；
 * - 锚点在第一页末尾（F-X-20）：目标之后不够一屏 → 先翻页再对齐；翻页失败时不挂死、不重复请求；
 * - 身份暂时未知时建页（F-X-19 补充）：恢复记录与保存状态都不动（不读、不删、不写）；身份确认后经 [OrderListHandle.onIdentityConfirmed] 换成已确认身份，
 *   照样回到锚点（补充三：未知期建的页恢复后能加载）；
 * - 新一轮查询（F-X-18 补充）：同步放弃恢复、清掉旧锚点，第一次持久化就是「新条件 + 空锚点」。
 * 等待一律按条件（列表请求、卡片出现、恢复结束原因），不按固定轮次断言异步结果。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class FollowupAnd0717OrderRestoreTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val root: File = Files.createTempDirectory("fuand0717-restore").toFile()

    /** 令牌存储里的持久化登录世代（存储的来源；页面快照里的分区与它一致）。 */
    @Volatile private var partition: String? = "login-1"

    private val store = OrderRestoreStore(root, OrderStaleCache.SiblingFileFlag(root)).apply { bindLoginEpoch { partition } }

    private inner class Session : OrderEnv {
        @Volatile var known = true

        override fun loggedIn() = true

        override fun sessionEpoch() = 1L

        override fun account() = if (known) USER else null

        override fun identity(): SessionIdentity =
            if (known) {
                SessionIdentity(
                    known = true,
                    account = USER,
                    loginEpoch = 1L,
                    partition = partition,
                )
            } else {
                SessionIdentity.unknown(1L)
            }

        override fun helpLinks(): JsonObject? = null

        override fun copy(text: String) = true

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun forceUpdate() = Unit

        override fun onDetailLoaded(detail: OrderDetail) = Unit
    }

    /** 两页订单：第一页 ord-0..19（游标 c2），第二页 ord-20..39；[secondFails] 时第二页断网。 */
    private inner class Pages : PageApi {
        val queries = mutableListOf<String?>()

        @Volatile var secondFails = false

        /** 第二页挂住（恢复任务停在「等翻页」）。 */
        @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun send(call: ApiCall): ApiResult {
            synchronized(queries) { queries += call.query }
            val second = call.query.orEmpty().contains("cursor=c2")
            if (second) hold?.await()
            if (second && secondFails) return ApiResult.TransportFailure("disconnect", null, null, false)
            val ids = if (second) 20 until 40 else 0 until 20
            val body =
                """{"items":[${ids.joinToString(",") { item("ord-$it") }}],"next_cursor":${if (second) "null" else "\"c2\""},""" +
                    """"earliest_visible_date":null}"""
            return ApiResult.Success(Json.parseToJsonElement(body), "trace-000123", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)

        fun count(): Int = synchronized(queries) { queries.size }

        fun secondPages(): Int = synchronized(queries) { queries.count { it.orEmpty().contains("cursor=c2") } }
    }

    private val session = Session()
    private val pages = Pages()
    private var handle: OrderListHandle? = null
    private lateinit var owner: Owner

    private fun item(id: String) =
        """{"order_id":"$id","platform":"taobao","title":"样例商品 A","image_url":null,"pay_amount_fen":4560,"quantity":2,""" +
            """"order_no":"3741234567890123456","display_status":"PAID","reason":null,"est_rebate_fen":234,""" +
            """"paid_at":"2026-10-08T09:05:00+08:00","is_other_product":false}"""

    /** 进程被回收前留下的：恢复记录（锚点订单 [target]，按 login-1 绑定）+ 系统保存状态（子 Tab、分组、锚点下标、记录引用）。 */
    private fun seed(target: String): Pair<String, Bundle> {
        val ref = store.newRef()
        val record = OrderRestoreRecord(owner = OrderStaleCache.Store.hash(USER), anchorOrderId = target)
        assertTrue(store.write(ref, store.epoch, record, "login-1"))
        val args =
            Bundle().apply {
                putString("orders.scope", "self")
                putString("orders.group", "all")
                putInt("orders.anchor_index", target.removePrefix("ord-").toInt())
                putInt("orders.anchor_offset", 0)
                putString("orders.restore_ref", ref)
            }
        return ref to args
    }

    private fun launch(
        args: Bundle,
        restoreGate: (suspend () -> Unit)? = null,
    ) {
        owner = Owner(args)
        val config =
            OrderListConfig(
                pages,
                session,
                OrderDraftTexts,
                cache = null,
                mask = null,
                pending = null,
                bindController = { handle = it },
                restoreGate = restoreGate,
                restoreStore = store,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides owner) {
                CouliTheme { OrderListScreen(config) }
            }
        }
    }

    private fun idleUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + CEILING_NANOS
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("等待超时：$message；请求 ${pages.queries}，结束原因 ${model()?.restoreEnd}")
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun model(): OrderListViewModel? = handle as? OrderListViewModel

    private fun hasCard(id: String) = composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + id).fetchSemanticsNodes().isNotEmpty()

    /** 目标卡顶对齐（容差：列表上内边距 + 1px）。 */
    private fun assertTopAligned(id: String) {
        val list = composeRule.onNodeWithTag(OrderTags.LIST).fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + id).fetchSemanticsNode().boundsInRoot
        val padding = with(composeRule.density) { CouliSpacing.space4.toPx() }
        assertTrue("目标卡 $id 顶对齐（顶 ${card.top}，列表顶 ${list.top}）", kotlin.math.abs(card.top - list.top) <= padding + 1f)
    }

    private fun restoreFinished() = model()?.let { it.pendingRestore == null && it.restoreEnd != null } == true

    @Test
    fun fuand0717_x19_coldStartOpensTheOrdersTabRestoredToTheAnchor() {
        val (_, args) = seed("ord-12")
        launch(args)
        idleUntil("恢复结束") { restoreFinished() }
        assertEquals(OrderListViewModel.RESTORE_LANDED, model()?.restoreEnd)
        assertTrue(model()!!.restored)
        assertTopAligned("ord-12")
    }

    @Test
    fun fuand0717_x20_anAnchorAtTheEndOfPageOnePagesFirstThenTopAligns() {
        val (_, args) = seed("ord-19")
        launch(args)
        idleUntil("翻到第二页并恢复结束") { restoreFinished() && pages.secondPages() == 1 }
        assertEquals("先第一页、再翻页", listOf(false, true), pages.queries.map { it.orEmpty().contains("cursor=c2") })
        assertEquals(OrderListViewModel.RESTORE_LANDED, model()?.restoreEnd)
        assertTopAligned("ord-19")
    }

    @Test
    fun fuand0717_x20_aFailedNextPageEndsTheRestoreWithoutHangingOrRepeatingTheRequest() {
        pages.secondFails = true
        val (_, args) = seed("ord-19")
        launch(args)
        idleUntil("翻页失败后恢复结束") {
            model()?.pendingRestore == null && pages.secondPages() >= 1 &&
                model()
                    ?.controller
                    ?.state
                    ?.value
                    ?.loadMoreError != null
        }
        assertTrue("目标卡仍在（停在已能到的位置）", hasCard("ord-19"))
        assertEquals("翻页失败只发了一次（恢复任务不重复请求）", 1, pages.secondPages())
    }

    @Test
    fun fuand0717_x19_aPageBuiltWhileTheIdentityIsUnknownKeepsTheRecordAndRestoresOnceTheIdentityIsKnown() {
        val (ref, args) = seed("ord-12")
        session.known = false
        launch(args)
        idleUntil("页面已建好") { model() != null }
        val unknownPage = model()!!
        assertTrue("未知期间：恢复记录没被删", File(root, "$ref.json").exists())
        assertEquals("未知期间：保存状态的引用原样保留", ref, model()!!.savedState.get<String>("orders.restore_ref"))
        assertEquals("未知期间：锚点下标没被覆盖", 12, model()!!.savedState.get<Int>("orders.anchor_index"))
        assertNull("未知期间不决策", unknownPage.pendingRestore)
        // 身份确认（AppOrders 判据相同、提前返回前换掉未知期间建的页）
        session.known = true
        composeRule.runOnUiThread { handle?.onIdentityConfirmed(session.identity()) }
        idleUntil("身份确认后恢复结束") { restoreFinished() }
        assertTrue("换成已确认身份后照常恢复", model()!!.restored)
        assertTopAligned("ord-12")
        assertNotNull(store.read(ref))
    }

    /**
     * FU-AND-0717 第 3 轮（第 2 轮评审 R2-05）：身份未知（恢复延后）期间手指在真实列表上按下——经页面的触摸接线记为接管；身份确认后不再按旧记录恢复（不跳回
     * 旧锚点），记录改为保存用户此刻的状态。对照见上一个用例（没有触摸时照常恢复到锚点）。
     */
    @Test
    fun fuand0717_rework_r2_05_aTouchOnTheRealListWhileDeferredIsATakeover() {
        val (_, args) = seed("ord-12")
        session.known = false
        launch(args)
        idleUntil("页面已建好") { model() != null }
        composeRule.onNodeWithTag(OrderTags.LIST).performTouchInput { down(center) }
        composeRule.onNodeWithTag(OrderTags.LIST).performTouchInput { up() }
        shadowOf(Looper.getMainLooper()).idle()
        session.known = true
        composeRule.runOnUiThread { handle?.onIdentityConfirmed(session.identity()) }
        idleUntil("身份确认后列表已加载") {
            pages.count() >= 1 && model()
                ?.controller
                ?.state
                ?.value
                ?.phase == OrderListPhase.CONTENT
        }
        assertFalse("被接管：不按旧记录恢复", model()!!.restored)
        assertNull("不再恢复到旧锚点", model()!!.pendingRestore)
        assertFalse(model()!!.restoreDeferred)
    }

    /**
     * 末轮窄修 R3-04：身份未知时建页 → 先确认账号、分区仍未知（渲染身份换了一张票据）→ 没有触摸、只有读屏式滚动（semantics 滚动）→ 分区确认为 E1：
     * 常驻滚动监听用的是最新票据，这次滚动记为接管，旧记录不再把列表拉回旧锚点。
     */
    @Test
    fun fuand0717_fixR3_04_aScrollWithoutTouchAfterTheAccountIsConfirmedIsStillATakeover() {
        val (_, args) = seed("ord-12")
        session.known = false
        launch(args)
        idleUntil("页面已建好") { model() != null }
        val first = model()!!.ticket.value
        partition = null
        session.known = true
        composeRule.runOnUiThread { handle?.onIdentityConfirmed(session.identity()) }
        idleUntil("账号确认后在线加载") {
            model()
                ?.controller
                ?.state
                ?.value
                ?.items
                ?.size == 20 && hasCard("ord-0")
        }
        assertNotEquals("渲染身份已换", first, model()!!.ticket.value)
        assertTrue("分区仍未知：恢复延后", model()!!.restoreDeferred)
        composeRule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(5)
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        partition = "login-1"
        composeRule.runOnUiThread { handle?.onIdentityConfirmed(session.identity()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(model()!!.restoreDeferred)
        assertFalse("被接管：不按旧记录恢复", model()!!.restored)
        assertNull("不再恢复到旧锚点", model()!!.pendingRestore)
    }

    @Test
    fun fuand0717_x18_aNewQueryDropsTheOldAnchorSynchronouslySoTheFirstPersistIsNewConditionsWithAnEmptyAnchor() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        pages.hold = held
        val (ref, args) = seed("ord-19")
        launch(args)
        idleUntil("恢复任务在等第二页") { pages.secondPages() == 1 && model()?.restoreEnd == OrderListViewModel.RESTORE_PAGING }
        val page = model()!!
        assertNotNull("前提：恢复任务还在", page.pendingRestore)
        composeRule.runOnUiThread { page.refresh() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("恢复任务同步作废", page.pendingRestore)
        assertEquals("锚点同步清零", 0, model()!!.savedState.get<Int>("orders.anchor_index"))
        assertNull("恢复记录里的锚点订单同步清空", store.read(ref)?.anchorOrderId)
        held.complete(Unit)
    }

    @Test
    fun fuand0717_x17_aPageFixedAtE1DoesNotWriteItsRecordUnderE2AfterASameAccountRelogin() {
        val (ref, args) = seed("ord-12")
        launch(args)
        idleUntil("恢复结束") { restoreFinished() }
        val page = model()!!
        // 同账号重新登录（令牌存储已是 E2），会话通知还没到：本页的迟到写入只按页面固定的 E1 绑定
        partition = "login-2"
        composeRule.runOnUiThread { page.onInput(page.ticket.value, "3741234567890123456") }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("E2 的页面恢复不出 E1 页面的迟到写入", store.read(ref, store.bindingFor("login-2")))
    }

    // ---------------------------------------------------------------- 第 1 轮修复

    /**
     * R1-02：恢复记录已按 A/E1 读成功、正在等版本检查；等待结束那一刻身份读取单次失败（快照未知）——不当成换号：条件、输入、锚点与恢复记录原样保留、零请求；
     * 身份恢复可读（AppOrders 判据相同时交来已确认的快照）后按原条件与锚点继续，回到锚点。
     */
    @Test
    fun fuand0717_r1_02_aSingleIdentityReadFailureAtTheEndOfTheRestoreWaitKeepsTheRecordAndRestoresLater() {
        val (ref, args) = seed("ord-12")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        launch(args, restoreGate = { gate.await() })
        idleUntil("页面已建好、在等版本检查") { model()?.restored == true }
        session.known = false
        composeRule.runOnUiThread { gate.complete(Unit) }
        idleUntil("等待已结束、延后决策") { model()?.restoreAwaitingIdentity == true }
        val page = model()!!
        assertEquals("零请求", 0, pages.count())
        assertEquals("锚点下标没被清", 12, page.savedState.get<Int>("orders.anchor_index"))
        assertEquals("引用没被清", ref, page.savedState.get<String>("orders.restore_ref"))
        assertNotNull("待恢复的锚点还在", page.pendingRestore)
        assertTrue("恢复记录还在", File(root, "$ref.json").exists())
        session.known = true
        composeRule.runOnUiThread { handle?.onIdentityConfirmed(session.identity()) }
        idleUntil("身份确认后按原条件与锚点恢复") { restoreFinished() }
        assertEquals(OrderListViewModel.RESTORE_LANDED, model()?.restoreEnd)
        assertTopAligned("ord-12")
    }

    /** R1-06：等翻页（第二页挂住）时手指按下、没有移动——即算用户接管：恢复取消；第二页到了也不再定位。 */
    @Test
    fun fuand0717_r1_06_aTouchWithoutMovingDuringThePageWaitCancelsTheRestore() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        pages.hold = held
        val (_, args) = seed("ord-19")
        launch(args)
        idleUntil("恢复任务在等第二页") { pages.secondPages() == 1 && model()?.pendingRestore != null }
        composeRule.onNodeWithTag(OrderTags.LIST).performTouchInput { down(center) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("按下即取消", model()!!.pendingRestore)
        assertEquals(OrderListViewModel.RESTORE_TAKEN_OVER, model()?.restoreEnd)
        composeRule.onNodeWithTag(OrderTags.LIST).performTouchInput { up() }
        val before = listTop()
        composeRule.runOnUiThread { held.complete(Unit) }
        idleUntil("第二页已到") {
            hasCard("ord-20") || model()
                ?.controller
                ?.state
                ?.value
                ?.items
                ?.size == 40
        }
        assertEquals("第二页到了也不再定位", OrderListViewModel.RESTORE_TAKEN_OVER, model()?.restoreEnd)
        assertEquals("列表停在用户手里的位置", before, listTop())
    }

    /** R1-06：等翻页时（网络等待期）用户滚离再滚回原位置——接管已记下，回到原位置也不恢复；第二页到了不再定位。 */
    @Test
    fun fuand0717_r1_06_scrollingAwayAndBackDuringThePageWaitStillCancelsTheRestore() {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        pages.hold = held
        val (_, args) = seed("ord-19")
        launch(args)
        idleUntil("恢复任务在等第二页") { pages.secondPages() == 1 && model()?.pendingRestore != null }
        val before = listTop()
        composeRule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(3)
        composeRule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(before)
        shadowOf(Looper.getMainLooper()).idle()
        val back = listTop()
        assertTrue("回到了原位置附近（$before → $back）", kotlin.math.abs(back - before) <= 1)
        assertNull("滚离即取消，回到原位置也不恢复", model()!!.pendingRestore)
        composeRule.runOnUiThread { held.complete(Unit) }
        idleUntil("第二页已到") {
            model()
                ?.controller
                ?.state
                ?.value
                ?.items
                ?.size == 40
        }
        assertEquals(OrderListViewModel.RESTORE_TAKEN_OVER, model()?.restoreEnd)
        assertEquals("第二页到了也不再定位（目标 ord-19 没被顶到顶部）", back, listTop())
    }

    /** 列表此刻顶部可见项（下标）。 */
    private fun listTop(): Int =
        (0 until PAGE_TOTAL)
            .mapNotNull { n ->
                composeRule
                    .onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-$n")
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.let { n to it.boundsInRoot.top }
            }.minByOrNull { it.second }
            ?.first ?: -1

    /** 带保存状态的页面持有者（进程重建后系统交回的那份）。 */
    private class Owner(
        private val args: Bundle,
    ) : ViewModelStoreOwner,
        SavedStateRegistryOwner,
        HasDefaultViewModelProviderFactory {
        private val registry = LifecycleRegistry.createUnsafe(this)
        private val controller = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory = ViewModelProvider.NewInstanceFactory()
        override val defaultViewModelCreationExtras: CreationExtras
            get() =
                MutableCreationExtras().apply {
                    set(SAVED_STATE_REGISTRY_OWNER_KEY, this@Owner)
                    set(VIEW_MODEL_STORE_OWNER_KEY, this@Owner)
                    set(DEFAULT_ARGS_KEY, args)
                }

        init {
            controller.performAttach()
            controller.performRestore(null)
            enableSavedStateHandles()
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private companion object {
        const val USER = "user-a"
        const val PAGE_TOTAL = 40
        const val CEILING_NANOS = 30_000_000_000L
    }
}
