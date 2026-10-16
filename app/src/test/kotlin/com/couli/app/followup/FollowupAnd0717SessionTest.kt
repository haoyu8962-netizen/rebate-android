@file:Suppress("MagicNumber")

package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.SessionCacheReconciler
import com.couli.app.orders.AppOrders
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.home.HomeLkgScope
import com.couli.core.home.HomeLkgStore
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.network.SessionIdentity
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderListData
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderSummary
import com.couli.core.orders.PendingTrackService
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletStaleCache
import com.couli.core.wallet.WalletSummary
import com.couli.feature.login.PhoneBindingState
import com.couli.feature.orders.OrderListHandle
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletController
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WithdrawViewModel
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0717 F-X-17 / 补充、F-X-19 / 补充 / 补充二 / 补充三（订单 / 钱包 / 首页 LKG / phone_bound 的分区写入与会话对账；iOS FU-IOS-0712 R1-01、
 * R2-01、R3-01..03、复核一 RC-01..03，鸿蒙 FU-HM-0723 同类）：
 * - 写入归属来自请求 / 页面固定的「账号 + 持久化登录世代」：在最后一次身份核对之后、写盘之前同账号重新登录（E1 → E2），旧数据只进 E1 分区，E2（同一实例、
 *   重建实例）读不到，会话对账按 E2 的固定标签把它删掉；
 * - 会话对账走生产入口 [AppOrders.onSessionChanged] / [AppOrders.connectSession]：一次身份快照；身份暂时未知只延后（不删）、已登记的待清理照做；
 *   同账号 E1 → E2 而本进程计数不变也按固定标签删掉旧分区；判据相同提前返回前先把未知期间建的页换成这份已确认身份；对账没完成登记、之后重试。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("LargeClass", "TooManyFunctions")
class FollowupAnd0717SessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base: File = Files.createTempDirectory("fuand0717-session").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun item(id: String) =
        OrderSummary(id, "taobao", "样例商品", null, 4560, 1, "3741234567890123456", null, "PAID", null, 234, null, false)

    private val summary = WalletSummary(1234, 1234, 0, 0, 5678, 2222, 3456, 456, "2026-10", false, 500, null, true)

    /** 一次快照的来源：可设为未知、改账号 / 世代；[reads] 计数（补充三 ①：一次检查只读一次）。 */
    private class Identity(
        @Volatile var value: SessionIdentity,
    ) {
        @Volatile var reads = 0

        /** 第 n 次读取（从 1 起）给出未知（相邻读取间的单次失败）。 */
        @Volatile var failOnRead = -1

        fun read(): SessionIdentity {
            reads += 1
            return if (reads == failOnRead) SessionIdentity.unknown(value.loginEpoch) else value
        }
    }

    private fun known(
        account: String?,
        partition: String?,
        epoch: Long = 1L,
    ) = SessionIdentity(known = true, account = account, loginEpoch = epoch, partition = partition)

    /** 缓存目录之外的待清理标记：[clearFailures] 次 clear 失败（删除后清标记失败 → 对账没完成）。 */
    private class Flag(
        @Volatile var value: Boolean = false,
    ) : OrderStaleCache.PendingClearFlag,
        WalletStaleCache.PendingClearFlag {
        @Volatile var clearFailures = 0

        override fun isSet() = value

        override fun set(): Boolean {
            value = true
            return true
        }

        override fun clear(): Boolean {
            if (clearFailures > 0) {
                clearFailures -= 1
                return false
            }
            value = false
            return true
        }
    }

    private fun orderCache(flag: Flag = Flag()) = OrderStaleCache.Store(File(base, "orders"), flag).apply { partitionByLoginEpoch() }

    private fun listFiles(): List<File> = File(base, "orders").walkTopDown().filter { it.isFile && it.name.startsWith("list_") }.toList()

    /** 订单列表的假页面（AppOrders 经 bindController 交来的那份）：记录会话换代与「身份已确认」的替换。 */
    private class FakeList : OrderListHandle {
        var sessionChanges = 0
        val replaced = mutableListOf<SessionIdentity>()

        override fun onSessionChanged() {
            sessionChanges += 1
        }

        override fun onCleanup() = Unit

        override fun patch(detail: OrderDetail) = Unit

        override fun requestScope(scope: String?) = Unit

        override fun refresh() = Unit

        override fun onIdentityConfirmed(identity: SessionIdentity) {
            replaced += identity
        }

        override val pending: PendingTrackService? = null
    }

    private object NoApi : PageApi {
        override suspend fun send(call: ApiCall): ApiResult = CompletableDeferred<ApiResult>().await()

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    /** 与 AppModule 同一入口的 AppOrders（只接会话对账要的部分），并登记一个假列表页。 */
    private fun appOrders(
        identity: Identity,
        cache: OrderStaleCache.Store,
        list: FakeList? = FakeList(),
        api: PageApi = NoApi,
        restore: com.couli.core.orders.OrderRestoreStore? = null,
    ): AppOrders {
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(context, environment).also {
                it.updateSession(com.couli.core.router.SessionState.PHONE_BOUND, resumePending = false)
            }
        val orders =
            AppOrders(
                api = api,
                store = null,
                routing = routing,
                sessionEpoch = { identity.value.loginEpoch },
                account = { identity.value.account },
                writer = { true },
                permissions = null,
                notificationsEnabled = { false },
                forceUpdate = {},
                cache = cache,
                mask = null,
                restoreStore = restore,
                identity = identity::read,
            )
        if (list != null) orders.listConfig(RouteDestination("OrderList", "{}", false)).bindController(list)
        return orders
    }

    // ---------------------------------------------------------------- F-X-17 补充

    @Test
    fun fuand0717_x17s_sameAccountE1ToE2WithTheCounterUnchangedDeletesTheOldPartitionThroughTheProductionEntry() {
        val cache = orderCache()
        cache.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, "login-1")
        val identity = Identity(known("user-a", "login-1"))
        val list = FakeList()
        val orders = appOrders(identity, cache, list)
        orders.onSessionChanged()
        assertNotNull("同一世代：不删", cache.readList("user-a", "self", "login-1"))
        // 同账号重新登录：持久化世代 E1 → E2，本进程登录世代（计数）不变
        identity.value = known("user-a", "login-2")
        orders.onSessionChanged()
        assertTrue("旧分区全删", listFiles().isEmpty())
        assertNull("新世代读 null", cache.readList("user-a", "self", "login-2"))
        assertEquals("按会话换代重置页面", 1, list.sessionChanges)
    }

    // ---------------------------------------------------------------- F-X-19 / 补充 / 补充二

    @Test
    fun fuand0717_x19_coldStartWithAnUnknownIdentityKeepsTheCacheAndTheKnownSameIdentityKeepsItToo() {
        val cache = orderCache()
        cache.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, "login-1")
        val identity = Identity(SessionIdentity.unknown(1L))
        val list = FakeList()
        val orders = appOrders(identity, cache, list)
        // 冷启动首次对账（connectSession 同一入口）：凭据暂不可读
        orders.connectSession()
        idle()
        assertNotNull("身份暂时未知：不删", cache.readList("user-a", "self", "login-1"))
        // 凭据变可读：未知 → 已知、同一账号同一世代 → 不算会话变化、不删
        identity.value = known("user-a", "login-1")
        orders.onSessionChanged()
        assertNotNull("未知 → 已知：不删", cache.readList("user-a", "self", "login-1"))
        assertEquals("不是会话换代", 0, list.sessionChanges)
        // 对照：已确认没有会话（登出）照删
        identity.value = known(null, null, epoch = 2L)
        orders.onSessionChanged()
        assertTrue("已确认没有会话：删", listFiles().isEmpty())
    }

    @Test
    fun fuand0717_x19s2_aPendingClearCompletesAtInitEvenWhenTheIdentityIsUnknown() {
        val flag = Flag()
        val cache = orderCache(flag)
        cache.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, "login-1")
        // 上次删除失败的残留：目录外的待清理标记还在
        flag.value = true
        val orders = appOrders(Identity(SessionIdentity.unknown(1L)), cache)
        orders.connectSession()
        idle()
        assertTrue("身份未知也先完成待清理：文件实际被删", listFiles().isEmpty())
        assertFalse(flag.value)
    }

    // ---------------------------------------------------------------- F-X-19 补充三

    @Test
    fun fuand0717_x19s3_oneCheckReadsTheIdentityOnceSoASingleFailureBetweenReadsDoesNotRebuild() {
        val cache = orderCache()
        // 别的账号留下的缓存：本次检查若用已知身份对账就会删掉
        cache.writeList("user-b", "self", OrderListData(listOf(item("ob")), null, null), 1L, "login-b")
        val identity = Identity(known("user-a", "login-1"))
        val list = FakeList()
        val orders = appOrders(identity, cache, list)
        orders.onSessionChanged()
        val before = identity.reads
        // 下一次检查里的第二次读取会失败（若判据与对账各读一次，就会拼出「已知判据 + 未知对账」或反过来）
        identity.failOnRead = before + 2
        orders.onSessionChanged()
        assertEquals("一次检查只读一次身份", before + 1, identity.reads)
        assertEquals("没有误判为会话换代", 0, list.sessionChanges)
        assertTrue("对账用的就是这一份已知身份：别的账号的缓存已删", listFiles().isEmpty())
    }

    @Test
    fun fuand0717_x19s3_aPageBuiltWhileUnknownIsReplacedWithTheConfirmedSnapshotBeforeTheEarlyReturn() {
        val identity = Identity(SessionIdentity.unknown(1L))
        val list = FakeList()
        val orders = appOrders(identity, orderCache(), list)
        orders.onSessionChanged()
        assertTrue("未知：不替换", list.replaced.isEmpty())
        val confirmed = known("user-a", "login-1")
        identity.value = confirmed
        orders.onSessionChanged()
        assertEquals("判据相同（未知 → 已知）：提前返回前先替换", 1, list.replaced.size)
        assertSame("用的就是这次检查的那份快照", confirmed, list.replaced.single())
        assertEquals(0, list.sessionChanges)
    }

    @Test
    fun fuand0717_x19s3_aReconcileThatDidNotCompleteIsRegisteredAndRetriedSoTheOldPartitionIsDeleted() {
        val flag = Flag()
        val cache = orderCache(flag)
        cache.writeList("user-a", "self", OrderListData(listOf(item("o1")), null, null), 1L, "login-1")
        val identity = Identity(known("user-a", "login-1"))
        val orders = appOrders(identity, cache)
        orders.onSessionChanged()
        // 换代识别后的对账没做完（删除后清标记失败）：登记待重试
        flag.clearFailures = 1
        identity.value = known("user-a", "login-2")
        orders.onSessionChanged()
        assertTrue("对账没完成：已登记", orders.reconcilePending)
        // 恢复后（判据已不变）重试：旧分区确实被删
        orders.onSessionChanged()
        assertFalse(orders.reconcilePending)
        assertTrue("旧分区被删", listFiles().isEmpty())
        assertNull(cache.readList("user-a", "self", "login-1"))
    }

    @Test
    fun fuand0717_x19_walletReconcileFollowsTheSameRules() {
        val flag = Flag()
        val cache = WalletStaleCache.Store(File(base, "wallet"), flag).apply { partitionByLoginEpoch() }
        cache.write("user-a", summary, 1L, "login-1")
        val identity = Identity(SessionIdentity.unknown(1L))
        val reconciler =
            SessionCacheReconciler(identity::read, 1L, cache::completePendingClear) { id -> cache.reconcileOwner(id.account, id.partition) }
        assertEquals(SessionCacheReconciler.Decision.UNKNOWN, reconciler.check())
        assertNotNull("未知：不删", cache.read("user-a", "login-1"))
        identity.value = known("user-a", "login-1")
        assertEquals(SessionCacheReconciler.Decision.SAME, reconciler.check())
        assertNotNull("未知 → 已知：不删", cache.read("user-a", "login-1"))
        identity.value = known("user-a", "login-2")
        assertEquals("同账号换代（计数不变）", SessionCacheReconciler.Decision.CHANGED, reconciler.check())
        assertNull(cache.read("user-a", "login-1"))
    }

    // ---------------------------------------------------------------- 第 1 轮修复

    /** 第 1 轮修复（疑点 ④ 裁定）：身份暂时未知时即使本进程登录世代变了也不决策、不重置页面；身份确认之后才按换代重置一次。 */
    @Test
    fun fuand0717_r1_unknownNeverDecidesAndOnlyAConfirmedChangeResetsThePage() {
        val identity = Identity(known("user-a", "login-1"))
        val list = FakeList()
        val orders = appOrders(identity, orderCache(), list)
        orders.onSessionChanged()
        identity.value = SessionIdentity.unknown(2L)
        orders.onSessionChanged()
        assertEquals("未知：不重置", 0, list.sessionChanges)
        identity.value = known("user-a", "login-2", epoch = 2L)
        orders.onSessionChanged()
        assertEquals("确认换代：重置一次", 1, list.sessionChanges)
    }

    /** 订单列表请求的假接口：每次立即回一页（[held] 非空时先挂住第一次请求）；记录取消。 */
    private class ListApi : PageApi {
        val calls = mutableListOf<ApiCall>()
        var cancelled = 0

        @Volatile var held: CompletableDeferred<Unit>? = null

        override suspend fun send(call: ApiCall): ApiResult {
            // 只记订单列表请求（待跟单卡的查询另回空）
            if (call.operationId != com.couli.feature.orders.OrderListController.LIST_OPERATION) {
                return ApiResult.Success(
                    kotlinx.serialization.json.Json
                        .parseToJsonElement("""{"items":[]}"""),
                    "trace-000123",
                    null,
                )
            }
            calls += call
            held?.let {
                held = null
                it.await()
            }
            return ApiResult.Success(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(LIST_PAGE),
                "trace-000123",
                null,
            )
        }

        override fun cancelOwned(owner: CallOwnership) {
            cancelled += 1
        }

        override fun epochs() = CallEpochs(0, 0)
    }

    /**
     * R1-03：真实订单控制器经生产入口 [AppOrders.onSessionChanged]：本进程计数不变、同账号持久化世代 E1 → E2——控制器按 App 交来的快照重新绑定 E2，
     * 作废并取消旧请求、清掉旧结果，按新会话重取第一页。
     */
    @Test
    fun fuand0717_r1_03_theRealListControllerRebindsE2WhenOnlyThePersistedEpochChanges() {
        val identity = Identity(known("user-a", "login-1"))
        val api = ListApi()
        val orders = appOrders(identity, orderCache(), list = null, api = api)
        orders.onSessionChanged()
        val page =
            com.couli.feature.orders.OrderListViewModel(
                orders.listConfig(RouteDestination("OrderList", "{}", false)),
                SavedStateHandle(),
            )
        idle()
        assertEquals("login-1", page.controller.ownerLoginEpoch)
        assertEquals(1, api.calls.size)
        assertEquals(1, page.controller.state.value.items.size)
        // 下一次请求挂住：换代时它在途
        api.held = CompletableDeferred()
        page.refresh()
        idle()
        assertEquals(2, api.calls.size)
        identity.value = known("user-a", "login-2")
        orders.onSessionChanged()
        idle()
        assertEquals("控制器按 E2 重新绑定", "login-2", page.controller.ownerLoginEpoch)
        assertTrue("旧请求已取消", api.cancelled > 0)
        assertEquals("按新会话重取第一页", 3, api.calls.size)
        page.controller.leave()
    }

    /** R1-05：没有订单页实例时同账号换代（E1 → E2）：生产入口对账之后、任何读取之前，E1 的恢复记录已被删掉；E2 自己的记录不动。 */
    @Test
    fun fuand0717_r1_05_aSessionChangeWithoutAnyOrdersPageDeletesTheOldRestoreRecordBeforeAnyRead() {
        val restoreRoot = File(base, "restore")
        var current: String? = "login-1"
        val restore =
            com.couli.core.orders
                .OrderRestoreStore(restoreRoot)
                .apply { bindLoginEpoch { current } }
        val owner = OrderStaleCache.Store.hash("user-a")
        val old = restore.newRef()
        assertTrue(
            restore.write(
                old,
                restore.epoch,
                com.couli.core.orders
                    .OrderRestoreRecord(owner, anchorOrderId = "o1"),
                "login-1",
            ),
        )
        val identity = Identity(known("user-a", "login-1"))
        val orders = appOrders(identity, orderCache(), list = null, restore = restore)
        orders.onSessionChanged()
        assertTrue("同一世代：保留", File(restoreRoot, "$old.json").exists())
        current = "login-2"
        identity.value = known("user-a", "login-2")
        orders.onSessionChanged()
        assertFalse("对账后（任何读取之前）旧世代的恢复记录已删", File(restoreRoot, "$old.json").exists())
        assertFalse(orders.reconcilePending)
    }

    /**
     * R1-01：合法旧会话（账号 A、令牌、full，只缺升级前没有的持久化登录世代）——身份快照保留已确认的账号（分区未知）；订单列表与钱包照常在线加载，
     * 缓存不读不写（分区目录里没有任何文件）。
     */
    @Test
    @Suppress("LongMethod") // 订单与钱包两条在线路径同一份旧会话
    fun fuand0717_r1_01_aLegacySessionWithoutAPersistedEpochLoadsOrdersAndWalletOnlineWithoutTouchingTheCache() {
        val memory =
            com.couli.core.network
                .MemorySessionTokens("access-a", "refresh-a", "full", "user-a")
        // 不实现 PersistedLoginEpochs：等同升级前登录、令牌存储里没有世代字段
        val legacy = object : com.couli.core.network.SessionTokens by memory {}
        val client =
            com.couli.core.network.ApiClient(
                identity =
                    com.couli.core.network
                        .ClientIdentity("couli", "android", "1.0.0", "1", "official"),
                baseUrl = { "https://api.example.test/" },
                context = {
                    com.couli.core.network.NetworkContext(
                        com.couli.core.network.PrivacyRaw.FULL,
                        true,
                        true,
                        com.couli.core.network.UpdateRaw.SUPPORTED,
                    )
                },
                device = LegacyDevice,
                tokens = legacy,
                pendingKeys =
                    com.couli.core.network
                        .PendingKeyStore(
                            com.couli.core.network
                                .MemoryPendingKeyBackend(),
                        ) { 0L },
                sleeper = {},
            )
        val id = client.sessionIdentity()
        assertTrue("在线身份已知", id.known)
        assertEquals("账号保留", "user-a", id.account)
        assertFalse("缓存分区未知", id.partitionKnown)
        // 订单：真实控制器按这份身份在线加载
        val cache = orderCache()
        val api = ListApi()
        val env =
            object : com.couli.feature.orders.OrderEnv {
                override fun loggedIn() = true

                override fun sessionEpoch() = client.sessionIdentity().loginEpoch

                override fun account() = client.activeUserId()

                override fun identity() = client.sessionIdentity()

                override fun helpLinks(): JsonObject? = null

                override fun copy(text: String) = true

                override fun open(
                    route: String,
                    params: JsonObject,
                ) = Unit

                override fun forceUpdate() = Unit
            }
        val controller =
            com.couli.feature.orders
                .OrderListController(api, env, cache, scope, { 0L })
        controller.open(null)
        idle()
        assertEquals("在线请求发出", 1, api.calls.size)
        assertEquals("在线结果显示", 1, controller.state.value.items.size)
        assertTrue("订单缓存不写", listFiles().isEmpty())
        assertNull("订单缓存不读", cache.readList("user-a", "self", id.partition))
        controller.leave()
        // 钱包：同一份身份在线加载，缓存不写
        val walletRoot = File(base, "wallet-legacy")
        val walletCache = WalletStaleCache.Store(walletRoot, Flag()).apply { partitionByLoginEpoch() }
        val walletEnv = Env()
        val walletApi =
            Api { call ->
                if (call.operationId ==
                    WalletController.OPERATION
                ) {
                    ApiResult.Success(
                        com.couli.core.wallet.WalletJson
                            .encode(summary),
                        "t",
                        null,
                    )
                } else {
                    null
                }
            }
        val wallet = WalletController(deps(LegacyWalletEnv(walletEnv, id), walletApi, walletCache), scope)
        wallet.load()
        assertEquals("钱包在线加载出摘要", summary, wallet.state.value.summary)
        assertTrue("钱包缓存不写", walletRoot.walkTopDown().none { it.isFile && it.name == "summary.json" })
    }

    private object LegacyDevice : com.couli.core.network.DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "test-only.install.device-a"
    }

    /** 钱包环境：身份快照取合法旧会话那一份（账号已知、分区未知）。 */
    private class LegacyWalletEnv(
        private val base: Env,
        private val id: SessionIdentity,
    ) : WalletEnv by base {
        override fun account() = id.account

        override fun sessionEpoch() = id.loginEpoch

        override fun identitySnapshot() = id
    }

    // ---------------------------------------------------------------- F-X-17：钱包摘要 / 金额草稿 / 首页 LKG / phone_bound

    private class Env : WalletEnv {
        @Volatile var epoch = 1L

        @Volatile var partition = "login-1"

        /** 身份暂时未知（凭据不可读）。 */
        @Volatile var unknown = false

        /** [identitySnapshot] 被调用时同账号重新登录（只用于捕捉「写入时再读当前世代」的残留）。 */
        @Volatile var reloginOnSnapshot = false

        override fun loggedIn() = true

        override fun sessionEpoch() = epoch

        override fun account() = if (unknown) null else "user-a"

        override fun identitySnapshot(): SessionIdentity {
            if (unknown) return SessionIdentity.unknown(epoch)
            if (reloginOnSnapshot) {
                reloginOnSnapshot = false
                relogin()
            }
            return SessionIdentity(known = true, account = "user-a", loginEpoch = epoch, partition = partition)
        }

        fun relogin() {
            epoch += 1
            partition = "login-" + epoch
        }

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) = Unit

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String) = "13800005678"

        override fun markWalletDirty(
            account: String,
            epoch: Long,
        ) = Unit

        override fun payoutSaved(entryId: String?) = false
    }

    private class Api(
        private val answer: (ApiCall) -> ApiResult?,
    ) : WalletApi {
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

        override suspend fun send(call: ApiCall): ApiResult = answer(call) ?: CompletableDeferred<ApiResult>().await()

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private fun deps(
        env: WalletEnv,
        api: WalletApi,
        cache: WalletStaleCache.Store?,
    ) = WalletDeps(
        api = api,
        env = env,
        gates = WalletGates(scope) { 0L },
        tokens = StepUpTokenCache(),
        cache = cache,
        mask = null,
        realname = null,
        labor = null,
        clock = { 0L },
        service = scope,
    )

    @Test
    fun fuand0717_x17_aLateWalletSummaryWriteAfterTheLastIdentityCheckStaysOutOfTheNewLoginPartition() {
        val env = Env()
        val flag =
            object : WalletStaleCache.PendingClearFlag {
                @Volatile var armed = false

                override fun isSet(): Boolean {
                    // 交错点：结果已通过最后一次身份核对、进入缓存写入，第一次核验目录外标记时同账号重新登录
                    if (armed) {
                        armed = false
                        env.relogin()
                    }
                    return false
                }

                override fun set() = true

                override fun clear() = true
            }
        val root = File(base, "wallet-x17")
        val cache = WalletStaleCache.Store(root, flag).apply { partitionByLoginEpoch() }
        val api =
            Api { call ->
                if (call.operationId != WalletController.OPERATION) return@Api null
                flag.armed = true
                ApiResult.Success(
                    com.couli.core.wallet.WalletJson
                        .encode(summary),
                    "trace-000123",
                    null,
                )
            }
        // 交错点也放在时钟上：结果通过身份核对后、求写入实参（摘要时刻）时同账号重新登录——实参里若再读当前世代就会读到 E2（iOS R2-02 同类）
        val clocked =
            WalletDeps(
                api = api,
                env = env,
                gates = WalletGates(scope) { 0L },
                tokens = StepUpTokenCache(),
                cache = cache,
                mask = null,
                realname = null,
                labor = null,
                clock = {
                    if (flag.armed) {
                        flag.armed = false
                        env.relogin()
                    }
                    0L
                },
                service = scope,
            )
        val wallet = WalletController(clocked, scope)
        wallet.load()
        assertEquals("前提：已是新世代", "login-2", env.partition)
        assertNull("新世代分区读不到旧摘要", cache.read("user-a", "login-2"))
        assertNull("重建实例同样读不到", WalletStaleCache.Store(root, flag).apply { partitionByLoginEpoch() }.read("user-a", "login-2"))
        assertNotNull("旧摘要只在旧世代分区", cache.read("user-a", "login-1"))
        assertTrue(cache.reconcileOwner("user-a", "login-2"))
        assertNull("对账不保留", cache.read("user-a", "login-1"))
    }

    /** F-X-19 补充三 ②（钱包）：身份暂时未知期间建的钱包页，身份确认后换成这份已确认身份并能加载（不是一直停在身份错误）。 */
    @Test
    fun fuand0717_x19s3_aWalletPageBuiltWhileUnknownLoadsAfterTheConfirmedIdentityReplacesIt() {
        val env = Env().apply { unknown = true }
        val api =
            Api { call ->
                if (call.operationId ==
                    WalletController.OPERATION
                ) {
                    ApiResult.Success(
                        com.couli.core.wallet.WalletJson
                            .encode(summary),
                        "t",
                        null,
                    )
                } else {
                    null
                }
            }
        val wallet = WalletController(deps(env, api, null), scope)
        wallet.load()
        assertNull("未知期间：没有摘要", wallet.state.value.summary)
        env.unknown = false
        wallet.onIdentity(env.identitySnapshot())
        assertEquals("换成已确认身份后加载出摘要", summary, wallet.state.value.summary)
    }

    @Test
    fun fuand0717_x17_anAmountDraftSavedAfterTheLastIdentityCheckIsNotRestoredByTheNewLogin() {
        val env = Env()
        val saved = SavedStateHandle()
        val config = WalletPageConfig(deps(env, Api { null }, null), onBack = {}, hooks = PageHooks())
        val first = WithdrawViewModel(config, saved)
        first.onAmountChange("12.34")
        // 再保存一次：身份核对通过之后、取分区之前（若此时再读当前身份）同账号重新登录
        env.reloginOnSnapshot = true
        first.persist()
        // 正确实现保存时不再读身份（重新登录在这里才发生）；残留实现在保存途中已重新登录。两条路都停在 E2
        if (env.reloginOnSnapshot) {
            env.reloginOnSnapshot = false
            env.relogin()
        }
        assertEquals("login-2", env.partition)
        val second = WithdrawViewModel(config, saved)
        assertEquals("新世代的表单不恢复旧金额", "", second.controller.state.value.amountText)
    }

    @Test
    fun fuand0717_x17_aHomeLkgWriteWithAnOldTicketNeverLandsInTheNewLoginPartition() {
        var partition: String? = "login-1"
        val root = File(base, "home")
        val flag =
            object : HomeLkgStore.PendingFlag {
                override fun entries(): Set<String> = emptySet()

                override fun add(entry: String) = true

                override fun remove(entry: String) = true
            }
        val store = HomeLkgStore(root, flag).apply { bindLoginEpoch { partition } }
        val scope = HomeLkgScope.of("member", "user-a", "test")
        val ticket = store.ticket(scope)
        // 取票（页面固定身份）之后同账号重新登录，旧写入才到
        partition = "login-2"
        store.write(ticket, """{"page":"old"}""")
        assertNull("新世代读不到", store.read(scope))
        assertNull("重建实例同样读不到", HomeLkgStore(root, flag).apply { bindLoginEpoch { partition } }.read(scope))
    }

    @Test
    fun fuand0717_x17_phoneBoundIsKeyedByTheLoginEpochFixedAtTheQuery() {
        val state = PhoneBindingState()
        val epoch = 1L
        val generation = state.generation()
        // getMe 在 E1 发起；回来写入时已同账号重新登录（E2）：写入只按发起时的 E1
        assertTrue(state.applyIfCurrent(epoch, generation, bound = true))
        assertNull("新世代没有记录（未知 = 未验证）", state.get(2L))
        assertTrue(state.treatAsUnbound(2L))
    }

    private companion object {
        const val LIST_PAGE =
            """{"items":[{"order_id":"ord-1","platform":"taobao","title":"样例商品 A","image_url":null,"pay_amount_fen":4560,"quantity":2,""" +
                """"order_no":"3741234567890123456","display_status":"PAID","reason":null,"est_rebate_fen":234,""" +
                """"paid_at":"2026-10-08T09:05:00+08:00","is_other_product":false}],"next_cursor":null,"earliest_visible_date":null}"""
    }
}
