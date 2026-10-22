package com.couli.app.me

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 只变异测试 fixture，证明逐帧判据确实拒绝切换当帧的泄漏；不修改产品源码。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class MeVisualTransitionProbeTest {
    @get:Rule val rule = createComposeRule()
    private val active = mutableListOf<String>()
    private var identity = "old-account"
    private val message = mutableStateOf("刷新失败 a1b2c3")
    private val copied = "已复制邀请码"

    private fun launch() {
        rule.setContent {
            if (message.value.isNotEmpty()) Text(message.value, Modifier.testTag(MeTags.TOAST))
            LaunchedEffect(message.value) {
                if (message.value == copied) {
                    withFrameNanos { }
                    message.value = ""
                }
            }
        }
        rule.mainClock.autoAdvance = false
    }

    @Test fun s1ProbeRejectsCallbackInsideSwitchBeforeAnyClockAdvance() {
        launch()
        val failure =
            assertThrows(AssertionError::class.java) {
                probe().check(copied, finished = { identity == "new-account" }) {
                    identity = "new-account"
                    message.value = ""
                    active += copied
                }
            }
        assertTrue(failure.message.orEmpty().contains("same-frame-after-action"))
        assertTrue(failure.message.orEmpty().contains("newActive=[$copied]"))
    }

    @Test fun s1ProbeRejectsVisibleCopyThatDisappearsNextFrame() {
        launch()
        val failure =
            assertThrows(AssertionError::class.java) {
                probe().check(copied, finished = { identity == "new-account" }) {
                    identity = "new-account"
                    message.value = copied
                }
            }
        assertTrue(failure.message.orEmpty().contains("visible=[$copied]"))
        assertTrue(failure.message.orEmpty().contains("newActive=[]"))
    }

    @Test fun s1ProbeAcceptsCleanSwitchAndChecksTheFullWindow() {
        launch()
        probe().check(copied, finished = { identity == "new-account" }) {
            identity = "new-account"
            message.value = ""
        }
    }

    private fun probe() = MeVisualTransitionProbe(rule, active) { identity }
}
