package com.couli.app.home

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.shell.BASIC_MODE_PAGE_TAG
import com.couli.app.shell.HOME_SEARCH_ENTRY_TAG
import com.couli.core.home.HomeLevel
import com.couli.core.router.PrivacyState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FU-AND-0725：真实 AppShell 的搜索入口；文字期望独立于产品字典，防止错键和错误文案一起通过。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSearchPlaceholder0725Test {
    @get:Rule
    val rule = createComposeRule()
    private val host by lazy { HomeVisualTestHost(rule) }

    @After
    fun close() = host.close()

    @Test
    fun basicHomeUsesBasicSearchPlaceholderForVisibleTextAndAccessibilityName() {
        host.launch(level = HomeLevel.BASIC)
        assertSearch(basic = true)
    }

    @Test
    fun guestHomeKeepsSearchPlaceholderForVisibleTextAndAccessibilityName() {
        host.launch(level = HomeLevel.GUEST)
        assertSearch(basic = false)
    }

    @Test
    fun memberHomeKeepsSearchPlaceholderForVisibleTextAndAccessibilityName() {
        host.launch(level = HomeLevel.MEMBER)
        assertSearch(basic = false)
    }

    @Test
    fun basicToFullToBasicKeepsEachSearchLabelInTheSameShell() {
        host.launch(level = HomeLevel.BASIC)
        assertSearch(basic = true)
        host.consentToFullMode()
        assertSearch(basic = false)
        host.withdrawToBasicMode()
        assertSearch(basic = true)
    }

    @Test
    fun fullToBasicToFullKeepsEachSearchLabelInTheSameShell() {
        host.launch(level = HomeLevel.GUEST)
        assertSearch(basic = false)
        host.withdrawToBasicMode()
        assertSearch(basic = true)
        host.consentToFullMode()
        assertSearch(basic = false)
    }

    private fun assertSearch(basic: Boolean) {
        val expected = if (basic) BASIC_PLACEHOLDER else HOME_PLACEHOLDER
        val other = if (basic) HOME_PLACEHOLDER else BASIC_PLACEHOLDER
        assertEquals(if (basic) PrivacyState.BASIC_MODE else PrivacyState.CONSENTED, host.routing.privacy.value)
        val scope =
            if (basic) {
                rule.onNodeWithTag(BASIC_MODE_PAGE_TAG).assertIsDisplayed()
                rule.onNodeWithTag(HOME_SEARCH_ENTRY_TAG).assertDoesNotExist()
                hasAnyAncestor(hasTestTag(BASIC_MODE_PAGE_TAG))
            } else {
                rule.onNodeWithTag(BASIC_MODE_PAGE_TAG).assertDoesNotExist()
                hasTestTag(HOME_SEARCH_ENTRY_TAG)
            }
        val entry = rule.onNode(scope and hasText(expected))
        entry
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertTextEquals(expected)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            // SearchEntry 的读屏名来自合并后的 Text；若显式覆盖，也只能是同一文案。
            .assert(
                SemanticsMatcher("读屏名与占位一致：$expected") { node ->
                    val description = node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                    description == null || description == listOf(expected)
                },
            )
        // 独立检查未合并的实际 Text，避免只改 contentDescription 就通过。
        rule.onNode(hasAnyAncestor(scope) and hasText(expected), useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText(other, useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals("另一模式的占位不得残留", 0, it.size)
        }
    }

    private companion object {
        // basic.search.placeholder（CT-16g）；不约束草稿键放 HomeDraftTexts 还是 ShellDraftTexts。
        const val BASIC_PLACEHOLDER = "搜索商品"

        // search.placeholder（G-V09），普通首页保持原文。
        const val HOME_PLACEHOLDER = "搜商品名，或粘贴商品链接"
    }
}
