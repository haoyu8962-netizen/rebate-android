// core:push —— 推送令牌上报（APP-12；G17、G18；BR-ID-07 细则「推送令牌与会话」）：令牌来源接口 PushTokenSource（Release 恒为无令牌，Debug 假实现在
// App 的 debugFeatures 源码集）、会话级执行器 PushTokenReporter 与会话跟踪 PushSessionTracker。纯策略 PushReportPolicy 在 core:me（向量测试）。
// 不接入任何真实推送 SDK、不写任何推送凭据（TODO(N-33)：MobPush AppKey 等由负责人提供后在 PrivacyGate.onConsented 的推送步骤里初始化）。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.push"
}

dependencies {
    // PushReportPolicy；core:network（ApiCall、PageApi、CallOwnership）经它传递
    api(project(":core:me"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
