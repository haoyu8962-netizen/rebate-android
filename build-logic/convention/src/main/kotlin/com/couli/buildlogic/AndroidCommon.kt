package com.couli.buildlogic

import com.android.build.api.dsl.Lint
import com.android.build.api.variant.SourceDirectories
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** 统一的 Java / Kotlin 字节码目标。 */
val COULI_JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun Project.intVersion(name: String): Int =
    libs
        .findVersion(name)
        .orElseThrow { GradleException("libs.versions.toml 缺少版本 $name") }
        .requiredVersion
        .toInt()

fun Project.library(alias: String): Any =
    libs
        .findLibrary(alias)
        .orElseThrow { GradleException("libs.versions.toml 缺少库 $alias") }
        .get()

/** debug、staging 变体加入 src/debugFeatures（目录存在时）；release 不加。 */
fun Project.addDebugFeatureSources(
    buildType: String?,
    kotlin: SourceDirectories.Flat?,
    res: SourceDirectories.Layered?,
) {
    if (buildType !in CouliBuildTypes.withDebugFeatures) return
    if (file(CouliBuildTypes.DEBUG_FEATURES_KOTLIN_DIR).isDirectory) {
        kotlin?.addStaticSourceDirectory(CouliBuildTypes.DEBUG_FEATURES_KOTLIN_DIR)
    }
    if (file(CouliBuildTypes.DEBUG_FEATURES_RES_DIR).isDirectory) {
        res?.addStaticSourceDirectory(CouliBuildTypes.DEBUG_FEATURES_RES_DIR)
    }
}

/** Robolectric 单测进程的堆上限（DS-02 用例量增加后 512M 不够）。 */
const val ROBOLECTRIC_MAX_HEAP = "3g"

/**
 * Robolectric 单测进程的 JVM 参数。
 *
 * - `--add-exports/--add-opens`：Robolectric 在 JDK 17+ 上读写 SharedPreferences 等文件时要访问
 *   jdk.internal.access（FileDescriptorInterceptor）。
 * - `-XX:CompileCommand=exclude,…getPackageManager`（F-AND-67 / FU-AND-0727）：JBR 21.0.6（b895.109）的 C2
 *   在编译 Robolectric 改写后的 `getPackageManager`（内联其 invokedynamic 方法句柄）时偶发 SIGSEGV
 *   （`Node::uncast`，C2 CompilerThread），测试进程以 134 退出。两份 hs_err 的 Current CompileTask 分别是
 *   `android.content.ContextWrapper::$$robo$$android_content_ContextWrapper$getPackageManager` 与
 *   `android.app.ContextImpl::getPackageManager`；这里只把这两个类上名字以 getPackageManager 结尾的方法
 *   （原方法 + `$$robo$$` 改写体）排除出 JIT 编译（被排除的方法也不会被内联），其余方法与 C2 不受影响。
 *   升级到修复该问题的 JBR 后可删。
 */
val ROBOLECTRIC_JVM_ARGS: List<String> =
    listOf(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "-XX:CompileCommand=exclude,android.content.ContextWrapper::*getPackageManager",
        "-XX:CompileCommand=exclude,android.app.ContextImpl::*getPackageManager",
    )

/** Android Lint：0 问题；生成物目录由 config/lint/lint.xml 排除。 */
fun Project.configureAndroidLint(lint: Lint) {
    lint.apply {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        checkDependencies = false
        lintConfig = rootProject.file("config/lint/lint.xml")
        // 版本更新提示类检查不作为 CI 失败条件：升级依赖走单独的任务与评审。
        disable +=
            setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                "OldTargetApi",
            )
    }
}

/**
 * 规则 3（03 §3.1）：feature 模块之间不得互相依赖，跨功能跳转只经 Router。
 * 任何 :feature:* 依赖另一个 :feature:* 即在配置阶段失败。
 */
fun Project.forbidFeatureToFeatureDependencies() {
    if (!path.startsWith(":feature:")) return
    val self = path
    configurations.configureEach {
        val configurationName = name
        dependencies.configureEach {
            if (this is ProjectDependency && path.startsWith(":feature:") && path != self) {
                throw GradleException(
                    "[feature-to-feature-dependency] $self 的 $configurationName 依赖了 $path：" +
                        "feature 模块之间不得互相依赖，跨功能跳转只经 Router 路由名（03 §3.1）",
                )
            }
        }
    }
}
