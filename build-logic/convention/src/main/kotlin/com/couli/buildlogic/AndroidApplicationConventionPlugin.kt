package com.couli.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * App 模块：三套构建类型 debug / staging / release（03 §3.6、02 §3.2），applicationId 三套相同。
 * staging 在 debug 基础上关可调试；不设 applicationIdSuffix（与正式包同包名、覆盖安装）。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("couli.lint")

            extensions.configure<ApplicationExtension> {
                compileSdk = intVersion("android-compileSdk")
                defaultConfig {
                    minSdk = intVersion("android-minSdk")
                    targetSdk = intVersion("android-targetSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = COULI_JAVA_VERSION
                    targetCompatibility = COULI_JAVA_VERSION
                }
                buildTypes {
                    getByName(CouliBuildTypes.DEBUG) {
                        isDebuggable = true
                        isMinifyEnabled = false
                    }
                    create(CouliBuildTypes.STAGING) {
                        initWith(getByName(CouliBuildTypes.DEBUG))
                        isDebuggable = false
                        isMinifyEnabled = false
                        // 依赖库没有 staging 时按 debug 取
                        matchingFallbacks += listOf(CouliBuildTypes.DEBUG)
                    }
                    getByName(CouliBuildTypes.RELEASE) {
                        isDebuggable = false
                        isMinifyEnabled = true
                        isShrinkResources = true
                        // 签名留空：正式签名证书由负责人提供后再接（../couli-runs/NATIVE/needs.md N-04）。
                        // assembleRelease 产出未签名 APK。
                        signingConfig = null
                    }
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                    unitTests.all { test ->
                        test.jvmArgs(ROBOLECTRIC_JVM_ARGS)
                        // DS-02：目录组件浅 / 深快照与大字号检查共 2000 余个 Robolectric 原生绘制用例，默认 512M 堆在同一测试进程里会耗尽
                        test.maxHeapSize = ROBOLECTRIC_MAX_HEAP
                    }
                }
                configureAndroidLint(lint)
            }

            extensions.getByType<ApplicationAndroidComponentsExtension>().apply {
                onVariants(selector().all()) { variant ->
                    addDebugFeatureSources(variant.buildType, variant.sources.kotlin, variant.sources.res)
                }
            }
        }
    }
}
