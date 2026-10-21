package com.couli.app.home

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.feature.home.HomeSduiScreen

/**
 * 首页 Tab 的原生头部项（APP-11v G-V02）：由首页 Tab 的外壳（AppShell 的 TabRoot）按此刻的身份与剪贴板状态提供，[HomeRoute] 把它交给
 * HomeSduiScreen 作为同一列表的头部。没有提供（只测 SDUI 区的单测、HomePreview）时为空。
 */
val LocalHomeNativeHeader = compositionLocalOf<LazyListScope.() -> Unit> { {} }

/**
 * 首页 Tab 的 SDUI 区（APP-11）：按此刻的身份取（没有就建）首页控制器并渲染。控制器按身份重建时整块按控制器编号重建，旧回调随旧节点销毁。
 * 基本模式下同样用这个渲染器（身份档 basic：匿名请求、不弹窗、不发埋点、LKG 写在 basic 分区，G25）。原生头部取 [LocalHomeNativeHeader]；
 * [footer] 是列表最底部的附加项（debug / staging 的开发入口）。
 */
@Composable
fun HomeRoute(
    home: AppHome,
    footer: LazyListScope.() -> Unit = {},
) {
    val observed by home.controller.collectAsStateWithLifecycle()
    val header = LocalHomeNativeHeader.current
    // 这个身份的 LKG 作用域还在清理（R2-02）时暂时没有控制器：留空，清理完成后按新控制器重组
    val controller = observed ?: home.ensure() ?: return
    key(controller.id) {
        HomeSduiScreen(controller = controller, signals = home.signals, header = header, footer = footer)
    }
}
