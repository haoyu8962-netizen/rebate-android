package com.couli.buildlogic

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * 本仓库自定义源码规则（APP-01 任务书 §2.6）：
 *   1. no-cjk-string-in-feature：feature/ 下 Kotlin 字符串字面量含 CJK 字符即失败（BR-TEXT-12）；测试源码集（src/test、src/androidTest）除外。
 *   2. sdk-init-only-in-privacy-gate：第三方 SDK 初始化调用只允许出现在 PrivacyGate.kt（03 §4.1）。
 * 规则 3（feature 互相依赖）在 couli.android.library 插件的配置阶段检查。
 *
 * 默认扫描仓库根；`-Pcouli.rules.root=<目录>` 改扫别的目录（tools/ci/lint-fixtures 用）。
 *
 * fail-closed（F-X-07，FU-AND-0715；对照鸿蒙 HM-LINT-10）：扫描根不存在、一个源码文件都没有、文件读不出、词法扫不完整（未闭合的字符串 / 块注释 /
 * 字符字面量 / 反引号 / 模板，[ScanProblem]）都算失败（`[scan-incomplete]`），任务以非零退出，不只打印诊断。第 1 轮评审 R1-04 / R1-05：Java 文件用
 * Java 词法（块注释不嵌套，[scanJavaSource]），不支持的语言或构造同样算扫不完整；某个子目录列不出内容（遍历失败）也算扫不完整。第 2 轮评审 R2-02 / R2-03：
 * 单独的 CR 也是换行（[normalizeLineTerminators]）；目录能列出名字但条目属性查不了（有读权限、没有执行 / 搜索权限）也算扫不完整。
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
        val scanningRepository = root.isDirectory && root.canonicalFile == repositoryRoot.get().asFile.canonicalFile
        val violations = CouliSourceRules.check(root, scanningRepository)
        // DS-02 G1（组件清单 §6）：Feature 直接调系统基础控件——先只警告，不让任务失败；现有违规见 tools/ci/design-system-required-baseline.txt
        val baseline = if (scanningRepository) DesignSystemGuard.baseline(repositoryRoot.get().asFile) else emptyList()
        val warnings = CouliSourceRules.warnings(root, scanningRepository)
        DesignSystemGuard.annotate(warnings, baseline).forEach { logger.warn(it) }
        if (warnings.isNotEmpty()) logger.warn("couliRules：${warnings.size} 处 [design-system-required] 警告（只警告，不失败）")
        if (violations.isNotEmpty()) {
            violations.forEach { logger.error(it) }
            throw GradleException("couliRules：${violations.size} 处违反自定义源码规则或扫不完整（见上）")
        }
        logger.lifecycle("couliRules：通过（扫描 $root）")
    }
}

/**
 * 规则本体（纯函数，任务与单测共用；第 1 轮评审 R1-04 / R1-05）：返回全部违反与扫不完整的诊断，空 = 通过。
 * - 按文件语言选词法（[scanSource]：`.kt` Kotlin、`.java` Java；其他语言不支持 → 扫不完整）；
 * - 目录遍历失败（某个子目录列不出内容：没有权限、I/O 错误）→ 扫不完整，不静默跳过整棵子树。
 */
internal object CouliSourceRules {
    @Suppress("ReturnCount") // 扫描根不存在、没有源码、逐文件三个出口
    fun check(
        root: File,
        scanningRepository: Boolean,
    ): List<String> {
        if (!root.isDirectory) return listOf("[scan-incomplete] 扫描根不存在：$root")
        val listing = sourceFiles(root, scanningRepository)
        val walkFailures = listing.failures.map { "$it，$INCOMPLETE_MESSAGE" }
        if (listing.files.isEmpty() && walkFailures.isEmpty()) return listOf("[scan-incomplete] 扫描根下没有 Kotlin / Java 源码：$root")
        return walkFailures + listing.files.flatMap { file -> violationsIn(file, file.relativeTo(root).invariantSeparatorsPath) }
    }

    @Suppress("ReturnCount") // 读不出、不支持的语言、逐项规则三个出口
    private fun violationsIn(
        file: File,
        relative: String,
    ): List<String> {
        val text =
            runCatching { file.readText() }.getOrElse { error ->
                return listOf("$relative:1: [scan-incomplete] 读不出文件（${error.message}），无法保证扫完")
            }
        val scanned =
            scanSource(file.extension, text)
                ?: return listOf("$relative:1: [scan-incomplete] 不支持的源码语言（.${file.extension}），$INCOMPLETE_MESSAGE")
        // 扫不完整：边界是猜的，之后的字符串与代码归属不可信——整份文件按失败报（不只打印诊断）
        val incomplete = scanned.problems.map { "$relative:${it.line}: [scan-incomplete] ${it.reason}，$INCOMPLETE_MESSAGE" }
        val cjk =
            // 只管业务页面的源码（BR-TEXT-12）：测试源码（src/test、src/androidTest 的断言说明、Codex 先写的向量测试）不面向用户，不在此列
            if (relative.startsWith("feature/") && file.extension == "kt" && TEST_SOURCE_DIRS.none { it in relative }) {
                // FU-AND-0717（F-X-07 补充二）：原文与还原转义后的文字都查（`"\u4e2d"` 编译后是中文），字符字面量（`'中'`、`'\u4e2d'`）同样算
                (scanned.strings + scanned.chars)
                    .sortedBy { it.line }
                    .asSequence()
                    .filter { CJK.containsMatchIn(it.text) || CJK.containsMatchIn(it.decoded) }
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
        return incomplete + (cjk + sdkInit).toList()
    }

    /**
     * DS-02 G1 警告（组件清单 §6）：feature 模块的非测试源码直接调用「必须经组件库」的系统控件（[DesignSystemGuard.CONTROLS]，
     * 直接导入、别名、全限定、星号导入四种写法）时，返回「相对路径:行: [design-system-required] 控件短名 …」，每个调用点一条。
     * 只警告：不进 [check] 的失败列表；扫不完整的文件由 [check] 报，这里跳过。
     */
    fun warnings(
        root: File,
        scanningRepository: Boolean,
    ): List<String> {
        if (!root.isDirectory) return emptyList()
        return sourceFiles(root, scanningRepository).files.flatMap { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (file.extension != "kt" || !DesignSystemGuard.applies(relative)) return@flatMap emptyList()
            val text = runCatching { file.readText() }.getOrNull() ?: return@flatMap emptyList()
            DesignSystemGuard.findings(scanKotlinSource(text).code).map { (line, control) ->
                "$relative:$line: ${DesignSystemGuard.MARKER} $control ${DesignSystemGuard.MESSAGE}"
            }
        }
    }

    /** 遍历结果：源码文件与遍历失败的诊断（列不出内容的目录、属性查不了的条目）。 */
    private class Listing(
        val files: List<File>,
        val failures: List<String>,
    )

    /**
     * 显式遍历（R1-05 / R2-03）：不用 walkTopDown + isFile / isDirectory——那两个查询把 I/O 错误吞成 false，「能列出名字但查不了属性」的条目
     * （目录有读权限、没有执行 / 搜索权限）会被当成「既不是文件也不是目录」静默丢掉。这里：
     * - 列目录用 [Files.newDirectoryStream]（失败抛错）→ 记「目录列不出内容」；
     * - 每个列出的条目都用 [Files.readAttributes] 查属性（失败抛错，跟随符号链接）→ 记「条目属性查不了」，不论扩展名（查不了就无法确认它是不是源码目录）；
     * - 跳过的目录（build、.gradle 等，扫描仓库时的 tools、build-logic）按名字 / 相对路径先排除，不查属性、不算失败；
     * - 扩展名是 .kt / .java 但不是普通文件（设备、管道等）→ 记扫不完整；符号链接环按文件标识只走一次。
     */
    private fun sourceFiles(
        root: File,
        scanningRepository: Boolean,
    ): Listing = SourceWalker(root.toPath(), scanningRepository).run()

    /** [sourceFiles] 的遍历状态（一次扫描一个实例）。 */
    private class SourceWalker(
        private val rootPath: Path,
        private val scanningRepository: Boolean,
    ) {
        private val failures = mutableListOf<String>()
        private val files = mutableListOf<File>()
        private val visited = mutableSetOf<Any>()

        fun run(): Listing {
            val rootAttributes = attributesOf(rootPath)
            if (rootAttributes != null) {
                rootAttributes.fileKey()?.let { visited += it }
                walk(rootPath)
            }
            return Listing(files.sortedBy { it.path }, failures.sorted())
        }

        private fun relative(path: Path): String {
            val text = rootPath.relativize(path).toString()
            return text.replace(File.separatorChar, '/').ifEmpty { "." }
        }

        /** 列目录；失败抛错的地方都记「目录列不出内容」并返回 null。 */
        private fun list(dir: Path): List<Path>? =
            try {
                Files.newDirectoryStream(dir).use { stream -> stream.toList() }
            } catch (error: IOException) {
                failures += "${relative(dir)}（${error.javaClass.simpleName}）: [scan-incomplete] 目录列不出内容"
                null
            } catch (error: DirectoryIteratorException) {
                failures += "${relative(dir)}（${(error.cause ?: error).javaClass.simpleName}）: [scan-incomplete] 目录列不出内容"
                null
            }

        /** 查条目属性（跟随符号链接）；查不了记「条目属性查不了」并返回 null（不论扩展名）。 */
        private fun attributesOf(entry: Path): BasicFileAttributes? =
            try {
                Files.readAttributes(entry, BasicFileAttributes::class.java)
            } catch (error: IOException) {
                failures += "${relative(entry)}（${error.javaClass.simpleName}）: [scan-incomplete] 条目属性查不了（无法确认是不是源码或目录）"
                null
            }

        private fun walk(dir: Path) {
            val entries = list(dir) ?: return
            entries.sortedBy { it.fileName.toString() }.forEach(::visit)
        }

        private fun visit(entry: Path) {
            val name = entry.fileName.toString()
            if (name in SKIPPED_DIRS || (scanningRepository && relative(entry) in SKIPPED_REPOSITORY_DIRS)) return
            val attributes = attributesOf(entry) ?: return
            val source = name.endsWith(".kt") || name.endsWith(".java")
            when {
                attributes.isDirectory -> {
                    val key = attributes.fileKey()
                    if (key == null || visited.add(key)) walk(entry)
                }

                attributes.isRegularFile -> {
                    if (source) files += entry.toFile()
                }

                source -> {
                    failures += "${relative(entry)}: [scan-incomplete] 不是普通文件"
                }
            }
        }
    }

    private const val PRIVACY_GATE_FILE = "PrivacyGate.kt"
    private const val CJK_MESSAGE = "feature 模块的字符串字面量不得含中文，文案经文案字典取键（BR-TEXT-12）"
    private const val SDK_MESSAGE = "第三方 SDK 只能在 PrivacyGate.onConsented() 里初始化（03 §4.1）"
    private const val INCOMPLETE_MESSAGE = "源码规则扫描器无法确认扫完整个文件（F-X-07 fail-closed）"

    private val SKIPPED_DIRS = setOf("build", ".gradle", ".git", ".idea", ".kotlin")

    /** 不受 no-cjk-string-in-feature 约束的测试源码集目录。 */
    private val TEST_SOURCE_DIRS = listOf("/src/test/", "/src/androidTest/")

    // 扫描仓库本身时跳过：违反样例（另行验证必须被拦）与构建逻辑。
    private val SKIPPED_REPOSITORY_DIRS = setOf("tools", "build-logic")

    private val CJK = Regex("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")

    // 03 §3.5 SDK 清单里各 SDK 的初始化入口；新增 SDK 时在这里补。
    private val SDK_INIT_PATTERNS =
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
