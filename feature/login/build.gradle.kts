// feature:login —— 短信登录主链路（APP-05a；03 §4.2、§4.4；BR-ID-04、BR-ID-05、BR-ID-10）：纯逻辑 LoginFlow（规范化、协议勾选、
// 倒计时、错误映射、邀请码、登录后计划）、执行器 LoginService（经 ApiClient 保存会话、经 LoginHost 交给 Router）与页面。
// 文案一律经 [LoginTexts] 取键（BR-TEXT-12，本模块不写中文）。feature 之间不得互相依赖，跨功能跳转只经 Router。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.login"
}

dependencies {
    // ApiClient、ErrorActionMapper、SessionTokens；ApiOperations、DefaultTexts 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // APP-13a：BindPhone 待确认（BindPhonePendingPolicy、PhoneChangeOutcomePolicy、AccountShared、AccountDraftTexts，G07；Feature → Core）
    implementation(project(":core:me"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 页面状态：ViewModel + StateFlow，跟随登录页的回退栈条目（03 §3.3）
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 系统返回：验证码步回手机号步
    implementation(libs.androidx.activity.compose)

    // LoginVectorTest 读 specs/login-vectors.json；LoginServiceIntegrationTest 用 MockWebServer 当本地假服务
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
