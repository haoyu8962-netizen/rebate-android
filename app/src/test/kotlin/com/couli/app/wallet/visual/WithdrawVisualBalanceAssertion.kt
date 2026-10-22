package com.couli.app.wallet.visual

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import com.couli.feature.wallet.WalletTags
import org.junit.Assert.assertTrue

/** AWIT W37 的唯一布局迁移：同一余额语义节点仍须完整携带标签和服务端金额。 */
internal fun ComposeContentTestRule.assertWithdrawVisualBalance() {
    val node = onNodeWithTag(WalletTags.WITHDRAW_BALANCE).fetchSemanticsNode()
    val text =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString(" ") { it.text }
    val description =
        node.config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
            .joinToString(" ")
    assertTrue("页头 withdrawable_fen 与标签", listOf(text, description).any { "可提现余额" in it && "¥12.34" in it })
}
