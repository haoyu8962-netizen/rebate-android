package com.couli.core.designsystem

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliIconMetrics.STROKE_WIDTH
import com.couli.core.designsystem.CouliIconMetrics.VIEWPORT

/**
 * 线性图标（GUIDE §4：24 视口、1.75 描边、圆头圆角，颜色跟随文字色——用 Icon(tint) 着色）。
 * 路径照抄设计稿的 SVG；circle / rect 已换写成等价的 path。
 */
@Suppress("TooManyFunctions")
object CouliIcons {
    val home: ImageVector by lazy {
        lineIcon(
            "Home",
            "M4 11.5 12 4l8 7.5",
            "M6.5 10v9.5h11V10",
        )
    }

    val chat: ImageVector by lazy {
        lineIcon(
            "Chat",
            "M5 6.5h14a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-6l-4 3v-3H5A1.5 1.5 0 0 1 3.5 16V8A1.5 1.5 0 0 1 5 6.5Z",
        )
    }

    val orders: ImageVector by lazy {
        lineIcon(
            "Orders",
            "M7 3.5H17A2 2 0 0 1 19 5.5V18.5A2 2 0 0 1 17 20.5H7A2 2 0 0 1 5 18.5V5.5A2 2 0 0 1 7 3.5Z",
            "M8.5 8.5h7M8.5 12h7M8.5 15.5h4",
        )
    }

    val user: ImageVector by lazy {
        lineIcon(
            "User",
            "M8.5 8.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z",
            "M5 20c.8-3.6 3.6-5.5 7-5.5s6.2 1.9 7 5.5",
        )
    }

    val search: ImageVector by lazy {
        lineIcon(
            "Search",
            "M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0Z",
            "m16 16 4 4",
        )
    }

    /** 系统返回箭头（Material Symbols arrow_back，填充、随布局方向镜像）：系统导航栏的返回（组件清单 §1 ②）。 */
    val systemBack: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "SystemBack",
                defaultWidth = VIEWPORT.dp,
                defaultHeight = VIEWPORT.dp,
                viewportWidth = VIEWPORT,
                viewportHeight = VIEWPORT,
                autoMirror = true,
            ).addPath(
                pathData = PathParser().parsePathString("M20,11H7.83l5.59,-5.59L12,4l-8,8l8,8l1.41,-1.41L7.83,13H20v-2z").toNodes(),
                fill = SolidColor(CouliColors.all.getValue("text.primary")),
            ).build()
    }

    val back: ImageVector by lazy {
        lineIcon(
            "Back",
            "m14.5 6-6 6 6 6",
        )
    }

    val chevron: ImageVector by lazy {
        lineIcon(
            "Chevron",
            "m9.5 6 6 6-6 6",
        )
    }

    val chevronDown: ImageVector by lazy {
        lineIcon(
            "ChevronDown",
            "m6 9.5 6 6 6-6",
        )
    }

    val close: ImageVector by lazy {
        lineIcon(
            "Close",
            "M6 6l12 12M18 6 6 18",
        )
    }

    val eye: ImageVector by lazy {
        lineIcon(
            "Eye",
            "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z",
            "M9.25 12a2.75 2.75 0 1 0 5.5 0a2.75 2.75 0 1 0 -5.5 0Z",
        )
    }

    val eyeOff: ImageVector by lazy {
        lineIcon(
            "EyeOff",
            "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z",
            "M9.25 12a2.75 2.75 0 1 0 5.5 0a2.75 2.75 0 1 0 -5.5 0Z",
            "m4 4 16 16",
        )
    }

    val clock: ImageVector by lazy {
        lineIcon(
            "Clock",
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z",
            "M12 7.5V12l3 2",
        )
    }

    val check: ImageVector by lazy {
        lineIcon(
            "Check",
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z",
            "m8.5 12.2 2.4 2.4 4.6-5",
        )
    }

    val alert: ImageVector by lazy {
        lineIcon(
            "Alert",
            "M12 4.5 21 19.5H3L12 4.5Z",
            "M12 10v4.5M12 17v.2",
        )
    }

    /** 盾牌带勾（实名认证说明卡 RealName；路径照抄画板）。 */
    val shieldCheck: ImageVector by lazy {
        lineIcon(
            "ShieldCheck",
            "M12 3.5 5 6v5.5c0 4.2 2.8 7.4 7 9 4.2-1.6 7-4.8 7-9V6l-7-2.5Z",
            "m9 12 2.2 2.2L15 10",
        )
    }

    /** 文档（劳务协议名 LaborAgreement-v2；折角纸 + 两行，路径照抄画板）。 */
    val document: ImageVector by lazy {
        lineIcon(
            "Document",
            "M7 3.5h7l4 4v13H7v-17Z",
            "M14 3.5v4h4M9.5 12h6M9.5 15.5h6",
        )
    }

    val info: ImageVector by lazy {
        lineIcon(
            "Info",
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z",
            "M12 11v5M12 7.8v.2",
        )
    }

    val help: ImageVector by lazy {
        lineIcon(
            "Help",
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z",
            "M9.6 9.6a2.5 2.5 0 1 1 3.4 2.3c-.6.3-1 .8-1 1.4v.6",
            "M12 16.8v.2",
        )
    }

    val copy: ImageVector by lazy {
        lineIcon(
            "Copy",
            "M10.5 8.5H17.5A2 2 0 0 1 19.5 10.5V17.5A2 2 0 0 1 17.5 19.5H10.5A2 2 0 0 1 8.5 17.5V10.5A2 2 0 0 1 10.5 8.5Z",
            "M15.5 8.5V6.5a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2",
        )
    }

    val wallet: ImageVector by lazy {
        lineIcon(
            "Wallet",
            "M4.5 8V7a1.5 1.5 0 0 1 1.5-1.5h10.5V8",
            "M6 8H18A2 2 0 0 1 20 10V17.5A2 2 0 0 1 18 19.5H6A2 2 0 0 1 4 17.5V10A2 2 0 0 1 6 8Z",
            "M15.5 13.75h1.5",
        )
    }

    val ledger: ImageVector by lazy {
        lineIcon(
            "Ledger",
            "M7 3.5H17A2 2 0 0 1 19 5.5V18.5A2 2 0 0 1 17 20.5H7A2 2 0 0 1 5 18.5V5.5A2 2 0 0 1 7 3.5Z",
            "M8.5 8h1M12 8h3.5M8.5 12h1M12 12h3.5M8.5 16h1M12 16h3.5",
        )
    }

    val claim: ImageVector by lazy {
        lineIcon(
            "Claim",
            "M12.5 20.5h-6A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5h9A1.5 1.5 0 0 1 17 5v5",
            "M8.5 8h5M8.5 11.5h3",
            "M13 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            "m18.2 18.2 2.3 2.3",
        )
    }

    val invite: ImageVector by lazy {
        lineIcon(
            "Invite",
            "M6.5 8.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z",
            "M3.5 20c.8-3.6 3.4-5.5 6.5-5.5 1.6 0 3 .5 4.2 1.4",
            "M18 13.5v6M15 16.5h6",
        )
    }

    val heart: ImageVector by lazy {
        lineIcon(
            "Heart",
            "M12 19.5s-7.5-4.4-7.5-9.7A4.1 4.1 0 0 1 12 7.4a4.1 4.1 0 0 1 7.5 2.4c0 5.3-7.5 9.7-7.5 9.7Z",
        )
    }

    val headset: ImageVector by lazy {
        lineIcon(
            "Headset",
            "M5 14v-2a7 7 0 0 1 14 0v2",
            "M5.5 13.5H6A1.5 1.5 0 0 1 7.5 15V17A1.5 1.5 0 0 1 6 18.5H5.5A1.5 1.5 0 0 1 4 17V15A1.5 1.5 0 0 1 5.5 13.5Z",
            "M18 13.5H18.5A1.5 1.5 0 0 1 20 15V17A1.5 1.5 0 0 1 18.5 18.5H18A1.5 1.5 0 0 1 16.5 17V15A1.5 1.5 0 0 1 18 13.5Z",
            "M18 18.5c0 1.2-1.3 2-3 2h-2",
        )
    }

    /** 并入（BindPhoneMerge 画板信息块：两条线汇成一条，APP-05b）。 */
    val merge: ImageVector by lazy {
        lineIcon(
            "Merge",
            "M7 4.5v4a4 4 0 0 0 4 4h6",
            "M7 19.5v-4a4 4 0 0 1 4-4",
            "m14.5 9 3 3-3 3",
        )
    }

    val link: ImageVector by lazy {
        lineIcon(
            "Link",
            "M10 14a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 0 0-5-5l-1 1",
            "M14 10a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l1-1",
        )
    }

    val settings: ImageVector by lazy {
        lineIcon(
            "Settings",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            "M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2M6 6l1.6 1.6M16.4 16.4 18 18M18 6l-1.6 1.6M7.6 16.4 6 18",
        )
    }

    val bell: ImageVector by lazy {
        lineIcon(
            "Bell",
            "M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 2h-14l1.5-2Z",
            "M10 20.5h4",
        )
    }

    val trend: ImageVector by lazy {
        lineIcon(
            "Trend",
            "M4 19.5h16",
            "M5 15.5 9.5 11l3 3L19 7.5",
        )
    }

    /** 柱状图（钱包「收益看板」入口 WalletEarningsEntry；三根竖柱，路径照抄画板）。 */
    val barChart: ImageVector by lazy {
        lineIcon(
            "BarChart",
            "M5 19.5V11M12 19.5V5M19 19.5v-6",
        )
    }

    val star: ImageVector by lazy {
        lineIcon(
            "Star",
            "M12 3.5 14.4 8.7 20.1 9.4 15.9 13.3 17 18.9 12 16.1 7 18.9 8.1 13.3 3.9 9.4 9.6 8.7Z",
        )
    }

    /** 断网（订单断网提示条 OrdersStale-v2；三段信号弧 + 斜杠，路径照抄画板）。 */
    val wifiOff: ImageVector by lazy {
        lineIcon(
            "WifiOff",
            "M3.5 9.5a12 12 0 0 1 17 0M6.5 12.5a7.5 7.5 0 0 1 11 0M9.5 15.5a3 3 0 0 1 5 0",
            "M12 19v.2",
            "m4 4 16 16",
        )
    }

    val image: ImageVector by lazy {
        lineIcon(
            "Image",
            "M5.5 5H18.5A2 2 0 0 1 20.5 7V17A2 2 0 0 1 18.5 19H5.5A2 2 0 0 1 3.5 17V7A2 2 0 0 1 5.5 5Z",
            "m3.5 16 5-5 4 4 2.5-2.5 5.5 5.5",
            "M14 9.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0Z",
        )
    }

    /** 锁（账号状态封禁头部 RiskNoticeBanned-v2，G-R06；rect rx2 + 锁扣，路径照抄画板）。 */
    val lock: ImageVector by lazy {
        lineIcon(
            "Lock",
            "M7.5 10.5H16.5A2 2 0 0 1 18.5 12.5V18A2 2 0 0 1 16.5 20H7.5A2 2 0 0 1 5.5 18V12.5A2 2 0 0 1 7.5 10.5Z",
            "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5",
        )
    }

    /** 暂停圆（账号状态冻结头部 RiskNoticeFrozen，G-R06；圆 r8.5 + 两竖，路径照抄画板）。 */
    val pauseCircle: ImageVector by lazy {
        lineIcon(
            "PauseCircle",
            "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z",
            "M10 9v6M14 9v6",
        )
    }
}

internal object CouliIconMetrics {
    const val VIEWPORT = 24f
    const val STROKE_WIDTH = 1.75f
}

internal fun lineIcon(
    name: String,
    vararg paths: String,
): ImageVector {
    val builder =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIEWPORT.dp,
            defaultHeight = VIEWPORT.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
    paths.forEach { data ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            // 静态默认描边色（浅色 text.primary）；使用处一律经 Icon 的 tint 取当前主题令牌
            stroke = SolidColor(CouliColors.all.getValue("text.primary")),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
