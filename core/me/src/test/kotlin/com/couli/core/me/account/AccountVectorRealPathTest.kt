package com.couli.core.me.account

import com.couli.core.wallet.StepUpTokenCache
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0716 第 5 项（APP-10b 共性第 46 条：向量通过不代表控制器路径一致）：[AccountVectorTest] 的 stepUpToken 组测的是
 * [StepUpTokenPolicy.reuse]，生产里注销页实际复用 token 走的是 core/wallet 的 [StepUpTokenCache.take]（按账号、会话世代、作用域、action 与
 * expire_at）。这里把同一组向量原样回放到真实缓存上，「是否复用」必须与向量一致。作用域本地拒绝（scope_denied）在生产里由网络层
 * RequestHeaderPolicy 拦（不在缓存里），这些行只核对向量期望的 reuse=false 由策略给出、不在缓存层断言。
 */
class AccountVectorRealPathTest {
    private val root: File by lazy {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun stepUpTokenVectorsAgreeWithTheRealStepUpTokenCache() {
        val rows =
            Json
                .parseToJsonElement(File(root, "specs/account-vectors.json").readText())
                .jsonObject
                .getValue("stepUpToken")
                .jsonArray
        assertTrue("向量组非空", rows.isNotEmpty())
        var replayed = 0
        rows.map { it.jsonObject }.forEach { row ->
            val name = row.text("name")
            val expect = row.getValue("expect").jsonObject
            val request = row.getValue("request").jsonObject
            if (expect.text("localRejectRaw") != StepUpTokenPolicy.NONE) {
                assertEquals("$name：作用域拒绝的行不复用", false, expect.getValue("reuse").jsonPrimitive.boolean)
                return@forEach
            }
            val cache = StepUpTokenCache()
            row["cached"]?.takeUnless { it == JsonNull }?.jsonObject?.let { cached ->
                cache.put(
                    cached.text("action"),
                    cached.text("accountId"),
                    "TEST_TOKEN_$name",
                    ms(cached.text("expireAtIso")),
                    cached.getValue("sessionGen").jsonPrimitive.long,
                    cached.text("scopeRaw"),
                )
            }
            val reused =
                cache.take(
                    request.text("action"),
                    request.text("accountId"),
                    ms(request.text("nowIso")),
                    request.getValue("sessionGen").jsonPrimitive.long,
                    request.text("scopeRaw"),
                ) != null
            assertEquals("$name：真实缓存的复用结论", expect.getValue("reuse").jsonPrimitive.boolean, reused)
            replayed += 1
        }
        assertTrue("至少回放了一行到真实缓存", replayed > 0)
    }
}
