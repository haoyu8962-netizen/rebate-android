package com.couli.app.home

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.feature.home.HomeSduiScreen

/**
 * 首页 Tab 的 SDUI 区（APP-11）：按此刻的身份取（没有就建）首页控制器并渲染。控制器按身份重建时整块按控制器编号重建，旧回调随旧节点销毁。
 * 基本模式下同样用这个渲染器（身份档 basic：匿名请求、不弹窗、不发埋点、LKG 写在 basic 分区，G25）。[footer] 是列表最底部的附加项（debug / staging 的开发入口）。
 */
@Composable
fun HomeRoute(
    home: AppHome,
    footer: LazyListScope.() -> Unit = {},
) {
    val observed by home.controller.collectAsStateWithLifecycle()
    // 这个身份的 LKG 作用域还在清理（R2-02）时暂时没有控制器：留空，清理完成后按新控制器重组
    val controller = observed ?: home.ensure() ?: return
    key(controller.id) {
        HomeSduiScreen(controller = controller, signals = home.signals, footer = footer)
    }
}
