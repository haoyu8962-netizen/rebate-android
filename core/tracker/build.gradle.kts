// core:tracker —— 埋点：按 events.yaml 生成的事件。APP-01 只放最小可编译占位。
plugins {
    alias(libs.plugins.couli.android.library)
}

android {
    namespace = "com.couli.core.tracker"
}
