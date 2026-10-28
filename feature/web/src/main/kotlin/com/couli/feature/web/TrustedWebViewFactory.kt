package com.couli.feature.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.couli.core.bridge.ClearingTrustedWebStore
import com.couli.core.bridge.TrustedWebStore

/**
 * 可信 WebView 的唯一工厂（F-X-25 v2，D10-310 (6)）。setProfile 只能在 WebView 首次使用前调用，所以所有可信 WebView（容器页、一致性页、
 * 设置页清缓存的临时实例、测试夹具）都经这里建：[base] 建出实例后**立即**挂到存储 [create] 指定的配置文件上（[profiles] 为 true 的 P
 * 路径），之后才交出去被使用；挂不上即销毁并抛出（调用方按失败处理，绝不落回默认配置文件）。F 路径（[profiles] 为 false）可信容器就用
 * 默认配置文件，不调 setProfile。
 */
class TrustedWebViewFactory(
    private val store: TrustedWebStore,
    val profiles: Boolean,
    private val base: (Context) -> WebView = { WebView(it) },
) {
    /** 当前活动存储。 */
    fun current(): String = store.current()

    /** 在活动存储上建一份可信 WebView。 */
    fun create(context: Context): WebView = create(context, store.current())

    /** 在存储 [store] 上建一份可信 WebView（P：配置文件 [store]，建好即挂上；F：默认配置文件）。主线程。 */
    @Suppress("TooGenericExceptionCaught") // 系统 WebView 建配置文件 / setProfile 抛任何运行时异常：销毁这份再原样抛出（绝不落回默认配置文件）
    fun create(
        context: Context,
        store: String,
    ): WebView {
        val web = base(context)
        if (!profiles) return web
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(store)
                WebViewCompat.setProfile(web, store)
            } catch (error: RuntimeException) {
                web.destroy()
                throw error
            }
        } else {
            // 本安装已定为 P 路径而系统 WebView 不再支持多配置文件（降级）：不落回默认配置文件，按失败处理
            web.destroy()
            error("MULTI_PROFILE unavailable for trusted store $store")
        }
        return web
    }

    companion object {
        /**
         * 不涉及会话的可信容器（没有切号协调器的独立容器、既有测试）：默认配置文件，不换存储。[base] 由调用方给（测试替身或系统 WebView）。
         */
        fun defaultProfile(base: (Context) -> WebView = { WebView(it) }): TrustedWebViewFactory =
            TrustedWebViewFactory(DefaultProfileStore, false, base)
    }
}

/** 默认配置文件、不换存储（只给 [TrustedWebViewFactory.defaultProfile]）：rotate 一律失败（没有切号协调器会调它）。 */
private object DefaultProfileStore : TrustedWebStore {
    override fun current(): String = ClearingTrustedWebStore.DEFAULT_STORE

    override fun rotate(completed: (Boolean) -> Unit) = completed(false)

    override fun discardPending(completed: (Boolean) -> Unit) = completed(true)
}
