package com.couli.app.followup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugFakeHome
import com.couli.app.DebugFakeLoginServer
import com.couli.app.DebugMockGate
import com.couli.app.RawFakeServerSwitch
import com.couli.app.agent.AgentFakeStream
import com.couli.app.applyDebugClipSample
import com.couli.app.buildDefaultEnvironment
import com.couli.app.conformanceLaunchAllowed
import com.couli.app.login.DebugWechatAdapter
import com.couli.app.me.variantNicknameBackend
import com.couli.app.me.variantNotifyBackend
import com.couli.app.me.variantOpenExternalFails
import com.couli.app.me.variantPushDeniedAt
import com.couli.app.me.variantPushTokenSource
import com.couli.app.me.variantWechatInstalled
import com.couli.app.trade.DebugWebCodeAuthorizer
import com.couli.app.trade.variantJumpPorts
import com.couli.app.update.DebugUpdateChecker
import com.couli.app.update.DebugUpdateScenarioStore
import com.couli.app.variantLaborBackend
import com.couli.app.variantRealNameBackend
import com.couli.app.variantStepUpPhone
import com.couli.app.web.ConformanceSource
import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentStreamSink
import com.couli.core.agent.AgentTimer
import com.couli.core.config.AppEnvironment
import com.couli.core.config.DebugCapabilities
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import com.couli.core.home.HomeCall
import com.couli.core.network.ApiResult
import com.couli.core.update.UpdateCheckOutcome
import com.couli.feature.login.ThirdPartyAuthKind
import com.couli.feature.login.UnavailableWechatAdapter
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.JumpPorts
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 1 项（F-X-02 Android）：Debug 替身一律在**实际传输 / 交付入口**过统一闸门 [DebugMockGate]（假服务开关在 + 当前环境允许
 * MOCK_DATA + 环境闸门已就绪），每次调用现判。覆盖鸿蒙 FU-HM-0711b 评审抓过的三类：① 在 test 拿到的旧实例切到 prod 后不再交假数据；
 * ② 等待期间（请求在途、模拟耗时）切到 prod 不交假结果；③ 启动参数（剪贴板样例、一致性页）在 prod 不生效。切回 test 恢复。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0714DebugGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val switcher = EnvironmentSwitcher(context, buildDefaultEnvironment(), EnvironmentSwitchCleanerRegistry())
    private val ready = DebugMockGate.environmentReady

    @Before
    fun setUp() {
        DebugMockGate.environmentReady = { true }
        setSwitch(true)
        switcher.switchTo(AppEnvironment.TEST)
    }

    @After
    fun tearDown() {
        setSwitch(false)
        switcher.switchTo(AppEnvironment.TEST)
        DebugMockGate.environmentReady = ready
    }

    @OptIn(RawFakeServerSwitch::class)
    private fun setSwitch(on: Boolean) {
        DebugMockGate.setSwitch(context, on)
    }

    private fun prod() = switcher.switchTo(AppEnvironment.PROD)

    private fun test() = switcher.switchTo(AppEnvironment.TEST)

    /** 第一次判定放行、之后一律关闭（模拟「入口放行、等待期间切到 prod」）。 */
    private fun closesAfterFirst(): () -> Boolean {
        val calls = AtomicInteger()
        return { calls.incrementAndGet() == 1 }
    }

    @Test
    fun fx02_gate_requires_switch_mock_data_and_ready_environment_on_every_call() {
        val gate = DebugMockGate.of(context)
        assertTrue("test 环境、开关在、环境就绪", gate())
        prod()
        assertFalse("同一个闸门对象：prod 立即关闭", gate())
        test()
        assertTrue("切回 test 恢复", gate())
        DebugMockGate.environmentReady = { false }
        assertFalse("环境未就绪（启动对账前、运行中切换）关闭", gate())
        DebugMockGate.environmentReady = { error("gate state unreadable") }
        assertFalse("读取出错按关闭", gate())
        DebugMockGate.environmentReady = { true }
        setSwitch(false)
        assertFalse("开关不在关闭", gate())
        assertTrue(DebugMockGate.mockDataAllowed(context))
        DebugMockGate.environmentReady = { false }
        assertFalse("MOCK_DATA 判定同样看环境就绪", DebugMockGate.mockDataAllowed(context))
    }

    @Test
    fun fx02_interceptor_prod_goes_to_real_network_and_waiting_switch_delivers_no_fake_answer() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{"from":"real"},"trace_id":"trace-real-000001"}"""))
        server.start()
        try {
            val stateDir = DebugMockGate.stateDir(context)

            fun client(gate: () -> Boolean) = OkHttpClient.Builder().addInterceptor(DebugFakeLoginServer(stateDir, null, gate)).build()

            fun call(gate: () -> Boolean) =
                client(gate).newCall(
                    Request
                        .Builder()
                        .url(server.url("/v1/devices"))
                        .post("{}".toRequestBody(null))
                        .build(),
                )
            val held = DebugMockGate.of(context)
            // test：本机回答，不连任何地址
            call(held).execute().use { assertTrue(it.body.string().contains("fake-device-0001")) }
            assertEquals(0, server.requestCount)
            // 同一个拦截器闸门、切到 prod：整条交给真实网络
            prod()
            call(held).execute().use { assertTrue(it.body.string().contains("real")) }
            assertEquals(1, server.requestCount)
            // 入口放行、交付前已切到 prod：不交出假回答，也不改发到真实网络
            try {
                call(closesAfterFirst()).execute().close()
                fail("等待期间切到 prod 不得交出假回答")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("gate closed"))
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fx02_contract_ports_and_overrides_obtained_in_test_fall_back_in_prod() {
        val realName = variantRealNameBackend(context)!!
        val labor = variantLaborBackend(context)!!
        val nickname = variantNicknameBackend(context)!!
        val notify = variantNotifyBackend(context)!!
        val push = variantPushTokenSource(context).apply { start() }
        val stepUp = variantStepUpPhone(context)
        val base =
            object : JumpPorts by jumpBase() {
                override fun installed(platform: String): String = "system"
            }
        val jump = variantJumpPorts(context, base)
        File(DebugMockGate.stateDir(context), "couli_fake_trade_state.txt").writeText("installed.taobao=false\n")
        File(DebugMockGate.stateDir(context), "couli_fake_me_state.txt").writeText("wechat=false\ncs_open=fail\npush_denied_hours=2\n")
        runBlocking {
            assertTrue(realName.submit("测试三", "110101199001011234", "key-1") is ApiResult.Success)
            assertTrue(labor.current() is ApiResult.Success)
            assertTrue(nickname.save("account-A", "新昵称", { true }) is ApiResult.Success)
            assertTrue(notify.get("account-A") { true } is ApiResult.Success)
        }
        assertNotNull(push.current())
        assertNotNull(stepUp("account-A"))
        assertEquals("false", jump.installed("taobao"))
        assertEquals("false", variantWechatInstalled(context))
        assertTrue(variantOpenExternalFails(context))
        assertNotNull(variantPushDeniedAt(context))

        prod()
        runBlocking {
            assertTrue("旧实例 prod 下按断网", realName.submit("测试三", "110101199001011234", "key-2") is ApiResult.TransportFailure)
            assertTrue(labor.current() is ApiResult.TransportFailure)
            assertTrue(labor.sign("v", "sha") is ApiResult.TransportFailure)
            assertTrue(nickname.save("account-A", "新昵称", { true }) is ApiResult.TransportFailure)
            assertTrue(notify.get("account-A") { true } is ApiResult.TransportFailure)
            assertTrue(notify.put("account-A", false) { true } is ApiResult.TransportFailure)
        }
        assertNull("推送令牌同 Release（没有）", push.current())
        assertNull("不编造二次验证号码", stepUp("account-A"))
        assertEquals("安装状态按系统实现", "system", jump.installed("taobao"))
        assertNull(variantWechatInstalled(context))
        assertFalse(variantOpenExternalFails(context))
        assertNull(variantPushDeniedAt(context))

        test()
        assertNotNull("切回 test 恢复", push.current())
        runBlocking { assertTrue(labor.current() is ApiResult.Success) }
    }

    private fun jumpBase(): JumpPorts =
        java.lang.reflect.Proxy.newProxyInstance(JumpPorts::class.java.classLoader, arrayOf(JumpPorts::class.java)) { _, _, _ ->
            error("not used")
        } as JumpPorts

    @Test
    fun fx02_auth_adapters_switching_to_prod_while_waiting_deliver_no_fake_credential() {
        runBlocking {
            val wechat = DebugWechatAdapter(closesAfterFirst(), UnavailableWechatAdapter(), delayMillis = 1)
            assertEquals(ThirdPartyAuthKind.UNAVAILABLE, wechat.authorize("nonce-1").kind)
            val webCode = DebugWebCodeAuthorizer(closesAfterFirst(), waitMillis = 1)
            assertEquals(AuthStepResult.Failed, webCode.authorize("https://auth.example.test", "state-1"))
            // 对照：一直放行时交出演示凭据
            assertEquals(ThirdPartyAuthKind.CREDENTIAL, DebugWechatAdapter({ true }, UnavailableWechatAdapter(), 1).authorize("n").kind)
            assertTrue(DebugWebCodeAuthorizer({ true }, 1).authorize("https://auth.example.test", "s") is AuthStepResult.Credential)
            // 统一闸门的实例：prod 下不可用
            val held = DebugWechatAdapter(DebugMockGate.of(context), UnavailableWechatAdapter(), 1)
            assertTrue(held.adapterAvailable())
            prod()
            assertFalse(held.adapterAvailable())
            assertEquals(ThirdPartyAuthKind.UNAVAILABLE, held.authorize("nonce-2").kind)
        }
    }

    @Test
    fun fx02_update_checker_uses_real_checker_in_prod_and_when_switched_while_waiting() {
        val real = AtomicInteger()
        val checker = { allowed: () -> Boolean ->
            DebugUpdateChecker(DebugUpdateScenarioStore(context), {
                real.incrementAndGet()
                UpdateCheckOutcome.Failure
            }, allowed, latencyMillis = 1)
        }
        runBlocking {
            assertTrue(checker { true }.check() is UpdateCheckOutcome.Success)
            assertEquals(0, real.get())
            assertEquals(UpdateCheckOutcome.Failure, checker { false }.check())
            assertEquals(1, real.get())
            assertEquals("等待期间切到 prod：改走真实检查", UpdateCheckOutcome.Failure, checker(closesAfterFirst()).check())
            assertEquals(2, real.get())
            val held = checker { DebugMockGate.mockDataAllowed(context) }
            prod()
            assertEquals(UpdateCheckOutcome.Failure, held.check())
            assertEquals(3, real.get())
        }
    }

    @Test
    fun fx02_agent_stream_frames_after_switching_to_prod_are_not_delivered() {
        var mock = true
        val timers = mutableListOf<() -> Unit>()
        val stream =
            AgentFakeStream(
                switchOn = { true },
                mockAllowed = { mock },
                fixtures = { name -> if (name == "normal") NDJSON else null },
                state = { mapOf("agent_fixture" to "normal") },
                timer =
                    AgentTimer { _, block ->
                        timers += block
                        {}
                    },
            )
        val chunks = StringBuilder()
        var failures = 0
        var ended = false
        val sink =
            object : AgentStreamSink {
                override fun onHeaders(
                    status: Int,
                    headers: Map<String, String>,
                ) = Unit

                override fun onData(chunk: String) {
                    chunks.append(chunk)
                }

                override fun onEnd() {
                    ended = true
                }

                override fun onFailure() {
                    failures += 1
                }
            }
        stream.sendMessage("s-1", """{"client_msg_id":"m-1"}""", AgentCall("owner-a", false, { true }, { true }), sink)
        timers.removeAt(0)() // headers
        timers.removeAt(0)() // 第一帧
        val delivered = chunks.length
        assertTrue(delivered > 0)
        mock = false // 流进行中切到 prod
        while (timers.isNotEmpty()) timers.removeAt(0)()
        assertEquals("之后的帧一律不交", delivered, chunks.length)
        assertFalse(ended)
        assertEquals("按传输失败结束一次", 1, failures)
    }

    @Test
    fun fx02_fake_home_slow_page_switching_to_prod_while_waiting_returns_offline() {
        var mock = true
        val state = File(context.filesDir, "fuand0714-home-state").apply { writeText("home=slow\n") }
        val home = DebugFakeHome(state, switchOn = { true }, mockAllowed = { mock })
        val call = HomeCall(anonymous = false, expectedAccount = null, allowed = { true }, stillOwned = { true })
        val result =
            runBlocking {
                val page = async { home.getPage("home", call) }
                delay(FLIP_AFTER_MS)
                mock = false
                page.await()
            }
        assertTrue("不交假页面（按断网）", result is ApiResult.TransportFailure)
        mock = true
        state.writeText("home=main\n")
        assertTrue(runBlocking { home.getPage("home", call) } is ApiResult.Success)
    }

    @Test
    fun fx02_launch_params_are_dropped_in_prod() {
        val written = mutableListOf<String>()
        val gate = DebugMockGate.of(context)
        val source = ConformanceSource("http://10.0.2.2:8787/conformance/", setOf("http://10.0.2.2:8787"))
        val capabilities = DebugCapabilities { switcher.current }
        assertTrue(applyDebugClipSample("样例", gate) { written += it })
        assertTrue(conformanceLaunchAllowed("logged_in", source, capabilities))
        prod()
        assertFalse("prod：剪贴板样例丢弃", applyDebugClipSample("样例-2", gate) { written += it })
        assertEquals(listOf("样例"), written)
        assertFalse("prod：一致性页（假定已登录、示例规则）不打开", conformanceLaunchAllowed("logged_in", source, capabilities))
        assertFalse(conformanceLaunchAllowed(null, source, DebugCapabilities { AppEnvironment.TEST }))
    }

    private companion object {
        const val FLIP_AFTER_MS = 100L
        const val NDJSON =
            """{"event":"meta","id":1,"data":{"session_id":"s","run_id":"r","message_id":"m"}}""" + "\n" +
                """{"event":"delta","id":2,"data":{"text":"a"}}""" + "\n" +
                """{"event":"delta","id":3,"data":{"text":"b"}}""" + "\n" +
                """{"event":"done","id":4,"data":{"finish_reason":"stop"}}""" + "\n"
    }
}
