package com.couli.app.wallet.visual

import com.couli.core.wallet.PayoutAccountView
import com.couli.core.wallet.RealNameForm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** visualPayout 夹具：收款账号报文经锁定解析器；画板上出现的每类元素都有数据；不造缺契约字段与微信零钱；键名避开扫描器关键字。 */
class PayoutIdentityVisualFixtureTest {
    private val group = PayoutIdentityVisualFixtures.group

    @Test fun payoutFixturesParseThroughLockedParserWithBoardValues() {
        assertEquals(
            false,
            PayoutAccountView
                .of(PayoutIdentityVisualFixtures.payout("unbound"))
                .getValue("bound")
                .jsonPrimitive.boolean,
        )
        val alipay = PayoutAccountView.of(PayoutIdentityVisualFixtures.payout("boundAlipay"))
        assertEquals("138****5678", alipay.getValue("maskedAccount").jsonPrimitive.content)
        assertEquals("**三", alipay.getValue("maskedPayee").jsonPrimitive.content)
        assertEquals(2L, alipay.getValue("changeRemaining").jsonPrimitive.long)
        val bank = PayoutAccountView.of(PayoutIdentityVisualFixtures.payout("boundBank"))
        assertEquals("招商银行", bank.getValue("bankName").jsonPrimitive.content)
        assertEquals("尾号 1234", bank.getValue("maskedAccount").jsonPrimitive.content)
        assertEquals(
            0L,
            PayoutAccountView
                .of(PayoutIdentityVisualFixtures.payout("boundExhausted"))
                .getValue("changeRemaining")
                .jsonPrimitive.long,
        )
        val stress =
            PayoutAccountView
                .of(
                    PayoutIdentityVisualFixtures.payout("boundStress"),
                ).getValue("maskedAccount")
                .jsonPrimitive.content
        assertTrue("200% 压力值：长邮箱", stress.length >= 40)
    }

    @Test fun realNameAndLaborFixturesMatchDebugHandWrittenShapes() {
        val input =
            RealNameForm.normalize(
                PayoutIdentityVisualFixtures.realnameForm("name"),
                PayoutIdentityVisualFixtures.realnameForm("idNo"),
            )
        assertTrue("实名夹具能通过本地规整（小写 x 转大写）", input.submitEnabled)
        assertEquals("11010119900101123X", input.idNo)
        val first = PayoutIdentityVisualFixtures.labor()
        assertEquals(setOf("version", "text", "text_sha256", "need_sign"), first.keys)
        assertTrue(
            "全文足够长，能撑满 360 并滚动",
            first
                .getValue("text")
                .jsonPrimitive.content
                .lines()
                .size >= 20,
        )
        val resign = PayoutIdentityVisualFixtures.labor("resign")
        assertEquals("G-W24：改版只登记锁定契约形状，待 FU", "G-W24", resign.getValue("pendingFu").jsonPrimitive.content)
        assertTrue(resign.containsKey("signed_version"))
        val errors =
            PayoutIdentityVisualFixtures.group
                .getValue("realname")
                .jsonObject
                .getValue("errors")
                .jsonObject
        assertEquals(setOf("verifyFailed", "idTaken", "age", "dailyLimit", "server", "fields"), errors.keys)
        assertEquals(
            setOf("changeLimit", "verifyLimit", "payeeMismatch", "accountTaken", "busy"),
            group.getValue("saveErrors").jsonObject.keys,
        )
    }

    @Test fun boardListIsTheTwentyValidEBoardsWithoutWechat() {
        val boards = PayoutIdentityVisualFixtures.boards()
        assertEquals("§1.1 E 有效 20 张", 20, boards.size)
        assertEquals(boards.size, boards.toSet().size)
        assertFalse("微信零钱 4 张不对照（G-W10）", boards.any { it.contains("Wechat") })
        assertEquals(4, group.getValue("notCompared").jsonArray.size)
        assertEquals(1791772200000L, PayoutIdentityVisualFixtures.now)
    }

    @Test fun fixtureKeyNamesAvoidSecretScannerWords() {
        val banned = Regex("token|secret|key|sentry|password", RegexOption.IGNORE_CASE)
        val names = mutableListOf<String>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    element.forEach { (name, value) ->
                        names += name
                        walk(value)
                    }
                }

                is JsonArray -> {
                    element.forEach(::walk)
                }

                else -> {
                    Unit
                }
            }
        }
        walk(group)
        assertTrue("夹具键名：${names.filter { banned.containsMatchIn(it) }}", names.none { banned.containsMatchIn(it) })
    }
}
