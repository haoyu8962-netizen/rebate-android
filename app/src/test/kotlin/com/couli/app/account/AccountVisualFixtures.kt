package com.couli.app.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.couli.core.designsystem.CouliColors
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * APP-13v 注销视觉的数据：只读新文件 specs/account-visual-vectors.json（G-D15：三端同一份，不往 account-vectors.json 追加）。
 * 只拼报文（getDeletion、钱包摘要）与未决记录种子；不在这里实现任何产品规则（金额格式化、时间格式化都交给产品的 CouliFormat，由用例复算）。
 */
internal object AccountVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "specs/account-vectors.json").isFile }
    val file: File = File(root, "specs/account-visual-vectors.json")
    val data: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
    val clock: String = text("clock")

    fun text(
        name: String,
        from: JsonObject = data,
    ): String = from.getValue(name).jsonPrimitive.content

    fun obj(
        name: String,
        from: JsonObject = data,
    ): JsonObject = from.getValue(name).jsonObject

    fun millis(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** getDeletion 的 data（"null" = 从未申请）。 */
    fun deletion(name: String): String {
        val value = obj("deletion").getValue(name)
        return if (value is JsonNull) "null" else value.toString()
    }

    /** 钱包摘要 data：契约全部字段，三个金额取 fixture，其余为 0 / null / 默认。 */
    fun wallet(name: String): String {
        val w = obj(name, obj("wallet"))
        val withdrawable = w.getValue("withdrawable_fen").jsonPrimitive.long
        val estimated = w.getValue("estimated_total_fen").jsonPrimitive.long
        val negative = w.getValue("negative_fen").jsonPrimitive.long
        val available = if (negative > 0) -negative else withdrawable
        return """{"available_fen":$available,"withdrawable_fen":$withdrawable,"negative_fen":$negative,""" +
            """"frozen_fen":0,"estimated_total_fen":$estimated,"estimated_fen":2222,""" +
            """"pending_credit_fen":0,"pending_credit_paused_fen":0,""" +
            """"next_credit_period":null,"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""
    }

    fun walletFen(
        name: String,
        field: String,
    ): Long = obj(name, obj("wallet")).getValue(field).jsonPrimitive.long

    fun pendingOperation(name: String): String = text("operation", obj(name, obj("pending")))

    fun pendingSummary(name: String): String = text("summary", obj(name, obj("pending")))

    fun pendingCreatedAt(name: String): Long = millis(text("createdAt", obj(name, obj("pending"))))

    fun phone(name: String): String = text(name, obj("phones"))

    fun draft(name: String): String = text(name, obj("drafts"))

    fun kept(name: String): String = text(name, obj("draftsKept"))

    fun drafts(): Map<String, String> = obj("drafts").mapValues { it.value.jsonPrimitive.content }

    fun kept(): Map<String, String> = obj("draftsKept").mapValues { it.value.jsonPrimitive.content }

    fun list(
        name: String,
        from: JsonObject = data,
    ): List<String> = from.getValue(name).jsonArray.map { it.jsonPrimitive.content }

    fun boards(segment: String? = null): List<JsonObject> =
        (data.getValue("boards") as JsonArray)
            .map { it.jsonObject }
            .filter { segment == null || text("segment", it) == segment }

    fun board(name: String): JsonObject = boards().single { text("name", it) == name }

    fun height(board: String): Int = text("height", board(board)).toInt()

    /** 30416 回包里的金额（fen）。 */
    fun amount30416(): Long =
        obj("data", obj("30416", obj("codes")))
            .getValue("amount_fen")
            .jsonPrimitive.long
}

/** 渲染时的令牌色（AccountVisualHarness 在 CouliTheme 里取一次；页面不写色值，测试也只比令牌）。 */
@Suppress("LongParameterList")
class AccountVisualPalette {
    var canvas = Color.Unspecified
    var surface = Color.Unspecified
    var muted = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var link = Color.Unspecified
    var emphasis = Color.Unspecified
    var brand = Color.Unspecified
    var warningText = Color.Unspecified
    var warningBackground = Color.Unspecified
    var pendingText = Color.Unspecified
    var pendingBackground = Color.Unspecified
    var successText = Color.Unspecified
    var successBackground = Color.Unspecified
    var error = Color.Unspecified
    var subtle = Color.Unspecified
    var control = Color.Unspecified
    var scrim = Color.Unspecified
    var buttonPrimary = Color.Unspecified
    var buttonDisabled = Color.Unspecified

    /** text.inverse（品牌实心上的白字 / 白勾；深色仍为白，测试 v3 评审 r1 S2-1）。 */
    var inverse = Color.Unspecified

    @Composable
    @ReadOnlyComposable
    fun Capture() {
        canvas = CouliColors.backgroundCanvas
        surface = CouliColors.backgroundSurface
        muted = CouliColors.backgroundMuted
        primary = CouliColors.textPrimary
        secondary = CouliColors.textSecondary
        link = CouliColors.textLink
        emphasis = CouliColors.brandEmphasis
        brand = CouliColors.brandPrimary
        warningText = CouliColors.statusWarningText
        warningBackground = CouliColors.statusWarningBackground
        pendingText = CouliColors.statusPendingText
        pendingBackground = CouliColors.statusPendingBackground
        successText = CouliColors.statusSuccessText
        successBackground = CouliColors.statusSuccessBackground
        error = CouliColors.statusErrorText
        subtle = CouliColors.borderSubtle
        control = CouliColors.borderControl
        scrim = CouliColors.overlayScrim
        buttonPrimary = CouliColors.buttonPrimaryDefaultBackground
        buttonDisabled = CouliColors.buttonPrimaryDisabledBackground
        inverse = CouliColors.textInverse
    }
}
