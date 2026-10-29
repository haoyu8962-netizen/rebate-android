package com.couli.app.trade

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06w 源码契约（brief-android §2 / §3 / §5、gaps G-A03 / G-A05 / G-A06 / G-A07 / G-A08 / G-A15；裁定 D10-414 ①③）：
 * - AuthManageParts.kt（新视图件文件）连注释也不得出现执行器 / 配置 / 网络 / 授权流程 / 生命周期 / 弹层禁词；只管外观；恰一个 PlatformIcon(…SELECT)；
 *   键表纯函数 authManageToastIcon、自定义行布局 AuthRowLayout 在这里；
 * - AuthManage.kt 视图段（第 491 行之后，按内容锚点取）：执行器只经原样回调表达式；收 controller / config 的可组合函数仍只有 AuthManageScreen / RowItem /
 *   ErrorBlock；不新增 LaunchedEffect / SideEffect / 自动焦点 / live region；卡片走 couliCard、【去授权】PRIMARY、【联系客服】SECONDARY、轻提示带图标与警告色；
 * - 视图段与 Parts 不写中文字面量、不写文案键字面量（键一律经 AuthManageKeys / TradeKeys，不新增文案键）。
 * 底版：Parts 不存在 → 本组大多红；视图段的「不新增」类断言底版绿。
 */
@Suppress("TooManyFunctions")
class AuthManageVisualSourceContractTest {
    private val root = AuthManageVisualFixtures.root
    private val pageFile = File(root, TRADE + "AuthManage.kt")
    private val partsFile = File(root, TRADE + "AuthManageParts.kt")

    private fun parts(): String {
        assertTrue("G-A03：新视图件放 feature/trade/AuthManageParts.kt（底版没有，红）", partsFile.isFile)
        return partsFile.readText()
    }

    /** 视图段：AuthManageScreen 里生命周期 DisposableEffect 收尾之后到文件尾（锚点同守卫）。 */
    private fun view(): String {
        val lines = pageFile.readText().split("\n")
        val start = lines.indexOfFirst { it.startsWith("fun AuthManageScreen(") }
        assertTrue("AuthManageScreen 仍在", start >= 0)
        val end = (start until lines.size).first { lines[it] == "    }" }
        return lines.drop(end + 1).joinToString("\n")
    }

    private fun squeezed(s: String) = s.filterNot(Char::isWhitespace)

    private fun count(
        haystack: String,
        needle: String,
    ): Int = haystack.windowed(needle.length, 1).count { it == needle }

    // ---------------------------------------------------------------- AuthManageParts.kt

    @Test fun partsHasNoExecutorConfigNetworkOrLifecycleWords() {
        val text = parts()
        FORBIDDEN_IN_PARTS.forEach { w -> assertEquals("AuthManageParts.kt 连注释也不得出现「$w」", 0, count(text, w)) }
        assertEquals("不得出现裸 Dialog(", 0, Regex("""(?<![A-Za-z])Dialog\(""").findAll(text).count())
    }

    @Test fun partsUsesOnePlatformIconAtTheSelectSize() {
        val text = parts()
        assertEquals("G-A04：恰一个 PlatformIcon(", 1, count(text, "PlatformIcon("))
        assertTrue("尺寸档 PlatformIconSize.SELECT（20，不新增档）", text.contains("PlatformIconSize.SELECT"))
        assertEquals("不用 PlatformBadge 胶囊", 0, count(text, "PlatformBadge("))
        assertTrue("圆勾 / 警告三角用已有成员", text.contains("CouliIcons.check") && text.contains("CouliIcons.alert"))
    }

    @Test fun partsHoldsTheKeyTableAndTheRowLayout() {
        val text = parts()
        assertTrue(
            "键表纯函数 authManageToastIcon(key: String)",
            Regex("""fun\s+authManageToastIcon\(\s*\w+\s*:\s*String\s*\)""").containsMatchIn(text),
        )
        assertTrue("G-A11：自定义行布局 AuthRowLayout（Layout）", text.contains("fun AuthRowLayout(") && text.contains("Layout("))
        assertTrue("AuthRowLayout 只按测量宽度判定：不读字号常量 / 屏宽", !text.contains("LocalConfiguration") && !text.contains("fontScale"))
    }

    @Test fun partsWritesNoChineseAndNoTextKeys() {
        val literals = STRING.findAll(parts()).map { it.groupValues[1] }.toList()
        assertTrue("Parts 字符串字面量不写中文：${literals.filter { it.any(::isCjk) }}", literals.none { it.any(::isCjk) })
        assertTrue("Parts 不写文案键字面量：${literals.filter { KEY.matches(it) }}", literals.none { KEY.matches(it) })
    }

    // ---------------------------------------------------------------- AuthManage.kt 视图段

    @Test fun viewReachesTheExecutorOnlyThroughTheOldExpressions() {
        val v = view()
        val s = squeezed(v)
        assertEquals("authorize( 只在 controller.authorize(row.platform)", 1, count(s, "authorize("))
        assertEquals(1, count(s, "controller.authorize(row.platform)"))
        assertEquals("只有轻提示那一处 LaunchedEffect", 1, count(s, "LaunchedEffect("))
        assertEquals(1, count(s, "LaunchedEffect(toast.id)"))
        listOf(
            "SideEffect",
            "api.",
            "ApiCall(",
            "PageApi",
            "TaobaoAuthFlow",
            "executor",
            "permit",
            "onForeground",
            "onBackground",
            "viewModel",
            "requestFocus",
            "liveRegion",
            "LiveRegionMode",
        ).forEach { w -> assertEquals("视图段不得出现「$w」", 0, count(v, w)) }
        val refs = Regex("""\bcontroller(?:\.|::)([A-Za-z]+)""").findAll(v).map { it.groupValues[1] }.toSet()
        assertEquals("视图段执行器成员只有 authorize / load / consumeToast（errorMessage 经伴生对象）", setOf("authorize", "load", "consumeToast"), refs)
    }

    @Test fun onlyTheThreeOldComposablesTakeTheControllerOrConfig() {
        val v = view()
        val takers =
            Regex("""fun\s+(\w+)\(([^)]*)\)""")
                .findAll(v)
                .filter { it.groupValues[2].contains("AuthManageController") || it.groupValues[2].contains("AuthManageConfig") }
                .map { it.groupValues[1] }
                .toSet()
        assertEquals("新视图件只收已求值参数与 lambda（G-A03）", setOf("RowItem", "ErrorBlock"), takers)
        assertTrue("RowItem / ErrorBlock 仍在 AuthManage.kt", v.contains("private fun RowItem(") && v.contains("private fun ErrorBlock("))
    }

    @Test fun viewUsesCouliCardPrimaryGrantSecondaryContactAndWarningToast() {
        val s = squeezed(view())
        assertTrue("A3：卡片 couliCard()（圆角 16 + 卡片阴影）", s.contains("couliCard("))
        assertEquals("A3：不再是 panel 24 无阴影底", 0, count(s, "RoundedCornerShape(CouliRadius.panel)"))
        assertEquals("A9 / G-A05：【去授权】PRIMARY 恰一处", 1, count(s, "CouliButtonVariant.PRIMARY"))
        assertEquals("A10 / G-A06：【联系客服】不再是 TEXT", 0, count(s, "CouliButtonVariant.TEXT"))
        assertEquals("SECONDARY：【联系客服】+【重试】", 2, count(s, "CouliButtonVariant.SECONDARY"))
        assertEquals("COMPACT 三处不变", 3, count(s, "CouliButtonSize.COMPACT"))
        assertEquals("A12：轻提示不再 icon = null", 0, count(s, "icon=null"))
        assertEquals("A12：键表选图标", 1, count(s, "authManageToastIcon(toast.key)"))
        assertEquals("A12：警告色", 1, count(s, "iconTint=CouliColors.statusWarningText"))
        assertTrue("G-A11：行布局件", s.contains("AuthRowLayout("))
        assertEquals("A14 / G-A09：错误态焦点只有现状一处", 1, count(s, "couliFocusTarget("))
    }

    @Test fun g08MergesNameAndStatusInOneNode() {
        val both = squeezed(view()) + squeezed(if (partsFile.isFile) partsFile.readText() else "")
        assertTrue(
            "G-A08：名与状态合成一个读屏节点（semantics(mergeDescendants = true) 或整组 clearAndSetSemantics）",
            both.contains("mergeDescendants=true") || both.contains("clearAndSetSemantics"),
        )
    }

    @Test fun viewWritesNoChineseAndNoTextKeys() {
        val literals = STRING.findAll(view()).map { it.groupValues[1] }.toList()
        assertTrue("视图段字符串字面量不写中文", literals.none { it.any(::isCjk) })
        assertTrue(
            "视图段不写文案键字面量（不新增键，经 AuthManageKeys / TradeKeys）：${literals.filter { KEY.matches(it) }}",
            literals.none { KEY.matches(it) },
        )
    }

    @Test fun purchaseFilesAndDesignSystemAreNotReferencedForNewWork() {
        val text = parts()
        assertEquals("不借用购买流程的 toastIcon（PurchaseVisualParts 冻结）", 0, count(text, "toastIcon("))
        assertEquals("不改组件库默认：不在本页文件里定义 CouliToast", 0, count(text, "fun CouliToast("))
    }

    private fun isCjk(c: Char) = c in '一'..'鿿'

    private companion object {
        const val TRADE = "feature/trade/src/main/kotlin/com/couli/feature/trade/"
        val STRING = Regex(""""((?:[^"\\\n]|\\.)*)"""")
        val KEY = Regex("""^[a-z_0-9]+(\.[a-z_0-9]+)+$""")
        val FORBIDDEN_IN_PARTS =
            listOf(
                "controller",
                "AuthManageController",
                "config.",
                "TaobaoAuthFlow",
                "authorize(",
                "load(",
                "onForeground",
                "onBackground",
                "api.",
                "ApiCall",
                "PageApi",
                "executor",
                "permit",
                "viewModel",
                "collectAsState",
                "LaunchedEffect",
                "SideEffect",
                "ModalBottomSheet(",
                "requestFocus",
                "liveRegion",
            )
    }
}
