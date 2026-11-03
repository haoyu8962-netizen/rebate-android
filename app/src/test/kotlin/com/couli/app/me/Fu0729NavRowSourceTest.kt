package com.couli.app.me

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * FU-AND-0729 ① 源码判据（brief §1.4，编排 11-03 13:39：「NavRow / SettingsNavRow 末尾加默认 48 的 minHeight、只在设置主页传 52」）：
 * - `NavRow`（MeUi 共用件）、`SettingsNavRow`（设置一族私有件，隐私中心清单也用）各只有一个定义；原参数逐个原样（含默认值）、原顺序在前，
 *   末尾恰好多一个 `minHeight: Dp = CouliSizes.touchTarget`（默认 = 旧外观 48，隐私中心 / 协议半屏 / 其他调用方零改动即保持 48）。
 * - 不跟改的页面文件不传 minHeight：隐私中心、昵称 / 通知子页、关于、客服半屏、剪贴板开关行、撤回 AI 行。
 * 参数比较与已合并守卫同一算法（按顶层逗号切参、去空白），另先去掉注释，参数前写 KDoc 不影响判定。底版 c8eac4a：前两项红（无 minHeight）、第三项绿。
 */
class Fu0729NavRowSourceTest {
    private val root = SettingsVisualFixtures.root
    private val me = "feature/me/src/main/kotlin/com/couli/feature/me/"

    @Test fun navRowOnlyAppendsMinHeightDefaulting48() {
        val source = File(root, me + "MeUi.kt").readText()
        assertEquals("NavRow 只能有一个定义", 1, Regex("""\bfun NavRow\(""").findAll(source).count())
        assertEquals(
            "NavRow：原 11 个参数原样原顺序，末尾只加 minHeight: Dp = CouliSizes.touchTarget",
            NAV_ROW_BASE + MIN_HEIGHT,
            params(source, "NavRow"),
        )
    }

    @Test fun settingsNavRowOnlyAppendsMinHeightDefaulting48() {
        val source = File(root, me + "SettingsParts.kt").readText()
        assertEquals("SettingsNavRow 只能有一个定义", 1, Regex("""\bfun SettingsNavRow\(""").findAll(source).count())
        assertEquals(
            "SettingsNavRow：原 5 个参数原样原顺序，末尾只加 minHeight: Dp = CouliSizes.touchTarget（隐私中心清单默认仍 48）",
            SETTINGS_NAV_ROW_BASE + MIN_HEIGHT,
            params(source, "SettingsNavRow"),
        )
    }

    @Test fun pagesThatKeepTheirRowsPassNoMinHeight() {
        listOf(
            me + "PrivacyCenter.kt",
            me + "SettingsSubPages.kt",
            me + "AboutController.kt",
            me + "CsSheets.kt",
            "app/src/main/kotlin/com/couli/app/clipboard/ClipboardScreens.kt",
            "feature/agent/src/main/kotlin/com/couli/feature/agent/AgentWithdrawRow.kt",
        ).forEach { path ->
            assertFalse("不跟改的页面不得传 minHeight / 52 常量：$path", File(root, path).readText().contains("minHeight"))
        }
    }

    /** 与 SettingsVisualGuardTest.params 同算法（顶层逗号切参、去空白；「->」的「>」不算尖括号），先去掉注释。 */
    private fun params(
        raw: String,
        name: String,
    ): List<String> {
        val source = raw.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
        val head = "fun $name("
        var i = source.indexOf(head) + head.length
        var depth = 0
        val current = StringBuilder()
        val out = mutableListOf<String>()
        while (true) {
            val ch = source[i]
            when {
                ch == '(' || ch == '<' -> depth++
                ch == '>' && source[i - 1] != '-' -> depth--
                ch == ')' && depth == 0 -> break
                ch == ')' -> depth--
            }
            if (ch == ',' && depth == 0) {
                out += current.toString()
                current.clear()
            } else {
                current.append(ch)
            }
            i++
        }
        if (current.isNotBlank()) out += current.toString()
        return out.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }
    }

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")
        const val MIN_HEIGHT = "minHeight:Dp=CouliSizes.touchTarget"

        /** 底版 c8eac4a 的 NavRow 参数（去空白）。 */
        val NAV_ROW_BASE =
            listOf(
                "title:String",
                "tag:String",
                "value:String?=null",
                "onClick:(()->Unit)?",
                "verticalPadding:Dp=CouliSpacing.space3",
                "horizontalPadding:Dp=CouliSpacing.space4",
                "chevronSize:Dp=CouliSizes.iconSmall",
                "titleGap:Dp=CouliSpacing.space2",
                "valueGap:Dp=CouliSpacing.space2",
                "showChevron:Boolean=onClick!=null",
                "wrapValue:Boolean=false",
            )

        /** 底版 c8eac4a 的 SettingsNavRow 参数（去空白）。 */
        val SETTINGS_NAV_ROW_BASE =
            listOf(
                "title:String",
                "tag:String",
                "value:String?",
                "onClick:(()->Unit)?",
                "chevron:Boolean",
            )
    }
}
