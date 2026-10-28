package com.couli.app.me

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码契约（去空白后比对；与行为用例互补，挡「看起来对、其实换了回调」的实现）：
 * - 隐私中心：四行去向、个性化 / 撤回 / 开启完整功能 / 权限行的回调引用各恰一次且只在页面文件里；确认框带 titleAlign = Center、scrim = overlayScrim（G-P09）；
 *   不再渲染 basic_desc（G-P08）；不新建 Dialog / 半屏；无 DeleteAccount。
 * - 客服：动作映射四个 Triple 逐项原样、dismiss / consumeToast / 无半屏轻提示分支各一次；只在 OPEN_FAILED 把帮助画成链接；传 headerCentered = true（G-P05）；
 *   盾牌图标（G-P06）；不读 entry 之外的状态。
 * - 新视图件文件（若有，名字含 Parts 且不是 SettingsParts.kt）只管外观：不出现 controller / entry / deps。
 * - 文案：cs.safety 新草稿值（G-P07），键名不变。
 */
class PrivacyVisualSourceContractTest {
    private val root = PrivacyVisualFixtures.root
    private val dir = File(root, "feature/me/src/main/kotlin/com/couli/feature/me")

    private fun squeezed(name: String) = File(dir, name).readText().filterNot(Char::isWhitespace)

    private fun count(
        haystack: String,
        needle: String,
    ) = haystack.windowed(needle.length).count { it == needle }

    private val privacy get() = squeezed("PrivacyCenter.kt")
    private val about get() = squeezed("AboutController.kt")
    private val cs get() = squeezed("CsSheets.kt")

    /** 本任务新增的私有视图件文件（brief 建议 PrivacyAboutCsParts.kt）；没有新文件时为空表。 */
    private val parts: List<File> get() =
        dir.listFiles().orEmpty().filter { it.name.endsWith("Parts.kt") && it.name !in setOf("SettingsParts.kt", "MeTabParts.kt") }

    private val partsSqueezed: String get() = parts.joinToString("") { it.readText().filterNot(Char::isWhitespace) }

    @Test fun privacyRowsKeepTheirDestinations() {
        assertEquals("收集清单与隐私政策都开 privacy（G-P19）", 2, count(privacy, "controller.openDoc(PrivacyCenterController.TYPE_PRIVACY)"))
        assertEquals(1, count(privacy, "controller.openDoc(PrivacyCenterController.TYPE_SDK_LIST)"))
        assertEquals(1, count(privacy, "controller.openDoc(PrivacyCenterController.TYPE_AGREEMENT)"))
        listOf(
            "MeTags.PRIVACY_LIST_PREFIX+\"collect\"",
            "MeTags.PRIVACY_LIST_PREFIX+\"share\"",
            "MeTags.PRIVACY_POLICY",
            "MeTags.PRIVACY_AGREEMENT",
        ).forEach {
            assertEquals("标记原样：$it", 1, count(privacy, it))
        }
    }

    @Test fun privacyCallbacksArePassedThroughExactlyOnce() {
        listOf(
            "controller::setPersonalization",
            "controller::askWithdraw",
            "controller::enableFull",
            "controller::openPermission",
        ).forEach {
            assertEquals("回调引用恰一次（不包新 lambda、不加分支）：$it", 1, count(privacy, it))
        }
        val view = privacy + partsSqueezed
        assertEquals("撤回按钮 loading = 撤回进行中", 1, count(view, "loading=withdrawing"))
        assertTrue("撤回按钮 / 开关 enabled = !withdrawing", count(view, "enabled=!withdrawing") >= 2)
        assertTrue("撤回按钮仍是次按钮（G-P17）", view.contains("CouliButtonVariant.SECONDARY"))
        assertFalse("不新增危险样式", privacy.contains("DANGER") || privacy.contains("statusErrorText"))
    }

    @Test fun g09WithdrawDialogCentersTheTitleAndUsesTheScrimToken() {
        assertEquals(1, count(privacy, "CouliDialog("))
        assertEquals("G-P09 标题居中", 1, count(privacy, "titleAlign=TextAlign.Center"))
        assertEquals("G-P09 遮罩令牌", 1, count(privacy, "scrim=CouliColors.overlayScrim"))
        assertEquals("点遮罩不关闭不执行", 1, count(privacy, "dismissOnClickOutside=false"))
    }

    @Test fun g08BasicSublineIsNoLongerRendered() {
        assertFalse(
            "G-P08 视图不再渲染 privacy_center.basic_desc（键留在草稿表）",
            (
                privacy +
                    parts.joinToString("") {
                        it.readText()
                    }
            ).contains("privacy_center.basic_desc"),
        )
        assertTrue(
            "键仍在草稿表",
            File(root, "core/me/src/main/kotlin/com/couli/core/me/MeDraftTexts.kt").readText().contains("\"privacy_center.basic_desc\""),
        )
    }

    @Test fun noPageLevelDialogSheetOrDeletionEntry() {
        val pages = listOf(privacy, about, cs) + parts.map { it.readText().filterNot(Char::isWhitespace) }
        pages.forEach { page ->
            assertFalse("页面层不另建 Dialog（Dialog / 半屏接续字号由组件库给）", Regex("""(?<![A-Za-z])Dialog\(""").containsMatchIn(page))
            assertFalse("页面层不另建 ModalBottomSheet", page.contains("ModalBottomSheet("))
            assertFalse("§0.1 不新增通往 DeleteAccount 的入口", page.contains("DeleteAccount") || page.contains("openDeletion"))
        }
    }

    @Test fun aboutCopyKeepsItsCallbackAndAddsTheCopyIcon() {
        assertEquals(1, count(about, "controller::copyDiagnostics"))
        assertTrue("P17 复制图标", (about + parts.joinToString("") { it.readText() }).contains("CouliIcons.copy"))
        assertTrue("P18 成功带对勾（失败不带由 PrivacyVisualAboutTest 实测）", about.contains("CouliIcons.check"))
        assertEquals("公示显示条件原样", 1, count(about, "controller.filing()?.let"))
    }

    @Test fun csActionMappingIsVerbatim() {
        listOf(
            "Triple(\"cs.open_wechat\",MeTags.CS_OPEN){entry.openWechat(serial)}",
            "Triple(\"cs.retry\",MeTags.CS_RETRY){entry.openWechat(serial)}",
            "Triple(\"cs.copy_link\",MeTags.CS_COPY){entry.copyLink(serial)}",
            "Triple(helpKey,MeTags.CS_HELP){entry.goHelp(serial)}",
            "valhelpKey=if(decision.sheetRaw==CsEntryDecision.HANDOFF)\"cs.handoff.go_help\"else\"cs.go_help\"",
            "onDismissRequest={entry.dismiss(serial)}",
            "entry.consumeToast(t.serial)",
            "if(current==null)MeToastHost(toast?.let{texts.text(it.key)},toast?.serial){toast?.serial?.let(entry::consumeToast)}",
            "valsheetbyentry.sheet.collectAsStateWithLifecycle()",
            "valtoastbyentry.toast.collectAsStateWithLifecycle()",
            "liveRegion=LiveRegionMode.Polite",
        ).forEach { assertEquals("客服映射 / 收集 / 提示分支原样恰一次：$it", 1, count(cs, it)) }
        assertEquals("恰四个 Triple", 4, count(cs, "Triple("))
        val calls = Regex("""entry\.([A-Za-z]+)""").findAll(cs).map { it.groupValues[1] }.toSet()
        assertEquals(
            "只调这些出口（不新增 open / invalidate 等）",
            setOf("sheet", "toast", "openWechat", "copyLink", "goHelp", "dismiss", "consumeToast"),
            calls,
        )
        listOf(
            "CsEntryDecision.HANDOFF->\"cs.title\"to\"cs.handoff.desc\"",
            "CsEntryDecision.WECHAT_MISSING->\"cs.title\"to\"cs.wechat_unavailable\"",
            "CsEntryDecision.OPEN_FAILED->\"cs.open_failed.title\"to\"cs.open_failed.desc\"",
            "else->\"cs.open_failed.title\"to\"cs.help_only.desc\"",
        ).forEach { assertEquals("标题 / 说明键映射原样：$it", 1, count(cs, it)) }
    }

    @Test fun g05g06CsHeaderIsCenteredWithShieldAndLinkOnlyForOpenFailed() {
        assertTrue("G-P05 传 headerCentered = true", cs.contains("headerCentered=true"))
        val all = cs + parts.joinToString("") { it.readText().filterNot(Char::isWhitespace) }
        assertTrue("G-P06 安全提示盾牌对勾", all.contains("CouliIcons.shieldCheck"))
        assertFalse("安全提示不再用 info", cs.contains("icon=CouliIcons.info"))
        assertTrue("G-P06 链接用 CompactTextLink（命中 48、语义盒 ≥ 48）", all.contains("CompactTextLink("))
    }

    @Test fun newPartsFileIsAppearanceOnly() {
        parts.forEach { file ->
            val text = file.readText()
            listOf("controller", "entry.", "deps.", "CustomerServiceEntry", "MeEnv").forEach {
                assertFalse("${file.name} 只管外观、回调由页面传入：不得出现 $it", text.contains(it))
            }
        }
    }

    @Test fun g07SafetyDraftUsesTheBoardWording() {
        assertEquals(
            "G-P07 cs.safety 新草稿值（键名不变）",
            PrivacyVisualFixtures.safetyDraft,
            com.couli.core.me.MeDraftTexts
                .text("cs.safety"),
        )
        assertEquals("经 App 文案字典取到同一句", PrivacyVisualFixtures.safetyDraft, AppMeTexts.text("cs.safety"))
    }
}
