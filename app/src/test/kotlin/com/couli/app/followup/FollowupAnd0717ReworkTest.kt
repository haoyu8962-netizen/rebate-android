@file:Suppress("MagicNumber")

package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.orders.AppOrders
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.wallet.AppWallet
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.network.SessionIdentity
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderRestoreRecord
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletStaleCache
import com.couli.core.wallet.WalletSummary
import com.couli.feature.orders.OrderDetailController
import com.couli.feature.orders.OrderDetailPhase
import com.couli.feature.orders.OrderDetailViewModel
import com.couli.feature.orders.OrderListController
import com.couli.feature.orders.OrderListPhase
import com.couli.feature.orders.OrderListViewModel
import com.couli.feature.wallet.FormOwner
import com.couli.feature.wallet.LaborAgreementViewModel
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.LaborSnapshot
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.PayoutAccountViewModel
import com.couli.feature.wallet.PayoutSnapshot
import com.couli.feature.wallet.RealNameViewModel
import com.couli.feature.wallet.RecordsSnapshot
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletController
import com.couli.feature.wallet.WalletSnapshot
import com.couli.feature.wallet.WalletViewModel
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawRecordsController
import com.couli.feature.wallet.WithdrawRecordsViewModel
import com.couli.feature.wallet.WithdrawSnapshot
import com.couli.feature.wallet.WithdrawViewModel
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0717 第 3 轮整体重理（第 2 轮评审 R2-01..R2-06）：全部经生产入口（AppOrders / AppWallet 的 listConfig、detailConfig、config 与
 * onSessionChanged；页面用真实 ViewModel，构造即按生产接线自己加载，测试不手动 load）——
 * - 一份快照成功之后，相邻的单次读取失败不让页面作废、不清恢复条件（R2-01）；
 * - 持久化世代暂时读不出、之后恢复：分区第一次确认时完成尚未被接管的恢复（R2-02）；
 * - 未知期建的详情 / 提现 / 收款账号 / 实名 / 劳务协议 / 提现记录 / 钱包页：身份确认（判据相同）后由 App 下发同一份快照接管并补取，可编辑；禁用期保留、
 *   不重放提交（R2-03、R2-04）；
 * - 恢复决策延后期间用户切 Tab、搜索、触摸都算接管，身份确认后不再用旧记录覆盖（R2-05）；
 * - 换代时页面按 App 下发的那一份快照重绑，不另读（之后的读取失败不影响）；在途请求回来时本进程登录世代已变、身份还没确认：不停在加载中。
 * 异步一律在主线程 idle 之后断言（按条件结算，不按固定时长等待）；负向断言都在结算之后。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("LargeClass", "TooManyFunctions")
class FollowupAnd0717ReworkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base: File = Files.createTempDirectory("fuand0717-rework").toFile()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 身份快照的来源（App 接 ApiClient.sessionIdentity 的那一处）：可设未知、改账号 / 世代；[failReads] 里的读取序号（从 1 起）给出未知。 */
    private class Identity(
        @Volatile var value: SessionIdentity,
    ) {
        @Volatile var reads = 0
        val failReads = mutableSetOf<Int>()

        fun read(): SessionIdentity {
            reads += 1
            return if (reads in failReads) SessionIdentity.unknown(value.loginEpoch) else value
        }

        /** 接下来的第 [n] 次读取失败（1 = 下一次）。 */
        fun failNext(vararg n: Int) {
            n.forEach { failReads += reads + it }
        }
    }

    private fun known(
        account: String? = USER,
        partition: String? = E1,
        epoch: Long = 1L,
    ) = SessionIdentity(known = true, account = account, loginEpoch = epoch, partition = partition)

    private fun routing(update: UpdateCheck = UpdateCheck.OK): AppRouting {
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        return AppRouting(context, environment, initialUpdate = update).also {
            it.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
    }

    // ---------------------------------------------------------------- 订单

    /** 订单接口：列表每次回一页（[held] 非空时挂住下一次列表请求），详情回一份；记录请求。 */
    private class OrdersApi : PageApi {
        val lists = mutableListOf<ApiCall>()
        val details = mutableListOf<ApiCall>()

        @Volatile var held: CompletableDeferred<Unit>? = null

        /** 非空时下一次详情请求先挂在它上面。 */
        @Volatile var heldDetail: CompletableDeferred<Unit>? = null

        override suspend fun send(call: ApiCall): ApiResult =
            when (call.operationId) {
                OrderListController.LIST_OPERATION -> {
                    lists += call
                    held?.let {
                        held = null
                        it.await()
                    }
                    ok(LIST_PAGE)
                }

                OrderDetailController.GET_OPERATION -> {
                    details += call
                    heldDetail?.let {
                        heldDetail = null
                        it.await()
                    }
                    ok(DETAIL)
                }

                else -> {
                    ok("""{"items":[]}""")
                }
            }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    /** 恢复记录（与 AppModule 同一种存储：接令牌存储里的持久化登录世代）。 */
    private var storePartition: String? = E1
    private val restore = OrderRestoreStore(File(base, "restore")).apply { bindLoginEpoch { storePartition } }

    /** 预存 A/E1 的恢复记录（搜索词、输入、锚点），返回放了引用的系统保存状态。 */
    private fun savedRecord(): SavedStateHandle {
        val ref = restore.newRef()
        val record = OrderRestoreRecord(OrderStaleCache.Store.hash(USER), q = SAVED_Q, input = SAVED_Q, anchorOrderId = ORDER)
        assertTrue("前提：A/E1 的恢复记录写入", restore.write(ref, restore.epoch, record, E1))
        return SavedStateHandle(
            mapOf(
                "orders.scope" to OrderQuery.SELF,
                "orders.group" to OrderQuery.ALL,
                "orders.anchor_index" to 3,
                "orders.anchor_offset" to 0,
                "orders.restore_ref" to ref,
            ),
        )
    }

    private fun appOrders(
        identity: Identity,
        api: OrdersApi,
        routing: AppRouting = routing(),
    ) = AppOrders(
        api = api,
        store = null,
        routing = routing,
        sessionEpoch = { identity.value.loginEpoch },
        account = { identity.value.account },
        writer = { true },
        permissions = null,
        notificationsEnabled = { false },
        forceUpdate = {},
        cache = null,
        mask = null,
        restoreStore = restore,
        identity = identity::read,
    )

    private fun listPage(
        orders: AppOrders,
        saved: SavedStateHandle = SavedStateHandle(),
    ) = OrderListViewModel(orders.listConfig(RouteDestination("OrderList", "{}", false)), saved)

    private fun OrdersApi.lastQuery(): String = lists.last().query.orEmpty()

    /**
     * R2-01：进程恢复的等待结束时一份完整快照确认 A/E1，紧接着的下一次身份读取单次失败——按保存的条件打开（不作废、不清条件 / 输入 / 锚点），恢复记录仍在。
     */
    @Test
    fun rework_r2_01_aFailedReadRightAfterTheRestoreSnapshotKeepsTheSavedQueryAndAnchor() {
        val identity = Identity(known())
        val api = OrdersApi()
        val routing = routing(UpdateCheck.CHECKING)
        val orders = appOrders(identity, api, routing)
        orders.onSessionChanged()
        val page = listPage(orders, savedRecord())
        idle()
        assertTrue("等待版本检查期间零请求", api.lists.isEmpty())
        // 等待结束：快照（下一次读取）成功，紧接着的一次读取失败
        identity.failNext(2)
        routing.update.value = UpdateCheck.OK
        idle()
        assertEquals("按保存的条件打开了一次", 1, api.lists.size)
        assertTrue("请求带保存的搜索词", api.lastQuery().contains("q=$SAVED_Q"))
        assertEquals(SAVED_Q, page.controller.state.value.conditions.q)
        assertEquals("输入框保留", SAVED_Q, page.input.value)
        assertEquals("锚点恢复仍在进行", ORDER, page.pendingRestore)
        assertEquals(OrderListPhase.CONTENT, page.controller.state.value.phase)
    }

    /** R2-01（入口）：已加载的 A/E1 页，用户提交搜索时相邻两次身份读取都失败——不作废列表、不清输入，照常按用户的词发请求。 */
    @Test
    fun rework_r2_01_failedReadsAtAUserEntryNeverInvalidateTheList() {
        val identity = Identity(known())
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders)
        idle()
        val ticket = page.ticket.value
        page.onInput(ticket, "abc")
        identity.failNext(1, 2, 3)
        assertTrue(page.submit(ticket))
        idle()
        assertTrue("按用户的词发请求", api.lastQuery().contains("q=abc"))
        assertEquals("输入框没被清", "abc", page.input.value)
        assertEquals("渲染身份没换（没有作废）", ticket, page.ticket.value)
    }

    /**
     * R2-02：账号可读、持久化世代暂时读不出（在线已确认、分区未知）时建页：在线照常加载、恢复延后；之后世代恢复为 E1，经生产入口
     * [AppOrders.onSessionChanged]（判据相同）完成尚未被接管的恢复——按保存的条件重开、输入与锚点恢复。
     */
    @Test
    fun rework_r2_02_aPartitionThatWasBrieflyUnreadableIsConfirmedAndTheRestoreCompletes() {
        val identity = Identity(known(partition = null))
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders, savedRecord())
        idle()
        assertTrue("分区未知：恢复延后", page.restoreDeferred)
        assertEquals("在线照常加载默认条件", 1, api.lists.size)
        assertFalse(api.lastQuery().contains("q="))
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertFalse("分区确认后不再延后", page.restoreDeferred)
        assertEquals(E1, page.controller.ownerLoginEpoch)
        assertTrue("按保存的条件重开", api.lastQuery().contains("q=$SAVED_Q"))
        assertEquals(SAVED_Q, page.input.value)
        assertEquals(ORDER, page.pendingRestore)
    }

    /** R2-05：身份未知（恢复延后）期间用户切到分享子 Tab；身份确认后不再用旧记录覆盖（仍是分享、搜索框空、没有锚点恢复），并按分享补取。 */
    @Test
    fun rework_r2_05_switchingTabsWhileDeferredIsATakeoverThatTheConfirmedIdentityKeeps() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders, savedRecord())
        idle()
        assertTrue(page.restoreDeferred)
        page.selectScope(page.ticket.value, OrderQuery.SHARE)
        idle()
        assertEquals(OrderQuery.SHARE, page.controller.state.value.scope)
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertEquals("用户的子 Tab 保留", OrderQuery.SHARE, page.controller.state.value.scope)
        assertNull("旧搜索词没回来", page.controller.state.value.conditions.q)
        assertEquals("", page.input.value)
        assertNull("不再恢复到旧锚点", page.pendingRestore)
        assertTrue("按用户的条件补取", api.lastQuery().contains("scope=share"))
        assertEquals(OrderListPhase.CONTENT, page.controller.state.value.phase)
    }

    /** R2-05：延后期间用户输入并提交搜索：身份确认后保留用户的词。 */
    @Test
    fun rework_r2_05_searchingWhileDeferredIsKeptAfterTheIdentityIsConfirmed() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders, savedRecord())
        idle()
        page.onInput(page.ticket.value, "mine")
        page.submit(page.ticket.value)
        idle()
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertEquals("mine", page.controller.state.value.conditions.q)
        assertEquals("mine", page.input.value)
        assertNull(page.pendingRestore)
        assertTrue(api.lastQuery().contains("q=mine"))
    }

    /** R2-05：延后期间只是触摸了列表（没有换条件）：同样算接管——身份确认后不按旧记录跳回旧条件与锚点。对照：没触摸的页面恢复旧条件（见 R2-02 用例）。 */
    @Test
    fun rework_r2_05_aTouchWhileDeferredIsATakeover() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders, savedRecord())
        idle()
        page.onUserTouch(page.ticket.value)
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertNull("旧搜索词没回来", page.controller.state.value.conditions.q)
        assertEquals("", page.input.value)
        assertNull(page.pendingRestore)
        assertFalse(page.restoreDeferred)
        assertEquals(OrderListPhase.CONTENT, page.controller.state.value.phase)
    }

    /** 对照（R2-05 / 补充三 ②）：延后期间没有任何用户操作，身份确认后照常恢复旧条件、输入与锚点。 */
    @Test
    fun rework_r2_05_withoutATakeoverTheConfirmedIdentityRestoresTheRecord() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders, savedRecord())
        idle()
        assertEquals("未知期：可重试的错误态（不是加载中）", OrderListPhase.ERROR, page.controller.state.value.phase)
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertEquals(SAVED_Q, page.controller.state.value.conditions.q)
        assertEquals(SAVED_Q, page.input.value)
        assertEquals(ORDER, page.pendingRestore)
        assertTrue(api.lastQuery().contains("q=$SAVED_Q"))
    }

    /**
     * 在途的列表请求回来时本进程登录世代已变（重新登录）而身份暂时未知：不停在加载中（进可重试的错误态）；身份确认后经生产入口按换代重置并取第一页。
     */
    @Test
    fun rework_aListRequestLandingAfterTheEpochMovedWhileUnknownDoesNotStayLoading() {
        val identity = Identity(known())
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders)
        idle()
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        page.refresh()
        idle()
        assertTrue("刷新在途", page.controller.state.value.refreshing)
        identity.value = SessionIdentity.unknown(2L)
        orders.onSessionChanged()
        gate.complete(Unit)
        idle()
        assertEquals("不停在加载中", OrderListPhase.ERROR, page.controller.state.value.phase)
        assertFalse(page.controller.state.value.refreshing)
        assertTrue(page.controller.identityAborted)
        val before = api.lists.size
        identity.value = known(partition = "login-2", epoch = 2L)
        orders.onSessionChanged()
        idle()
        assertEquals("确认换代：按新会话取第一页", before + 1, api.lists.size)
        assertEquals(OrderListPhase.CONTENT, page.controller.state.value.phase)
        assertEquals(2L, page.controller.ownerEpoch)
    }

    /** R2-03：身份未知时打开详情（生产配置，ViewModel 构造即加载）：可重试的错误态、零请求；身份确认后经 AppOrders 生产入口接管并加载，不手动 load。 */
    @Test
    fun rework_r2_03_aDetailBuiltWhileUnknownLoadsAfterTheProductionPush() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val detail = detailPage(orders, SavedStateHandle())
        idle()
        assertEquals("不是加载中", OrderDetailPhase.ERROR, detail.controller.state.value.phase)
        assertTrue(api.details.isEmpty())
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertEquals(1, api.details.size)
        assertEquals(OrderDetailPhase.CONTENT, detail.controller.state.value.phase)
        assertEquals(
            USER,
            detail.controller.state.value.owner
                ?.account,
        )
    }

    /** R2-03（恢复）：进程恢复的详情在等待期间身份未知、等待结束仍未知：可重试的错误态；身份确认后经生产入口加载。 */
    @Test
    fun rework_r2_03_aRestoredDetailWaitingWhileUnknownLoadsAfterTheProductionPush() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = OrdersApi()
        val routing = routing(UpdateCheck.CHECKING)
        val orders = appOrders(identity, api, routing)
        orders.onSessionChanged()
        val detail = detailPage(orders, SavedStateHandle(mapOf("order_detail.started" to true)))
        idle()
        routing.update.value = UpdateCheck.OK
        idle()
        assertEquals(OrderDetailPhase.ERROR, detail.controller.state.value.phase)
        assertTrue(api.details.isEmpty())
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertEquals(OrderDetailPhase.CONTENT, detail.controller.state.value.phase)
        assertEquals(1, api.details.size)
    }

    /** R2-03（相邻读取）：已知身份打开的恢复详情，等待结束时的一次身份读取失败：照常按固定身份请求（不另读判「是不是当前」，不停在加载中）。 */
    @Test
    fun rework_r2_03_aFailedReadWhenTheDetailRestoreWaitEndsStillLoads() {
        val identity = Identity(known())
        val api = OrdersApi()
        val routing = routing(UpdateCheck.CHECKING)
        val orders = appOrders(identity, api, routing)
        orders.onSessionChanged()
        val detail = detailPage(orders, SavedStateHandle(mapOf("order_detail.started" to true)))
        idle()
        identity.failNext(1, 2)
        routing.update.value = UpdateCheck.OK
        idle()
        assertEquals(1, api.details.size)
        assertEquals(OrderDetailPhase.CONTENT, detail.controller.state.value.phase)
    }

    private fun detailPage(
        orders: AppOrders,
        saved: SavedStateHandle,
    ): OrderDetailViewModel {
        val config =
            orders.detailConfig(RouteDestination("OrderDetail", """{"order_id":"$ORDER"}""", false), "entry-detail", onBack = {})!!
        return OrderDetailViewModel(config, saved)
    }

    // ---------------------------------------------------------------- 钱包类页面

    private val summary = WalletSummary(1234, 1234, 0, 0, 5678, 2222, 3456, 456, "2026-10", false, 500, null, true)

    private class WalletsApi(
        private val summary: WalletSummary,
    ) : WalletApi {
        val calls = mutableListOf<String>()

        /** 非空时读请求先挂在它上面（换代窗口里的在途读取）。 */
        @Volatile var held: CompletableDeferred<Unit>? = null

        /** 非空时提现提交先挂在它上面，放行后回成功（F-X-22）。 */
        @Volatile var submitHeld: CompletableDeferred<Unit>? = null

        override fun pendingFor(operationId: String): PendingKeyRecord? = null

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = CompletableDeferred<ApiResult>().await()

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = CompletableDeferred<ApiResult>().await()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call.operationId
            held?.takeIf { call.operationId != WithdrawController.OPERATION }?.await()
            return when (call.operationId) {
                WithdrawController.RULES_OPERATION -> {
                    ok(RULES_OK)
                }

                PayoutAccountController.GET_OPERATION -> {
                    ok("{}")
                }

                WalletController.OPERATION -> {
                    ApiResult.Success(WalletJson.encode(summary), "trace-000123", null)
                }

                WithdrawController.OPERATION -> {
                    submitHeld?.let {
                        it.await()
                        ok("""{"withdrawal_id":"W1"}""")
                    } ?: CompletableDeferred<ApiResult>().await()
                }

                WithdrawRecordsController.LIST_OPERATION -> {
                    ok("""{"items":[],"next_cursor":null}""")
                }

                else -> {
                    CompletableDeferred<ApiResult>().await()
                }
            }
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private object Labor : LaborBackend {
        override suspend fun current(): ApiResult = ok("""{"version":"v1","text":"协议正文","text_sha256":"abc","need_sign":true}""")

        override suspend fun sign(
            version: String,
            textSha256: String,
        ): ApiResult = CompletableDeferred<ApiResult>().await()
    }

    private fun appWallet(
        identity: Identity,
        api: WalletsApi,
        accountAlone: () -> String? = { identity.value.account },
        partitionTracked: Boolean = false,
    ) = AppWallet(
        api = api,
        store = null,
        routing = routing(),
        sessionEpoch = { identity.value.loginEpoch },
        account = accountAlone,
        writer = { true },
        forceUpdate = {},
        cache = null,
        mask = null,
        realname = null,
        labor = Labor,
        stepUpPhone = { "13800005678" },
        partitionTracked = partitionTracked,
        identity = identity::read,
    )

    private fun AppWallet.page(
        route: String,
        entry: String,
    ) = config(RouteDestination(route, "{}", false), entry, onBack = {})

    /**
     * R2-04：身份未知时建的提现页（生产配置，构造即加载）：可重试的错误态、金额不可编辑；身份确认（判据相同）经 AppWallet 生产入口接管表单身份并补取 rules，
     * 金额可编辑；提交禁用期保留；不重放提交。本机存着属于这份身份的金额草稿时按它恢复。
     */
    @Test
    fun rework_r2_04_aWithdrawFormBuiltWhileUnknownIsAdoptedAndEditableAfterThePush() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api)
        wallet.onSessionChanged()
        val draft = WalletStaleCache.Store.hash(USER + "\u0000login_epoch:" + E1)
        val withdraw =
            WithdrawViewModel(
                wallet.page("Withdraw", "entry-withdraw"),
                SavedStateHandle(
                    mapOf(
                        "withdraw.amount" to "12",
                        "withdraw.owner" to draft,
                    ),
                ),
            )
        idle()
        assertEquals("不是加载中", WithdrawSnapshot.ERROR, withdraw.controller.state.value.phase)
        assertFalse("未知期不读 rules", WithdrawController.RULES_OPERATION in api.calls)
        assertFalse("未知期不可编辑", withdraw.controller.onAmountChange("5"))
        wallet.gates.withdraw.start(60_000)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals(WithdrawSnapshot.CONTENT, withdraw.controller.state.value.phase)
        assertEquals(FormOwner(USER, 1L), withdraw.controller.flow.owner)
        assertEquals("本机草稿按已确认身份恢复", "12", withdraw.controller.state.value.amountText)
        assertTrue("可编辑", withdraw.controller.onAmountChange("123"))
        assertEquals("123", withdraw.controller.state.value.amountText)
        assertTrue("提交禁用期保留", wallet.gates.withdraw.active)
        assertFalse("不重放提交", WithdrawController.OPERATION in api.calls)
    }

    /** R2-04：未知期建的收款账号页：恢复后经生产入口补取并可编辑（输入写进表单）。 */
    @Test
    fun rework_r2_04_aPayoutFormBuiltWhileUnknownIsAdoptedAndEditableAfterThePush() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api)
        wallet.onSessionChanged()
        val payout = PayoutAccountViewModel(wallet.page("PayoutAccount", "entry-payout"))
        idle()
        assertEquals(PayoutSnapshot.ERROR, payout.controller.state.value.phase)
        payout.controller.onLogonChange("early@example.com")
        assertEquals("未知期不写入", "", payout.controller.state.value.form.alipayLogonId)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals(PayoutSnapshot.CONTENT, payout.controller.state.value.phase)
        assertTrue(payout.controller.state.value.editing)
        payout.controller.onLogonChange("a@example.com")
        assertEquals("a@example.com", payout.controller.state.value.form.alipayLogonId)
        assertFalse("不重放保存", PayoutAccountController.OPERATION in api.calls)
    }

    /** 未知期建的实名、劳务协议、提现记录、钱包页：同一套入口——身份确认后实名可输入、劳务协议与提现记录读出内容、钱包出摘要（都不手动 load）。 */
    @Test
    fun rework_otherFundPagesBuiltWhileUnknownRecoverThroughTheSamePush() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api)
        wallet.onSessionChanged()
        val realname = RealNameViewModel(wallet.page("RealName", "entry-realname"))
        val labor = LaborAgreementViewModel(wallet.page("LaborAgreement", "entry-labor"))
        val records = WithdrawRecordsViewModel(wallet.page("WithdrawRecords", "entry-records"))
        val summaryPage = WalletViewModel(wallet.page("Wallet", "entry-wallet"))
        idle()
        realname.controller.onNameChange("甲某")
        assertEquals("", realname.controller.state.value.name)
        assertEquals("不是加载中", LaborSnapshot.ERROR, labor.controller.state.value.phase)
        assertEquals("不是加载中", RecordsSnapshot.ERROR, records.controller.state.value.phase)
        assertNull(summaryPage.controller.state.value.summary)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        realname.controller.onNameChange("甲某")
        assertEquals("甲某", realname.controller.state.value.name)
        assertEquals(LaborSnapshot.CONTENT, labor.controller.state.value.phase)
        assertNotEquals(RecordsSnapshot.ERROR, records.controller.state.value.phase)
        assertNotEquals(RecordsSnapshot.LOADING, records.controller.state.value.phase)
        assertEquals(summary, summaryPage.controller.state.value.summary)
    }

    /** 换代：页面按 App 下发的那一份快照重绑（不另读）——快照之后的读取全部失败，提现表单仍按新世代建立并加载。 */
    @Test
    fun rework_aSessionChangeRebindsPagesWithThePushedSnapshotWithoutAnotherRead() {
        val identity = Identity(known())
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api)
        wallet.onSessionChanged()
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), SavedStateHandle())
        idle()
        assertEquals(WithdrawSnapshot.CONTENT, withdraw.controller.state.value.phase)
        identity.value = known(partition = "login-2", epoch = 2L)
        // App 的对账读这一次（第 1 次），之后的读取全部失败
        identity.failNext(2, 3, 4, 5, 6)
        wallet.onSessionChanged()
        idle()
        assertEquals(FormOwner(USER, 2L), withdraw.controller.flow.owner)
        assertEquals(WithdrawSnapshot.CONTENT, withdraw.controller.state.value.phase)
        assertFalse(withdraw.controller.identityAborted)
    }

    /** R2-01（复制入口）：已加载的 A/E1 页，复制时身份读取单次失败——只拦下这次写入，不作废列表、不换渲染身份。 */
    @Test
    fun rework_r2_01_aCopyDuringAFailedReadIsRefusedWithoutInvalidating() {
        val identity = Identity(known())
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val page = listPage(orders)
        idle()
        val ticket = page.ticket.value
        val state = page.controller.state.value
        val row = state.items.single()
        identity.failNext(1, 2, 3)
        assertFalse("身份读不出：不写剪贴板", page.copy(ticket, row.orderId, row.orderNo!!, state.round, state.identity))
        idle()
        assertEquals("没有作废", ticket, page.ticket.value)
        assertEquals(1, api.lists.size)
        assertEquals(OrderListPhase.CONTENT, page.controller.state.value.phase)
    }

    /**
     * 钱包类页面的读取在途时本进程登录世代已变（重新登录）而身份暂时未知：回来的结果不显示、页面不停在加载中（钱包、提现记录、收款账号、提现页头都进可重试的
     * 错误态，提现记录之后的刷新仍受理）；身份确认后经 AppWallet 生产入口按换代重建并加载。
     */
    @Test
    fun rework_walletReadsLandingAfterTheEpochMovedWhileUnknownDoNotStayLoading() {
        val identity = Identity(known())
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api)
        wallet.onSessionChanged()
        val gate = CompletableDeferred<Unit>()
        api.held = gate
        val summaryPage = WalletViewModel(wallet.page("Wallet", "entry-wallet"))
        val records = WithdrawRecordsViewModel(wallet.page("WithdrawRecords", "entry-records"))
        val payout = PayoutAccountViewModel(wallet.page("PayoutAccount", "entry-payout"))
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), SavedStateHandle())
        idle()
        assertEquals(WalletController.OPERATION, api.calls.first())
        identity.value = SessionIdentity.unknown(2L)
        wallet.onSessionChanged()
        api.held = null
        gate.complete(Unit)
        idle()
        assertEquals("钱包不停在加载中", WalletSnapshot.ERROR, summaryPage.controller.state.value.phase)
        assertEquals("提现记录不停在加载中", RecordsSnapshot.ERROR, records.controller.state.value.phase)
        assertFalse("提现记录的刷新在途已结束", records.controller.refreshPending)
        assertEquals("收款账号不停在加载中", PayoutSnapshot.ERROR, payout.controller.state.value.phase)
        assertEquals("提现页头不停在加载中", WithdrawSnapshot.ERROR, withdraw.controller.state.value.phase)
        identity.value = known(partition = "login-2", epoch = 2L)
        wallet.onSessionChanged()
        idle()
        assertEquals(summary, summaryPage.controller.state.value.summary)
        assertNotEquals(RecordsSnapshot.ERROR, records.controller.state.value.phase)
        assertEquals(PayoutSnapshot.CONTENT, payout.controller.state.value.phase)
        assertEquals(WithdrawSnapshot.CONTENT, withdraw.controller.state.value.phase)
    }

    /** 详情的在途请求回来时本进程登录世代已变而身份未知：不停在加载中；身份确认后经 AppOrders 按换代重取。 */
    @Test
    fun rework_aDetailRequestLandingAfterTheEpochMovedWhileUnknownDoesNotStayLoading() {
        val identity = Identity(known())
        val api = OrdersApi()
        val orders = appOrders(identity, api)
        orders.onSessionChanged()
        val gate = CompletableDeferred<Unit>()
        api.heldDetail = gate
        val detail = detailPage(orders, SavedStateHandle())
        idle()
        assertEquals(OrderDetailPhase.LOADING, detail.controller.state.value.phase)
        identity.value = SessionIdentity.unknown(2L)
        orders.onSessionChanged()
        gate.complete(Unit)
        idle()
        assertEquals("不停在加载中", OrderDetailPhase.ERROR, detail.controller.state.value.phase)
        identity.value = known(partition = "login-2", epoch = 2L)
        orders.onSessionChanged()
        idle()
        assertEquals(OrderDetailPhase.CONTENT, detail.controller.state.value.phase)
        assertEquals(2, api.details.size)
    }

    /**
     * 写入核对只读一份快照（FU-AND-0717 第 3 轮）：钱包环境的单独账号读取一调用就失败——提交、表单身份核对都不碰它，按快照照常放行。
     */
    @Test
    fun rework_walletWriteChecksUseOneSnapshotAndNeverReadTheAccountAlone() {
        val identity = Identity(known())
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, accountAlone = { error("不得单独读账号") })
        wallet.onSessionChanged()
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), SavedStateHandle())
        idle()
        val owner = withdraw.controller.flow.owner
        assertTrue(owner.current(wallet.env))
        withdraw.controller.flow.submit("""{"amount_fen":1000}""", """{"amount_fen":1000}""", owner)
        idle()
        assertTrue("按快照放行，提交发出", WithdrawController.OPERATION in api.calls)
    }

    // ---------------------------------------------------------------- 末轮窄修（第 3 轮评审 R3-01..R3-04）

    private fun withdrawDraft(
        amount: String,
        owner: String,
    ) = SavedStateHandle(mapOf("withdraw.amount" to amount, "withdraw.owner" to owner))

    private val e1Draft = WalletStaleCache.Store.hash(USER + "\u0000login_epoch:" + E1)

    /** R3-01：恢复中的详情（A、分区暂时读不出）在等版本检查时分区第一次确认为 E1（经 AppOrders 生产入口）：等待结束照常加载，不停在加载中。 */
    @Test
    fun fixR3_01_aPartitionConfirmedWhileTheDetailRestoreWaitsStillLoads() {
        val identity = Identity(known(partition = null))
        val api = OrdersApi()
        val routing = routing(UpdateCheck.CHECKING)
        val orders = appOrders(identity, api, routing)
        orders.onSessionChanged()
        val detail = detailPage(orders, SavedStateHandle(mapOf("order_detail.started" to true)))
        idle()
        identity.value = known()
        orders.onSessionChanged()
        idle()
        assertTrue("等待期间不发", api.details.isEmpty())
        routing.update.value = UpdateCheck.OK
        idle()
        assertEquals(1, api.details.size)
        assertEquals(OrderDetailPhase.CONTENT, detail.controller.state.value.phase)
        assertEquals(
            E1,
            detail.controller.state.value.owner
                ?.loginEpoch,
        )
    }

    /**
     * R3-02：令牌存储带持久化世代、分区暂时读不出（A/未知）时：只按账号归属的旧草稿不恢复、用户新填的金额不按账号写；分区确认为 E1 后用户的金额保留并按 E1
     * 保存（经 AppWallet 生产入口）。
     */
    @Test
    fun fixR3_02_anUnknownPartitionNeitherReadsNorWritesTheDraftAndTheConfirmationBindsIt() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val saved = withdrawDraft("88", WalletStaleCache.Store.hash(USER))
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), saved)
        idle()
        assertEquals("分区未知：按账号归属的旧草稿不恢复", "", withdraw.controller.state.value.amountText)
        withdraw.onAmountChange("99")
        assertEquals("99", withdraw.controller.state.value.amountText)
        assertEquals("分区未知：不按账号写", "88", saved.get<String>("withdraw.amount"))
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("用户的新输入保留", "99", withdraw.controller.state.value.amountText)
        assertEquals("99", saved.get<String>("withdraw.amount"))
        assertEquals("按 E1 归属保存", e1Draft, saved.get<String>("withdraw.owner"))
    }

    /** R3-02：分区未知时建的提现页，本机有 A/E1 的草稿：分区确认为 E1、用户没填时补恢复。 */
    @Test
    fun fixR3_02_theE1DraftIsRestoredOnceThePartitionIsConfirmed() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), withdrawDraft("66", e1Draft))
        idle()
        assertEquals("", withdraw.controller.state.value.amountText)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("66", withdraw.controller.state.value.amountText)
    }

    /** R3-03：构造时一份快照确认 A/E1，紧接着的安全复核单次读不出：草稿不当成没有，保留待恢复；之后身份确认（判据相同）补恢复。 */
    @Test
    fun fixR3_03_aFailedReadRightAfterTheDraftSnapshotKeepsTheDraftPendingUntilConfirmed() {
        val identity = Identity(known())
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        identity.failNext(2)
        val saved = withdrawDraft("66", e1Draft)
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), saved)
        idle()
        assertEquals("复核读不出：先不恢复", "", withdraw.controller.state.value.amountText)
        assertEquals("草稿原样保留", "66", saved.get<String>("withdraw.amount"))
        wallet.onSessionChanged()
        idle()
        assertEquals("确认后补恢复", "66", withdraw.controller.state.value.amountText)
    }

    // ---------------------------------------------------------------- 末轮窄修二（定向复核 RC-01 / RC-02）

    /** RC-01：分区未知时用户输入 99 又清空，分区确认为 E1 后金额保持空（不回填 E1 草稿 66），并按 E1 保存空值。 */
    @Test
    fun fixR3b_rc01_clearingTheAmountWhileThePartitionIsUnknownIsKeptAfterConfirmation() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val saved = withdrawDraft("66", e1Draft)
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), saved)
        idle()
        withdraw.onAmountChange("99")
        withdraw.onAmountChange("")
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("用户清空的金额保持空", "", withdraw.controller.state.value.amountText)
        assertEquals("按 E1 保存用户此刻的值（空）", "", saved.get<String>("withdraw.amount"))
        assertEquals(e1Draft, saved.get<String>("withdraw.owner"))
    }

    /** RC-01（快捷档位）：分区未知时点了快捷档位，分区确认后保留档位金额，不回填 E1 草稿。 */
    @Test
    fun fixR3b_rc01_aQuickAmountTappedWhileThePartitionIsUnknownIsKept() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), withdrawDraft("66", e1Draft))
        idle()
        val state = withdraw.controller.state.value
        withdraw.fillQuick(state.version, 500L, withdraw.controller.flow.owner)
        val filled = withdraw.controller.state.value.amountText
        assertNotEquals("", filled)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("档位金额保留", filled, withdraw.controller.state.value.amountText)
    }

    /** 末轮窄修三 RC-03：草稿待决期间点快捷档位，恰遇身份复核读不出被拒：不算用户操作，确认 E1 后仍恢复 66。 */
    @Test
    fun fixR3c_rc03_aQuickAmountRejectedByAnUnknownOwnerCheckStillRestoresTheDraft() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), withdrawDraft("66", e1Draft))
        idle()
        val state = withdraw.controller.state.value
        identity.failNext(1)
        withdraw.fillQuick(state.version, 500L, withdraw.controller.flow.owner)
        assertEquals("被拒：金额没填", "", withdraw.controller.state.value.amountText)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("确认后仍恢复原草稿", "66", withdraw.controller.state.value.amountText)
    }

    /** 末轮窄修三 RC-03：草稿待决期间旧画面（版本已过期）的【全部提现】被拒：不算用户操作，确认 E1 后仍恢复 66。 */
    @Test
    fun fixR3c_rc03_aStaleFillAllIsRejectedAndTheDraftIsStillRestored() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), withdrawDraft("66", e1Draft))
        idle()
        val stale = withdraw.controller.state.value.version - 1
        withdraw.fillAll(stale, withdraw.controller.flow.owner)
        assertEquals("被拒：金额没填", "", withdraw.controller.state.value.amountText)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("确认后仍恢复原草稿", "66", withdraw.controller.state.value.amountText)
    }

    /**
     * F-X-22（窄修三核对）：提现提交的成功响应在身份暂时读不出时到达——不显示成可重试错误、钱包照常收到失效通知重取余额；身份确认后经生产入口也不会再发，
     * 提现写请求总数为 1。
     */
    @Test
    fun fixR3c_fx22_aWithdrawSuccessArrivingWhileUnknownIsSettledOnceAndNeverResent() {
        val identity = Identity(known())
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val summaryPage = WalletViewModel(wallet.page("Wallet", "entry-wallet"))
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), SavedStateHandle())
        idle()
        withdraw.onAmountChange("10")
        val gate = CompletableDeferred<Unit>()
        api.submitHeld = gate
        withdraw.controller.submit(withdraw.controller.state.value.version, withdraw.controller.flow.owner)
        idle()
        assertEquals(1, api.calls.count { it == WithdrawController.OPERATION })
        val summariesBefore = api.calls.count { it == WalletController.OPERATION }
        identity.value = SessionIdentity.unknown(1L)
        gate.complete(Unit)
        idle()
        val flow = withdraw.controller.flow.state.value
        assertNull("成功不显示成错误", flow.error)
        assertTrue("钱包按失效通知重取余额", api.calls.count { it == WalletController.OPERATION } > summariesBefore)
        identity.value = known()
        wallet.onSessionChanged()
        idle()
        assertEquals("提现写请求总数为 1", 1, api.calls.count { it == WithdrawController.OPERATION })
        assertNull(withdraw.controller.flow.state.value.error)
        assertEquals(summary, summaryPage.controller.state.value.summary)
    }

    /** RC-02：分区确认的快照成功，紧接着恢复金额时的表单归属复核单次读不出：草稿不丢，下一次确认仍恢复 66。 */
    @Test
    fun fixR3b_rc02_aFailedOwnerCheckDuringTheLateRestoreKeepsTheDraftPending() {
        val identity = Identity(known(partition = null))
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        val saved = withdrawDraft("66", e1Draft)
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), saved)
        idle()
        identity.value = known()
        // App 的对账读这一次（第 1 次）成功，恢复金额时的归属复核（第 2 次）读不出
        identity.failNext(2)
        wallet.onSessionChanged()
        idle()
        assertEquals("这次没恢复", "", withdraw.controller.state.value.amountText)
        assertEquals("草稿原样保留", "66", saved.get<String>("withdraw.amount"))
        wallet.onSessionChanged()
        idle()
        assertEquals("再次确认后恢复", "66", withdraw.controller.state.value.amountText)
    }

    /** RC-02（构造时复核失败后的补恢复，同一路径）：两次相邻读取各失败一次，第三次确认后仍恢复 66。 */
    @Test
    fun fixR3b_rc02_theConstructionPathAlsoKeepsTheDraftAcrossAFailedLateOwnerCheck() {
        val identity = Identity(known())
        val api = WalletsApi(summary)
        val wallet = appWallet(identity, api, partitionTracked = true)
        wallet.onSessionChanged()
        identity.failNext(2)
        val saved = withdrawDraft("66", e1Draft)
        val withdraw = WithdrawViewModel(wallet.page("Withdraw", "entry-withdraw"), saved)
        idle()
        identity.failNext(2)
        wallet.onSessionChanged()
        idle()
        assertEquals("", withdraw.controller.state.value.amountText)
        assertEquals("66", saved.get<String>("withdraw.amount"))
        wallet.onSessionChanged()
        idle()
        assertEquals("66", withdraw.controller.state.value.amountText)
    }

    private companion object {
        const val USER = "user-a"
        const val E1 = "login-1"
        const val ORDER = "ord-1"
        const val SAVED_Q = "saved"
        const val ITEM =
            """{"order_id":"ord-1","platform":"taobao","title":"样例商品 A","image_url":null,"pay_amount_fen":4560,"quantity":2,""" +
                """"order_no":"3741234567890123456","display_status":"PAID","reason":null,"est_rebate_fen":234,""" +
                """"paid_at":"2026-10-08T09:05:00+08:00","is_other_product":false}"""
        const val LIST_PAGE = """{"items":[$ITEM],"next_cursor":null,"earliest_visible_date":null}"""
        val DETAIL = ITEM.dropLast(1) + ""","product_key":"tb:sampleA","timeline":[]}"""
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""

        fun ok(json: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(json), "trace-000123", null)
    }
}
