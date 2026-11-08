package com.couli.buildlogic

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider

/**
 * ktlint 与 detekt（03 §3.3 静态检查），以 CLI 经 JavaExec 运行（ktlint CLI、detekt CLI）：
 *   ktlintCheck / ktlintFormat / detekt —— 每个模块各一组，`./gradlew ktlintCheck detekt` 跑全部。
 * 根项目另有：
 *   couliRules —— 本仓库自定义规则（feature 字符串不含中文、SDK 初始化只在 PrivacyGate、页面禁写死样式），
 *                 根项目的 detekt 依赖它，所以 `./gradlew detekt` 也会跑。
 * 生成物目录（core:generated 的 src/main、src/debugFeatures）不检查。
 */
class LintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val sources = kotlinSources()
            registerKtlintTasks(sources)
            val detekt = registerDetektTask(sources)

            pluginManager.withPlugin("base") {
                tasks.named("check") {
                    dependsOn("ktlintCheck", detekt)
                }
            }

            if (this == rootProject) {
                val rules =
                    tasks.register<CouliSourceRulesTask>("couliRules") {
                        group = "verification"
                        description = "本仓库自定义源码规则（BR-TEXT-12、03 §4.1）"
                        val override = providers.gradleProperty("couli.rules.root").orNull
                        scanRoot.set(if (override == null) rootDir else rootProject.file(override))
                        repositoryRoot.set(rootDir)
                        // LINT-STYLE：-Pcouli.rules.writeBaseline=true 按现状重写扫描根下的 tools/ci/hardcoded-style-baseline.json
                        writeStyleBaseline.set(providers.gradleProperty("couli.rules.writeBaseline").map { it.toBoolean() }.orElse(false))
                    }
                detekt.configure { dependsOn(rules) }
            }
        }
    }

    private fun Project.registerKtlintTasks(sources: FileTree) {
        val ktlintClasspath = toolClasspath("ktlintCli", "ktlint-cli")
        tasks.register<JavaExec>("ktlintCheck") {
            group = "verification"
            description = "ktlint 检查本模块 Kotlin 源码与构建脚本"
            classpath = ktlintClasspath
            mainClass.set(KTLINT_MAIN)
            workingDir = rootDir
            inputs.files(sources)
            argumentProviders.add(KtlintArguments(sources, format = false))
        }
        tasks.register<JavaExec>("ktlintFormat") {
            group = "formatting"
            description = "ktlint 自动修正本模块 Kotlin 源码与构建脚本"
            classpath = ktlintClasspath
            mainClass.set(KTLINT_MAIN)
            workingDir = rootDir
            argumentProviders.add(KtlintArguments(sources, format = true))
        }
    }

    private fun Project.registerDetektTask(sources: FileTree): TaskProvider<JavaExec> {
        val detektClasspath = toolClasspath("detektCli", "detekt-cli")
        val detektInputs = detektInputDirectories()
        val detektConfig = rootProject.file("config/detekt/detekt.yml")
        return tasks.register<JavaExec>("detekt") {
            group = "verification"
            description = "detekt 检查本模块 Kotlin 源码"
            classpath = detektClasspath
            mainClass.set(DETEKT_MAIN)
            workingDir = rootDir
            inputs.files(sources)
            inputs.file(detektConfig)
            onlyIf { detektInputs().isNotEmpty() }
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        "--input",
                        detektInputs().joinToString(",") { it.absolutePath },
                        "--config",
                        detektConfig.absolutePath,
                        "--build-upon-default-config",
                        "--excludes",
                        "**/build/**",
                    )
                },
            )
        }
    }

    private class KtlintArguments(
        private val sources: FileTree,
        private val format: Boolean,
    ) : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> {
            val files = sources.files.map(File::getAbsolutePath).sorted()
            val flags = if (format) listOf("--format") else emptyList()
            return listOf("--relative") + flags + files
        }
    }

    private companion object {
        const val KTLINT_MAIN = "com.pinterest.ktlint.Main"
        const val DETEKT_MAIN = "io.gitlab.arturbosch.detekt.cli.Main"
    }
}

/** CLI 工具的运行时类路径（Java runtime、非 shadow 变体）。 */
private fun Project.toolClasspath(
    name: String,
    alias: String,
): Configuration {
    val configuration = configurations.maybeCreate(name)
    configuration.isCanBeConsumed = false
    configuration.isCanBeResolved = true
    configuration.attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
        )
    }
    dependencies.add(name, library(alias))
    return configuration
}

/** 生成物目录：不手改，也不做 ktlint / detekt 检查。 */
private val GENERATED_SOURCE_DIRS: Map<String, Set<String>> =
    mapOf(":core:generated" to setOf("main", "debugFeatures"))

private fun Project.kotlinSources(): FileTree =
    if (this == rootProject) {
        fileTree(rootDir) {
            include("*.gradle.kts")
            include("build-logic/*.gradle.kts")
            include("build-logic/convention/*.gradle.kts")
            include("build-logic/convention/src/**/*.kt")
        }
    } else {
        val excluded = GENERATED_SOURCE_DIRS[path].orEmpty()
        fileTree(projectDir) {
            include("*.gradle.kts")
            include("src/**/*.kt")
            exclude("build/**")
            excluded.forEach { exclude("src/$it/**") }
        }
    }

private fun Project.detektInputDirectories(): () -> List<File> {
    val root = this == rootProject
    val base = if (root) rootProject.file("build-logic/convention/src") else file("src")
    val excluded = GENERATED_SOURCE_DIRS[path].orEmpty()
    return {
        if (root) {
            listOf(base).filter(File::isDirectory)
        } else {
            base
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name !in excluded }
                .sortedBy(File::getName)
        }
    }
}
