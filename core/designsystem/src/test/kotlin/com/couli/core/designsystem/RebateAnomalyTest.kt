package com.couli.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 返利异常数据（编排者裁定，三端统一；TODO(N-13)）：缺端、负数、min > max、上限为 0、未知 basis 一律「暂无返利」（kind none），
 * 不显示「可返利，金额以订单为准」（只给 amount_unknown）；loginRequired 优先于 basis。
 */
class RebateAnomalyTest {
    private fun kind(
        basis: String,
        min: Long?,
        max: Long?,
        login: Boolean = false,
    ) = RebateTagModel.make(basis, min, max, login).kindRaw

    @Test
    fun anomaliesAreNone() {
        listOf("normal", "price_compare_risk").forEach { basis ->
            assertEquals("$basis 缺下限", "none", kind(basis, null, 100))
            assertEquals("$basis 缺上限", "none", kind(basis, 100, null))
            assertEquals("$basis 两端都缺", "none", kind(basis, null, null))
            assertEquals("$basis 下限为负", "none", kind(basis, -1, 100))
            assertEquals("$basis 上限为负", "none", kind(basis, -5, -1))
            assertEquals("$basis min > max 不排序", "none", kind(basis, 200, 100))
            assertEquals("$basis 上限为 0", "none", kind(basis, 0, 0))
        }
        assertEquals("未知 basis", "none", kind("something_new", 100, 200))
        val none = RebateTagModel.make("normal", 200, 100, false)
        assertEquals(PriceTerms.NO_REBATE, none.text)
        assertEquals(emptyList<String>(), none.amountSegments)
    }

    @Test
    fun amountUnknownOnlyForItsBasis() {
        assertEquals("unknown", kind("amount_unknown", null, null))
        assertEquals("none", kind("normal", null, null))
    }

    @Test
    fun loginRequiredWins() {
        assertEquals("login", kind("normal", 100, 200, login = true))
        assertEquals("login", kind("normal", 200, 100, login = true))
        assertEquals("login", kind("something_new", null, null, login = true))
    }
}
