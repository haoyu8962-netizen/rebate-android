package com.couli.core.agent

import com.couli.core.agent.AgentTestKit.Companion.META
import com.couli.core.agent.AgentTestKit.Companion.consentData
import com.couli.core.agent.AgentTestKit.Companion.errorLine
import com.couli.core.agent.AgentTestKit.Companion.fail
import com.couli.core.agent.AgentTestKit.Companion.frameLine
import com.couli.core.agent.AgentTestKit.Companion.member
import com.couli.core.agent.AgentTestKit.Companion.ok
import com.couli.core.router.RouteSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentController 执行层（brief §0 红线、§2 第 9 条；APP-10 共性第 1–24 条；APP-09 第 39、45、56、62–65 条；APP-11 第 1、4–6 条；APP-12 第 1、2、18、34 条）：
 * 真实控制器 + StreamClient + Reducer，端口与计时器是替身。每条用例对应一条规则，断言「该发生的发生了、不该发生的没发生」。
 */
@Suppress("LargeClass", "TooManyFunctions")
class AgentControllerTest {
    private val kit = AgentTestKit()

    private fun AgentController.page(): AgentPageTicket = snapshot.value.page

    private fun AgentController.sendNow(text: String = "帮我找纯牛奶"): Boolean = send(page(), text)

    private fun AgentController.turns() = snapshot.value.turns

    // ---------------------------------------------------------------- 红线 1：卡片按钮只在用户点击时打开，agent_card 来源、params 为空

    @Test
    fun pageGuideOpensOnlyOnTapWithAgentCardSourceAndEmptyParams() {
        val c = kit.controller()
        assertTrue(c.sendNow())
        kit.port.last.headers()
        kit.port.last.fixture("normal-page-guide")
        kit.port.last.end()
        // 卡片出现、流结束：不自动打开（AC-S1-142 ②）
        kit.advance(10_000)
        assertTrue(kit.opens.isEmpty())
        val guide =
            c
                .turns()
                .single()
                .cards
                .single()
                .guide
        assertNotNull(guide)
        assertEquals("去看看", guide!!.text)
        assertEquals("去看看，提现记录", guide.label)
        c.tap(guide)
        assertEquals(1, kit.opens.size)
        val request = kit.opens.single()
        assertEquals("WithdrawRecords", request.route)
        assertEquals(JsonObject(emptyMap()), request.params)
        assertEquals(RouteSource.AGENT_CARD.raw, request.source)
    }

    @Test
    fun earningsButtonsOpenWalletAndRecordsWithEmptyParamsAgentCard() {
        val c = kit.controller()
        c.sendNow("我的收益")
        kit.port.last.headers()
        kit.port.last.fixture("normal-earnings")
        kit.port.last.end()
        val taps =
            c
                .turns()
                .single()
                .cards
                .single()
                .earningsTaps
        assertEquals(listOf("Wallet", "WithdrawRecords"), taps.map { it.route })
        taps.forEach(c::tap)
        assertEquals(listOf("Wallet", "WithdrawRecords"), kit.opens.map { it.route })
        assertTrue(kit.opens.all { it.params.isEmpty() && it.source == RouteSource.AGENT_CARD.raw })
    }

    @Test
    fun unknownPageGuideRouteHasNoButtonAndReportsOnceInApp() {
        val c = kit.controller()
        c.sendNow("设置在哪")
        kit.port.last.headers()
        kit.port.last.fixture("unknown-card-page-guide")
        kit.port.last.end()
        val card =
            c
                .turns()
                .single()
                .cards
                .single()
        assertFalse(card.card.known)
        assertNull(card.guide)
        assertEquals("设置相关的选项都在设置页。", card.card.fallbackText)
        assertEquals(1, kit.tracks.size)
        assertEquals("route_entry_blocked", kit.tracks.single().name)
        assertEquals(
            "Settings",
            kit.tracks
                .single()
                .fields["route"]
                ?.content,
        )
        assertEquals(
            "in_app",
            kit.tracks
                .single()
                .fields["entry"]
                ?.content,
        )
        // 重绘不重复记（G10）
        kit.mask.value = true
        c.refresh()
        assertEquals(1, kit.tracks.size)
        assertTrue(kit.opens.isEmpty())
    }

    @Test
    fun basicModeNeverSendsAgentRequestsNorEvents() {
        kit.identity = AgentTestKit.basic()
        val c = kit.controller()
        assertEquals(AgentPhase.BLOCKED, c.currentPhase)
        assertFalse(c.sendNow())
        assertEquals(0, kit.port.currents + kit.port.creates + kit.port.streams.size)
        assertTrue(kit.tracks.isEmpty())
    }

    @Test
    fun guideTapIgnoredWhenRouteNotOnCard() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal-page-guide")
        val guide =
            c
                .turns()
                .single()
                .cards
                .single()
                .guide!!
        // 伪造一张路由不同的旧票据（执行时只认卡上已校验的路由）
        c.tap(guide.copy(route = "PayoutAccount"))
        assertTrue(kit.opens.isEmpty())
    }

    // ---------------------------------------------------------------- 红线 2：金额只在收益卡实时形状、遵守金额隐藏、不进日志

    @Test
    fun earningsLiveShowsAmountsAndMaskRedrawsImmediately() {
        val c = kit.controller()
        c.sendNow("我的收益")
        kit.port.last.headers()
        kit.port.last.fixture("normal-earnings")
        kit.port.last.end()
        val live =
            c
                .turns()
                .single()
                .cards
                .single()
                .earnings!!
        assertEquals("¥10", live.withdrawable)
        assertEquals("¥56.78", live.estimatedTotal)
        assertEquals("¥20", live.appliedAmount)
        assertEquals("审核中", live.latestTitle)
        assertTrue(c.liveEarningsShown)
        kit.mask.value = true
        val masked =
            c
                .turns()
                .single()
                .cards
                .single()
                .earnings!!
        assertEquals("****", masked.withdrawable)
        assertEquals("****", masked.estimatedTotal)
        assertEquals("****", masked.appliedAmount)
        assertEquals("11 月", masked.creditPeriod)
        assertTrue(c.snapshot.value.amountMasked)
    }

    @Test
    fun historyCardNeverShowsAmountsEvenIfPresent() {
        val c = kit.controller()
        c.sendNow("之前的收益")
        kit.port.last.headers()
        kit.port.last.line(META)
        // SSE 收到历史形状（G13）：历史渲染，没有任何金额
        kit.port.last.line(AgentTestKit.fixtureLines("normal-earnings-history").single())
        kit.port.last.line(frameLine("done", 9, """{"finish_reason":"stop","quota_left":1}"""))
        val v =
            c
                .turns()
                .single()
                .cards
                .single()
                .earnings!!
        assertEquals(EarningsShape.HISTORY, v.shape)
        assertNull(v.withdrawable)
        assertNull(v.estimatedTotal)
        assertNull(v.appliedAmount)
        assertEquals("agent.earnings.history", v.historyTextKey)
        // 历史来源误带金额：仍然只按历史形状（不保留金额字段）
        val history =
            StreamReducer.historyCard(
                StreamReducer.initial(),
                AgentJson.objectOf(
                    AgentJson.parse(
                        AgentTestKit.fixtureLines("normal-earnings").first {
                            it.contains("earnings_summary")
                        },
                    ),
                )!!["data"] as JsonObject,
            )
        val card = history.cards.single()
        assertEquals(EarningsShape.HISTORY, card.shape)
        assertFalse(card.data.containsKey("withdrawable_fen"))
        assertFalse(card.liveEarnings)
    }

    @Test
    fun amountsNeverReachLogsEventsOrToString() {
        val c = kit.controller()
        c.sendNow("我的收益")
        kit.port.last.headers()
        kit.port.last.fixture("normal-earnings")
        kit.port.last.end()
        val everything =
            (
                kit.logs + kit.tracks.map { it.fields.toString() } + c.snapshot.value.toString() + c.turns().toString() +
                    c
                        .turns()
                        .single()
                        .cards
                        .toString() +
                    c
                        .turns()
                        .single()
                        .cards
                        .single()
                        .earnings
                        .toString()
            ).joinToString("\n")
        listOf("1000", "5678", "2000", "¥10", "56.78", "¥20").forEach { assertFalse(it, everything.contains(it)) }
    }

    // ---------------------------------------------------------------- 红线 3：每个事件只属于发起它的票据

    @Test
    fun lateFramesAfterDisposeDoNotTouchAnything() {
        val a = kit.controller()
        a.sendNow()
        val stream = kit.port.last
        stream.headers()
        stream.fixture("normal", upTo = 6) // 已收到 card 帧
        assertEquals(
            1,
            a
                .turns()
                .single()
                .cards.size,
        )
        // A 会话失效：同步作废（关连接、清消息、不发 cancel）
        kit.identity = member("user-b")
        a.dispose()
        assertTrue(stream.cancelled)
        assertTrue(kit.port.cancels.isEmpty())
        val b = kit.controller()
        // A 的连接迟到的 text.delta / card / done
        stream.fixture("normal")
        stream.end()
        assertTrue(a.turns().isEmpty())
        assertTrue(b.turns().isEmpty())
        assertNull(b.snapshot.value.quotaLeft)
        assertTrue(kit.tracks.isEmpty())
        assertTrue(kit.port.owned.isNotEmpty())
    }

    @Test
    fun identityChangeWithoutDisposeStillDropsLateFrames() {
        val a = kit.controller()
        a.sendNow()
        val stream = kit.port.last
        stream.headers()
        stream.fixture("normal", upTo = 2)
        // 只换了身份、还没调 dispose（控制器自己比较固定身份）
        kit.identity = member("user-b")
        stream.fixture("normal")
        assertFalse(a.alive())
        assertTrue(
            a.streamStates
                .single()
                .cards
                .isEmpty(),
        )
        assertNull(a.streamStates.single().terminal)
    }

    @Test
    fun newChatDropsOldRunWithoutCancelAndIgnoresLateDone() {
        val c = kit.controller()
        c.sendNow()
        val old = kit.port.last
        old.headers()
        old.fixture("normal", upTo = 6)
        c.newChat(c.page())
        assertTrue(old.cancelled)
        assertTrue(kit.port.cancels.isEmpty())
        assertTrue(c.turns().isEmpty())
        old.fixture("normal")
        assertTrue(c.turns().isEmpty())
        assertNull(c.snapshot.value.quotaLeft)
    }

    @Test
    fun stopCancelsLocallySendsCancelWithOriginalCallAndDropsLateDoneCancelled() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.fixture("cancelled", upTo = 4)
        val stop = c.snapshot.value.stop!!
        c.stop(stop)
        assertTrue(s.cancelled)
        assertEquals(AgentStatus.CANCELLED, c.streamStates.single().status)
        assertEquals(1, kit.port.cancels.size)
        assertEquals(
            "019a0000-0000-7000-8000-000000000103",
            kit.port.cancels
                .single()
                .first,
        )
        // 服务端的 done(cancelled) 迟到：丢弃
        s.fixture("cancelled")
        assertNull(c.streamStates.single().terminal)
        assertNull(c.snapshot.value.quotaLeft)
        // 停止也作废已渲染卡片的回调与购买尝试（共性第 3 条）
        assertEquals(1, kit.cancelledPurchases)
        val products =
            c
                .turns()
                .single()
                .cards
                .single()
                .products
        assertTrue(products.all { it.buy == null && it.detail == null })
    }

    @Test
    fun stopRevokesPreviouslyRenderedCardCallbacks() {
        val c = kit.controller()
        c.sendNow("提现进度")
        kit.port.last.headers()
        kit.port.last.fixture("normal-page-guide")
        val guide =
            c
                .turns()
                .single()
                .cards
                .single()
                .guide!!
        c.sendNow("再找个牛奶")
        kit.port.last.headers()
        kit.port.last.fixture("normal", upTo = 6)
        val buy =
            c
                .turns()
                .last()
                .cards
                .single()
                .products
                .first()
                .buy!!
        c.stop(c.snapshot.value.stop!!)
        c.tap(guide)
        c.buy(buy)
        assertTrue(kit.opens.isEmpty())
        assertTrue(kit.buys.isEmpty())
    }

    @Test
    fun cancelResultAfterNewChatOnlySettlesSharedFacts() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("cancelled", upTo = 2)
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.port.cancelReply = { hold.await() }
        c.stop(c.snapshot.value.stop!!)
        c.newChat(c.page())
        hold.complete(fail(10001, http = 401))
        // 旧对话的停止结果：不导航（共性第 19 条）
        assertTrue(kit.opens.isEmpty())
    }

    @Test
    fun oldRenderedChipCannotSendIntoNewConversation() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal")
        kit.port.last.end()
        val chip =
            c
                .turns()
                .single()
                .chips
                .first()
        c.newChat(c.page())
        val before = kit.port.streams.size
        c.chip(chip)
        assertEquals(before, kit.port.streams.size)
    }

    // ---------------------------------------------------------------- 红线 4：不自动执行购买

    @Test
    fun productCardsNeverAutoBuyAndBuyOnlyOnTapWithRenderSnapshot() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal")
        kit.port.last.end()
        assertTrue(kit.buys.isEmpty())
        val product =
            c
                .turns()
                .single()
                .cards
                .first()
                .products
                .first()
        c.buy(product.buy!!)
        assertEquals(1, kit.buys.size)
        assertEquals(
            "c2",
            kit.buys
                .single()
                .second.itemCardId,
        )
        assertEquals(product.json, kit.buys.single().first)
    }

    @Test
    fun purchaseResultUpdatesOnlyThatCard() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal")
        val product =
            c
                .turns()
                .single()
                .cards
                .first()
                .products
                .first()
        c.buy(product.buy!!)
        val ref = kit.buys.single().second
        c.updateProduct(ref) { JsonObject(it + ("availability" to JsonPrimitive("off_shelf"))) }
        val updated =
            c
                .turns()
                .single()
                .cards
                .first()
                .products
                .first()
        assertEquals("off_shelf", AgentJson.stringOf(updated.json["availability"]))
        assertNotNull(updated.refresh)
        // 别的卡不受影响
        assertEquals(
            "ok",
            AgentJson.stringOf(
                c
                    .turns()
                    .single()
                    .cards[1]
                    .products
                    .single()
                    .json["availability"],
            ),
        )
    }

    // ---------------------------------------------------------------- 红线 6：同意与身份闸门

    @Test
    fun httpConsentOpensSheetAgreeReplaysOnceWithSameClientMsgId() {
        val c = kit.controller()
        c.sendNow()
        val first = kit.port.last
        first.headers(type = "application/json", status = 403)
        first.sink.onData("""{"code":10004,"msg":"x","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
        first.end()
        val ticket = c.snapshot.value.consent!!
        assertEquals(AgentConsentGate.AWAITING, c.consentState)
        assertFalse(c.sendNow("第二条"))
        assertEquals(1, kit.port.streams.size)
        c.consentAgree(ticket)
        assertEquals(listOf(true), kit.consentCalls)
        assertEquals(2, kit.port.streams.size)
        assertEquals(first.clientMsgId, kit.port.last.clientMsgId)
        assertEquals(AgentConsentGate.CLEAR, c.consentState)
    }

    @Test
    fun consentSubmittingStillBlocksSendingAndFailureKeepsGateLocked() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers(type = "application/json", status = 403)
        kit.port.last.sink
            .onData("""{"code":10004,"msg":"x","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
        kit.port.last.end()
        val hold = CompletableDeferred<AgentHttpResult>()
        kit.consentReply = { hold.await() }
        c.consentAgree(c.snapshot.value.consent!!)
        assertEquals(AgentConsentGate.SUBMITTING, c.consentState)
        assertFalse(c.sendNow("提交中再发"))
        hold.complete(fail(50001, http = 500))
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertTrue(c.snapshot.value.consentFailed)
        assertEquals(AgentPhase.CONSENT_DECLINED, c.currentPhase)
        assertFalse(c.sendNow("失败后再发"))
        assertEquals(1, kit.port.streams.size)
        assertTrue(kit.toasts.last().contains("4f5a6b"))
    }

    @Test
    fun replayedRequestHitting10004AgainRelocksGate() {
        val c = kit.controller()
        c.sendNow()

        fun reply10004() {
            kit.port.last.headers(type = "application/json", status = 403)
            kit.port.last.sink
                .onData("""{"code":10004,"msg":"x","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
            kit.port.last.end()
        }
        reply10004()
        c.consentAgree(c.snapshot.value.consent!!)
        reply10004()
        // 共性第 16 条（S0）：重放过的那次再遇 AI 10004 也要立即锁闸门并再弹
        assertEquals(AgentConsentGate.AWAITING, c.consentState)
        assertNotNull(c.snapshot.value.consent)
        assertFalse(c.sendNow("再发"))
        assertEquals(2, kit.port.streams.size)
    }

    @Test
    fun inStream10004LocksGateRevokesCallbacksAndClearsConversation() {
        val c = kit.controller()
        c.sendNow("提现进度")
        kit.port.last.headers()
        kit.port.last.fixture("normal-page-guide")
        val guide =
            c
                .turns()
                .single()
                .cards
                .single()
                .guide!!
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.fixture("normal", upTo = 6)
        val allowed = s.call.sendAllowed
        // 共性第 22 条（S0）：流内 error 10004（没有 consent_type）同样锁闸门、撤销旧回调
        s.line(errorLine(7, 10004))
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertEquals(AgentPhase.CONSENT_DECLINED, c.currentPhase)
        assertTrue(c.turns().isEmpty())
        assertNull(c.snapshot.value.consent)
        assertFalse(allowed())
        assertFalse(c.sendNow("再发一条"))
        c.tap(guide)
        assertTrue(kit.opens.isEmpty())
        assertEquals(2, kit.port.streams.size)
    }

    @Test
    fun duplicateStream10004AlsoLocksGate() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.line(META.replace("\"ai_label\"", "\"duplicate\":true,\"ai_label\""))
        kit.port.last.line(errorLine(2, 10004))
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertFalse(c.sendNow("再发"))
    }

    @Test
    fun lateOldCreateSession10004OnlyLocksGateWithoutDialog() {
        val c = kit.controller()
        val hold = CompletableDeferred<Unit>()
        kit.port.hold = hold
        var replies = 0
        // 第一次（旧对话的建会话）回 AI 10004；【新对话】自己的建会话正常
        kit.port.createReply = { if (replies++ == 0) fail(10004, consentData(), http = 403) else ok() }
        c.sendNow()
        // 发起建会话之后换了对话：旧 operation 的 AI 10004 是同一身份的共享事实，只锁闸门、不弹框、不改新对话（鸿蒙 H27，共性第 18 条）
        c.newChat(c.page())
        hold.complete(Unit)
        assertEquals(AgentConsentGate.REFUSED, c.consentState)
        assertNull(c.snapshot.value.consent)
        assertTrue(c.turns().isEmpty())
        assertFalse(c.sendNow("再发"))
    }

    @Test
    fun bytesAfterLocalCloseAreDiscarded() {
        val c = kit.controller()
        c.sendNow()
        val old = kit.port.last
        old.headers()
        old.line(META)
        c.newChat(c.page())
        // cancel 之后到达的任何字节一律丢弃（StreamClient）：不解析、不交付
        old.line(errorLine(2, 10004))
        old.fixture("normal")
        assertEquals(AgentConsentGate.CLEAR, c.consentState)
        assertTrue(c.turns().isEmpty())
    }

    @Test
    fun privacy10004GoesUnifiedWithoutAgentConsent() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers(type = "application/json", status = 403)
        kit.port.last.sink
            .onData("""{"code":10004,"msg":"x","data":{"consent_type":"privacy"},"trace_id":"t"}""")
        kit.port.last.end()
        assertNull(c.snapshot.value.consent)
        assertEquals(AgentConsentGate.CLEAR, c.consentState)
    }

    @Test
    fun declineRecordsFalseAndStaysWithReagree() {
        kit.port.currentReply = { fail(10004, consentData(), http = 403) }
        val c = kit.controller()
        val ticket = c.snapshot.value.consent!!
        c.consentDecline(ticket)
        assertEquals(listOf(false), kit.consentCalls)
        assertEquals(AgentPhase.CONSENT_DECLINED, c.currentPhase)
        c.consentReopen(c.page())
        assertNotNull(c.snapshot.value.consent)
        assertTrue(
            c.snapshot.value.consent!!
                .serial > ticket.serial,
        )
        // 旧弹窗票据失效
        c.consentAgree(ticket)
        assertEquals(listOf(false), kit.consentCalls)
    }

    @Test
    fun dismissDoesNotRecordAnything() {
        kit.port.currentReply = { fail(10004, consentData(), http = 403) }
        val c = kit.controller()
        c.consentDismissed(c.snapshot.value.consent!!)
        assertTrue(kit.consentCalls.isEmpty())
        assertEquals(AgentPhase.CONSENT_DECLINED, c.currentPhase)
    }

    // ---------------------------------------------------------------- 红线 7：Release 不发请求

    @Test
    fun noContractShowsUnavailableAndSendsNothing() {
        val c = kit.controller(contract = null)
        assertEquals(AgentPhase.UNAVAILABLE, c.currentPhase)
        assertFalse(c.sendNow())
        assertEquals(0, kit.port.currents + kit.port.creates + kit.port.streams.size)
    }

    // ---------------------------------------------------------------- 可见性、30 秒、trace6、AiLabel

    @Test
    fun leavingPageClosesStreamAndPermitNeverRecovers() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        s.line(META)
        val allowed = s.call.sendAllowed
        c.setVisible(false)
        assertTrue(s.cancelled)
        assertEquals(AgentStatus.DISCONNECTED, c.streamStates.single().status)
        c.setVisible(true)
        assertFalse(allowed())
        // 迟到的帧不改界面
        s.fixture("normal")
        assertEquals("", c.streamStates.single().text)
        assertNotNull(
            c
                .turns()
                .single()
                .error
                ?.retry,
        )
    }

    @Test
    fun lateCreateSession42901AfterLeavingStillSettlesSharedGate() {
        val c = kit.controller()
        kit.port.createReply = { fail(42901, retryAfter = "7", http = 429) }
        val hold = CompletableDeferred<Unit>()
        kit.port.hold = hold
        c.sendNow()
        c.setVisible(false)
        hold.complete(Unit)
        assertTrue(kit.gate.active)
        assertEquals(7_000L, kit.gate.remainingMillis())
        c.setVisible(true)
        assertFalse(c.sendNow("禁用期内"))
        assertTrue(kit.toasts.last().isNotEmpty())
    }

    @Test
    fun thirtySecondsFromRequestStartHeadersDoNotResetCompleteLinesDo() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        kit.advance(29_000)
        s.headers()
        kit.advance(1_000)
        // 共性第 9 条：响应头不重置，从请求发出起 30 秒
        assertEquals(AgentStatus.DISCONNECTED, c.streamStates.single().status)
        assertTrue(s.cancelled)
        assertEquals(
            "连接中断",
            c
                .turns()
                .single()
                .error
                ?.text,
        )
    }

    @Test
    fun halfLineDoesNotResetButCompleteLineDoes() {
        val c = kit.controller()
        c.sendNow()
        val s = kit.port.last
        s.headers()
        kit.advance(20_000)
        s.sink.onData(": pi")
        kit.advance(9_000)
        s.sink.onData("ng\n")
        kit.advance(29_000)
        assertEquals(AgentStatus.IDLE, c.streamStates.single().status)
        kit.advance(1_000)
        assertEquals(AgentStatus.DISCONNECTED, c.streamStates.single().status)
    }

    @Test
    fun inStreamServerErrorUsesHeaderTraceAndKeepsAiLabel() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers(trace = "trace-abcdef")
        kit.port.last.fixture("error")
        val turn = c.turns().single()
        assertTrue(turn.error!!.text.endsWith("（abcdef）"))
        assertNotNull(turn.error!!.search)
        assertNotNull(turn.error!!.retry)
        assertEquals(AgentRetryMode.NEW, turn.error!!.retry!!.mode)
        assertEquals("内容由 AI 生成，仅供参考", turn.aiLabel)
    }

    @Test
    fun searchFallbackPrefillsQOnlyWhenShort() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("error-search-fallback")
        val search =
            c
                .turns()
                .single()
                .error!!
                .search!!
        c.tap(search)
        assertEquals("Search", kit.opens.single().route)
        assertEquals("纯牛奶 24盒", AgentJson.stringOf(kit.opens.single().params["q"]))
        assertEquals(RouteSource.IN_APP.raw, kit.opens.single().source)
    }

    @Test
    fun disconnectedRetryReusesClientMsgIdAndDuplicateDoesNotMerge() {
        val c = kit.controller()
        c.sendNow()
        val first = kit.port.last
        first.headers()
        first.fixture("disconnected")
        kit.advance(30_000)
        val retry =
            c
                .turns()
                .single()
                .error!!
                .retry!!
        assertEquals(AgentRetryMode.SAME, retry.mode)
        c.retry(retry)
        val second = kit.port.last
        assertEquals(first.clientMsgId, second.clientMsgId)
        second.headers()
        second.fixture("duplicate-ended-done")
        val state = c.streamStates.single()
        assertEquals(AgentStatus.DONE, state.status)
        assertEquals("为你找到以下商品，", state.text)
        assertNull(c.snapshot.value.quotaLeft)
    }

    @Test
    fun duplicateInProgressShowsRunInProgressWithSameIdRetry() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("duplicate-in-progress")
        val error = c.turns().single().error!!
        assertEquals("上一条还在回复中", error.text)
        assertEquals(AgentRetryMode.SAME, error.retry!!.mode)
        assertNull(c.snapshot.value.quotaLeft)
    }

    @Test
    fun quotaOnlyFromNonDuplicateDone() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal")
        assertEquals(29L, c.snapshot.value.quotaLeft)
    }

    // ---------------------------------------------------------------- JSON 错误分流（G24）

    private fun jsonReply(
        c: AgentController,
        code: Int,
        data: String = "{}",
        retryAfter: String? = null,
        http: Int = 422,
    ) {
        c.sendNow()
        kit.port.last.headers(type = "application/json", status = http, retryAfter = retryAfter)
        kit.port.last.sink
            .onData("""{"code":$code,"msg":"m","data":$data,"trace_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"}""")
        kit.port.last.end()
    }

    @Test
    fun json42901DisablesSendingByRetryAfterAndDefaults() {
        val c = kit.controller()
        jsonReply(c, 42901, retryAfter = "12", http = 429)
        assertEquals(12_000L, kit.gate.remainingMillis())
        assertTrue(c.snapshot.value.limited)
        assertTrue(c.turns().isEmpty())
        kit.advance(12_000)
        assertFalse(kit.gate.active)
        jsonReply(c, 42901, retryAfter = "n/a", http = 429)
        assertEquals(5_000L, kit.gate.remainingMillis())
    }

    @Test
    fun json50401RetriesSameIdWithTrace6AndNoAutoRetry() {
        val c = kit.controller()
        jsonReply(c, 50401, http = 504)
        val error = c.turns().single().error!!
        assertTrue(error.text.endsWith("（4f5a6b）"))
        assertEquals(1, kit.port.streams.size)
        val first = kit.port.last.clientMsgId
        c.retry(error.retry!!)
        assertEquals(first, kit.port.last.clientMsgId)
    }

    @Test
    fun json50001OffersNewChatAnd30501Unavailable() {
        val c = kit.controller()
        jsonReply(c, 50001, http = 500)
        assertNotNull(
            c
                .turns()
                .single()
                .error!!
                .newChat,
        )
        jsonReply(c, 30501)
        assertEquals(AgentPhase.UNAVAILABLE, c.currentPhase)
        assertFalse(c.sendNow("未开放后"))
    }

    @Test
    fun json30502GuestOffersLoginAndSearch() {
        kit.identity = AgentTestKit.guest()
        val c = kit.controller()
        jsonReply(c, 30502)
        val error = c.turns().single().error!!
        assertEquals("Login", error.upgrade!!.route)
        assertEquals("Search", error.search!!.route)
        assertTrue(c.snapshot.value.quotaExhausted)
        assertFalse(c.sendNow("额度用完后"))
    }

    @Test
    fun json10405ForcesUpdateAnd10001GoesLogin() {
        val c = kit.controller()
        jsonReply(c, 10405, http = 403)
        assertEquals(1, kit.forced)
        jsonReply(c, 10001, http = 401)
        assertEquals("Login", kit.opens.single().route)
        assertTrue(
            kit.opens
                .single()
                .params
                .isEmpty(),
        )
    }

    @Test
    fun json20001TextFieldToastsTooLong() {
        val c = kit.controller()
        jsonReply(c, 20001, data = """{"fields":["text"]}""", http = 400)
        assertEquals("最多输入 500 字", kit.toasts.last())
        assertTrue(c.turns().isEmpty())
    }

    @Test
    fun inputOver500CodePointsIsNotSent() {
        val c = kit.controller()
        assertFalse(c.send(c.page(), "好".repeat(501)))
        assertTrue(c.send(c.page(), "😀".repeat(500)))
        assertEquals(1, kit.port.streams.size)
    }

    // ---------------------------------------------------------------- 情境入口（G27）

    @Test
    fun contextOnlyOnFirstMessageAndNeverAutoSent() {
        val c = kit.controller(context = JsonObject(mapOf("order_id" to JsonPrimitive("019a0000-0000-7000-8000-000000000501"))))
        assertTrue(kit.port.streams.isEmpty())
        assertEquals("agent.draft.context.order", c.snapshot.value.contextLabelKey)
        c.sendNow("这单为什么还没到账")
        assertTrue(
            kit.port.last.bodyJson
                .containsKey("context"),
        )
        kit.port.last.headers()
        kit.port.last.fixture("normal")
        c.sendNow("再问一句")
        assertFalse(
            kit.port.last.bodyJson
                .containsKey("context"),
        )
    }

    @Test
    fun invalidContextIsDropped() {
        val c = kit.controller(context = JsonObject(mapOf("text" to JsonPrimitive("a"), "order_id" to JsonPrimitive("x"))))
        assertNull(c.snapshot.value.contextLabelKey)
    }

    @Test
    fun handoffRejectsStaleSource() {
        val handoff = AgentContextHandoff()
        val a = member("user-a").ownerKey
        val ticket = AgentContextTicket(AgentContextSource(a) { true }, JsonObject(mapOf("product_key" to JsonPrimitive("jd:demo-0001"))))
        // 换号后执行的旧回调：拒绝
        assertFalse(handoff.offer(ticket, member("user-b").ownerKey, null))
        assertFalse(handoff.hasPending)
        // 来源页已离开：拒绝
        assertFalse(handoff.offer(AgentContextTicket(AgentContextSource(a) { false }, null), a, null))
        assertTrue(handoff.offer(ticket, a, null))
        assertNull(handoff.take(member("user-b").ownerKey))
    }

    // ---------------------------------------------------------------- 撤回 AI 同意（G18）

    @Test
    fun withdrawFlowKeepsFullErrorAndNavigatesOnlyWithPermit() {
        val gate = kit.gate
        var withdrawn = 0
        val opened = mutableListOf<String>()
        var permit = true
        val flow =
            AgentWithdrawFlow(gate, { fail(10005, http = 403) }, { withdrawn += 1 }, { opened += it }, { permit })
        permit = false
        kotlinx.coroutines.runBlocking { flow.run() }
        assertTrue(opened.isEmpty())
        assertEquals(0, withdrawn)
        val limited =
            kotlinx.coroutines.runBlocking {
                AgentWithdrawFlow(gate, { fail(42901, retryAfter = "3", http = 429) }, { }, { }, { true }).run()
            }
        assertEquals(3_000L, limited.disableMs)
        assertTrue(gate.active)
        val server = AgentWithdrawOutcome.of(fail(50001, http = 500))
        assertTrue(server.text.endsWith("（4f5a6b）"))
        assertEquals(50001, server.result.code)
    }

    @Test
    fun aiConsentWithdrawnRebuildsFromEmptyConversation() {
        val c = kit.controller()
        c.sendNow()
        kit.port.last.headers()
        kit.port.last.fixture("normal-earnings", upTo = 4)
        val s = kit.port.last
        kit.identity = member("user-a", aiConsent = 1)
        c.dispose()
        val next = kit.controller()
        s.fixture("normal-earnings")
        assertTrue(next.turns().isEmpty())
        assertFalse(next.liveEarningsShown)
        assertTrue(c.turns().isEmpty())
    }
}
