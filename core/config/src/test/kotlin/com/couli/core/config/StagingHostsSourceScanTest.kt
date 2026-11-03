package com.couli.core.config

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-X-STAGING-HOSTS-android 源码扫描（03 §3.6、AGENTS.md 硬规则 6；brief-android §1「不进 main 源码集、不进 release」）：
 * - X1：任何模块的 src/main、src/release（含 core/generated 的生成物与资源）都不含 staging 地址（`-stg.couliapp.cn`、旧占位 `staging.couli.invalid`）；
 * - X2：仓库里写了 `-stg.couliapp.cn` 的源码文件只能在 src/debugFeatures、src/staging 或测试源码集里；
 * - X3：App 的各份 AndroidManifest 不手写 http / https 入站 intent-filter、不写 staging 主机（CSB-11：入站声明只由 apps.json
 *   生成，快照 inbound 与 own_link_hosts 都为空；l-stg 深链主机只进 debugFeatures 的解析配置，见 handback §1）。
 * 制品层面（release APK 不含 `-stg.couliapp.cn`）由 tools/ci/release-scan.sh 与其 --self-test 守，见 tools/ci/tests/test_release_scan_staging_hosts.py。
 */
class StagingHostsSourceScanTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private val stagingAddress = Regex("""-stg\.couliapp\.cn|staging\.couli\.invalid""", RegexOption.IGNORE_CASE)
    private val realStagingHost = Regex("""-stg\.couliapp\.cn""", RegexOption.IGNORE_CASE)
    private val textExtensions =
        setOf("kt", "kts", "java", "xml", "json", "properties", "pro", "txt", "yaml", "yml", "toml", "html", "js", "gradle")
    private val skippedDirs = setOf("build", ".gradle", ".git", ".idea", "node_modules", ".kotlin")

    /** 仓库里全部文本源文件（相对路径用 `/`）。 */
    private val sources: List<Pair<String, File>> by lazy {
        root
            .walkTopDown()
            .onEnter { it == root || it.name !in skippedDirs }
            .filter { it.isFile && it.extension.lowercase() in textExtensions }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .toList()
    }

    /** 路径里 `src/<源码集>/` 的源码集名；不在 src 下返回 null。 */
    private fun sourceSet(path: String): String? = Regex("""(?:^|/)src/([^/]+)/""").find(path)?.groupValues?.get(1)

    private val testSourceSets = Regex("""^(test|androidTest)([A-Z].*)?$""")

    @Test fun x00ScanSeesTheRepository() {
        assertTrue("扫描范围不得为空", sources.size > 100)
        assertTrue(
            "扫描范围必须包含 main 与 debugFeatures 源码",
            sources.any { it.first == "core/config/src/main/kotlin/com/couli/core/config/AppEnvironment.kt" } &&
                sources.any { it.first == "core/config/src/debugFeatures/kotlin/com/couli/core/config/EnvironmentEndpointsCatalog.kt" },
        )
    }

    @Test fun x01MainAndReleaseSourceSetsHaveNoStagingAddress() {
        val hits =
            sources
                .filter { sourceSet(it.first) in setOf("main", "release") }
                .filter { stagingAddress.containsMatchIn(it.second.readText()) }
                .map { it.first }
        assertTrue("main / release 源码集出现 staging 地址：$hits", hits.isEmpty())
    }

    @Test fun x02RealStagingHostsOnlyLiveInDebugFeaturesStagingOrTests() {
        val hits =
            sources
                .filter { realStagingHost.containsMatchIn(it.second.readText()) }
                .map { it.first }
                .filter { path ->
                    val set = sourceSet(path)
                    set != null && set != "debugFeatures" && set != "staging" && !testSourceSets.matches(set)
                }
        assertTrue("staging 主机只许写在 src/debugFeatures、src/staging 或测试源码集：$hits", hits.isEmpty())
    }

    @Test fun x03ManifestsDeclareNoHandWrittenWebLinksOrStagingHosts() {
        val manifests = sources.filter { it.first.startsWith("app/src/") && it.second.name == "AndroidManifest.xml" }
        assertTrue("应扫到 app 的 main / debug / staging 清单", manifests.size >= 3)
        val webScheme = Regex("""android:scheme\s*=\s*"https?"""")
        val hits =
            manifests
                .filter { (_, file) -> file.readText().let { webScheme.containsMatchIn(it) || it.contains("couliapp.") } }
                .map { it.first }
        assertTrue("CSB-11：清单不得手写 http(s) 入站声明或 couliapp 主机：$hits", hits.isEmpty())
    }
}
