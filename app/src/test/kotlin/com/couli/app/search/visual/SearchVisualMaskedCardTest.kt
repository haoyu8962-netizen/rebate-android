package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardMapper
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 组件探针：搜索页没有金额隐藏入口，不将遮罩伪装为搜索 API 字段，也不覆盖 b 段游客降级。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualMaskedCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun maskedExistingCardComponentHasNoRebateAmountOrNetAmount() {
        val mapped = ProductCardMapper.map(SearchVisualFixtures.card("tb_single"), SearchDraftTexts, SearchVisualFixtures.now)
        rule.setContent {
            CouliTheme {
                ProductCardList(data = mapped.data.withAmountHidden(true), onBuy = {})
            }
        }
        rule.onNodeWithContentDescription("金额已隐藏", substring = true).assertIsDisplayed()
        rule.onNodeWithText("¥0.56", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("预估返后", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onRoot().captureRoboImage("src/test/snapshots/search-v/component-amount-hidden-light.png")
    }
}
