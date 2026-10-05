package com.couli.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI 冒烟：冷启动后首屏可见（模拟器上由编排者运行：./gradlew connectedDebugAndroidTest）。 */
@RunWith(AndroidJUnit4::class)
class RootScreenSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rootScreenIsVisibleAfterLaunch() {
        composeRule.onNodeWithTag(ROOT_SCREEN_TAG).assertIsDisplayed()
    }
}
