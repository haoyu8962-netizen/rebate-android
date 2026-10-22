package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.ProductVisualFixtures
import com.couli.app.product.textLayout
import com.couli.core.designsystem.PlatformIconSize
import com.couli.feature.trade.TradeTags
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 源码断言仅负责共用接口默认值/接线；实际按钮行为与颜色另由独立 UI 用例覆盖。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    private fun source(module: String): String =
        File(ProductVisualFixtures.root, "$module/src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }

    private fun signature(name: String): String = source("core/designsystem").substringAfter("fun $name(").substringBefore(") {")

    @Test fun optionalDialogTitleIconDefaultsToNull() {
        assertTrue("titleIcon 必须可选且默认null", Regex("titleIcon:\\s*ImageVector\\?\\s*=\\s*null").containsMatchIn(signature("CouliDialog")))
        assertTrue("内容接口也须默认null", Regex("titleIcon:\\s*ImageVector\\?\\s*=\\s*null").containsMatchIn(signature("CouliDialogContent")))
    }

    @Test fun optionalSheetLeadingAndExistingTitleStyleKeepOldDefaults() {
        for (name in listOf("CouliSheet", "CouliSheetBody")) {
            val signature = signature(name)
            assertTrue("$name 前置槽默认null", Regex("titleLeading:.*=\\s*null").containsMatchIn(signature))
            assertTrue("$name 原标题17/600默认保持", signature.contains("titleStyle: TextStyle = CouliTextStyles.bodySemibold"))
        }
    }

    @Test fun toastTintIsOptionalAndDefaultKeepsSuccessColor() {
        assertTrue(
            "不能全局把旧Toast变成警告色",
            Regex("iconTint:\\s*Color\\s*=\\s*CouliColors.statusSuccessText").containsMatchIn(signature("CouliToast")),
        )
    }

    @Test fun platformSheetSizeIs32AndExistingSizesDoNotChange() {
        val sheet = PlatformIconSize.entries.find { it.name == "SHEET" }
        assertNotNull("新增SHEET档，不改HOME", sheet)
        assertEquals(32f, sheet!!.dp.value, .001f)
        assertEquals(14f, PlatformIconSize.BADGE.dp.value, .001f)
        assertEquals(44f, PlatformIconSize.HOME.dp.value, .001f)
    }

    @Test fun authHeadingIs20BoldAndUsesOfficialSheetIcon() {
        host.launch()
        host.auth()
        val title = rule.onNodeWithText("购买前需完成淘宝授权，用于识别你的订单", useUnmergedTree = true).textLayout()
        host.counts(open = 1)
        assertEquals(20f, title.layoutInput.style.fontSize.value, .001f)
        assertEquals(
            700,
            title.layoutInput.style.fontWeight
                ?.weight,
        )
        val trade = source("feature/trade")
        assertTrue(trade.contains("PlatformIconSize.SHEET") && trade.contains("titleLeading ="))
    }

    @Test fun jumpTipRemainsBottomSheetAndUsesLargeHeading() {
        host.showTip = true
        host.launch()
        host.prepare("tip", rule)
        host.counts(open = 1)
        val action = rule.onNodeWithTag(TradeTags.JUMP_TIP).fetchSemanticsNode()
        assertTrue("须知仍为底部半屏", action.boundsInWindow.bottom / action.layoutInfo.density.density > 850f)
        val title = rule.onNodeWithText("下单须知", useUnmergedTree = true).textLayout()
        assertEquals(20f, title.layoutInput.style.fontSize.value, .001f)
        assertEquals(
            700,
            title.layoutInput.style.fontWeight
                ?.weight,
        )
    }

    @Test fun pendingClaimAddsChevronAndNotificationKeepsDividerAndSecondaryStyle() {
        val pending = source("core/orders").substringAfter("private fun PendingTrackCard(")
        assertTrue("找回入口需要尾部箭头", pending.substringBefore("private fun NotificationRow").contains("CouliIcons.chevron"))
        assertTrue(pending.contains("CouliSizes.hairline"))
        assertTrue(pending.contains("CouliButtonVariant.SECONDARY"))
    }
}
