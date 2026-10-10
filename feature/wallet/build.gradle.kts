// feature:wallet —— 钱包、提现、二次验证、提现记录、实名、收款账号、劳务协议（APP-09；03 §3.2、§4.2、§4.4、§10.2；01 F-WDR-01…05、08、09、13、14；
// BR-WDR、BR-ID-08 / 10 / 25、BR-FUND-18、BR-TEXT-01 / 06 / 07 / 08 / 10）：执行器（WalletController、WithdrawController、SensitiveFlow、
// StepUpController、PayoutAccountController、RealNameController、LaborAgreementController、WithdrawRecordsController）与页面。
// 判定、文案草稿表与缓存在 core:wallet（以后「我的」、设置页也要用收款账号与实名状态）。文案一律经 WalletTexts 取键（BR-TEXT-12，本模块不写中文）。
// feature 之间不得互相依赖，跨功能跳转只经 Router（路由名）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.wallet"
}

dependencies {
    // 钱包的解析、显示规则、结局表、文案、缓存；core:network（ApiClient、PageApi、未决键）、core:router、core:designsystem 经它传递
    api(project(":core:wallet"))
    // 金额隐藏偏好（AmountMaskStore，G24）
    api(project(":core:config"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // 执行器测试：MockWebServer 当本地假服务；页面测试用 Robolectric 跑 Compose
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
