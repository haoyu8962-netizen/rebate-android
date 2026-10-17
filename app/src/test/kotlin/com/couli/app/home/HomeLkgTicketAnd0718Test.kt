@file:Suppress("MagicNumber")

package com.couli.app.home

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.persistedLoginEpochOf
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeLevel
import com.couli.core.home.HomeLkgScope
import com.couli.core.home.HomeLkgStore
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PersistedLoginEpochs
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.home.HomeController
import com.couli.feature.home.HomeTimers
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0718 / F-AND-40（FU-AND-0717 第 3 轮评审 R3-05）：首页会员 LKG 的写入票据与首页固定身份用同一份身份快照；持久化登录世代某一次读不出之后，
 * 首次确认要能补上有效票据、保存 LKG；同一进程计数下 E1→E2 换代要作废旧工作重建，旧响应不得落进新世代。
 *
 * 经生产入口：真实 AppHome（connect / ensure = 首页根页组合时的入口 / sync = 会话通知的同步出口）+ 真实首页控制器（下拉刷新 = 页面手势调用的
 * controller.refresh(渲染票据)）+ 真实 HomeLkgStore（与 HomeSystem 同一接线：bindLoginEpoch(persistedLoginEpochOf(tokens))）+ ApiClient 的身份快照；
 * 待定 operation 由替身端口回答（Debug / Staging 本机假服务的位置；Release 没有首页网络来源）。
 * 唯一的故障注入点是令牌存储的「持久化登录世代」读取（[EpochTokens]）：生产里身份快照与 LKG 存储都从同一个令牌存储读它，测试不区分是谁在读，
 * 只按读取序号注入（「仅第 n 次失败」「第 n 次起一直失败、之后恢复」两种，按序号遍历）。
 * 等待：本机文件读写放在主线程（io = Main.immediate），端口返回后往主线程队列尾部投结算记录（[Port.settled]）；负向断言都在结算之后。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class HomeLkgTicketAnd0718Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 令牌存储（与 AppModule 同一个对象交给 ApiClient、AppHome 与 LKG 存储）：持久化登录世代按读取序号（从 1 起）注入「读不出」；可在进程计数不变时换世代。 */
    private class EpochTokens(
        private val inner: MemorySessionTokens,
    ) : SessionTokens by inner,
        PersistedLoginEpochs {
        @Volatile var reads = 0
        private val failAt = mutableSetOf<Int>()

        @Volatile private var failFrom = Int.MAX_VALUE

        /** 跨进程换了令牌（同账号重新登录）：本进程登录世代计数不变，持久化世代变了。 */
        @Volatile var swapped: String? = null

        @Volatile private var swapFrom = Int.MAX_VALUE

        @Volatile private var swapTo: String? = null

        /** 从此刻算起的第 [n] 次读取起读到另一个**有效**世代 [epoch]（第 2 版 T-02：身份确认与取票之间跨进程换代，进程计数不变）。 */
        fun swapFromNext(
            n: Int,
            epoch: String,
        ) = synchronized(this) {
            swapFrom = reads + n
            swapTo = epoch
        }

        /** 从此刻算起的第 [n] 次读取（只这一次）读不出。 */
        fun failOnlyNext(n: Int) = synchronized(this) { failAt += reads + n }

        /** 从此刻算起的第 [n] 次读取起一直读不出，直到 [recover]。 */
        fun failFromNext(n: Int) = synchronized(this) { failFrom = reads + n }

        fun recover() =
            synchronized(this) {
                failAt.clear()
                failFrom = Int.MAX_VALUE
            }

        override fun persistedLoginEpoch(): String? {
            val n = synchronized(this) { ++reads }
            return when {
                n in failAt || n >= failFrom -> null
                n >= swapFrom -> swapTo
                else -> swapped ?: inner.persistedLoginEpoch()
            }
        }
    }

    private object NoFlag : HomeLkgStore.PendingFlag {
        override fun entries(): Set<String> = emptySet()

        override fun add(entry: String) = true

        override fun remove(entry: String) = true
    }

    private class Store : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture-0718"
        override val installSecret: String = "install-fixture"
    }

    /** 不触发的计时（端口即答，不需要 1.5 秒兜底）。 */
    private object NoTimers : HomeTimers {
        override fun after(
            delayMs: Long,
            block: () -> Unit,
        ): () -> Unit = {}
    }

    /** 待定 operation 的替身：页面按 [page] 回答；[hold] 非空时回答之后先挂住（迟到的旧响应）；每次返回后往主线程队列尾部记一次结算。 */
    private class Port : HomeContractPort {
        private val main = Handler(Looper.getMainLooper())

        @Volatile var calls = 0

        @Volatile var settled = 0

        /** 每个页面请求实际绑定的世代（接口约定 [HomeCall.loginEpoch]，按发出顺序）。 */
        val bound = mutableListOf<String?>()

        fun boundFrom(index: Int): List<String?> = synchronized(bound) { bound.drop(index) }

        @Volatile var page: (HomeCall) -> ApiResult = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.DEFAULT_TEST_PAGE) }

        @Volatile var hold: CompletableDeferred<Unit>? = null

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult {
            if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            calls += 1
            synchronized(bound) { bound += call.loginEpoch }
            try {
                val reply = page(call)
                hold?.await()
                return reply
            } finally {
                main.post { settled += 1 }
            }
        }

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult = AppHomeIntegrationTest.itemsOf(sectionId, cursor)
    }

    /** 一个进程：账号 A 已登录（持久化世代 E1），首页按 AppModule 的接线搭起来。 */
    private inner class Rig {
        val tokens = EpochTokens(MemorySessionTokens("access-a", "refresh-a", "full", USER) { "login-epoch-" + (++epochSerial) })
        val lkg = HomeLkgStore(File(context.noBackupFilesDir, "home-lkg-0718-${System.nanoTime()}"), NoFlag)
        val port = Port()
        val home: AppHome

        init {
            // 与 HomeSystem.homeLkgStore(context, tokens) 同一接线
            lkg.bindLoginEpoch(persistedLoginEpochOf(tokens))
            val gate =
                PrivacyGate(Store(), clock = { 0L }, bundledPolicyVersion = "3").apply {
                    retryScheduler =
                        RetryScheduler {
                            _,
                            _,
                            ->
                        }
                }
            val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { 0L }).also { it.coldStart() }
            val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
            val routing =
                AppRouting(
                    context,
                    environment,
                    clock = { 0L },
                    initialPrivacy = privacy.conclusion,
                    privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
                    initialUpdate = UpdateCheck.OK,
                )
            val client =
                ApiClient(
                    identity =
                        ClientIdentity(
                            appId = "couli",
                            platform = "android",
                            appVersion = "1.0.0",
                            build = "1",
                            channel = "official",
                        ),
                    baseUrl = { "http://127.0.0.1:9/" },
                    context = {
                        NetworkContext(
                            PrivacyRaw.FULL,
                            environmentSettled = true,
                            credentialsReadable = true,
                            UpdateRaw.SUPPORTED,
                        )
                    },
                    device = Device,
                    tokens = tokens,
                    pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                    inFlight = InFlightCalls(),
                    sleeper = {},
                )
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
            home =
                AppHome(
                    client = client,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    port = port,
                    lkg = lkg,
                    prefs = null,
                    images = null,
                    envKey = { ENV },
                    configData = { null },
                    track = {},
                    serverForced = {},
                    cardTexts = AppHomeIntegrationTest.CARD_TEXTS,
                    appVersion = "1.10.0",
                    clock = { 0L },
                    timers = NoTimers,
                    bundled = { null },
                    api = Notices(client),
                    io = Dispatchers.Main.immediate,
                ).also { it.connect() }
        }

        val member: String get() = HomeLkgScope.of(HomeLevel.MEMBER, USER, ENV)

        /** 首页根页组合（HomeRoute 调的入口）。 */
        fun open(): HomeController? {
            val controller = home.ensure()
            portSettled()
            return controller
        }

        /** 等此刻已发出的页面请求（除仍被有意挂住的 [outstanding] 个）全部连同首页控制器的处理都已结算。 */
        fun portSettled(outstanding: Int = 0) {
            idle()
            FlowWait.until(::idle, { "页面请求结算 ${port.settled}/${port.calls}（挂住 $outstanding）" }) {
                port.settled >= port.calls - outstanding
            }
            idle()
        }

        /** 当前首页控制器上的一次下拉刷新（页面手势调用的入口，带此刻的渲染票据），等它结算。 */
        fun pullRefresh(version: Int) {
            val controller = home.controller.value
            assertNotNull("首页有控制器", controller)
            val before = port.calls
            port.page = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(version, AppHomeIntegrationTest.activity("v$version"))) }
            controller!!.refresh(controller.state.value.render)
            idle()
            assertTrue("下拉刷新发出了页面请求", port.calls > before)
            portSettled()
        }

        /** 此刻（当前世代分区）读到的会员 LKG。 */
        fun saved(): String? = lkg.read(member)
    }

    /** 公告接口（listArticles）不连网：回空列表。 */
    private class Notices(
        private val client: ApiClient,
    ) : PageApi {
        override suspend fun send(call: ApiCall): ApiResult =
            ApiResult.Success(
                kotlinx.serialization.json.Json
                    .parseToJsonElement("""{"items":[],"next_cursor":null}"""),
                "trace-000123",
                null,
            )

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = client.inFlight.epochs()
    }

    private var epochSerial = 0

    /**
     * F-AND-40 ①：建首页（首页同步 + 控制器建立 + 冷启动 + 首次网络页）期间，持久化世代的第 n 次读取读不出（仅这一次 / 从这一次起一直到恢复）。恢复之后
     * 经真实首页同步（判据相同或首次确认）、再下拉刷新得到新版本 v(100+n)：LKG 必须保存的是这个新版本（当前世代分区读得到），首屏也换成它。
     * 之后再单次失败一次（v(200+n) 这次可以不存），恢复后的 v(300+n) 仍要保存。
     * 遍历 n = 1、2、…，直到建首页期间的读取次数不足 n 为止（每个 n 都核对确实打中了第 n 次读取）。
     */
    @Test
    fun fand40_aFailedEpochReadAtAnyPositionWhileBuildingTheHomeNeverLeavesItUnableToSaveLkg() {
        val misses = mutableListOf<String>()
        for (form in listOf(ONLY, FROM)) {
            var n = 1
            var complete = false
            while (n <= MAX_POSITION) {
                val rig = Rig()
                if (form == ONLY) rig.tokens.failOnlyNext(n) else rig.tokens.failFromNext(n)
                rig.open()
                // 故障确实命中第 n 次读取才算这个位置；建首页期间的读取不足 n 次 = 实际发生的位置已遍历完
                val hit = rig.tokens.reads >= n
                rig.tokens.recover()
                if (!hit) {
                    complete = true
                    break
                }
                // 恢复后：一次真实首页同步（会话通知的同步出口；身份判据相同或首次确认），然后下拉刷新拿到新版本
                rig.home.sync()
                rig.portSettled()
                val version = 100 + n
                rig.pullRefresh(version)
                val saved = rig.saved()
                if (saved == null || !saved.contains("\"v$version\"")) misses += "$form#$n：LKG=${saved?.take(60)}"
                val shown =
                    rig.home.controller.value
                        ?.state
                        ?.value
                        ?.pageVersion
                if (shown != version) misses += "$form#$n：首屏版本=$shown"
                // 恢复后再失败一次（下一次世代读取，例如写入时的锁内复核）：这次不写可以，之后再下拉刷新仍要能保存
                rig.tokens.failOnlyNext(1)
                rig.pullRefresh(200 + n)
                rig.tokens.recover()
                rig.pullRefresh(300 + n)
                val again = rig.saved()
                if (again == null || !again.contains("\"v${300 + n}\"")) misses += "$form#$n：再次失败后 LKG=${again?.take(60)}"
                n += 1
            }
            // 第 2 版 T-03：不要求固定读取次数；只要求每个实际发生的位置都打中过，且遍历没有被上限截断
            if (!complete) misses += "$form：遍历上限 $MAX_POSITION 耗尽，读取位置覆盖未完成"
            if (n == 1) misses += "$form：建首页期间没有任何世代读取，故障没有命中"
        }
        assertEquals("单次 / 持续读不出恢复后都要能保存 LKG：$misses", emptyList<String>(), misses)
    }

    /**
     * F-AND-40 ②：同一进程计数下持久化世代 E1→E2（跨进程换了令牌）。E1 的控制器有一次下拉刷新在途（旧响应）；经真实首页同步确认 E2：
     * - 旧响应迟到之后（已结算）不得落进 E2 的 LKG；
     * - E2 下得到的新版本能保存（同步之后的首页再下拉刷新 → E2 分区读得到它）。
     */
    @Test
    fun fand40_anE1ToE2SwitchInTheSameProcessRebuildsTheTicketAndTheOldResponseNeverLandsInE2() {
        val rig = Rig()
        val e1 = rig.tokens.persistedLoginEpoch()
        rig.open()
        assertNotNull("前提：E1 下首个网络页已存 LKG", rig.saved())
        // 第 4 版 T-07：稳定 E1 下，每个页面请求的归属字段都等于 E1（观察入口确实接线，不能是 null）
        val e1Bound = rig.port.boundFrom(0)
        assertTrue("前提：E1 下发过页面请求", e1Bound.isNotEmpty())
        assertEquals("稳定 E1：请求归属字段都等于 E1", e1Bound.map { e1 }, e1Bound)
        val gate = CompletableDeferred<Unit>()
        rig.port.hold = gate
        val old = rig.home.controller.value!!
        rig.port.page = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(40, AppHomeIntegrationTest.activity("old_resp"))) }
        old.refresh(old.state.value.render)
        idle()
        val oldCalls = rig.port.calls
        rig.port.hold = null
        rig.port.page = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(41, AppHomeIntegrationTest.activity("v41"))) }
        rig.tokens.swapped = "login-epoch-cross-process"
        val afterSwap = rig.port.calls
        rig.home.sync()
        // 旧响应可能仍挂着（旧控制器没被作废时），也可能已随作废取消而结算：两种都只等其余请求
        rig.portSettled(outstanding = if (rig.port.settled < oldCalls) 1 else 0)
        // 旧响应放行并结算（它之前的、同步之后新发的都已结算）
        gate.complete(Unit)
        idle()
        FlowWait.until(::idle, { "旧响应结算 ${rig.port.settled}/${rig.port.calls}" }) {
            rig.port.settled >= rig.port.calls &&
                rig.port.calls >= oldCalls
        }
        idle()
        val afterOld = rig.saved()
        assertFalse("旧响应不得落进 E2：$afterOld", afterOld?.contains("old_resp") == true)
        rig.pullRefresh(42)
        // 第 4 版 T-07：确认 E2 之后发出的页面请求，归属字段都等于 E2
        val e2Bound = rig.port.boundFrom(afterSwap)
        assertTrue("前提：确认 E2 后发过页面请求", e2Bound.isNotEmpty())
        assertEquals("稳定 E2：请求归属字段都等于 E2", e2Bound.map { E2 }, e2Bound)
        val saved = rig.saved()
        assertTrue("E2 下的新版本能保存：${saved?.take(60)}", saved?.contains("\"v42\"") == true)
        assertFalse(saved?.contains("old_resp") == true)
    }

    /**
     * F-AND-40 对照（未知只延后）：首页已按 A/E1 建好并存了 LKG；一次同步的那份快照分区读不出——不重建首页、不删 LKG；读取恢复后的正常同步同样不重建，
     * 下拉刷新的新版本照常保存。
     */
    @Test
    fun fand40_aSyncWhoseSnapshotPartitionIsUnknownDefersAndKeepsTheHome() {
        val rig = Rig()
        val first = rig.open()
        val before = rig.saved()
        assertNotNull("前提：E1 下首个网络页已存 LKG", before)
        rig.tokens.failFromNext(1)
        rig.home.sync()
        rig.portSettled()
        rig.tokens.recover()
        assertTrue("分区未知：不换控制器", rig.home.controller.value === first)
        assertEquals("分区未知：LKG 不动", before, rig.saved())
        rig.home.sync()
        rig.portSettled()
        assertTrue("同一身份：不换控制器", rig.home.controller.value === first)
        rig.pullRefresh(50)
        assertTrue(rig.saved()?.contains("\"v50\"") == true)
    }

    /**
     * 第 2 版 T-02 / 第 3 版 T-06（F-AND-40「票据与固定身份同一份快照」）：建首页期间从第 k 次世代读取起读到有效的 E2（跨进程换了令牌，进程计数不变），
     * 之前读到 E1——遍历 k = 2、3、… 直到建首页期间实际发生的读取全部覆盖（每个 k 都核对命中；上限耗尽判覆盖未完成）：
     * - 每个页面请求按它**实际绑定的不可变身份**回答不同内容（接口约定 [HomeCall.loginEpoch]：请求所属首页固定身份的持久化世代，只读观察）：
     *   绑定 E1 的回 "bound_e1"、绑定 E2 的回 "bound_e2"、未知的回 "bound_unknown"；
     * - 只禁止绑定 E1 的响应进入 E2 分区（建控制器前已确认换代、按 E2 重建的新控制器响应存进 E2 是合法的）；
     * - 之后经真实首页同步确认 E2，下拉刷新的新版本 v(600+k) 能存进 E2。
     */
    @Test
    fun fand40_aTicketTakenRightAfterAnE1SnapshotThatReadsAValidE2NeverLetsE1ContentIntoE2() {
        val misses = mutableListOf<String>()
        var k = 2
        var complete = false
        while (k <= MAX_POSITION) {
            val rig = Rig()
            rig.port.page =
                { call -> AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(21, AppHomeIntegrationTest.activity(boundTag(call)))) }
            rig.tokens.swapFromNext(k, E2)
            rig.open()
            if (rig.tokens.reads < k) {
                complete = true
                break
            }
            // 第 4 版 T-07：本用例不注入读取失败，每个请求都必须带上归属世代；null 判失败，不当作「未知」放过归属检查
            if (rig.port.boundFrom(0).any { it == null }) misses += "#$k：有页面请求没有带归属世代（HomeCall.loginEpoch 为 null）"
            val early = rig.saved()
            if (early?.contains("\"bound_e1\"") == true) misses += "#$k：绑定 E1 的响应进了 E2"
            rig.home.sync()
            rig.portSettled()
            rig.pullRefresh(600 + k)
            val saved = rig.saved()
            if (saved == null || !saved.contains("\"v${600 + k}\"")) misses += "#$k：确认 E2 后新版本没存：${saved?.take(60)}"
            k += 1
        }
        if (!complete) misses += "遍历上限 $MAX_POSITION 耗尽，读取位置覆盖未完成"
        if (k == 2) misses += "建首页期间只有 1 次世代读取，E1→E2 窗口没有命中"
        assertEquals("$misses", emptyList<String>(), misses)
    }

    /**
     * 第 2 版 T-04（反向顺序）：E1 的下拉刷新在途；跨进程换成 E2 并经真实首页同步之后，先在 E2 下发起新一轮下拉刷新并挂住，再放行仍在的 E1 旧响应——
     * 旧请求结算后 E2 不得出现旧内容（旧请求被正确取消也接受）；最后放行新响应，E2 存的是新版本。
     */
    @Test
    fun fand40_anOldE1ResponseArrivingAfterANewE2RefreshStartedNeverLandsInE2() {
        val rig = Rig()
        rig.open()
        val oldGate = CompletableDeferred<Unit>()
        rig.port.hold = oldGate
        val old = rig.home.controller.value!!
        rig.port.page = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(40, AppHomeIntegrationTest.activity("old_resp"))) }
        old.refresh(old.state.value.render)
        idle()
        val oldCalls = rig.port.calls
        rig.port.hold = null
        rig.port.page = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(41, AppHomeIntegrationTest.activity("v41"))) }
        rig.tokens.swapped = E2
        rig.home.sync()
        rig.portSettled(outstanding = if (rig.port.settled < oldCalls) 1 else 0)
        // E2 下先发起新刷新并挂住
        val newGate = CompletableDeferred<Unit>()
        rig.port.hold = newGate
        val current = rig.home.controller.value!!
        rig.port.page = { AppHomeIntegrationTest.ok(AppHomeIntegrationTest.page(43, AppHomeIntegrationTest.activity("v43"))) }
        val before = rig.port.calls
        current.refresh(current.state.value.render)
        idle()
        assertTrue("前提：E2 下的新刷新已发出", rig.port.calls > before)
        rig.port.hold = null
        // 再放行旧响应，等除新刷新之外的请求全部结算
        oldGate.complete(Unit)
        rig.portSettled(outstanding = 1)
        val afterOld = rig.saved()
        assertFalse("旧响应不得落进 E2：${afterOld?.take(60)}", afterOld?.contains("old_resp") == true)
        newGate.complete(Unit)
        rig.portSettled()
        val saved = rig.saved()
        assertTrue("E2 存的是新版本：${saved?.take(60)}", saved?.contains("\"v43\"") == true)
    }

    /** 请求实际绑定的身份（接口约定的只读观察入口 [HomeCall.loginEpoch]），不用令牌此刻的值。 */
    private fun boundTag(call: HomeCall): String =
        when (call.loginEpoch) {
            E2 -> "bound_e2"
            null -> "bound_unknown"
            else -> "bound_e1"
        }

    private companion object {
        const val E2 = "login-epoch-cross-process"
        const val USER = "user-a"
        const val ENV = "test"
        const val ONLY = "仅第n次"
        const val FROM = "第n次起"
        const val MAX_POSITION = 40
    }
}
