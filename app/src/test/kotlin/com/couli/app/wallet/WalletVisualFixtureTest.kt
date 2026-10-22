package com.couli.app.wallet

import com.couli.core.wallet.WalletJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WalletVisualFixtureTest {
    @Test fun everyVisualSummaryUsesTheLockedParser() {
        listOf(
            "normal",
            "frozen",
            "negative",
            "risk",
            "disabled",
            "no_month",
            "overdue",
            "received_zero",
            "long_amounts",
            "no_sum",
        ).forEach {
            assertNotNull(it, WalletJson.summary(WalletVisualFixtures.summary(it)))
        }
    }

    @Test fun everyMoneyFieldRejectsMissingNullAndWrongTypeInsteadOfFillingZero() {
        val base = WalletVisualFixtures.summary()
        base.keys.filter { it.endsWith("_fen") }.forEach { field ->
            assertNull("missing $field", WalletJson.summary(JsonObject(base - field)))
            assertNull("null $field", WalletJson.summary(JsonObject(base + (field to JsonNull))))
            assertNull("string $field", WalletJson.summary(JsonObject(base + (field to JsonPrimitive("0")))))
        }
    }

    @Test fun inconsistentServerAmountsRemainIndependent() {
        val summary = checkNotNull(WalletJson.summary(WalletVisualFixtures.summary("no_sum")))
        assertEquals(9900L, summary.availableFen)
        assertEquals(1234L, summary.withdrawableFen)
        assertEquals(5678L, summary.estimatedTotalFen)
        assertEquals(1L, summary.estimatedFen)
        assertEquals(2L, summary.pendingCreditFen)
    }
}
