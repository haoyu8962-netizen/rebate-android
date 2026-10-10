// feature:home —— 首页 SDUI（APP-11；01 F-HOME-01…09；03 §6）：首页控制器 HomeController（三级兜底、数据源、公告条、弹窗、下拉刷新、42901 禁用期）与渲染器
// （8 种卡片、下拉刷新、弹窗、置灰由 App 加在根视图）。解码、判定、存储、草稿表在 core:home；文案一律经 HomeTexts / 契约字典取键（BR-TEXT-12，本模块不写中文）。
// 首页与 HomePreview（app 的调试源码集）共用同一个渲染器。feature 之间不得互相依赖，跨功能跳转只经 Router（路由名）。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.couli.feature.home"
}

// golden 页截图基准入库：src/test/snapshots（recordRoborazziDebug 生成，verifyRoborazziDebug 比对；tools/ci/verify.sh 跑后者，G29）。
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

dependencies {
    // 首页解码、判定、本机存储、待定 operation 端口与草稿表；core:network、core:router、core:wallet、core:designsystem 经它传递
    api(project(":core:home"))
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}
