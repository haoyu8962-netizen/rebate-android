package com.couli.app.web

import com.couli.core.bridge.BridgeProtocol
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-02 编排裁定（契约即白名单）：桥可代发的 signed 操作集合 = 契约里的 signed 操作集合。
 * 预期值独立读 contracts/snapshot（openapi 的 x-signed、bridge.schema 的 signed_paths），不从生成代码反推、不手抄。
 */
class CtLock02BridgeSignedOperationsTest {
    private val snapshotRoot: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val repo = checkNotNull(generateSequence(start) { it.parentFile }.firstOrNull { File(it, "contract.lock").isFile })
        File(repo, "contracts/snapshot/contracts")
    }

    private fun read(name: String): JsonObject = Json.parseToJsonElement(File(snapshotRoot, name).readText()).jsonObject

    /** 契约 openapi 的客户端接口（不含 /admin/）：(method, path) → (operationId, x-signed)。 */
    private fun contractOperations(): Map<Pair<String, String>, Pair<String, Boolean>> {
        val result = mutableMapOf<Pair<String, String>, Pair<String, Boolean>>()
        val paths = read("openapi.json").getValue("paths").jsonObject.filterKeys { !it.startsWith("/admin/") }
        for ((path, item) in paths) {
            for ((method, op) in item.jsonObject) {
                val id = (op as? JsonObject)?.get("operationId")?.jsonPrimitive?.content ?: continue
                val signed = op.jsonObject["x-signed"]?.jsonPrimitive?.boolean ?: false
                result[method.uppercase() to path] = id to signed
            }
        }
        return result
    }

    /** 快照 bridge.schema 的 signed_paths：(method, path)。 */
    private fun snapshotSignedPaths(): Set<Pair<String, String>> =
        read("bridge.schema.json")
            .getValue("signed_paths")
            .jsonArray
            .map {
                val row = it.jsonObject
                row.getValue("method").jsonPrimitive.content to row.getValue("path").jsonPrimitive.content
            }.toSet()

    /**
     * 复审 r1 重写：预期 = 快照 signed_paths ∩ openapi x-signed（只读快照文件）；实际 = 把快照里每个客户端接口（签名与否）
     * 连同 signed_paths 各项都当候选，过生产准入的两道关（协议层 BridgeProtocol.signedPathAllowed + 代发查找
     * BridgeSignedOperations.operationFor），能通过的 operationId 集合。双向比较；白名单外候选一律不准入。
     */
    @Test
    fun bridgeSignedSetEqualsContractSignedSet() {
        val signedPaths = snapshotSignedPaths()
        assertTrue("前提：契约 signed_paths 非空", signedPaths.isNotEmpty())
        val contract = contractOperations()
        val expected =
            signedPaths
                .mapNotNull { contract[it] }
                .filter { it.second }
                .map { it.first }
                .toSet()
        assertEquals("signed_paths 每一项在 openapi 里都是签名接口", signedPaths.size, expected.size)
        assertTrue("当前契约含 createOrderClaim", "createOrderClaim" in expected)
        val candidates = contract.keys + signedPaths
        val admitted =
            candidates
                .filter { (method, path) -> BridgeProtocol.signedPathAllowed(method, path) }
                .mapNotNull { (method, path) -> BridgeSignedOperations.operationFor(method, path)?.operationId }
                .toSet()
        assertEquals("多放的操作", emptySet<String>(), admitted - expected)
        assertEquals("少放的操作", emptySet<String>(), expected - admitted)
        for ((method, path) in candidates - signedPaths) {
            assertFalse("白名单外的候选不准入：$method $path", BridgeProtocol.signedPathAllowed(method, path))
        }
    }

    @Test
    fun lookupFollowsOpenapiXSignedForEveryClientOperation() {
        for ((key, value) in contractOperations()) {
            val found = BridgeSignedOperations.operationFor(key.first, key.second)?.operationId
            assertEquals("${key.first} ${key.second}", if (value.second) value.first else null, found)
        }
        assertEquals("createOrderClaim", BridgeSignedOperations.operationFor("POST", "/v1/orders/claims?x=1")?.operationId)
    }
}
