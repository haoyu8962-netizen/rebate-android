package com.couli.feature.web

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread

/**
 * WebView 资源缓存的管理入口（APP-12 设置页「清除缓存」，AC-S1-137，Android R1-10）：WebView.clearCache(true) 清的是临时实例所在配置文件的
 * 内存与磁盘资源缓存（不清 Cookie、Web Storage，会话与待办不在这里）。临时实例经可信 WebView 工厂 [webViews] 建（F-X-25 D10-310 (6)）：P 路径
 * 挂在当前可信配置文件上，只清可信容器的缓存，不碰外部容器（I6）；缺省是默认配置文件。只在主线程调用；借一个临时实例调用后立即销毁。
 * WebView 不可用（系统 WebView 缺失、正在更新）时返回 false，由调用方按失败提示。
 */
object WebViewCaches {
    /**
     * App 的可信 WebView 工厂来源（F-X-25 D10-310 (6)：设置页清缓存的临时实例经它建；App 启动接线时设置，单测不设 = 默认配置文件）。
     * 主线程读写。
     */
    @Volatile var trustedWebViews: () -> TrustedWebViewFactory? = { null }

    @MainThread
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // WebView 提供方缺失、正在更新时构造会抛出各种运行时异常：一律按失败
    fun clear(
        context: Context,
        webViews: TrustedWebViewFactory = trustedWebViews() ?: TrustedWebViewFactory.defaultProfile(),
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewCaches.clear must run on the main thread" }
        return try {
            val view = webViews.create(context.applicationContext)
            try {
                view.clearCache(true)
                true
            } finally {
                view.destroy()
            }
        } catch (error: RuntimeException) {
            false
        }
    }
}
