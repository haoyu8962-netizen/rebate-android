package com.couli.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LINT-STYLE-android 测试 v4（编排 D10-788：隐私类旧规则扫描范围内不再套 SKIPPED_DIRS）。v1 在 [CouliSourceRulesTest]、v2 在
 * [CouliStyleRulesV2Test]、v3 在 [CouliStyleRulesV3Test]，都一字未动。只走真实入口 [CouliSourceRules.check]。
 *
 * 现状（红的原因）：[CouliSourceRules] 遍历时按名字跳过 SKIPPED_DIRS（build、.gradle、.git、.idea、.kotlin）。LINT-STYLE v2 让页面源码集
 * （feature/<模块>、app 的 src/main、src/release）里的这类目录照走，但其下文件只进 pageOnly、只给样式规则；页面源码集之外（core 模块、
 * staging / debug 等源码集）里的这类目录整棵跳过。于是把违规代码放进源码集里一个叫 build/ 的包目录就能躲过 no-cjk-string-in-feature 与
 * sdk-init-only-in-privacy-gate——而这些文件照样会被编译进包。
 *
 * 本单口径（旧规则的「扫描范围」）：
 * - **源码集内**（路径形如 `<模块路径>/src/<任意源码集>/…`）：目录叫 build / .gradle / .idea / .kotlin 也照走，其下 .kt / .java 交给两条旧规则，
 *   各规则自身的适用条件不变——
 *   - no-cjk-string-in-feature：feature/ 下、.kt、非测试源码集（src/test、src/androidTest 照旧不管）；
 *   - sdk-init-only-in-privacy-gate：除 PrivacyGate.kt 外所有 .kt / .java（core、app、feature 都管）。
 * - **源码集之外**照旧跳过（防过度扩大）：模块根的构建产物（<模块>/build/、<模块>/.kotlin/）、工程根的 build/、.gradle/、.kotlin/；扫描仓库时的
 *   tools/、build-logic/；.git 一律跳过（与样式规则 v2 口径一致）。
 * - 每个违规点只报一次（不能因为文件同时进样式扫描与旧规则扫描而重复报）；源码集内这类目录下扫不完整的文件同样 fail-closed、只报一次。
 * - 样式规则（HardcodedStyleGuard）的范围口径不动。
 */
class CouliStyleRulesV4Test {
    private val root: File = Files.createTempDirectory("lintstyle-v4").toFile()
    private val legal = "feature/home/src/main/kotlin/com/couli/feature/home/Page.kt"
    private val cjkSource = "val title = \"首页\"\n"
    private val sdkKotlin = "fun start(context: Any) {\n    SentryAndroid.init(context)\n}\n"
    private val sdkJava = "class Init {\n    void start(Object context) {\n        MobPush.initSdk(context);\n    }\n}\n"

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(
        relative: String,
        text: String,
    ): File =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private fun prepare() {
        write(legal, "val legal = 1\n")
        write("tools/ci/hardcoded-style-baseline.json", "{}\n")
    }

    /** 每个路径恰好一条 [rule]，结果里没有别的诊断。 */
    private fun assertEachOnce(
        rule: String,
        paths: List<String>,
        result: List<String>,
        context: String,
    ) {
        assertEquals("$context：总数 → $result", paths.size, result.size)
        paths.forEach { path ->
            assertEquals(
                "$context：$path 必须恰好报一次 [$rule] → $result",
                1,
                result.count { it.startsWith("$path:") && "[$rule]" in it },
            )
        }
    }

    // ---------------------------------------------------------------- ① no-cjk-string-in-feature：feature 源码集里的 build/ 等目录不是后门

    @Test
    fun v4CjkInsideSkipNamedDirsWithinFeatureSourceSetsFails() {
        val hidden =
            listOf(
                // 页面源码集（现状：进 pageOnly，只给样式规则）
                "feature/demo/src/main/kotlin/demo/build/Hidden.kt",
                "feature/demo/src/main/kotlin/build/generated/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/.gradle/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/.idea/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/.kotlin/Hidden.kt",
                "feature/demo/src/main/java/demo/build/Hidden.kt",
                "feature/demo/src/release/kotlin/demo/build/Hidden.kt",
                // 非页面、非测试源码集（现状：整棵跳过）——CJK 规则本来就管 feature 的这些源码集
                "feature/demo/src/staging/kotlin/demo/build/Hidden.kt",
                "feature/demo/src/debug/kotlin/demo/.kotlin/Hidden.kt",
            )
        prepare()
        hidden.forEach { write(it, cjkSource) }
        listOf(true, false).forEach { scanningRepository ->
            val result = CouliSourceRules.check(root, scanningRepository)
            assertEachOnce("no-cjk-string-in-feature", hidden, result, "scanningRepository=$scanningRepository")
        }
    }

    // ---------------------------------------------------------------- ② sdk-init-only-in-privacy-gate：任何模块源码集里的 build/ 等目录不是后门

    @Test
    fun v4SdkInitInsideSkipNamedDirsWithinSourceSetsFails() {
        val hiddenKotlin =
            listOf(
                "feature/demo/src/main/kotlin/demo/build/Init.kt",
                "app/src/main/kotlin/com/couli/app/.idea/Init.kt",
                "app/src/debug/kotlin/com/couli/app/.gradle/Init.kt",
                "core/push/src/main/kotlin/com/couli/core/push/build/Init.kt",
                "core/privacy/src/main/kotlin/build/Init.kt",
            )
        val hiddenJava =
            listOf(
                "core/push/src/main/java/com/couli/core/push/.kotlin/Init.java",
                "app/src/main/java/com/couli/app/build/Init.java",
            )
        prepare()
        hiddenKotlin.forEach { write(it, sdkKotlin) }
        hiddenJava.forEach { write(it, sdkJava) }
        listOf(true, false).forEach { scanningRepository ->
            val result = CouliSourceRules.check(root, scanningRepository)
            assertEachOnce("sdk-init-only-in-privacy-gate", hiddenKotlin + hiddenJava, result, "scanningRepository=$scanningRepository")
        }
    }

    // ---------------------------------------------------------------- ③ 扫不完整同样 fail-closed、只报一次

    @Test
    fun v4IncompleteFilesInsideSkipNamedDirsWithinSourceSetsFailOnce() {
        val broken =
            listOf(
                // 页面源码集：现状由样式扫描报一次；改后不得因旧规则再报一次
                "feature/demo/src/main/kotlin/demo/build/Broken.kt",
                // 非页面源码集：现状整棵跳过（没报）；改后旧规则扫到，必须报
                "core/push/src/main/kotlin/com/couli/core/push/build/Broken.kt",
            )
        prepare()
        broken.forEach { write(it, "val broken = \"never closed\n") }
        listOf(true, false).forEach { scanningRepository ->
            val result = CouliSourceRules.check(root, scanningRepository)
            assertEachOnce("scan-incomplete", broken, result, "scanningRepository=$scanningRepository")
        }
    }

    // ---------------------------------------------------------------- ④ 正向保留：源码集之外、测试源码集（CJK）、仓库豁免目录、.git 仍不拦

    @Test
    fun v4OutsideSourceSetsAndTestSourcesStayUnflagged() {
        val both = cjkSource + sdkKotlin
        // 源码集之外的构建产物 / IDE / 缓存目录：照旧整棵跳过
        listOf(
            "feature/demo/build/generated/source/kapt/main/demo/Gen.kt",
            "feature/demo/build/intermediates/src/main/kotlin/demo/Gen.kt",
            "feature/demo/.kotlin/src/main/kotlin/demo/Gen.kt",
            "core/push/build/generated/ksp/main/kotlin/Gen.kt",
            "app/build/generated/ksp/release/kotlin/Gen.kt",
            "app/.gradle/src/main/kotlin/Gen.kt",
            "build/feature/demo/src/main/kotlin/demo/Gen.kt",
            ".gradle/feature/demo/src/main/kotlin/Gen.kt",
            ".kotlin/feature/demo/src/main/kotlin/Gen.kt",
            ".idea/feature/demo/src/main/kotlin/Gen.kt",
            // .git 一律跳过（与样式规则 v2 口径一致）
            "feature/demo/src/main/kotlin/demo/.git/Gen.kt",
        ).forEach { write(it, both) }
        // 测试源码集：CJK 规则本身不管（SDK 规则对测试源码集的口径本单不断言，这里只放 CJK）
        listOf(
            "feature/demo/src/test/kotlin/demo/build/HiddenTest.kt",
            "feature/demo/src/androidTest/kotlin/demo/.kotlin/HiddenTest.kt",
        ).forEach { write(it, cjkSource) }
        // 扫描仓库时的 tools/、build-logic/：照旧跳过
        listOf(
            "tools/ci/lint-fixtures/sample/feature/demo/src/main/kotlin/build/Gen.kt",
            "build-logic/convention/src/main/kotlin/build/Gen.kt",
        ).forEach { write(it, both) }
        prepare()
        assertEquals("源码集之外 / 测试源码集 / 仓库豁免目录不进旧规则", emptyList<String>(), CouliSourceRules.check(root, scanningRepository = true))
    }
}
