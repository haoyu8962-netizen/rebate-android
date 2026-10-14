package com.couli.core.agent

import com.couli.core.agent.AgentTestKit.Companion.META
import com.couli.core.agent.AgentTestKit.Companion.frameLine
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 第 1 轮评审 R1-04、R1-05（03 §7.2、02 §9.2「输出上限」）：单帧 data 64 KiB、单轮文本 20000 码点、卡片 12 张、内容帧 400 帧；超限停止累积、
 * 关连接、结束本轮。同一轮里同 card_id 后到的卡丢弃、保留第一张。跨分片（含多字节字符切在块边界）与大量小帧各一组边界。
 */
class AgentStreamLimitsTest {
    private val kit = AgentTestKit()

    private fun reduce(lines: List<String>): AgentStreamState =
        lines.fold(StreamReducer.initial()) { s, line -> StreamReducer.frame(s, AgentFrameDecoder.frame(AgentSse.fromNdjsonLine(line))) }

    private fun delta(
        id: Int,
        text: String,
    ): String = frameLine("text.delta", id, """{"seq":$id,"delta":"$text"}""")

    private fun guide(
        id: Int,
        cardId: String,
        route: String = "WithdrawRecords",
    ): String =
        frameLine(
            "card",
            id,
            """{"seq":$id,"card_id":"$cardId","type":"page_guide","schema_version":1,"data":{"route":"$route",""" +
                """"text_key":"agent.guide.$route"},"fallback_text":"看看$route。"}""",
        )

    // ---------------------------------------------------------------- R1-04 单帧 64 KiB（AgentSse）

    @Test
    fun r1_04_frameOfExactly64KiBSplitAcrossChunksStillParses() {
        val sse = AgentSse()
        // 21845 个「汉」（3 字节）+ 1 个 a = 65536 字节；按 7 个字符一块切开，多字节字符不会被拆成字节，但块边界与字段行边界都错开
        val data = "汉".repeat(21_845) + "a"
        val text = "event: text.delta\nid: 2\ndata: $data\n\n"
        val items = text.chunked(7).flatMap(sse::feed)
        assertEquals(1, items.size)
        assertEquals(AgentSse.KIND_FRAME, AgentJson.stringOf(items.single()["kindRaw"]))
        assertEquals(data, AgentJson.stringOf(items.single()["dataText"]))
        assertFalse(sse.overflowed)
    }

    @Test
    fun r1_04_frameOneByteOver64KiBAcrossChunksStopsParsing() {
        val sse = AgentSse()
        val data = "汉".repeat(21_845) + "ab"
        val text = "event: text.delta\nid: 2\ndata: $data\n\n"
        val items = text.chunked(1_000).flatMap(sse::feed)
        assertEquals(listOf(AgentSse.KIND_TOO_LARGE), items.map { AgentJson.stringOf(it["kindRaw"]) })
        assertTrue(sse.overflowed)
        // 之后的块一律丢弃（不再累积，也不再交出任何帧）
        assertTrue(sse.feed("event: text.delta\nid: 3\ndata: {}\n\n").isEmpty())
    }

    @Test
    fun r1_04_multiLineDataCountsJoinedBytesAndHalfLineIsBounded() {
        // 多行 data 按 \n 连接后计：两行各 32768 字节 + 1 个换行 = 65537 → 超限
        val sse = AgentSse()
        val half = "a".repeat(32_768)
        val items = sse.feed("event: text.delta\nid: 2\ndata: $half\ndata: $half\n\n")
        assertEquals(listOf(AgentSse.KIND_TOO_LARGE), items.map { AgentJson.stringOf(it["kindRaw"]) })
        // 一直不换行的半行：超过上限即截停（不等换行）
        val open = AgentSse()
        val out = mutableListOf<JsonObject>()
        repeat(80) { out += open.feed("x".repeat(1_000)) }
        assertEquals(listOf(AgentSse.KIND_TOO_LARGE), out.map { AgentJson.stringOf(it["kindRaw"]) })
        assertTrue(open.overflowed)
    }

    @Test
    fun r1_04_oversizedFrameThroughControllerClosesConnectionAndEndsTurn() {
        val c = kit.controller()
        assertTrue(c.send(c.snapshot.value.page, "帮我找纯牛奶"))
        val s = kit.port.last
        s.headers()
        s.line(META)
        s.line(delta(2, "前半段"))
        // 一帧超大的 text.delta 分 100 块到达
        val huge = "event: text.delta\nid: 3\ndata: {\"seq\":3,\"delta\":\"" + "长".repeat(30_000) + "\"}\n\n"
        huge.chunked(huge.length / 100 + 1).forEach(s.sink::onData)
        assertTrue("同步关连接", s.cancelled)
        assertEquals(0, kit.timer.armed)
        val state = c.streamStates.single()
        assertEquals(AgentStatus.LIMITED, state.status)
        assertEquals("前半段", state.text)
        // 关连接之后到达的帧不再改这一轮
        s.line(delta(4, "迟到"))
        s.line(frameLine("done", 5, """{"finish_reason":"stop","quota_left":3}"""))
        assertEquals("前半段", c.streamStates.single().text)
        assertEquals(null, c.snapshot.value.quotaLeft)
        assertEquals(null, c.activeRun)
    }

    // ---------------------------------------------------------------- R1-04 单轮累计上限（StreamReducer）

    @Test
    fun r1_04_textOverTwentyThousandCodePointsStopsAccumulating() {
        // 每段 100 个表情（每个是一个码点、两个 UTF-16 单元），200 段正好 20000 码点
        val emoji = "😀".repeat(100)
        val ok = listOf(META) + (2..201).map { delta(it, emoji) }
        val full = reduce(ok)
        assertEquals(AgentStatus.STREAMING, full.status)
        assertEquals(20_000, full.text.codePointCount(0, full.text.length))
        val over = reduce(ok + delta(202, "😀"))
        assertEquals(AgentStatus.LIMITED, over.status)
        assertEquals(20_000, over.text.codePointCount(0, over.text.length))
        // 结束之后的一切丢弃
        assertEquals(over, StreamReducer.frame(over, AgentFrameDecoder.frame(AgentSse.fromNdjsonLine(delta(203, "x")))))
    }

    @Test
    fun r1_04_thirteenthCardEndsTurn() {
        val twelve = listOf(META) + (2..13).map { guide(it, "c$it") }
        assertEquals(12, reduce(twelve).cards.size)
        assertEquals(AgentStatus.STREAMING, reduce(twelve).status)
        val over = reduce(twelve + guide(14, "c14"))
        assertEquals(AgentStatus.LIMITED, over.status)
        assertEquals(12, over.cards.size)
    }

    @Test
    fun r1_04_fourHundredFirstSmallContentFrameEndsTurnAndClosesConnection() {
        val c = kit.controller()
        assertTrue(c.send(c.snapshot.value.page, "帮我找纯牛奶"))
        val s = kit.port.last
        s.headers()
        s.line(META)
        // 400 个一字小帧（远小于 64 KiB，也远低于 20000 码点）：照常并入
        (2..401).forEach { s.line(delta(it, "字")) }
        assertEquals(AgentStatus.STREAMING, c.streamStates.single().status)
        assertEquals(
            400,
            c.streamStates
                .single()
                .text.length,
        )
        assertFalse(s.cancelled)
        // 第 401 个内容帧：不并入、本地结束、同步关连接
        s.line(delta(402, "多"))
        val state = c.streamStates.single()
        assertEquals(AgentStatus.LIMITED, state.status)
        assertEquals(400, state.text.length)
        assertTrue(s.cancelled)
        assertEquals(0, kit.timer.armed)
    }

    @Test
    fun r1_04_pingAndToolStatusAreNotContentFrames() {
        val tools =
            (2..500).map {
                frameLine("tool.status", it, """{"seq":$it,"tool":"t","phase":"start","display_text":"查询中"}""")
            }
        val state = reduce(listOf(META) + tools + delta(501, "好"))
        assertEquals(AgentStatus.STREAMING, state.status)
        assertEquals("好", state.text)
    }

    // ---------------------------------------------------------------- R1-05 同 card_id 后到的卡丢弃

    @Test
    fun r1_05_sameCardIdWithLaterSeqAndDifferentContentKeepsTheFirst() {
        val done = frameLine("done", 4, """{"finish_reason":"stop","quota_left":7}""")
        val state = reduce(listOf(META, guide(2, "c1", "WithdrawRecords"), guide(3, "c1", "Wallet"), done))
        assertEquals(1, state.cards.size)
        assertEquals("WithdrawRecords", AgentJson.stringOf(state.cards.single().data["route"]))
        assertEquals(4L, state.lastSeq)
        assertEquals(AgentStatus.DONE, state.status)
        // 经控制器：渲染与动作定位都只有第一张
        val c = kit.controller()
        assertTrue(c.send(c.snapshot.value.page, "提现进度在哪看"))
        kit.port.last.headers()
        listOf(META, guide(2, "c1", "WithdrawRecords"), guide(3, "c1", "Wallet"), done).forEach(kit.port.last::line)
        val cards =
            c.snapshot.value.turns
                .single()
                .cards
        assertEquals(1, cards.size)
        c.tap(cards.single().guide!!)
        assertEquals("WithdrawRecords", kit.opens.single().route)
    }
}
