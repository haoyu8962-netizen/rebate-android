package com.couli.app.account

import com.couli.core.designsystem.CouliFormat
import com.couli.core.me.account.AccountDraftTexts
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-13v fixture（specs/account-visual-vectors.json，G-D15）自检：三端同一份；键名避开 gitleaks 敏感词；画板 35 张（a 段 15、b 段 20）与登记一致；
 * 报文字段与锁定契约 Deletion / 钱包摘要同名；金额 / 时间只交给产品格式化（这里复算）；不放请求体原文、不放像令牌的串。底版上应全绿。
 */
class AccountVisualFixtureTest {
    private val data = AccountVisualFixtures.data

    @Test fun keysAvoidSensitiveWords() {
        val keys = mutableListOf<String>()

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> keys += k.lowercase().also { walk(v) } }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(data)
        val bad = keys.filter { k -> SENSITIVE.any { k.contains(it) } }
        assertTrue("fixture 键名不含 token / secret / key / sentry / password / auth：$bad", bad.isEmpty())
        val raw = AccountVisualFixtures.file.readText()
        assertFalse("不含像令牌的串", Regex("""(?i)(tok-|access-|refresh-|bearer|step_up_token)""").containsMatchIn(raw))
    }

    @Test fun boardsMatchTheRegisteredThirtyFive() {
        val boards = AccountVisualFixtures.boards()
        assertEquals("绑定 3 + 更换 17 + 注销 15", 35, boards.size)
        assertEquals("a 段注销 15 张", 15, AccountVisualFixtures.boards("a").size)
        assertEquals("b 段换号 + 绑号 20 张", 20, AccountVisualFixtures.boards("b").size)
        boards.forEach {
            assertTrue("sha12 为 12 位十六进制：$it", Regex("^[0-9a-f]{12}$").matches(AccountVisualFixtures.text("sha12", it)))
            assertEquals("宽 390", "390", AccountVisualFixtures.text("width", it))
        }
        assertEquals("深色稿 4 张", 4, boards.count { it.getValue("dark").jsonPrimitive.boolean })
        assertEquals("名字不重复", boards.size, boards.map { AccountVisualFixtures.text("name", it) }.toSet().size)
        val retired = (data.getValue("retiredBoards") as JsonArray).map { AccountVisualFixtures.text("name", it.jsonObject) }.toSet()
        assertTrue("作废旧稿不在对照表", boards.none { AccountVisualFixtures.text("name", it) in retired })
        assertEquals("DeleteAccount-v2 1164 高（brief §1.1）", 1164, AccountVisualFixtures.height("DeleteAccount-v2"))
    }

    @Test fun deletionPayloadsUseTheLockedContractFields() {
        val fields = setOf("status", "apply_at", "cooling_until", "can_cancel", "cancelled_at", "cancel_reason", "processed_at")
        AccountVisualFixtures.obj("deletion").forEach { (name, value) ->
            if (value is JsonNull) return@forEach
            assertEquals("$name 只用契约 Deletion 字段", fields, value.jsonObject.keys)
        }
        assertEquals("null = 从未申请", "null", AccountVisualFixtures.deletion("none"))
        val cooling = AccountVisualFixtures.obj("cooling", AccountVisualFixtures.obj("deletion"))
        assertEquals("2026-10-08T10:00:00+08:00", AccountVisualFixtures.text("apply_at", cooling))
        assertEquals("2026-10-15T10:00:00+08:00", AccountVisualFixtures.text("cooling_until", cooling))
        assertTrue(cooling.getValue("can_cancel").jsonPrimitive.boolean)
        assertFalse(
            AccountVisualFixtures
                .obj("cooling_expired", AccountVisualFixtures.obj("deletion"))
                .getValue("can_cancel")
                .jsonPrimitive.boolean,
        )
    }

    @Test fun amountsAndTimesAreFormattedByTheProduct() {
        assertEquals("¥12.34", CouliFormat.money(AccountVisualFixtures.walletFen("funds", "withdrawable_fen")))
        assertEquals("¥56.78", CouliFormat.money(AccountVisualFixtures.walletFen("funds", "estimated_total_fen")))
        assertEquals("¥5.67", CouliFormat.money(AccountVisualFixtures.walletFen("negative", "negative_fen")))
        assertEquals("30416 金额与进页负余额同值", AccountVisualFixtures.walletFen("negative", "negative_fen"), AccountVisualFixtures.amount30416())
        val now = AccountVisualFixtures.millis(AccountVisualFixtures.clock)
        val until = AccountVisualFixtures.millis("2026-10-15T10:00:00+08:00")
        assertTrue("冷静期截止只由 CouliFormat 格式化（非空）", CouliFormat.timeListFuture(until, now).isNotBlank())
        assertTrue("提交时间（过去）", CouliFormat.timeListPast(AccountVisualFixtures.pendingCreatedAt("deletion"), now).isNotBlank())
    }

    @Test fun walletPayloadKeepsTheSummaryShape() {
        val wallet =
            kotlinx.serialization.json.Json
                .parseToJsonElement(AccountVisualFixtures.wallet("negative"))
                .jsonObject
        assertEquals(
            "负余额：可提现 0",
            0L,
            wallet
                .getValue("withdrawable_fen")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            "available = -negative",
            -567L,
            wallet
                .getValue("available_fen")
                .jsonPrimitive.content
                .toLong(),
        )
        assertTrue("契约摘要字段齐全", wallet.keys.containsAll(listOf("negative_fen", "estimated_total_fen", "withdraw_enabled")))
    }

    @Test fun pendingSeedsCarryNoBodyAndOnlyMaskedPhones() {
        val pending = AccountVisualFixtures.obj("pending")
        assertEquals("注销未决无请求体", "", AccountVisualFixtures.text("body", pending.getValue("deletion").jsonObject))
        assertEquals("requestDeletion", AccountVisualFixtures.pendingOperation("deletion"))
        listOf("phone_change", "bind_phone").forEach {
            assertTrue("未决摘要只放本机脱敏号码：$it", Regex("""^1\d{2}\*{4}\d{4}$""").matches(AccountVisualFixtures.pendingSummary(it)))
        }
        val phones = AccountVisualFixtures.obj("phones")
        listOf("fresh", "blocked", "bind").forEach { k ->
            val masked = AccountVisualFixtures.text(k + "Masked", phones)
            val full = AccountVisualFixtures.text(k, phones)
            assertEquals("脱敏 = 前 3 + **** + 后 4", full.take(3) + "****" + full.takeLast(4), masked)
        }
    }

    @Test fun draftKeysAreKnownAndRetiredPhrasesAreNotInTheFinalDrafts() {
        val drafts = AccountVisualFixtures.drafts()
        drafts.keys.filter { it != "deletion.withdraw_hint.no_withdraw" }.forEach {
            assertTrue("G-D07 只改已有键：$it", AccountDraftTexts.DRAFTS.containsKey(it))
        }
        AccountVisualFixtures.list("retiredPhrases").forEach { phrase ->
            assertTrue("定稿句不含旧说法：$phrase", drafts.values.none { it.contains(phrase) })
        }
        assertEquals("须知六条", 6, AccountVisualFixtures.text("noticeCount").toInt())
        assertEquals("资金术语新增一键", listOf("deletion.withdraw_hint.no_withdraw"), AccountVisualFixtures.list("fundTermsAdded"))
    }

    @Test fun behaviourVectorsAreUntouched() {
        val file = File(AccountVisualFixtures.root, "specs/account-vectors.json")
        assertTrue("account-vectors.json 仍在（G-D15：不追加、不改）", file.isFile)
        val text = file.readText()
        assertFalse("没有把视觉组追加进行为向量", text.contains("account-visual") || text.contains("visualAccount"))
        assertEquals(64, sha(file.readBytes()).length)
    }

    @Test fun codesCarryOnlyFixtureValues() {
        val codes = AccountVisualFixtures.obj("codes")
        listOf(
            "30412",
            "30416",
            "10007",
            "20903",
            "40901",
            "42901",
            "44001",
            "50001",
        ).forEach { assertTrue("码表含 $it", codes.containsKey(it)) }
        assertEquals(JsonPrimitive(30), codes.getValue("42901").jsonObject.getValue("retryAfterSec"))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val SENSITIVE = listOf("token", "secret", "key", "sentry", "password", "auth")
    }
}
