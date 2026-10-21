package com.couli.core.me

import com.couli.generated.RiskReasonCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** CT-LOCK-02：契约新增枚举 risk_reason_category；本端手写的原因类别（G08）与它一致，契约再改时这里先失败。 */
class CtLock02RiskReasonCategoryTest {
    @Test
    fun riskReasonCategoriesMatchContractEnum() {
        assertEquals(RiskReasonCategory.known.map { it.raw }, RiskReasonText.CATEGORIES)
    }
}
