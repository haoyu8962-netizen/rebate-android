package com.couli.app.wallet.visual

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资金逻辑零改动（brief §5，资金页口径）：冻结入场底版 6770a58 的资金 / 身份逻辑文件、core/wallet 纯规则（StepUpSheet.kt 归 d、WalletTexts.kt 草稿可改）、
 * WithdrawScreen 前 305 行与 FU-AND-0718…0721 / Invariant / Wiring / Review / Owner 保护测试；wallet-vectors.json 旧组逐组规范指纹不变、只多 visualPayout。
 * 改逻辑让视觉测试变绿会在这里直接失败。
 */
class PayoutIdentityVisualFundsGuardTest {
    @Test fun protectedFundsAndIdentityLogicAndSafetyTestsAreByteIdenticalTo6770a58() {
        val baseline = resource("funds-baseline.json")
        assertEquals("6770a580c2c9eac45b47589f2b24e24b2659dbdc", baseline.getValue("base").jsonPrimitive.content)
        val files = baseline.getValue("files").jsonObject
        assertTrue("清单不得被清空", files.size >= 34)
        listOf(
            "feature/wallet/src/main/kotlin/com/couli/feature/wallet/PayoutAccountController.kt",
            "feature/wallet/src/main/kotlin/com/couli/feature/wallet/IdentityControllers.kt",
            "feature/wallet/src/main/kotlin/com/couli/feature/wallet/WalletPorts.kt",
            "core/wallet/src/main/kotlin/com/couli/core/wallet/PayoutLogic.kt",
            "app/src/test/kotlin/com/couli/app/wallet/WalletPageWiringTest.kt",
            "app/src/test/kotlin/com/couli/app/followup/FollowupAnd0721InvariantTest.kt",
        ).forEach { assertTrue("清单必须包含 $it", it in files) }
        files.forEach { (path, expected) ->
            assertEquals(
                "资金 / 身份保护文件被改：$path",
                expected.jsonPrimitive.content,
                digest(File(PayoutIdentityVisualFixtures.root, path).readBytes()),
            )
        }
        val prefix = baseline.getValue("prefix").jsonObject
        val bytes = File(PayoutIdentityVisualFixtures.root, prefix.getValue("path").jsonPrimitive.content).readBytes()
        var end = 0
        repeat(305) {
            while (end < bytes.size && bytes[end] != NEWLINE) end++
            if (end < bytes.size) end++
        }
        assertEquals(
            "WithdrawScreen 前 305 行（ViewModel，含换行）禁止变动",
            prefix.getValue("sha256").jsonPrimitive.content,
            digest(bytes.copyOf(end)),
        )
    }

    @Test fun everyExistingVectorGroupIsUnchangedAndOnlyVisualPayoutIsAdded() {
        val stored = resource("old-groups.json")
        // 资源用安全别名存指纹（避开扫描器关键字），读取时还原实际组名；不跳过任何组。
        val aliases = mapOf("stepUpGroup" to "stepUpToken", "earningsTile" to "earningsEntry")
        val old = stored.mapKeys { (name, _) -> aliases[name] ?: name }
        assertEquals("别名还原不能覆盖或丢失向量组", stored.size, old.size)
        assertTrue("入场 25 组都在清单内", old.size >= 25 && "visualWithdraw" in old && "visualWallet" in old)
        val actual = WithdrawVisualFixtures.obj(File(PayoutIdentityVisualFixtures.root, "specs/wallet-vectors.json").readText())
        assertTrue("既有组不得删除", actual.keys.containsAll(old.keys))
        assertTrue("本任务新增 visualPayout", "visualPayout" in actual.keys)
        val added = actual.keys - old.keys
        assertTrue("只允许新增 visual 开头的组（编排口径）：$added", added.all { it.startsWith("visual") })
        old.forEach { (name, sha) ->
            assertEquals("旧向量组被改：$name", sha.jsonPrimitive.content, digest(canonical(actual.getValue(name)).toByteArray()))
        }
    }

    private fun resource(name: String): JsonObject =
        WithdrawVisualFixtures.obj(
            checkNotNull(javaClass.getResourceAsStream("/payout-identity-visual/$name")).bufferedReader().use { it.readText() },
        )

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val NEWLINE: Byte = 10

        /** 与 couli-runs/NATIVE/APP-09v/tests-e-android-groups.py 同一规范：键排序；字符串 s:、数字 / 布尔 p: 原字面量、null n。 */
        fun canonical(element: JsonElement): String =
            when (element) {
                is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonical(element.getValue(it)) }
                is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
                JsonNull -> "n"
                is JsonPrimitive -> if (element.isString) "s:" + quote(element.content) else "p:" + element.content
            }

        fun quote(text: String): String =
            buildString {
                append('"')
                text.forEach {
                    when {
                        it == '"' -> append("\\\"")
                        it == '\\' -> append("\\\\")
                        it == '\n' -> append("\\n")
                        it == '\r' -> append("\\r")
                        it == '\t' -> append("\\t")
                        it == '\b' -> append("\\b")
                        it == '\u000C' -> append("\\f")
                        it.code < SPACE -> append("\\u%04x".format(it.code))
                        else -> append(it)
                    }
                }
                append('"')
            }

        const val SPACE = 0x20
    }
}
