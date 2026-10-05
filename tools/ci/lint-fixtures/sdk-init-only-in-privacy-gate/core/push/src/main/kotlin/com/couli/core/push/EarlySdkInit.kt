package com.couli.core.push

// lint 违反样例（sdk-init-only-in-privacy-gate，03 §4.1）：在 PrivacyGate 之外初始化第三方 SDK。
// 只是文本样例，不参与编译；tools/ci/verify.sh 证明它会被拦。
object EarlySdkInit {
    fun start(context: Any) {
        MobSDK.init(context)
    }
}
