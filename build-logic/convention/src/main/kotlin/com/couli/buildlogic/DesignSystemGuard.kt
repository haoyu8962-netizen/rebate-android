package com.couli.buildlogic

import java.io.File

/**
 * DS-02 G1（组件清单 §6）：Feature 模块不得直接调用基础系统控件，必须经组件库（core/designsystem）。
 * 推行顺序：① 现在只警告并生成现有违规基线（tools/ci/design-system-required-baseline.txt）；② 页面逐个替换，基线只减不增；
 * ③ 某控件的基线清零后改为失败。只看 feature 模块的非测试源码；组件库、App、其他 core 模块与测试源码不管。
 */
internal object DesignSystemGuard {
    const val MARKER = "[design-system-required]"
    const val MESSAGE = "：Feature 不直接调系统控件，改用组件库（组件清单 §6 G1，当前只警告）"
    private const val BASELINE_PATH = "tools/ci/design-system-required-baseline.txt"

    /** 必须经组件库的系统控件（全限定名）。 */
    val CONTROLS: List<String> =
        listOf(
            "androidx.compose.material3.Checkbox",
            "androidx.compose.material3.Switch",
            "androidx.compose.material3.RadioButton",
            "androidx.compose.material3.TextField",
            "androidx.compose.material3.OutlinedTextField",
            "androidx.compose.material3.AlertDialog",
            "androidx.compose.material3.ModalBottomSheet",
            "androidx.compose.material3.TopAppBar",
            "androidx.compose.material3.CenterAlignedTopAppBar",
            "androidx.compose.material3.NavigationBar",
            "androidx.compose.material3.FilterChip",
            "androidx.compose.material3.SegmentedButton",
            "androidx.compose.foundation.text.BasicTextField",
        )

    private val IMPORT = Regex("""(?m)^[ \t]*import[ \t]+(\w+(?:\.\w+)*(?:\.\*)?)(?:[ \t]+as[ \t]+(\w+))?""")
    private val DECLARATION = Regex("""\b(?:fun|class|object|interface|val|var|typealias)\s+$""")

    /** 相对仓库根（或扫描根）的路径是否归 G1 管：feature 模块、非测试源码集。 */
    fun applies(relative: String): Boolean {
        if (!relative.startsWith("feature/")) return false
        val sourceSet = relative.substringAfter("/src/", missingDelimiterValue = "").substringBefore('/')
        return sourceSet.isNotEmpty() && !sourceSet.startsWith("test") && !sourceSet.startsWith("androidTest")
    }

    /** 在 [code]（注释、字符串已替换为空格的源码）里找调用点：返回（行号，控件短名），按出现顺序。 */
    fun findings(code: String): List<Pair<Int, String>> {
        val imports = IMPORT.findAll(code).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val importLines = IMPORT.findAll(code).map { it.range }.toList()
        return CONTROLS
            .flatMap { fqn -> callOffsets(code, fqn, imports, importLines).map { it to fqn.substringAfterLast('.') } }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (offset, short) -> code.substring(0, offset).count { it == '\n' } + 1 to short }
    }

    /** 一个控件在源码里的调用偏移：直接 / 别名 / 星号导入后的短名调用，以及全限定调用；跳过导入行与同名声明。 */
    private fun callOffsets(
        code: String,
        fqn: String,
        imports: List<Pair<String, String>>,
        importLines: List<IntRange>,
    ): List<Int> {
        val short = fqn.substringAfterLast('.')
        val pkg = fqn.substringBeforeLast('.')
        val localNames =
            imports
                .mapNotNull { (path, alias) ->
                    when (path) {
                        fqn -> alias.ifEmpty { short }
                        "$pkg.*" -> short
                        else -> null
                    }
                }.toSet()
        val patterns =
            localNames.map { Regex("""(?<![\w.$])${Regex.escape(it)}\s*[({]""") } +
                Regex("""(?<![\w$])${Regex.escape(fqn)}\s*[({]""")
        return patterns
            .flatMap { it.findAll(code).toList() }
            .map { it.range.first }
            .filter { offset -> importLines.none { offset in it } }
            .filterNot { offset -> DECLARATION.containsMatchIn(code.substring(0, offset).takeLast(DECLARATION_WINDOW)) }
    }

    /** 现有违规基线（`路径|控件短名`，重复行表示多个调用点）。 */
    fun baseline(repositoryRoot: File): List<String> =
        File(repositoryRoot, BASELINE_PATH)
            .takeIf { it.isFile }
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }
            .orEmpty()

    /** 给警告标上「在基线内 / 新增」：基线只减不增，新增的在代码评审里拦。 */
    fun annotate(
        warnings: List<String>,
        baseline: List<String>,
    ): List<String> {
        val remaining = baseline.groupingBy { it }.eachCount().toMutableMap()
        return warnings.map { warning ->
            val key = warning.substringBefore(':') + "|" + warning.substringAfter("$MARKER ").substringBefore(' ')
            val left = remaining[key] ?: 0
            if (left > 0) {
                remaining[key] = left - 1
                "$warning（已在现有违规基线）"
            } else {
                "$warning（新增，不在基线）"
            }
        }
    }

    private const val DECLARATION_WINDOW = 24
}
