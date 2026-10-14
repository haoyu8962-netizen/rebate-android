package com.couli.app.trade

import android.content.Context
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import com.couli.feature.trade.WebCodeAuthorizer

/**
 * Release：淘宝 web_code 授权的回跳 scheme 在 apps.json inbound 登记前不可执行（TODO(N-28)），按「本端执行不了」处理（授权失败二选一）。
 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantWebCodeAuthorizer(context: Context): WebCodeAuthorizer = UnavailableWebCodeAuthorizer

/** 「将通过浏览器打开」的提示停留时长。 */
internal fun variantBrowserNoticeMillis(): Long = OpenPurchaseController.BROWSER_NOTICE_MILLIS

/** 外跳端口：Release 原样用 Android 实现。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantJumpPorts(
    context: Context,
    base: JumpPorts,
): JumpPorts = base

/** 商品图缓存的来源：Release 没有替身、环境恒为 prod，来源恒定。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantImageSource(context: Context): () -> String = { AppProductImages.RELEASE_SOURCE }
