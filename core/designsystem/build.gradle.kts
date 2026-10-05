// core:designsystem —— 设计令牌 → Compose 主题常量（03 §10）。组件在下一个任务。
plugins {
    alias(libs.plugins.couli.android.library)
    alias(libs.plugins.couli.android.compose)
}

android {
    namespace = "com.couli.core.designsystem"
}

dependencies {
    api(project(":core:generated"))
}
