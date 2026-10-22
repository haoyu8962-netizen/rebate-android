package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 与四个截图入口相同尺寸和插槽，但只测几何、不录基准。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1082dp-xhdpi")
class PurchaseVisualPendingLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w390dp-h1130dp-xhdpi")
    fun notifyCardMatchesFormalWidth() = layout(false, false)

    @Test
    @Config(qualifiers = "w390dp-h1050dp-xhdpi")
    fun decidedCardMatchesFormalWidth() = layout(false, true)

    @Test fun claimCardMatchesFormalWidth() = layout(true, true)

    @Test fun darkClaimCardMatchesFormalWidth() = layout(true, true, dark = true)

    private fun layout(
        claim: Boolean,
        decided: Boolean,
        dark: Boolean = false,
    ) {
        val pending = PurchaseVisualPending(host, rule)
        pending.permission = if (decided) "granted" else "not_determined"
        host.extraBanner = { PurchaseVisualPendingSlot(pending) }
        host.launch(dark = dark, shell = true)
        pending.show(claim)
        rule.assertPurchasePendingSlot()
        host.counts(open = 1, jumps = 1)
        org.junit.Assert.assertEquals(0, pending.notifications)
        org.junit.Assert.assertEquals(0, pending.claims)
    }
}
