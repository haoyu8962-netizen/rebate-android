package com.couli.core.push

// lint 违反样例（sdk-init-only-in-privacy-gate，03 §4.1）：SDK 初始化调用写在字符串模板 ${…} 里。
// 只是文本样例，不参与编译；tools/ci/verify.sh 证明插值里的代码不会被当成字符串抹掉。
object SdkInitInInterpolation {
    fun start(context: Any): String = "started: ${SentryAndroid.init(context)}"
}
