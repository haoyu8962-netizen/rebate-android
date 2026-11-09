package com.couli.app.wallet.realname

import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.core.wallet.WalletErrors
import com.couli.feature.wallet.WalletTags
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三端实名向量（specs/realname-submit-vectors.json，sha256 钉死、逐字节照抄 iOS）25 条逐条跑。步骤照向量的 harness / harnessUnknown 字段：
 * GET /v1/me 先回 none；钱包 → 提现被守卫送进 RealName；填 request.normalize[0]，【提交认证】→ 勾选 → 【同意并继续】；/v1/consents 回 0；
 * POST /v1/me/realname 第 1 次回 reply，之后回 0；第 1 次提交后等 1 秒观察窗再判 expect，expect 里没写的项不判。带 sequence 的按 harnessUnknown：
 * 断连 → 等恢复再提交（沿用原键）→ 等恢复（42901 等过 Retry-After）后第 3 次提交，判第 3 次与第 1 次的键。e40901_busy 的上限见 RealnameFundsBusyTest。
 */
class RealnameFundsVectorTest : RealnameFundsHarness() {
    @Test fun vectorFileFormatAndContractCommit() {
        val raw = vectorsFile.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals("向量逐字节照抄 iOS（D10-887 ⑥）", VECTORS_SHA256, sha)
        assertEquals(1, vectors.text("version").toInt())
        val lock = Json.parseToJsonElement(File(WithdrawVisualFixtures.root, "contract.lock").readText()).jsonObject
        assertEquals(
            "向量 contractCommit 与本仓库锁定的 rebate-platform 提交一致",
            lock.getValue("rebate_platform").jsonObject.text("commit"),
            vectors.text("contractCommit"),
        )
        assertTrue("harness 写明步骤", vectors.text("harness").isNotBlank())
        assertTrue("harnessUnknown 写明步骤", vectors.text("harnessUnknown").isNotBlank())
        val names = cases.map { it.getValue("name").jsonPrimitive.content }
        assertEquals("向量条数", CASE_COUNT, names.size)
        assertEquals("向量名不重复", names.size, names.toSet().size)
        assertEquals("每条向量都有用例", names.toSet(), COVERED.toSet())
    }

    @Test fun ok_verified() = run("ok_verified")

    @Test fun e20001_field_id_no() = run("e20001_field_id_no")

    @Test fun e20001_field_name() = run("e20001_field_name")

    @Test fun e20001_no_fields() = run("e20001_no_fields")

    @Test fun e30405_taken() = run("e30405_taken")

    @Test fun e30406_mismatch() = run("e30406_mismatch")

    @Test fun e30407_age() = run("e30407_age")

    @Test fun e30410_daily_limit() = run("e30410_daily_limit")

    @Test fun e42901_rate_limited() = run("e42901_rate_limited")

    @Test fun e50401_vendor_timeout() = run("e50401_vendor_timeout")

    @Test fun e50301_service_unavailable() = run("e50301_service_unavailable")

    @Test fun e50001_server() = run("e50001_server")

    @Test fun e10004_id_verification() = run("e10004_id_verification")

    @Test fun e10005_bind_phone() = run("e10005_bind_phone")

    @Test fun e10010_reverify() = run("e10010_reverify")

    @Test fun e10001_login() = run("e10001_login")

    @Test fun e10405_force_update() = run("e10405_force_update")

    @Test fun transport_lost() = run("transport_lost")

    @Test fun unknown_then_e10405() = run("unknown_then_e10405")

    @Test fun unknown_then_e50001() = run("unknown_then_e50001")

    @Test fun unknown_then_e50401() = run("unknown_then_e50401")

    @Test fun unknown_then_e50301() = run("unknown_then_e50301")

    @Test fun unknown_then_e42901() = run("unknown_then_e42901")

    @Test fun unknown_then_e30406() = run("unknown_then_e30406")

    /** e40901_busy（harness：第 1 次 40901，之后 0）：保持在途、自动原键原体重发、不重记同意；一直 40901 的上限见 RealnameFundsBusyTest。 */
    @Test fun e40901_busy() {
        val case = case("e40901_busy")
        val first = case.getValue("reply").jsonObject
        val busyExpect = case.getValue("expect").jsonObject
        val limit = busyExpect.text("maxPostsIn6s").toInt()
        assertEquals("向量写明 6 秒内最多 4 次（D10-887 ⑤）", 4, limit)
        submitReply = { index -> if (index == 0) replyOf(first) else success() }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        // 首次退避不短于 1 秒（iOS 实测 1 / 1.5 / 2 秒）：这里只等主线程处理完回包，不推进时钟
        settle()
        assertTrue("40901 保持在途（按钮锁住）", !submitEnabled())
        assertFalse("40901 不按已实名", realnameVerified())
        assertNull("40901 不出错误条", errorBar())
        idleUntil { advanceAndCheckSecond() }
        val posts = submits()
        assertTrue("自动重发（第 2 次提交）", posts.size >= 2)
        assertEquals("重发沿用原键", posts[0].header("Idempotency-Key"), posts[1].header("Idempotency-Key"))
        assertTrue("重发沿用原请求体", posts[0].body == posts[1].body)
        idleUntil { realnameVerified() }
        assertEquals("重发不重记同意", 1, consents().size)
    }

    /** 等待期间推进时钟（退避在主线程定时器上），第 2 次提交发出就结束。 */
    private fun advanceAndCheckSecond(): Boolean {
        observe(OBSERVE_STEP_MS)
        return submits().size >= 2
    }

    private fun case(name: String): JsonObject = cases.single { it.getValue("name").jsonPrimitive.content == name }

    private fun run(name: String) {
        val case = case(name)
        val expect = case.getValue("expect").jsonObject
        val sequence = case["sequence"]?.jsonArray?.map { it.jsonObject }
        val replies = sequence ?: listOf(case.getValue("reply").jsonObject)
        submitReply = { index -> replies.getOrNull(index)?.let(::replyOf) ?: success() }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        val mesBefore = meGets()
        submitAndAgree(1)
        if (sequence != null) {
            runUnknownSequence(expect)
            return
        }
        observe(OBSERVATION_WINDOW_MS)
        check(expect, mesBefore)
        when (expect["nextSubmit"]?.jsonPrimitive?.content) {
            "new_key" -> assertNextKey(same = false, consentAlreadyOpen = expect.flag("consentReopen"))
            "same_key" -> assertNextKey(same = true, consentAlreadyOpen = expect.flag("consentReopen"))
            "blocked" -> assertBlocked()
            null -> Unit
            else -> error("向量 nextSubmit 取值未知")
        }
    }

    @Suppress("CyclomaticComplexMethod") // 向量 expect 逐项判
    private fun check(
        expect: JsonObject,
        mesBefore: Int,
    ) {
        expect["done"]?.jsonPrimitive?.booleanOrNull?.let { done ->
            assertEquals("done：按已实名放行", done, realnameVerified())
            if (done) assertTrue("成功后出栈，不停在实名页", top()?.name != "RealName")
        }
        expect["refreshMe"]?.jsonPrimitive?.booleanOrNull?.let { if (it) assertTrue("成功后复查 GET /v1/me", meGets() > mesBefore) }
        expect["autoResend"]?.jsonPrimitive?.booleanOrNull?.let { resend ->
            assertEquals("观察窗内实名提交次数（autoResend=$resend）", if (resend) 2 else 1, submits().size)
        }
        val trace6 = expect.flag("trace6")
        if (expect.containsKey("textKey")) {
            val key = expect["textKey"]?.jsonPrimitive?.contentOrNull
            if (key == null) {
                assertNull("没有错误条", errorBar())
            } else {
                assertEquals("错误条文案键 $key", WalletErrors.message(wallet.deps.texts, key, TRACE, trace6), errorBar())
            }
        }
        expect["textKeyNot"]?.jsonArray?.forEach { element ->
            val bar = errorBar()
            assertTrue("textKeyNot：错误条存在", bar != null)
            val dictText = dict(element.jsonPrimitive.content)
            val core = dictText.replace(Regex("\\{[^}]*\\}"), "")
            assertFalse("错误条不是 ${element.jsonPrimitive.content}", core.isNotEmpty() && bar.orEmpty().contains(core))
        }
        if (trace6) assertTrue("错误条带 trace 后 6 位", errorBar().orEmpty().lowercase().contains(TRACE6))
        if (expect.flag("messageClean")) {
            val bar = errorBar().orEmpty()
            assertFalse("整句不留 { } 占位", bar.contains('{') || bar.contains('}'))
            assertFalse("不以「维护中」开头", bar.startsWith("维护中"))
            assertFalse("不留空主语", bar.startsWith("，"))
        }
        checkFields(expect)
        expect["lockedToday"]?.jsonPrimitive?.booleanOrNull?.let { locked ->
            assertEquals("lockedToday：按钮置灰", locked, !submitEnabled())
        }
        if (expect.flag("gateActive")) assertTrue("禁用期生效", wallet.gates.realname.active)
        if (expect.flag("consentReopen")) assertTrue("重开单独同意半屏", has(WalletTags.CONSENT_SHEET))
    }

    /** 字段错误：字段旁提示、不另出错误条；哪个字段由 RealnameFundsControllerTest 钉（同一句文案，Compose 层靠 state 区分）。 */
    private fun checkFields(expect: JsonObject) {
        val fields = expect["fieldErrors"]?.jsonArray ?: return
        val fieldError = dict("error.20001")
        if (fields.isEmpty()) {
            val bar = expect["textKey"]?.jsonPrimitive?.contentOrNull
            assertEquals("没有字段错误", if (bar == "error.20001") 1 else 0, texts(fieldError))
        } else {
            assertEquals("字段错误显示在字段旁（出现次数 = 字段数）", fields.size, texts(fieldError))
        }
    }

    private fun assertNextKey(
        same: Boolean,
        consentAlreadyOpen: Boolean,
    ) {
        val first = submits()[0]
        if (consentAlreadyOpen) {
            toggleAndAgree()
            idleUntil { submits().size >= 2 }
        } else {
            resubmit()
        }
        val second = submits()[1]
        assertKeyShape(second.header("Idempotency-Key"))
        assertEquals(
            "nextSubmit：再次提交沿用原键（same=$same）",
            same,
            first.header("Idempotency-Key") == second.header("Idempotency-Key"),
        )
        // 同键绝不能配不同请求体；同表单沿用同一请求体
        if (same) assertTrue("同键同体", first.body == second.body)
    }

    private fun assertBlocked() {
        val before = submits().size
        val consentsBefore = consents().size
        click(WalletTags.REALNAME_SUBMIT)
        assertFalse("blocked：点了也不开半屏", has(WalletTags.CONSENT_SHEET))
        observe(OBSERVE_STEP_MS)
        assertEquals("blocked：不发实名提交", before, submits().size)
        assertEquals("blocked：不发同意", consentsBefore, consents().size)
    }

    private fun runUnknownSequence(expect: JsonObject) {
        // 第 1 次断连已发出；第 2 次沿用原键
        assertFalse("断连不按已实名", realnameVerified())
        resubmit()
        val posts = submits()
        assertEquals("结果未知后第 2 次沿用原键（前提）", posts[0].header("Idempotency-Key"), posts[1].header("Idempotency-Key"))
        assertTrue("结果未知后请求体不变", posts[0].body == posts[1].body)
        // 等按钮恢复（42901 等 Retry-After 过去）
        observe(RECOVER_WINDOW_MS)
        assertTrue("第 2 次回答后可再提交（前提，不空过）", submitEnabled())
        assertEquals("第 3 次提交前不算成功", expect.flag("done"), realnameVerified())
        resubmit()
        val third = submits()[2]
        val same = expect.getValue("nextSubmit").jsonPrimitive.content == "same_key"
        assertEquals(
            "第 3 次与第 1 次的键（same=$same）",
            same,
            posts[0].header("Idempotency-Key") == third.header("Idempotency-Key"),
        )
        assertTrue("三次请求体一致", posts[0].body == third.body)
        idleUntil { realnameVerified() }
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.flag(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull == true

    private companion object {
        const val VECTORS_SHA256 = "2b3f872080677d36e3965bf57a5af30c9e7e39c5c1212ff34e6b30ce22b85cf9"
        const val CASE_COUNT = 25
        const val RECOVER_WINDOW_MS = 1_500L
        const val TRACE6 = "zz9876"
        val COVERED =
            listOf(
                "ok_verified",
                "e20001_field_id_no",
                "e20001_field_name",
                "e20001_no_fields",
                "e30405_taken",
                "e30406_mismatch",
                "e30407_age",
                "e30410_daily_limit",
                "e42901_rate_limited",
                "e50401_vendor_timeout",
                "e50301_service_unavailable",
                "e50001_server",
                "e10004_id_verification",
                "e10005_bind_phone",
                "e10010_reverify",
                "e10001_login",
                "e10405_force_update",
                "transport_lost",
                "unknown_then_e10405",
                "unknown_then_e50001",
                "unknown_then_e50401",
                "unknown_then_e50301",
                "unknown_then_e42901",
                "unknown_then_e30406",
                "e40901_busy",
            )
    }
}
