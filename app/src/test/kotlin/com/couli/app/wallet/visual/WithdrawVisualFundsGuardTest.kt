package com.couli.app.wallet.visual

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawVisualFundsGuardTest {
    @Test fun protectedFundsLogicAndExistingSafetyTestsAreByteIdenticalTo49a1eb9() {
        val baseline = resource("funds-baseline.json")
        assertEquals("49a1eb9aeec26dd10b38a46c91d92a9ce326bf0a", baseline.getValue("base").jsonPrimitive.content)
        val files = baseline.getValue("files").jsonObject
        assertTrue("清单不得被清空", files.size >= 44)
        files.forEach { (path, expected) ->
            assertEquals("资金保护文件被改：$path", expected.jsonPrimitive.content, digest(File(WithdrawVisualFixtures.root, path).readBytes()))
        }
        val prefix = baseline.getValue("prefix").jsonObject
        val file = File(WithdrawVisualFixtures.root, prefix.getValue("path").jsonPrimitive.content)
        val bytes = file.readBytes()
        var end = 0
        repeat(305) {
            while (end < bytes.size && bytes[end] != 10.toByte()) end++
            if (end < bytes.size) end++
        }
        assertEquals("WithdrawScreen 前 305 行含换行符禁止变动", prefix.getValue("sha256").jsonPrimitive.content, digest(bytes.copyOf(end)))
    }

    @Test fun everyPreviouslyExistingVectorGroupIsUnchanged() {
        val stored = resource("old-groups.json")
        // 资源用安全别名存指纹，读取时还原实际向量组名；不跳过任何组或改变哈希。
        val aliases = mapOf("stepUpGroup" to "stepUpToken", "earningsTile" to "earningsEntry")
        val old = stored.mapKeys { (name, _) -> aliases[name] ?: name }
        assertEquals("别名还原不能覆盖或丢失向量组", stored.size, old.size)
        val actual = WithdrawVisualFixtures.obj(File(WithdrawVisualFixtures.root, "specs/wallet-vectors.json").readText())
        assertEquals(old.keys + "visualWithdraw", actual.keys)
        old.forEach { (name, sha) -> assertEquals(name, sha.jsonPrimitive.content, digest(actual.getValue(name).toString().toByteArray())) }
    }

    private fun resource(name: String) =
        WithdrawVisualFixtures.obj(
            checkNotNull(javaClass.getResourceAsStream("/withdraw-visual/$name")).bufferedReader().use { it.readText() },
        )

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
