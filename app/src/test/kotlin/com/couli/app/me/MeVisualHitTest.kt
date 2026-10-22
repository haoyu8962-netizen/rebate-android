package com.couli.app.me

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeVisualHitTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun m8CopyAllFourEdgesOnlyCopyOncePerTouch() = copyEdges(1f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun m8CopyAt200NarrowKeepsFourEdgesAndInviterSeparate() = copyEdges(2f)

    private fun copyEdges(fontScale: Float) {
        host.launch(fontScale = fontScale)
        val copy = rule.meTag(MeTags.ME_COPY)
        val hit = copy.fetchSemanticsNode().touchBoundsInRoot
        val d = copy.meDensity()
        val inviter = rule.meTag(MeTags.ME_INVITER).meBounds()
        assertTrue("复制扩展区不能覆盖邀请人文字", hit.intersect(inviter).let { it.width <= 0 || it.height <= 0 })
        val points =
            listOf(
                Offset(hit.center.x, hit.top + d),
                Offset(hit.center.x, hit.bottom - d),
                Offset(hit.left + d, hit.center.y),
                Offset(hit.right - d, hit.center.y),
            )
        points.forEachIndexed { index, point ->
            rule.meTouch(point)
            assertEquals("边缘$point", List(index + 1) { "K7M3QX" }, host.env.copies)
            assertTrue(host.env.opened.isEmpty())
            host.assertReads(1)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun m9m20WrappedEyeAndWithdrawNeverShareHitAreaAndEdgesKeepActions() {
        host.launch(fontScale = 2f)
        rule.meTag(MeTags.ME_BALANCE).performScrollTo()
        val eye = rule.onNodeWithContentDescription("隐藏金额")
        val withdraw = rule.meTag(MeTags.ME_WITHDRAW)
        val a = eye.fetchSemanticsNode().touchBoundsInRoot
        val b = withdraw.fetchSemanticsNode().touchBoundsInRoot
        val d = eye.meDensity()
        assertMeTargetsSeparate(a, b, d)
        for (point in listOf(
            Offset(a.center.x, a.top + d),
            Offset(a.center.x, a.bottom - d),
            Offset(a.left + d, a.center.y),
            Offset(a.right - d, a.center.y),
        )) {
            val before = host.mask.hidden.value
            rule.meTouch(point)
            assertEquals(!before, host.mask.hidden.value)
            assertTrue(host.env.opened.isEmpty())
            host.assertReads(1)
        }
        for ((index, point) in listOf(
            Offset(b.center.x, b.top + d),
            Offset(b.center.x, b.bottom - d),
            Offset(b.left + d, b.center.y),
            Offset(b.right - d, b.center.y),
        ).withIndex()) {
            rule.meTouch(point)
            assertEquals(List(index + 1) { "Wallet" }, host.env.opened.map { it.first })
            assertEquals(false, host.mask.hidden.value)
            host.assertReads(1)
        }
    }

    @Test fun m4GuideBodyAndCloseHaveSeparateTargetsAndFacingEdges() {
        host.env.guide = true
        host.launch()
        val body =
            rule.onNode(hasClickAction() and (hasText("通知没有开启", substring = true) or hasContentDescription("通知没有开启", substring = true)))
        val close = rule.meTag(MeTags.ME_GUIDE_CLOSE)
        val a = body.fetchSemanticsNode().touchBoundsInRoot
        val b = close.fetchSemanticsNode().touchBoundsInRoot
        val d = close.meDensity()
        assertMeTargetsSeparate(a, b, d)
        rule.meTouch(Offset(a.right - d, b.center.y.coerceIn(a.top + d, a.bottom - d)))
        assertEquals(1, host.env.settings)
        assertEquals(null, host.deps.local!!.guideClosedAt())
        rule.meTouch(Offset(b.left + d, b.center.y))
        assertEquals(1, host.env.settings)
        assertEquals(MeVisualFixtures.now, host.deps.local!!.guideClosedAt())
        host.assertReads(1)
    }
}
