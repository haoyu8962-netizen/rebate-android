// feature:search —— 搜索与结果（APP-06a；03 §3.2、§4.2、§4.3；BR-PROD-07 / 08 / 10、BR-PRICE-08 / 15）：纯逻辑 SearchProductFlow
// （请求构造、平台 Tab、分页、结果态、请求世代）、执行器 SearchController（经 ApiClient 搜索、订阅 ConfigStore 的 search_status）与页面。
// 文案一律经 [SearchTexts] 取键（BR-TEXT-12，本模块不写中文）。feature 之间不得互相依赖，跨功能跳转只经 Router。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.search"
}

dependencies {
    // ApiClient、PageApi、ErrorActionMapper；ApiOperations、DefaultTexts 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // 商品卡映射与详情判定（ProductCardMapper、ProductDetailState，feature:product 共用）
    api(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // SearchProductVectorTest 读 specs/search-product-vectors.json；集成测试用 MockWebServer 当本地假服务
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
