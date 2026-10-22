package com.couli.app.wallet.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pair.first 是主按钮文案键，second 是次按钮。回调另由真实点击逐次证明。 */
class DialogSlotMappingTest {
    @Test fun w60RiskPrimaryIsAppealSecondaryIsAcknowledge() {
        assertEquals("cs.appeal_entry" to "withdraw.risk_blocked.ok", call("riskDialogSlots"))
    }

    @Test fun w61AbandonPrimaryIsCancelSecondaryIsDestructive() {
        assertEquals("pending_confirm.abandon.cancel" to "pending_confirm.abandon.ok", call("abandonDialogSlots"))
    }

    private fun call(name: String): Any? {
        val type = runCatching { Class.forName("com.couli.feature.wallet.WalletDialogSlotsKt") }.getOrNull()
        assertNotNull("G-W16：需要交回 §2 的无副作用槽位映射 $name", type)
        return checkNotNull(type).getDeclaredMethod(name).invoke(null)
    }
}
