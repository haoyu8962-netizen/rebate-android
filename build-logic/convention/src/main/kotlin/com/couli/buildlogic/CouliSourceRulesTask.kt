package com.couli.buildlogic

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * 本仓库自定义源码规则（APP-01 任务书 §2.6）：
 *   1. no-cjk-string-in-feature：feature/ 下 Kotlin 字符串字面量含 CJK 字符即失败（BR-TEXT-12）。
 *   2. sdk-init-only-in-privacy-gate：第三方 SDK 初始化调用只允许出现在 PrivacyGate.kt（03 §4.1）。
 * 规则 3（feature 互相依赖）在 couli.android.library 插件的配置阶段检查。
 *
 * 默认扫描仓库根；`-Pcouli.rules.root=<目录>` 改扫别的目录（tools/ci/lint-fixtures 用）。
 */
@DisableCachingByDefault(because = "源码规则检查每次都跑，结果只有通过或失败")
abstract class CouliSourceRulesTask : DefaultTask() {
    @get:Internal
    abstract val scanRoot: DirectoryProperty

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val root = scanRoot.get().asFile
        val scanningRepository = root.canonicalFile == repositoryRoot.get().asFile.canonicalFile
        val violations =
            sourceFiles(root, scanningRepository)
                .flatMap { file -> violationsIn(file, file.relativeTo(root).invariantSeparatorsPath) }
                .toList()
        if (violations.isNotEmpty()) {
            violations.forEach { logger.error(it) }
            throw GradleException("couliRules：${violations.size} 处违反自定义源码规则（见上）")
        }
        logger.lifecycle("couliRules：通过（扫描 $root）")
    }

    private fun violationsIn(
        file: File,
        relative: String,
    ): Sequence<String> {
        val scanned = scanKotlinSource(file.readText())
        val cjk =
            if (relative.startsWith("feature/") && file.extension == "kt") {
                scanned.strings
                    .asSequence()
                    .filter { CJK.containsMatchIn(it.text) }
                    .map { "$relative:${it.line}: [no-cjk-string-in-feature] $CJK_MESSAGE" }
            } else {
                emptySequence()
            }
        val sdkInit =
            if (file.name == PRIVACY_GATE_FILE) {
                emptySequence()
            } else {
                SDK_INIT_PATTERNS
                    .asSequence()
                    .flatMap { it.findAll(scanned.code) }
                    .map { match ->
                        val line = scanned.code.substring(0, match.range.first).count { it == '\n' } + 1
                        "$relative:$line: [sdk-init-only-in-privacy-gate] `${match.value.trim()}` $SDK_MESSAGE"
                    }
            }
        return cjk + sdkInit
    }

    private fun sourceFiles(
        root: File,
        scanningRepository: Boolean,
    ): Sequence<File> =
        root
            .walkTopDown()
            .onEnter { dir ->
                val relative = dir.relativeTo(root).invariantSeparatorsPath
                dir.name !in SKIPPED_DIRS && !(scanningRepository && relative in SKIPPED_REPOSITORY_DIRS)
            }.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.path }

    private companion object {
        const val PRIVACY_GATE_FILE = "PrivacyGate.kt"
        const val CJK_MESSAGE = "feature 模块的字符串字面量不得含中文，文案经文案字典取键（BR-TEXT-12）"
        const val SDK_MESSAGE = "第三方 SDK 只能在 PrivacyGate.onConsented() 里初始化（03 §4.1）"

        val SKIPPED_DIRS = setOf("build", ".gradle", ".git", ".idea", ".kotlin")

        // 扫描仓库本身时跳过：违反样例（另行验证必须被拦）与构建逻辑。
        val SKIPPED_REPOSITORY_DIRS = setOf("tools", "build-logic")

        val CJK = Regex("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")

        // 03 §3.5 SDK 清单里各 SDK 的初始化入口；新增 SDK 时在这里补。
        val SDK_INIT_PATTERNS =
            listOf(
                Regex("""\bMobSDK\s*\.\s*(init|submitPolicyGrantResult)\s*\("""),
                Regex("""\bMobPush\s*\.\s*init\w*\s*\("""),
                Regex("""\bSentryAndroid\s*\.\s*init\s*\("""),
                Regex("""\bSentry\s*\.\s*init\s*\("""),
                Regex("""\bWXAPIFactory\s*\.\s*createWXAPI\s*\("""),
                Regex("""\bAlibcTradeSDK\s*\.\s*(asyncInit|init)\s*\("""),
                Regex("""\bKeplerApiManager\s*\.\s*asyncInitSdk\s*\("""),
            )
    }
}
