package com.couli.app.me

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualAnnouncementTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun s2RefreshFailureUsesExactlyOneAssertiveOutlet() {
        host.launch()
        assertEquals(emptyList<String>(), host.spoken)
        rule.mainClock.autoAdvance = false
        host.refresh(MeVisualFixtures.failure())
        rule.mainClock.advanceTimeByFrame()
        val text = rule.meTag(MeTags.TOAST).fetchSemanticsNode().meNoticeText()
        assertEquals(true, text.contains("a1b2c3"))
        sampleEveryFrame()
        host.announcements.assertSingle(text, LiveRegionMode.Assertive)
        host.assertReads(2)
    }

    @Test fun s2CopySuccessUsesExactlyOnePoliteOutlet() {
        copyResult(ok = true, message = "已复制邀请码")
    }

    @Test fun s2CopyFailureUsesExactlyOnePoliteOutlet() {
        copyResult(ok = false, message = "复制失败，请重试")
    }

    private fun copyResult(
        ok: Boolean,
        message: String,
    ) {
        host.env.copyOk = ok
        host.launch()
        assertEquals(emptyList<String>(), host.spoken)
        rule.mainClock.autoAdvance = false
        rule.meTag(MeTags.ME_COPY).performClick()
        sampleEveryFrame()
        host.announcements.assertSingle(message, LiveRegionMode.Polite)
        host.assertReads(1)
    }

    @Test fun s2EyeToggleUsesOnePoliteOutletAndIdleDoesNotRepeat() {
        host.launch()
        assertEquals(emptyList<String>(), host.spoken)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("隐藏金额").performClick()
        sampleEveryFrame()
        host.announcements.assertSingle("金额已隐藏", LiveRegionMode.Polite)
        rule.onNodeWithContentDescription("显示金额").performClick()
        sampleEveryFrame()
        val deliveries = host.announcements.sample()
        assertEquals(listOf("金额已隐藏", "金额已显示"), deliveries.map { it.text })
        assertEquals(List(2) { LiveRegionMode.Polite }, deliveries.map { it.priority })
        host.assertReads(1)
    }

    private fun sampleEveryFrame() {
        host.announcements.sample()
        repeat(140) {
            rule.mainClock.advanceTimeByFrame()
            host.announcements.sample()
        }
    }

    @Test fun s2SharedToastDefaultsStillAnnounceActivelyOncePerMessageOrId() {
        val probe = MeVisualAnnouncementProbe(rule)
        val message = mutableStateOf("共用轻提示")
        val id = mutableStateOf<Any?>(null)
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides probe.announcer) {
                CouliTheme { CouliToast(message.value, announcementId = id.value) }
            }
        }
        assertEquals(listOf(MeVisualAnnouncementProbe.Delivery("共用轻提示", "active")), probe.sample())
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            assertEquals(1, probe.sample().size)
        }
        rule.runOnIdle { id.value = 1 }
        assertEquals(listOf("共用轻提示", "共用轻提示"), probe.messages)
        rule.runOnIdle { message.value = "新共用轻提示" }
        val all = probe.sample()
        assertEquals(listOf("共用轻提示", "共用轻提示", "新共用轻提示"), all.map { it.text })
        assertEquals(List(3) { "active" }, all.map { it.channel })
    }

    @Test fun s2ProbeCountsBothOutletsAndRejectsDuplicateText() {
        val probe = MeVisualAnnouncementProbe(rule)
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides probe.announcer) {
                CouliTheme {
                    CouliToast("重复播报反例", Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
            }
        }
        assertEquals(listOf("active", "liveRegion"), probe.sample().map { it.channel })
        assertThrows(AssertionError::class.java) { probe.assertSingle("重复播报反例", LiveRegionMode.Assertive) }
    }

    @Test fun s2ProbeAcceptsSemanticOnlyAndCountsChangedAndReinsertedRegions() {
        val probe = MeVisualAnnouncementProbe(rule)
        val message = mutableStateOf("系统语义首条")
        val visible = mutableStateOf(true)
        rule.setContent {
            if (visible.value) Text(message.value, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        probe.assertSingle("系统语义首条", LiveRegionMode.Polite)
        repeat(3) { probe.assertSingle("系统语义首条", LiveRegionMode.Polite) }
        assertThrows(AssertionError::class.java) { probe.assertSingle("系统语义首条", LiveRegionMode.Assertive) }
        rule.runOnIdle { message.value = "系统语义次条" }
        assertEquals(listOf("系统语义首条", "系统语义次条"), probe.messages)
        rule.runOnIdle { visible.value = false }
        assertEquals(2, probe.sample().size)
        rule.runOnIdle { visible.value = true }
        assertEquals(listOf("系统语义首条", "系统语义次条", "系统语义次条"), probe.messages)
    }
}
