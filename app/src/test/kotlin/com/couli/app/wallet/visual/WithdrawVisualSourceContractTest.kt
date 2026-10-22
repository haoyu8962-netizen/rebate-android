package com.couli.app.wallet.visual

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只冻结视图接线和共用默认值；真实门槛、票据、计数由行为测试另验。 */
class WithdrawVisualSourceContractTest {
    @Test fun w53SubmitCallbackAndEnableExpressionStayLiteral() {
        val source = views()
        assertTrue(source.contains("onClick = { controller.submit(version, owner) }.tapped(WalletTags.WITHDRAW_SUBMIT)"))
        assertTrue(
            source.contains(
                "enabled = display.submitEnabled && flow.canSubmit && !submitBlocked && !state.serverBlocked && !state.successPending",
            ),
        )
        assertTrue(source.contains(".imePadding()"))
    }

    @Test fun w60DialogTitleAlignDefaultsToOldStart() {
        val source = read("core/designsystem/src/main/kotlin/com/couli/core/designsystem/FeedbackComponents.kt")
        assertTrue("新 titleAlign 必须可选且默认不改旧弹窗", source.contains("titleAlign: TextAlign = TextAlign.Start"))
    }

    @Test fun w62ToastIconTintDefaultsToOldSuccess() {
        val source = read("core/designsystem/src/main/kotlin/com/couli/core/designsystem/FeedbackComponents.kt")
        assertTrue("iconTint 默认仍成功色", source.contains("iconTint: Color = CouliColors.statusSuccessText"))
    }

    @Test fun w45WithdrawHelpUsesExistingTextAndExplicitOutlinedButton() {
        val source = views()
        assertTrue(source.contains("\"fee_tax\""))
        assertTrue("提现说明显式给描边，不改变共用默认", source.contains("okVariant = CouliButtonVariant.SECONDARY"))
    }

    @Test fun w56PendingCallbacksKeepTheirTagsAndCapturedOwner() {
        val source = views()
        listOf(
            "controller.flow.askAbandon(key, owner)",
            "controller.flow.confirm(key, owner)",
            "controller.flow.abandon(key, owner)",
        ).forEach {
            assertTrue("票据回调：$it", source.contains(it))
        }
        val ui = read("feature/wallet/src/main/kotlin/com/couli/feature/wallet/WalletUi.kt")
        listOf(".tapped(WalletTags.PENDING_ABANDON)", ".tapped(WalletTags.PENDING_CONFIRM)", ".tapped(WalletTags.ABANDON_DIALOG)").forEach {
            assertTrue("tag 应跟随原动作：$it", ui.contains(it))
        }
    }

    private fun views(): String =
        File(WithdrawVisualFixtures.root, "feature/wallet/src/main/kotlin/com/couli/feature/wallet")
            .listFiles()!!
            .filter { it.name.startsWith("Withdraw") }
            .joinToString("\n") { it.readText() }

    private fun read(path: String) = File(WithdrawVisualFixtures.root, path).readText()
}
