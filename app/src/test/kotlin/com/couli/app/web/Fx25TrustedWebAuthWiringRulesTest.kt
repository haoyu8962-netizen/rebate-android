package com.couli.app.web

import com.couli.core.network.NetworkHooks
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-X-25 v2 方案的 App 接线（源码规则；行为由 core:bridge Fx25Auth* 在协调器层证明）：
 * - S0-02 / I5（变异 14）：退出登录、注销成功处直接 sessionCleared("logout" / "account_deleted")，不靠快照；
 * - S1-01（变异 15）：网络层有「保存新会话之前」钩子 beforeSessionSave，ApiClient 在写新会话前调用，App 把它接到协调器。
 */
class Fx25TrustedWebAuthWiringRulesTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private fun sources(relative: String): List<File> =
        File(root, relative)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun code(file: File): String =
        file
            .readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun i5LogoutAndAccountDeletionClearTrustedWebSessionWithoutReadingCredentials() {
        val app = sources("app/src/main").map(::code)
        val constants =
            app
                .flatMap { Regex("""const val (\w+)\s*=\s*"([^"]*)"""").findAll(it).toList() }
                .associate { it.groupValues[1] to it.groupValues[2] }
        val reasons =
            app
                .flatMap { Regex("""\bsessionCleared\(\s*([^)]*?)\s*\)""").findAll(it).toList() }
                .map { it.groupValues[1] }
                .map { argument ->
                    argument.removeSurrounding("\"").takeIf { argument.startsWith("\"") }
                        ?: constants[argument.substringAfterLast('.')]
                }.toSet()
        assertTrue("退出登录直接登记待清（I5，S0-02）：$reasons", "logout" in reasons)
        assertTrue("注销成功直接登记待清（I5，S0-02）：$reasons", "account_deleted" in reasons)
    }

    @Test
    fun i3NetworkLayerOffersBeforeSessionSaveAndAppWiresItToTheCoordinator() {
        assertTrue(
            "NetworkHooks 必须有 beforeSessionSave（保存新会话之前，先写待清标记）",
            runCatching { NetworkHooks::class.java.getMethod("getBeforeSessionSave") }.isSuccess,
        )
        val api = code(File(root, "core/network/src/main/kotlin/com/couli/core/network/ApiClient.kt"))
        assertTrue("ApiClient 在写新会话前调 beforeSessionSave", Regex("""\bbeforeSessionSave\(""").containsMatchIn(api))
        val wiring = sources("app/src/main").map(::code).filter { Regex("""\bbeforeSessionSave\s*=""").containsMatchIn(it) }
        assertTrue("App 把 beforeSessionSave 接到可信 Web 协调器", wiring.isNotEmpty())
    }
}
