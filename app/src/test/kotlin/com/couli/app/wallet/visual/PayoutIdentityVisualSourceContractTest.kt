package com.couli.app.wallet.visual

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只冻结视图接线与共用默认值；真实门槛、票据、计数由行为测试另验，不以源码代替渲染。 */
class PayoutIdentityVisualSourceContractTest {
    private val wallet = File(PayoutIdentityVisualFixtures.root, "feature/wallet/src/main/kotlin/com/couli/feature/wallet")

    @Test fun payoutSaveChooseAndChangeCallbacksStayLiteral() {
        val source = views()
        listOf(
            "onClick = { controller.save(formOwner) }.tapped(WalletTags.PAYOUT_SAVE)",
            "enabled = flow.canSubmit && !blocked",
            "controller.chooseMethod(WalletJson.ALIPAY, owner)",
            "controller.chooseMethod(WalletJson.BANK_CARD, owner)",
            "controller.change(shownVersion, account)",
            "controller.onLogonChange(it, owner)",
            "controller.onPayeeChange(it, owner)",
            "controller.onCardChange(it, owner)",
            "controller.onBankChange(it, owner)",
            "controller.flow.askAbandon(key, owner)",
            "controller.flow.confirm(key, owner)",
            "controller.flow.abandon(key, formOwner)",
            ".imePadding()",
        ).forEach { assertTrue("收款账号票据 / 门槛原样：$it", source.containsCode(it)) }
    }

    @Test fun identityConsentSignAndToggleCallbacksStayLiteral() {
        val source = views()
        listOf(
            "onClick = { controller.submit(owner) }.tapped(WalletTags.REALNAME_SUBMIT)",
            "enabled = controller.submitEnabled",
            "{ controller.agreeAndSubmit(owner) }.tapped(WalletTags.CONSENT_OK)",
            "enabled = state.consentChecked",
            "controller::declineConsent",
            "{ controller.toggleConsent(owner) }",
            "{ controller.toggle(owner) }",
            "onClick = { controller.sign(owner) }.tapped(WalletTags.LABOR_SIGN)",
            "enabled = controller.signEnabled && !blocked",
            "controller.onNameChange(it, owner)",
            "controller.onIdChange(it, owner)",
        ).forEach { assertTrue("实名 / 劳务票据 / 门槛原样：$it", source.containsCode(it)) }
        assertFalse("G-W24：视觉任务不切真实接口（不引用 recordConsent / signLaborAgreement）", source.contains("recordConsent"))
        assertFalse(source.contains("signLaborAgreement"))
    }

    @Test fun w94CheckRowsMoveToDesignSystemRoundAgreementRow() {
        val identity = File(wallet, "IdentityScreens.kt").readText()
        assertTrue("W94 / W100 勾选改用 DS AgreementCheckRow（圆形 18）", identity.contains("AgreementCheckRow("))
        val selection =
            File(
                PayoutIdentityVisualFixtures.root,
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliSelection.kt",
            ).readText()
        val signature =
            selection
                .substringAfter("fun AgreementCheckRow(")
                .substringBefore(") {")
                .replace(Regex("\\s+"), " ")
                // v3：ktlint_official 多参签名分行，"(" 后首字符是换行；归一后去首尾空白再比，参数名 / 顺序 / 类型不放宽。
                .trim()
        val head = "checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: AnnotatedString, modifier: Modifier = Modifier,"
        assertTrue("原四个参数顺序不变、modifier 仍是首个可选参数", signature.startsWith(head))
    }

    /** v3：按 Kotlin 记号序列比对，忽略空白与换行（合规的分行 / 链式换行不应翻红），记号本身逐字不放宽。 */
    private fun String.containsCode(needle: String): Boolean = squash(this).contains(squash(needle))

    private fun squash(text: String): String = text.replace(Regex("\\s+"), "")

    private fun views(): String =
        wallet
            .listFiles()!!
            .filter { it.name.startsWith("Payout") || it.name.startsWith("Identity") || it.name == "WalletUi.kt" }
            .filterNot { it.name.endsWith("Controller.kt") || it.name == "IdentityControllers.kt" }
            .joinToString("\n") { it.readText() }
}
