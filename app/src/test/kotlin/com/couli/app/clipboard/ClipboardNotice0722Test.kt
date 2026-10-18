package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** R08：逐字锁草稿，另用替代字典证明 Core 弹层真正按键取文案，没有硬编码中文。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class ClipboardNotice0722Test {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun r08_draftKeysMatchTheApprovedPlanningDraftVerbatim() {
        val expected =
            mapOf(
                "settings.clipboard_auto" to "打开 App 自动识别剪贴板",
                "settings.clipboard_auto.desc" to "开启后，在系统支持并允许时回到 App 会自动识别复制的商品链接或口令，否则可以用粘贴入口；可以随时关闭",
                "clipboard.consent.title" to "开启回到 App 自动查返利？",
                "clipboard.consent.body" to
                    "开启后，回到可用页面时会读取剪贴板，在这台设备上提取商品链接、口令或好友邀请码：商品会提交到凑狸的服务查询优惠，" +
                    "邀请码要你确认后才绑定。系统支持并允许时才会自动识别，否则可以用粘贴入口；可以随时在设置里关闭",
                "clipboard.consent.enable" to "开启",
                "clipboard.consent.not_now" to "暂不开启",
                "clipboard.paste_hint" to "粘贴查返利",
                "clipboard.paste_hint.agent" to "让 AI 查返利",
            )
        expected.forEach { (key, value) ->
            assertEquals("草稿键 $key", value, ClipboardDraftTexts.DRAFTS[key])
            assertEquals("字典取值 $key", value, ClipboardDraftTexts.text(key))
        }
    }

    @Test
    fun r08_coreNoticeUsesTextKeysAndDispatchesEachButtonToItsOwnAction() {
        var enables = 0
        var notNows = 0
        composeRule.setContent {
            ClipboardNotice(
                text = { key -> "fixture:$key" },
                onEnable = { enables += 1 },
                onNotNow = { notNows += 1 },
            )
        }
        composeRule.waitForIdle()
        for (key in listOf("title", "body", "enable", "not_now")) {
            composeRule.onNodeWithText("fixture:clipboard.consent.$key").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("clipboard_consent_enable").performClick()
        composeRule.waitForIdle()
        val afterEnable = enables to notNows
        assertEquals(1 to 0, afterEnable)
        // Core 为受控展示组件；关闭由宿主处理，此处保持展示以独立检查另一个按钮。
        composeRule.onNodeWithTag("clipboard_consent_not_now").performClick()
        composeRule.waitForIdle()
        val afterNotNow = enables to notNows
        assertEquals(1 to 1, afterNotNow)
    }
}
