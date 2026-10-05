plugins {
    `kotlin-dsl`
}

group = "com.couli.buildlogic"

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)

    // 源码规则扫描器（KotlinSourceScanner）的单测；tools/ci/verify.sh 用 ./gradlew -p build-logic :convention:test 跑。
    testImplementation(libs.junit4)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id =
                libs.plugins.couli.android.application
                    .get()
                    .pluginId
            implementationClass = "com.couli.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id =
                libs.plugins.couli.android.library
                    .get()
                    .pluginId
            implementationClass = "com.couli.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id =
                libs.plugins.couli.android.compose
                    .get()
                    .pluginId
            implementationClass = "com.couli.buildlogic.AndroidComposeConventionPlugin"
        }
        register("kotlinLibrary") {
            id =
                libs.plugins.couli.kotlin.library
                    .get()
                    .pluginId
            implementationClass = "com.couli.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("lint") {
            id =
                libs.plugins.couli.lint
                    .get()
                    .pluginId
            implementationClass = "com.couli.buildlogic.LintConventionPlugin"
        }
    }
}
