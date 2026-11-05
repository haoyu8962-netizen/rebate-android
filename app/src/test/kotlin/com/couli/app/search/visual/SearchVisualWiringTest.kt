package com.couli.app.search.visual

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只检查运行时难以辨别的组件复用与列表项内部绘制；布局、角色、焦点另有真页面断言。 */
class SearchVisualWiringTest {
    private val source =
        File(SearchVisualFixtures.root, "feature/search/src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
            .replace(Regex("/\\*[\\s\\S]*?\\*/|//[^\\n]*"), "")

    @Test fun tabsUseExistingOfficialDecorativePlatformIcon20() {
        assertTrue(Regex("\\bPlatformIcon\\s*\\(").containsMatchIn(source))
        assertTrue(source.contains("PlatformIconSize.SELECT"))
        // 装饰语义由未改动的 PlatformIcon 和 Tab 中文读屏断言共同保证。
    }

    @Test fun pageErrorUsesDesignSystemErrorStateAndPrimaryAction() {
        assertTrue("Q12 使用组件库 ErrorState", Regex("\\bErrorState\\s*\\(").containsMatchIn(source))
        assertTrue("StateAction 走实心样式", Regex("primary\\s*=\\s*true").containsMatchIn(source))
    }

    @Test fun disclaimerUsesSheetInsteadOfSharedDetailDialog() {
        assertFalse("详情弹窗保持原样，搜索页不用居中弹窗", Regex("\\bDisclaimerNoteDialog\\s*\\(").containsMatchIn(source))
        assertTrue(Regex("\\bCouliSheet\\s*\\(").containsMatchIn(source))
        assertTrue("半屏关闭复用契约字典", source.contains("external_page.close"))
        assertTrue("知道了用描边次按钮", source.contains("CouliButtonVariant.SECONDARY"))
        assertTrue("回同卡 i", Regex("restoreFocusTo\\s*=\\s*infoFocus\\[current\\.cardKey]").containsMatchIn(source))
    }

    @Test fun pagingSkeletonRemainsInsideExistingFooter() {
        val footer = source.substringAfter("private fun ListFooter(").substringBefore("internal fun pageDisclaimerLines(")
        val loading = footer.substringAfter("state.loadingMore ->").substringBefore("!state.endReached")
        assertTrue("Q14 骨架直接画在 footer 的 loadingMore 分支", loading.contains("SkeletonProductCard("))
        assertTrue("保留加载中语义", loading.contains("SearchKeys.LOADING"))
        assertTrue("保留既有标记", loading.contains("SearchTags.LOAD_MORE"))
    }
}
