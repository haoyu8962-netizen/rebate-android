package com.couli.core.network

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals

/** 沿用原夹具的真实屏障、发布窗口、计数存储与 LocalExchange 写出后接点。 */
internal class BarrierNotReadableFix8Fixture {
    val stored = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, BarrierNotReadableFixture.ACCOUNT)
    val control = BarrierNotReadableFixture(stored)
    val tokens = BarrierNotReadableFix8Tokens(control.tokens)
    val backend = MemoryPendingKeyBackend()
    val pending = PendingKeyStore(backend) { 0L }
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val events: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val effects: MutableList<ErrorEffect> = Collections.synchronizedList(mutableListOf())
    var answer: (Request) -> Pair<Int, String> = { 0 to "{}" }
    var afterSaved: () -> Unit = {}
    val api =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.2.0", "1", "official"),
            baseUrl = { "https://barrier-not-readable.invalid/" },
            context = { control.state },
            device =
                object : DeviceCredentials {
                    override val deviceId: String? get() = read("device-fixture")
                    override val installSecret: String? get() = read("test-only.install.device-a")

                    private fun read(value: String): String? = control.barrier.read({ true }, { null }) { value }
                },
            tokens = tokens,
            pendingKeys = pending,
            hooks = NetworkHooks(onSessionStored = { events += it }, onEffect = { effects += it }),
            sleeper = {},
            inFlight = control.inFlight,
            readBarrier = control.barrier,
            local =
                object : LocalExchange {
                    override fun handles(request: Request): Boolean = true

                    override fun answer(
                        request: Request,
                        canceled: () -> Boolean,
                    ): Response {
                        requests += request
                        val (code, data) = answer(request)
                        return Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"code":$code,"msg":"","data":$data,"trace_id":"fix8"}""".toResponseBody(null))
                            .build()
                    }
                },
        )

    fun login(): ApiCall =
        ApiCall(
            "loginBySms",
            body = """{"phone":"13800000000","code":"123456"}""",
            ownership = CallOwnership(control.inFlight.epochs(), stillOwned = { true }, onSessionSaved = { afterSaved() }),
        )

    companion object {
        const val PAIR = """{"access_token":"access-new","refresh_token":"refresh-new","session_scope":"full"}"""
        const val LOGIN = """{"user_id":"account-B","tokens":$PAIR}"""
    }
}

internal class BarrierNotReadableFix8Tokens(
    private val backing: BarrierNotReadableTokens,
) : SessionTokens by backing,
    SessionReader,
    SessionSelfDiscards {
    val generationReads = AtomicInteger()
    val loginReads = AtomicInteger()
    val clearAttempts = AtomicInteger()
    val saveAttempts = AtomicInteger()
    var beforeClear: () -> Unit = {}
    var rejectClear = false
    var beforeReplace: () -> Unit = {}
    var discarded: () -> Unit = {}

    override fun onSelfDiscarded(listener: () -> Unit) {
        discarded = listener
    }

    override fun readSession(): SessionRead = backing.readSession()

    override val generation: Long
        get() {
            generationReads.incrementAndGet()
            return backing.generation
        }
    override val loginEpoch: Long
        get() {
            loginReads.incrementAndGet()
            return backing.loginEpoch
        }

    override fun clearIf(expectedGeneration: Long): Boolean {
        clearAttempts.incrementAndGet()
        beforeClear()
        return !rejectClear && backing.clearIf(expectedGeneration)
    }

    override fun replaceIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
    ): Boolean {
        beforeReplace()
        return backing.replaceIf(expectedGeneration, accessToken, refreshToken, scope)
    }

    override fun saveIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ): Boolean {
        saveAttempts.incrementAndGet()
        return backing.saveIf(expectedGeneration, accessToken, refreshToken, scope, userId)
    }

    fun readCounts(): List<Int> = listOf(generationReads.get(), loginReads.get())

    fun assertNoEpochReads(before: List<Int>) {
        assertEquals("窗口内 generation / loginEpoch 读取增量必须都是零", listOf(0, 0), readCounts().zip(before) { a, b -> a - b })
    }
}
