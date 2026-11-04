package com.couli.core.network

import com.couli.core.generated.ApiOperations
import com.couli.generated.DefaultTexts
import com.couli.generated.ErrorCodes
import com.couli.generated.RetryKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-01（contract.lock 9cd1e6f → 40cfc268）：升版后网络层的一致性。
 * - 10008 / 10009（只出现在 /admin/v1，进了错误码表、用户侧字典没有文案键）与表外 1xxxx 同一回落（服务端 msg），不写专门分流；
 * - 30102 / 30111 带新 reason sharer_auth_invalid：Mapper 读生成表的 retry_kind_by_reason（never）→ 只提示（Toast，文案回落 error.<code>）、
 *   不弹授权、不登记待办、不重放（契约数据驱动，与鸿蒙同口径；R1-01）；不带 reason、表里没有的 reason 仍走原授权动作；
 * - 生成的操作登记表不含 /admin/ 后台接口，/v1/agent 的 7 个 operation 已登记；planned 照锁定契约
 *   （锁 00deded0ef6f 起 4 个 planned=false、listAgentSessionMessages / setAgentMessageFeedback / reportAgentMessage 仍 planned=true；#24）。
 */
class CtLock01ContractUpgradeTest {
    private fun map(
        code: Int,
        data: JsonObject = JsonObject(emptyMap()),
        operationId: String = "openLink",
    ) = ErrorActionMapper.map(
        ErrorActionInput(
            code = code,
            data = data,
            operation = checkNotNull(ApiOperations.byId(operationId)),
            headers = emptyMap(),
            isReplay = false,
            state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
        ),
    )

    @Test
    fun adminOnlyCodesAreInTheTableWithoutUserTexts() {
        for (code in listOf(10008, 10009)) {
            val info = ErrorCodes.info(code)
            assertNotNull("ErrorCodes[$code]", info)
            assertFalse("$code deprecated", info!!.isDeprecated)
            assertNull("user dictionary has no error.$code", DefaultTexts.text("error.$code"))
        }
        assertNull("19999 is not in the table", ErrorCodes.info(19999))
    }

    @Test
    fun adminOnlyCodesFallBackLikeUnlistedAuthCodes() {
        val unlisted = map(19999)
        assertEquals(ErrorEffectKind.FALLBACK_MESSAGE, unlisted.kind)
        for (code in listOf(10008, 10009)) {
            for (operationId in listOf("openLink", "loginBySms", "getMe")) {
                val effect = map(code, operationId = operationId)
                assertEquals("$code/$operationId kind", unlisted.kind, effect.kind)
                assertEquals("$code/$operationId route", unlisted.route, effect.route)
                assertEquals("$code/$operationId replay", unlisted.replay, effect.replay)
                assertEquals("$code/$operationId registerPending", unlisted.registerPending, effect.registerPending)
                assertEquals("$code/$operationId textKey", unlisted.textKey, effect.textKey)
                assertEquals("$code/$operationId includeTrace6", unlisted.includeTrace6, effect.includeTrace6)
                assertEquals("$code/$operationId disableMillis", unlisted.disableMillis, effect.disableMillis)
            }
        }
    }

    @Test
    fun textedAuthCodesWithoutDedicatedBranchStillToast() {
        // 回落只看「字典有没有文案键」：10401 / 10403 有 error.<code>，仍是 Toast（行为不变）
        assertEquals(ErrorEffectKind.TOAST, map(10401).kind)
        assertEquals(ErrorEffectKind.TOAST, map(10403).kind)
        // 若字典将来给 10008 加了文案键，它回到表内末尾的 Toast（数据驱动，与鸿蒙同口径）
        val withText =
            ErrorActionMapper.map(
                ErrorActionInput(
                    code = 10008,
                    data = JsonObject(emptyMap()),
                    operation = checkNotNull(ApiOperations.byId("openLink")),
                    headers = emptyMap(),
                    isReplay = false,
                    state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
                ),
                hasText = { it == "error.10008" },
            )
        assertEquals(ErrorEffectKind.TOAST, withText.kind)
        assertEquals("error.10008", withText.textKey)
    }

    @Test
    fun sharerAuthInvalidReasonOnlyPromptsWithoutAuthorizeOrReplay() {
        val reason = JsonObject(mapOf("reason" to JsonPrimitive("sharer_auth_invalid")))
        assertEquals(RetryKind.NEVER, ErrorCodes.info(30102)!!.retryKindByReason["sharer_auth_invalid"])
        assertEquals(RetryKind.NEVER, ErrorCodes.info(30111)!!.retryKindByReason["sharer_auth_invalid"])
        for (code in listOf(30102, 30111)) {
            assertTrue("$code sharer_auth_invalid is prompt-only", ErrorActionMapper.promptOnlyReason(code, "sharer_auth_invalid"))
            for (operationId in listOf("openLink", "convertLink", "getLink", "getSharePage")) {
                val shared = map(code, reason, operationId)
                assertEquals("$code/$operationId kind", ErrorEffectKind.TOAST, shared.kind)
                assertNull("$code/$operationId route (no AuthSheet)", shared.route)
                assertEquals("$code/$operationId replay", ReplayMode.NONE, shared.replay)
                assertFalse("$code/$operationId pending", shared.registerPending)
                // 字典没有 error.<code>.sharer_auth_invalid 子键：文案回落 error.<code>（link_landing.invalid 专门展示归 APP-13b）
                assertEquals("$code/$operationId textKey", "error.$code", shared.textKey)
            }
        }
    }

    @Test
    fun authorizationCodesWithoutATableReasonKeepTheAuthorizeFlow() {
        for (code in listOf(30101, 30102, 30111)) {
            for (data in listOf(JsonObject(emptyMap()), JsonObject(mapOf("reason" to JsonPrimitive("something_new"))))) {
                assertFalse("$code $data", ErrorActionMapper.promptOnlyReason(code, (data["reason"] as? JsonPrimitive)?.content))
                val effect = map(code, data)
                assertEquals("$code $data kind", ErrorEffectKind.AUTHORIZE, effect.kind)
                assertEquals("$code $data route", "AuthSheet", effect.route)
                assertEquals("$code $data replay", ReplayMode.NEW_KEY, effect.replay)
                assertTrue("$code $data pending", effect.registerPending)
            }
        }
        // auth_unavailable（表里 later）照旧只提示
        val unavailable = JsonObject(mapOf("reason" to JsonPrimitive("auth_unavailable")))
        for (code in listOf(30101, 30102)) {
            val effect = map(code, unavailable)
            assertEquals(ErrorEffectKind.TOAST, effect.kind)
            assertFalse(effect.registerPending)
            assertEquals(ReplayMode.NONE, effect.replay)
        }
    }

    @Test
    fun operationRegistryHasNoAdminPathsAndListsTheAgentOperations() {
        val admin = ApiOperations.all.values.filter { it.pathTemplate.startsWith("/admin/") }
        assertTrue("admin operations generated for the App: ${admin.map { it.operationId }}", admin.isEmpty())
        assertTrue(ApiOperations.all.values.all { it.pathTemplate.startsWith("/v1/") || it.pathTemplate == "/healthz" })
        // planned 照锁 00deded0ef6f 的 openapi.yaml x-implementation（#24）：4 个已实现（planned=false），
        // listAgentSessionMessages / setAgentMessageFeedback / reportAgentMessage 仍标 planned（openapi.yaml:28020、28322、28389）
        val agent =
            mapOf(
                "createAgentSession" to Triple("POST", "/v1/agent/sessions", false),
                "getCurrentAgentSession" to Triple("GET", "/v1/agent/sessions/current", false),
                "listAgentSessionMessages" to Triple("GET", "/v1/agent/sessions/{id}/messages", true),
                "sendAgentMessage" to Triple("POST", "/v1/agent/sessions/{id}/messages", false),
                "cancelAgentRun" to Triple("POST", "/v1/agent/runs/{run_id}/cancel", false),
                "setAgentMessageFeedback" to Triple("POST", "/v1/agent/messages/{message_id}/feedback", true),
                "reportAgentMessage" to Triple("POST", "/v1/agent/messages/{message_id}/report", true),
            )
        agent.forEach { (id, route) ->
            val operation = checkNotNull(ApiOperations.byId(id)) { "missing $id" }
            assertEquals("$id method", route.first, operation.method)
            assertEquals("$id path", route.second, operation.pathTemplate)
            assertEquals("$id planned", route.third, operation.planned)
        }
        assertEquals(
            "agent operations",
            agent.keys,
            ApiOperations.all.values
                .filter { it.pathTemplate.startsWith("/v1/agent/") }
                .map { it.operationId }
                .toSet(),
        )
    }
}
