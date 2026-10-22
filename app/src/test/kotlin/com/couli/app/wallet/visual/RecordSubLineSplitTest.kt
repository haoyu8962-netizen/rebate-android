package com.couli.app.wallet.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecordSubLineSplitTest {
    @Test fun w22CollapsedKeepsOnlyFirstRenderedLineWithoutRewritingIt() {
        assertEquals(listOf("原因；¥8 已退回可提现余额"), call(listOf("原因；¥8 已退回可提现余额", "附句"), false))
        assertEquals(listOf("时效"), call(listOf("时效", "¥10 已冻结"), false))
    }

    @Test fun w24ExpandedSeparatesOnlySemicolonPreservingAllOtherPunctuation() {
        assertEquals(
            listOf("原因", "¥8 已退回可提现余额，可修改收款账号后重新提现", "第二句"),
            call(listOf("原因；¥8 已退回可提现余额，可修改收款账号后重新提现", "第二句"), true),
        )
        assertEquals(
            listOf("已转入支付宝 138****5678，到账 ¥9.2", "已代扣个税 ¥0.8"),
            call(listOf("已转入支付宝 138****5678，到账 ¥9.2", "已代扣个税 ¥0.8"), true),
        )
    }

    @Test fun emptyUnknownStatusDoesNotCreateFallbackTextOrMutateInput() {
        assertEquals(emptyList<String>(), call(emptyList(), false))
        val input = listOf("首句", "附句")
        call(input, false)
        assertEquals(listOf("首句", "附句"), input)
    }

    private fun call(
        lines: List<String>,
        expanded: Boolean,
    ): Any? {
        val type = runCatching { Class.forName("com.couli.feature.wallet.WithdrawRecordVisualsKt") }.getOrNull()
        assertNotNull("W22/W24需实现交回§2的纯展示拆行函数，不能改WithdrawalText", type)
        return checkNotNull(type)
            .getMethod("recordVisibleSubLines", List::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, lines, expanded)
    }
}
