package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.PriceTerms
import com.couli.core.wallet.WithdrawalText
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsV2SemanticsTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun reviewingFrozenSentencesHaveOneReaderBeforeAndAfterExpansion() = verify("pending")

    @Test fun failedReasonAndReturnedMoneyHaveOneReaderBeforeAndAfterExpansion() = verify("failed")

    @Test fun paidTaxAndArrivalHaveOneReaderBeforeAndAfterExpansion() = verify("tax")

    @Test fun rejectedReasonAndReturnedMoneyHaveOneReaderBeforeAndAfterExpansion() = verify("rejected")

    private fun verify(name: String) {
        val id = WithdrawRecordsVisualFixtures.id(name)
        host.launch(name)
        val item =
            host.controller.state.value.items
                .single()
        val display = WithdrawalText.of(item, null, host.texts)
        val lines = WithdrawalText.render(display, host.texts)
        val sentences = lines.flatMap { it.split(host.texts.text("withdrawal.draft.join")) }.filter(String::isNotEmpty)
        assertTrue("前置条件：至少两句才能覆盖主副文案", sentences.size >= 2)
        val collapsed = header(id)
        lines.forEach { assertTrue("收起卡头保留完整副文案：$it", collapsed.spoken().contains(it)) }
        assertEquals(host.texts.text("withdraw_records.expand"), collapsed.config[SemanticsActions.OnClick].label)
        assertSentenceCounts(sentences)
        host.toggle(id)
        host.assertCalls("listWithdrawals", "getWithdrawal")
        val expanded = header(id)
        val description =
            expanded.config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .joinToString(PriceTerms.SPOKEN_PAUSE)
        assertEquals(host.texts.text("withdraw_records.collapse"), expanded.config[SemanticsActions.OnClick].label)
        assertEquals(host.texts.text("withdraw_records.collapse"), expanded.config[SemanticsProperties.StateDescription])
        listOf(CouliFormat.money(item.amountFen), host.texts.text(display.titleKey), item.payoutChannelName).forEach {
            assertTrue("展开卡头仍需金额、状态、账号：$description", description.contains(it))
        }
        val channel = collapsed.config[SemanticsProperties.ContentDescription].single().split(PriceTerms.SPOKEN_PAUSE)[2]
        assertTrue("展开卡头仍需时间与脱敏账号", description.contains(channel))
        // 先计数以便红日志同时给出重复的各句；contentDescription 优先于同节点的可见 Text，按读屏遍历计数。
        assertSentenceCounts(sentences)
        sentences.forEach { assertFalse("展开头不能再重复状态块：$it", description.contains(it)) }
        host.toggle(id)
        assertSentenceCounts(sentences)
        host.assertCalls("listWithdrawals", "getWithdrawal")
    }

    private fun header(id: String): SemanticsNode =
        rule
            .onNode(
                hasClickAction() and (
                    hasTestTag(
                        WalletTags.RECORD_PREFIX + id,
                    ) or hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id))
                ) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
                useUnmergedTree = true,
            ).fetchSemanticsNode()

    private fun assertSentenceCounts(sentences: List<String>) {
        val spoken = rule.onAllNodes(SemanticsMatcher("有读屏文案") { it.spoken().isNotEmpty() }).fetchSemanticsNodes().map { it.spoken() }
        val counts = sentences.associateWith { sentence -> spoken.sumOf { Regex(Regex.escape(sentence)).findAll(it).count() } }
        println("RECORD_SPEECH $counts")
        assertEquals("语义遍历每句只能出现一次；不能删掉状态块掩盖重复", sentences.associateWith { 1 }, counts)
        // 展开后每句必须有一个独立状态节点，不能只在卡头隐藏播报。
        if (host.controller.state.value.expanded
                .isNotEmpty()
        ) {
            sentences.forEach { sentence ->
                val independent = spoken.count { it == sentence }
                assertEquals("状态块完整独立朗读：$sentence", 1, independent)
            }
        }
    }

    private fun SemanticsNode.spoken(): String =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(PriceTerms.SPOKEN_PAUSE)
            ?: config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(PriceTerms.SPOKEN_PAUSE) { it.text }
}
