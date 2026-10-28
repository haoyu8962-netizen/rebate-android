package com.couli.core.bridge

import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I1 的负向观察在真实 BridgeProtocol 和 BridgeHost；不读测试夹具的 allowed 布尔值冒充准入结果。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fx25BridgeAdmissionTest {
    @Test
    fun i1ProtocolRejectsEvenReadOnlyAndTokenMethodsWhileDocumentIsIsolated() {
        val protocol = BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet())
        for (method in listOf("app.getEnv", "auth.getUser", "auth.getH5Token", "clipboard.read")) {
            val context =
                BridgeContext(
                    method,
                    ORIGIN,
                    true,
                    true,
                    BridgePrivacy.CONSENTED,
                    true,
                    JsonObject(emptyMap()),
                    documentIsolated = true,
                )
            val decision = protocol.decide(context)
            assertFalse("$method 不得绕过文档隔离", decision.allow)
            assertTrue(decision.errorCode in setOf(BridgeCode.NATIVE_ERROR, BridgeCode.NOT_LOGGED_IN))
            assertTrue("正对照保证请求自身有效", protocol.decide(context.copy(documentIsolated = false)).allow)
        }
    }

    @Test
    fun i1HostNeverEntersHandlerAfterIsolationEvenIfCallerAlreadyHasNewAccount() =
        runTest {
            val entered = mutableListOf<String>()
            val services =
                Proxy.newProxyInstance(BridgeServices::class.java.classLoader, arrayOf(BridgeServices::class.java)) { _, method, _ ->
                    if (method.name != "reportRejected") entered += method.name
                    when (method.name) {
                        "env" -> JsonObject(emptyMap())
                        "reportRejected" -> Unit
                        else -> BridgeOutcome.Ok()
                    }
                } as BridgeServices
            val ui =
                Proxy.newProxyInstance(BridgeUi::class.java.classLoader, arrayOf(BridgeUi::class.java)) { _, _, _ ->
                    error("隔离后不能进入 UI")
                } as BridgeUi
            val host =
                BridgeHost(
                    { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
                    { BridgeCallerState(BridgePrivacy.CONSENTED, "user-b", BridgeUpdateState.SUPPORTED, 2L) },
                    services,
                    ui,
                    { BridgeUpdateState.SUPPORTED },
                    this,
                )
            try {
                host.setDocumentIsolated(true)
                val replies = mutableListOf<String>()
                for (method in listOf("app.getEnv", "auth.getUser", "auth.getH5Token")) {
                    host.handle("""{"v":1,"id":"$method","method":"$method","params":{}}""", ORIGIN, true, true) { replies += it }
                }
                runCurrent()
                assertEquals("先等真实宿主结算，不能在协程没运行时断言零", 3, replies.size)
                assertTrue(entered.isEmpty())
                replies.forEach {
                    assertTrue(
                        Json
                            .parseToJsonElement(it)
                            .jsonObject
                            .getValue("code")
                            .jsonPrimitive.int in setOf(90500, 90401),
                    )
                }
                host.setDocumentIsolated(false)
                host.handle("""{"v":1,"id":"new","method":"app.getEnv","params":{}}""", ORIGIN, true, true) { replies += it }
                runCurrent()
                assertEquals(listOf("env"), entered)
                assertEquals(
                    0,
                    Json
                        .parseToJsonElement(replies.last())
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.int,
                )
            } finally {
                host.dispose()
            }
        }

    /**
     * S2-01（评审 r1）：准入读到「未隔离」之后、在途登记之前另一线程进入隔离（invalidate 已过）。这条调用的结果在结算时也必须复核隔离位，
     * 不得按新会话交回旧文档。用协议提供者在准入读取隔离位之后、判定之前同步进入隔离，确定性地重放这一窗口。
     */
    @Test
    fun i1SettlementRechecksIsolationForCallAdmittedJustBeforeIsolation() =
        runTest {
            val entered = mutableListOf<String>()
            val services =
                Proxy.newProxyInstance(BridgeServices::class.java.classLoader, arrayOf(BridgeServices::class.java)) { _, method, _ ->
                    if (method.name != "reportRejected") entered += method.name
                    when (method.name) {
                        "env" -> JsonObject(emptyMap())
                        "reportRejected" -> Unit
                        else -> BridgeOutcome.Ok()
                    }
                } as BridgeServices
            val ui =
                Proxy.newProxyInstance(BridgeUi::class.java.classLoader, arrayOf(BridgeUi::class.java)) { _, _, _ ->
                    error("不进入 UI")
                } as BridgeUi
            var host: BridgeHost? = null
            var raceArmed = false
            host =
                BridgeHost(
                    {
                        // 准入已读过隔离位（false）：此刻另一线程完成判定并进入隔离
                        if (raceArmed) {
                            raceArmed = false
                            checkNotNull(host).setDocumentIsolated(true)
                        }
                        BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet())
                    },
                    { BridgeCallerState(BridgePrivacy.CONSENTED, "user-b", BridgeUpdateState.SUPPORTED, 2L) },
                    services,
                    ui,
                    { BridgeUpdateState.SUPPORTED },
                    this,
                )
            try {
                val replies = mutableListOf<String>()
                raceArmed = true
                checkNotNull(host).handle("""{"v":1,"id":"race","method":"app.getEnv","params":{}}""", ORIGIN, true, true) { replies += it }
                runCurrent()
                assertTrue("正对照：确实重放到了准入之后的窗口", !raceArmed)
                assertEquals("先等真实宿主结算", 1, replies.size)
                assertTrue(
                    "隔离后结算的结果不得交回旧文档：$replies",
                    Json
                        .parseToJsonElement(replies.single())
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.int in setOf(90500, 90401),
                )
            } finally {
                host.dispose()
            }
        }

    private companion object {
        const val ORIGIN = "https://fx25.test"
    }
}
