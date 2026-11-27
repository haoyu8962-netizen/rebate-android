package com.couli.app.trade

import kotlinx.coroutines.flow.Flow

/**
 * 打开淘宝授权页（设计稿 §4）：Custom Tab，没有就退回系统浏览器；都打不开返回 false。授权器只经这个接口打开，单测注入替身
 * （测试不依赖 androidx.browser，D10-3568）。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩；Custom Tab / 系统浏览器的生产实现由实现子代理加。
 */
fun interface AuthBrowserLauncher {
    fun open(authUrl: String): Boolean
}

/** MainActivity 的可见性事件（不含配置变更重建）。 */
enum class AuthReturnEvent {
    STOPPED,
    STARTED,
}

/**
 * 「用户从授权页回到 App」的信号（设计稿 §3.1、§6.1）：MainActivity onStop / onStart 的事件流。授权器只认拉起之后「先 STOPPED、再 STARTED」。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩；MainActivity 接线的生产实现由实现子代理加。
 */
interface AuthReturnSignal {
    val events: Flow<AuthReturnEvent>
}
