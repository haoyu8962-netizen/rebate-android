// 假数据的金额、编号都是演示值（设计稿 sample-data §0、§11、§12.1–12.3），按值直接写
@file:Suppress("MagicNumber")

package com.couli.app

import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorActionMapper
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RealNameBackend
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug 专用的本机假钱包服务（APP-09 设计稿比对与冒烟用；src/debugFeatures，Release 不编译，release-scan 查 [FAKE_WALLET_SERVER_MARKER]）。
 * 与假登录服务同一个开关文件（[DebugFakeLoginServer] 接管时才回答），全部在本机回答，不连任何地址，不做真实提现与真实实名核验。数据照设计稿
 * sample-data §12（可提现 ¥12.34、已提现 ¥5、预估 ¥56.78、已收货 ¥34.56、暂缓 ¥4.56、最早 10 月；提现记录 10 月 ¥10 审核中、¥8 打款未成功，9 月
 * ¥5 已到账、¥6 未通过；收款账号支付宝 138****5678、收款人 **三）。姓名、身份证号、卡号、登录号一律不落盘、不打印。
 *
 * - 签名、step-up、幂等走网络层真实管线，只把传输层换成本服务：createWithdrawal / savePayoutAccount 没带有效 X-Step-Up-Token 回 10003；同一
 *   Idempotency-Key 回同一结果；作废接口按键回 abandoned 或 completed（原结果）；二次验证码 123456 通过，200030 → 20003，其余 → 20002。
 * - 实名与劳务协议（契约没有接口，G01 / G02）：只由本服务在进程内实现（[realname]、[labor]），不经网络管线。
 *
 * 状态开关文件 files/[FAKE_WALLET_STATE_FILE]（每行 key=value）：
 * wallet=normal|frozen|negative|risk|disabled|new（钱包摘要场景）；offline=1（钱包接口传输失败）；realname=none|verified（初值，默认 verified）；
 * payout=none|alipay|bank（初值，默认 alipay）；labor=signed|unsigned（初值，默认 signed）；records=empty|normal；
 * submit=ok|unknown_created|unknown_lost|44001|30303|busy|5xxxx（提现提交场景）；save=ok|unknown_created|30307|44001（收款账号保存场景）；
 * rules=normal|below_min|feature_off|negative（rules 场景）；step_up=always（每次都要二次验证，不复用 token）。
 */
@Suppress("TooManyFunctions", "LargeClass", "CyclomaticComplexMethod", "ReturnCount") // 每个接口一个场景函数；场景逐项
internal class DebugFakeWallet(
    private val stateFile: File,
) {
    private val results = ConcurrentHashMap<String, Pair<Int, String>>()
    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    private val created = mutableListOf<JsonObject>()

    @Volatile private var realnameVerified: Boolean? = null

    @Volatile private var laborSigned: Boolean? = null

    @Volatile private var payout: JsonObject? = null

    @Volatile private var payoutInitialized = false
    private var serial = 0

    /** 回答：(code, data JSON, 响应头)；不是本服务的路径为 null。 */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun answer(
        method: String,
        path: String,
        query: String?,
        body: JsonObject,
        headers: Map<String, String>,
    ): Triple<Int, String, Map<String, String>>? {
        val state = state()
        val mine =
            path.startsWith("/v1/wallet/") || path.startsWith("/v1/withdrawals") || path == "/v1/me/payout-account" ||
                path == "/v1/auth/step-up" || path == "/v1/idempotency-keys/abandon"
        if (!mine) return null
        if (state["offline"] == "1" && path != "/v1/idempotency-keys/abandon") throw IOException("fake offline")
        return when {
            path == "/v1/wallet/summary" -> ok(summary(state["wallet"]).toString())
            path == "/v1/withdrawals/rules" -> ok(rules(state, parse(query)["amount_fen"]?.toLongOrNull()).toString())
            path == "/v1/withdrawals" && method == "POST" -> submit(state, body, headers)
            path == "/v1/withdrawals" -> list(state, parse(query))
            path.startsWith("/v1/withdrawals/") -> detail(URLDecoder.decode(path.removePrefix("/v1/withdrawals/"), "UTF-8"))
            path == "/v1/me/payout-account" && method == "PUT" -> save(state, body, headers)
            path == "/v1/me/payout-account" -> ok((payout(state) ?: JsonObject(emptyMap())).toString())
            path == "/v1/auth/step-up" -> stepUp(body)
            else -> abandon(body)
        }
    }

    /** /v1/me 的 realname_status（假登录服务回答 getMe 时取）。 */
    fun realnameStatus(): String = if (realname(state())) "verified" else "none"

    // ---------------------------------------------------------------- 钱包摘要

    private fun summary(scene: String?): JsonObject {
        val base =
            linkedMapOf<String, JsonElement>(
                "available_fen" to JsonPrimitive(1234),
                "withdrawable_fen" to JsonPrimitive(1234),
                "negative_fen" to JsonPrimitive(0),
                "frozen_fen" to JsonPrimitive(0),
                "estimated_total_fen" to JsonPrimitive(5678),
                "estimated_fen" to JsonPrimitive(2222),
                "pending_credit_fen" to JsonPrimitive(3456),
                "pending_credit_paused_fen" to JsonPrimitive(456),
                "next_credit_period" to JsonPrimitive("2026-10"),
                "credit_overdue" to JsonPrimitive(false),
                "withdrawn_fen" to JsonPrimitive(500),
                "risk_paused_reason" to JsonNull,
                "withdraw_enabled" to JsonPrimitive(true),
            )
        when (scene) {
            "frozen" -> {
                base["available_fen"] = JsonPrimitive(234)
                base["withdrawable_fen"] = JsonPrimitive(234)
                base["frozen_fen"] = JsonPrimitive(1000)
                base["pending_credit_paused_fen"] = JsonPrimitive(0)
            }

            "negative" -> {
                base["available_fen"] = JsonPrimitive(-567)
                base["withdrawable_fen"] = JsonPrimitive(0)
                base["negative_fen"] = JsonPrimitive(567)
                base["pending_credit_paused_fen"] = JsonPrimitive(0)
            }

            "risk" -> {
                base["risk_paused_reason"] = JsonPrimitive("abnormal_trade")
            }

            "disabled" -> {
                base["withdraw_enabled"] = JsonPrimitive(false)
            }

            "new" -> {
                listOf(
                    "available_fen",
                    "withdrawable_fen",
                    "estimated_total_fen",
                    "estimated_fen",
                    "pending_credit_fen",
                    "pending_credit_paused_fen",
                    "withdrawn_fen",
                ).forEach { base[it] = JsonPrimitive(0) }
                base["next_credit_period"] = JsonNull
            }
        }
        return JsonObject(base)
    }

    // ---------------------------------------------------------------- rules

    private fun rules(
        state: Map<String, String>,
        amount: Long?,
    ): JsonObject {
        val block =
            when {
                state["rules"] == "feature_off" -> 30306

                state["rules"] == "negative" -> 30302

                // 风控冻结（G11 编排者更正）：钱包【提现】可点，rules 给 30303 account_frozen
                state["wallet"] == "risk" -> 30303

                !realname(state) -> 30304

                payout(state) == null -> 30305

                !labor(state) -> 10004

                state["rules"] == "below_min" -> 30303

                else -> null
            }
        val can = block == null
        val est = if (can && amount != null) amount else null
        return JsonObject(
            linkedMapOf(
                "can_withdraw" to JsonPrimitive(can),
                "block_code" to (block?.let(::JsonPrimitive) ?: JsonNull),
                "block_reason" to
                    when {
                        block != 30303 -> JsonNull
                        state["wallet"] == "risk" -> JsonPrimitive("account_frozen")
                        else -> JsonPrimitive("below_min")
                    },
                "max_withdrawable_fen" to JsonPrimitive(if (can) 1200 else 0),
                "withdrawable_fen" to JsonPrimitive(if (state["rules"] == "negative") 0 else 1234),
                "daily_remaining" to JsonPrimitive(1),
                "monthly_remaining" to JsonPrimitive(10),
                "est_fee_fen" to (est?.let { JsonPrimitive(0) } ?: JsonNull),
                "est_tax_fen" to (est?.let { JsonPrimitive(0) } ?: JsonNull),
                "est_net_fen" to (est?.let(::JsonPrimitive) ?: JsonNull),
                "quick_amounts_fen" to if (can) JsonArray(listOf(JsonPrimitive(500), JsonPrimitive(1000))) else JsonArray(emptyList()),
            ),
        )
    }

    // ---------------------------------------------------------------- 提交提现

    @Suppress("ReturnCount")
    private fun submit(
        state: Map<String, String>,
        body: JsonObject,
        headers: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        val key = header(headers, "Idempotency-Key") ?: return fail(20001)
        results[key]?.let { (code, data) -> return Triple(code, data, marker()) }
        if (!stepUpPassed(headers, "withdraw", state)) return fail(10003)
        val amount = (body["amount_fen"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return fail(20001)
        val outcome: Pair<Int, String> =
            when (state["submit"]) {
                "44001" -> 44001 to """{"risk_msg_code":"fixture_unlisted_reason"}"""
                "30303" -> 30303 to """{"reason":"daily_count"}"""
                "busy" -> return Triple(42901, "null", marker() + ("Retry-After" to "10"))
                "5xxxx" -> return fail(50001)
                else -> 0 to withdrawal(amount).toString()
            }
        when (state["submit"]) {
            "unknown_created" -> {
                // 服务端已受理、响应丢了：记下结果（作废返回 completed、确认返回原结果）
                results[key] = outcome
                throw IOException("fake response lost")
            }

            "unknown_lost" -> {
                throw IOException("fake request lost")
            }
        }
        results[key] = outcome
        return Triple(outcome.first, outcome.second, marker())
    }

    private fun withdrawal(amount: Long): JsonObject {
        serial += 1
        val item =
            record(
                "W2026101200${100 + serial}",
                "PENDING_REVIEW",
                "manual",
                amount,
                "2026-10-12T10:00:00+08:00",
                reason = null,
                failAction = null,
                channelOrderId = null,
            )
        synchronized(created) { created.add(0, item) }
        return item
    }

    // ---------------------------------------------------------------- 提现记录

    private fun samples(): List<JsonObject> =
        listOf(
            record("W2026101200001", "PENDING_REVIEW", "manual", 1000, "2026-10-12T10:00:00+08:00", null, null, null),
            record(
                "W2026100800003",
                "FAILED",
                "manual",
                800,
                "2026-10-08T11:30:00+08:00",
                "PAYEE_ACCOUNT_STATUS_ERROR",
                "change_payout_account",
                null,
            ),
            record("W2026092700002", "PAID_API", "auto", 500, "2026-09-27T15:20:00+08:00", null, null, "20260928200040011100"),
            record("W2026092500001", "REJECTED", "manual", 600, "2026-09-25T10:00:00+08:00", null, null, null),
        )

    private fun list(
        state: Map<String, String>,
        params: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        val all =
            if (state["records"] ==
                "empty"
            ) {
                synchronized(created) { created.toList() }
            } else {
                synchronized(created) { created.toList() } + samples()
            }
        val page = params["cursor"]?.removePrefix("p")?.toIntOrNull() ?: 0
        val size = PAGE
        val chunk = all.drop(page * size).take(size)
        val more = all.size > (page + 1) * size
        val items = chunk.map { JsonObject(it - "channel_order_id") }
        val data = JsonObject(mapOf("items" to JsonArray(items), "next_cursor" to if (more) JsonPrimitive("p${page + 1}") else JsonNull))
        return ok(data.toString())
    }

    private fun detail(id: String): Triple<Int, String, Map<String, String>> {
        val all = synchronized(created) { created.toList() } + samples()
        val item = all.firstOrNull { (it["withdrawal_id"] as? JsonPrimitive)?.content == id } ?: return fail(30701)
        return ok(item.toString())
    }

    @Suppress("LongParameterList")
    private fun record(
        id: String,
        status: String,
        reviewMode: String?,
        amount: Long,
        createdAt: String,
        reason: String?,
        failAction: String?,
        channelOrderId: String?,
    ): JsonObject =
        JsonObject(
            linkedMapOf(
                "withdrawal_id" to JsonPrimitive(id),
                "status" to JsonPrimitive(status),
                "review_mode" to (reviewMode?.let(::JsonPrimitive) ?: JsonNull),
                "amount_fen" to JsonPrimitive(amount),
                "fee_fen" to JsonPrimitive(0),
                "tax_fen" to JsonPrimitive(0),
                "net_fen" to JsonPrimitive(amount),
                "payout_channel" to JsonPrimitive("alipay"),
                "payout_channel_name" to JsonPrimitive("支付宝"),
                "masked_account" to JsonPrimitive("138****5678"),
                "reason_code" to (reason?.let(::JsonPrimitive) ?: JsonNull),
                "fail_action" to (failAction?.let(::JsonPrimitive) ?: JsonNull),
                "created_at" to JsonPrimitive(createdAt),
                "channel_order_id" to (channelOrderId?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )

    // ---------------------------------------------------------------- 收款账号

    private fun payout(state: Map<String, String>): JsonObject? {
        if (!payoutInitialized) {
            payoutInitialized = true
            payout =
                when (state["payout"]) {
                    "none" -> null
                    "bank" -> bank("招商银行", "1234")
                    else -> alipay("138****5678")
                }
        }
        return payout
    }

    private fun alipay(masked: String) =
        JsonObject(
            linkedMapOf(
                "payout_method" to JsonPrimitive("alipay"),
                "masked_account" to JsonPrimitive(masked),
                "masked_payee_name" to JsonPrimitive("**三"),
                "change_remaining_this_month" to JsonPrimitive(2),
            ),
        )

    private fun bank(
        name: String,
        tail: String,
    ) = JsonObject(
        linkedMapOf(
            "payout_method" to JsonPrimitive("bank_card"),
            "masked_account" to JsonPrimitive("尾号 $tail"),
            "bank_name" to JsonPrimitive(name),
            "masked_payee_name" to JsonPrimitive("**三"),
            "change_remaining_this_month" to JsonPrimitive(2),
        ),
    )

    @Suppress("ReturnCount")
    private fun save(
        state: Map<String, String>,
        body: JsonObject,
        headers: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        val key = header(headers, "Idempotency-Key") ?: return fail(20001)
        results[key]?.let { (code, data) -> return Triple(code, data, marker()) }
        if (!stepUpPassed(headers, "payout_account_change", state)) return fail(10003)
        val method = (body["payout_method"] as? JsonPrimitive)?.content
        val account =
            if (method == "bank_card") {
                val card = (body["card_no"] as? JsonPrimitive)?.content.orEmpty().filter(Char::isDigit)
                if (card.length !in 12..19) return Triple(20001, """{"fields":["card_no"]}""", marker())
                bank((body["bank_name"] as? JsonPrimitive)?.content.orEmpty(), card.takeLast(4))
            } else {
                val logon = (body["alipay_logon_id"] as? JsonPrimitive)?.content.orEmpty()
                alipay(maskLogon(logon))
            }
        val outcome: Pair<Int, String> =
            when (state["save"]) {
                "30307" -> 30307 to "null"
                "44001" -> 44001 to "null"
                else -> 0 to account.toString()
            }
        if (outcome.first == 0) payout = account
        if (state["save"] == "unknown_created") {
            results[key] = outcome
            throw IOException("fake response lost")
        }
        results[key] = outcome
        return Triple(outcome.first, outcome.second, marker())
    }

    private fun maskLogon(logon: String): String {
        val at = logon.lastIndexOf('@')
        if (at > 0) return logon.take(minOf(2, at)) + "***" + logon.substring(at)
        val digits = logon.filter(Char::isDigit)
        return if (digits.length >= 7) digits.take(3) + "****" + digits.takeLast(4) else "***"
    }

    // ---------------------------------------------------------------- 二次验证与作废

    private fun stepUpPassed(
        headers: Map<String, String>,
        action: String,
        state: Map<String, String>,
    ): Boolean {
        val token = header(headers, "X-Step-Up-Token") ?: return false
        val ok = token in validTokens && token.startsWith("fake-step-up-$action-")
        // 一次性：成功提交后核销（step_up=always 时每次都要重新验证）
        if (ok && state["step_up"] == "always") validTokens.remove(token)
        return ok
    }

    private fun stepUp(body: JsonObject): Triple<Int, String, Map<String, String>> {
        val action = (body["action"] as? JsonPrimitive)?.content.orEmpty()
        return when ((body["code"] as? JsonPrimitive)?.content) {
            "123456" -> {
                serial += 1
                val token = "fake-step-up-$action-$serial"
                validTokens += token
                ok("""{"step_up_token":"$token","expire_at":"2099-01-01T00:05:00+08:00"}""")
            }

            "200030" -> {
                fail(20003)
            }

            else -> {
                fail(20002)
            }
        }
    }

    private fun abandon(body: JsonObject): Triple<Int, String, Map<String, String>> {
        val key = (body["idempotency_key"] as? JsonPrimitive)?.content ?: return fail(20001)
        if (state()["abandon"] == "busy") return fail(40901)
        val done = results[key]
        return if (done == null) {
            // 没有受理过：作废，之后同一个键回 20903
            results[key] = 20903 to "null"
            ok("""{"outcome":"abandoned","original":null}""")
        } else {
            ok("""{"outcome":"completed","original":{"code":${done.first},"msg":"","data":${done.second}}}""")
        }
    }

    // ---------------------------------------------------------------- 实名与劳务协议（契约没有接口，只在进程内）

    private fun realname(state: Map<String, String>): Boolean = realnameVerified ?: (state["realname"] != "none")

    private fun labor(state: Map<String, String>): Boolean = laborSigned ?: (state["labor"] != "unsigned")

    /** 假的实名提交：姓名「测试三」+ 任意 18 位通过；身份证号末位 1 → 30406；末位 2 → 30410；姓名为空 → 20001。不落盘、不打印。 */
    val realnameBackend: RealNameBackend =
        object : RealNameBackend {
            override suspend fun submit(
                name: String,
                idNo: String,
                idempotencyKey: String,
            ): ApiResult {
                if (!switchOn()) return ApiResult.TransportFailure("disconnect", null, null, false)
                return when {
                    name.isBlank() -> {
                        failure(20001, JsonObject(mapOf("fields" to JsonArray(listOf(JsonPrimitive("name"))))))
                    }

                    idNo.endsWith("1") -> {
                        failure(30406)
                    }

                    idNo.endsWith("2") -> {
                        failure(30410)
                    }

                    else -> {
                        realnameVerified = true
                        ApiResult.Success(
                            JsonObject(mapOf("realname_status" to JsonPrimitive("verified"))),
                            "fake-trace-realname",
                            idempotencyKey,
                        )
                    }
                }
            }
        }

    val laborBackend: LaborBackend =
        object : LaborBackend {
            override suspend fun current(): ApiResult {
                if (!switchOn()) return ApiResult.TransportFailure("disconnect", null, null, false)
                return ApiResult.Success(
                    JsonObject(
                        mapOf(
                            "version" to JsonPrimitive(LABOR_VERSION),
                            "text" to JsonPrimitive(LABOR_TEXT),
                            "text_sha256" to JsonPrimitive(sha256(LABOR_TEXT)),
                            "need_sign" to JsonPrimitive(!labor(state())),
                        ),
                    ),
                    "fake-trace-labor",
                    null,
                )
            }

            override suspend fun sign(
                version: String,
                textSha256: String,
            ): ApiResult {
                if (!switchOn()) return ApiResult.TransportFailure("disconnect", null, null, false)
                if (version != LABOR_VERSION || textSha256 != sha256(LABOR_TEXT)) return failure(20001)
                laborSigned = true
                return ApiResult.Success(JsonObject(emptyMap()), "fake-trace-labor", null)
            }
        }

    /** 假服务开关（与假登录服务同一个文件）。 */
    var switchOn: () -> Boolean = { true }

    private fun failure(
        code: Int,
        data: JsonObject = JsonObject(emptyMap()),
    ): ApiResult =
        ApiResult.Failure(
            code,
            "",
            data,
            "fake-trace-${"%06d".format(code)}",
            com.couli.core.network.ErrorEffect(
                code,
                com.couli.core.network.ErrorEffectKind.TOAST,
                com.couli.core.network.ReplayMode.NONE,
                false,
            ),
            null,
        )

    // ---------------------------------------------------------------- 工具

    private fun state(): Map<String, String> =
        runCatching {
            stateFile
                .takeIf(File::exists)
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap()
        }.getOrNull().orEmpty()

    private fun parse(query: String?): Map<String, String> =
        query
            ?.split('&')
            ?.filter { it.contains('=') }
            ?.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            .orEmpty()

    private fun header(
        headers: Map<String, String>,
        name: String,
    ): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun marker() = mapOf(MARKER_HEADER to FAKE_WALLET_SERVER_MARKER)

    private fun ok(data: String) = Triple(0, data, marker())

    private fun fail(code: Int) = Triple(code, "null", marker())

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MARKER_HEADER = "X-Couli-Debug-Fake"
        private const val PAGE = 20
        private const val LABOR_VERSION = "v1"

        /** 协议正文（法务定稿前的演示文本，设计稿 LaborAgreement 的占位说明）。 */
        private val LABOR_TEXT =
            "协议正文（待法务提供）\n法务定稿后，全文放在这里，可上下滚动阅读。\n" +
                (1..12).joinToString("\n") { "第 $it 条　演示条款，用于检查长文滚动与读屏完整读到。" }

        /** 进程内唯一（假登录服务与实名、劳务协议的进程内实现共用状态）。 */
        private val instances = mutableMapOf<String, DebugFakeWallet>()

        fun of(stateFile: File): DebugFakeWallet =
            synchronized(instances) {
                instances.getOrPut(stateFile.absolutePath) { DebugFakeWallet(stateFile) }
            }

        /** 42901 的缺省禁用（测试与冒烟对照）。 */
        const val DEFAULT_RETRY_MS = ErrorActionMapper.DEFAULT_RETRY_AFTER_MILLIS
    }
}

/** 假钱包服务的状态开关文件名（与假登录服务的开关同目录）。 */
internal const val FAKE_WALLET_STATE_FILE = "couli_fake_wallet_state.txt"

/** release-scan 查的唯一标记（Release 制品里不得出现）。 */
internal const val FAKE_WALLET_SERVER_MARKER = "COULI_FAKE_WALLET_SERVER_APP09_5c71e4"
