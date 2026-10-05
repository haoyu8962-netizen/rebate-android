package com.couli.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType

/** Jetpack Compose（Material 3 作底层，外观由令牌定制；03 §3.3）。须在 couli.android.* 之后应用。 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
            extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true

            dependencies {
                val bom = platform(library("compose-bom"))
                add("implementation", bom)
                add("androidTestImplementation", bom)
                add("implementation", library("compose-ui"))
                add("implementation", library("compose-ui-graphics"))
                add("implementation", library("compose-foundation"))
                add("implementation", library("compose-material3"))
                add("implementation", library("compose-ui-tooling-preview"))
                add("debugImplementation", library("compose-ui-tooling"))
            }
        }
    }
}
