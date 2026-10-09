// feature:orders —— 订单列表与详情（APP-08；03 §3.2、§4.2；01 F-ORD-07 / 09 / 15 / 16；BR-TEXT-02 / 03 / 04 / 05、BR-ATTR-17 / 21）：
// 执行器 OrderListController（查询轮次、翻页、断网缓存、42901）、OrderDetailController 与页面（OrderListScreen、OrderDetailScreen）。
// 判定与待跟单卡在 core:orders（商品详情也要用待跟单卡）。文案一律经 OrderTexts 取键（BR-TEXT-12，本模块不写中文）。
// feature 之间不得互相依赖，跨功能跳转只经 Router（路由名）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.orders"
}

dependencies {
    // 订单解析与显示规则、待跟单卡、缓存；core:network（ApiClient、PageApi）、core:designsystem 经它传递
    api(project(":core:orders"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // 执行器与页面测试：MockWebServer 当本地假服务，Robolectric 跑 Compose
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
