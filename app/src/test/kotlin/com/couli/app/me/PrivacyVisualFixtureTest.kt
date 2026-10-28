package com.couli.app.me

import com.couli.core.me.AboutView
import com.couli.core.me.CsEntryDecision
import com.couli.core.me.Diagnostics
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-P13：fixture 是新文件 specs/privacy-visual-vectors.json（三端同一份），me-vectors.json 不追加（由 PrivacyVisualGuardTest 整文件冻结）。
 * 覆盖画板上出现的每类元素需要的数据；诊断 / 公示 / 客服决策的期望值由产品纯规则复算（fixture 不实现规则）；键名避开 gitleaks 敏感词。
 */
class PrivacyVisualFixtureTest {
    @Test fun boardsMatchTheBriefRegister() {
        val boards = PrivacyVisualFixtures.boards()
        assertEquals("第 3 批隐私中心 7 + 关于 3 + 客服 3", 13, boards.size)
        assertEquals("对照 11 张（两份清单正文按 G-P01 不对照）", 11, boards.values.count { it.getValue("compare").jsonPrimitive.content == "true" })
        assertEquals(
            "4d60cd5f4c7c",
            boards
                .getValue("PrivacyCenter")
                .getValue("sha12")
                .jsonPrimitive.content,
        )
        assertEquals(
            "2beaf0e8a771",
            boards
                .getValue("CsOpenFailed-v2")
                .getValue("sha12")
                .jsonPrimitive.content,
        )
        assertEquals(setOf("PrivacyCenterDark", "AboutDark", "CsWechatMissingDark"), boards.filterValues { it["dark"] != null }.keys)
        assertEquals(864, PrivacyVisualFixtures.height("PrivacyCenter"))
        assertEquals(1032, PrivacyVisualFixtures.height("CsWechatMissing-v2"))
        assertTrue("作废稿 CsHandoff 登记为不对照", PrivacyVisualFixtures.obj("superseded").containsKey("CsHandoff"))
    }

    @Test fun sessionsCoverMemberGuestBasicAndForceUpdate() {
        assertEquals("member", PrivacyVisualFixtures.level("privacy_member"))
        assertEquals("guest", PrivacyVisualFixtures.level("privacy_guest"))
        assertEquals("basic", PrivacyVisualFixtures.level("privacy_basic"))
        assertTrue(PrivacyVisualFixtures.forceUpdate("privacy_force_update"))
        assertTrue(PrivacyVisualFixtures.deviceRegistered("privacy_member"))
        assertFalse("游客设备未注册（只记本地）", PrivacyVisualFixtures.deviceRegistered("privacy_guest"))
        assertEquals("not_asked", PrivacyVisualFixtures.permissionStatus("privacy_member", "push"))
        assertEquals("granted", PrivacyVisualFixtures.permissionStatus("privacy_member", "photos"))
        assertEquals(3, PrivacyVisualFixtures.legalPrivacyVersion("privacy_member"))
        assertEquals(
            "android",
            PrivacyVisualFixtures
                .behaviorPermissionEntry()
                .getValue("platform")
                .jsonPrimitive.content,
        )
    }

    @Test fun aboutDataMatchesTheProductRules() {
        assertEquals("画板占位句原样", PrivacyVisualFixtures.filed, AboutView.filing(PrivacyVisualFixtures.filed))
        assertNull("空串整节不显示", AboutView.filing(PrivacyVisualFixtures.unfiled))
        assertEquals(
            "诊断期望值 = Diagnostics.lines（白名单）",
            Diagnostics.lines(PrivacyVisualFixtures.diagnostics).joinToString("\n"),
            PrivacyVisualFixtures.diagnosticsText,
        )
        listOf("ABC123", "CONFIG_123", "HOME_456", "TEST_MODEL").forEach {
            assertTrue("AC-S1-136 测试值：$it", PrivacyVisualFixtures.diagnosticsText.contains(it))
        }
        PrivacyVisualFixtures.diagnosticsForbidden.forEach {
            assertFalse(
                "白名单外不出现：$it",
                PrivacyVisualFixtures.diagnosticsText.contains(it),
            )
        }
        assertEquals("1.0.0", PrivacyVisualFixtures.version)
        assertEquals("100", PrivacyVisualFixtures.build)
    }

    @Test fun csCasesMatchTheDecisionRule() {
        listOf(
            "cs_open_failed",
            "cs_help_only",
            "cs_help_only_insecure",
            "cs_wechat_missing",
            "cs_wechat_missing_copy_failed",
            "cs_handoff",
        ).forEach { case ->
            val via = PrivacyVisualFixtures.text("via", PrivacyVisualFixtures.case(case))
            val decision =
                CsEntryDecision.model(
                    PrivacyVisualFixtures.caseKf(case),
                    PrivacyVisualFixtures.text("wechat", PrivacyVisualFixtures.case(case)),
                    if (via == "bridge" && !PrivacyVisualFixtures.caseBool(case, "opens")) "failed" else null,
                )
            assertEquals("情形 → 半屏：$case", PrivacyVisualFixtures.text("sheet", PrivacyVisualFixtures.case(case)), decision.sheetRaw)
            assertEquals("情形 → 按钮：$case", PrivacyVisualFixtures.caseButtons(case), decision.buttons)
        }
        assertTrue("kf 是明显测试 https 链接", PrivacyVisualFixtures.kf.startsWith("https://") && PrivacyVisualFixtures.kf.contains("example.com"))
        assertFalse("kf 不含账号标识", PrivacyVisualFixtures.kf.contains("uid") || PrivacyVisualFixtures.kf.contains("phone"))
        assertEquals("G-P07 新句", "客服不会向你索要验证码或密码", PrivacyVisualFixtures.safetyDraft)
    }

    @Test fun noticesListEachToastWithItsIconClass() {
        val notices = PrivacyVisualFixtures.notices().toMap()
        assertEquals(setOf("about.copy_diagnostics.done"), notices.filterValues { it }.keys)
        assertTrue(notices.keys.containsAll(listOf("me.copy_failed", "privacy_center.personalization_failed", "cs.copy_link.done")))
    }

    @Test fun fixtureNamesAvoidGitleaksWordsAndMeVectorsIsUntouched() {
        val names = mutableListOf<String>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> names += k.lowercase().also { walk(v) } }
                is JsonArray -> element.forEach(::walk)
                is JsonPrimitive -> Unit
            }
        }
        walk(PrivacyVisualFixtures.data)
        listOf("token", "secret", "key", "sentry", "password", "auth").forEach { word ->
            assertTrue("键名不得含 $word：${names.filter { it.contains(word) }}", names.none { it.contains(word) })
        }
        assertFalse(
            "不往 me-vectors.json 追加（G-P13）",
            File(PrivacyVisualFixtures.root, "specs/me-vectors.json").readText().contains("visualPrivacy"),
        )
    }
}
