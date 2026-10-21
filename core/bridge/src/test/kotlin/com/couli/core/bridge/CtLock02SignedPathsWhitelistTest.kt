package com.couli.core.bridge

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT-LOCK-02 复审 r1：桥运行时白名单（BridgeContract.signedPaths）与协议准入（BridgeProtocol.signedPathAllowed）
 * 都要与锁定快照 bridge.schema.json 的 signed_paths 双向一致。预期值直接读 contracts/snapshot 文件，不经生成物或运行时。
 * 运行时多放一项、少放一项都使本测试变红（变异记录见 NATIVE/CT-LOCK-02/android-mutations.txt）。
 */
class CtLock02SignedPathsWhitelistTest {
    private val snapshotRoot: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val repo = checkNotNull(generateSequence(start) { it.parentFile }.firstOrNull { File(it, "contract.lock").isFile })
        File(repo, "contracts/snapshot/contracts")
    }

    private fun read(name: String): JsonObject = Json.parseToJsonElement(File(snapshotRoot, name).readText()).jsonObject

    /** 快照 signed_paths：`METHOD path`。 */
    private fun snapshotSignedPaths(): Set<String> =
        read("bridge.schema.json")
            .getValue("signed_paths")
            .jsonArray
            .map {
                val row = it.jsonObject
                row.getValue("method").jsonPrimitive.content + " " + row.getValue("path").jsonPrimitive.content
            }.toSet()

    /** 快照 openapi 的全部客户端接口（不含 /admin/），签名与否都算候选：`METHOD path`。 */
    private fun snapshotClientOperations(): Set<String> {
        val paths = read("openapi.json").getValue("paths").jsonObject.filterKeys { !it.startsWith("/admin/") }
        return paths
            .flatMap { (path, item) ->
                item.jsonObject
                    .filterValues { (it as? JsonObject)?.containsKey("operationId") == true }
                    .keys
                    .map { it.uppercase() + " " + path }
            }.toSet()
    }

    @Test
    fun runtimeWhitelistEqualsSnapshotSignedPaths() {
        val expected = snapshotSignedPaths()
        assertTrue("前提：快照 signed_paths 非空", expected.isNotEmpty())
        val runtime = BridgeContract.signedPaths.map { it.method + " " + it.path }
        assertEquals("运行时白名单没有重复项", runtime.size, runtime.toSet().size)
        assertEquals("多出的项", emptySet<String>(), runtime.toSet() - expected)
        assertEquals("缺少的项", emptySet<String>(), expected - runtime.toSet())
    }

    @Test
    fun protocolAdmitsExactlyTheSnapshotSignedPaths() {
        val expected = snapshotSignedPaths()
        val candidates = snapshotClientOperations() + expected + BridgeContract.signedPaths.map { it.method + " " + it.path }
        val admitted =
            candidates
                .filter { key ->
                    BridgeProtocol.signedPathAllowed(key.substringBefore(' '), key.substringAfter(' '))
                }.toSet()
        assertEquals(expected, admitted)
        for (key in candidates - expected) {
            val method = key.substringBefore(' ')
            val path = key.substringAfter(' ')
            assertTrue("白名单外不准入：$key", !BridgeProtocol.signedPathAllowed(method, path))
            assertTrue("带查询串也不准入：$key", !BridgeProtocol.signedPathAllowed(method, "$path?x=1"))
        }
    }
}
