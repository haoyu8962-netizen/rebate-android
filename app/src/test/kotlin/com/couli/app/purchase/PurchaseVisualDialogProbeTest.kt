package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S2-4 正反控制：正确图标可通过，缺图标、错色、错轮廓必须失败。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualDialogProbeTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun errorAlertControlPasses() = check("correct", false)

    @Test fun darkErrorAlertControlPasses() = check("correct", true)

    @Test fun secondaryInfoControlPasses() = check("info", false)

    @Test fun darkSecondaryInfoControlPasses() = check("info", true)

    @Test fun missingTitleIconIsRejected() = check("missing", false)

    @Test fun wrongTitleIconColorIsRejected() = check("color", false)

    @Test fun wrongTitleIconShapeIsRejected() = check("shape", false)

    private fun check(
        kind: String,
        dark: Boolean,
    ) {
        val info = kind == "info"
        val probe = PurchaseVisualDialogIconProbe(rule, host)
        val expected = if (info) CouliIcons.info else CouliIcons.alert
        probe.reference(expected, if (info) PurchaseIconTone.SECONDARY else PurchaseIconTone.ERROR)
        val reference = host.reference
        host.reference = {
            reference()
            CouliDialog(
                "图标对照",
                "知道了",
                {},
                {},
                titleIcon =
                    when (kind) {
                        "missing" -> null
                        "shape" -> CouliIcons.copy
                        else -> expected
                    },
                titleIconTint = if (info || kind == "color") CouliColors.textSecondary else CouliColors.statusErrorText,
            )
        }
        host.launch(dark = dark)
        if (kind in listOf("correct", "info")) {
            probe.assertLeadingIcon("图标对照")
        } else {
            assertThrows(AssertionError::class.java) { probe.assertLeadingIcon("图标对照") }
        }
        host.counts(open = 0)
    }
}
