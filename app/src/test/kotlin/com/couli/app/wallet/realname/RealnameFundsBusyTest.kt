package com.couli.app.wallet.realname

import com.couli.core.wallet.WalletErrors
import com.couli.feature.wallet.WalletTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 40901（D10-887 ⑤；向量 e40901_busy 的 maxPostsIn6s）：一直回 40901 时 6 秒内最多 4 次、全部同键同体、不重记同意；用完停下、出 error.40901、保留原键（表单不变再提交沿用，
 * 改了表单作废）。时间同时走实际时钟与主线程时钟（[observe]），实现用 delay 还是定时器都能跑。
 */
class RealnameFundsBusyTest : RealnameFundsHarness() {
    private fun busyForever() {
        submitReply = { envelope("null", code = BUSY) }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
    }

    /** 跑满 [windowMs]，每 [OBSERVE_STEP_MS] 记一次累计提交数，返回最后一次。 */
    private fun runWindow(windowMs: Long): Int {
        var elapsed = 0L
        while (elapsed < windowMs) {
            observe(OBSERVE_STEP_MS)
            elapsed += OBSERVE_STEP_MS
        }
        return submits().size
    }

    @Test fun atMostFourPostsWithinSixSecondsSameKeyAndBody() {
        busyForever()
        val posted = runWindow(SIX_SECONDS_MS)
        assertTrue("6 秒内至少自动重发一次（共 $posted 次）", posted >= 2)
        assertTrue("6 秒内最多 4 次（共 $posted 次，D10-887 ⑤）", posted <= MAX_POSTS)
        val posts = submits()
        val key = posts[0].header("Idempotency-Key")
        assertTrue("全部同键", posts.all { it.header("Idempotency-Key") == key })
        assertTrue("全部同体", posts.all { it.body == posts[0].body })
        assertEquals("重发不重记同意", 1, consents().size)
    }

    @Test fun keepsLoadingBetweenResendsThenStopsAtLimit() {
        busyForever()
        settle()
        assertFalse("40901 保持在途（按钮锁住）", submitEnabled())
        assertFalse("输入框保持锁定", nameFieldEnabled())
        assertTrue("不算错误条", errorBar() == null)
        assertFalse("不按已实名", realnameVerified())
        // 用完之后不再发（看 6 秒之外）
        runWindow(SIX_SECONDS_MS)
        val atSix = submits().size
        runWindow(AFTER_LIMIT_MS)
        assertEquals("用完上限后不再重发", atSix, submits().size)
    }

    @Test fun exhaustionShowsBusyErrorAndKeepsKey() {
        busyForever()
        runWindow(SIX_SECONDS_MS + AFTER_LIMIT_MS)
        idleUntil { errorBar() != null }
        assertEquals(
            "用完上限显示 error.40901",
            WalletErrors.message(wallet.deps.texts, "error.40901", TRACE, false),
            errorBar(),
        )
        assertTrue("用完上限后可再提交", submitEnabled())
        assertFalse("不按已实名", realnameVerified())
        val key = submits()[0].header("Idempotency-Key")
        val posted = submits().size
        submitReply = { success() }
        resubmit()
        assertEquals("保留原键（表单不变）", key, submits()[posted].header("Idempotency-Key"))
        idleUntil { realnameVerified() }
        assertEquals("成功后照常完成", posted + 1, submits().size)
        assertTrue("成功后出栈", top()?.name != "RealName")
    }

    @Test fun exhaustionThenEditedFormUsesNewKey() {
        busyForever()
        runWindow(SIX_SECONDS_MS + AFTER_LIMIT_MS)
        idleUntil { errorBar() != null }
        val key = submits()[0].header("Idempotency-Key")
        val body = submits()[0].body
        fillForm(EDITED_NAME, EDITED_ID)
        submitReply = { success() }
        resubmit()
        val last = submits().last()
        assertFalse("改了表单换新键（D10-887 ③）", last.header("Idempotency-Key") == key)
        assertKeyShape(last.header("Idempotency-Key"))
        assertFalse("新键配新请求体", last.body == body)
        idleUntil { realnameVerified() }
    }

    @Test fun declineDuringBusyIsImpossibleAndLeavingStopsResend() {
        busyForever()
        settle()
        assertFalse("在途时不开半屏", has(WalletTags.CONSENT_SHEET))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val afterLeave = submits().size
        runWindow(LEAVE_WINDOW_MS)
        assertEquals("离页后不再重发", afterLeave, submits().size)
    }

    private companion object {
        const val BUSY = 40901
        const val SIX_SECONDS_MS = 6_000L
        const val AFTER_LIMIT_MS = 3_000L
        const val LEAVE_WINDOW_MS = 4_000L
        const val MAX_POSTS = 4
    }
}
