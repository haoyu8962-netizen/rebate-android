package com.couli.feature.home

import android.app.Application
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A7/A9：五列金刚区整格读一次；平台有包内图时不再用第二份文字胶囊代替；活动副标题与游客整行入口。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
class HomeVisualStaticCardsTest {
    @get:Rule
    val rule = createComposeRule()
    private val driver by lazy { HomeVisualTestDriver(rule) }

    @After
    fun close() = driver.close()

    @Test
    fun tenIconsKeepFiveColumnsAndOneSpokenCellEach() {
        driver.show("icons")
        val cells =
            (
                driver.controller.state.value.cards
                    .single() as HomeCardUi.IconGrid
            ).cells
        assertEquals(10, cells.size)
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(HomeTags.CARD_PREFIX + "icons"))).assertCountEquals(10)
        val bounds =
            cells.mapIndexed { index, cell ->
                val tag = HomeTags.tap("icons", index + 1)
                val node = rule.onNodeWithTag(tag).assertContentDescriptionEquals(cell.title)
                rule.onAllNodes(hasText(cell.title) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertCountEquals(1)
                node.getUnclippedBoundsInRoot()
            }
        bounds.take(5).forEach { assertEquals(bounds.first().top.value, it.top.value, 1f) }
        bounds.drop(5).forEach { assertEquals(bounds[5].top.value, it.top.value, 1f) }
        assertTrue(bounds[5].top >= bounds[0].bottom)
        bounds.forEach { assertTrue(it.width.value >= 48f && it.height.value >= 48f) }
    }

    @Test
    fun activitiesKeepBothSubtitlesAnd72DpMinimumHeight() {
        driver.show("activities")
        listOf("三步看懂", "填订单号找回").forEachIndexed { index, subtitle ->
            val tag = HomeTags.tap("activities", index + 1)
            rule.onAllNodes(hasText(subtitle) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertCountEquals(1)
            assertTrue(
                rule
                    .onNodeWithTag(tag)
                    .getUnclippedBoundsInRoot()
                    .height.value >= 72f,
            )
        }
    }

    @Test
    fun serverSuppliedSingleGuestActivityOccupiesTheWholeRow() {
        driver.show("activities", fixtureName = "guest_v3")
        val card = rule.onNodeWithTag(HomeTags.CARD_PREFIX + "activities").getUnclippedBoundsInRoot()
        val cell = rule.onNodeWithTag(HomeTags.tap("activities", 1)).getUnclippedBoundsInRoot()
        assertTrue("单张活动卡不能留半行空白", cell.width.value >= card.width.value - 32f)
        rule.onNodeWithTag(HomeTags.tap("activities", 2)).assertDoesNotExist()
    }
}
