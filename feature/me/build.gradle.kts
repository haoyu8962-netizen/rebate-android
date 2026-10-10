// feature:me —— 「我的」Tab、设置（含修改昵称、通知设置子页与协议半屏）、隐私中心、关于、封禁 / 冻结说明（RiskNotice）、账户申诉（Appeal）与统一客服出口的
// 半屏（APP-12；01 §4.2；BR-ID-02 / 04 / 07 / 11 / 13 / 15 / 16 / 31 / 33 / 36 / 39、BR-TEXT-10 / 11 / 14 / 23）：执行器与页面。判定、解析、文案草稿表、
// 客服出口状态机在 core:me；42901 禁用期与错误分类、提现单文案复用 core:wallet（APP-09 抽出的共用实现）。文案一律经 MeTexts 取键（BR-TEXT-12，本模块
// 不写中文）。feature 之间不得互相依赖，跨功能跳转只经 Router（路由名）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.me"
}

dependencies {
    // 「我的」类纯规则、解析、草稿表、客服出口；core:network、core:designsystem 经它传递
    api(project(":core:me"))
    // 42901 禁用期（WalletRateGate / SendProbe）、错误分类（WalletErrors）、进行中提现的状态标题（WithdrawalText，G16）
    api(project(":core:wallet"))
    // 金额隐藏偏好（AmountMaskStore，G06）
    api(project(":core:config"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
