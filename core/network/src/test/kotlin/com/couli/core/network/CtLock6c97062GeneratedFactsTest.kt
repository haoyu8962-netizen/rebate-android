package com.couli.core.network

import com.couli.core.generated.ApiOperations
import com.couli.core.network.CtLock6c97062Fixture.Companion.ABANDON
import com.couli.core.network.CtLock6c97062Fixture.Companion.WITHDRAW
import com.couli.core.network.CtLock6c97062Fixture.Companion.operation
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-CONTRACT-LOCK-6c97062（D10-2888）：网络层判定所依据的生成物事实，逐项与契约快照 openapi.json 对照（防回退；97d0ffd 上已成立，非先红）。
 * - 敏感操作集合 = 快照里客户端路径上带 x-sensitive-operation 的操作；x-step-up 的操作是它的子集，提现敏感而没有 stepUp；
 * - 每个敏感操作幂等、x-error-codes 含 20903；
 * - abandonAction 一对一覆盖 IdempotencyAbandonAction 枚举，且与 abandonIdempotencyKey 说明里的「action → 方法 路径」对照一致。
 */
class CtLock6c97062GeneratedFactsTest {
    private val openapi: JsonObject by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        CtLock6c97062Fixture.json(File(root, "contracts/snapshot/contracts/openapi.json").readText())
    }

    /** 快照里客户端路径（不含 /admin/）的操作：operationId → (方法, 路径, 操作对象)。 */
    private fun snapshotOperations(): Map<String, Triple<String, String, JsonObject>> {
        val found = linkedMapOf<String, Triple<String, String, JsonObject>>()
        val paths = openapi.getValue("paths").jsonObject
        for ((path, item) in paths) {
            if (path.startsWith("/admin/")) continue
            for ((method, op) in item.jsonObject) {
                if (method !in HTTP_METHODS) continue
                val body = op.jsonObject
                found[body.getValue("operationId").jsonPrimitive.content] = Triple(method.uppercase(), path, body)
            }
        }
        return found
    }

    private val sensitive = ApiOperations.all.values.filter { it.sensitiveOperation }

    @Test
    fun sensitiveSetIsExactlyTheSnapshotMarkedOperations() {
        val marked =
            snapshotOperations()
                .filterValues { (_, _, op) -> op["x-sensitive-operation"]?.jsonPrimitive?.content == "true" }
                .keys
        assertEquals(marked, sensitive.map { it.operationId }.toSet())
        assertEquals("契约 info.description：恰好四个敏感操作", 4, marked.size)
    }

    @Test
    fun stepUpIsASubsetAndTheWithdrawalHasNone() {
        val stepUp = ApiOperations.all.values.filter { it.stepUp != null }
        assertTrue("x-step-up 的操作都是敏感操作", stepUp.all { it.sensitiveOperation })
        assertEquals("契约：四个里只有三个要二次验证", sensitive.size - 1, stepUp.size)
        assertNull(operation(WITHDRAW).stepUp)
        assertTrue(operation(WITHDRAW).sensitiveOperation)
    }

    @Test
    fun sensitiveOperationsAreIdempotentAndList20903() {
        sensitive.forEach { op ->
            assertTrue("${op.operationId} 幂等", op.idempotent)
            assertTrue("${op.operationId} 列出 20903", 20903 in op.errorCodes)
        }
    }

    @Test
    fun abandonActionsCoverTheEnumOneToOneAndMatchTheAbandonDescription() {
        val schemas =
            openapi
                .getValue("components")
                .jsonObject
                .getValue("schemas")
                .jsonObject
        val abandonEnum =
            schemas
                .getValue("IdempotencyAbandonAction")
                .jsonObject
                .getValue("enum")
                .jsonArray
        val enum = abandonEnum.map { it.jsonPrimitive.content }
        val actions = sensitive.map { checkNotNull(it.abandonAction) { "${it.operationId} 缺 abandonAction" } }
        assertEquals("一对一", actions.size, actions.toSet().size)
        assertEquals(enum.toSet(), actions.toSet())
        assertTrue(
            "非敏感操作没有 abandonAction",
            ApiOperations.all.values
                .filterNot { it.sensitiveOperation }
                .all { it.abandonAction == null },
        )

        // abandonIdempotencyKey 说明里写的对照：「withdraw → POST /v1/withdrawals, …」
        val abandon = snapshotOperations().getValue(ABANDON).third
        val description =
            abandon
                .getValue("description")
                .jsonPrimitive.content
                .replace(Regex("\\s+"), " ")
        val described =
            MAPPING.findAll(description).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }
        val generated = sensitive.associate { checkNotNull(it.abandonAction) to (it.method to it.pathTemplate) }
        assertEquals(generated, described)
    }

    private companion object {
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")
        val MAPPING = Regex("""([a-z_]+) → (GET|POST|PUT|PATCH|DELETE) (/[A-Za-z0-9/_{}-]*[A-Za-z0-9}])""")
    }
}
