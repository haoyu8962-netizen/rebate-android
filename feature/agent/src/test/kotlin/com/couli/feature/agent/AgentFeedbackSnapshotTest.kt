package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentIdentity
import com.couli.core.agent.AgentReport
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-10b 界面快照（Roborazzi，基准图在 src/test/snapshots/b*.png，另拷到 NATIVE/APP-10b/shots/android/）：AgentResult 赞 / 已赞 / 已踩、AgentReport 三态、
 * AgentReported、正在停止、历史恢复（openapi active / expired 示例原样）、加载更早、历史未知卡、历史加载失败、额度三种 next、401 帧上限、缺号、200% 字号、320 宽；
 * 读屏顺序另写成文本。经真实 AgentController + StreamClient + Reducer + 渲染器；数据全是契约 fixture、openapi 示例与标「构造」的合成数据。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass", "TooManyFunctions") // 一个画板一个用例
class AgentFeedbackSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var frame by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var contentSet = false

    private fun render(
        kit: AgentUiKit,
        controller: AgentController,
        widthDp: Int = 390,
        fontScale: Float = 1f,
    ) {
        if (!contentSet) {
            contentSet = true
            composeRule.setContent { frame?.invoke() }
        }
        composeRule.runOnIdle {
            frame = { key(controller.serial) { Frame(kit, controller, widthDp, fontScale) } }
        }
        idle()
    }

    @Composable
    private fun Frame(
        kit: AgentUiKit,
        controller: AgentController,
        widthDp: Int,
        fontScale: Float,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalProductImageLoader provides AgentUiKit.images,
        ) {
            CouliTheme {
                Box(modifier = Modifier.width(widthDp.dp).fillMaxHeight().background(CouliColors.backgroundCanvas)) {
                    AgentScreen(controller, kit.ui())
                }
            }
        }
    }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun shot(name: String) = composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")

    private fun screen(name: String) = captureScreenRoboImage("src/test/snapshots/$name.png")

    private fun lastActions(c: AgentController) =
        c.snapshot.value.turns
            .last { it.actions != null }
            .actions!!

    private fun normal(): Pair<AgentUiKit, AgentController> {
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        composeRule.runOnIdle { kit.play(c, "normal") }
        return kit to c
    }

    // ---------------------------------------------------------------- AgentResult / AgentReport / AgentReported

    @Test
    fun resultFeedbackLikedDisliked() {
        val (kit, c) = normal()
        render(kit, c)
        shot("b01-result-feedback")
        composeRule.runOnIdle { c.feedback(lastActions(c).ticket, "up") }
        render(kit, c)
        assertEquals("up", lastActions(c).selected)
        shot("b02-result-liked")
        composeRule.runOnIdle { c.feedback(lastActions(c).ticket, "down") }
        render(kit, c)
        assertEquals("down", lastActions(c).selected)
        shot("b03-result-disliked")
    }

    @Test
    fun reportSheetSelectedReadyThenReported() {
        val (kit, c) = normal()
        render(kit, c)
        // 长按回复气泡 / 读屏「更多操作 → 举报」都走同一个入口
        composeRule.runOnIdle { c.openReport(lastActions(c).ticket) }
        idle()
        screen("b04-report-sheet")
        val t =
            c.snapshot.value.reportSheet!!
                .ticket
        composeRule.runOnIdle { c.selectReportReason(t, AgentReport.reasons()[1]) }
        idle()
        screen("b05-report-selected")
        composeRule.runOnIdle { c.editReportSupplement(t, "推荐的商品和我问的规格不一样") }
        idle()
        assertTrue(
            c.snapshot.value.reportSheet!!
                .submitEnabled,
        )
        screen("b05b-report-ready")
        composeRule.runOnIdle { c.submitReport(t) }
        idle()
        assertTrue(lastActions(c).reported)
        assertEquals(1, kit.toasts.size)
        shot("b06-reported")
    }

    @Test
    fun stoppingBeforeMeta() {
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        composeRule.runOnIdle {
            check(c.send(c.snapshot.value.page, "帮我找纯牛奶，24 盒装"))
            kit.port.streams
                .last()
                .sink
                .onHeaders(200, mapOf("Content-Type" to "text/event-stream"))
            c.stop(c.snapshot.value.stop!!)
        }
        render(kit, c)
        shot("b07-stopping")
    }

    @Test
    fun duplicateInProgressRefreshIsTappable() {
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        composeRule.runOnIdle { kit.play(c, "duplicate-in-progress") }
        render(kit, c)
        val s = c.snapshot.value
        assertTrue(
            "sending=${s.sending} readLimited=${s.readLimited} limited=${s.limited} gate=${s.consentGate}",
            !s.sending && !s.readLimited,
        )
    }

    // ---------------------------------------------------------------- 历史

    private fun historyKit(
        example: String,
        older: JsonObject? = null,
    ): AgentUiKit =
        AgentUiKit().apply {
            port.current =
                AgentUiKit.ok(JsonObject(mapOf("session" to JsonObject(mapOf("session_id" to Json.parseToJsonElement("\"s1\""))))))
            port.history = { cursor -> AgentUiKit.ok(if (cursor == null) openapiHistory(example) else older ?: openapiHistory(example)) }
        }

    @Test
    fun historyActiveEarlierUnknownExpiredFailed() {
        val olderPage =
            page(
                msg(211, "user", "09:58:00", "这个链接有返利吗"),
                msg(212, "assistant", "09:58:04", "这个商品有返利，以下单页为准。"),
            )
        val kit = historyKit("active", olderPage)
        val c = composeRule.runOnIdle { kit.controller() }
        render(kit, c)
        assertTrue(c.snapshot.value.hasEarlier)
        shot("b09-history-active")
        composeRule.runOnIdle { c.loadEarlier(c.snapshot.value.page) }
        render(kit, c)
        assertTrue(c.snapshot.value.historyExhausted)
        shot("b09b-history-earlier-loaded")

        // 历史里的未知卡（构造：本端不认识的卡片类型 → 整卡只显示 fallback_text、无按钮）
        val timeline =
            """{"card_id":"c9","type":"rebate_timeline","schema_version":1,"data":{"steps":[]},""" +
                """"fallback_text":"返利一般在确认收货后进入结算，以订单页为准。"}"""
        val unknown =
            page(
                msg(241, "user", "10:00:00", "返利什么时候到账"),
                msg(242, "assistant", "10:00:03", "一般确认收货后结算。", "[$timeline]"),
            )
        val unknownKit = AgentUiKit().apply { sessionHistory(this, unknown) }
        val u = composeRule.runOnIdle { unknownKit.controller() }
        render(unknownKit, u)
        shot("b10-history-unknown-card")

        val expiredKit = historyKit("expired")
        val e = composeRule.runOnIdle { expiredKit.controller() }
        render(expiredKit, e)
        assertTrue(e.snapshot.value.readOnly)
        // R1-05：正文为空的历史回复不画【赞】【踩】（界面上没有反馈按钮节点；AiLabel 照常）
        val turns = e.snapshot.value.turns
        assertTrue(turns.all { it.actions == null })
        val key = turns.single().key
        assertTrue(
            composeRule.onAllNodesWithTag(AgentTags.feedbackUp(key), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithTag(AgentTags.feedbackDown(key), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(composeRule.onAllNodesWithTag(AgentTags.AI_LABEL, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("b11-history-expired-readonly")

        val failedKit =
            AgentUiKit().apply {
                port.current =
                    AgentUiKit.ok(JsonObject(mapOf("session" to JsonObject(mapOf("session_id" to Json.parseToJsonElement("\"s1\""))))))
                port.history =
                    { AgentHttpResult(false, 0, 50001, "m", JsonObject(emptyMap()), "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b", null) }
            }
        val f = composeRule.runOnIdle { failedKit.controller() }
        render(failedKit, f)
        shot("b12-history-load-failed")
    }

    // ---------------------------------------------------------------- 额度 next、帧上限、缺号

    @Test
    fun quotaNextLoginBindPhoneNone() {
        listOf(
            "b13-quota-next-login" to "login",
            "b14-quota-next-bind-phone" to "bind_phone",
            "b15-quota-next-none" to "none",
        ).forEach { (name, next) ->
            val kit = AgentUiKit()
            if (next == "login") kit.identity = kit.identity.copy(level = AgentIdentity.GUEST, account = null)
            val c = composeRule.runOnIdle { kit.controller() }
            composeRule.runOnIdle { kit.json(c, 30502, data = """{"next":"$next","reset_at":"2026-10-07T00:00:00+08:00"}""") }
            render(kit, c)
            assertTrue(!c.snapshot.value.canCompose)
            shot(name)
        }
    }

    @Test
    fun frameLimitAndSeqGap() {
        val meta = AgentUiKit.fixtureLines("normal").first()
        // 构造：401 条 1 字 text.delta（第 401 帧起不渲染，本轮 limited）
        val deltas = (2..LIMIT_FRAMES + 1).map { """{"event":"text.delta","id":$it,"data":{"seq":$it,"delta":"字"}}""" }
        val done = """{"event":"done","id":${LIMIT_FRAMES + 2},"data":{"finish_reason":"stop","quota_left":20}}"""
        val limitKit = AgentUiKit()
        val l = composeRule.runOnIdle { limitKit.controller() }
        composeRule.runOnIdle { limitKit.playLines(l, listOf(meta) + deltas + done) }
        render(limitKit, l)
        shot("b17-limited-401")

        // 构造：normal 去掉 id=3 → 缺号 → incomplete → 自动拉历史（空页）→ 只留【重试】
        val gapKit = AgentUiKit()
        val g = composeRule.runOnIdle { gapKit.controller() }
        composeRule.runOnIdle { gapKit.playLines(g, AgentUiKit.fixtureLines("normal").filterNot { it.contains("\"id\":3,") }) }
        render(gapKit, g)
        shot("b18-seq-gap")
    }

    // ---------------------------------------------------------------- 适配与读屏

    @Test
    fun font200Width320AndFocusOrder() {
        val (kit, c) = normal()
        render(kit, c, fontScale = 2f)
        shot("b19-font200")

        val narrowKit = historyKit("active")
        val n = composeRule.runOnIdle { narrowKit.controller() }
        render(narrowKit, n, widthDp = 320)
        shot("b20-width320")

        render(kit, c)
        val lines = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            val state = node.config.getOrNull(SemanticsProperties.StateDescription)
            val actions =
                node.config
                    .getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)
                    ?.joinToString(",") { it.label }
            listOfNotNull(text, desc, state?.let { "[$it]" }, actions?.let { "{$it}" })
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let { lines += it.joinToString(" ") }
            node.children.forEach(::walk)
        }
        walk(composeRule.onRoot().fetchSemanticsNode())
        val out = File("build/outputs/roborazzi/b21-a11y-focus-order.txt")
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
        val report =
            lines.indexOfFirst {
                it.contains(
                    com.couli.core.agent.AgentDraftTexts
                        .text("agent.draft.report.more_entry"),
                )
            }
        val ai = lines.indexOfFirst { it.contains("内容由 AI 生成") }
        // 「更多操作，举报」挂在回复正文这个可聚焦节点上（TalkBack 的操作菜单），正文在 AiLabel、【赞】【踩】之前
        assertTrue("举报入口在回复正文节点上", lines[report].contains("为你找到以下商品"))
        assertTrue("读屏顺序：回复（含举报操作）→ AiLabel", report in 0 until ai)
    }

    // ---------------------------------------------------------------- 第 2 轮修复 R2-06：只有卡片的回复也能举报

    /** 一轮只有卡片、没有文字的回复（构造：normal fixture 的 meta + 第一张卡 + done，id 连续）。 */
    private fun cardOnly(): Pair<AgentUiKit, AgentController> {
        val kit = AgentUiKit()
        val c = composeRule.runOnIdle { kit.controller() }
        val lines = AgentUiKit.fixtureLines("normal")
        val card = lines.first { it.contains("\"event\":\"card\"") }.replace("\"id\":5,", "\"id\":2,").replace("\"seq\":5,", "\"seq\":2,")
        val done = lines.last().replace("\"id\":10,", "\"id\":3,")
        composeRule.runOnIdle { kit.playLines(c, listOf(lines.first(), card, done)) }
        return kit to c
    }

    @Test
    fun cardOnlyReplyHasAFocusableReportHostWithLongClickAndScreenReaderAction() {
        val (kit, c) = cardOnly()
        render(kit, c)
        val turn =
            c.snapshot.value.turns
                .single()
        assertTrue("只有卡片：$turn", turn.blocks.isEmpty() && turn.cards.isNotEmpty())
        assertNotNull("卡片算正文、有可信回复 id：给反馈与举报 ${turn.status} ${turn.phase} ${c.streamStates}", turn.actions)
        val label =
            com.couli.core.agent.AgentDraftTexts
                .text("agent.draft.report.more_entry")
        val hosts = composeRule.onAllNodesWithTag(AgentTags.reportHost(turn.key), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("纯卡片回复有一个举报操作宿主", 1, hosts.size)
        val host = hosts.single()
        val custom = host.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf(label), custom.map { it.label })
        assertNotNull("长按可达", host.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
        // 卡片自身的按钮仍是各自的可点节点（不并进宿主）
        val clickable =
            composeRule
                .onAllNodesWithTag(AgentTags.reply(turn.key), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single()
                .let { reply ->
                    fun count(n: SemanticsNode): Int =
                        (
                            if (n.id != host.id &&
                                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
                            ) {
                                1
                            } else {
                                0
                            }
                        ) +
                            n.children.sumOf(::count)
                    count(reply)
                }
        assertTrue("卡片按钮仍可点：$clickable", clickable > 0)
        shot("b22-card-only-reply")
        // 读屏「更多操作 → 举报」
        composeRule.runOnIdle { custom.single().action() }
        idle()
        assertNotNull(c.snapshot.value.reportSheet)
        screen("b23-card-only-report-sheet")
        composeRule.runOnIdle {
            c.closeReport(
                c.snapshot.value.reportSheet!!
                    .ticket,
            )
        }
        idle()
        assertNull(c.snapshot.value.reportSheet)
        // 长按同样打开
        composeRule.onAllNodesWithTag(AgentTags.reportHost(turn.key), useUnmergedTree = true)[0].performTouchInput { longClick() }
        idle()
        assertNotNull(c.snapshot.value.reportSheet)
    }

    private fun sessionHistory(
        kit: AgentUiKit,
        page: JsonObject,
    ) {
        kit.port.current =
            AgentUiKit.ok(JsonObject(mapOf("session" to JsonObject(mapOf("session_id" to Json.parseToJsonElement("\"s1\""))))))
        kit.port.history = { AgentUiKit.ok(page) }
    }

    private fun page(vararg items: String): JsonObject =
        Json.parseToJsonElement("""{"items":[${items.joinToString(",")}],"next_cursor":null,"expired":false}""").jsonObject

    /** 一条历史消息（构造；message_id 用契约示例同一前缀）。 */
    private fun msg(
        n: Int,
        role: String,
        time: String,
        text: String,
        cards: String = "[]",
    ): String {
        val finish = if (role == "user") "null" else "\"stop\""
        val id = "019a0000-0000-7000-8000-%012d".format(n)
        return """{"message_id":"$id","role":"$role","created_at":"2026-10-14T$time+08:00","text":"$text",""" +
            """"cards":$cards,"finish_reason":$finish}"""
    }

    private companion object {
        const val ROUNDS = 4
        const val LIMIT_FRAMES = 401

        /** openapi.json 里 listAgentMessages 的示例（原样取 data）。 */
        fun openapiHistory(example: String): JsonObject {
            val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
            val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
            val api = Json.parseToJsonElement(File(root, "contracts/snapshot/contracts/openapi.json").readText()).jsonObject
            val op = api["paths"]!!.jsonObject["/v1/agent/sessions/{id}/messages"]!!.jsonObject["get"]!!.jsonObject
            val ex =
                op["responses"]!!
                    .jsonObject["200"]!!
                    .jsonObject["content"]!!
                    .jsonObject["application/json"]!!
                    .jsonObject["examples"]!!
                    .jsonObject[example]!!
                    .jsonObject["value"]!!
                    .jsonObject
            return ex["data"]!!.jsonObject
        }
    }
}
