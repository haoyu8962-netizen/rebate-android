package com.couli.feature.web

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebSettings
import android.webkit.WebView
import kotlin.math.abs

/**
 * 两类容器共同的 WebView 设置（03 §5.1、§4.8 CSB-03；WebView 的设置只写在 feature:web 的容器封装里）：
 * - 禁止 file / content 访问（含经 file 地址读取其他文件与跨域访问），禁止混合内容；
 * - 不自动开新窗口；不保存表单与密码；不开地理位置；Safe Browsing 保持开启；
 * - UA 完整保留系统默认值，只在末尾追加本 App 的后缀 `Couli/<版本号>`（不删不改原有内容，不冒充其他 App 或浏览器）。
 * JavaScript 两类容器都要开（自有 H5 与第三方页面都需要）；桥只由 TrustedWebView 经 WebViewCompat 注入，从不使用
 * addJavascriptInterface（03 §5.2）。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyContainerSettings(appVersion: String) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        safeBrowsingEnabled = true
        userAgentString = userAgent(WebSettings.getDefaultUserAgent(context), appVersion)
    }
    setDownloadListener { _, _, _, _, _ -> }
}

/** 系统默认 UA 原样保留，末尾追加 ` Couli/<版本号>`（03 §5.1）。 */
internal fun userAgent(
    systemDefault: String,
    appVersion: String,
): String = "$systemDefault $UA_PRODUCT/$appVersion"

private const val UA_PRODUCT = "Couli"

/**
 * 用户手势时钟（APP-04 三端共性第 5 条）：只把原生识别出的**有效点击**当作用户手势——同一指针按下后在触摸容差内抬起、按住时间
 * 短于长按阈值。只有按下、滑动（移出容差）、长按、取消（ACTION_CANCEL）、多指都不算。点击时刻用单调时钟（uptimeMillis，
 * 页面脚本造不出系统派发的 MotionEvent）记录；L2 与 gesture_required 的方法要求最近 1 秒内有点击（contracts/bridge.schema.json）。
 * 主框架换文档（切换页面）时 [clear] 清除手势资格。不消费事件。
 */
internal class GestureClock(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val longPressMs: Long = ViewConfiguration.getLongPressTimeout().toLong(),
) {
    @Volatile
    private var lastClick: Long? = null

    private var downAt: Long? = null
    private var downX = 0f
    private var downY = 0f
    private var touchSlop = DEFAULT_TOUCH_SLOP_PX

    // 只记时刻、不消费事件，点击仍由 WebView 自己处理
    @SuppressLint("ClickableViewAccessibility")
    fun attach(webView: WebView) {
        touchSlop = ViewConfiguration.get(webView.context).scaledTouchSlop.toFloat()
        webView.setOnTouchListener { _, event ->
            onTouch(event.actionMasked, event.x, event.y, event.pointerCount)
            false
        }
    }

    /** 一个触摸事件（[action] 为 MotionEvent.actionMasked）。 */
    fun onTouch(
        action: Int,
        x: Float,
        y: Float,
        pointers: Int = 1,
    ) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downAt = now()
                downX = x
                downY = y
            }

            MotionEvent.ACTION_MOVE -> {
                if (outOfSlop(x, y)) downAt = null
            }

            MotionEvent.ACTION_UP -> {
                val start = downAt
                downAt = null
                val quick = start != null && now() - start < longPressMs
                if (quick && pointers == 1 && !outOfSlop(x, y)) lastClick = now()
            }

            // 取消、第二根手指按下：这次触摸不算点击
            else -> {
                downAt = null
            }
        }
    }

    /** 最近 [WINDOW_MS] 毫秒内有有效点击。 */
    fun recent(): Boolean = lastClick?.let { now() - it in 0..WINDOW_MS } == true

    /** 切换页面（主框架换文档）：清除手势资格。 */
    fun clear() {
        lastClick = null
        downAt = null
    }

    private fun outOfSlop(
        x: Float,
        y: Float,
    ): Boolean = abs(x - downX) > touchSlop || abs(y - downY) > touchSlop

    private companion object {
        const val WINDOW_MS = 1_000L
        const val DEFAULT_TOUCH_SLOP_PX = 24f
    }
}
