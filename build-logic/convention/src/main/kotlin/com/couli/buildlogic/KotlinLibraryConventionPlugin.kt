package com.couli.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * 纯 Kotlin（JVM）模块。APP-01 里还没有模块使用：现有 core 模块以后都要用 Android 能力
 * （Context、Keystore 等），先建成 Android library。以后出现与平台无关的纯逻辑模块时用它。
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("couli.lint")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = COULI_JAVA_VERSION
                targetCompatibility = COULI_JAVA_VERSION
            }
            tasks.withType<KotlinJvmCompile>().configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(COULI_JAVA_VERSION.toString()))
            }
            dependencies {
                add("testImplementation", library("junit4"))
            }
        }
    }
}
