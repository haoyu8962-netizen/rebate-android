package com.couli.core.designsystem

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 首页金刚区类目图标与首页功能小图标（APP-11v G-V08：凑狸自有内容图标，照 Main-v3 / HomeGuest-v3 画板的 SVG 逐条转写，三端同形；
 * 24 视口、1.75 描边、圆头圆角，颜色经 Icon(tint) 取令牌）。rect 已换写成等价 path。
 */
object CouliCategoryIcons {
    /** 超市百货：购物篮。 */
    val supermarket: ImageVector by lazy {
        lineIcon(
            "CategorySupermarket",
            "M4 9.5h16l-1.6 9.1a1.5 1.5 0 0 1-1.5 1.2H7.1a1.5 1.5 0 0 1-1.5-1.2L4 9.5Z",
            "M8 9.5 10.5 4.5M16 9.5 13.5 4.5",
            "M9.5 13v3.5M14.5 13v3.5",
        )
    }

    /** 食品生鲜：苹果。 */
    val fresh: ImageVector by lazy {
        lineIcon(
            "CategoryFresh",
            "M12 8c-1.6-1.1-5.6-1.3-6.5 2.6-.8 3.6 1.5 9.4 4.3 9.4.9 0 1.4-.5 2.2-.5s1.3.5 2.2.5" +
                "c2.8 0 5.1-5.8 4.3-9.4C17.6 6.7 13.6 6.9 12 8Z",
            "M12 8c0-1.9.7-3.4 2.5-4.5",
        )
    }

    /** 母婴用品：奶瓶。 */
    val baby: ImageVector by lazy {
        lineIcon(
            "CategoryBaby",
            "M10.5 6V4.5a1.5 1.5 0 0 1 3 0V6",
            "M8.5 6H15.5A1 1 0 0 1 16.5 7V8A1 1 0 0 1 15.5 9H8.5A1 1 0 0 1 7.5 8V7A1 1 0 0 1 8.5 6Z",
            "M8 9h8v9.5a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V9Z",
            "M8 13h3M8 16h3",
        )
    }

    /** 美妆个护：口红。 */
    val beauty: ImageVector by lazy {
        lineIcon(
            "CategoryBeauty",
            "M9 12.5H15A1.5 1.5 0 0 1 16.5 14V19A1.5 1.5 0 0 1 15 20.5H9A1.5 1.5 0 0 1 7.5 19V14A1.5 1.5 0 0 1 9 12.5Z",
            "M9.5 12.5v-3h5v3",
            "M10.5 9.5V5.5l3-2.5v6.5",
        )
    }

    /** 家居日用：沙发。 */
    val household: ImageVector by lazy {
        lineIcon(
            "CategoryHousehold",
            "M5.5 11.5V9a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v2.5",
            "M3.5 13a1.5 1.5 0 0 1 3 0v1.5h11V13a1.5 1.5 0 0 1 3 0v4.5h-17V13Z",
            "M6 17.5v2M18 17.5v2",
        )
    }

    /** 数码家电：显示器。 */
    val electronics: ImageVector by lazy {
        lineIcon(
            "CategoryElectronics",
            "M5 4.5H19A1.5 1.5 0 0 1 20.5 6V14.5A1.5 1.5 0 0 1 19 16H5A1.5 1.5 0 0 1 3.5 14.5V6A1.5 1.5 0 0 1 5 4.5Z",
            "M9 20h6M12 16v4",
        )
    }

    /** 新人指南：书本。 */
    val guide: ImageVector by lazy {
        lineIcon(
            "CategoryGuide",
            "M12 6.5C10.5 5.3 8 4.8 4 5v13c4-.2 6.5.3 8 1.5 1.5-1.2 4-1.7 8-1.5V5c-4-.2-6.5.3-8 1.5Z",
            "M12 6.5v13",
        )
    }

    /** 粘贴（剪贴板夹）：首页粘贴卡按钮的图标（Main-v3 粘贴按钮 16 图标）。 */
    val paste: ImageVector by lazy {
        lineIcon(
            "Paste",
            "M8 5H16A2 2 0 0 1 18 7V18.5A2 2 0 0 1 16 20.5H8A2 2 0 0 1 6 18.5V7A2 2 0 0 1 8 5Z",
            "M9.5 5V4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1",
        )
    }

    /** 包内默认 / 服务端 `builtin:` 名称 → 类目图标；不认识的名字（含平台格 `platform.*`）返回 null。 */
    fun byName(name: String): ImageVector? =
        when (name) {
            "category.supermarket" -> supermarket
            "category.fresh" -> fresh
            "category.baby" -> baby
            "category.beauty" -> beauty
            "category.household" -> household
            "category.electronics" -> electronics
            "guide" -> guide
            else -> null
        }
}
