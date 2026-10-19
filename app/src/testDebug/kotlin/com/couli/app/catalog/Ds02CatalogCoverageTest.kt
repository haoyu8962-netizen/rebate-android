package com.couli.app.catalog

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.couli.core.designsystem.CouliTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** 从真正目录入口点入每个组件；状态标签不能只存在于未使用的注册表。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02CatalogCoverageTest(
    private val component: String,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun entryOpensAllVariantsAndStatesWithImplementationLabel() {
        rule.setContent { CouliTheme { ComponentCatalog(initial = CatalogPage.INDEX, onClose = {}) } }
        rule.onNodeWithTag(COMPONENT_CATALOG_MARKER).assertExists()
        rule.onNodeWithTag("catalog-index").performScrollToNode(hasTestTag("catalog-entry/$component"))
        rule.onNodeWithTag("catalog-entry/$component").performClick()
        val cases = Ds02Inventory.cases.filter { it.component == component }
        rule.onNodeWithTag("catalog-kind/$component").assertTextEquals(cases.first().kind)
        if (component ==
            "OneTapLoginPanel"
        ) {
            rule.onNodeWithTag("catalog-note/$component").assertIsDisplayed().assertTextContains("SDK 页面示意", substring = true)
        }
        if (component in setOf("OAuthButton", "ThirdPartyLoginRow")) {
            val approvals =
                java.util.Properties().apply {
                    val root =
                        generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
                            .first { java.io.File(it, "contract.lock").isFile }
                    java.io
                        .File(root, "core/designsystem/src/test/resources/ds02-approved-oauth-assets.properties")
                        .inputStream()
                        .use { load(it) }
                }
            if (listOf("WECHAT", "HUAWEI").any { approvals.getProperty("$it.resource").isNullOrBlank() }) {
                rule.onNodeWithTag("catalog-note/$component").assertIsDisplayed().assertTextContains("待官方素材", substring = true)
            }
        }
        cases.forEach { sample ->
            rule.onNodeWithTag("catalog-cases/$component").performScrollToNode(hasTestTag(sample.tag))
            rule.onNodeWithTag(sample.tag).assertIsDisplayed()
            assertRenderedState(sample)
            // 必须有可读的变体、状态标题；空 Box + testTag 不算展示。
            rule
                .onNodeWithTag("catalog-label/$component/${sample.key}")
                .assert(
                    SemanticsMatcher("可读的变体与状态说明") { node ->
                        node.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .any { it.text.isNotBlank() }
                    },
                )
        }
    }

    private fun assertRenderedState(sample: Ds02Case) {
        val role =
            when (sample.component) {
                "CouliCheckbox", "AgreementCheckRow" -> Role.Checkbox
                "CouliSwitch" -> Role.Switch
                "CouliButton" -> Role.Button
                else -> return
            }
        val control =
            rule.onNode(
                hasAnyAncestor(hasTestTag(sample.tag)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, role),
            )
        when (sample.state) {
            "disabled", "loading", "disabled-on", "disabled-off", "disabled-checked", "disabled-unchecked" -> control.assertIsNotEnabled()
            else -> control.assertIsEnabled()
        }
        when (sample.state) {
            "checked", "confirmed", "on", "disabled-on", "disabled-checked" -> control.assertIsOn()
            "unchecked", "off", "disabled-off", "disabled-unchecked" -> control.assertIsOff()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            Ds02Inventory.cases
                .map { it.component }
                .distinct()
                .map { arrayOf(it) }
    }
}
