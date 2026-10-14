package com.couli.feature.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentContractPort
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentDeps
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentIdentity
import com.couli.core.agent.AgentSse
import com.couli.core.agent.AgentStreamHandle
import com.couli.core.agent.AgentStreamSink
import com.couli.core.agent.AgentTimer
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.network.CallEpochs
import com.couli.core.router.RouteRequest
import com.couli.core.wallet.WalletRateGate
import com.couli.generated.DefaultTexts
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * feature:agent 界面测试的替身：真实 AgentController + StreamClient + Reducer，待定 operation 端口按锁定契约的 fixture（原样，从仓库根读）以 SSE 文本下发，
 * 计时器手动推进；商品卡映射与 App 同一个 ProductCardMapper（平台名等草稿只为截图可读，照 App 的 SearchDraftTexts 抄几条）。不连网、不落盘。
 */
internal class AgentUiKit {
    var now: Long = OffsetDateTime.parse("2026-10-06T10:00:00+08:00").toInstant().toEpochMilli()
    val scope = CoroutineScope(Dispatchers.Main.immediate)
    val timer = Timer()
    val gate = WalletRateGate(scope) { now }
    val port = Port()
    val mask = MutableStateFlow(false)
    val opens = mutableListOf<RouteRequest>()
    val toasts = mutableListOf<String>()
    var identity: AgentIdentity = AgentIdentity("test", AgentIdentity.MEMBER, "user-a", 1, CallEpochs(0, 0), 0, 1)
    var examples: List<String> = listOf("帮我找 500 元以内的降噪耳机", "这个链接有返利吗")
    var filing: String = "生成式人工智能服务已备案（示例）"
    var consentReply: AgentHttpResult = AgentHttpResult(true, 200, 0, "ok", JsonObject(emptyMap()), "trace", null)

    fun controller(contract: AgentContractPort? = port): AgentController =
        AgentController(
            AgentDeps(
                identity = identity,
                currentIdentity = { identity },
                contract = contract,
                gate = gate,
                scope = scope,
                timer = timer,
                clock = { now },
                masked = mask,
                open = { opens += it },
                recordConsent = { consentReply },
                toast = { toasts += it },
                examples = { examples },
                filingText = { filing },
            ),
        ).also {
            it.setVisible(true)
            it.start()
        }

    fun ui(
        memory: AgentPageMemory = AgentPageMemory(),
        reader: Boolean = false,
    ): AgentPageUi =
        AgentPageUi(
            mapCard = { ProductCardMapper.map(it, CARD_TEXTS, nowMillis = now, purchaseStatus = "on") },
            screenReader = MutableStateFlow(reader),
            memory = memory,
            paste = { "https://item.jd.com/100012043978.html" },
        )

    /** 发一条消息，并把 fixture 全部按 SSE 下发（[upTo] 行之后停住）。 */
    fun play(
        c: AgentController,
        fixture: String,
        text: String = "帮我找纯牛奶，24 盒装",
        upTo: Int = Int.MAX_VALUE,
        end: Boolean = true,
    ) = playLines(c, fixtureLines(fixture).take(upTo), text, end)

    fun playLines(
        c: AgentController,
        lines: List<String>,
        text: String = "帮我找纯牛奶，24 盒装",
        end: Boolean = true,
    ) {
        check(c.send(c.snapshot.value.page, text)) { "没有受理" }
        val s = port.streams.last()
        s.sink.onHeaders(200, mapOf("Content-Type" to "text/event-stream"))
        lines.forEach { s.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) }
        if (end) s.sink.onEnd()
    }

    fun json(
        c: AgentController,
        code: Int,
        status: Int = 422,
        text: String = "今天还能问吗",
    ) {
        check(c.send(c.snapshot.value.page, text))
        val s = port.streams.last()
        s.sink.onHeaders(status, mapOf("Content-Type" to "application/json"))
        s.sink.onData("""{"code":$code,"msg":"m","data":{},"trace_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"}""")
        s.sink.onEnd()
    }

    fun advance(ms: Long) {
        now += ms
        timer.fire()
    }

    class Timer : AgentTimer {
        private val pending = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        var clock: () -> Long = { 0 }

        override fun after(
            delayMs: Long,
            block: () -> Unit,
        ): () -> Unit {
            val cancelled = BooleanArray(1)
            pending += Triple(clock() + delayMs, block, cancelled)
            return { cancelled[0] = true }
        }

        fun fire() {
            while (true) {
                val due = pending.firstOrNull { !it.third[0] && it.first <= clock() } ?: return
                pending -= due
                due.second()
            }
        }
    }

    init {
        timer.clock = { now }
    }

    class Stream(
        val sink: AgentStreamSink,
    ) : AgentStreamHandle {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    inner class Port : AgentContractPort {
        val streams = mutableListOf<Stream>()
        var current: AgentHttpResult = ok(JsonObject(mapOf("session" to JsonNull)))

        override suspend fun createSession(call: AgentCall): AgentHttpResult =
            if (call.sendAllowed()) {
                ok(
                    JsonObject(mapOf("session" to JsonObject(mapOf("id" to JsonPrimitive("s1"))))),
                )
            } else {
                AgentHttpResult.notSentResult()
            }

        override suspend fun currentSession(call: AgentCall): AgentHttpResult =
            if (call.sendAllowed()) current else AgentHttpResult.notSentResult()

        override fun sendMessage(
            sessionId: String,
            body: String,
            call: AgentCall,
            sink: AgentStreamSink,
        ): AgentStreamHandle = Stream(sink).also { if (call.sendAllowed()) streams += it }

        override suspend fun cancelRun(
            runId: String,
            call: AgentCall,
        ): AgentHttpResult = ok(JsonObject(emptyMap()))

        override fun cancelOwned(identityKey: Any) {
            streams.forEach { it.cancelled = true }
        }
    }

    companion object {
        fun ok(data: JsonObject): AgentHttpResult = AgentHttpResult(true, 200, 0, "ok", data, "trace", null)

        private val root: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
            generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        }

        fun fixtureLines(name: String): List<String> =
            File(root, "contracts/snapshot/contracts/fixtures/agent-streams/$name.ndjson").readText().split('\n').filter { it.isNotBlank() }

        /** 截图可读用的卡片文案（照 App 的 SearchDraftTexts 抄；契约字典优先）。 */
        private val DRAFTS =
            mapOf(
                "platform.taobao" to "淘宝",
                "platform.jd" to "京东",
                "platform.pdd" to "拼多多",
                "card.disclaimer_button" to "价格与返利说明",
                "card.image_placeholder" to "商品图",
                "card.price_unavailable" to "暂时查不到该商品价格",
                "card.price_stale" to "价格可能已变化，以下单页为准",
                "card.retry_later" to "稍后再试",
            )
        val CARD_TEXTS = ProductCardTexts { key -> DRAFTS[key] ?: DefaultTexts.text(key) ?: DefaultTexts.fallback(key).orEmpty() }

        val images =
            ProductImageLoader { url ->
                if (!url.startsWith("https://")) {
                    null
                } else {
                    val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
                    val hue = (url.hashCode() and 0xff) / 255f * 360f
                    Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.9f)))
                    bitmap.asImageBitmap()
                }
            }

        /** 构造样例（向量里的构造形态，标「构造」，不冒充契约 fixture）：改 normal 第一张 product_list 卡的第一件商品。 */
        fun constructed(name: String): List<String> {
            var done = false
            return fixtureLines("normal").map { line ->
                if (done || !line.contains("\"type\":\"product_list\"")) return@map line
                done = true
                when (name) {
                    "login_required" -> {
                        line
                            .replaceFirst("\"rebate_min_fen\":200", "\"rebate_min_fen\":null")
                            .replaceFirst("\"rebate_max_fen\":200", "\"rebate_max_fen\":null")
                            .replaceFirst("\"rebate_basis\":\"normal\"", "\"rebate_basis\":\"login_required\"")
                    }

                    "price_compare_risk" -> {
                        line
                            .replaceFirst("\"rebate_max_fen\":200", "\"rebate_max_fen\":260")
                            .replaceFirst("\"rebate_basis\":\"normal\"", "\"rebate_basis\":\"price_compare_risk\"")
                    }

                    "relaxed" -> {
                        line.replaceFirst("\"match_tag\":\"matched\"", "\"match_tag\":\"relaxed\"")
                    }

                    "spec_unconfirmed" -> {
                        line
                            .replaceFirst(
                                "\"match_tag\":\"matched\"",
                                "\"match_tag\":\"spec_unconfirmed\"",
                            ).replaceFirst(Regex("\"spec_text\":\"[^\"]*\""), "\"spec_text\":null")
                    }

                    "grid" -> {
                        line.replace("\"layout\":\"list\"", "\"layout\":\"grid\"")
                    }

                    "off_shelf" -> {
                        line.replaceFirst("\"availability\":\"ok\"", "\"availability\":\"off_shelf\"")
                    }

                    "coupon_gone" -> {
                        line.replaceFirst("\"availability\":\"ok\"", "\"availability\":\"coupon_gone\"")
                    }

                    else -> {
                        line
                    }
                }
            }
        }
    }
}
