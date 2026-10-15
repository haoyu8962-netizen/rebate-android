package com.couli.core.agent

import com.couli.core.agent.AgentTestKit.Companion.fail
import com.couli.core.agent.AgentTestKit.Companion.ok
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-10b Android 第 3 轮窄修 R3-02（G25）：把 Codex 向量「G25_构造_30505历史只替换非cancelled的原轮」接到**真实控制器路径**（真实 AgentController + StreamClient +
 * 重载状态机 + 历史对齐；端口与计时器是替身）。向量文件只读、不改；断言按向量每个轴的 expect 逐项核对（localStatusRaw、replaced、conversationMutations）。
 */
class AgentFixR3ControllerTest {
    private val kit = AgentTestKit()

    private fun AgentController.page(): AgentPageTicket = snapshot.value.page

    private fun AgentController.turns() = snapshot.value.turns

    private fun id(n: Int): String = "019a0000-0000-7000-8000-%012d".format(n)

    private fun iso(ms: Long): String =
        Instant
            .ofEpochMilli(
                ms,
            ).atOffset(ZoneOffset.ofHours(8))
            .format(
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            ) +
            "+08:00"

    private fun item(
        n: Int,
        role: String,
        text: String,
        finish: String? = null,
    ) = AgentJson.obj(
        "message_id" to id(n),
        "role" to role,
        "created_at" to iso(kit.now),
        "text" to text,
        "cards" to JsonArray(emptyList()),
        "finish_reason" to if (role == AgentRole.USER) null else finish,
    )

    private fun page(items: List<JsonObject>) = AgentJson.obj("items" to JsonArray(items), "next_cursor" to null, "expired" to false)

    /** Codex 向量（只读）：cancel 组里的 G25 构造用例。 */
    private fun g25(): JsonObject {
        val start = File(System.getProperty("user.dir") ?: error("缺少 user.dir")).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val all = AgentJson.objectOf(AgentJson.parse(File(root, "specs/agent-transport-vectors.json").readText()))!!
        return AgentJson
            .arrayOf(all["cancel"])!!
            .mapNotNull { AgentJson.objectOf(it) }
            .single { AgentJson.stringOf(it["name"]) == "G25_构造_30505历史只替换非cancelled的原轮" }
    }

    @Test
    fun fixR3Cancel30505HistoryReplacesOnlyANonCancelledOriginalTurnOnTheRealControllerPath() {
        val vector = g25()
        assertEquals(30505L, AgentJson.intOf(AgentJson.objectOf(AgentJson.objectOf(vector["input"])!!["result"])!!["code"]))
        val axes = AgentJson.arrayOf(AgentJson.arrayOf(vector["axes"])!!.single())!!.mapNotNull { AgentJson.objectOf(it) }
        assertEquals(4, axes.size)
        axes.forEach { axis -> runAxis(axis) }
    }

    @Suppress("LongMethod") // 一个轴的完整时间线
    private fun runAxis(axis: JsonObject) {
        val name = AgentJson.stringOf(axis["name"])!!
        val input = AgentJson.objectOf(axis["input"])!!
        val expect = AgentJson.objectOf(axis["expect"])!!
        val local = AgentTestKit()
        local.port.currentReply = { ok(AgentJson.obj("session" to AgentJson.obj("session_id" to "s1"))) }
        val c = local.controller()
        val cancel = CompletableDeferred<AgentHttpResult>()
        local.port.cancelReply = { cancel.await() }
        assertTrue(name, c.send(c.page(), "帮我找纯牛奶"))
        val s = local.port.last
        s.headers()
        // 本地已收到正文与卡片（meta、工具、正文、商品卡），之后点【停止】：cancel 挂起
        s.fixture("normal", upTo = 6)
        val localText =
            c
                .turns()
                .single()
                .blocks
                .joinToString("") { it.plain }
        assertTrue(name, localText.isNotEmpty())
        c.stop(c.snapshot.value.stop!!)
        assertEquals(name, 1, local.port.cancels.size)
        // 停止之后对这条回复点【赞】（动作状态应在 KEEP 时保留）
        c.feedback(
            c
                .turns()
                .single()
                .actions!!
                .ticket,
            AgentFeedbackValue.UP,
        )
        local.advance(0)
        assertEquals(
            name,
            AgentFeedbackValue.UP,
            c
                .turns()
                .single()
                .actions!!
                .selected,
        )
        val matched = AgentJson.boolOf(input["historyMatched"]) == true
        val finish = AgentJson.stringOf(input["historyFinishReasonRaw"])
        local.port.historyReply = {
            if (matched) {
                ok(
                    page(
                        listOf(
                            item(101, "user", "帮我找纯牛奶"),
                            item(
                                201,
                                "assistant",
                                if (finish ==
                                    "cancelled"
                                ) {
                                    ""
                                } else {
                                    "服务端其实已经答完。"
                                },
                                finish,
                            ),
                        ),
                    ),
                )
            } else {
                ok(page(emptyList()))
            }
        }
        if (AgentJson.boolOf(input["ticketMatches"]) == false) c.newChat(c.page())
        val historiesBefore = local.port.histories.size
        val turnsBefore = c.turns()
        cancel.complete(fail(30505))
        local.advance(0)
        val replaced = AgentJson.boolOf(expect["replaced"]) == true
        if (AgentJson.boolOf(input["ticketMatches"]) == false) {
            // 已经新对话：旧轮的 30505 不拉历史、不改新对话
            assertEquals(name, 0L, AgentJson.intOf(expect["conversationMutations"]))
            assertEquals(name, historiesBefore, local.port.histories.size)
            assertEquals(name, turnsBefore, c.turns())
            assertTrue(name, c.turns().isEmpty())
            return
        }
        assertEquals(name, historiesBefore + 1, local.port.histories.size)
        val t = c.turns().single()
        assertEquals(name, AgentJson.stringOf(expect["localStatusRaw"]), t.status)
        if (replaced) {
            assertTrue(name, t.history)
            assertEquals(name, "服务端其实已经答完。", t.blocks.joinToString("") { it.plain })
        } else {
            // KEEP：本地正文、卡片、动作状态原样保留
            assertFalse(name, t.history)
            assertEquals(name, localText, t.blocks.joinToString("") { it.plain })
            assertTrue(name, t.cards.isNotEmpty())
            assertEquals(name, AgentFeedbackValue.UP, t.actions!!.selected)
        }
        assertTrue(name, local.opens.isEmpty())
    }
}
