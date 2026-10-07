package com.couli.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Android 的设备原始标识：只用 ANDROID_ID（BR-ID-09；MVP 不取 OAID、IMEI 等）。只由 [DeviceRegistry] 在已同意隐私政策之后读取
 * （BR-ID-11）；读不到（系统抛错、返回 null）按取不到处理，由 DeviceIdPolicy 判为无效、不注册。原值只用于本机求 device_hash，不保存、
 * 不上报。
 */
class AndroidDeviceIdReader(
    context: Context,
) : DeviceIdReader {
    private val resolver = context.applicationContext.contentResolver

    // BR-ID-09 规定 Android 只用 ANDROID_ID 求 device_hash（只哈希后上报、不存原值），不是广告标识用途
    @SuppressLint("HardwareIds")
    override fun read(): String? = runCatching { Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) }.getOrNull()
}
