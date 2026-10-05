package com.couli.buildlogic

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * core / feature 模块：Android library，带 debug / staging / release 三套构建类型，
 * debug、staging 加入 src/debugFeatures；feature 之间不得互相依赖。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("couli.lint")

            extensions.configure<LibraryExtension> {
                compileSdk = intVersion("android-compileSdk")
                defaultConfig {
                    minSdk = intVersion("android-minSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = COULI_JAVA_VERSION
                    targetCompatibility = COULI_JAVA_VERSION
                }
                buildTypes {
                    create(CouliBuildTypes.STAGING) {
                        initWith(getByName(CouliBuildTypes.DEBUG))
                        matchingFallbacks += listOf(CouliBuildTypes.DEBUG)
                    }
                    getByName(CouliBuildTypes.RELEASE) {
                        isMinifyEnabled = false
                    }
                }
                testOptions {
                    targetSdk = intVersion("android-targetSdk")
                    unitTests.isIncludeAndroidResources = true
                    unitTests.all { test -> test.jvmArgs(ROBOLECTRIC_JVM_ARGS) }
                }
                lint.targetSdk = intVersion("android-targetSdk")
                configureAndroidLint(lint)
            }

            extensions.getByType<LibraryAndroidComponentsExtension>().apply {
                onVariants(selector().all()) { variant ->
                    addDebugFeatureSources(variant.buildType, variant.sources.kotlin, variant.sources.res)
                }
            }

            dependencies {
                add("testImplementation", library("junit4"))
            }

            forbidFeatureToFeatureDependencies()
        }
    }
}
