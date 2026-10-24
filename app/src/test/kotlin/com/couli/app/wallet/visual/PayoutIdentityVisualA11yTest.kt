package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.readableText
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 读屏（brief §4.2）：单选读标题 + 说明 + 选中；脱敏串逐字读；勾选框读状态（劳务协议另读版本号）；挪位后关联不丢。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayoutIdentityVisualA11yTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun payoutOptionsReadTitleDescriptionRoleAndSelection() {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("unbound"))
        host.launch(PayoutIdentityPage.PAYOUT)
        val alipay = merged(WalletTags.PAYOUT_METHOD_ALIPAY)
        val bank = merged(WalletTags.PAYOUT_METHOD_BANK)
        listOf(alipay to ("payout.alipay" to "payout.alipay_desc"), bank to ("payout.bank" to "payout.bank_desc")).forEach { (node, keys) ->
            assertEquals(Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
            val read = node.readableText()
            assertTrue("单选读标题：$read", read.contains(t(keys.first)))
            assertTrue("单选读说明：$read", read.contains(t(keys.second)))
        }
        assertEquals(true, alipay.config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, bank.config.getOrNull(SemanticsProperties.Selected))
        assertEquals(
            "输入框读字段名",
            t("payout.alipay_account"),
            merged(WalletTags.PAYOUT_LOGON).config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        )
    }

    @Test fun boundCardReadsMaskedAccountAndPayeeAsWords() {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("boundAlipay"))
        host.launch(PayoutIdentityPage.PAYOUT)
        val star = t("wallet.star_spoken")
        val account =
            p
                .text("138****5678")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
        assertEquals("账号读屏把 * 读成字", listOf("138****5678".replace("*", star)), account)
        val payee = "**三".replace("*", star)
        val rows =
            rule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                .fetchSemanticsNodes()
                .map { it.readableText() }
        assertTrue("收款人一行合并读出标签与逐字值：$rows", rows.any { it.contains(t("payout.payee_label")) && it.contains(payee) })
        assertTrue("剩余次数一行合并读出：$rows", rows.any { it.contains(t("payout.remaining_label")) })
    }

    @Test fun consentCheckReadsSentenceRoleAndState() {
        host.launch(PayoutIdentityPage.REALNAME)
        host.fillRealName()
        p.tag(WalletTags.REALNAME_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        val check = merged(WalletTags.CONSENT_CHECK)
        assertEquals(Role.Checkbox, check.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.Off, check.config.getOrNull(SemanticsProperties.ToggleableState))
        assertTrue(check.readableText().contains(t("realname.consent.check")))
    }

    @Test fun laborCheckReadsStateAndAgreementVersion() {
        host.launch(PayoutIdentityPage.LABOR)
        val check = merged(WalletTags.LABOR_CHECK)
        assertEquals(Role.Checkbox, check.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.Off, check.config.getOrNull(SemanticsProperties.ToggleableState))
        val read = check.readableText()
        assertTrue("勾选读协议名：$read", read.contains(t("labor.check")))
        assertTrue("brief §4.2 劳务勾选框读版本号：$read", read.contains(WalletDraftTexts.format("labor.version", mapOf("version" to "v1"))))
    }

    private fun merged(tag: String): SemanticsNode = rule.onNodeWithTag(tag).fetchSemanticsNode()

    private fun t(key: String) = WalletDraftTexts.text(key)
}
