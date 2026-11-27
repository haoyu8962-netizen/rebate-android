package com.couli.app.trade

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-A 测试：回跳 code、明文 state 不进日志、不落盘（设计稿 §8、§5.5 第 5 条；BR-ID-17「不落库、不进日志」）。源码扫描：
 * 1. web_code 相关新文件里没有 Log / println / Timber / printStackTrace / System.out|err；
 * 2. 经手 code 或明文 state 的文件（解析器、收件箱、真实授权器、入站中转）不碰 SharedPreferences、文件、DataStore、SavedStateHandle、
 *    保存状态，也不往 Intent 里放 extras（落盘只有 SharedPreferencesWebCodeAttempts，且只存哈希，行为见 WebCodeAttemptStoreTest）；
 * 3. 入站中转只读 data，不读 extras；不把回跳交给 AppRouting / RoutingTrace；
 * 4. 全仓库正式源码不出现 mayLaunchUrl（预取会提前发出带 state 的地址，设计稿 §4.2）与 HttpLoggingInterceptor（请求体含 code）。
 * 实现前在接口桩上是绿的（守卫型用例），扫描范围先由 s00 钉住，防止改名后扫空。
 */
class WebCodeNoLeakSourceScanTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private fun kt(dir: String): List<File> =
        File(root, dir)
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            .orEmpty()

    /** 去掉块注释与行注释（不去字符串）。 */
    private fun code(file: File) =
        file
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private val featureFiles: List<File>
        get() = kt(FEATURE_TRADE).filter { it.name.startsWith("WebCode") }

    private val inboundFiles: List<File>
        get() = kt(INBOUND)

    private val appTradeFiles: List<File>
        get() = kt(APP_TRADE).filter { Regex("""(?i)webcode|authbrowser|authreturn""").containsMatchIn(it.name) }

    /** 新增的 web_code 相关正式源码。 */
    private val scanned: List<File>
        get() = featureFiles + inboundFiles + appTradeFiles

    /** 经手 code / 明文 state 的文件（不许有任何落盘途径）。 */
    private val carriers: List<File>
        get() =
            featureFiles.filter { it.name in setOf("WebCodeCallback.kt", "WebCodeInbox.kt") } +
                inboundFiles +
                appTradeFiles.filter { it.name.startsWith("BrowserWebCodeAuthorizer") }

    private fun offenders(
        files: List<File>,
        pattern: Regex,
    ): List<String> =
        files.flatMap { file ->
            pattern.findAll(code(file)).map { "${file.relativeTo(root)}: ${it.value}" }.toList()
        }

    @Test
    fun s00_scanScopeCoversTheNewFiles() {
        val names = scanned.map { it.name }.toSet()
        listOf(
            "WebCodeCallback.kt",
            "WebCodeInbox.kt",
            "WebCodeAttempts.kt",
            "BrowserWebCodeAuthorizer.kt",
            "AuthBrowserLauncher.kt",
            "SharedPreferencesWebCodeAttempts.kt",
            "InboundSchemeActivity.kt",
        ).forEach { assertTrue("扫描范围要含 $it（改名请同步本测试）：$names", it in names) }
        assertTrue("经手 code 的文件 4 个以上（解析器、收件箱、授权器、中转）：${carriers.map { it.name }}", carriers.size >= 4)
    }

    @Test
    fun s01_noLoggingInWebCodeSources() {
        val hits = offenders(scanned, Regex("""\bLog\.[a-z]+\(|\bprintln\(|\bprint\(|\bTimber\b|printStackTrace|System\.(out|err)"""))
        assertEquals("web_code 新文件不打日志（code / state 不进日志）", emptyList<String>(), hits)
    }

    @Test
    fun s02_codeCarriersNeverPersist() {
        val hits =
            offenders(
                carriers,
                Regex(
                    """SharedPreferences|getSharedPreferences|putString\(|\.edit\(|openFileOutput|FileOutputStream|FileWriter|""" +
                        """\.writeText\(|\.writeBytes\(|DataStore|SavedStateHandle|onSaveInstanceState|putExtra\(|putExtras\(""",
                ),
            )
        assertEquals("经手 code / 明文 state 的文件不落盘、不放进 Intent extras / 保存状态", emptyList<String>(), hits)
    }

    @Test
    fun s03_inboundActivityReadsOnlyData() {
        val hits =
            offenders(
                inboundFiles,
                Regex("""get\w*Extra\(|\.extras\b|getExtras\(|AppRouting|onLaunchIntent|RoutingTrace"""),
            )
        assertEquals("入站中转只读 data、不读 extras；不把回跳交给路由 / 路由轨迹", emptyList<String>(), hits)
    }

    @Test
    fun s04_noPrefetchAndNoBodyLoggingAnywhere() {
        val main =
            root
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", ".kotlin", "test", "testDebug", "androidTest") }
                .filter { it.isFile && it.extension == "kt" && "/src/" in it.path.replace(File.separatorChar, '/') }
                .toList()
        val hits = offenders(main, Regex("""mayLaunchUrl|HttpLoggingInterceptor"""))
        assertEquals("正式源码不预取授权页、不记请求体", emptyList<String>(), hits)
    }

    private companion object {
        const val FEATURE_TRADE = "feature/trade/src/main/kotlin/com/couli/feature/trade"
        const val INBOUND = "app/src/main/kotlin/com/couli/app/inbound"
        const val APP_TRADE = "app/src/main/kotlin/com/couli/app/trade"
    }
}
