package com.couli.app.wallet.visual

import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliTagTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WithdrawalStatusPillTest {
    @Test fun w21PureStatusToneMappingIncludesUnknownFallback() {
        val expected =
            mapOf(
                "PENDING_REVIEW" to CouliTagTone.NEUTRAL,
                "APPROVED" to CouliTagTone.NEUTRAL,
                "PAYING" to CouliTagTone.NEUTRAL,
                "PAID_API" to CouliTagTone.SUCCESS,
                "PAID_MANUAL" to CouliTagTone.SUCCESS,
                "REJECTED" to CouliTagTone.WARNING,
                "FAILED" to CouliTagTone.WARNING,
                "FUTURE" to CouliTagTone.NEUTRAL,
                "" to CouliTagTone.NEUTRAL,
            )
        expected.forEach { (status, tone) -> assertEquals(status, tone, call("withdrawalStatusTone", status)) }
    }

    @Test fun w21PureIconMappingDoesNotConfusePaidAndPaying() {
        listOf("PENDING_REVIEW", "APPROVED", "PAYING", "FUTURE").forEach {
            assertEquals(CouliIcons.clock, call("withdrawalStatusIcon", it))
        }
        listOf("PAID_API", "PAID_MANUAL").forEach { assertEquals(CouliIcons.check, call("withdrawalStatusIcon", it)) }
        listOf("REJECTED", "FAILED").forEach { assertEquals(CouliIcons.alert, call("withdrawalStatusIcon", it)) }
    }

    private fun call(
        method: String,
        status: String,
    ): Any? {
        val type = runCatching { Class.forName("com.couli.feature.wallet.WithdrawRecordVisualsKt") }.getOrNull()
        assertNotNull("W21需实现交回§2的纯映射函数：$method（当前底版未实现）", type)
        return checkNotNull(type).getMethod(method, String::class.java).invoke(null, status)
    }
}
