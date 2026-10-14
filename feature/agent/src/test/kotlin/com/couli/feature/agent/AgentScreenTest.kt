package com.couli.feature.agent

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.agent.AgentConsentGate
import com.couli.core.agent.AgentController
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.router.RouteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Agent 页的界面行为（brief §0 红线经真实页面控件触发；APP-09 第 63 条：保留真实页面的旧回调、换号后再执行；APP-12 第 24、27 条：配置重建只在内存保留；
 * BR-ID-14：同意框默认不勾选；读屏、48dp 命中区）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AgentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val kit = AgentUiKit()
    private var current by mutableStateOf<AgentController?>(null)
    private var uiState by mutableStateOf<AgentPageUi?>(null)
    private val announced = mutableListOf<String>()
    private val saved = mutableMapOf<String, List<Any?>>()

    /** 记录所有「会进 Bundle」的保存值（rememberSaveable / SaveableStateRegistry）：金额不得出现在这里（G15）。 */
    private val registry =
        object : SaveableStateRegistry {
            private val providers = mutableMapOf<String, MutableList<() -> Any?>>()

            override fun canBeSaved(value: Any): Boolean = true

            override fun consumeRestored(key: String): Any? = null

            override fun performSave(): Map<String, List<Any?>> =
                providers.mapValues { (_, list) -> list.map { it() } }.also { saved.putAll(it) }

            override fun registerProvider(
                key: String,
                valueProvider: () -> Any?,
            ): SaveableStateRegistry.Entry {
                providers.getOrPut(key) { mutableListOf() } += valueProvider
                return object : SaveableStateRegistry.Entry {
                    override fun unregister() {
                        providers[key]?.remove(valueProvider)
                    }
                }
            }
        }

    private fun show(
        controller: AgentController,
        ui: AgentPageUi = kit.ui(),
    ) {
        current = controller
        uiState = ui
        composeRule.setContent { Host() }
        idle()
    }

    @Composable
    private fun Host() {
        val c = current ?: return
        val ui = uiState ?: return
        CompositionLocalProvider(
            LocalProductImageLoader provides AgentUiKit.images,
            LocalCouliAnnouncer provides CouliAnnouncer { announced += it },
            LocalSaveableStateRegistry provides registry,
        ) {
            CouliTheme { key(c.serial) { AgentScreen(c, ui) } }
        }
    }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun guideButtonOnlyOpensOnTapWithEmptyParamsAndFullSpokenName() {
        val c = kit.controller()
        show(c)
        composeRule.runOnIdle { kit.play(c, "normal-page-guide", "提现进度在哪看") }
        idle()
        assertTrue(kit.opens.isEmpty())
        val node = composeRule.onNodeWithTag(AgentTags.guide("c1"))
        node.assertHeightIsAtLeast(48.dp)
        assertTrue(
            node
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .contains("去看看，提现记录"),
        )
        node.performClick()
        idle()
        assertEquals(1, kit.opens.size)
        assertEquals("WithdrawRecords", kit.opens.single().route)
        assertTrue(
            kit.opens
                .single()
                .params
                .isEmpty(),
        )
        assertEquals(RouteSource.AGENT_CARD.raw, kit.opens.single().source)
    }

    @Test
    fun oldRenderedCallbackAfterIdentityChangeDoesNothing() {
        val a = kit.controller()
        show(a)
        composeRule.runOnIdle { kit.play(a, "normal-page-guide", "提现进度在哪看") }
        idle()
        // 保留真实页面上的旧按钮：换号（固定身份不再等于此刻身份）、页面按新控制器重建之后再点旧按钮
        val oldTap =
            a.snapshot.value.turns
                .single()
                .cards
                .single()
                .guide!!
        kit.identity = kit.identity.copy(account = "user-b")
        a.dispose()
        val b = kit.controller()
        composeRule.runOnIdle { current = b }
        idle()
        composeRule.runOnIdle { a.tap(oldTap) }
        assertTrue(kit.opens.isEmpty())
        assertTrue(composeRule.onAllNodesWithTag(AgentTags.guide("c1")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun consentCheckboxDefaultUncheckedAgreeEnabledOnlyAfterCheck() {
        val c = kit.controller()
        show(c)
        composeRule.runOnIdle {
            c.send(c.snapshot.value.page, "帮我找纯牛奶")
            val s = kit.port.streams.last()
            s.sink.onHeaders(403, mapOf("Content-Type" to "application/json"))
            s.sink.onData("""{"code":10004,"msg":"m","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
            s.sink.onEnd()
        }
        idle()
        composeRule.onNodeWithTag(AgentTags.CONSENT_CHECK).assertIsOff()
        composeRule.onNodeWithTag(AgentTags.CONSENT_AGREE).assertIsNotEnabled()
        composeRule.onNodeWithTag(AgentTags.CONSENT_CHECK).performClick()
        idle()
        composeRule.onNodeWithTag(AgentTags.CONSENT_AGREE).assertIsEnabled().performClick()
        idle()
        assertEquals(AgentConsentGate.CLEAR, c.consentState)
        // 同一 client_msg_id 重放一次
        assertEquals(2, kit.port.streams.size)
    }

    @Test
    fun consentDialogHiddenWhileBlockedOrInvisibleWithoutDeciding() {
        val c = kit.controller()
        val blocked = kotlinx.coroutines.flow.MutableStateFlow(true)
        show(c, kit.ui().let { AgentPageUi(mapCard = it.mapCard, blocked = blocked, memory = it.memory) })
        composeRule.runOnIdle {
            c.send(c.snapshot.value.page, "帮我找纯牛奶")
            val s = kit.port.streams.last()
            s.sink.onHeaders(403, mapOf("Content-Type" to "application/json"))
            s.sink.onData("""{"code":10004,"msg":"m","data":{"consent_type":"ai_third_party"},"trace_id":"t"}""")
            s.sink.onEnd()
        }
        idle()
        assertTrue(composeRule.onAllNodesWithTag(AgentTags.CONSENT_DIALOG).fetchSemanticsNodes().isEmpty())
        assertEquals(AgentConsentGate.AWAITING, c.consentState)
        blocked.value = false
        idle()
        assertFalse(composeRule.onAllNodesWithTag(AgentTags.CONSENT_DIALOG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun maskToggleRedrawsAmountsAndSpokenTextHidesNumbers() {
        val c = kit.controller()
        show(c)
        composeRule.runOnIdle { kit.play(c, "normal-earnings", "我赚了多少") }
        idle()
        val visibleTree = composeRule.onRoot(useUnmergedTree = true).printToString()
        assertTrue(visibleTree.contains("¥56.78"))
        kit.mask.value = true
        idle()
        val tree = composeRule.onRoot(useUnmergedTree = true).printToString()
        listOf("¥10", "¥56.78", "¥20").forEach { assertFalse(it, tree.contains(it)) }
        assertTrue(tree.contains("****"))
        assertTrue(tree.contains("金额已隐藏"))
    }

    @Test
    fun savedStateNeverContainsAmountsOrMessages() {
        val c = kit.controller()
        show(c)
        composeRule.runOnIdle { kit.play(c, "normal-earnings", "我赚了多少") }
        idle()
        composeRule.runOnIdle { registry.performSave() }
        val dump = saved.toString()
        listOf("1000", "5678", "2000", "¥10", "56.78", "我赚了多少", "你的收益情况").forEach { assertFalse(it, dump.contains(it)) }
    }

    @Test
    fun draftAndScrollSurviveRecreationOnlyInMemory() {
        val memory = AgentPageMemory()
        val c = kit.controller()
        show(c, kit.ui(memory))
        composeRule.onNodeWithTag(AgentTags.INPUT).performTextInput("草稿还在吗")
        idle()
        // 配置重建：页面整块重建（同一个内存记忆）；草稿与滚动位置只在内存
        composeRule.runOnIdle { uiState = kit.ui(memory) }
        composeRule.runOnIdle { current = null }
        idle()
        composeRule.runOnIdle { current = c }
        idle()
        assertTrue(composeRule.onRoot(useUnmergedTree = true).printToString().contains("草稿还在吗"))
        // 新对话（票据换代）：旧草稿不写进新对话
        composeRule.runOnIdle { c.newChat(c.snapshot.value.page) }
        idle()
        assertFalse(composeRule.onRoot(useUnmergedTree = true).printToString().contains("草稿还在吗"))
    }

    @Test
    fun pasteFillsInputButNeverSends() {
        val c = kit.controller()
        show(c)
        composeRule.onNodeWithTag(AgentTags.PASTE).assertHeightIsAtLeast(48.dp).performClick()
        idle()
        assertTrue(composeRule.onRoot(useUnmergedTree = true).printToString().contains("https://item.jd.com/100012043978.html"))
        assertTrue(kit.port.streams.isEmpty())
    }

    @Test
    fun screenReaderAnnouncesFinishedReplyText() {
        val c = kit.controller()
        show(c, kit.ui(reader = true))
        composeRule.runOnIdle { kit.play(c, "normal-earnings", "我赚了多少") }
        idle()
        assertTrue(announced.any { it.contains("你的收益情况见下方卡片") })
        assertTrue(announced.none { it.contains("¥") })
    }

    @Test
    fun readingOrderFollowsTimeAndAiLabelIsReadable() {
        val c = kit.controller()
        show(c)
        composeRule.runOnIdle { kit.play(c, "normal-page-guide", "提现进度在哪看") }
        idle()
        val tree = composeRule.onRoot(useUnmergedTree = true).printToString()
        val user = tree.indexOf("我说：提现进度在哪看")
        val card = tree.indexOf("提现进度在提现记录里查看。")
        val label = tree.indexOf("内容由 AI 生成，仅供参考")
        assertTrue(tree, user in 0 until card)
        assertTrue(tree, card < label)
    }

    @Test
    fun claimDraftHasNoSubmitAndFallbackCardIsNotClickable() {
        val c = kit.controller()
        show(c)
        val meta = AgentUiKit.fixtureLines("normal").first()
        val claim =
            """{"event":"card","id":2,"data":{"seq":2,"card_id":"c1","type":"claim_draft","schema_version":1,""" +
                """"data":{"platform":"jd","order_no":"123456789012",""" +
                """"click_times":["2026-10-06T10:00:00+08:00"],"evidence_summary":null,""" +
                """"submit_action":{"text_key":"agent.rule.not_found","action":"submit_claim"}},""" +
                """"fallback_text":"当前版本暂不支持显示这张卡片，请升级后查看。"}}"""
        val done = """{"event":"done","id":3,"data":{"finish_reason":"stop","quota_left":1}}"""
        composeRule.runOnIdle { kit.playLines(c, listOf(meta, claim, done), "订单找不到了") }
        idle()
        val clickable = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        val card = composeRule.onNodeWithTag(AgentTags.card("c1"))
        card.assert(!clickable)
        assertTrue(composeRule.onRoot(useUnmergedTree = true).printToString().contains("123456789012"))
    }

    @Test
    fun r1_06_fallbackTextOfUnknownAndBrokenCardsIsSanitizedAndNotClickable() {
        val c = kit.controller()
        show(c)
        val meta = AgentUiKit.fixtureLines("normal").first()
        val dirty = "先看 https://evil.example.com/x?a=1 再开 taobao://item?id=1 或 www.evil.example.com/y 口令 ￥AbCdEfGh12￥ 结束"
        // 未注册类型
        val unknown =
            """{"event":"card","id":2,"data":{"seq":2,"card_id":"c1","type":"watch_list","schema_version":1,"data":{},""" +
                """"fallback_text":"$dirty"}}"""
        // 已注册类型但不合 schema（page_guide 多了 params）
        val broken =
            """{"event":"card","id":3,"data":{"seq":3,"card_id":"c2","type":"page_guide","schema_version":1,""" +
                """"data":{"route":"Wallet","text_key":"agent.guide.Wallet","params":{"x":1}},"fallback_text":"$dirty"}}"""
        val done = """{"event":"done","id":4,"data":{"finish_reason":"stop","quota_left":1}}"""
        composeRule.runOnIdle { kit.playLines(c, listOf(meta, unknown, broken, done), "这是什么卡") }
        idle()
        val tree = composeRule.onRoot(useUnmergedTree = true).printToString()
        listOf("https://", "evil.example.com", "taobao://", "www.", "AbCdEfGh12", "￥").forEach { assertFalse(it, tree.contains(it)) }
        assertTrue(tree, tree.contains("先看"))
        assertTrue(tree, tree.contains("结束"))
        val clickable = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        listOf("c1", "c2").forEach { id ->
            composeRule.onNodeWithTag(AgentTags.card(id)).assert(!clickable)
            assertTrue(
                composeRule
                    .onAllNodes(
                        clickable and
                            androidx.compose.ui.test
                                .hasAnyAncestor(
                                    androidx.compose.ui.test
                                        .hasTestTag(AgentTags.card(id)),
                                ),
                    ).fetchSemanticsNodes()
                    .isEmpty(),
            )
        }
    }

    /** 连发几轮带商品卡的完整回复，内容超过多屏。 */
    private fun fillScreens(
        c: AgentController,
        rounds: Int = 4,
    ) {
        repeat(rounds) { i ->
            composeRule.runOnIdle { kit.play(c, "normal", "第 ${i + 1} 问：帮我找纯牛奶") }
            idle()
        }
    }

    private fun firstTurnTag(c: AgentController): String =
        AgentTags.turn(
            c.snapshot.value.turns
                .first()
                .key,
        )

    private fun shown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun r1_07_screenReaderOnLongReplyGrowthNeverAutoScrolls() {
        val c = kit.controller()
        show(c, kit.ui(reader = true))
        fillScreens(c)
        // 内容已超过一屏（「回到底部」出现），但读屏开启：列表停在原处，第一轮仍在屏上
        assertTrue("内容应超过一屏", shown(AgentTags.BACK_TO_BOTTOM))
        assertTrue("读屏开启时不得自动滚动", shown(firstTurnTag(c)))
        // 再来一条长回复（流式增长）也不动
        composeRule.runOnIdle { kit.play(c, "normal", "再来一条", end = false) }
        idle()
        assertTrue(shown(firstTurnTag(c)))
        // 用户主动点「回到底部」：照常滚动
        composeRule.onNodeWithTag(AgentTags.BACK_TO_BOTTOM).performClick()
        idle()
        assertFalse(shown(firstTurnTag(c)))
    }

    @Test
    fun r1_08_readingPositionAndFollowSurviveRecreationAfterRealScroll() {
        val memory = AgentPageMemory()
        val c = kit.controller()
        show(c, kit.ui(memory))
        fillScreens(c)
        // 跟随中：已在底部，第一轮不在屏上
        assertFalse(shown(firstTurnTag(c)))
        // 用户拖动上滑到最早的消息
        repeat(SWIPES) {
            if (!shown(firstTurnTag(c))) {
                composeRule.onNodeWithTag(AgentTags.LIST).performTouchInput { swipeDown() }
                idle()
            }
        }
        assertTrue("应已上滑到第一轮", shown(firstTurnTag(c)))
        // 配置重建 / 切 Tab（页面组合整块移除再回来，同一个内存记忆）
        composeRule.runOnIdle { current = null }
        idle()
        assertFalse(memory.follow)
        composeRule.runOnIdle {
            uiState = kit.ui(memory)
            current = c
        }
        idle()
        assertTrue("恢复阅读位置，不被跟随拉到底", shown(firstTurnTag(c)))
        // 恢复后又来新内容：仍不跟随
        composeRule.runOnIdle { kit.play(c, "normal", "又一问") }
        idle()
        assertTrue(shown(firstTurnTag(c)))
    }

    private companion object {
        const val ROUNDS = 4
        const val SWIPES = 30
    }
}
