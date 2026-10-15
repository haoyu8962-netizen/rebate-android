package com.couli.core.agent

import com.couli.core.agent.AgentTestKit.Companion.META
import com.couli.core.agent.AgentTestKit.Companion.consentData
import com.couli.core.agent.AgentTestKit.Companion.fail
import com.couli.core.agent.AgentTestKit.Companion.frameLine
import com.couli.core.agent.AgentTestKit.Companion.ok
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-10b 控制器层（brief §2 第 4–11 条、§0 红线；鸿蒙三轮 + 窄修的共性 1–35）：真实控制器 + StreamClient（字节入口、序号守卫、30 / 90 秒）+ Reducer + 历史 / 重载 /
 * 停止 / 反馈 / 举报状态机，端口与计时器是替身（只记次数，不保存正文）。每条用例对应一条规则。
 */
@Suppress("LargeClass", "TooManyFunctions")
class AgentTransportControllerTest {
    private val kit = AgentTestKit()

    private fun AgentController.page(): AgentPageTicket = snapshot.value.page

    private fun AgentController.sendNow(text: String = "帮我找纯牛奶"): Boolean = send(page(), text)

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

    @Suppress("LongParameterList") // 字段逐项
    private fun item(
        n: Int,
        role: String,
        text: String,
        atMs: Long = kit.now,
        cards: List<JsonObject> = emptyList(),
        finish: String? = "stop",
    ) = AgentJson.obj(
        "message_id" to id(n),
        "role" to role,
        "created_at" to iso(atMs),
        "text" to text,
        "cards" to JsonArray(cards),
        "finish_reason" to if (role == AgentRole.USER) null else finish,
    )

    private fun page(
        items: List<JsonObject>,
        next: String? = null,
        expired: Boolean = false,
    ) = AgentJson.obj("items" to JsonArray(items), "next_cursor" to next, "expired" to expired)

    private fun withSession() {
        kit.port.currentReply = { ok(AgentJson.obj("session" to AgentJson.obj("session_id" to "s1"))) }
    }

    /** 一轮正常的流（meta id=1 … done），流内 meta.message_id = 201。 */
    private fun streamNormal(c: AgentController) {
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal")
        kit.port.last.end()
    }

    private val guide =
        AgentJson.obj(
            "card_id" to "c1",
            "type" to "page_guide",
            "schema_version" to 1,
            "data" to AgentJson.obj("route" to "WithdrawRecords", "text_key" to "agent.guide.WithdrawRecords"),
            "fallback_text" to "提现进度在提现记录里查看。",
        )

    // ---------------------------------------------------------------- 历史：进入恢复、翻页、只读、30504、10004、迟到（§2 第 5 条，G08、G09、G39）

    @Test
    fun coldEntryReadsCurrentSessionThenFirstHistoryPageWithoutCreatingOrSending() {
        withSession()
        kit.port.historyReply =
            { ok(page(listOf(item(221, "user", "提现记录在哪看"), item(222, "assistant", "提现进度在提现记录里查看。", cards = listOf(guide))), "c2")) }
        val c = kit.controller()
        assertEquals(1, kit.port.currents)
        assertEquals(listOf<String?>(null), kit.port.histories.map { it.first })
        assertEquals(0, kit.port.creates)
        assertTrue(kit.port.streams.isEmpty())
        val t = c.turns().single()
        assertTrue(t.history)
        assertEquals("提现记录在哪看", t.userText)
        assertEquals(AgentStatus.DONE, t.status)
        assertNotNull(t.aiLabel)
        // 历史回复有可信的 message_id：画【赞】【踩】与举报入口；没有【停止】
        assertEquals(id(222), t.actions!!.ticket.messageId)
        assertNull(c.snapshot.value.stop)
        assertTrue(c.snapshot.value.hasEarlier)
        // 历史卡按钮按新票据：用户点才打开
        val g = t.cards.single().guide!!
        assertTrue(kit.opens.isEmpty())
        c.tap(g)
        assertEquals("WithdrawRecords", kit.opens.single().route)
    }

    @Test
    fun pagingPrependsOlderPageOnceAndStopsWhenCursorIsNull() {
        withSession()
        kit.port.historyReply = { cursor ->
            if (cursor == null) {
                ok(page(listOf(item(331, "user", "新的问题"), item(332, "assistant", "新的回答")), "c2"))
            } else {
                ok(page(listOf(item(321, "user", "旧的问题"), item(322, "assistant", "旧的回答"))))
            }
        }
        val c = kit.controller()
        val hold = CompletableDeferred<Unit>()
        val reply = kit.port.historyReply
        kit.port.historyReply = { cursor ->
            hold.await()
            reply(cursor)
        }
        c.loadEarlier(c.page())
        // 一次只在途一页
        c.loadEarlier(c.page())
        assertEquals(listOf(null, "c2"), kit.port.histories.map { it.first })
        assertTrue(c.snapshot.value.earlierLoading)
        hold.complete(Unit)
        kit.advance(0)
        assertEquals(listOf("旧的问题", "新的问题"), c.turns().map { it.userText })
        assertFalse(c.snapshot.value.hasEarlier)
        assertTrue(c.snapshot.value.historyExhausted)
        c.loadEarlier(c.page())
        assertEquals(2, kit.port.histories.size)
    }

    @Test
    fun expiredHistoryIsReadOnlyWithNewChatAndCardsStillUsable() {
        withSession()
        kit.port.historyReply =
            { ok(page(listOf(item(331, "user", "返利什么时候到账"), item(332, "assistant", "", finish = "cancelled")), expired = true)) }
        val c = kit.controller()
        val s = c.snapshot.value
        assertTrue(s.readOnly)
        assertFalse(s.canCompose)
        assertEquals(AgentController.errorText(AgentErrorCodes.ROUND_LIMIT, ""), s.sessionEndedText)
        assertFalse(c.sendNow())
        assertEquals(AgentDraftTexts.text("agent.draft.stopped"), s.turns.single().statusText)
        // 正文为空的回复不画【赞】【踩】与举报入口（编排者裁定第 3 条，Android R1-05；有正文的过期回复照常，见 fixR1EmptyBody…）
        assertNull(s.turns.single().actions)
    }

    @Test
    fun history30504ClearsSessionAndNextSendCreatesOne() {
        withSession()
        kit.port.historyReply = { fail(30504, AgentJson.obj("reason" to "not_found")) }
        val c = kit.controller()
        assertEquals(AgentPhase.READY, c.currentPhase)
        assertTrue(c.turns().isEmpty())
        assertEquals(AgentController.errorText(AgentErrorCodes.ROUND_LIMIT, ""), c.snapshot.value.sessionEndedText)
        assertTrue(c.sendNow())
        assertEquals(1, kit.port.creates)
    }

    @Test
    fun history10004LocksGateWithoutDialogAndReportsAppLevelLapse() {
        withSession()
        kit.port.historyReply = { fail(10004, consentData()) }
        val c = kit.controller()
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertNull(c.snapshot.value.consent)
        assertEquals(listOf<Any>(kit.identity!!.ownerKey), kit.lapses)
        assertFalse(c.sendNow())
    }

    @Test
    fun lateHistoryAfterNewChatIsDiscarded() {
        withSession()
        val hold = CompletableDeferred<Unit>()
        kit.port.historyReply = {
            hold.await()
            ok(page(listOf(item(221, "user", "旧会话"), item(222, "assistant", "旧回答"))))
        }
        val c = kit.controller()
        // 加载中不能新对话；作废这个控制器后到达的旧历史一律丢弃
        c.dispose()
        hold.complete(Unit)
        kit.advance(0)
        assertTrue(
            c.snapshot.value.turns
                .isEmpty(),
        )
        assertTrue(kit.port.owned.contains(kit.identity!!))
    }

    // ---------------------------------------------------------------- 缺号、断线重载、duplicate（§2 第 4、6 条，G07、G11、G38）

    @Test
    fun seqGapEndsIncompleteThenTerminalReloadsHistoryAndReplacesByReplyId() {
        withSession()
        kit.port.historyReply = { ok(page(emptyList())) }
        val c = kit.controller()
        kit.port.historyReply = { ok(page(listOf(item(101, "user", "帮我找纯牛奶"), item(201, "assistant", "为你找到以下商品，完整版。")))) }
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.line(META)
        s.line(frameLine("text.delta", 3, """{"seq":3,"delta":"跳号"}"""))
        assertEquals(AgentStatus.INCOMPLETE, c.turns().single().status)
        assertNull(c.turns().single().error)
        assertFalse(s.cancelled)
        // 缺号之后的内容帧不交 Reducer；终止帧到 → 关连接、自动拉一次历史替换本条
        s.line(frameLine("text.delta", 4, """{"seq":4,"delta":"不该出现"}"""))
        s.line(frameLine("done", 5, """{"finish_reason":"stop","quota_left":3}"""))
        assertTrue(s.cancelled)
        assertEquals(2, kit.port.histories.size)
        val t = c.turns().single()
        assertEquals(AgentStatus.DONE, t.status)
        assertEquals("为你找到以下商品，完整版。", t.blocks.joinToString("") { it.plain })
        assertNull(c.snapshot.value.quotaLeft)
    }

    @Test
    fun disconnectRetryPullsHistoryWithBackoffAndKeepsTrace6AfterFiveFailures() {
        val c = kit.controller()
        withSession()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("disconnected")
        kit.advance(30_000)
        val times = mutableListOf<Long>()
        kit.port.historyReply = {
            times += kit.now
            fail(50001)
        }
        val start = kit.now
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        repeat(STEPS) { kit.advance(STEP_MS) }
        // 首次 + 4 次，间隔 1 / 2 / 4 / 8 秒（抖动取 0.5 → ×1.0）；断线类重载关掉 GET 自动重试（G35）
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L), times.map { it - start })
        val error = c.turns().single().error!!
        assertTrue(error.text, error.text.contains("4f5a6b"))
        assertEquals(AgentRetryMode.RELOAD, error.retry!!.mode)
        assertEquals(1, kit.port.streams.size)
    }

    @Test
    fun disconnectRetryReplacesWhenTheTurnIsInHistory() {
        val c = kit.controller()
        withSession()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("disconnected")
        kit.advance(30_000)
        kit.port.historyReply = { ok(page(listOf(item(104, "user", "帮我找纯牛奶"), item(204, "assistant", "已经回答完了。")))) }
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        val t = c.turns().single()
        assertEquals(AgentStatus.DONE, t.status)
        assertEquals("已经回答完了。", t.blocks.joinToString("") { it.plain })
        assertNull(t.error)
        assertEquals(1, kit.port.streams.size)
    }

    @Test
    fun backgroundDuringBackoffStopsReloadAndForegroundDoesNotResume() {
        val c = kit.controller()
        withSession()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("disconnected")
        kit.advance(30_000)
        kit.port.historyReply = { fail(50001) }
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        assertEquals(1, kit.port.histories.size)
        kit.foreground = false
        c.onForeground(false)
        kit.advance(60_000)
        kit.foreground = true
        c.onForeground(true)
        kit.advance(60_000)
        assertEquals(1, kit.port.histories.size)
        assertEquals(
            AgentRetryMode.RELOAD,
            c
                .turns()
                .single()
                .error!!
                .retry!!
                .mode,
        )
        // 进后台同步取消挂在 reload:<run> 子归属上的历史请求
        assertTrue(kit.port.scopes.any { it.second.startsWith("reload:") })
    }

    // ---------------------------------------------------------------- 停止与 cancel（§2 第 7 条，G25、G26、G52）

    @Test
    fun stopAfterMetaClosesAndSendsExactlyOneCancelEvenWhenTappedTwice() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.line(META)
        val run = c.snapshot.value.stop!!
        c.stop(run)
        c.stop(run)
        assertTrue(s.cancelled)
        assertEquals(1, kit.port.cancels.size)
        val call =
            kit.port.cancels
                .single()
                .second
        assertTrue(call.singleShot)
        assertFalse(call.pageOwned)
        assertEquals(AgentStatus.CANCELLED, c.turns().single().status)
        // 停止同步取消本控制器归属的其他请求（保留这一轮的流通道例外）
        assertTrue(
            kit.port.ownedExcept
                .single()
                .second
                .startsWith("stream:"),
        )
    }

    @Test
    fun stopBeforeMetaShowsStoppingKeepsConnectionUntilMetaThenCancelsOnce() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        c.stop(c.snapshot.value.stop!!)
        assertEquals(AgentDraftTexts.text("agent.draft.stopping"), c.turns().single().statusText)
        assertFalse(s.cancelled)
        assertTrue(kit.port.cancels.isEmpty())
        // meta 到：立即关连接、发 1 次 cancel；之后的内容帧不交 Reducer
        s.line(META)
        assertTrue(s.cancelled)
        assertEquals(1, kit.port.cancels.size)
        assertEquals(
            "",
            c
                .turns()
                .single()
                .blocks
                .joinToString("") { it.plain },
        )
    }

    @Test
    fun stopBeforeMetaWithoutMetaFor30SecondsClosesWithoutCancel() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        c.stop(c.snapshot.value.stop!!)
        kit.advance(30_000)
        assertTrue(s.cancelled)
        assertTrue(kit.port.cancels.isEmpty())
    }

    @Test
    fun cancel30505ReloadsHistoryAndReplacesANonCancelledServerEnd() {
        withSession()
        kit.port.historyReply = { ok(page(emptyList())) }
        val c = kit.controller()
        kit.port.cancelReply = { fail(30505) }
        kit.port.historyReply = { ok(page(listOf(item(101, "user", "帮我找纯牛奶"), item(201, "assistant", "服务端其实已经答完。")))) }
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.line(META)
        c.stop(c.snapshot.value.stop!!)
        val t = c.turns().single()
        assertEquals(AgentStatus.DONE, t.status)
        assertEquals("服务端其实已经答完。", t.blocks.joinToString("") { it.plain })
        assertTrue(kit.opens.isEmpty())
        assertNull(c.snapshot.value.consent)
    }

    @Test
    fun stopCancelAi10004AfterTheControllerIsGoneStillLapsesTheSameOwner() {
        val c = kit.controller()
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.cancelReply = { hold.await() }
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.line(META)
        c.stop(c.snapshot.value.stop!!)
        c.dispose()
        hold.complete(fail(10004, consentData()))
        kit.advance(0)
        assertEquals(listOf<Any>(kit.identity!!.ownerKey), kit.lapses)
        assertTrue(kit.opens.isEmpty())
    }

    // ---------------------------------------------------------------- 反馈与举报（§2 第 8、9 条，G28、G29、G31）

    @Test
    fun feedbackIsOptimisticSingleFlightRollsBackOnFailureAndSameValueDoesNotSend() {
        val c = kit.controller()
        streamNormal(c)
        val t =
            c
                .turns()
                .single()
                .actions!!
                .ticket
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.feedbackReply = { hold.await() }
        c.feedback(t, AgentFeedbackValue.UP)
        var a = c.turns().single().actions!!
        assertEquals(AgentFeedbackValue.UP, a.selected)
        assertEquals("已赞", a.a11y)
        assertFalse(a.enabled)
        c.feedback(t, AgentFeedbackValue.DOWN)
        assertEquals(1, kit.port.feedbacks.size)
        hold.complete(fail(50001))
        kit.advance(0)
        a = c.turns().single().actions!!
        assertNull(a.selected)
        assertTrue(kit.toasts.last().contains("4f5a6b"))
        kit.port.feedbackReply = { ok() }
        c.feedback(t, AgentFeedbackValue.UP)
        c.feedback(t, AgentFeedbackValue.UP)
        c.feedback(t, AgentFeedbackValue.DOWN)
        assertEquals(listOf("up", "up", "down"), kit.port.feedbacks.map { it.second })
        assertEquals(
            AgentFeedbackValue.DOWN,
            c
                .turns()
                .single()
                .actions!!
                .selected,
        )
    }

    @Test
    fun feedback42901WritesTheActionGateAndAi10004LocksWithoutDialog() {
        val c = kit.controller()
        streamNormal(c)
        val t =
            c
                .turns()
                .single()
                .actions!!
                .ticket
        kit.port.feedbackReply = { fail(42901, retryAfter = "7", http = 429) }
        c.feedback(t, AgentFeedbackValue.UP)
        assertTrue(kit.gate.active)
        assertEquals(7_000L, kit.gate.remainingMillis())
        kit.advance(7_000)
        kit.port.feedbackReply = { fail(10004, consentData(), http = 403) }
        c.feedback(t, AgentFeedbackValue.UP)
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertNull(c.snapshot.value.consent)
        assertEquals(1, kit.lapses.size)
        assertTrue(kit.opens.isEmpty())
    }

    @Test
    fun reportComposesReasonWithSupplementSucceedsOnceAndHidesTheEntry() {
        val c = kit.controller()
        streamNormal(c)
        val t =
            c
                .turns()
                .single()
                .actions!!
                .ticket
        c.openReport(t)
        val sheet = c.snapshot.value.reportSheet!!
        assertEquals(AgentReport.reasons(), sheet.reasons)
        assertFalse(sheet.submitEnabled)
        c.selectReportReason(t, AgentReport.reasons()[1])
        c.editReportSupplement(t, "  多说两句 ")
        assertTrue(
            c.snapshot.value.reportSheet!!
                .submitEnabled,
        )
        c.submitReport(t)
        assertEquals(
            "含违法违规内容：多说两句",
            kit.port.reports
                .single()
                .second,
        )
        assertNull(c.snapshot.value.reportSheet)
        assertTrue(
            c
                .turns()
                .single()
                .actions!!
                .reported,
        )
        assertEquals("举报已提交，我们会核实处理", kit.toasts.last())
        c.openReport(t)
        assertNull(c.snapshot.value.reportSheet)
        assertEquals(1, kit.port.reports.size)
    }

    @Test
    fun reportFailuresKeepOrCloseTheSheetByCode() {
        val c = kit.controller()
        streamNormal(c)
        val t =
            c
                .turns()
                .single()
                .actions!!
                .ticket
        c.openReport(t)
        c.selectReportReason(t, AgentReport.reasons()[4])
        c.editReportSupplement(t, "x".repeat(250))
        assertTrue(
            c.snapshot.value.reportSheet!!
                .tooLong,
        )
        assertFalse(
            c.snapshot.value.reportSheet!!
                .submitEnabled,
        )
        c.editReportSupplement(t, "补充")
        kit.port.reportReply = { fail(20001, AgentJson.obj("fields" to listOf("reason"))) }
        c.submitReport(t)
        val kept = c.snapshot.value.reportSheet!!
        assertTrue(kept.reasonError)
        assertEquals("补充", kept.supplement)
        kit.port.reportReply = { fail(30504) }
        c.selectReportReason(t, AgentReport.reasons()[4])
        c.submitReport(t)
        assertNull(c.snapshot.value.reportSheet)
        assertEquals(AgentDraftTexts.text("agent.draft.action_failed"), kit.toasts.last())
        assertFalse(
            c
                .turns()
                .single()
                .actions!!
                .reported,
        )
    }

    @Test
    fun feedback30501LocksThisEntryAndNoMoreAgentRequests() {
        val c = kit.controller()
        streamNormal(c)
        kit.port.feedbackReply = { fail(30501) }
        c.feedback(
            c
                .turns()
                .single()
                .actions!!
                .ticket,
            AgentFeedbackValue.UP,
        )
        assertEquals(AgentPhase.UNAVAILABLE, c.currentPhase)
        assertTrue(c.unavailableForEntry)
        assertFalse(c.sendNow())
    }

    // ---------------------------------------------------------------- 切 Tab 保活与隐藏期 AI 10004（G02、G59）

    @Test
    fun hiddenAi10004OnlyLocksTheGateWithoutDialogOrPendingReplay() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        c.setVisible(false)
        s.headers(type = "application/json", status = 403)
        s.sink.onData("""{"code":10004,"msg":"x","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
        s.end()
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        c.setVisible(true)
        assertNull(c.snapshot.value.consent)
        assertTrue(c.turns().none { it.phase == AgentTurnPhase.AWAITING_CONSENT })
        assertEquals(AgentPhase.CONSENT_DECLINED, c.currentPhase)
    }

    // ---------------------------------------------------------------- 历史匹配排除他轮（鸿蒙 R3-02、F-X-06 ①）

    @Test
    @Suppress("LongMethod") // 步骤逐项
    fun textTimeMatchExcludesRepliesClaimedByOtherLocalTurns() {
        val p =
            AgentHistory.page(
                page(
                    listOf(
                        item(11, "user", "同一句", kit.now - 60_000),
                        item(12, "assistant", "第一轮的回答"),
                        item(21, "user", "同一句", kit.now - 30_000),
                        item(22, "assistant", "第二轮的回答"),
                    ),
                ),
            )!!
        val key = AgentTurnKey("同一句", kit.now, null)
        assertEquals(id(22), AgentHistoryMerge.match(p, key).reply!!.messageId)
        // 第二轮的回复已明确属于本地另一轮：不得被目标轮拿走
        assertEquals(id(12), AgentHistoryMerge.match(p, key, setOf(id(22))).reply!!.messageId)
        assertFalse(AgentHistoryMerge.match(p, key, setOf(id(12), id(22))).matched)
        // 向量接线按控制器口径传其他轮已确认归属（F-X-06 ①）：本地第二轮带着 22，目标轮（第一轮）只能对上 12
        val local =
            JsonArray(
                listOf(
                    AgentJson.obj("turnId" to "t1", "roleRaw" to "user", "text" to "同一句", "createdAtIso" to iso(kit.now - 60_000)),
                    AgentJson.obj("turnId" to "t2", "roleRaw" to "user", "text" to "同一句", "messageId" to id(21)),
                    AgentJson.obj("turnId" to "t2", "roleRaw" to "assistant", "messageId" to id(22)),
                ),
            )
        val history =
            AgentJson.obj(
                "code" to 0,
                "msg" to "",
                "data" to
                    page(
                        listOf(
                            item(11, "user", "同一句", kit.now - 60_000),
                            item(12, "assistant", "第一轮的回答"),
                            item(
                                21,
                                "user",
                                "同一句",
                                kit.now - 30_000,
                            ),
                            item(22, "assistant", "第二轮的回答"),
                        ),
                    ),
            )
        val merged =
            AgentHistoryMerge.of(
                AgentJson.obj(
                    "reasonRaw" to "disconnect",
                    "ticketMatches" to true,
                    "targetTurnId" to "t1",
                    "local" to local,
                    "history" to history,
                ),
                kit.now,
            )
        val ids =
            AgentJson
                .arrayOf(
                    merged["messages"],
                ).orEmpty()
                .mapNotNull { AgentJson.stringOf(AgentJson.objectOf(it)?.get("messageId")) }
        assertEquals(listOf(id(11), id(12), id(21), id(22)), ids)
    }

    // ---------------------------------------------------------------- 应用级：闸门保留、同意失效、身份变更、归属 cancel（鸿蒙 R1-03、R2-02、R3-01、R3-03）

    @Test
    fun gatesSurviveSameOwnerRebuildAndClearOnOwnerChange() {
        var now = 0L
        var owner: Any = "A"
        val gates = AgentGates.create({ now }, { owner })
        gates.retain("A")
        gates.read.start(12_000)
        assertFalse(gates.retain("A"))
        now = 1_000
        assertTrue(gates.read.active)
        assertEquals(11_000L, gates.read.remainingMillis())
        owner = "B"
        assertFalse(gates.read.active)
        assertTrue(gates.retain("B"))
        owner = "A"
        assertFalse("换回原归属也不复活", gates.read.active)
    }

    @Test
    fun consentLapseRecordsOnlyTheCurrentOwnerAndHaltsItsController() {
        val lapse = AgentConsentLapse()
        val c = kit.controller()
        streamNormal(c)
        assertFalse(lapse.lapse("other", kit.identity!!.ownerKey, c))
        assertFalse(lapse.blocks(kit.identity!!.ownerKey))
        assertTrue(lapse.lapse(kit.identity!!.ownerKey, kit.identity!!.ownerKey, c))
        assertTrue(lapse.blocks(kit.identity!!.ownerKey))
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        // 已上报的那次不会再从 App 回调里上报一次
        assertTrue(kit.lapses.isEmpty())
        lapse.agreed(kit.identity!!.ownerKey)
        assertFalse(lapse.blocks(kit.identity!!.ownerKey))
        // 重建出来的控制器在失效期间一开始就是「需要重新同意」，不取会话
        kit.consentLapsed = true
        val rebuilt = kit.controller()
        assertEquals(AgentPhase.CONSENT_DECLINED, rebuilt.currentPhase)
        assertEquals(1, kit.port.currents)
    }

    @Test
    fun identityChangeBlocksRebuildUntilEndAndSyncPolicyRebuildsOnConfigChange() {
        val change = AgentIdentityChange()
        change.begin(AgentOwnershipEvent.LOGOUT)
        assertEquals(AgentSyncAction.INVALIDATE, AgentSyncPolicy.decide(null, true, "A", true, change.active, true))
        change.end(AgentOwnershipEvent.LOGOUT)
        assertEquals(AgentSyncAction.REBUILD, AgentSyncPolicy.decide(null, true, "A", true, change.active, true))
        assertEquals(AgentSyncAction.KEEP, AgentSyncPolicy.decide("A", true, "A", true, false, true))
        assertEquals(AgentSyncAction.REBUILD, AgentSyncPolicy.decide("A", true, "A", false, false, true))
        assertEquals(AgentSyncAction.INVALIDATE, AgentSyncPolicy.decide("A", true, "B", true, false, false))
    }

    @Test
    fun ownershipStopInvalidatesSynchronouslyAndSendsOneSingleShotCancelWithTheOriginalIdentity() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.line(META)
        val stop = AgentOwnershipStop(kit.ledger, AgentOwnershipHandlers(), CoroutineScope(Dispatchers.Unconfined))
        val decision = stop.before(AgentOwnershipEvent.LOGOUT, c)
        assertTrue(c.isDisposed)
        assertTrue(s.cancelled)
        assertTrue(decision.sendCancel)
        stop.dispatch(decision, kit.port)
        assertEquals(1, kit.port.cancels.size)
        val call =
            kit.port.cancels
                .single()
                .second
        assertEquals(kit.identity, call.identityKey)
        assertTrue(call.singleShot)
        assertFalse(call.pageOwned)
        // 作废之后旧连接上到达的字节：0 帧交付（快照不再变化）
        val frozen = c.snapshot.value
        s.fixture("normal")
        org.junit.Assert.assertSame(frozen, c.snapshot.value)
        assertTrue(
            c.snapshot.value.turns
                .isEmpty(),
        )
        // 同一 run 不再发（账本）；切环境、会话失效不发
        assertFalse(stop.before(AgentOwnershipEvent.LOGOUT, null).sendCancel)
        assertFalse(AgentOwnershipStop.sendsCancel(AgentOwnershipEvent.ENV_SWITCH, true, true))
        assertFalse(AgentOwnershipStop.sendsCancel(AgentOwnershipEvent.SESSION_INVALID, true, true))
    }

    @Test
    fun applicationCancelIsNotSentWhenTheOriginalIdentityNoLongerAdmits() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.line(META)
        val stop = AgentOwnershipStop(kit.ledger, AgentOwnershipHandlers(), CoroutineScope(Dispatchers.Unconfined))
        // 原身份已变（换号）：协调器不发、不改发新身份
        kit.identity = AgentTestKit.member("user-b")
        val decision = stop.before(AgentOwnershipEvent.LOGOUT, c)
        assertFalse(decision.sendCancel)
        assertTrue(kit.port.cancels.isEmpty())
    }

    // ---------------------------------------------------------------- 第 1 轮修复（Android R1-01…R1-05）

    /** 断线（disconnected 夹具 + 30 秒空闲）后的这一轮。 */
    private fun disconnectedTurn(c: AgentController): AgentStreamCapture {
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.fixture("disconnected")
        kit.advance(30_000)
        val text =
            c
                .turns()
                .single()
                .blocks
                .joinToString("") { it.plain }
        return AgentStreamCapture(s, text)
    }

    private class AgentStreamCapture(
        val stream: AgentTestKit.Stream,
        val text: String,
    )

    @Test
    fun fixR1StopBeforeMetaThenHttpJsonAi10004LocksTheGateAndLapsesTheAppLevelOwnerOnce() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        // meta 之前点停止：「正在停止」，连接保持
        c.stop(c.snapshot.value.stop!!)
        assertFalse(s.cancelled)
        // 等待期间服务端返回 HTTP JSON 10004（consent_type=ai_third_party）
        s.headers(type = "application/json", status = 403)
        s.sink.onData("""{"code":10004,"msg":"x","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
        s.end()
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertFalse(c.canSend)
        assertEquals(listOf<Any>(kit.identity!!.ownerKey), kit.lapses)
        // 不弹框、不导航、不发 cancel（meta 没到）
        assertNull(c.snapshot.value.consent)
        assertTrue(kit.opens.isEmpty())
        assertTrue(kit.port.cancels.isEmpty())
        assertFalse(c.sendNow())
        assertEquals(1, kit.port.streams.size)
    }

    @Test
    fun fixR1StopBeforeMetaThenHttpJson42901WritesTheSendGate() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        c.stop(c.snapshot.value.stop!!)
        s.sink.onEnvelope(fail(42901, retryAfter = "9", http = 429))
        // 共享闸门替身（kit.gates 为 null 时三组退回同一个 [gate]）
        assertTrue(kit.gate.active)
        assertEquals(9_000L, kit.gate.remainingMillis())
        assertTrue(kit.lapses.isEmpty())
    }

    @Test
    fun fixR1NotSentStreamIsSilentAndLeavesNoTurn() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.sink
            .onEnvelope(AgentHttpResult.notSentResult())
        assertTrue(c.turns().isEmpty())
        assertTrue(kit.toasts.isEmpty())
        assertTrue(kit.opens.isEmpty())
    }

    @Test
    fun fixR1NoEnvelopeGatewayPageShowsDisconnectedWithReloadThatQueriesHistoryThenResendsTheSameId() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers(type = "text/html", status = 502)
        s.sink.onData("<html>Bad Gateway</html>")
        s.end()
        assertReloadRetryThenSameId(c, s)
    }

    @Test
    fun fixR1SuccessJsonThatIsNotAStreamShowsDisconnectedWithReload() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers(type = "application/json")
        s.sink.onData("""{"code":0,"msg":"","data":{},"trace_id":"t"}""")
        s.end()
        assertReloadRetryThenSameId(c, s)
    }

    @Test
    fun fixR1TransportFailureBeforeAnyResponseShowsDisconnectedWithReload() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.sink.onFailure()
        assertReloadRetryThenSameId(c, s)
    }

    @Test
    fun fixR1RealBusinessCodesKeepTheirOwnRules() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.sink
            .onEnvelope(fail(50001))
        val e = c.turns().single().error!!
        // 真实的 50001 仍是 NEW_CHAT（统一文案 + trace6 +【新对话】），不当成连接中断
        assertNotNull(e.newChat)
        assertNull(e.retry)
        assertTrue(e.text.contains("4f5a6b"))
    }

    private fun assertReloadRetryThenSameId(
        c: AgentController,
        first: AgentTestKit.Stream,
    ) {
        val t = c.turns().single()
        assertEquals(AgentStatus.DISCONNECTED, t.status)
        assertEquals(AgentDraftTexts.text("agent.draft.disconnected"), t.error!!.text)
        assertNull(t.error!!.newChat)
        val retry = t.error!!.retry!!
        assertEquals(AgentRetryMode.RELOAD, retry.mode)
        assertTrue(kit.port.histories.isEmpty())
        // 【重试】先查历史：对不上 → 同一 client_msg_id 重发
        kit.port.historyReply = { ok(page(emptyList())) }
        c.retry(retry)
        assertEquals(1, kit.port.histories.size)
        assertEquals(2, kit.port.streams.size)
        assertEquals(first.clientMsgId, kit.port.last.clientMsgId)
        assertEquals(1, c.turns().size)
    }

    @Test
    fun fixR1HistoryReturnsUnmatchedAfterSwitchingTabKeepsTheTurnAndResendsWhileHidden() {
        val c = kit.controller()
        val before = disconnectedTurn(c)
        assertTrue(before.text.isNotEmpty())
        val hold = CompletableDeferred<Unit>()
        kit.port.historyReply = {
            hold.await()
            ok(page(emptyList()))
        }
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        assertEquals(1, kit.port.histories.size)
        // 历史在途时切到其他 Tab（App 仍在前台），历史返回未匹配
        c.setVisible(false)
        hold.complete(Unit)
        kit.advance(0)
        // 恢复流程保活：同一 client_msg_id 重发照常发出，原轮次不删
        assertEquals(2, kit.port.streams.size)
        assertEquals(before.stream.clientMsgId, kit.port.last.clientMsgId)
        c.setVisible(true)
        val t = c.turns().single()
        assertEquals("帮我找纯牛奶", t.userText)
        assertEquals(before.text, t.blocks.joinToString("") { it.plain })
    }

    @Test
    fun fixR1ResumedResendThatIsNotSentKeepsTheTurnWithReloadRetry() {
        val c = kit.controller()
        val before = disconnectedTurn(c)
        kit.port.historyReply = { ok(page(emptyList())) }
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        assertEquals(2, kit.port.streams.size)
        // 恢复流程的重发在本地没发出（准入此刻不通过）：原轮次不删，只留 RELOAD【重试】
        kit.port.last.sink
            .onEnvelope(AgentHttpResult.notSentResult())
        val t = c.turns().single()
        assertEquals(before.text, t.blocks.joinToString("") { it.plain })
        assertEquals(AgentRetryMode.RELOAD, t.error!!.retry!!.mode)
    }

    @Test
    fun fixR1EmptyBodyRepliesDrawNoFeedbackButReplyWithTextStillDoes() {
        withSession()
        kit.port.historyReply = {
            ok(
                page(
                    listOf(
                        item(341, "user", "第一问", kit.now - 60_000),
                        item(342, "assistant", "", kit.now - 60_000, finish = "cancelled"),
                        item(343, "user", "第二问"),
                        item(344, "assistant", "这次答到一半", finish = "cancelled"),
                    ),
                    expired = true,
                ),
            )
        }
        val c = kit.controller()
        val (empty, text) = c.turns()
        assertNull(empty.actions)
        assertEquals(id(344), text.actions!!.ticket.messageId)
        // 空正文那条点了也不发（票据对不上可见的入口）
        c.feedback(AgentReplyTicket(c.page().controller, c.page().conversation, id(342)), AgentFeedbackValue.UP)
        assertTrue(kit.port.feedbacks.isEmpty())
    }

    @Test
    fun fixR1StreamErrorReplyWithTextAndTrustedIdKeepsFeedback() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.line(META)
        s.line(frameLine("text.delta", 2, """{"seq":2,"delta":"先说一半"}"""))
        s.line(AgentTestKit.errorLine(3, 50001))
        assertNotNull(c.turns().single().actions)
    }

    // ---------------------------------------------------------------- 第 2 轮修复（android-review-r2）

    /**
     * R2-01 场景：收到正文后停止（cancel 暂不返回）→ 对这条回复发起动作 → cancel 返回 30505、历史重载用相同 replyId 替换本轮（动作状态被重置）→ 新一轮开始。
     * 返回动作的 ticket，[cancel] 由调用方放行。
     */
    private fun stoppedThenReplacedByHistory(
        c: AgentController,
        cancel: CompletableDeferred<AgentHttpResult>,
        action: (AgentReplyTicket) -> Unit,
    ): AgentTestKit.Stream {
        kit.port.cancelReply = { cancel.await() }
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.fixture("normal", upTo = 5)
        c.stop(c.snapshot.value.stop!!)
        assertEquals(1, kit.port.cancels.size)
        val ticket =
            c
                .turns()
                .single()
                .actions!!
                .ticket
        action(ticket)
        kit.port.historyReply = { ok(page(listOf(item(101, "user", "帮我找纯牛奶"), item(201, "assistant", "服务端其实已经答完。")))) }
        cancel.complete(fail(30505))
        kit.advance(0)
        assertEquals(
            "服务端其实已经答完。",
            c
                .turns()
                .single()
                .blocks
                .joinToString("") { it.plain },
        )
        // 新的一轮开始
        assertTrue(c.sendNow("第二个问题"))
        val next = kit.port.last
        assertTrue(next !== s)
        next.headers()
        return next
    }

    @Test
    fun fixR2FeedbackAi10004AfterCancel30505HistoryReplaceStopsTheNewStreamAndLocksTheGate() {
        withSession()
        val c = kit.controller()
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.feedbackReply = { hold.await() }
        val next = stoppedThenReplacedByHistory(c, CompletableDeferred()) { t -> c.feedback(t, AgentFeedbackValue.UP) }
        assertEquals(1, kit.port.feedbacks.size)
        hold.complete(fail(10004, consentData(), http = 403))
        kit.advance(0)
        // 所有来源的 AI 10004 同步停流、作废所属工作、锁同意闸门、上报应用级失效 1 次；不弹框、不导航
        assertTrue(next.cancelled)
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertEquals(listOf<Any>(kit.identity!!.ownerKey), kit.lapses)
        assertNull(c.snapshot.value.consent)
        assertTrue(kit.opens.isEmpty())
        assertFalse(c.canSend)
        val delivered = c.streamDeliveries
        next.fixture("normal")
        next.end()
        assertEquals(delivered, c.streamDeliveries)
    }

    @Test
    fun fixR2ReportAi10004AfterCancel30505HistoryReplaceStopsTheNewStreamAndLocksTheGate() {
        withSession()
        val c = kit.controller()
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.reportReply = { hold.await() }
        val next =
            stoppedThenReplacedByHistory(c, CompletableDeferred()) { t ->
                c.openReport(t)
                c.selectReportReason(t, AgentReport.reasons()[0])
                c.submitReport(t)
            }
        assertEquals(1, kit.port.reports.size)
        hold.complete(fail(10004, consentData(), http = 403))
        kit.advance(0)
        assertTrue(next.cancelled)
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertEquals(listOf<Any>(kit.identity!!.ownerKey), kit.lapses)
        assertNull(c.snapshot.value.consent)
        assertTrue(kit.opens.isEmpty())
        assertFalse(c.canSend)
    }

    @Test
    fun fixR2FeedbackRateLimitAfterHistoryReplaceWritesTheActionGateButKeepsTheResetState() {
        withSession()
        val c = kit.controller()
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.feedbackReply = { hold.await() }
        val next = stoppedThenReplacedByHistory(c, CompletableDeferred()) { t -> c.feedback(t, AgentFeedbackValue.DOWN) }
        hold.complete(fail(42901, retryAfter = "6", http = 429))
        kit.advance(0)
        // 旧动作的结果只做共享结算（动作闸门），不改历史替换后的新状态、不提示，新流照常
        assertTrue(kit.gate.active)
        assertNull(
            c
                .turns()
                .first()
                .actions!!
                .selected,
        )
        assertFalse(next.cancelled)
        assertTrue(kit.toasts.none { it.contains("42901") })
    }

    @Test
    fun fixR2ActionAi10004AfterTheControllerIsGoneStillLapsesTheSameOwner() {
        val c = kit.controller()
        streamNormal(c)
        val t =
            c
                .turns()
                .single()
                .actions!!
                .ticket
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.feedbackReply = { hold.await() }
        c.feedback(t, AgentFeedbackValue.UP)
        c.dispose()
        hold.complete(fail(10004, consentData(), http = 403))
        kit.advance(0)
        assertEquals(listOf<Any>(kit.identity!!.ownerKey), kit.lapses)
        assertTrue(kit.opens.isEmpty())
    }

    /** R2-04：首次建会话断网 → 【重试】→ 第二次建会话在途时 App 进后台 → 建会话成功：不发流，保留原轮次与同一 client_msg_id，回前台不自动续发。 */
    @Test
    fun fixR2ResumeCreateSessionReturningInBackgroundSendsNoStreamAndKeepsTheTurnRetryable() {
        val c = kit.controller()
        kit.port.createReply = { AgentHttpResult.network() }
        assertTrue(c.sendNow())
        assertTrue(kit.port.streams.isEmpty())
        val first = c.turns().single()
        assertEquals(AgentRetryMode.RELOAD, first.error!!.retry!!.mode)
        val created = CompletableDeferred<AgentHttpResult>()
        kit.port.createReply = { created.await() }
        c.retry(first.error!!.retry!!)
        assertEquals(2, kit.port.creates)
        kit.foreground = false
        c.onForeground(false)
        created.complete(ok(JsonObject(mapOf("session" to AgentJson.obj("session_id" to "s1")))))
        kit.advance(0)
        assertTrue("后台不发流", kit.port.streams.isEmpty())
        val kept = c.turns().single()
        assertEquals("帮我找纯牛奶", kept.userText)
        assertEquals(AgentStatus.DISCONNECTED, kept.status)
        assertEquals(AgentRetryMode.RELOAD, kept.error!!.retry!!.mode)
        // 回前台不自动续发
        kit.foreground = true
        c.onForeground(true)
        kit.advance(60_000)
        assertTrue(kit.port.streams.isEmpty())
        // 用户再点【重试】：会话已记下 → 先查历史，对不上 → 同一 client_msg_id 重发
        kit.port.historyReply = { ok(page(emptyList())) }
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        assertEquals(1, kit.port.histories.size)
        assertEquals(1, kit.port.streams.size)
        assertEquals("帮我找纯牛奶", AgentJson.stringOf(kit.port.last.bodyJson["text"]))
        assertEquals(first.error!!.retry!!.clientMsgId, kit.port.last.clientMsgId)
        assertEquals(1, c.turns().size)
    }

    /** R2-05：断线轮 A 点【重试】、历史在途 → 发新轮 B → A 的历史返回未匹配：A 恢复为「连接中断」+ RELOAD【重试】；B 结束后不自动重发 A。 */
    @Test
    fun fixR2NewTurnWhileTheOldTurnReloadsKeepsTheOldTurnRetryableAndDoesNotAutoResend() {
        withSession()
        val c = kit.controller()
        val a = disconnectedTurn(c)
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.historyReply = { hold.await() }
        val before = kit.port.histories.size
        c.retry(
            c
                .turns()
                .single()
                .error!!
                .retry!!,
        )
        assertEquals(before + 1, kit.port.histories.size)
        assertTrue(c.canSend)
        assertTrue(c.sendNow("第二个问题"))
        val b = kit.port.last
        assertEquals(2, kit.port.streams.size)
        hold.complete(ok(page(emptyList())))
        kit.advance(0)
        var old = c.turns().first()
        assertEquals("帮我找纯牛奶", old.userText)
        assertEquals(AgentStatus.DISCONNECTED, old.status)
        assertEquals(AgentRetryMode.RELOAD, old.error!!.retry!!.mode)
        assertEquals(2, kit.port.streams.size)
        // B 正常结束：不自动重发 A
        b.headers()
        b.fixture("normal")
        b.end()
        kit.advance(60_000)
        assertEquals(2, kit.port.streams.size)
        old = c.turns().first()
        assertEquals(AgentRetryMode.RELOAD, old.error!!.retry!!.mode)
        // 用户点 A 的【重试】：先查历史，对不上 → 同一 client_msg_id 重发
        kit.port.historyReply = { ok(page(emptyList())) }
        c.retry(old.error!!.retry!!)
        assertEquals(3, kit.port.streams.size)
        assertEquals(a.stream.clientMsgId, kit.port.last.clientMsgId)
    }

    private companion object {
        const val STEPS = 40
        const val STEP_MS = 500L
    }
}
