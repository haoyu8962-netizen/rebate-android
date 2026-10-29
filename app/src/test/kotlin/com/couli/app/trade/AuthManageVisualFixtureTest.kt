package com.couli.app.trade

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06w fixture 自检（brief §6、gaps G-A13）：三端同一份 specs/auth-manage-visual-vectors.json；键名不含 token / secret / key / sentry / password / auth；
 * 不放授权地址 / state / 凭证 / 账号名样式文本 / 平台中文名；bindings 各组都在、投影与执行器口径一致；画板 6 张都有截图或不对照去向；
 * 截图名单与 AuthManageVisualSnapshotTest 的入口一一对应；画板上每类元素至少一张截图覆盖。
 */
class AuthManageVisualFixtureTest {
    private val data = AuthManageVisualFixtures.data

    @Test fun keysAvoidSecretLikeWords() {
        val bad = mutableListOf<String>()

        fun walk(
            e: Any?,
            path: String,
        ) {
            when (e) {
                is JsonObject -> {
                    e.forEach { (k, v) ->
                        if (FORBIDDEN_KEY.containsMatchIn(k)) bad += "$path/$k"
                        walk(v, "$path/$k")
                    }
                }

                is JsonArray -> {
                    e.forEach { walk(it, path) }
                }

                else -> {
                    Unit
                }
            }
        }
        walk(data, "")
        assertEquals("键名不含 token / secret / key / sentry / password / auth（gitleaks）", emptyList<String>(), bad)
    }

    @Test fun noUrlsStatesCredentialsOrChinesePlatformNames() {
        val text = AuthManageVisualFixtures.file.readText()
        listOf("http", "auth_url", "\"state\"", "access_token", "\"code\": \"", "淘宝", "拼多多").forEach {
            assertTrue("fixture 不放「$it」", !text.contains(it))
        }
    }

    @Test fun bindingGroupsAreCompleteAndProjectionsMatchTheExecutor() {
        val groups = data.getValue("bindings").jsonObject.keys
        assertEquals(
            setOf(
                "normal",
                "blocked",
                "all_active",
                "both_unbound",
                "taobao_unbound",
                "both_blocked",
                "pdd_released",
                "pdd_invalid",
                "pdd_pending",
                "with_jd",
                "empty",
            ),
            groups,
        )
        assertEquals("每组都有投影", groups, data.getValue("projection").jsonObject.keys)
        groups.forEach { g ->
            val statuses =
                AuthManageVisualFixtures
                    .bindings(g)
                    .associate {
                        it.jsonObject
                            .getValue("platform")
                            .jsonPrimitive.content to
                            it.jsonObject
                                .getValue("status")
                                .jsonPrimitive.content
                    }
            AuthManageVisualFixtures.projection(g).forEach { (p, want) ->
                val s = statuses[p]
                val got =
                    when (s) {
                        "active" -> "authorized"
                        "blocked" -> "blocked"
                        else -> "can_grant"
                    }
                assertEquals("$g/$p 投影（active 已授权；blocked 停用；其余 / 没返回 → 未授权 + 去授权）", got, want)
            }
            assertEquals("$g 只投影淘宝、拼多多", setOf("taobao", "pdd"), AuthManageVisualFixtures.projection(g).keys)
        }
    }

    @Test fun boardsHaveAShotOrANotComparedReason() {
        val shots = AuthManageVisualFixtures.snapshots()
        val notCompared =
            data.getValue("notCompared").jsonArray.map {
                it.jsonObject
                    .getValue("target")
                    .jsonPrimitive.content
            }
        val boards = data.getValue("boards").jsonArray.map { it.jsonObject }
        assertEquals("画板 6 张（授权管理 4 + 通用状态 2）", 6, boards.size)
        boards.forEach { b ->
            val name = b.getValue("name").jsonPrimitive.content
            assertTrue("$name 有截图或不对照去向", shots.any { it.startsWith(name) } || name in notCompared)
            assertTrue("$name sha12", Regex("^[0-9a-f]{12}$").matches(b.getValue("sha12").jsonPrimitive.content))
        }
    }

    @Test fun snapshotListMatchesTheSnapshotEntries() {
        val shots = AuthManageVisualFixtures.snapshots()
        assertEquals("名单不重复", shots.size, shots.toSet().size)
        assertEquals("34 张", 34, shots.size)
        val source =
            File(
                AuthManageVisualFixtures.root,
                "app/src/test/kotlin/com/couli/app/trade/AuthManageVisualSnapshotTest.kt",
            ).readText()
        val inSource = Regex(""""([A-Za-z][A-Za-z0-9-]*-(?:light|dark))"""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals("截图入口与 fixture 名单一一对应", shots.toSet(), inSource)
        assertTrue(
            "命名照 risk-v / account-v：画板名或 auth-manage-… + -light / -dark",
            shots.all { it.endsWith("-light") || it.endsWith("-dark") },
        )
    }

    @Test fun everyBoardElementIsCoveredByAShot() {
        val shots = AuthManageVisualFixtures.snapshots().toSet()
        val elements = data.getValue("boardElements").jsonArray.map { it.jsonObject }
        assertTrue("至少 11 类元素（平台图标两种、圆勾、未授权 + 主按钮、停用块 + 描边按钮、分隔线、底部说明、轻提示 + 三角…）", elements.size >= 11)
        elements.forEach { e ->
            val list = e.getValue("shots").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("${e.getValue("element")} 有覆盖截图：$list", list.isNotEmpty() && list.all { it in shots })
        }
    }

    @Test fun codesCarryTheWordingsTheExecutorUses() {
        val wordings =
            mapOf(
                "c30101" to "error.30101.auth_unavailable",
                "c30102" to "error.30102.auth_unavailable",
                "c30151" to "error.30151",
                "c30153" to "error.30153",
                "c42901" to "error.42901",
                "c50001" to "error.50001",
                "c30104_used_up" to "error.30104.credential_invalid",
                "offline" to "buy.timeout",
            )
        wordings.forEach { (c, w) -> assertEquals(c, w, AuthManageVisualFixtures.wording(c)) }
        assertEquals("trace6 = trace_id 末 6 位", AuthManageVisualFixtures.traceId.takeLast(6), AuthManageVisualFixtures.trace6)
        assertEquals("Retry-After 缺省样例 5 秒", 5_000L, AuthManageVisualFixtures.retryAfterMillis)
    }

    private companion object {
        val FORBIDDEN_KEY = Regex("token|secret|key|sentry|password|auth", RegexOption.IGNORE_CASE)
    }
}
