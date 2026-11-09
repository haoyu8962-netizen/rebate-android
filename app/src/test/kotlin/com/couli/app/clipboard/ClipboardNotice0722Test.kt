package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                "clipboard.paste_hint" to "粘贴查返利",
                "clipboard.paste_hint.agent" to "让 AI 查返利",
            )
        expected.forEach { (key, value) ->
            assertEquals("草稿键 $key", value, ClipboardDraftTexts.DRAFTS[key])
            assertEquals("字典取值 $key", value, ClipboardDraftTexts.text(key))
        }
    }
}
