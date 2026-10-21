@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.login.AppLogin
import com.couli.app.trade.AppTrade
import com.couli.app.update.AppUpdate
import com.couli.app.update.StoreAccess
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.LocalExchange
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.router.UpdateCheck
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStoreListing
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.LoginOutcome
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.PurchaseEnv
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** r2 S1-1 / G29：真实服务对象 + 真实任务入口；只替换网络/系统出口，绝不伪造来源 StateFlow。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardServiceSources0724Test {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun realUpdateCheckShowsPromptAndDismissalReleasesTheWaitingClipboardRound() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val response = CompletableDeferred<UpdateCheckOutcome>()
                var checks = 0
                val update = updateService(f, backgroundScope, response) { checks += 1 }
                f.clipboard.connectModalSources(update = update)
                assertFalse(update.promptShowing.value)
                update.coldStart()
                runCurrent()
                assertEquals(1, checks)
                response.complete(
                    UpdateCheckOutcome.Success(
                        UpdateCheckResult(
                            minSupported = "1.0.0",
                            recommended = "1.1.0",
                            latest = "1.1.0",
                            defaultStore = "huawei",
                            stores = listOf(UpdateStoreListing("huawei", "1.1.0")),
                        ),
                    ),
                )
                runCurrent()
                assertTrue("独立见证：真实更新 Gate 已经要求显示提示", update.ui.value.state.showPrompt)
                assertTrue("不能用永远 false 的服务属性", update.promptShowing.value)
                f.foreground()
                f.noEffects()
                val epoch =
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch
                update.dismissPrompt()
                runCurrent()
                f.settle()
                assertFalse(update.ui.value.state.showPrompt)
                assertFalse(update.promptShowing.value)
                assertEquals(
                    epoch,
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch,
                )
                assertEquals(1, f.system.reads)
                assertEquals(1, f.parseBodies().size)
                f.card()
            }
        }

    @Test
    fun realPurchaseJumpBlocksAutoReadUntilTheSystemLaunchFails() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val ports = PurchasePorts()
                val trade = tradeService(f, ports)
                f.clipboard.connectModalSources(trade = trade)
                val controller = trade.purchaseController(purchaseEnvironment(), backgroundScope)
                try {
                    assertFalse(trade.jumpInProgress.value)
                    controller.buy(CARD, "home.product.buy")
                    runCurrent()
                    assertEquals(1, ports.requests.size)
                    ports.open.complete(ApiResult.Success(OPEN_RESULT, "trace-0724", ports.requests.single().idempotencyKey))
                    runCurrent()
                    assertTrue("独立见证：执行器已进入外跳中的浏览器提示等待", controller.state.value.browserNotice)
                    assertTrue("live controller 的真实外跳必须投影到 AppTrade", trade.jumpInProgress.value)
                    f.process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    runCurrent() // 排空当前时点工作，不提前越过正在验证的浏览器提示期限。
                    val epoch =
                        f.clipboard.importCoordinator
                            .snapshot()
                            .epoch
                    val readsWhileJumping = f.system.reads
                    val parsesWhileJumping = f.parseBodies().size
                    assertEquals(0, readsWhileJumping)
                    assertEquals(0, parsesWhileJumping)
                    assertNull(f.clipboard.result.value)
                    assertEquals(0, ports.browserCalls)
                    advanceTimeBy(BROWSER_NOTICE_MS)
                    runCurrent() // 虚拟时钟到期，真实执行器调用系统端口并收到失败。
                    assertEquals(1, ports.browserCalls)
                    assertFalse(controller.state.value.browserNotice)
                    assertFalse("外跳失败必须解除服务状态，不能永远为 true", trade.jumpInProgress.value)
                    f.settle()
                    assertEquals(
                        epoch,
                        f.clipboard.importCoordinator
                            .snapshot()
                            .epoch,
                    )
                    assertEquals(1, f.system.reads)
                    assertEquals(1, f.parseBodies().size)
                    f.card()
                } finally {
                    trade.release(controller)
                }
            }
        }

    @Test
    fun realLoginTaskOnHomeBlocksAutoReadUntilTheRequestFinishes() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val login = loginService(f, backgroundScope, entered, release)
                f.clipboard.connectModalSources(login = login) // 服务已构造完成，不在 appClipboard provider 内 login.get()。
                assertTrue(f.clipboard.homeRoot())
                assertFalse(login.taskActive.value)
                val task = async { login.service.login("13800138000", "123456", f.now, "", restricted = false) }
                try {
                    runCurrent()
                    withContext(Dispatchers.IO) { assertTrue("请求已实际进入本机传输口", entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) }
                    runCurrent()
                    assertTrue("独立见证：真实 LoginService 有登录请求在途", login.service.busy)
                    assertTrue("不能把 taskActive 实现成永远 false", login.taskActive.value)
                    f.foreground()
                    f.noEffects()
                    val epoch =
                        f.clipboard.importCoordinator
                            .snapshot()
                            .epoch
                    release.countDown()
                    val outcome = task.await() // 完成屏障，包含真实服务的 finally 清 busy。
                    assertTrue(outcome.stateRaw != LoginOutcome.LOGGED_IN)
                    runCurrent()
                    f.settle()
                    assertFalse(login.service.busy)
                    assertFalse(login.taskActive.value)
                    assertTrue(f.clipboard.homeRoot())
                    assertEquals(
                        epoch,
                        f.clipboard.importCoordinator
                            .snapshot()
                            .epoch,
                    )
                    assertEquals(1, f.system.reads)
                    assertEquals(1, f.parseBodies().size)
                    f.card()
                } finally {
                    release.countDown()
                    task.cancel()
                }
            }
        }

    private fun updateService(
        f: Clipboard0724Fixture,
        scope: CoroutineScope,
        response: CompletableDeferred<UpdateCheckOutcome>,
        onCheck: () -> Unit,
    ): AppUpdate {
        val store = MemoryUpdateStore()
        return AppUpdate(
            gate = UpdateGate(store, { f.now }, "1.0.0", "android", "official", null),
            store = store,
            checker = {
                onCheck()
                response.await()
            },
            stores =
                object : StoreAccess {
                    override val installSource: String? = null

                    override fun canOpen(storeKey: String) = true

                    override fun open(storeKey: String) = error("本例只关闭提示，不打开商店")
                },
            scope = scope,
            canCheck = { true },
            clock = { f.now },
        )
    }

    private fun tradeService(
        f: Clipboard0724Fixture,
        ports: PurchasePorts,
    ): AppTrade {
        val tracker =
            TrackerQueue(
                readFile = { null },
                writeFile = {},
                sink = { _: List<String> -> true },
                privacy = "full",
                userId = { null },
                deviceId = "fixture",
                platform = "android",
                appVersion = "1.0.0",
                sessionId = "fixture-session",
                clock = { f.now },
                canUpload = { false },
            )
        return AppTrade(
            ports,
            null,
            f.routing,
            Provider { tracker },
            UnavailableBaichuan(),
            UnavailableWebCodeAuthorizer,
            ports,
            browserNoticeMillis = BROWSER_NOTICE_MS,
        )
    }

    private class PurchasePorts :
        PageApi,
        JumpPorts {
        val open = CompletableDeferred<ApiResult>()
        val requests = mutableListOf<ApiCall>()
        var browserCalls = 0

        override suspend fun send(call: ApiCall): ApiResult {
            requests += call // 请求入口、挂起之前记录。
            check(call.operationId == TradeOperations.OPEN_LINK)
            return open.await()
        }

        override fun epochs() = CallEpochs(0, 0)

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun installed(platform: String) = "unknown"

        override fun launchApp(
            type: String,
            value: String,
        ) = error("本例只有 h5 步骤")

        override fun openBrowser(url: String): Boolean {
            browserCalls += 1
            return false
        }

        override fun copy(text: String) = error("本例不能写剪贴板")
    }

    private fun loginService(
        f: Clipboard0724Fixture,
        scope: CoroutineScope,
        entered: CountDownLatch,
        release: CountDownLatch,
    ): AppLogin {
        val tokens = MemorySessionTokens()
        val client =
            ApiClient(
                identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
                baseUrl = { "https://fixture.invalid/" },
                context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
                device =
                    object : DeviceCredentials {
                        override val deviceId = "fixture-device"
                        override val installSecret = "fixture-secret"
                    },
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { f.now },
                clock = { f.now },
                local = LoginExchange(entered, release),
            )
        return AppLogin(
            client,
            tokens,
            f.routing,
            { UpdateCheck.OK },
            {},
            LegalVersions(1, 1),
            sessionScope = scope,
            pendingInvite = PendingInviteCode(),
        )
    }

    private class LoginExchange(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : LocalExchange {
        override fun handles(request: Request) = true // 所有请求本机回答，绝不落到真实网络。

        override fun answer(
            request: Request,
            canceled: () -> Boolean,
        ): Response {
            entered.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "测试未释放登录响应" }
            return Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"code":20001,"message":"fixture","data":{"field":"code"},"trace_id":"trace-0724"}"""
                        .toResponseBody(
                            null,
                        ),
                ).build()
        }
    }

    private fun purchaseEnvironment() =
        object : PurchaseEnv {
            override fun loggedIn() = false // 成功 open 后无需额外发须知查询，隔离正在验证的外跳状态。

            override fun basicMode() = false

            override fun requestConsent() = Unit

            override fun platformStatus(platform: String): String? = "on"

            override fun updateCard(
                target: JsonObject,
                transform: (JsonObject) -> JsonObject,
            ) = Unit

            override fun open(
                route: String,
                params: JsonObject,
            ) = Unit

            override fun refetchCard(
                target: JsonObject,
                merge: (JsonObject, JsonObject) -> JsonObject,
            ) = Unit

            override fun helpLink(key: String): Pair<String, JsonObject>? = null
        }

    companion object {
        private const val WAIT_SECONDS = 5L
        private const val BROWSER_NOTICE_MS = 2_000L
        private val CARD =
            Clipboard0724Fixture.json(
                """
                {
                  "card_id": "c1",
                  "product_key": "jd:1",
                  "platform": "jd",
                  "title": "fixture",
                  "link_id": "link-1",
                  "availability": "ok",
                  "final_price_fen": 4678,
                  "rebate_min_fen": 123,
                  "rebate_max_fen": 234,
                  "rebate_basis": "normal"
                }
                """,
            )

        // 固定远期到期值仅防方案过期干扰；等待浏览器提示使用注入的虚拟调度时钟，不睡墙钟。
        private val OPEN_RESULT =
            Clipboard0724Fixture.json(
                """
                {
                  "attempt_id": "attempt-1",
                  "jump": {
                    "primary": {
                      "type": "h5",
                      "value": "https://fixture.invalid/item"
                    },
                    "fallbacks": [],
                    "expire_at": "2099-01-01T00:00:00Z"
                  },
                  "price_changed": false,
                  "old_final_price_fen": 4678,
                  "new_final_price_fen": 4678,
                  "new_rebate_min_fen": 123,
                  "new_rebate_max_fen": 234,
                  "availability": "ok",
                  "requote_failed": false
                }
                """,
            )
    }
}
