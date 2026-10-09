// feature:trade —— open 购买、外跳、平台授权（APP-06b；03 §4.5、§4.7、§7.4；BR-PRICE-08 / 12 / 13、BR-ATTR-21 / 27、BR-ID-10 / 17 / 18 / 22）：
// 纯逻辑（OpenAttempt、OpenResponseAction、OpenErrorAction、LateResponsePolicy、JumpPlanRunner、LinkJumpEvents、AuthMethodPicker，
// 向量 specs/open-trade-vectors.json）、执行器（OpenPurchaseController、TradeJumpExecutor、UnionAuthFlow、AuthManageController）与界面
// （购买浮层、AuthSheet、JumpTip、AuthManage）。文案一律经 [TradeTexts] 取键（BR-TEXT-12，本模块不写中文）。
// feature 之间不得互相依赖：商品详情（feature:product）与本模块只在 App 层接线，跨功能跳转只经 Router。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.feature.trade"
}

dependencies {
    // ApiClient、PageApi、ErrorActionMapper；ApiOperations、DefaultTexts 与 kotlinx-serialization-json 经它传递
    api(project(":core:network"))
    api(project(":core:designsystem"))
    // 百川适配层接口与不可用实现（BaichuanAdapter，不引入 SDK）
    api(project(":core:tradejump"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // OpenTradeVectorTest 读 specs/open-trade-vectors.json；集成测试用 MockWebServer 当本地假服务
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
