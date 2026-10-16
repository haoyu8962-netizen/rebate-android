package com.couli.app.followup

import com.couli.app.login.DebugWechatAdapter
import com.couli.feature.login.UnavailableWechatAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FU-AND-0717 F-X-12：Debug / Staging 假微信适配器的提示口径（每次现判统一闸门）。 */
class FollowupAnd0717DebugTest {
    @Test
    fun fuand0717_x12_theDebugWechatAdapterHintsOnlyWhileTheFakeServerIsOff() {
        var open = false
        val wechat = DebugWechatAdapter({ open }, UnavailableWechatAdapter(), delayMillis = 1)
        assertFalse(wechat.adapterAvailable())
        assertEquals("调试用，需开本机假服务", wechat.debugHint())
        open = true
        assertTrue(wechat.adapterAvailable())
        assertNull("假服务开着：正常授权，不提示", wechat.debugHint())
        assertNull("Release / 正式适配器不提示", UnavailableWechatAdapter().debugHint())
    }
}
