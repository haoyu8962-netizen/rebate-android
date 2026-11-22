// App：AppShell 入口（最小可运行）。三套构建类型见 build-logic 的 couli.android.application（03 §3.6）。
plugins {
    alias(libs.plugins.couli.android.application)
    alias(libs.plugins.couli.android.compose)
    // DS-02：真实目录样例的浅 / 深 Roborazzi 基准（仅测试设施）。
    alias(libs.plugins.roborazzi)
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

    // APP-10：debug / staging 的本机假 Agent 流（src/debugFeatures 的 AgentFakeStream）回放锁定契约的 15 个流 fixture（contracts/snapshot，逐字节原样）；
    // 只加进这两个构建类型的 assets，Release 制品里没有
    sourceSets {
        listOf("debug", "staging").forEach { type ->
            getByName(type).assets.srcDir(rootProject.file("contracts/snapshot/contracts/fixtures"))
        }
    }

    // N-22（D10-2539）：bridge_origins 验签的随包公钥表 BuildConfig.CONFIG_SIGN_KEYS，空白分隔的若干项 `env:kid:<SPKI DER 十六进制>`
    // （AppConfig.bundledSignKeys 经 ConfigSignKeys.parse 读）。staging 钥只进 debug / staging；release 只放生产钥。
    // 生产 kid 与公钥由负责人与 ops 提供，格式 `prod:<kid>:<SPKI DER hex>`，每 env 随包 current + next 两把（契约 Key rotation）；
    // 给到之前为空 → Release 生产可信白名单为空（WebPage 不开），这是正确的保守状态。
    val configSignKeysProd = ""
    // staging：kid config-staging-2026-11b，couli-runs/NATIVE/N22-VECTORS/es256.pub.pem 的 SPKI DER（sha256 前 16 位 07ba14efc5908e5c；只是公钥）
    val configSignKeysStaging =
        "staging:config-staging-2026-11b:" +
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004ae883f464888bece2a33c015a42e897e07672c37b43a244f556678f595417dbc" +
            "f359128f1ee420e841b4b286d06e487882b7d4a1a92abd5e7e81db5715462af0"
    val configSignKeysNonRelease = listOf(configSignKeysStaging, configSignKeysProd).filter { it.isNotBlank() }.joinToString(" ")

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "BUILD_KIND", "\"debug\"")
            buildConfigField("String", "DEFAULT_ENVIRONMENT", "\"test\"")
            buildConfigField("boolean", "DEBUG_FEATURES", "true")
            buildConfigField("String", "CONFIG_SIGN_KEYS", "\"$configSignKeysNonRelease\"")
        }
        getByName("staging") {
            // 构建号带可识别的测试标记；应用名资源「凑狸测试」在 src/staging/res（03 §3.6）。
            versionNameSuffix = "-staging"
            buildConfigField("String", "BUILD_KIND", "\"staging\"")
            buildConfigField("String", "DEFAULT_ENVIRONMENT", "\"staging\"")
            buildConfigField("boolean", "DEBUG_FEATURES", "true")
            buildConfigField("String", "CONFIG_SIGN_KEYS", "\"$configSignKeysNonRelease\"")
        }
        getByName("release") {
            buildConfigField("String", "BUILD_KIND", "\"release\"")
            buildConfigField("String", "DEFAULT_ENVIRONMENT", "\"prod\"")
            buildConfigField("boolean", "DEBUG_FEATURES", "false")
            // 只放生产钥；不得出现任何非 prod 的 kid / 公钥（release-scan 口径）
            buildConfigField("String", "CONFIG_SIGN_KEYS", "\"$configSignKeysProd\"")
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
    implementation(project(":core:orders"))
    implementation(project(":core:push"))
    implementation(project(":core:storage"))
    implementation(project(":core:home"))
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
    implementation(libs.androidx.lifecycle.process)
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
    // 首页设计稿比对截图（APP-11；只写到 build/home-shots，不入库、不比对）：复用已锁定的 Roborazzi
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
