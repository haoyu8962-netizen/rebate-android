package com.couli.app.trade

import android.content.Context
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.OpenPurchaseController
import com.couli.feature.trade.WebCodeAuthorizer

/** Release 的 web_code 装配（FU-AND-TB-WEBCODE-A，设计稿 §7）：直接用真实授权器 [real]（Custom Tab + couli://auth/taobao 回跳）。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantWebCodeAuthorizer(
    context: Context,
    real: WebCodeAuthorizer,
): WebCodeAuthorizer = real

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
