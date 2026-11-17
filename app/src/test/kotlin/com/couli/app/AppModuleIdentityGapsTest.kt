package com.couli.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务书第 4 处（FU-AND-APPMODULE-WIRING-TEST）；03 §4.2 第五条「调用方声明这次请求属于某个账号」和会话世代核对。
 * 沿用 ClipDefaultOnSourceGuardTest 的根目录查找、去注释和局部源码判据；不固定整文件或路径哈希。
 * 只钉 AppTrade / AppClipboard 两处装配的 sessionAccount，以及 AppClipboard 的 sessionEpoch。
 */
class AppModuleIdentityGapsTest {
    private val source: String by lazy {
        val root =
            generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        File(root, "app/src/main/kotlin/com/couli/app/AppModule.kt").readText()
    }

    // 第 4 处 / 第五条：购买请求账号来源必须接当前真实会话，不能退回 null。
    @Test
    fun appTradeAccountComesFromTheClientSession() {
        assertTrue(
            "appTrade → AppTrade.sessionAccount 应接 { client.activeUserId() }",
            hasWiring(source, "appTrade", "AppTrade", "sessionAccount", "client.activeUserId()"),
        )
    }

    // 第 4 处 / 第五条：识别请求账号来源必须接当前真实会话，不能退回 null。
    @Test
    fun appClipboardAccountComesFromTheClientSession() {
        assertTrue(
            "appClipboard → AppClipboard.sessionAccount 应接 { client.activeUserId() }",
            hasWiring(source, "appClipboard", "AppClipboard", "sessionAccount", "client.activeUserId()"),
        )
    }

    // 第 4 处 / 第五条：识别的会话世代必须接 tokens.loginEpoch，不能退回 null。
    @Test
    fun appClipboardEpochComesFromTheTokenStore() {
        assertTrue(
            "appClipboard → AppClipboard.sessionEpoch 应接 { tokens.loginEpoch }",
            hasWiring(source, "appClipboard", "AppClipboard", "sessionEpoch", "tokens.loginEpoch"),
        )
    }

    // 第 4 处 / 同上：只在内存把各处接线改成 null，证明另一处相同表达式与注释不能替它过关。
    @Test
    fun nullWiringMutationsAreRejectedIndependently() {
        assertNullMutationRejected("appTrade", "AppTrade", "sessionAccount", "client.activeUserId()")
        assertNullMutationRejected("appClipboard", "AppClipboard", "sessionAccount", "client.activeUserId()")
        assertNullMutationRejected("appClipboard", "AppClipboard", "sessionEpoch", "tokens.loginEpoch")
    }

    private fun assertNullMutationRejected(
        provider: String,
        constructor: String,
        parameter: String,
        expression: String,
    ) {
        val args = arguments(source, provider, constructor)
        val old = "$parameter={$expression}"
        assertEquals("装配参数必须唯一：$provider.$parameter", 1, args.count { it == old })
        val changed = args.joinToString(",") { if (it == old) "$parameter={null} /* $old */" else it }
        val mutation = "fun $provider(): $constructor = $constructor($changed)"
        assertFalse("{ null } 必须使守卫变红：$provider.$parameter", hasWiring(mutation, provider, constructor, parameter, expression))
    }

    private fun hasWiring(
        text: String,
        provider: String,
        constructor: String,
        parameter: String,
        expression: String,
    ): Boolean = arguments(text, provider, constructor).singleOrNull { it.startsWith("$parameter=") } == "$parameter={$expression}"

    /** 定位 provider 中构造器的实参；按括号 / 花括号深度切顶层逗号，避免把嵌套 lambda 里的同名参数算进来。 */
    private fun arguments(
        raw: String,
        provider: String,
        constructor: String,
    ): List<String> {
        val clean = raw.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)//[^\\n]*"), "")
        val start = checkNotNull(Regex("\\bfun\\s+$provider\\s*\\(").find(clean)).range.first
        val next = Regex("\\bfun\\s+\\w+\\s*\\(").find(clean, start + 4)?.range?.first ?: clean.length
        val body = clean.substring(start, next)
        val call = checkNotNull(Regex("\\b$constructor\\s*\\(").find(body)).range.last + 1
        return splitArguments(body.substring(call), provider, constructor)
    }

    private fun splitArguments(
        tail: String,
        provider: String,
        constructor: String,
    ): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in tail) {
            when {
                ch == ')' && depth == 0 -> {
                    if (current.isNotBlank()) result += current.toString().replace(Regex("\\s+"), "")
                    return result
                }

                ch == ',' && depth == 0 -> {
                    result += current.toString().replace(Regex("\\s+"), "")
                    current.clear()
                }

                else -> {
                    depth += bracketDepthChange(ch)
                    current.append(ch)
                }
            }
        }
        error("没有闭合的构造器实参：$provider / $constructor")
    }

    private fun bracketDepthChange(ch: Char): Int =
        when (ch) {
            '(', '{', '[' -> 1
            ')', '}', ']' -> -1
            else -> 0
        }
}
