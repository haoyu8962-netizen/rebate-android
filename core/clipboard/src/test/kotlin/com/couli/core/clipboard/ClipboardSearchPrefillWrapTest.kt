package com.couli.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FU-AND-SEARCH-LINK-SUBMIT 测试 v5（修复 r2 / 评审 S2-6，承 D10-666 ① 与编排 23:46 短链口径）：首行是被常见包装裹住的链接——半角 / 全角括号、
 * 「链接：」类前缀、前后引号——仍是链接、不是商品名，[ClipboardText.searchPrefill] 返回空，不退到第二行。判链接复用候选白名单，不另写主机正则；
 * 以 HTTP 字母开头的商品名、带括号前缀的商品名不误判（守护）。
 */
class ClipboardSearchPrefillWrapTest {
    @Test
    fun parenthesizedLinkPrefillsNothing() {
        assertNull("全角括号包裹的 https 链接：留空", ClipboardText.searchPrefill("（$LINK）"))
        assertNull("半角括号包裹的 https 链接：留空", ClipboardText.searchPrefill("($LINK)"))
        assertNull("全角括号包裹的链接：留空，也不退到第二行", ClipboardText.searchPrefill("（$LINK）\n$TITLE"))
    }

    @Test
    fun labelPrefixedLinkPrefillsNothing() {
        assertNull("「链接：」全角冒号前缀：留空", ClipboardText.searchPrefill("链接：$LINK"))
        assertNull("「链接:」半角冒号前缀：留空", ClipboardText.searchPrefill("链接:$LINK"))
        assertNull("「链接：」后有空格：留空，也不退到第二行", ClipboardText.searchPrefill("链接： $LINK\n$TITLE"))
    }

    @Test
    fun quotedLinkPrefillsNothing() {
        assertNull("中文双引号包裹：留空", ClipboardText.searchPrefill("“$LINK”"))
        assertNull("中文单引号包裹：留空", ClipboardText.searchPrefill("‘$LINK’"))
        assertNull("半角双引号包裹：留空", ClipboardText.searchPrefill("\"$LINK\""))
        assertNull("半角单引号包裹：留空", ClipboardText.searchPrefill("'$LINK'"))
    }

    @Test
    fun wrappedPlatformShortLinkPrefillsNothing() {
        assertNull("全角括号包裹的淘宝短链（无 scheme）：留空", ClipboardText.searchPrefill("（$TB_SHORT）"))
        assertNull("「链接：」前缀的京东短链（无 scheme）：留空", ClipboardText.searchPrefill("链接：$JD_SHORT"))
        assertNull("引号包裹的淘宝短链（无 scheme）：留空", ClipboardText.searchPrefill("“$TB_SHORT”"))
    }

    @Test
    fun quotedTitleStillWinsOverWrappedLink() {
        assertEquals("有「」标题仍取标题", TITLE, ClipboardText.searchPrefill("链接：$LINK「$TITLE」"))
    }

    @Test
    fun productNamesAreNotMistakenForLinks() {
        assertEquals("以 HTTP 字母开头的商品名原样", "HTTP转接头 USB3.0", ClipboardText.searchPrefill("HTTP转接头 USB3.0"))
        assertEquals("括号前缀的商品名原样", "（新款）HTTP转接头 USB3.0", ClipboardText.searchPrefill("（新款）HTTP转接头 USB3.0"))
        assertEquals("「链接」字样开头的商品名原样", "链接线 Type-C 1米", ClipboardText.searchPrefill("链接线 Type-C 1米"))
        assertEquals("首行商品名、第二行包裹链接：取首行", TITLE, ClipboardText.searchPrefill("$TITLE\n（$LINK）"))
    }

    private companion object {
        const val LINK = "https://item.taobao.com/item.htm?id=123456"
        const val TB_SHORT = "m.tb.cn/h.UxQ3kZ9"
        const val JD_SHORT = "u.jd.com/AbCdEf"
        const val TITLE = "示例纯牛奶整箱"
    }
}
