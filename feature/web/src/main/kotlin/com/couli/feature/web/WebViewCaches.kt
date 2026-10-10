package com.couli.feature.web

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.MainThread

/**
 * WebView 资源缓存的管理入口（APP-12 设置页「清除缓存」，AC-S1-137，Android R1-10）：WebView.clearCache(true) 清的是本进程所有 WebView 共用的内存与磁盘
 * 资源缓存（不清 Cookie、Web Storage，会话与待办不在这里）。只在主线程调用；不需要页面上正在显示的容器——借一个临时实例调用后立即销毁。
 * WebView 不可用（系统 WebView 缺失、正在更新）时返回 false，由调用方按失败提示。
 */
object WebViewCaches {
    @MainThread
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // WebView 提供方缺失、正在更新时构造会抛出各种运行时异常：一律按失败
    fun clear(context: Context): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewCaches.clear must run on the main thread" }
        return try {
            val view = WebView(context.applicationContext)
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
