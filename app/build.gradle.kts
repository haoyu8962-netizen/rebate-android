// App：AppShell 入口（最小可运行）。三套构建类型见 build-logic 的 couli.android.application（03 §3.6）。
plugins {
    alias(libs.plugins.couli.android.application)
    alias(libs.plugins.couli.android.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.couli.app"

    defaultConfig {
        // 三套构建类型同一个包名，测试包与正式包覆盖安装（02 §3.2）。
        applicationId = "com.couli.app"
        versionCode = 1
        versionName = "0.1.0"
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
    implementation(project(":feature:web"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
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
