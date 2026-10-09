// App：AppShell 入口（最小可运行）。三套构建类型见 build-logic 的 couli.android.application（03 §3.6）。
plugins {
    alias(libs.plugins.couli.android.application)
    alias(libs.plugins.couli.android.compose)
    // Navigation Compose 类型安全路由（APP-02a 的占位接线）
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.couli.app"

    defaultConfig {
        // 三套构建类型同一个包名，测试包与正式包覆盖安装（02 §3.2）。
        applicationId = "com.couli.app"
        versionCode = 1
        // routes.json 的 since 从 1.0.0 起（APP-02a：Router 用它判断「since 高于当前版本」；0.1.0 会让所有路由走升级提示）。
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "BUILD_KIND", "\"debug\"")
            buildConfigField("String", "DEFAULT_ENVIRONMENT", "\"test\"")
            buildConfigField("boolean", "DEBUG_FEATURES", "true")
        }
        getByName("staging") {
            // 构建号带可识别的测试标记；应用名资源「凑狸测试」在 src/staging/res（03 §3.6）。
            versionNameSuffix = "-staging"
            buildConfigField("String", "BUILD_KIND", "\"staging\"")
            buildConfigField("String", "DEFAULT_ENVIRONMENT", "\"staging\"")
            buildConfigField("boolean", "DEBUG_FEATURES", "true")
        }
        getByName("release") {
            buildConfigField("String", "BUILD_KIND", "\"release\"")
            buildConfigField("String", "DEFAULT_ENVIRONMENT", "\"prod\"")
            buildConfigField("boolean", "DEBUG_FEATURES", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:generated"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:config"))
    implementation(project(":core:network"))
    implementation(project(":core:session"))
    implementation(project(":core:router"))
    implementation(project(":core:tradejump"))
    implementation(project(":core:bridge"))
    implementation(project(":core:privacy"))
    implementation(project(":core:tracker"))
    implementation(project(":core:clipboard"))
    implementation(project(":core:push"))
    implementation(project(":core:storage"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:product"))
    implementation(project(":feature:agent"))
    implementation(project(":feature:orders"))
    implementation(project(":feature:wallet"))
    implementation(project(":feature:me"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:login"))
    implementation(project(":feature:web"))
    implementation(project(":feature:trade"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    // ApiClient 的 OkHttpClient 由 App 按构建类型给（debug / staging 带本机假登录服务，APP-05a）
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    // 桥调用截止时间走真实 ApiClient 的链路测试（APP-04 R2-01）
    testImplementation(libs.okhttp.mockwebserver)
    // RootScreen 阻断页的 Robolectric Compose 测试（R2-01）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
