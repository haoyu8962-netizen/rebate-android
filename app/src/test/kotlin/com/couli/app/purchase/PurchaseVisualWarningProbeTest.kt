package com.couli.app.purchase

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliTypography
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S2-3 像素判据的独立样本；fixture 按画板布局，不充当产品测试或截图基准。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualWarningProbeTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun warningBlockControlPasses() = check("correct", false)

    @Test fun darkWarningBlockControlPasses() = check("correct", true)

    @Test fun wrongBackgroundIsRejected() = check("background", false)

    @Test fun wrongTextColorIsRejected() = check("text", false)

    @Test fun wrongIconColorIsRejected() = check("icon", false)

    private fun check(
        kind: String,
        dark: Boolean,
    ) {
        val probe = PurchaseVisualDialogIconProbe(rule, host)
        probe.reference(CouliIcons.alert, PurchaseIconTone.WARNING)
        val reference = host.reference
        host.reference = {
            reference()
            CouliDialog("警告块对照", "知道了", {}, {}, body = { WarningSample(kind) })
        }
        host.launch(dark = dark)
        val verify = {
            probe.assertWarningBackground(TITLE, NOTE)
            probe.assertWarningText(TITLE, NOTE)
            probe.assertLeadingIcon(TITLE, centerLine = false)
        }
        if (kind == "correct") {
            verify()
        } else {
            val error = assertThrows(AssertionError::class.java) { verify() }
            val reason =
                when (kind) {
                    "background" -> "警告块缺令牌底色"
                    "text" -> "警告块文字色"
                    else -> "轻提示缺指定颜色图标"
                }
            assertTrue("错误原因必须对应反例：${error.message}", error.message.orEmpty().startsWith(reason))
        }
        host.counts(open = 0)
    }

    @androidx.compose.runtime.Composable
    private fun WarningSample(kind: String) {
        val ink = CouliColors.statusWarningText
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (kind == "background") CouliColors.backgroundSurface else CouliColors.statusWarningBackground)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CouliIcons.alert, null, Modifier.size(20.dp), tint = if (kind == "icon") CouliColors.textSecondary else ink)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val color = if (kind == "text") CouliColors.textPrimary else ink
                Text(TITLE, style = CouliTypography.footnote, color = color)
                Text(NOTE, style = CouliTypography.footnote, color = color)
            }
        }
    }

    companion object {
        private const val TITLE = "警告标题"
        private const val NOTE = "警告正文"
    }
}
