package com.couli.core.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * APP-04 三端共性第 43 条（iOS I-R2-04）：桥调用触发的导航让本容器出栈（nav.open(Home) 清栈、nav.close 关闭本容器），容器在导航过程中
 * 立即拆除（作废调用、销毁宿主）。发起这次导航的调用先按成功结算并回给页面，再拆；其他在途调用照常作废（90500）。
 */
class BridgeHostNavigationTeardownTest {
    private val replies = mutableMapOf<String, Int>()
    private val userGate = CompletableDeferred<BridgeOutcome>()
    private var otherValidity: BridgeCallValidity? = null
    private lateinit var host: BridgeHost

    /** 本容器出栈：容器拆除时作废全部调用、销毁宿主（与 WebContainerHolder.teardown 的效果一致）。 */
    private fun containerPopped() = host.dispose()

    private val services =
        object : BridgeServices {
            override fun env() = JsonObject(emptyMap())

            override fun config(keys: List<String>) = BridgeOutcome.Ok()

            override suspend fun user(validity: BridgeCallValidity): BridgeOutcome {
                otherValidity = validity
                return userGate.await()
            }

            override suspend fun h5Token(
                readOnly: Boolean,
                validity: BridgeCallValidity,
            ) = BridgeOutcome.Ok()

            override suspend fun login(update: String) = BridgeOutcome.Ok()

            override suspend fun signedRequest(
                method: String,
                path: String,
                body: JsonElement?,
                validity: BridgeCallValidity,
            ) = BridgeOutcome.Ok()

            override suspend fun navOpen(
                route: String,
                params: JsonObject,
                update: String,
            ): BridgeOutcome {
                // Router 推 Home 并清栈：本容器在这次调用返回之前就被拆除
                containerPopped()
                return BridgeOutcome.Ok(JsonObject(mapOf("opened" to JsonPrimitive(true))))
            }

            override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

            override suspend fun clipboardRead() = BridgeOutcome.Ok()

            override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

            override suspend fun pushStatus() = BridgeOutcome.Ok()

            override suspend fun requestPermission(
                type: String,
                validity: BridgeCallValidity,
            ) = BridgeOutcome.Ok()

            override fun track(
                name: String,
                props: JsonObject,
            ) = BridgeOutcome.Ok()

            override fun reportRejected(
                method: String,
                code: Int,
            ) = Unit
        }

    private val ui =
        object : BridgeUi {
            override fun toast(
                text: String,
                long: Boolean,
            ) = Unit

            override fun showLoading(text: String?) = Unit

            override fun hideLoading() = Unit

            override fun setNavBar(params: JsonObject) = true

            override fun close() = containerPopped()
        }

    private fun newHost() =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
            caller = { BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-a", update = BridgeUpdateState.SUPPORTED) },
            services = services,
            ui = ui,
            awaitUpdate = { BridgeUpdateState.SUPPORTED },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).also { host = it }

    private fun call(
        id: String,
        method: String,
        params: String = "{}",
    ) = host.handle("""{"v":1,"id":"$id","method":"$method","params":$params}""", ORIGIN, true, false) { reply ->
        replies[id] =
            Json
                .parseToJsonElement(reply)
                .jsonObject
                .getValue("code")
                .jsonPrimitive.int
    }

    @Test
    fun navOpenThatPopsThisContainerIsSettledAsSuccessAndOtherCallsAreInvalidated() {
        newHost()
        call("other", "auth.getUser")
        call("nav", "nav.open", """{"route":"Home"}""")

        assertEquals("the navigating call is answered with success", 0, replies["nav"])
        assertEquals("the other in-flight call is invalidated", 90500, replies["other"])
        assertFalse(checkNotNull(otherValidity).stillValid())
    }

    @Test
    fun navCloseIsSettledAsSuccessBeforeTheContainerIsTornDown() {
        newHost()
        call("close", "nav.close")
        assertEquals(0, replies["close"])
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
    }
}
