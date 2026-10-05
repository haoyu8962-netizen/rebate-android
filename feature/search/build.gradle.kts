// feature:search —— 占位。feature 之间不得互相依赖（couli.android.library 检查），跨功能跳转只经 Router。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.feature.search"
}
