package com.couli.core.privacy

/**
 * 隐私闸门（03 §4.1）：所有第三方 SDK 只在 [onConsented] 里初始化。
 * 自定义规则 sdk-init-only-in-privacy-gate 只放行本文件里的 SDK 初始化调用。
 *
 * TODO(APP-02): 同意状态、首启弹窗与二次说明、基本模式、初始化顺序（BR-ID-11）。
 */
object PrivacyGate {
    /** 用户同意当前版本隐私政策之后调用。APP-01 不集成任何 SDK，所以这里还没有内容。 */
    fun onConsented() {
        // TODO(APP-02): 设备注册 → 推送 SDK → 联盟 SDK → 崩溃 SDK → 微信 SDK（03 §4.1）。
    }
}
