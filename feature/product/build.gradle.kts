// feature:product —— 商品详情（APP-06a；03 §3.2、§4.2；BR-PRICE-01 / 08 / 11 / 22、TRADE-08 / 10 / 21）：执行器 ProductDetailController
// （getProduct → ProductDetailState，判定在 core:designsystem）与页面。购买只到「发出购买意图」为止（TODO(APP-06b)）。
// 文案一律经 [ProductTexts] 取键（BR-TEXT-12，本模块不写中文）。feature 之间不得互相依赖，跨功能跳转只经 Router。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.product"
}

dependencies {
    // ApiClient、PageApi、ErrorActionMapper；ApiOperations、DefaultTexts 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    // 商品卡映射与详情判定（ProductCardMapper、ProductDetailState，feature:product 共用）
    api(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // 集成测试用 MockWebServer 当本地假服务
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
