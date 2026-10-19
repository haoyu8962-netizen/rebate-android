@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.couli.app.routing.AppRouting
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.SecretCipher
import com.couli.core.router.GuardDecision
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 只替换系统 I/O、网络、时钟；装配/调度/门控/去重/呈现都在真实 AppClipboard 中。 */
internal class Clipboard0724Fixture(
    val test: TestScope,
    enabled: Boolean? = true,
    lifecycle: Lifecycle? = null,
    cipher: SecretCipher = SampleCipher,
) : AutoCloseable {
    // 和 TestScope 共用虚拟调度器；独立持有 Job，close 后结束来源订阅，避免 runTest 等待常驻 collector。
    private val clipboardJob = Job(test.coroutineContext[Job])
    private val clipboardScope = CoroutineScope(test.coroutineContext + clipboardJob)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferenceName = "clipboard-0724-${UUID.randomUUID()}"
    val disk = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    var hashReads = 0
    private val observedDisk =
        object : SharedPreferences by disk {
            override fun getString(
                key: String?,
                defValue: String?,
            ): String? {
                if (key?.startsWith("hash.") == true) hashReads += 1
                return disk.getString(key, defValue)
            }
        }
    val prefs = ClipboardPreferences(observedDisk, cipher)
    val preferences = prefs.importPreferences({ now })
    var now = 1_800_000_000_000L
    var privacy = PrivacyState.CONSENTED
    var sessionEpoch = 0L
    var config = json("""{"clipboard":{"consent_version":"1"},"share_domains":["share.example.test"]}""")
    val system = CountingClipboard0724()
    val requests = mutableListOf<ApiCall>()
    val navigations = mutableListOf<Pair<String, JsonObject>>()
    val held = ArrayDeque<CompletableDeferred<ApiResult>>()
    private val awaitingReplies = mutableSetOf<CompletableDeferred<ApiResult>>()
    var response: ApiResult = success()
    var atSend: (ApiCall) -> Unit = {}
    val api =
        object : PageApi {
            override suspend fun send(call: ApiCall): ApiResult {
                requests += call // 在端口入口、任何挂起和错误之前同步记录；不等传输层。
                atSend(call)
                val reply = held.removeFirstOrNull() ?: return response
                awaitingReplies += reply
                return try {
                    reply.await()
                } finally {
                    awaitingReplies -= reply
                }
            }

            override fun cancelOwned(owner: CallOwnership) = Unit // 不让替身替产品丢掉迟到响应。

            override fun epochs() = CallEpochs(0, 0)
        }
    val routing =
        AppRouting(
            context,
            EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
            clock = { now },
        )
    val clipboard: AppClipboard
    val process = TestProcessOwner()

    class TestProcessOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    init {
        if (enabled != null) {
            assertTrue(preferences.enable("1"))
            if (!enabled) assertTrue(preferences.setOff())
        }
        routing.attach(
            object : RouteNavigator {
                override fun open(
                    route: String,
                    params: JsonObject,
                    readonly: Boolean,
                ) {
                    navigations += route to params
                }

                override fun showUpgradePrompt(request: RouteRequest) = Unit

                override fun requestConsent(request: RouteRequest) = Unit

                override fun blocked(
                    request: RouteRequest,
                    decision: GuardDecision,
                ) = Unit
            },
        )
        clipboard =
            AppClipboard(
                system = system,
                api = api,
                routing = routing,
                privacy = { privacy },
                promptShowing = { false },
                configData = { config },
                prefs = prefs,
                importPreferences = preferences,
                sessionEpoch = { sessionEpoch },
                clock = { now },
                randomSalt = { SAMPLE_SALT.copyOf() },
                workScope = clipboardScope,
            )
        // 生产绑定函数必须同时供 AppModule 使用；测试不得在这里翻译 ON_START/ON_STOP。
        clipboard.bindProcessLifecycle(lifecycle ?: process.lifecycle)
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        clipboard.onShellAttached()
        clipboard.onShell("Home", false)
        clipboard.onBackStack(null, null, emptySet())
        clipboard.onWindowFocus(true)
    }

    fun settle() = test.advanceUntilIdle()

    fun foreground() {
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        settle()
    }

    fun background() {
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        settle()
    }

    fun nextRound(text: String = LINK) {
        background()
        system.content = ClipRead.Text(text)
        foreground()
    }

    fun parseBodies(): List<JsonObject> = requests.filter { it.operationId == "parseInput" }.map { json(checkNotNull(it.body)) }

    fun card(): ClipboardResult.CardList {
        val result = clipboard.result.value
        assertTrue("必须在当前页显示商品卡，实际=$result", result is ClipboardResult.CardList)
        assertEquals(clipboard.currentOrigin(), result?.origin)
        return result as ClipboardResult.CardList
    }

    fun noEffects(reads: Int = 0) {
        settle()
        val observed = Triple(system.reads, parseBodies().size, clipboard.result.value)
        assertEquals(reads, observed.first)
        assertEquals(0, observed.second)
        assertEquals(null, observed.third)
        assertEquals(0, system.writes)
    }

    override fun close() {
        held.forEach { it.cancel() }
        awaitingReplies.toList().forEach { it.cancel() }
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        clipboardJob.cancel()
        context.deleteSharedPreferences(preferenceName)
    }

    companion object {
        const val LINK = "https://e.tb.cn/h.AbCdEf?tk=Xy12"
        const val DAY = 86_400_000L

        fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

        fun success(key: String = "tb:0724") =
            ApiResult.Success(
                json(
                    """
                    {
                      "results": [
                        {
                          "hit": {
                            "platform": "taobao",
                            "kind": "url",
                            "raw": "$LINK"
                          },
                          "card": {
                            "product_key": "$key",
                            "item_ref": "v1.ref.sig",
                            "platform": "taobao",
                            "title": "0724商品",
                            "price_fen": 3990,
                            "final_price_fen": 2990,
                            "coupon_fen": 1000,
                            "rebate_min_fen": 269,
                            "rebate_max_fen": 269,
                            "rebate_basis": "normal",
                            "link_id": "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",
                            "availability": "ok"
                          }
                        }
                      ]
                    }
                    """,
                ),
                "trace-0724",
                null,
            )
    }
}

internal class CountingClipboard0724 : SystemClipboard {
    var content: ClipRead = ClipRead.Text(Clipboard0724Fixture.LINK)
    var failure: (() -> Throwable?)? = null
    var afterRead: () -> Unit = {}
    var reads = 0
    var writes = 0

    override fun read(): ClipRead {
        reads += 1
        failure?.invoke()?.let { throw it }
        val value = content
        afterRead()
        return value
    }

    override fun write(
        text: String,
        label: String,
    ): Boolean {
        writes += 1
        content = ClipRead.Text(text)
        return true
    }
}
