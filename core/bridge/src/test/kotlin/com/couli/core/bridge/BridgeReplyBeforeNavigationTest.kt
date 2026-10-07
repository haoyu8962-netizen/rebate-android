package com.couli.core.bridge

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-04 共性第 59、60 条（Android 在 APP-05a 核对：原先 nav.open / nav.close 在调用里当场导航、回复之后才交出，不成立，已修）：
 * - 会导航的调用先交出回复，等传输层确认回复脚本在页面里执行完（deliver 的 executed 回调）之后才导航；
 * - 等待期间页面被作废（换文档、撤回同意、切换环境、容器出栈）、会话或隐私变了：不再导航；
 * - 导航动作本身由能力层用执行那一刻的状态重新过 Router 守卫（见 app 的 BridgeNavOpenRoutingTest）。
 */
class BridgeReplyBeforeNavigationTest {
    private val replies = mutableMapOf<String, Int>()
    private val navigations = mutableListOf<String>()
    private var pendingExecuted: (() -> Unit)? = null

    /** 传输层的结束回调（true = 已执行，false = 超时 / 没有执行）。 */
    private var pendingSettled: ((Boolean) -> Unit)? = null
    private var account = "account-a"
    private var privacy = BridgePrivacy.CONSENTED

    private val services =
        object : BridgeServices by NoServices {
            override suspend fun navOpen(
                route: String,
                params: JsonObject,
                update: String,
            ) = BridgeOutcome.Ok(JsonObject(mapOf("opened" to JsonPrimitive(true))), afterReply = { navigations += "open:$route" })
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

            override fun close() {
                navigations += "close"
            }
        }

    private val host =
        BridgeHost(
            protocol = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
            caller = { BridgeCallerState(privacy, accountKey = account, update = BridgeUpdateState.SUPPORTED) },
            services = services,
            ui = ui,
            awaitUpdate = { BridgeUpdateState.SUPPORTED },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    /** 传输层替身：交出回复，executed 留到测试里「脚本执行完」时再调。 */
    private fun call(
        id: String,
        method: String,
        params: String = "{}",
    ) = host.handle(
        """{"v":1,"id":"$id","method":"$method","params":$params}""",
        ORIGIN,
        true,
        false,
        deliver = { text, settled ->
            record(id, text)
            pendingSettled = settled
            pendingExecuted = { settled(true) }
        },
    ) { record(id, it) }

    private fun record(
        id: String,
        text: String,
    ) {
        replies[id] =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("code")
                .jsonPrimitive.int
    }

    @Test
    fun navOpenNavigatesOnlyAfterTheReplyHasRunInThePage() {
        call("n1", "nav.open", """{"route":"Home"}""")
        assertEquals("回复已交出", 0, replies["n1"])
        assertTrue("回复脚本还没执行完：不导航", navigations.isEmpty())
        checkNotNull(pendingExecuted).invoke()
        assertEquals(listOf("open:Home"), navigations)
    }

    @Test
    fun navCloseClosesTheContainerOnlyAfterTheReplyHasRun() {
        call("c1", "nav.close")
        assertEquals(0, replies["c1"])
        assertTrue(navigations.isEmpty())
        checkNotNull(pendingExecuted).invoke()
        assertEquals(listOf("close"), navigations)
    }

    @Test
    fun aPageInvalidatedWhileTheReplyRunsDoesNotNavigate() {
        call("n2", "nav.open", """{"route":"Home"}""")
        host.invalidate()
        checkNotNull(pendingExecuted).invoke()
        assertTrue("页面已作废：不再导航", navigations.isEmpty())
    }

    @Test
    fun aSessionOrPrivacyChangeWhileTheReplyRunsDoesNotNavigate() {
        call("n3", "nav.open", """{"route":"Home"}""")
        account = "account-b"
        checkNotNull(pendingExecuted).invoke()
        assertTrue("会话已换：不再导航", navigations.isEmpty())

        account = "account-a"
        call("n4", "nav.open", """{"route":"Home"}""")
        privacy = BridgePrivacy.BASIC_MODE
        checkNotNull(pendingExecuted).invoke()
        assertFalse("隐私已变：不再导航", navigations.isNotEmpty())
    }

    /**
     * APP-04 共性第 62 条（Android 第 1 轮评审 R1-05）：nav.close / 会清栈的 nav.open 的回复脚本迟迟没有执行，传输层等待超时按
     * 「没有执行」结束——放弃这次导航，不关闭容器；之后迟到的执行确认也不再导航。
     */
    @Test
    fun aReplyThatTimesOutWithoutRunningAbandonsTheNavigation() {
        call("t1", "nav.close")
        checkNotNull(pendingSettled).invoke(false)
        assertTrue("回复没有执行：不关闭容器", navigations.isEmpty())
        checkNotNull(pendingExecuted).invoke()
        assertTrue("超时之后迟到的确认不算数", navigations.isEmpty())

        call("t2", "nav.open", """{"route":"Home"}""")
        checkNotNull(pendingSettled).invoke(false)
        assertTrue("回复没有执行：不导航", navigations.isEmpty())
    }

    @Test
    fun theExecutedConfirmationNavigatesAtMostOnce() {
        call("n5", "nav.open", """{"route":"Home"}""")
        val executed = checkNotNull(pendingExecuted)
        executed()
        executed()
        assertEquals(1, navigations.size)
    }

    private object NoServices : BridgeServices {
        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity) = BridgeOutcome.Ok()

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
        ) = BridgeOutcome.Ok()

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

    private companion object {
        const val ORIGIN = "https://h5.example.test"
    }
}
