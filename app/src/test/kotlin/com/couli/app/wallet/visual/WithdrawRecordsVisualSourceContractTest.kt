package com.couli.app.wallet.visual

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只有无法从公共语义看出的接线/default做源码检查，其余由实际UI及点击验证。 */
class WithdrawRecordsVisualSourceContractTest {
    @Test fun w27SharedButtonIconSizeIsOptionalAndStillDefaultsToTwenty() {
        val source =
            File(
                WithdrawRecordsVisualFixtures.root,
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliButtons.kt",
            ).readText()
        assertTrue(
            "新增leadingIconSize默认仍是20dp，不能全局改为16",
            Regex(
                "leadingIconSize\\s*:\\s*Dp\\s*=\\s*(CouliSizes.iconMedium|20.dp)",
            ).containsMatchIn(source),
        )
        assertTrue("图标实际使用该参数", source.contains("size(leadingIconSize)"))
        assertTrue(
            "记录复制显式使用16dp图标",
            Regex(
                "leadingIconSize\\s*=\\s*(CouliSizes.iconSmall|16.dp)",
            ).containsMatchIn(recordsSources()),
        )
    }

    @Test fun w31EmptyUsesLedgerAndFortyEightDpTopSpace() {
        val source = recordsSources()
        assertTrue("空态用流水图标", source.contains("icon = CouliIcons.ledger"))
        assertTrue("空态额外留白48dp", source.contains("CouliSpacing.space12") || source.contains("48.dp"))
    }

    @Test fun w32W33VisualLayerDoesNotIntroduceMissingProgressFieldsOrFreeText() {
        val source = recordsSources()
        listOf("review_deadline", "confirm_deadline", "channel_state", "hold_days", "paid_at").forEach {
            assertFalse("不能按未锁定字段拼进度：$it", source.contains("[\"$it\"]"))
        }
        assertTrue("副文案必须继续取现有规则", source.contains("WithdrawalText.render("))
    }

    private fun recordsSources(): String =
        File(
            WithdrawRecordsVisualFixtures.root,
            "feature/wallet/src/main/kotlin/com/couli/feature/wallet",
        ).listFiles()
            .orEmpty()
            .filter { it.name.startsWith("WithdrawRecord") && it.extension == "kt" && !it.name.contains("Controller") }
            .joinToString("\n") { it.readText() }
}
