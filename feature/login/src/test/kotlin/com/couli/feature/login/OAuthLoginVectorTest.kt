/*
 * 实现前测试；只读输出，未编译运行。复用已有 JUnit4 / serialization。
 *
 * 接线约定：
 * - 以下六个类型在 com.couli.feature.login 中可见。
 * - OAuthAttemptSession()：
 *   create(): String；launch(attempt: String)；
 *   callback(attempt: String, seq: Int)；cancel()/leave()/timeout()；
 *   submit(attempt: String)。
 *   callback 只接收凭证，submit 是唯一提交边界；均为同步纯逻辑。
 *   submitted: List<String>；dropped: List<{attempt: String, seq: Int}>。
 *   dropped 只记录被拒 callback；重复/失效 submit 不追加 submitted。
 *   a/b 是用例别名，绑定 create 返回值；seq 只是回调观测编号，
 *   不代表 nonce、时间或跨 attempt 的先后。
 * - OAuthLoginRoute.map(provider: String, response: JsonObject)
 *   -> {routeRaw: String, saveSession: Boolean, firstPurchaseTip: Boolean?}。
 *   response 为完整外壳；只测 full、默认开启引导的候选分流。
 *   saveSession 是意图，原子保存与最终路由守卫另验。
 * - OAuthLoginErrorAction.map(provider: String, code: Int, data: JsonObject)
 *   -> {actionRaw: String, textKey: String?, showTrace6: Boolean?}。
 *   接已有 ErrorActionMapper；reauthorize 由用户重新取尝试并授权；
 *   provider_unavailable 保留其他方式；二者不自动重试。
 * - ThirdPartyButtons.wechatVisible(platform: String, installed: Boolean,
 *   adapterAvailable: Boolean): Boolean；
 *   ThirdPartyButtons.appleVisible(platform: String): Boolean。
 *   adapterAvailable 表示已允许启用；正式微信实现为 false，
 *   true 行用于 Debug 假适配器/能力已获准的纯逻辑。
 * - BindPhoneAction.map(responseOrError: JsonObject)
 *   -> {actionRaw: String, textKey: String?}。
 *   输入为本地包装 {operation, purpose?, body, headers?}；
 *   body 才是契约响应，purpose 为原请求上下文。
 *   retry_after 表示等待/交倒计时组件；发码成功不等于绑定完成。
 *   merge_confirm 只提示确认；done 后刷新 getMe 的执行另验。
 * - MergeDecision.apply(decision: String, response: JsonObject?)
 *   -> {resultRaw: String, saveSession: Boolean}。
 *   cancelled=请求前取消；rejected=确认后收到 30411。
 *   并号成功 schema 缺失，见 gaps，不使用 LoginResponse 冒充。
 * - raw 值是测试接线名，不是新增契约路由。
 * - expect 缺字段不判定；显式 null 要求无值。
 * - 每行独立状态，无 SDK、网络、真实时钟、令牌解码或持久化。
 */
package com.couli.feature.login

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthLoginVectorTest {
    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("缺少 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")
        Json.parseToJsonElement(
            File(root, "specs/oauth-login-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.oauthInt("version"))
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
    }

    @Test
    fun attemptVectors() {
        each("attempt") { row, expected, label ->
            val session = OAuthAttemptSession()
            val handles = mutableMapOf<String, String>()
            val steps = row.getValue("steps").jsonArray
            assertTrue("$label: 步骤不能为空", steps.isNotEmpty())

            steps.forEachIndexed { index, element ->
                val step = element.jsonObject
                val op = step.oauthString("op")
                val context = "$label.steps[$index].$op"

                when (op) {
                    "create" -> {
                        val alias = step.oauthString("attempt")
                        assertFalse("$context: 别名重复", handles.containsKey(alias))
                        val handle = session.create()
                        assertTrue(context, handle.isNotEmpty())
                        assertFalse(
                            "$context: 新尝试不能复用旧句柄",
                            handles.containsValue(handle),
                        )
                        handles[alias] = handle
                    }
                    "launch" -> session.launch(handles.getValue(step.oauthString("attempt")))
                    "callback" ->
                        session.callback(
                            handles.getValue(step.oauthString("attempt")),
                            step.oauthInt("seq"),
                        )
                    "submit" -> session.submit(handles.getValue(step.oauthString("attempt")))
                    "cancel" -> session.cancel()
                    "leave" -> session.leave()
                    "timeout" -> session.timeout()
                    else -> throw AssertionError("$context: 未知操作")
                }
            }

            val submitted =
                expected.getValue("submitted").jsonArray.map {
                    handles.getValue(it.jsonPrimitive.content)
                }
            assertEquals(
                "$label.submitted：内容、次数、顺序",
                submitted,
                session.submitted,
            )

            val dropped = expected.getValue("dropped").jsonArray
            assertEquals("$label.dropped.count", dropped.size, session.dropped.size)
            session.dropped.zip(dropped).forEachIndexed { index, (actual, element) ->
                val wanted = element.jsonObject
                assertEquals(
                    "$label.dropped[$index].attempt",
                    handles.getValue(wanted.oauthString("attempt")),
                    actual.attempt,
                )
                assertEquals("$label.dropped[$index].seq", wanted.oauthInt("seq"), actual.seq)
            }
        }
    }

    @Test
    fun loginResultVectors() {
        each("loginResult") { row, expected, label ->
            val actual =
                OAuthLoginRoute.map(
                    row.oauthString("provider"),
                    row.oauthObject("response"),
                )
            assertEquals("$label.routeRaw", expected.oauthString("routeRaw"), actual.routeRaw)
            assertEquals("$label.saveSession", expected.oauthBool("saveSession"), actual.saveSession)
            assertOptionalBoolean(expected, "firstPurchaseTip", actual.firstPurchaseTip, label)
        }
    }

    @Test
    fun loginErrorVectors() {
        each("loginError") { row, expected, label ->
            val actual =
                OAuthLoginErrorAction.map(
                    row.oauthString("provider"),
                    row.oauthInt("code"),
                    row["data"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            assertEquals("$label.actionRaw", expected.oauthString("actionRaw"), actual.actionRaw)
            assertOptionalText(expected, "textKey", actual.textKey, label)
            assertOptionalBoolean(expected, "showTrace6", actual.showTrace6, label)
        }
    }

    @Test
    fun wechatButtonVectors() {
        each("wechatButton") { row, expected, label ->
            assertEquals(
                "$label.visible",
                expected.oauthBool("visible"),
                ThirdPartyButtons.wechatVisible(
                    row.oauthString("platform"),
                    row.oauthBool("installed"),
                    row.oauthBool("adapterAvailable"),
                ),
            )
        }
    }

    @Test
    fun appleButtonVectors() {
        each("appleButton") { row, expected, label ->
            assertEquals(
                "$label.visible",
                expected.oauthBool("visible"),
                ThirdPartyButtons.appleVisible(row.oauthString("platform")),
            )
        }
    }

    @Test
    fun bindPhoneVectors() {
        each("bindPhone") { row, expected, label ->
            val hasResponse = row.containsKey("response")
            val hasError = row.containsKey("error")
            assertTrue("$label: response/error 必须且只能有一个", hasResponse != hasError)
            val actual =
                BindPhoneAction.map(
                    row.oauthObject(if (hasResponse) "response" else "error"),
                )
            assertEquals("$label.actionRaw", expected.oauthString("actionRaw"), actual.actionRaw)
            assertOptionalText(expected, "textKey", actual.textKey, label)
        }
    }

    @Test
    fun mergeVectors() {
        each("merge") { row, expected, label ->
            val actual =
                MergeDecision.apply(
                    row.oauthString("decision"),
                    row["response"]?.jsonObject,
                )
            assertEquals("$label.resultRaw", expected.oauthString("resultRaw"), actual.resultRaw)
            assertEquals("$label.saveSession", expected.oauthBool("saveSession"), actual.saveSession)
        }
    }

    private fun each(
        group: String,
        body: (JsonObject, JsonObject, String) -> Unit,
    ) {
        val rows = vectors.getValue(group).jsonArray
        assertTrue("$group: 向量不能为空", rows.isNotEmpty())
        val names = mutableSetOf<String>()
        for (element in rows) {
            val row = element.jsonObject
            val name = row.oauthString("name")
            val label = "$group.$name"
            assertTrue("$label: 名称不能为空", name.isNotEmpty())
            assertTrue("$label: 名称重复", names.add(name))
            body(row, row.oauthObject("expect"), label)
        }
    }

    private fun assertOptionalText(
        expected: JsonObject,
        key: String,
        actual: String?,
        label: String,
    ) {
        if (!expected.containsKey(key)) return
        val raw = expected.getValue(key)
        val wanted = if (raw == JsonNull) null else raw.jsonPrimitive.content
        assertEquals("$label.$key", wanted, actual)
    }

    private fun assertOptionalBoolean(
        expected: JsonObject,
        key: String,
        actual: Boolean?,
        label: String,
    ) {
        if (!expected.containsKey(key)) return
        val raw = expected.getValue(key)
        val wanted = if (raw == JsonNull) null else raw.jsonPrimitive.boolean
        assertEquals("$label.$key", wanted, actual)
    }
}

private fun JsonObject.oauthString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.oauthInt(key: String): Int = getValue(key).jsonPrimitive.int

private fun JsonObject.oauthBool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

private fun JsonObject.oauthObject(key: String): JsonObject = getValue(key).jsonObject
