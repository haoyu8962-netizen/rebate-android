package com.couli.app.me

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 红线 ③ 判据本身的自测（FU-AND-RISK-WDR T1，brief §4.3「测试作者在测试内造页面验证判据本身」）：在测试内造最小页面，
 * 跑 RiskVisualRedLineTest.kt 里同一个 [scanDeadline] / [violations]，证明判据**没有被放宽为任意文案**：
 * 只有「提现行内、整句恰等于 BR-TEXT-07 原文、无描述、不超过在途笔数」的节点被排除，其余写法与位置一律照红。
 * 判据代码只在冻结测试文件里一处，这里只调用不复写。底版（T1 已改）与实现后都应全绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@Suppress("TooManyFunctions")
class FuRiskWdrDeadlineJudgeTest {
    @get:Rule val rule = createComposeRule()

    private val forbidden get() = RiskVisualFixtures.forbidden

    @Test fun constantIsTheBrText07Original() {
        assertEquals(FuRiskWdr.ORIGINAL_SLA, WITHDRAWAL_SLA)
        assertTrue("先正：原文本身含禁用字样（不排除就会红）", forbidden.any { WITHDRAWAL_SLA.contains(it) })
    }

    @Test fun originalSentenceInsideTheWithdrawalRowWithinTheAllowancePasses() {
        val v = judge(row = listOf(WITHDRAWAL_SLA, FROZEN), allowance = 1)
        assertEquals("排除 1 处、其余无禁用字样", emptyList<String>(), v)
    }

    @Test fun zeroAllowanceStillFailsTheOriginal() = assertRed(judge(row = listOf(WITHDRAWAL_SLA), allowance = 0))

    @Test fun twoOriginalsOverOneAllowanceFail() = assertRed(judge(row = listOf(WITHDRAWAL_SLA, WITHDRAWAL_SLA), allowance = 1))

    @Test fun originalOutsideTheWithdrawalRowFails() =
        assertRed(
            judge(row = listOf(FROZEN), outside = listOf(WITHDRAWAL_SLA), allowance = 1),
        )

    @Test fun broaderManualReviewPhraseFails() = assertRed(judge(row = listOf("人工审核，申诉 3 天内处理"), allowance = 1))

    @Test fun originalWithAnAppendedClauseFails() = assertRed(judge(row = listOf(WITHDRAWAL_SLA + "，申诉 3 天内处理"), allowance = 1))

    @Test fun originalWithTrailingSpaceIsNotTrimmedAndFails() = assertRed(judge(row = listOf("$WITHDRAWAL_SLA "), allowance = 1))

    // 锁 00deded0ef6f 起包内 withdraw.sla_text 改为 BR-TEXT-07 原文本身，不再是变体；这里固定用旧包内变体字面量（a7c9f1f 的
    // default_config.json:27），判据不动（#24）
    @Test fun configuredOrBundledVariantIsNotExcludedByTheRedLine() = assertRed(judge(row = listOf(OLD_BUNDLED_VARIANT), allowance = 1))

    @Test fun originalCarryingADescriptionFails() = assertRed(judge(row = emptyList(), described = WITHDRAWAL_SLA, allowance = 1))

    private fun assertRed(violations: List<String>) = assertTrue("判据应判红", violations.isNotEmpty())

    private fun judge(
        row: List<String>,
        allowance: Int,
        outside: List<String> = emptyList(),
        described: String? = null,
    ): List<String> {
        rule.setContent { JudgePage(row, outside, described) }
        rule.waitForIdle()
        val nodes =
            rule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes()
        return scanDeadline(nodes).violations(allowance, forbidden)
    }

    private companion object {
        const val FROZEN = "¥10 已冻结"
        const val OLD_BUNDLED_VARIANT = "工作日 24 小时内处理"
    }
}

/** 最小页面：提现行（合并节点 + RISK_WITHDRAWALS 标记，标签 + 值列）与行外的提示句。 */
@Composable
private fun JudgePage(
    row: List<String>,
    outside: List<String>,
    described: String?,
) {
    Column {
        Row(Modifier.semantics(mergeDescendants = true) {}.testTag(MeTags.RISK_WITHDRAWALS)) {
            BasicText("进行中的提现")
            Column {
                BasicText("¥10 · 审核中")
                row.forEach { BasicText(it) }
                described?.let { text -> BasicText(text, Modifier.semantics { contentDescription = text }) }
            }
        }
        outside.forEach { BasicText(it) }
    }
}
