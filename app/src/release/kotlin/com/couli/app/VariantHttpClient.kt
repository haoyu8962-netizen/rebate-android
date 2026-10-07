package com.couli.app

import android.content.Context
import okhttp3.OkHttpClient

/** Release：普通 OkHttpClient（没有任何假服务；网络核心自己关自动重发与重定向）。 */
@Suppress("UnusedParameter") // 与 debug / staging 源码集同一签名
internal fun variantHttpClient(context: Context): OkHttpClient = OkHttpClient()
