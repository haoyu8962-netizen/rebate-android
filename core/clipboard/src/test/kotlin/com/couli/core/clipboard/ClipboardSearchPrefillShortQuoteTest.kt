package com.couli.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FU-AND-SEARCH-LINK-SUBMIT 测试 v6（修复 r3 / 评审 r2 S2-5）：半角双引号包裹的平台短链（无 scheme）仍是链接，[ClipboardText.searchPrefill]
 * 返回空。短链判定先补 scheme 再经 [ClipboardHostPolicy.check]（它用 URI 校验整条地址，不只看主机）：首段收尾的半角双引号若不先去掉，
 * 补成的地址含未转义的 `"`，会被拒绝，整行被误当商品名预填（变异 X6e：去掉首段的收尾清理）。
 */
class ClipboardSearchPrefillShortQuoteTest {
    @Test
    fun asciiDoubleQuotedShortLinkPrefillsNothing() {
        assertNull("半角双引号包裹的淘宝短链：留空", ClipboardText.searchPrefill("\"$TB_SHORT\""))
        assertNull("半角双引号包裹的京东短链：留空", ClipboardText.searchPrefill("\"$JD_SHORT\""))
        assertNull("半角双引号包裹的短链，第二行有商品名：留空，不退到第二行", ClipboardText.searchPrefill("\"$TB_SHORT\"\n$TITLE"))
        assertNull("「链接：」前缀加半角双引号：留空", ClipboardText.searchPrefill("链接：\"$TB_SHORT\""))
    }

    @Test
    fun asciiDoubleQuotedShortLinkStillYieldsQuotedTitle() {
        assertEquals("有「」标题仍取标题", TITLE, ClipboardText.searchPrefill("\"$TB_SHORT\"「$TITLE」"))
    }

    private companion object {
        const val TB_SHORT = "m.tb.cn/h.UxQ3kZ9"
        const val JD_SHORT = "u.jd.com/AbCdEf"
        const val TITLE = "示例纯牛奶整箱"
    }
}
